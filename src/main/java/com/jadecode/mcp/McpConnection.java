package com.jadecode.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Json;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 一条真实 stdio 连接，SDK 类型集中在这里；只由 McpManager 持有。
 *
 * <p>Fetch 是独立进程。SDK 负责 JSON-RPC 与初始化，本类通过 transport 扩展点限制
 * 进程目录、环境和消息大小。连接失败不能留下半份目录；外部异常只返回类别，不回显参数或原始报文。</p>
 */
final class McpConnection implements AutoCloseable {
    static {
        // SDK 默认日志可能包含原始协议行和启动参数。生产默认仅使用下方受控诊断；显式调试设置仍由宿主决定。
        String logger = "org.slf4j.simpleLogger.log.io.modelcontextprotocol";
        if (System.getProperty(logger) == null) System.setProperty(logger, "off");
    }
    private static final Set<String> ENVIRONMENT = Set.of("PATH", "HOME", "USER", "LOGNAME", "TMPDIR", "TEMP", "TMP",
            "SystemRoot", "WINDIR", "PATHEXT", "LANG", "LC_ALL", "LC_CTYPE", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY",
            "NO_PROXY", "http_proxy", "https_proxy", "all_proxy", "no_proxy");
    private final String serverName;
    private final String id = UUID.randomUUID().toString();
    private final McpServerConfig config;
    private final McpSyncClient client;
    private final Consumer<String> diagnostics;
    private volatile boolean closed;
    private volatile boolean broken;
    private volatile List<McpToolDescriptor> tools = List.of();

    /** 保存配置并构造 SDK，尚未启动外部进程。工作区由工厂确定，不能来自模型参数。 */
    McpConnection(String serverName, McpServerConfig config, Path workspace, Consumer<String> diagnostics) {
        this.serverName = serverName; this.config = config; this.diagnostics = diagnostics;
        Path cwd = workspace.resolve(config.cwd()).normalize();
        String command = config.command();
        // 裸程序名保留 PATH 查找；包含路径分隔符的相对程序按工作区解析，搬迁后重新计算。
        if (!Path.of(command).isAbsolute() && (command.contains("/") || command.contains("\\")))
            command = workspace.resolve(command).normalize().toString();
        var parameters = ServerParameters.builder(command).args(config.args()).build();
        var transport = new StdioClientTransport(parameters, new JacksonMcpJsonMapper(Json.mapper().copy()), 1_048_576) {
            /** 只改进程启动设置，通信读写仍使用 SDK；绝不继承模型认证或所有环境变量。 */
            @Override protected ProcessBuilder getProcessBuilder() {
                if (closed) throw new IllegalStateException("MCP connection closed");
                ProcessBuilder builder = super.getProcessBuilder().directory(cwd.toFile());
                builder.environment().clear();
                for (String name : ENVIRONMENT) {
                    String value = System.getenv(name);
                    if (value != null) builder.environment().put(name, value);
                }
                builder.environment().put("PYTHONIOENCODING", "utf-8");
                builder.environment().put("PYTHONUNBUFFERED", "1");
                return builder;
            }
        };
        // stderr 是诊断通道，不属于模型正文。仅报告出现诊断及长度，避免日志把 URL/凭据带回聊天。
        AtomicInteger lines = new AtomicInteger();
        transport.setStdErrorHandler(line -> {
            if (lines.getAndIncrement() < 8) diagnostics.accept("[MCP " + serverName + "] server stderr (" + line.length() + " chars)");
        });
        client = McpClient.sync(transport).clientInfo(new McpSchema.Implementation("jadecode", "1.0.0"))
                .initializationTimeout(Duration.ofSeconds(config.startupTimeoutSeconds()))
                .requestTimeout(Duration.ofSeconds(config.callTimeoutSeconds()))
                .capabilities(McpSchema.ClientCapabilities.builder().build()).build();
    }

