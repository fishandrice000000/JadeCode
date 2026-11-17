package com.jadecode.safety;

import java.nio.file.Path;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.safety.approval.*;
import com.jadecode.safety.checks.*;
import com.jadecode.safety.checks.ToolPermissionCheck.Analysis;
import com.jadecode.safety.rules.*;
import com.jadecode.util.PathResolver;

/**
 * 在工具执行前分析资源并作出权限决定。按用户规则、路径保护和权限档位选择允许、拒绝或审批；批准后核对目标与版本，
 * 将具体调用保存为同线程一次许可，供沙箱包装器消费。
 */
public final class PermissionGate {
    private final PathResolver resolver;
    private final PermissionState state;
    private final ApprovalCoordinator coordinator;
    private final String actor;
    private final Approval defaultHuman;
    private final FilePermissionCheck files;
    private final ShellPermissionCheck shell;
    private final McpPermissionCheck mcp;
    private final PermissionRuleMatcher matcher = new PermissionRuleMatcher();
    private final DefaultPermissionRules defaults = new DefaultPermissionRules();
    private boolean sandboxed;
    private boolean managedWorkspace;

    /**
     * 登记此会话是否为受管工作区，供权限决策要求容器执行并限制 Full Access 档位。
     *
     * @param value 此会话是否在受管工作区内执行
     */
    public void managedWorkspace(boolean value) {
        managedWorkspace = value;
    }

    private final ThreadLocal<CheckedCall> checked = new ThreadLocal<>();

    /**
     * 登记文件与 Shell 是否接入沙箱，供普通越界请求选择后续范围审批，并检查派工调用具备容器执行入口。
     *
     * @param value 文件与 Shell 工具是否已经接入 Docker 沙箱
     */
    public void sandboxed(boolean value) {
        sandboxed = value;
    }

    /**
     * 一次工具的不可变检查结果，供受限工具消费；不向模型暴露。
     *
     * @param tool 待执行工具的实际注册名
     * @param input 模型提出的原工具参数，检查期间与固定副本比较
     * @param runId 当前轮次 ID，用于关联取消、后台命令和任务收尾
     * @param actorScope 一次子代理委派的隔离编号；普通会话使用空串
     * @param revision 本次权限快照版本，批准后执行前用它核对权限是否变化
     * @param explicitlyApproved 这次调用是否经过明确的一次审批
     */
    public record CheckedCall(String tool, ObjectNode input, String runId, String actorScope, long revision,
            boolean explicitlyApproved) {
        /**
         * 复制本次获准调用的参数，连同工具、轮次、作用域和权限版本固定为一次执行许可。
         *
         * @param tool 待执行工具的实际注册名
         * @param input 模型提出的原工具参数，检查期间与固定副本比较
         * @param runId 当前轮次 ID，用于关联取消、后台命令和任务收尾
         * @param actorScope 一次子代理委派的隔离编号；普通会话使用空串
         * @param revision 本次权限快照版本，批准后执行前用它核对权限是否变化
         * @param explicitlyApproved 这次调用是否经过明确的一次审批
         */
        public CheckedCall {
            input = input.deepCopy();
        }

        /**
         * 返回已批准参数的深拷贝，供沙箱编译执行计划时使用。
         *
         * @return 已批准工具参数的深拷贝
         */
        @Override
        public ObjectNode input() {
            return input.deepCopy();
        }
    }

    /**
     * 取出并移除同线程保存的 CheckedCall，核对工具、参数、轮次、作用域和当前权限版本后返回许可。
     * 许可缺失或过期抛出 IllegalStateException，供包装器返回错误结果。
     *
     * @param tool 待执行工具的实际注册名
     * @param input 模型提出的原工具参数，检查期间与固定副本比较
     * @param context 当前轮次的来源、身份、审批通道、派工范围和取消检查
     * @return 与当前工具、参数、run、作用域和版本匹配的一次许可
     * @throws IllegalStateException 本线程缺少匹配许可，或工具、输入、run、作用域、权限版本已改变
     */
    public CheckedCall consume(String tool, ObjectNode input, ExecutionContext context) {
        CheckedCall call = checked.get();
        checked.remove();
        if (call == null || context == null || !call.tool().equals(tool) || !call.input().equals(input)
                || !call.runId().equals(context.id()) || !call.actorScope().equals(context.actorScope())
                || call.revision() != state.snapshot().revision())
            throw new IllegalStateException("Tool authorization is missing or stale; re-issue the operation");
        return call;
    }

