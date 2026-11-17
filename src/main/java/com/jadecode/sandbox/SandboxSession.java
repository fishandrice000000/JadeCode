package com.jadecode.sandbox;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
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
 * 把一个 Agent 的工具授权转换为可执行的容器计划。记录当前轮次已批准的外部路径，
 * 结合权限档位、派工范围、显式路径和网络申请，编译挂载及限制；
 * 包装后的工具将计划交给 DockerRuntime 或 Shell runner 执行。
 *
 * @see ExecutionPlan
 * @see DockerRuntime
 * @see com.jadecode.safety.PermissionGate
 */
public final class SandboxSession {
    /**
     * 保存某轮任务已经获得的路径授权，包括读写方式、权限版本和工具名，供后续计划编译查找相同范围。
     *
     * @param path 本次申请或核对的真实挂载来源；网络申请时为 null
     * @param write 本次资源访问是否需要写入
     * @param revision 本次操作依据的版本号，用于读取后修改时核对状态
     * @param tool 模型调用的工具注册名
     */
    private record Grant(Path path, boolean write, long revision, String tool) {
    }

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

    /**
     * 将队友的范围扩展请求路由给 Leader，取得允许一次、拒绝或转交用户的决定。
     */
    @FunctionalInterface
    public interface ScopeApproval {
        /**
         * 将队友的越界操作请求连同真实执行上下文交给 Leader 协调，返回范围决定；申请交接失败以 IOException 报告。
         *
         * @param request 固定本次工具、输入、真实身份和权限版本的审批请求
         * @param context 当前轮次的来源、身份、审批通道、派工范围和取消检查
         * @return Leader 对这次派工范围扩展作出的允许、拒绝或转交用户决定
         * @throws IOException 派工范围申请无法完成路由或交付
         */
        com.jadecode.team.TeamManager.ScopeDecision request(ApprovalRequest request, ExecutionContext context)
                throws IOException;
    }

    private ScopeApproval scopeApproval = (request, context) -> {
        throw new IOException("No Leader scope approval route");
    };

    /**
     * 绑定 Team 范围申请路由，供 prepareAssignment 将超出原派工路径的操作交给 Leader 判断。
     *
     * @param value 将范围扩展申请发送给 Leader 的处理函数
     * @throws NullPointerException value 为 null
     */
    public void scopeApproval(ScopeApproval value) {
        scopeApproval = Objects.requireNonNull(value);
    }

    /**
     * 绑定真实 Agent 身份、工作区、Harness 数据根、共享权限状态、审批入口和 Docker 运行时，
     * 建立工作区路径解析器与任务授权账本。
     *
     * @param actor 产生此操作的 Agent 身份，用于事件展示、用量归属和资源回收
     * @param workspace 会话绑定的真实工作区目录
     * @param dataRoot Harness 数据根，用于识别运行状态和配置资料的边界
     * @param state 用户档位与规则的共享权限状态
     * @param gate 执行前分析工具资源并完成授权的权限门
     * @param approvals 沙箱申请外部路径或网络时使用的审批协调器
     * @param runtime 持有容器实例和生命周期的 Docker 运行时
     */
    public SandboxSession(String actor, Path workspace, Path dataRoot, PermissionState state, PermissionGate gate,
            ApprovalCoordinator approvals, DockerRuntime runtime) {
        this.actor = actor;
        this.workspace = workspace.toAbsolutePath().normalize();
        this.dataRoot = dataRoot.toAbsolutePath().normalize();
        this.state = state;
        this.gate = gate;
        this.approvals = approvals;
        this.runtime = runtime;
        resolver = new PathResolver(workspace);
    }

