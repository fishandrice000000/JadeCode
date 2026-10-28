package com.jadecode.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/**
 * 一份 Session 的 MCP 服务集合；只有配置允许的服务可以启动。
 *
 * <p>构造没有进程或网络副作用。连接时先登记候选，再在锁外执行 IO；关闭可同时标记关闭，
 * 迟到的初始化结果不会重新发布。已发现目录只含不可变数据，不持有 Tool 或对话历史。</p>
 */
public final class McpManager implements AutoCloseable {
    private final Map<String, McpServerConfig> configurations;
    private final Path workspace;
    private final Consumer<String> diagnostics;
    private final Map<String, McpConnection> connections = new LinkedHashMap<>();
    private final Map<String, List<McpToolDescriptor>> discovered = new LinkedHashMap<>();
    private final Set<String> connecting = new HashSet<>();
    private boolean closed;
    private final com.jadecode.sandbox.DockerRuntime sandbox;
    private final String actor;
    private final com.jadecode.safety.PermissionState permissions;
    private final AutoCloseable permissionListener;

    /** 保存可信配置快照；不读取 HOME、环境凭据或启动进程。 */
    public McpManager(Map<String, McpServerConfig> configurations, Path workspace, Consumer<String> diagnostics) {
        this(configurations, workspace, diagnostics, null, "offline");
    }
    /** 正式装配传受限后端；构造仍不启动服务，不能通过服务配置绕回宿主进程。 */
    public McpManager(Map<String, McpServerConfig> configurations, Path workspace, Consumer<String> diagnostics,
            com.jadecode.sandbox.DockerRuntime sandbox, String actor) {
        this(configurations, workspace, diagnostics, sandbox, actor, null);
    }
    /** 生产服务绑定共享权限；版本变化使旧服务退场，下一次连接重新编译只读视图。 */
    public McpManager(Map<String, McpServerConfig> configurations, Path workspace, Consumer<String> diagnostics,
            com.jadecode.sandbox.DockerRuntime sandbox, String actor, com.jadecode.safety.PermissionState permissions) {
        configurations.forEach((name, config) -> {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("MCP server name cannot be empty");
            Objects.requireNonNull(config, "server config");
        });
        this.configurations = Map.copyOf(configurations);
        this.workspace = workspace.toAbsolutePath().normalize();
        this.diagnostics = Objects.requireNonNull(diagnostics);
        this.sandbox = sandbox; this.actor = actor; this.permissions = permissions;
        permissionListener = sandbox != null && permissions != null ? permissions.onChange(this::invalidatePolicy) : null;
    }

    /** 返回已启用名称供 connect_mcp 的 schema 使用，不启动任何服务。 */
    public List<String> availableServers() {
        return configurations.entrySet().stream().filter(e -> e.getValue().enabled()).map(Map.Entry::getKey).sorted().toList();
    }

    /**
     * 按名称连接。重复连接复用；未知/禁用服务明确失败，模型不能提供自己的程序。
     * 连接失败会移除候选并释放资源；只有完整发现成功才对 tools() 可见。
     */
    public List<McpToolDescriptor> connect(String name) {
        return connect(name, ignored -> { });
    }

    /**
     * 连接并在发布前执行宿主命名校验；校验只读且快速，不允许执行 IO。
     * 这样 tools 层可以检查规范化冲突，mcp 包仍不反向依赖 ToolCatalog。
     */
    public List<McpToolDescriptor> connect(String name, Consumer<List<McpToolDescriptor>> validate) {
        McpConnection candidate = null;
        McpServerConfig configuration;
        long revision = permissions == null ? 0 : permissions.snapshot().revision();
        synchronized (this) {
            if (closed) throw new IllegalStateException("MCP manager closed");
            McpServerConfig config = configurations.get(name);
            if (config == null || !config.enabled()) throw new IllegalArgumentException("MCP server is not configured or not enabled");
            if (discovered.containsKey(name)) return discovered.get(name);
            if (connections.containsKey(name) || !connecting.add(name)) throw new IllegalStateException("MCP server is already connecting");
            configuration = config;
        }
        try {
            // 准备 Docker 容器可能阻塞；不持有 manager 锁，关闭仍可立即登记并拒绝迟到结果。
            candidate = new McpConnection(name, configuration, workspace, diagnostics, sandbox, actor, serviceRestrictions(configuration));
            synchronized (this) {
                if (closed || permissions != null && permissions.snapshot().revision() != revision)
                    throw new IllegalStateException("MCP manager closed or permissions changed while preparing the server");
                connections.put(name, candidate);
            }
            List<McpToolDescriptor> found = candidate.connect();
            synchronized (this) {
                if (closed || connections.get(name) != candidate || permissions != null && permissions.snapshot().revision() != revision)
                    throw new IllegalStateException("MCP manager closed or permissions changed");
                validate.accept(found);
                discovered.put(name, found);
                return found;
            }
        } catch (RuntimeException failed) {
            synchronized (this) { connections.remove(name, candidate); discovered.remove(name); }
            if (candidate != null) candidate.close();
            throw failed;
        } finally { synchronized (this) { connecting.remove(name); } }
    }