    /**
     * 检查目标是否命中已绑定的记忆或内部产物可读入口，供沙箱为该精确资料建立只读挂载。
     *
     * @param path 本次读取、授权或校验的路径，按方法说明解析到实际目标
     * @return 目标命中精确记忆或内部产物读取范围时为 true
     */
    public boolean internalReadable(Path path) {
        return memoryReadable.test(path) || artifactReadable.test(path);
    }

    // Workflow 仅分析传入文本并写宿主管理的运行记录，不获得文件/Shell 能力；显式 deny/ask 仍优先。
    private static final Set<String> INTERNAL = Set.of("glob", "todo_write", "compact", "load_skill", "task",
            "Workflow", "memory_note",
            "create_task", "get_task", "list_tasks", "update_task", "claim_task", "complete_task",
            "connect_mcp", "schedule_cron", "list_crons", "cancel_cron", "spawn_teammate", "list_teammates",
            "close_teammate",
            "shutdown_teammate", "assign_task", "send_message", "submit_task_result", "reopen_task",
            "retry_team_message", "review_task_result", "resolve_scope_request", "workspace_status");
    private static final Set<String> ADMIN_WRITES = Set.of("memory_note", "create_task", "update_task", "claim_task",
            "complete_task",
            "schedule_cron", "cancel_cron", "spawn_teammate", "close_teammate", "shutdown_teammate", "assign_task",
            "send_message", "submit_task_result",
            "reopen_task", "retry_team_message", "review_task_result", "resolve_scope_request");

    private Path runtimeRoot;
    private java.util.function.Predicate<Path> artifactReadable = path -> false;
    private Path memoryRoot;
    private java.util.function.Predicate<Path> memoryReadable = path -> false;

    /**
     * 工厂绑定受限只读入口；正文写入仍禁止，任意 Shell 不因此获得隔离保证。
     * 不授予 ~/.jadecode 其它文件的权限，显式 deny/ask 与敏感文件保护仍优先。
     *
     * @param root 正式长期记忆库根目录，用于识别受控可读资料和写保护范围
     * @param readable 判断精确资料路径是否属于内部可读范围的函数
     */
    public void memoryAccess(Path root, java.util.function.Predicate<Path> readable) {
        memoryRoot = root.toAbsolutePath().normalize();
        memoryReadable = readable;
    }

    /**
     * 当前项目资料获得窄只读授权，所有项目的运行状态均禁止模型直接改写。
     *
     * @param paths 本工作区的 Harness 数据布局，供保护内部状态与产物读取
     */
    public void runtimeData(com.jadecode.config.WorkspacePaths paths) {
        runtimeRoot = paths.jade().workspaces();
        artifactReadable = paths::readableArtifact;
    }

    /**
     * 绑定同一会话的路径解析器和共享权限状态，以 actor 标识工具执行者，使用协调器处理用户或辅助模型审批。
     *
     * @param resolver 绑定真实工作区的路径解析器，用于定位相对路径和链接目标
     * @param state 本轮工具检查使用的共享权限状态
     * @param coordinator 按当前档位选择人工或辅助模型通道的审批协调器
     * @param actor 产生此操作的 Agent 身份，用于事件展示、用量归属和资源回收
     */
    public PermissionGate(PathResolver resolver, PermissionState state, ApprovalCoordinator coordinator, String actor) {
        this(resolver, state, coordinator, actor, null);
    }

    /**
     * 为直接调用入口绑定固定人工审批通道和共享权限状态，使用 leader 作为执行身份，供离线或嵌入式调用使用。
     *
     * @param resolver 绑定真实工作区的路径解析器，用于定位相对路径和链接目标
     * @param state 本轮工具检查使用的共享权限状态
     * @param human 本次操作可用的人工审批通道；null 表示无法交给用户作答
     */
    public PermissionGate(PathResolver resolver, PermissionState state, Approval human) {
        this(resolver, state, new ApprovalCoordinator(null), "leader", human);
    }

