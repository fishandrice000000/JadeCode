package com.jadecode.sandbox;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.process.ShellCommand;
import com.jadecode.safety.*;
import com.jadecode.safety.approval.*;
import com.jadecode.safety.checks.ShellPermissionCheck;
import com.jadecode.safety.rules.PermissionRule;
import com.jadecode.tools.*;
import com.jadecode.util.*;

/**
 * 一个 Agent 的任务授权账本和工具适配器。模型只调用工具，Harness 决定申请范围和容器复用。
 * 目录级候选由明确任务路径或同仓库第二个不同文件触发；候选本身不授予权限。
 * 调用级许可通过 ExecutionContext 派生后交给后台，不能污染共享 PermissionState。
 */
public final class SandboxSession {
    private record Grant(Path path, boolean write, long revision, String tool) { }
    private final String actor;
    private final Path workspace, dataRoot;
    private final PermissionState state;
    private final PermissionGate gate;
    private final ApprovalCoordinator approvals;
    private final DockerRuntime runtime;
    private final PathResolver resolver;
    private final Map<String, List<Grant>> grants = new HashMap<>();
    private final Map<String, Set<Path>> visits = new HashMap<>();
    private final Set<String> declined = new HashSet<>();
    /** 工厂注入真实身份和专属数据根；dataRoot 中只有精确的内部可读产物可挂载。 */
    public SandboxSession(String actor, Path workspace, Path dataRoot, PermissionState state, PermissionGate gate,
            ApprovalCoordinator approvals, DockerRuntime runtime) {
        this.actor = actor; this.workspace = workspace.toAbsolutePath().normalize(); this.dataRoot = dataRoot.toAbsolutePath().normalize();
        this.state = state; this.gate = gate; this.approvals = approvals; this.runtime = runtime; resolver = new PathResolver(workspace);
    }
    /**
     * 代理定义和原工具完全共用，只有执行位置改变。Shell 的联网/额外路径是权限需求声明，
     * 经过审批后才转成挂载，不接收 Docker flags、用户身份或认证环境。
     */
    public Tool wrap(Tool delegate) {
        return new Tool() {
            public String name() { return delegate.name(); }
            public String description() { return delegate.description(); }
            public Map<String, String> properties() { return delegate.properties(); }
            public List<String> required() { return delegate.required(); }
            public ObjectNode inputSchema() {
                ObjectNode schema = delegate.inputSchema().deepCopy();
                if (name().equals("bash")) {
                    ObjectNode properties = (ObjectNode) schema.path("properties");
                    properties.set("network_access", Json.mapper().createObjectNode().put("type", "boolean")
                            .put("description", "Request outbound network for this command; requires separate approval and does not provide credentials."));
                    var access = Json.mapper().createObjectNode().put("type", "array");
                    var item = ToolSchema.objectSchema(Map.of("path", "string", "write", "boolean"), List.of("path"));
                    access.set("items", item); properties.set("access_paths", access);
                }
                return schema;
            }
            public String execute(ObjectNode input) { return "Error: Sandbox tools require a Harness execution context"; }
            public String execute(ObjectNode input, ExecutionContext context) { return executeOutcome(input, context).content(); }
            public ToolOutcome executeOutcome(ObjectNode input, ExecutionContext context) {
                try {
                    var checked = gate.consume(name(), input, context);
                    // 范围审批也可能等待；后续只用 Gate 保存的副本，不让等待中的原参数变化混入计划。
                    ObjectNode exact = checked.input();
                    if (state.snapshot().profile() == PermissionProfile.FULL_ACCESS) return delegate.executeOutcome(exact, context);
                    ExecutionPlan plan = prepare(name(), exact, context, checked);
                    if (!input.equals(exact)) throw new IOException("Tool parameters changed during sandbox approval; re-issue the operation");
                    if (name().equals("bash")) {
                        // 后台线程可能晚于父任务终态才运行；先登记已接收资格，不能依赖线程调度速度。
                        boolean background = exact.path("run_in_background").asBoolean();
                        if (background) runtime.acceptBackground(plan);
                        ToolOutcome result = null;
                        try { result = delegate.executeOutcome(exact, context.withSandboxPlan(plan)); return result; }
                        finally { if (background && (result == null || result.isError())) runtime.discardBackground(plan); }
                    }
                    var result = runtime.execute(plan, 120, () -> context.isCancelled() || plan.revision() != state.snapshot().revision());
                    return new ToolOutcome(result.content(), result.error());
                } catch (Exception failure) { return new ToolOutcome("Error: " + failure.getMessage(), true); }
            }
        };
    }
    /** 同步和后台 Shell 使用同一入口；空许可在受限模式下必须失败。 */
    public ShellCommand.Runner shellRunner() {
        return (command, cwd, timeout, context) -> {
            ExecutionPlan plan = context == null ? null : context.sandboxPlan();
            if (context == null || context.isCancelled()) {
                if (plan != null) runtime.discardBackground(plan);
                return new ShellCommand.Result("Error: Missing or cancelled execution context", null, false);
            }
            if (plan == null && state.snapshot().profile() == PermissionProfile.FULL_ACCESS) {
                try (var host = new ShellCommand(cwd, timeout)) { return host.run(command, context); }
            }
            if (plan == null || !plan.actor().equals(actor(context)) || !plan.runId().equals(context.id())
                    || plan.revision() != state.snapshot().revision() || !command.equals(plan.input().path("command").asText())) {
                if (plan != null) runtime.discardBackground(plan);
                return new ShellCommand.Result("Error: Shell execution permit is missing or stale", null, false);
            }
            // 创建容器或排队也可能等待；真正启动与执行期间仍观察权限版本，不能只在 prepare 检查一次。
            var value = runtime.execute(plan, timeout, () -> context.isCancelled() || plan.revision() != state.snapshot().revision());
            return new ShellCommand.Result(value.rawOutput(), value.exitCode(), value.truncated());
        };
    }
    /** 收集确定资源与显式额外需求；普通脚本内部访问由内核拒绝，不靠猜测 stderr 自动扩权。 */
    private ExecutionPlan prepare(String tool, ObjectNode input, ExecutionContext context, PermissionGate.CheckedCall checked) throws IOException {
        if (workspace.startsWith(dataRoot) || dataRoot.startsWith(workspace))
            throw new IOException("Sandbox workspace and Harness data must be separate directories");
        if (workspace.getNameCount() < 2 || workspace.equals(Path.of(System.getProperty("user.home"))))
            throw new IOException("Choose a project directory rather than a filesystem root or the whole HOME as sandbox workspace");
        long revision = state.snapshot().revision();
        boolean readOnly = state.snapshot().profile() == PermissionProfile.READ_ONLY;
        boolean network = false, reusable = !input.path("run_in_background").asBoolean();
        var needed = new LinkedHashMap<Path, Boolean>();
        if (Set.of("read_file", "write_file", "edit_file").contains(tool)) {
            if (!input.path("path").isTextual()) throw new IOException("A textual file path is required");
            needed.put(resolver.resolve(input.path("path").asText()), !tool.equals("read_file"));
        } else if (tool.equals("glob")) {
            needed.put(resolver.resolve(input.path("root").asText(".")), false);
        } else if (tool.equals("bash")) {
            var analysis = new ShellPermissionCheck(resolver).analyze(tool, input);
            for (var resource : analysis.resources()) needed.merge(resource.path(), !resource.operation().equals("READ"), Boolean::logicalOr);
            if (input.has("network_access") && !input.path("network_access").isBoolean()) throw new IOException("network_access must be boolean");
            network = input.path("network_access").asBoolean(false);
            if (input.has("access_paths")) {
                if (!input.path("access_paths").isArray()) throw new IOException("access_paths must be an array");
                for (JsonNode item : input.path("access_paths")) {
                    if (!item.isObject() || !item.path("path").isTextual() || item.has("write") && !item.path("write").isBoolean())
                        throw new IOException("Each access_paths entry requires a path and optional boolean write");
                    needed.merge(resolver.resolve(item.path("path").asText()), item.path("write").asBoolean(), Boolean::logicalOr);
                }
            }
        }
        // 用户任务已经明确外部目录时，在首次 rg/glob/脚本前主动申请，避免等待模型选对工具。
        for (Path directory : declaredDirectories(context)) if (!directory.startsWith(workspace)) needed.putIfAbsent(directory, false);
        var mounts = new LinkedHashMap<Path, ExecutionPlan.Mount>();
        mounts.put(workspace, new ExecutionPlan.Mount(workspace, workspace, !readOnly));
        // 已授权任务目录对后续逐文件调用持续生效；不要求模型选择复用容器。
        for (var grant : grants.getOrDefault(scope(context), List.of())) if (grant.revision == revision && applies(grant, tool))
            mounts.put(grant.path, new ExecutionPlan.Mount(grant.path, grant.path, grant.write));
        for (var entry : needed.entrySet()) {
            Path target = entry.getKey(); boolean write = entry.getValue();
            if (target.equals(Path.of("/dev/null"))) continue;
            boolean protectedPath = PathProtection.sensitive(target);
            boolean internal = !write && gate.internalReadable(target);
            if (target.startsWith(dataRoot) && !internal) throw new IOException("Harness configuration and runtime state cannot be mounted into a work container");
            if (target.startsWith(workspace) && !protectedPath && (!readOnly || !write)) continue;
            Grant existing = grants.getOrDefault(scope(context), List.of()).stream()
                    .filter(g -> g.revision == revision && applies(g, tool) && target.startsWith(g.path) && (!write || g.write) && !protectedPath).findFirst().orElse(null);
            if (existing != null) continue;
            Path source = target;
            // 新文件需要父目录写入；审批必须显示真实目录范围，不能冒充“只写单文件”。
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                if (!write) throw new IOException("Requested input does not exist: " + target);
                source = source.getParent();
                while (source != null && !Files.exists(source)) source = source.getParent();
            }
            if (source == null || source.getParent() == null) throw new IOException("Refuse a filesystem-root mount grant");
            source = source.toRealPath();
            if (Files.isDirectory(source) && (source.getNameCount() < 2 || source.equals(Path.of(System.getProperty("user.home")))))
                throw new IOException("A broad filesystem or HOME directory cannot be granted; choose a narrower project path");
            boolean task = false;
            // 已存在的精确 PATH allow 本身就是用户授权；只在其实际范围内落实，不再次弹相同问题。
            PermissionRule allowed = !protectedPath && !checked.explicitlyApproved() ? pathAllowance(tool, target, write) : null;
            boolean configured = allowed != null && (allowed.recursive() && Files.isDirectory(Path.of(allowed.path())) || source.equals(target));
            if (configured && allowed.recursive()) { source = Path.of(allowed.path()).toRealPath(); task = true; }
            else if (!configured && !write && !protectedPath && !internal && !target.startsWith(workspace)) {
                Path candidate = candidate(source, context);
                if (candidate != null && !declined.contains(scope(context) + candidate)) { source = candidate; task = true; }
            }
            if (source.getParent() == null || Files.isDirectory(source) && (source.getNameCount() < 2
                    || source.equals(Path.of(System.getProperty("user.home"))) || dataRoot.startsWith(source)))
                throw new IOException("The selected scope is too broad; choose a project directory");
            if (!internal) {
                // 精确文件已获 Gate 批准时直接落实；扩大目录或 Shell 额外需求仍需范围审批。
                boolean alreadyApproved = checked.explicitlyApproved() && Set.of("read_file", "write_file", "edit_file").contains(tool)
                        && source.equals(target) && !Files.isDirectory(source);
                if (!alreadyApproved && !configured) approve(tool, input, context, source, write, task, false, revision);
                if (task) grants.computeIfAbsent(scope(context), ignored -> new ArrayList<>()).add(new Grant(source, write, revision, configured ? allowed.tool() : "*"));
                else reusable = false;
            } else reusable = false;
            mounts.put(source, new ExecutionPlan.Mount(source, source, write));
        }
        if (network) { approve(tool, input, context, null, false, false, true, revision); reusable = false; }
        if (revision != state.snapshot().revision() || context.isCancelled()) throw new IOException("Sandbox permissions changed; re-issue the operation");
        var restrictions = restrictions(tool, input, checked, mounts.values());
        if (checked.explicitlyApproved()) reusable = false;
        return new ExecutionPlan(actor(context), context.id(), revision, tool, input, workspace, readOnly, network, reusable,
                List.copyOf(mounts.values()), restrictions);
    }
    /** 用户明确目录优先；否则同一仓库第二个不同文件触发目录级候选，仍须明确审批。 */
    private Path candidate(Path target, ExecutionContext context) throws IOException {
        for (Path path : declaredDirectories(context)) if (target.startsWith(path) && !path.equals(workspace)) return path;
        Path repository = Files.isDirectory(target) ? target : target.getParent();
        while (repository != null && repository.getNameCount() >= 2 && !Files.exists(repository.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) repository = repository.getParent();
        if (repository == null || repository.getNameCount() < 2) return null;
        Set<Path> seen = visits.computeIfAbsent(scope(context) + "/" + repository, ignored -> new HashSet<>());
        seen.add(target); return seen.size() >= 2 ? repository : null;
    }
    /** 绝对目录只作为候选，不构成批准；固定使用任务 goal，不解析网页或工具输出中的指令。 */
    private List<Path> declaredDirectories(ExecutionContext context) throws IOException {
        var matcher = Pattern.compile("(?:^|[\\s`\"'])((?:/)[^\\s`\"'，。；：]+)").matcher(context.goal());
        var directories = new ArrayList<Path>();
        while (matcher.find()) {
            Path path;
            try { path = Path.of(matcher.group(1)); } catch (InvalidPathException bad) { continue; }
            if (Files.isDirectory(path)) {
                path = path.toRealPath();
                if (path.getNameCount() >= 2 && !path.startsWith(dataRoot) && !PathProtection.sensitive(path)) directories.add(path);
            }
        }
        return List.copyOf(directories);
    }
    /** 审批原操作和实际范围；任务来源与版本来自 Harness，模型的说明不是授权。 */
    private void approve(String tool, ObjectNode input, ExecutionContext context, Path path, boolean write,
            boolean task, boolean network, long revision) throws IOException {
        var display = Json.mapper().createObjectNode().put("operation_tool", tool).put("actor", actor(context))
                .put("scope", task ? "CURRENT_TASK" : "THIS_CALL").put("access", network ? "NETWORK_BRIDGE" : write ? "READ_WRITE" : "READ_ONLY");
        if (path != null) display.put("host_path", path.toString()).put("container_path", path.toString());
        display.set("operation", input.deepCopy());
        String reason = network ? "Allow network for this operation; bridge access is not a domain/private-network allowlist and provides no credentials"
                : (task ? "Read this directory throughout the current task" : "Expose this exact source for this operation") + ": " + path;
        var request = new ApprovalRequest(UUID.randomUUID().toString(), actor(context), context.id(), "sandbox_access", display, workspace, reason, revision, null);
        Approval human = context.source() == ExecutionContext.Source.USER ? context.approval() : null;
        var answer = approvals.request(request, PermissionDecision.ask("sandbox.scope", reason, write || network || path != null && PathProtection.sensitive(path)),
                state, human, context.goal()).answer();
        if (answer != ApprovalAnswer.ALLOW_ONCE || state.snapshot().revision() != revision || context.isCancelled()) {
            if (task) declined.add(scope(context) + path);
            throw new IOException("Sandbox scope was not authorized; no container operation was executed");
        }
        if (path != null && !path.toRealPath().equals(path)) throw new IOException("Sandbox grant target changed during approval");
    }
    /**
     * 将显式路径 deny/ask 编译为内核挂载保护；原工具解析看不到的脚本内部访问仍受限。
     * 已人工批准的精确文件操作可解除本次 ask，deny 永不解除。缺失路径/可变链接拒绝执行。
     */
    private List<ExecutionPlan.Restriction> restrictions(String tool, ObjectNode input, PermissionGate.CheckedCall checked,
            Collection<ExecutionPlan.Mount> mounts) throws IOException {
        var result = new ArrayList<ExecutionPlan.Restriction>();
        for (var rule : state.snapshot().rules()) if (rule.kind() == PermissionRule.Kind.PATH
                && rule.effect() != PermissionRule.Effect.ALLOW && (rule.tool().equals("*") || rule.tool().equals(tool))
                && (rule.workspace().isEmpty() || Path.of(rule.workspace()).equals(workspace))) {
            Path denied = Path.of(rule.path()).normalize();
            if (mounts.stream().noneMatch(m -> denied.startsWith(m.source()) || m.source().startsWith(denied))) continue;
            if (rule.effect() == PermissionRule.Effect.ASK && checked.explicitlyApproved()
                    && Set.of("read_file", "write_file", "edit_file").contains(tool)
                    && resolver.resolve(input.path("path").asText()).equals(denied) && !rule.recursive()) continue;
            if (!Files.exists(denied, LinkOption.NOFOLLOW_LINKS) || !denied.toRealPath().equals(denied)
                    || mounts.stream().noneMatch(m -> denied.startsWith(m.source())))
                throw new IOException("Cannot safely express missing, ancestor or symbolic-link path rule " + rule.id() + " in this Docker view");
            result.add(new ExecutionPlan.Restriction(denied, rule.operation().equals("READ")));
        }
        return List.copyOf(result);
    }
    /** 工具专属长期规则不能因为缓存为任务授权而被其它工具继承。 */
    private static boolean applies(Grant grant, String tool) { return grant.tool.equals("*") || grant.tool.equals(tool); }
    /** 选取覆盖该目标的最窄 PATH allow；命令前缀/整工具 allow 不自动成为目录挂载许可。 */
    private PermissionRule pathAllowance(String tool, Path target, boolean write) {
        return state.snapshot().rules().stream().filter(r -> r.kind() == PermissionRule.Kind.PATH && r.effect() == PermissionRule.Effect.ALLOW
                && (r.tool().equals("*") || r.tool().equals(tool)) && (r.workspace().isEmpty() || Path.of(r.workspace()).equals(workspace))
                && r.operation().equals(write ? "WRITE" : "READ") && (r.recursive() ? target.startsWith(Path.of(r.path())) : target.equals(Path.of(r.path()))))
                .max(Comparator.comparingInt(r -> Path.of(r.path()).getNameCount())).orElse(null);
    }
    /** 真实任务终态撤销临时授权；历史恢复不能恢复这份内存账本。 */
    public void endTask(String runId) {
        grants.keySet().removeIf(key -> key.startsWith(runId + "/")); visits.keySet().removeIf(key -> key.startsWith(runId + "/"));
        declined.removeIf(key -> key.startsWith(runId)); runtime.endTask(actor, runId);
    }
    /** 子代理结束只撤销它的委派范围，不改变父 run 的来源或其它成员的任务。 */
    public void endScope(ExecutionContext context) {
        grants.remove(scope(context)); visits.keySet().removeIf(key -> key.startsWith(scope(context) + "/"));
        declined.removeIf(key -> key.startsWith(scope(context))); runtime.endTask(actor(context), context.id());
        runtime.endActor(actor(context));
    }
    /** 授权账本同时绑定真实 run 与一次委派编号，不能仅用父任务 ID 混合子代理。 */
    private String scope(ExecutionContext context) { return context.id() + "/" + context.actorScope(); }
    /** 展示和物理容器归属使用同一身份，不接受模型正文提供的名字。 */
    private String actor(ExecutionContext context) { return actor + (context.actorScope().isEmpty() ? "" : "/" + context.actorScope()); }
}