    /**
     * 服务持有整份只读目录，不能按不透明 RPC 内部文件访问判断规则；保守隐藏所有 READ deny/ask。
     * 工具专属路径限制也收紧服务视图；WRITE 已由整目录只读满足。缺失/祖先/链接目标不能表达时拒绝连接。
     */
    private List<com.jadecode.sandbox.ExecutionPlan.Restriction> serviceRestrictions(McpServerConfig config) {
        if (sandbox == null || permissions == null) return List.of();
        Path root = workspace.resolve(config.cwd()).normalize();
        var result = new ArrayList<com.jadecode.sandbox.ExecutionPlan.Restriction>();
        for (var rule : permissions.snapshot().rules()) if (rule.kind() == com.jadecode.safety.rules.PermissionRule.Kind.PATH
                && rule.effect() != com.jadecode.safety.rules.PermissionRule.Effect.ALLOW && rule.operation().equals("READ")
                && (rule.workspace().isEmpty() || Path.of(rule.workspace()).equals(workspace))) {
            Path path = Path.of(rule.path()).normalize();
            if (!path.startsWith(root) && !root.startsWith(path)) continue;
            try {
                if (!path.startsWith(root) || !java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        || !path.toRealPath().equals(path)) throw new IllegalStateException("Cannot express MCP path protection " + rule.id());
            } catch (java.io.IOException unavailable) { throw new IllegalStateException("Cannot resolve MCP path protection " + rule.id(), unavailable); }
            result.add(new com.jadecode.sandbox.ExecutionPlan.Restriction(path, true));
        }
        return List.copyOf(result);
    }

    /** 先撤下目录阻止新调用，再停止原服务；正在准备的连接通过版本检查拒绝发布。 */
    private void invalidatePolicy() {
        List<McpConnection> owned;
        synchronized (this) { owned = List.copyOf(connections.values()); connections.clear(); discovered.clear(); }
        owned.forEach(McpConnection::revoke);
    }

    /**
     * 一个服务的只读展示快照。只含名称/启用/连接状态/工具名，不含启动命令、env、凭据或
     * 错误详情；0 个工具的已连接服务仍标记 connected=true，可与“配置但未连接”区分。
     */
    public record ServerSnapshot(String name, boolean enabled, boolean connected, List<String> tools) {
        public ServerSnapshot { tools = tools == null ? List.of() : List.copyOf(tools); }
    }

    /**
     * 纯内存快照，供 UI 只读显示。锁内复制，不触发连接/discovery/网络，也不暴露凭据。
     * 已关闭时仍报告配置和最后已知连接状态，但不重新连接。
     */
    public synchronized List<ServerSnapshot> snapshot() {
        var result = new ArrayList<ServerSnapshot>();
        for (var entry : configurations.entrySet()) {
            String name = entry.getKey();
            boolean connected = connections.containsKey(name) || discovered.containsKey(name);
            List<String> toolNames = discovered.getOrDefault(name, List.of()).stream()
                    .map(McpToolDescriptor::name).toList();
            result.add(new ServerSnapshot(name, entry.getValue().enabled(), connected && !closed, toolNames));
        }
        result.sort(java.util.Comparator.comparing(ServerSnapshot::name));
        return List.copyOf(result);
    }

    /** 给工具目录本次请求使用的完整数据快照；已关闭时不再暴露可用工具。 */
    public synchronized List<McpToolDescriptor> tools() {
        return closed ? List.of() : discovered.values().stream().flatMap(List::stream).toList();
    }

    /** 根据原始身份路由，不解析模型可见前缀；连接代次由连接本身再次核对。 */
    public McpCallResult call(McpToolDescriptor descriptor, ObjectNode arguments) {
        McpConnection connection;
        synchronized (this) { connection = closed ? null : connections.get(descriptor.serverName()); }
        if (connection == null) return McpCallResult.failure("Server is not connected or the session is closed");
        McpCallResult result = connection.call(descriptor, arguments);
        // 传输失败撤下这条连接。服务返回的普通 isError 不触发这里，模型仍可修正参数继续调用。
        if (!connection.isUsable()) {
            synchronized (this) {
                if (connections.remove(descriptor.serverName(), connection)) discovered.remove(descriptor.serverName());
            }
            connection.close();
        }
        return result;
    }

    /** 先禁止新连接并撤下目录，再在锁外关闭，避免其它线程等待锁而无法结束。 */
    @Override public void close() {
        List<McpConnection> owned;
        synchronized (this) {
            if (closed) return;
            closed = true; owned = List.copyOf(connections.values()); connections.clear(); discovered.clear();
        }
        if (permissionListener != null) try { permissionListener.close(); }
        catch (Exception failure) { diagnostics.accept("[MCP] permission listener cleanup failed"); }
        owned.forEach(McpConnection::close);
    }
}