    /**
     * 保存路径解析器、权限状态、协调器、身份和默认审批通道，并创建文件、Shell 与 MCP 权限分析器。
     *
     * @param resolver 绑定真实工作区的路径解析器，用于定位相对路径和链接目标
     * @param state 本轮工具检查使用的共享权限状态
     * @param coordinator 按当前档位选择人工或辅助模型通道的审批协调器
     * @param actor 产生此操作的 Agent 身份，用于事件展示、用量归属和资源回收
     * @param human 本次操作可用的人工审批通道；null 表示无法交给用户作答
     */
    private PermissionGate(PathResolver resolver, PermissionState state, ApprovalCoordinator coordinator, String actor,
            Approval human) {
        this.resolver = resolver;
        this.state = state;
        this.coordinator = coordinator;
        this.actor = actor;
        this.defaultHuman = human;
        files = new FilePermissionCheck(resolver);
        shell = new ShellPermissionCheck(resolver);
        mcp = new McpPermissionCheck(resolver);
    }

    /**
     * 为直接调用入口创建 USER 执行上下文，使用默认人工审批通道完成工具授权。返回 null 表示允许；返回文本说明阻止原因，
     * 允许结果由完整 check 入口登记为待消费许可。
     *
     * @param tool 待执行工具的实际注册名
     * @param input 模型提出的原工具参数，检查期间与固定副本比较
     * @return 允许时为 null；被阻止时为可直接转成工具错误结果的原因文本
     */
    public String check(String tool, ObjectNode input) {
        return check(tool, input, ExecutionContext.create(ExecutionContext.Source.USER, defaultHuman));
    }

    /**
     * 为内置工具身份调用完整权限检查入口，传入工具参数和本轮执行上下文。返回 null 表示已经登记一次许可，
     * 返回文本表示本次调用被阻止。
     *
     * @param tool 待执行工具的实际注册名
     * @param input 模型提出的原工具参数，检查期间与固定副本比较
     * @param context 当前轮次的来源、身份、审批通道、派工范围和取消检查
     * @return 允许时为 null；被阻止时为可直接转成工具错误结果的原因文本
     */
    public String check(String tool, ObjectNode input, ExecutionContext context) {
        return check(tool, input, context, com.jadecode.execution.ToolIdentity.builtin(tool));
    }