    /**
     * 初始化并分页发现，整个过程受启动期限约束。局部列表只在全部成功后发布。
     * 不支持的能力不声明；服务不能反过来要求宿主调用模型或收集用户秘密。
     */
    List<McpToolDescriptor> connect() {
        // 虚拟线程仅隔离可阻塞的 SDK 初始化；超时取消等待并关闭连接，不自动重试。
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<List<McpToolDescriptor>> pending = executor.submit(() -> {
                ensureOpen(); client.initialize();
                if (client.getServerCapabilities().tools() == null) throw new IllegalArgumentException("Server has no tools capability");
                List<McpToolDescriptor> found = new ArrayList<>();
                Set<String> names = new HashSet<>(), cursors = new HashSet<>();
                String cursor = null;
                do {
                    ensureOpen();
                    var page = client.listTools(cursor);
                    for (var tool : page.tools()) {
                        if (found.size() >= 256 || !names.add(tool.name())) throw new IllegalArgumentException("Invalid tool count or name");
                        ObjectNode schema = Json.mapper().valueToTree(tool.inputSchema());
                        found.add(new McpToolDescriptor(serverName, id, tool.name(), tool.description(), schema,
                                config.readOnlyTools().contains(tool.name())));
                    }
                    cursor = page.nextCursor();
                    if (cursor != null && !cursors.add(cursor)) throw new IllegalArgumentException("Duplicate tool pagination cursor");
                } while (cursor != null);
                return List.copyOf(found);
            });
            try {
                List<McpToolDescriptor> found = pending.get(config.startupTimeoutSeconds(), TimeUnit.SECONDS);
                ensureOpen(); tools = found; return found;
            } catch (InterruptedException interrupted) {
                pending.cancel(true); Thread.currentThread().interrupt();
                throw new IllegalStateException("MCP initialization was interrupted");
            } catch (ExecutionException | TimeoutException failed) {
                pending.cancel(true);
                throw new IllegalStateException("MCP initialization/discovery failed (" + failed.getClass().getSimpleName() + "); check installation, configuration and timeouts");
            }
        } finally { executor.shutdownNow(); }
    }

    /**
     * 仅调用本连接已经发现的工具；正文上限和错误状态在离开 SDK 边界前处理。
     * 参数转成独立 Map，既不修改模型 JSON，也不将 SDK 对象交给上层。
     */
    McpCallResult call(McpToolDescriptor descriptor, ObjectNode input) {
        if (!isUsable() || !id.equals(descriptor.connectionId()) || tools.stream().noneMatch(t -> t.equals(descriptor)))
            return McpCallResult.failure("Tool belongs to a closed or different connection; reconnect and discover again");
        try {
            Map<String, Object> arguments = Json.mapper().convertValue(input, new TypeReference<>() { });
            var result = client.callTool(McpSchema.CallToolRequest.builder(descriptor.name()).arguments(arguments).build());
            StringBuilder text = new StringBuilder();
            for (var block : result.content()) {
                if (!(block instanceof McpSchema.TextContent value))
                    return McpCallResult.failure("Only text content is supported; the server returned another content type");
                // 在追加前限制总量，不把超限内容无标记截断后当成完整结果。
                if ((long) text.length() + value.text().length() + (text.isEmpty() ? 0 : 1) > config.maxResultChars())
                    return McpCallResult.failure("Result exceeds the character limit; reduce max_length or read in segments");
                if (!text.isEmpty()) text.append('\n');
                text.append(value.text());
            }
            if (result.structuredContent() != null)
                return McpCallResult.failure("structuredContent is not supported; use a text tool");
            return new McpCallResult(text.toString(), Boolean.TRUE.equals(result.isError()));
        } catch (RuntimeException failed) {
            broken = true;
            // SDK 异常可能带原始报文，不将 getMessage() 直接暴露给模型或界面。
            return McpCallResult.failure("Call returned no result (" + failed.getClass().getSimpleName() + "); it may have timed out or disconnected and will not be replayed automatically");
        }
    }

    /** 检查逻辑关闭标志，不假称能撤销服务已经做出的外部副作用。 */
    private void ensureOpen() { if (closed) throw new IllegalStateException("MCP connection closed"); }

    /** 只有可用连接继续参与目录与路由；传输失败后不偷偷重连或重放。 */
    boolean isUsable() { return !closed && !broken; }

    /** SDK 的限时 graceful close 负责 TERM 与线程释放；失败明确报告，不假称进程已经退出。 */
    @Override public void close() {
        closed = true;
        try {
            if (!client.closeGracefully()) diagnostics.accept("[MCP " + serverName + "] close was not confirmed; check the server process");
        } catch (RuntimeException failed) {
            diagnostics.accept("[MCP " + serverName + "] close failed (" + failed.getClass().getSimpleName() + ")");
        }
    }
}