    /**
     * 包装 bash 和文件工具，保留模型可见名称与业务说明，为 bash schema 加入网络及额外路径申请。
     * 执行时消费 Gate 许可、编译计划，再选择宿主、前台容器、后台容器或文件 Worker 入口。
     *
     * @param delegate 被包装的原始客户端或工具，实际操作继续由它执行
     * @return 保留原工具身份并接入许可消费与沙箱计划的包装工具
     */
    public Tool wrap(Tool delegate) {
        return new Tool() {
            /**
             * 沿用被包装工具的名字，使模型目录和权限检查指向同一工具。
             *
             * @return 原工具的注册名
             */
            public String name() {
                return delegate.name();
            }

            /**
             * 沿用被包装工具的业务说明，供模型理解该工具的用途。
             *
             * @return 原工具的模型可见业务说明
             */
            public String description() {
                return delegate.description();
            }

            /**
             * 复制原工具参数定义；bash 额外加入 network_access 和 access_paths，供模型声明本次需要的网络和路径范围。
             * 返回包装后的独立 schema。
             *
             * @return 独立复制的工具 schema，bash 增加网络和额外路径申请字段
             */
            public ObjectNode inputSchema() {
                ObjectNode schema = delegate.inputSchema().deepCopy();
                if (name().equals("bash")) {
                    ObjectNode properties = (ObjectNode) schema.path("properties");
                    properties.set("network_access", Json.mapper().createObjectNode().put("type", "boolean")
                            .put("description",
                                    "Request outbound network for this command; requires separate approval and does not provide credentials."));
                    var access = Json.mapper().createObjectNode().put("type", "array");
                    var item = ToolSchema.objectSchema(Map.of("path", "string", "write", "boolean"), List.of("path"));
                    access.set("items", item);
                    properties.set("access_paths", access);
                }
                return schema;
            }

            /**
             * 消费本次工具许可，按权限和派工范围准备执行计划，再交给对应执行入口。
             *
             * <p>普通 Full Access 调用使用原工具的宿主入口。容器分支中，bash 将计划附到执行上下文，
             * 由原工具选择前台或后台；后台先登记接纳资格，启动失败时撤销。文件工具直接调用 DockerRuntime。
             * 参数变化、计划编译或执行异常转成错误 ToolOutcome，供执行器生成对应调用的结果。</p>
             *
             * @param input 当前已固定的原工具参数，供计划编译和审批呈现使用
             * @param context 当前轮次的来源、身份、审批通道、派工范围和取消检查
             * @return 实际执行结果；授权、计划编译或执行异常返回错误结果
             */
            public ToolOutcome executeOutcome(ObjectNode input, ExecutionContext context) {
                try {
                    var checked = gate.consume(name(), input, context);
                    // 范围审批也可能等待；后续只用 Gate 保存的副本，不让等待中的原参数变化混入计划。
                    ObjectNode exact = checked.input();
                    if (context.assignment() == null && state.snapshot().profile() == PermissionProfile.FULL_ACCESS)
                        return delegate.executeOutcome(exact, context);
                    ExecutionPlan plan = prepare(name(), exact, context, checked);
                    if (!input.equals(exact))
                        throw new IOException(
                                "Tool parameters changed during sandbox approval; re-issue the operation");
                    if (name().equals("bash")) {
                        // 后台线程可能晚于父任务终态才运行；先登记已接收资格，不能依赖线程调度速度。
                        boolean background = exact.path("run_in_background").asBoolean();
                        if (background)
                            runtime.acceptBackground(plan);
                        ToolOutcome result = null;
                        try {
                            result = delegate.executeOutcome(exact, context.withSandboxPlan(plan));
                            return result;
                        } finally {
                            if (background && (result == null || result.isError()))
                                runtime.discardBackground(plan);
                        }
                    }
                    var result = runtime.execute(plan, 120,
                            () -> context.isCancelled() || plan.revision() != state.snapshot().revision());
                    return new ToolOutcome(result.content(), result.error());
                } catch (Exception failure) {
                    return new ToolOutcome("Error: " + failure.getMessage(), true);
                }
            }
        };
    }

    /**
     * 返回前台与后台共用的 Shell 执行后端。核对上下文、计划的 Agent、轮次、权限版本和命令；
     * 普通 Full Access 走明确的宿主分支，其余调用按计划进入 Docker。执行中持续观察取消与权限版本，
     * 返回退出码、有界原始输出和截断标记；失效计划撤销后台资格并返回错误结果。
     *
     * @return 前台和后台共用、按当前计划执行 Shell 的后端
     */
    public ShellCommand.Runner shellRunner() {
        return (command, cwd, timeout, context) -> {
            ExecutionPlan plan = context == null ? null : context.sandboxPlan();
            if (context == null || context.isCancelled()) {
                if (plan != null)
                    runtime.discardBackground(plan);
                return new ShellCommand.Result("Error: Missing or cancelled execution context", null, false);
            }
            if (plan == null && context.assignment() == null
                    && state.snapshot().profile() == PermissionProfile.FULL_ACCESS) {
                try (var host = new ShellCommand(cwd, timeout)) {
                    return host.run(command, context);
                }
            }
            if (plan == null || !plan.actor().equals(actor(context)) || !plan.runId().equals(context.id())
                    || plan.revision() != state.snapshot().revision()
                    || !command.equals(plan.input().path("command").asText())) {
                if (plan != null)
                    runtime.discardBackground(plan);
                return new ShellCommand.Result("Error: Shell execution permit is missing or stale", null, false);
            }
            // 创建容器或排队也可能等待；真正启动与执行期间仍观察权限版本，不能只在 prepare 检查一次。
            var value = runtime.execute(plan, timeout,
                    () -> context.isCancelled() || plan.revision() != state.snapshot().revision());
            return new ShellCommand.Result(value.rawOutput(), value.exitCode(), value.truncated());
        };
    }