    /**
     * 为具体工具调用完成权限分析、必要审批和一次许可登记。
     *
     * <p>先检查派工能力是否接入隔离，固定输入副本，再循环解析真实目标并读取同一版本权限快照。
     * 允许时保存 CheckedCall，拒绝时返回原因；需要询问时创建带身份、操作和建议规则的审批请求。</p>
     * <p>审批要求重判或版本、目标变化时重新分析；允许相似操作先持久保存规则再重新匹配。
     * 允许一次时固定工具、输入、run、作用域和版本，供 consume 领取。取消、参数变化或保存失败返回阻止文字。</p>
     *
     * @param tool 本次工具名
     * @param input 原工具参数，检查期间与固定副本比较
     * @param context 真实执行身份与审批通道，null 按无人工通道处理
     * @param identity 本次目录中的宿主工具身份
     * @return null 表示许可，其他文字为阻止原因
     */
    public String check(String tool, ObjectNode input, ExecutionContext context,
            com.jadecode.execution.ToolIdentity identity) {
        checked.remove();
        // 常驻 MCP 服务尚不具备逐 Assignment 挂载；受限队友不能借它或工作区管理工具绕过文件边界。
        if (context != null && context.assignment() != null
                && (identity.kind() == com.jadecode.execution.ToolIdentity.Kind.MCP
                        || Set.of("connect_mcp", "import_resource", "publish_result", "prepare_environment")
                                .contains(tool)))
            return "This capability is not integrated with Assignment isolation; ask the Leader to perform it";
        // Assignment 是独立权限边界；没有 OS 沙箱时拒绝文件/Shell，而非 Full Access 宿主直通。
        if (context != null && context.assignment() != null && !sandboxed
                && Set.of("read_file", "write_file", "edit_file", "glob", "bash").contains(tool))
            return "Assignment file and Shell operations require an enabled Docker sandbox";
        ObjectNode exact = input.deepCopy();
        while (!Thread.currentThread().isInterrupted() && (context == null || !context.isCancelled())) {
            if (!input.equals(exact))
                return "The pending parameters changed; re-issue the tool call";
            // 1. 每次重判都重新解析真实路径，并同时拿到规则和版本的同一份快照。
            Analysis action;
            try {
                action = analyze(tool, exact, identity);
            } catch (RuntimeException invalid) {
                return "Cannot resolve tool target: " + invalid.getClass().getSimpleName();
            }
            var snapshot = state.snapshot();
            var decision = evaluate(action, snapshot);
            if (decision.kind() == PermissionDecision.Kind.DENY)
                return decision.reason();
            if (decision.kind() == PermissionDecision.Kind.ALLOW) {
                if (state.snapshot().revision() != snapshot.revision())
                    continue;
                if (!input.equals(exact))
                    return "The pending parameters changed; re-issue the tool call";
                checked.set(new CheckedCall(tool, exact, context == null ? "" : context.id(),
                        context == null ? "" : context.actorScope(), snapshot.revision(), false));
                return null;
            }
            // 2. 模型不能通过 input.source 把定时任务冒充成人工任务。
            Approval human = context != null && context.source() == ExecutionContext.Source.USER ? context.approval()
                    : null;
            var suggestion = decision.humanOnly() ? null : suggestion(action);
            String requestActor = actor
                    + (context == null || context.actorScope().isEmpty() ? "" : "/subagent/" + context.actorScope());
            var request = new ApprovalRequest(UUID.randomUUID().toString(), requestActor,
                    context == null ? "" : context.id(),
                    tool, exact, action.cwd(), decision.reason() + " [" + decision.ruleId() + "]", snapshot.revision(),
                    suggestion);
            var outcome = coordinator.request(request, decision, state, human,
                    (context == null ? "" : context.goal()) + "\nTask origin="
                            + (context == null ? "unknown" : context.source()));
            var answer = outcome.answer();
            if (answer == ApprovalAnswer.RECHECK)
                continue;
            if (context != null && context.isCancelled())
                return "The session is closed";
            if (answer == ApprovalAnswer.DENY)
                return outcome.reason();
            if (answer != ApprovalAnswer.ALLOW_ONCE && answer != ApprovalAnswer.ALLOW_SIMILAR)
                return "Approval returned no valid answer; the operation was not executed";
            if (state.snapshot().revision() != snapshot.revision())
                continue;
            if (!input.equals(exact))
                return "Tool parameters changed during approval; the old approval no longer applies";
            // 仅本次和长期授权都要核对目标；不能先保存旧目标的批准，再发现链接已经变了。
            try {
                if (!action.equals(analyze(tool, exact, identity)))
                    continue;
            } catch (RuntimeException changed) {
                return "Cannot re-resolve the tool target after approval";
            }
            if (answer == ApprovalAnswer.ALLOW_SIMILAR) {
                // 3. 建议由解释过参数的权限层生成，界面只选答案，不能自行扩大范围。
                if (suggestion == null)
                    return "This operation has no safe long-term scope to save";
                try {
                    state.remember(suggestion, snapshot.revision());
                } catch (java.io.IOException failed) {
                    return "Rule was not saved; the operation was not executed: " + failed.getMessage();
                }
                continue;
            }
            checked.set(new CheckedCall(tool, exact, context == null ? "" : context.id(),
                    context == null ? "" : context.actorScope(), snapshot.revision(), true));
            return null;
        }
        return "The session is closed or execution was interrupted";
    }

    /**
     * 按宿主工具身份选择文件、Glob、Shell、MCP 或工作区操作分析器；其余工具依据内部能力表生成 Analysis，
     * 尚未声明检查方式的工具标为不确定。
     *
     * @param tool 待执行工具的实际注册名
     * @param input 模型提出的原工具参数，检查期间与固定副本比较
     * @param identity 宿主工具目录提供的可信工具身份
     * @return 命令、资源、读写与不确定属性组成的分析结果
     */
    private Analysis analyze(String tool, ObjectNode input, com.jadecode.execution.ToolIdentity identity) {
        if (identity.kind() == com.jadecode.execution.ToolIdentity.Kind.MCP)
            return mcp.analyze(tool, input, identity);
        if (Set.of("read_file", "write_file", "edit_file").contains(tool))
            return files.analyze(tool, input);
        if (tool.equals("glob"))
            return files.analyzeGlob(input);
        if (tool.equals("bash"))
            return shell.analyze(tool, input);
        if (Set.of("import_resource", "publish_result", "prepare_environment", "workspace_status").contains(tool))
            return new WorkspacePermissionCheck(resolver).analyze(tool, input);
        return new Analysis(tool, resolver.workspaceReal(), List.of(), List.of(), ADMIN_WRITES.contains(tool), true,
                !INTERNAL.contains(tool), "", "");
    }