    /**
     * 把本次工具许可、用户权限和资源申请编译成固定执行计划。
     *
     * <p>处理过程：</p>
     * <ol>
     * <li>检查工作区范围、数据根分离与许可版本，解析工具路径、access_paths 和 network_access。</li>
     * <li>派工调用转给 prepareAssignment；普通会话先挂载工作区，再加入同版本、适用工具的任务路径授权。</li>
     * <li>逐项检查外部目标，解析新文件需要的既存父目录；使用已有 PATH allow 或提出精确、任务级范围申请。</li>
     * <li>把批准范围加入本次挂载；网络申请单独审批，精确一次视图和网络使用一次性实例。</li>
     * <li>再次检查取消与版本，编译路径限制，返回真实身份、固定参数及完整挂载的 ExecutionPlan。</li>
     * </ol>
     *
     * @param tool 模型调用的工具注册名
     * @param input 当前已固定的原工具参数，供计划编译和审批呈现使用
     * @param context 当前轮次的来源、身份、审批通道、派工范围和取消检查
     * @param checked 权限门为这次具体工具调用签发的许可快照
     * @return 固定真实身份、版本、操作、挂载、网络与保护限制的执行计划
     * @throws IOException 工作区边界、许可版本、资源范围或网络申请不满足执行要求
     */
    private ExecutionPlan prepare(String tool, ObjectNode input, ExecutionContext context,
            PermissionGate.CheckedCall checked) throws IOException {
        if (workspace.startsWith(dataRoot) || dataRoot.startsWith(workspace))
            throw new IOException("Sandbox workspace and Harness data must be separate directories");
        if (workspace.getNameCount() < 2 || workspace.equals(Path.of(System.getProperty("user.home"))))
            throw new IOException(
                    "Choose a project directory rather than a filesystem root or the whole HOME as sandbox workspace");
        long revision = checked.revision();
        if (revision != state.snapshot().revision())
            throw new IOException("Tool authorization changed before sandbox preparation");
        boolean readOnly = state.snapshot().profile() == PermissionProfile.READ_ONLY;
        boolean network = false, reusable = !input.path("run_in_background").asBoolean();
        var needed = neededPaths(tool, input);
        network = tool.equals("bash") && input.path("network_access").asBoolean(false);
        // 队友只从固定 Assignment 和本次明确资源编译视图；不解析 Leader 的 goal 自动扩大目录。
        if (context.assignment() != null)
            return prepareAssignment(tool, input, context, checked, needed, network, revision);
        // 用户任务已经明确外部目录时，在首次 rg/glob/脚本前主动申请，避免等待模型选对工具。
        for (Path directory : declaredDirectories(context))
            if (!directory.startsWith(workspace))
                needed.putIfAbsent(directory, false);
        var mounts = new LinkedHashMap<Path, ExecutionPlan.Mount>();
        mounts.put(workspace, new ExecutionPlan.Mount(workspace, workspace, !readOnly));
        // 已授权任务目录对后续逐文件调用持续生效；不要求模型选择复用容器。
        for (var grant : grants.getOrDefault(scope(context), List.of()))
            if (grant.revision == revision && applies(grant, tool))
                mounts.put(grant.path, new ExecutionPlan.Mount(grant.path, grant.path, grant.write));
        for (var entry : needed.entrySet()) {
            Path target = entry.getKey();
            boolean write = entry.getValue();
            if (target.equals(Path.of("/dev/null")))
                continue;
            boolean protectedPath = PathProtection.sensitive(target);
            boolean internal = !write && gate.internalReadable(target);
            if (target.startsWith(dataRoot) && !internal)
                throw new IOException(
                        "Harness configuration and runtime state cannot be mounted into a work container");
            if (target.startsWith(workspace) && !protectedPath && (!readOnly || !write))
                continue;
            Grant existing = grants.getOrDefault(scope(context), List.of()).stream()
                    .filter(g -> g.revision == revision && applies(g, tool) && target.startsWith(g.path)
                            && (!write || g.write) && !protectedPath)
                    .findFirst().orElse(null);
            if (existing != null)
                continue;
            Path source = target;
            // 新文件需要父目录写入；审批必须显示真实目录范围，不能冒充“只写单文件”。
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                if (!write)
                    throw new IOException("Requested input does not exist: " + target);
                source = source.getParent();
                while (source != null && !Files.exists(source))
                    source = source.getParent();
            }
            if (source == null || source.getParent() == null)
                throw new IOException("Refuse a filesystem-root mount grant");
            source = source.toRealPath();
            if (Files.isDirectory(source)
                    && (source.getNameCount() < 2 || source.equals(Path.of(System.getProperty("user.home")))))
                throw new IOException(
                        "A broad filesystem or HOME directory cannot be granted; choose a narrower project path");
            boolean task = false;
            // 已存在的精确 PATH allow 本身就是用户授权；只在其实际范围内落实，不再次弹相同问题。
            PermissionRule allowed = !protectedPath && !checked.explicitlyApproved()
                    ? pathAllowance(tool, target, write)
                    : null;
            boolean configured = allowed != null
                    && (allowed.recursive() && Files.isDirectory(Path.of(allowed.path())) || source.equals(target));
            if (configured && allowed.recursive()) {
                source = Path.of(allowed.path()).toRealPath();
                task = true;
            } else if (!configured && !write && !protectedPath && !internal && !target.startsWith(workspace)) {
                Path candidate = candidate(source, context);
                if (candidate != null && !declined.contains(scope(context) + candidate)) {
                    source = candidate;
                    task = true;
                }
            }
            if (source.getParent() == null || Files.isDirectory(source) && (source.getNameCount() < 2
                    || source.equals(Path.of(System.getProperty("user.home"))) || dataRoot.startsWith(source)))
                throw new IOException("The selected scope is too broad; choose a project directory");
            if (!internal) {
                // 精确文件已获 Gate 批准时直接落实；扩大目录或 Shell 额外需求仍需范围审批。
                boolean alreadyApproved = checked.explicitlyApproved()
                        && Set.of("read_file", "write_file", "edit_file").contains(tool)
                        && source.equals(target) && !Files.isDirectory(source);
                if (!alreadyApproved && !configured)
                    approve(tool, input, context, source, write, task, false, revision);
                if (task)
                    grants.computeIfAbsent(scope(context), ignored -> new ArrayList<>())
                            .add(new Grant(source, write, revision, configured ? allowed.tool() : "*"));
                else
                    reusable = false;
            } else
                reusable = false;
            mounts.put(source, new ExecutionPlan.Mount(source, source, write));
        }
        if (network) {
            approve(tool, input, context, null, false, false, true, revision);
            reusable = false;
        }
        if (revision != state.snapshot().revision() || context.isCancelled())
            throw new IOException("Sandbox permissions changed; re-issue the operation");
        var restrictions = restrictions(tool, input, checked, mounts.values());
        if (checked.explicitlyApproved())
            reusable = false;
        return new ExecutionPlan(actor(context), context.id(), revision, tool, input, workspace, readOnly, network,
                reusable,
                List.copyOf(mounts.values()), restrictions);
    }

    /**
     * 从文件 path、glob root 或 Shell 分析结果提取目标路径和读写方式，再合并 bash 的 access_paths，
     * 同一路径以写需求优先。检查 network_access 和路径数组的字段类型，返回有序资源表；非法参数抛出 IOException。
     *
     * @param tool 模型调用的工具注册名
     * @param input 当前已固定的原工具参数，供计划编译和审批呈现使用
     * @return 按声明顺序组织的真实路径到写入需求表
     * @throws IOException 路径、network_access 或 access_paths 参数类型不合法
     */
    private LinkedHashMap<Path, Boolean> neededPaths(String tool, ObjectNode input) throws IOException {
        var needed = new LinkedHashMap<Path, Boolean>();
        if (Set.of("read_file", "write_file", "edit_file").contains(tool)) {
            if (!input.path("path").isTextual())
                throw new IOException("A textual file path is required");
            needed.put(resolver.resolve(input.path("path").asText()), !tool.equals("read_file"));
        } else if (tool.equals("glob")) {
            needed.put(resolver.resolve(input.path("root").asText(".")), false);
        } else if (tool.equals("bash")) {
            var analysis = new ShellPermissionCheck(resolver).analyze(tool, input);
            for (var resource : analysis.resources())
                needed.merge(resource.path(), !resource.operation().equals("READ"), Boolean::logicalOr);
            if (input.has("network_access") && !input.path("network_access").isBoolean())
                throw new IOException("network_access must be boolean");
            if (input.has("access_paths")) {
                if (!input.path("access_paths").isArray())
                    throw new IOException("access_paths must be an array");
                for (JsonNode item : input.path("access_paths")) {
                    if (!item.isObject() || !item.path("path").isTextual()
                            || item.has("write") && !item.path("write").isBoolean())
                        throw new IOException("Each access_paths entry requires a path and optional boolean write");
                    needed.merge(resolver.resolve(item.path("path").asText()), item.path("write").asBoolean(),
                            Boolean::logicalOr);
                }
            }
        }
        return needed;
    }

    /**
     * 在用户权限与派工路径的交集内构造队友执行视图。
     *
     * <p>处理过程：</p>
     * <ol>
     * <li>从 Assignment 的 read_paths 和 write_paths 建立基线挂载，只读档位使写路径按只读挂载。</li>
     * <li>逐个检查本次明确访问目标；已覆盖目标继续，内部精确只读资料单独挂载，其余越界目标生成真实范围申请。</li>
     * <li>先请 Leader 决定允许、拒绝或 ask_user；超出用户权限的操作继续取得人工一次批准。</li>
     * <li>核对权限、取消、真实路径和原工具资源仍一致；将本次额外范围加入一次性计划。</li>
     * <li>按祖先先、子目录后的顺序排列挂载，再编译限制，返回固定快照。</li>
     * </ol>
     *
     * @param tool 模型调用的工具注册名
     * @param input 当前已固定的原工具参数，供计划编译和审批呈现使用
     * @param context 当前轮次的来源、身份、审批通道、派工范围和取消检查
     * @param checked 权限门为这次具体工具调用签发的许可快照
     * @param needed 工具解析得到的路径及写入需求表，同一路径以写需求优先
     * @param network 该次计划或服务是否需要容器桥接网络
     * @param revision 本次操作依据的版本号，用于读取后修改时核对状态
     * @return 用户权限与派工范围交集内、含本次额外批准的执行计划
     * @throws IOException 派工挂载不可表达、范围审批被拒、用户权限不足或批准后状态变化
     */
    private ExecutionPlan prepareAssignment(String tool, ObjectNode input, ExecutionContext context,
            PermissionGate.CheckedCall checked, Map<Path, Boolean> needed, boolean network, long revision)
            throws IOException {
        boolean readOnly = state.snapshot().profile() == PermissionProfile.READ_ONLY;
        boolean reusable = !network && !input.path("run_in_background").asBoolean() && !checked.explicitlyApproved();
        var mounts = new LinkedHashMap<Path, ExecutionPlan.Mount>();
        var scope = context.assignment().scope();
        for (String path : scope.readPaths())
            addAssignedMount(mounts, path, false);
        for (String path : scope.writePaths())
            addAssignedMount(mounts, path, !readOnly);
        // 内部邮件仅有精确只读特许；不会把 Harness 数据根或整个邮箱目录暴露给 Shell。
        for (var entry : needed.entrySet()) {
            Path target = entry.getKey();
            boolean write = entry.getValue();
            if (target.equals(Path.of("/dev/null")))
                continue;
            if (tool.equals("glob") && target.equals(workspace))
                continue; // 仅遍历已经暴露的局部目录。
            if (!PathProtection.sensitive(target) && covered(mounts.values(), target, write))
                continue;
            // 基线视图可按完整挂载策略复用；任何本次额外路径都必须使用一次性实例。
            reusable = false;
            boolean internal = !write && gate.internalReadable(target);
            if (target.startsWith(dataRoot) && !internal)
                throw new IOException("Harness state cannot be exposed to teammates");
            Path source = mountSource(target, write);
            if (!internal) {
                var action = new com.jadecode.safety.checks.ToolPermissionCheck.Analysis(tool, workspace, List.of(
                        new com.jadecode.safety.checks.ToolPermissionCheck.Resource(source, write ? "WRITE" : "READ")),
                        List.of(), write, !write, false, "", "");
                var userDecision = gate.evaluate(action, state.snapshot());
                if (userDecision.kind() == PermissionDecision.Kind.DENY)
                    throw new IOException(userDecision.reason());
                // 展示真实挂载源：新文件可能需要父目录，不能以文件名掩盖扩大后的目录权限。
                var display = Json.mapper().createObjectNode().put("assignment_id", context.assignment().id())
                        .put("host_path", source.toString()).put("access", write ? "READ_WRITE" : "READ_ONLY")
                        .put("within_user_permissions", userDecision.kind() == PermissionDecision.Kind.ALLOW);
                display.set("operation", input.deepCopy());
                var allowed = pathAllowance(tool, source, write);
                boolean withinUser = userDecision.kind() == PermissionDecision.Kind.ALLOW
                        && (source.startsWith(workspace) || state.snapshot().profile() == PermissionProfile.FULL_ACCESS
                                || allowed != null && (!Files.isDirectory(source) || allowed.recursive()));
                display.put("within_user_permissions", withinUser);
                var request = new ApprovalRequest("scope_" + UUID.randomUUID(), actor(context), context.id(), tool,
                        display, workspace, "Operation exceeds Assignment scope", revision, null);
                var decision = scopeApproval.request(request,
                        context.withAdditionalCancellation(() -> state.snapshot().revision() != revision));
                if (decision == null || decision == com.jadecode.team.TeamManager.ScopeDecision.DENY)
                    throw new IOException("Leader did not authorize Assignment scope extension");
                // Leader 无权提升用户授权；ask_user 也强制走人工，不交给辅助审批模型代批。
                if (decision == com.jadecode.team.TeamManager.ScopeDecision.ASK_USER
                        || !withinUser) {
                    Approval human = context.source() == ExecutionContext.Source.USER ? context.approval() : null;
                    var result = approvals.request(request,
                            PermissionDecision.ask("assignment.user", request.reason(), true), state, human,
                            context.goal());
                    if (result.answer() != ApprovalAnswer.ALLOW_ONCE)
                        throw new IOException("User did not authorize Assignment scope extension");
                }
                ensureAssignmentFresh(context, revision, source);
            }
            // 一次性补充挂载只属于本次计划，不缓存、不覆盖原 scope。
            mounts.merge(source, new ExecutionPlan.Mount(source, source, write),
                    (old, value) -> old.writable() ? old : value);
        }
        if (network) {
            approve(tool, input, context, null, false, false, true, revision);
        }
        ensureAssignmentFresh(context, revision, workspace);
        if (!needed.equals(neededPaths(tool, input)))
            throw new IOException("Tool targets changed during scope approval");
        // 祖先先挂载、子目录后覆盖；read_paths=[.] + write_paths=[src] 得到项目只读、src 可写。
        var ordered = mounts.values().stream().sorted(Comparator.comparingInt(m -> m.target().getNameCount())).toList();
        return new ExecutionPlan(actor(context), context.id(), revision, tool, input, workspace, readOnly, network,
                reusable,
                ordered, restrictions(tool, input, checked, ordered));
    }

    /**
     * 把派工相对路径解析到工作区，检查范围根已存在、位于工作区且为可用普通路径，再合并到挂载表；
     * 同一路径保留更高写权限，非法范围抛出 IOException。
     *
     * @param mounts 固定的宿主来源、容器目标及读写方式列表
     * @param relative 派工声明中的工作区相对路径
     * @param write 本次资源访问是否需要写入
     * @throws IOException 派工根越界、缺失、敏感或包含符号链接别名
     */
    private void addAssignedMount(Map<Path, ExecutionPlan.Mount> mounts, String relative, boolean write)
            throws IOException {
        Path path = workspace.resolve(relative).normalize();
        if (!path.startsWith(workspace) || PathProtection.sensitive(path)
                || !Files.exists(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().equals(path))
            throw new IOException(
                    "Assignment mount must exist, be non-sensitive and contain no symbolic-link aliases: " + path
                            + "; assign an existing parent directory for new files");
        mounts.merge(path, new ExecutionPlan.Mount(path, path, write), (old, value) -> old.writable() ? old : value);
    }

    /**
     * 检查目标是否被某个挂载覆盖：单文件要求路径相等，目录覆盖子树，写操作同时要求该挂载可写。
     *
     * @param mounts 固定的宿主来源、容器目标及读写方式列表
     * @param target 工具本次需要读写的真实目标路径
     * @param write 本次资源访问是否需要写入
     * @return 目标被现有挂载覆盖且写需求满足时为 true
     */
    private static boolean covered(Collection<ExecutionPlan.Mount> mounts, Path target, boolean write) {
        return mounts.stream().anyMatch(m -> (!write || m.writable()) &&
                (target.equals(m.source()) || Files.isDirectory(m.source()) && target.startsWith(m.source())));
    }

    /**
     * 为新建文件向上寻找最近已存在的父目录，读取操作使用原目标。校验实际来源为可用窄范围并返回；
     * 根目录、HOME、Harness 祖先或链接别名等目标抛出 IOException。
     *
     * @param target 工具本次需要读写的真实目标路径
     * @param write 本次资源访问是否需要写入
     * @return 实际需要挂载的既存目标或最近既存父目录
     * @throws IOException 找不到可挂载的真实来源，或来源为过宽目录及受保护数据
     */
    private Path mountSource(Path target, boolean write) throws IOException {
        Path source = target;
        while (!Files.exists(source, LinkOption.NOFOLLOW_LINKS) && write)
            source = source.getParent();
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS) || !source.toRealPath().equals(source)
                || source.getNameCount() < 2 || source.equals(Path.of(System.getProperty("user.home")))
                || dataRoot.startsWith(source))
            throw new IOException("Cannot grant this mount source: " + source);
        return source;
    }

    /**
     * 范围审批返回后检查上下文取消、权限版本和路径真实身份；任一变化抛出 IOException，供原调用结束并重新申请。
     *
     * @param context 当前轮次的来源、身份、审批通道、派工范围和取消检查
     * @param revision 本次操作依据的版本号，用于读取后修改时核对状态
     * @param path 本次申请或核对的真实挂载来源；网络申请时为 null
     * @throws IOException 任务取消、权限版本变化或路径真实身份已改变
     */
    private void ensureAssignmentFresh(ExecutionContext context, long revision, Path path) throws IOException {
        if (context.isCancelled() || revision != state.snapshot().revision() || !path.toRealPath().equals(path))
            throw new IOException("Assignment execution scope changed during approval; re-issue operation");
    }

    /**
     * 优先选择当前任务明确提到且包含目标的外部目录；否则找到目标所在 Git 仓库，
     * 同轮访问第二个不同文件时提出仓库级候选。返回候选路径，无可用候选时返回 null，由后续审批决定采用范围。
     *
     * @param target 工具本次需要读写的真实目标路径
     * @param context 当前轮次的来源、身份、审批通道、派工范围和取消检查
     * @return 可供本轮申请的外部目录；尚未形成目录候选时为 null
     * @throws IOException 任务声明目录或仓库候选的真实路径解析失败
     */
    private Path candidate(Path target, ExecutionContext context) throws IOException {
        for (Path path : declaredDirectories(context))
            if (target.startsWith(path) && !path.equals(workspace))
                return path;
        Path repository = Files.isDirectory(target) ? target : target.getParent();
        while (repository != null && repository.getNameCount() >= 2
                && !Files.exists(repository.resolve(".git"), LinkOption.NOFOLLOW_LINKS))
            repository = repository.getParent();
        if (repository == null || repository.getNameCount() < 2)
            return null;
        Set<Path> seen = visits.computeIfAbsent(scope(context) + "/" + repository, ignored -> new HashSet<>());
        seen.add(target);
        return seen.size() >= 2 ? repository : null;
    }

    /**
     * 从本轮 goal 中提取明确的绝对目录，解析现存目录后作为外部范围候选返回，供 prepare 主动申请任务涉及的资源。
     *
     * @param context 当前轮次的来源、身份、审批通道、派工范围和取消检查
     * @return 从本轮真实任务中提取并校验的外部目录候选
     * @throws IOException 任务中已存在的候选目录无法解析真实路径
     */
    private List<Path> declaredDirectories(ExecutionContext context) throws IOException {
        var matcher = Pattern.compile("(?:^|[\\s`\"'])((?:/)[^\\s`\"'，。；：]+)").matcher(context.goal());
        var directories = new ArrayList<Path>();
        while (matcher.find()) {
            Path path;
            try {
                path = Path.of(matcher.group(1));
            } catch (InvalidPathException bad) {
                continue;
            }
            if (Files.isDirectory(path)) {
                path = path.toRealPath();
                if (path.getNameCount() >= 2 && !path.startsWith(dataRoot) && !PathProtection.sensitive(path))
                    directories.add(path);
            }
        }
        return List.copyOf(directories);
    }

    /**
     * 将本次操作与真实挂载范围或网络需求组成审批请求，使用当前轮次身份、权限版本和人工通道取得决定。
     * 只有允许一次后继续，拒绝、取消或状态变化以 IOException 返回。
     *
     * @param tool 模型调用的工具注册名
     * @param input 当前已固定的原工具参数，供计划编译和审批呈现使用
     * @param context 当前轮次的来源、身份、审批通道、派工范围和取消检查
     * @param path 本次申请或核对的真实挂载来源；网络申请时为 null
     * @param write 本次资源访问是否需要写入
     * @param task 该范围是否申请在当前轮次内持续使用
     * @param network 该次计划或服务是否需要容器桥接网络
     * @param revision 本次操作依据的版本号，用于读取后修改时核对状态
     * @throws IOException 范围未获允许一次、权限版本变化、取消或真实目标改变
     */
    private void approve(String tool, ObjectNode input, ExecutionContext context, Path path, boolean write,
            boolean task, boolean network, long revision) throws IOException {
        var display = Json.mapper().createObjectNode().put("operation_tool", tool).put("actor", actor(context))
                .put("scope", task ? "CURRENT_TASK" : "THIS_CALL")
                .put("access", network ? "NETWORK_BRIDGE" : write ? "READ_WRITE" : "READ_ONLY");
        if (path != null)
            display.put("host_path", path.toString()).put("container_path", path.toString());
        display.set("operation", input.deepCopy());
        String reason = network
                ? "Allow network for this operation; bridge access is not a domain/private-network allowlist and provides no credentials"
                : (task ? "Read this directory throughout the current task"
                        : "Expose this exact source for this operation") + ": " + path;
        var request = new ApprovalRequest(UUID.randomUUID().toString(), actor(context), context.id(), "sandbox_access",
                display, workspace, reason, revision, null);
        Approval human = context.source() == ExecutionContext.Source.USER ? context.approval() : null;
        var answer = approvals.request(request,
                PermissionDecision.ask("sandbox.scope", reason,
                        write || network || path != null && PathProtection.sensitive(path)),
                state, human, context.goal()).answer();
        if (answer != ApprovalAnswer.ALLOW_ONCE || state.snapshot().revision() != revision || context.isCancelled()) {
            if (task)
                declined.add(scope(context) + path);
            throw new IOException("Sandbox scope was not authorized; no container operation was executed");
        }
        if (path != null && !path.toRealPath().equals(path))
            throw new IOException("Sandbox grant target changed during approval");
    }

    /**
     * 筛选当前工具和工作区适用的 PATH deny、ask 规则，为已暴露视图编译限制：READ 限制隐藏目标，
     * WRITE 或 DELETE 限制将目标覆盖为只读。本次已明确批准的非递归精确文件 ask 可跳过；
     * 缺失、祖先或链接路径无法表达时抛出 IOException。
     *
     * @param tool 模型调用的工具注册名
     * @param input 当前已固定的原工具参数，供计划编译和审批呈现使用
     * @param checked 权限门为这次具体工具调用签发的许可快照
     * @param mounts 固定的宿主来源、容器目标及读写方式列表
     * @return 编译完成的隐藏与只读挂载限制
     * @throws IOException 规则目标缺失、为挂载祖先或链接路径，当前视图无法表达该限制
     */
    private List<ExecutionPlan.Restriction> restrictions(String tool, ObjectNode input,
            PermissionGate.CheckedCall checked,
            Collection<ExecutionPlan.Mount> mounts) throws IOException {
        var result = new ArrayList<ExecutionPlan.Restriction>();
        for (var rule : state.snapshot().rules())
            if (rule.kind() == PermissionRule.Kind.PATH
                    && rule.effect() != PermissionRule.Effect.ALLOW
                    && (rule.tool().equals("*") || rule.tool().equals(tool))
                    && (rule.workspace().isEmpty() || Path.of(rule.workspace()).equals(workspace))) {
                Path denied = Path.of(rule.path()).normalize();
                if (mounts.stream().noneMatch(m -> denied.startsWith(m.source()) || m.source().startsWith(denied)))
                    continue;
                if (rule.effect() == PermissionRule.Effect.ASK && checked.explicitlyApproved()
                        && Set.of("read_file", "write_file", "edit_file").contains(tool)
                        && resolver.resolve(input.path("path").asText()).equals(denied) && !rule.recursive())
                    continue;
                if (!Files.exists(denied, LinkOption.NOFOLLOW_LINKS) || !denied.toRealPath().equals(denied)
                        || mounts.stream().noneMatch(m -> denied.startsWith(m.source())))
                    throw new IOException("Cannot safely express missing, ancestor or symbolic-link path rule "
                            + rule.id() + " in this Docker view");
                result.add(new ExecutionPlan.Restriction(denied, rule.operation().equals("READ")));
            }
        return List.copyOf(result);
    }

    /**
     * 比较任务路径授权中的工具限定；星号匹配任意工具，具体名称只匹配同名工具。
     *
     * @param grant 当前轮次已保存的路径授权
     * @param tool 模型调用的工具注册名
     * @return 授权工具名为星号或与当前工具相同时为 true
     */
    private static boolean applies(Grant grant, String tool) {
        return grant.tool.equals("*") || grant.tool.equals(tool);
    }

    /**
     * 从当前权限规则中筛选适用于此工具、目标及读写方式的 PATH allow，选择覆盖范围最窄的一项，
     * 供计划复用已保存的路径授权。
     *
     * @param tool 模型调用的工具注册名
     * @param target 工具本次需要读写的真实目标路径
     * @param write 本次资源访问是否需要写入
     * @return 覆盖此目标的最窄 PATH allow；未命中为 null
     */
    private PermissionRule pathAllowance(String tool, Path target, boolean write) {
        return state.snapshot().rules().stream()
                .filter(r -> r.kind() == PermissionRule.Kind.PATH && r.effect() == PermissionRule.Effect.ALLOW
                        && (r.tool().equals("*") || r.tool().equals(tool))
                        && (r.workspace().isEmpty() || Path.of(r.workspace()).equals(workspace))
                        && r.operation().equals(write ? "WRITE" : "READ")
                        && (r.recursive() ? target.startsWith(Path.of(r.path())) : target.equals(Path.of(r.path()))))
                .max(Comparator.comparingInt(r -> Path.of(r.path()).getNameCount())).orElse(null);
    }

    /**
     * 按轮次撤销内存中的临时范围授权、访问计数和已拒绝候选，再通知 DockerRuntime 收尾该轮资源。
     *
     * @param runId 当前轮次 ID，用于关联取消、后台命令和任务收尾
     */
    public void endTask(String runId) {
        grants.keySet().removeIf(key -> key.startsWith(runId + "/"));
        visits.keySet().removeIf(key -> key.startsWith(runId + "/"));
        declined.removeIf(key -> key.startsWith(runId));
        runtime.endTask(actor, runId);
    }

    /**
     * 撤销一次子代理委派对应的范围账本，并通知 DockerRuntime 收尾该子身份的容器，保留父轮次其他作用域。
     *
     * @param context 当前轮次的来源、身份、审批通道、派工范围和取消检查
     */
    public void endScope(ExecutionContext context) {
        grants.remove(scope(context));
        visits.keySet().removeIf(key -> key.startsWith(scope(context) + "/"));
        declined.removeIf(key -> key.startsWith(scope(context)));
        runtime.endTask(actor(context), context.id());
        runtime.endActor(actor(context));
    }

    /**
     * 组合真实 run ID 与子代理委派编号，生成任务授权账本的键，供同轮不同委派分别登记范围。
     *
     * @param context 当前轮次的来源、身份、审批通道、派工范围和取消检查
     * @return 由真实轮次 ID 和委派编号组成的账本键
     */
    private String scope(ExecutionContext context) {
        return context.id() + "/" + context.actorScope();
    }

    /**
     * 以本 Agent 身份和可选子代理作用域生成容器归属身份，供执行计划与资源回收使用。
     *
     * @param context 当前轮次的来源、身份、审批通道、派工范围和取消检查
     * @return 当前 Agent 加可选委派编号组成的资源归属身份
     */
    private String actor(ExecutionContext context) {
        return actor + (context.actorScope().isEmpty() ? "" : "/" + context.actorScope());
    }
}