    /**
     * 将工具资源与同一版权限快照转换为允许、拒绝或询问。
     *
     * <p>处理过程：</p>
     * <ol>
     * <li>依次处理受管工作区沙箱要求、输入错误、正式记忆和运行状态的写保护。</li>
     * <li>先匹配用户 deny，再匹配要求人工的 ask，随后应用默认硬保护。</li>
     * <li>对受管网络和未知能力选择询问；内部精确资料读取按已注册可读入口判断。</li>
     * <li>用户 allow 完整覆盖调用时允许，否则由当前档位和沙箱接线决定普通操作策略。</li>
     * </ol>
     *
     * @param action 从工具参数解析出的命令、资源及读写属性
     * @param snapshot 本次决策依据的同一版权限档位、规则与停用项
     * @return 按规则优先级与当前档位作出的允许、拒绝或询问决定
     */
    public PermissionDecision evaluate(Analysis action, PermissionState.Snapshot snapshot) {
        if (managedWorkspace && snapshot.profile() == PermissionProfile.FULL_ACCESS)
            return PermissionDecision.deny("workspace.sandbox-required",
                    "Managed workspaces require Docker; select Workspace or Approve for Me");
        if (!action.error().isEmpty())
            return PermissionDecision.deny("input.invalid", action.error());
        if (memoryRoot != null && action.resources().stream().anyMatch(r -> !r.operation().equals("READ")
                && (r.path().startsWith(memoryRoot) || memoryRoot.startsWith(r.path()))))
            return PermissionDecision.deny("memory.read-only",
                    "Formal memory is read-only; propose changes with memory_note");
        if (runtimeRoot != null && action.resources().stream().anyMatch(r -> !r.operation().equals("READ")
                && (r.path().startsWith(runtimeRoot) || runtimeRoot.startsWith(r.path()))))
            return PermissionDecision.deny("runtime.read-only",
                    "Runtime state is managed by Harness tools and is read-only to file commands");
        var denied = matcher.first(snapshot.rules(), PermissionRule.Effect.DENY, action);
        if (denied != null)
            return PermissionDecision.deny(denied.id(), "Matched deny rule: " + denied.id());
        var asked = matcher.first(snapshot.rules(), PermissionRule.Effect.ASK, action);
        if (asked != null)
            return PermissionDecision.ask(asked.id(), "Matched required-ask rule: " + asked.id(), true);
        var protection = defaults.protectedDecision(action, snapshot);
        if (protection != null)
            return protection;
        if (action.uncertaintyReason().equals("managed.network"))
            return PermissionDecision.ask("workspace.network",
                    "Repository import requires network access; source and revision are shown in this request", false);
        // 尚未声明检查方式的新工具，不能因 Full Access 绕过集成时的显式确认。
        if (action.commands().isEmpty() && action.resources().isEmpty() && action.uncertain())
            return PermissionDecision.ask("tool.unknown", "This tool has not declared a permission check", true);
        if (action.tool().equals("read_file") && !action.resources().isEmpty()
                && action.resources().stream().allMatch(r -> r.operation().equals("READ")
                        && (memoryReadable.test(r.path()) || artifactReadable.test(r.path()))))
            return PermissionDecision.allow();
        if (matcher.covers(snapshot.rules(), action))
            return PermissionDecision.allow();
        return defaults.ordinary(action, snapshot, sandboxed);
    }

    /**
     * 为已确定的单条无重定向命令或单项资源生成绑定工作区的长期 allow 建议；命令保存完整 argv 前缀，
     * 资源保存精确路径与操作。复杂或多目标调用返回 null。
     *
     * @param action 从工具参数解析出的命令、资源及读写属性
     * @return 可持久保存的精确命令或路径 allow 建议；无法准确表达时为 null
     */
    private PermissionRule suggestion(Analysis action) {
        if (action.uncertain())
            return null;
        String id = "user." + UUID.randomUUID();
        if (action.commands().size() == 1 && !action.commands().getFirst().redirected())
            return new PermissionRule(id, PermissionRule.Effect.ALLOW, PermissionRule.Kind.COMMAND, action.tool(),
                    action.cwd().toString(), "", "", false, action.commands().getFirst().argv());
        if (action.commands().isEmpty() && action.resources().size() == 1) {
            var resource = action.resources().getFirst();
            return new PermissionRule(id, PermissionRule.Effect.ALLOW, PermissionRule.Kind.PATH, action.tool(),
                    action.cwd().toString(), resource.operation(), resource.path().toString(), false, List.of());
        }
        return null;
    }
}
