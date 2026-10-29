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
 * 执行前统一判断：解释输入、匹配规则，再处理真正需要的审批，不运行工具。
 * 配置/目标改变时重新判断；原调用只获准一次，不能把旧批准借给另一份输入。
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
    /** 独立云端工作区始终走 Docker，不能切换 Full Access 后绕过共享执行屏障。 */
    public void managedWorkspace(boolean value) { managedWorkspace=value; }
    private final ThreadLocal<CheckedCall> checked = new ThreadLocal<>();
    /** 只将普通越界询问交给沙箱范围审批；显式规则、敏感保护与只读检查仍由 Gate 执行。 */
    public void sandboxed(boolean value) { sandboxed = value; }
    /** 一次工具的不可变检查结果，供受限工具消费；不向模型暴露。 */
    public record CheckedCall(String tool, ObjectNode input, String runId, String actorScope, long revision, boolean explicitlyApproved) {
        public CheckedCall { input = input.deepCopy(); }
        @Override public ObjectNode input() { return input.deepCopy(); }
    }
    /** 消费同线程、同调用的许可。展示 hook 改参数或版本变化时拒绝使用旧批准。 */
    public CheckedCall consume(String tool, ObjectNode input, ExecutionContext context) {
        CheckedCall call = checked.get(); checked.remove();
        if (call == null || context == null || !call.tool().equals(tool) || !call.input().equals(input)
                || !call.runId().equals(context.id()) || !call.actorScope().equals(context.actorScope()) || call.revision() != state.snapshot().revision())
            throw new IllegalStateException("Tool authorization is missing or stale; re-issue the operation");
        return call;
    }
    /** 只读产物权限不扩展为整个运行目录授权。 */
    public boolean internalReadable(Path path) { return memoryReadable.test(path) || artifactReadable.test(path); }
    // Workflow 仅分析传入文本并写宿主管理的运行记录，不获得文件/Shell 能力；显式 deny/ask 仍优先。
    private static final Set<String> INTERNAL = Set.of("glob", "todo_write", "compact", "load_skill", "task", "Workflow", "memory_note",
            "create_task", "get_task", "list_tasks", "update_task", "claim_task", "complete_task",
            "connect_mcp", "schedule_cron", "list_crons", "cancel_cron", "spawn_teammate", "list_teammates", "close_teammate",
            "shutdown_teammate", "assign_task", "send_message", "submit_task_result", "reopen_task", "retry_team_message", "review_task_result", "workspace_status");
    private static final Set<String> ADMIN_WRITES = Set.of("memory_note", "create_task", "update_task", "claim_task", "complete_task",
            "schedule_cron", "cancel_cron", "spawn_teammate", "close_teammate", "shutdown_teammate", "assign_task", "send_message", "submit_task_result",
            "reopen_task", "retry_team_message", "review_task_result");

    private Path runtimeRoot;
    private java.util.function.Predicate<Path> artifactReadable = path -> false;
    private Path memoryRoot;
    private java.util.function.Predicate<Path> memoryReadable = path -> false;

    /**
     * 工厂绑定受限只读入口；正文写入仍禁止，任意 Shell 不因此获得隔离保证。
     * 不授予 ~/.jadecode 其它文件的权限，显式 deny/ask 与敏感文件保护仍优先。
     */
    public void memoryAccess(Path root, java.util.function.Predicate<Path> readable) {
        memoryRoot = root.toAbsolutePath().normalize(); memoryReadable = readable;
    }

    /** 当前项目资料获得窄只读授权，所有项目的运行状态均禁止模型直接改写。 */
    public void runtimeData(com.jadecode.config.WorkspacePaths paths) {
        runtimeRoot = paths.jade().workspaces(); artifactReadable = paths::readableArtifact;
    }

    /** 工厂传入同一 CLI 的共享状态；coordinator 可以询问用户或辅助模型，本类不读终端。 */
    public PermissionGate(PathResolver resolver, PermissionState state, ApprovalCoordinator coordinator, String actor) {
        this(resolver, state, coordinator, actor, null);
    }
    /** 离线调用者可以提供固定审批器；生产始终读取本次 ExecutionContext 中的真实通道。 */
    public PermissionGate(PathResolver resolver, PermissionState state, Approval human) {
        this(resolver, state, new ApprovalCoordinator(null), "leader", human);
    }
    private PermissionGate(PathResolver resolver, PermissionState state, ApprovalCoordinator coordinator, String actor, Approval human) {
        this.resolver = resolver; this.state = state; this.coordinator = coordinator; this.actor = actor; this.defaultHuman = human;
        files = new FilePermissionCheck(resolver); shell = new ShellPermissionCheck(resolver); mcp = new McpPermissionCheck(resolver);
    }
    public String check(String tool, ObjectNode input) {
        return check(tool, input, ExecutionContext.create(ExecutionContext.Source.USER, defaultHuman));
    }
    /**
     * 在工具执行前给一次具体调用作许可判断，不修改入参、不运行命令。
     *
     * <p>例如模型请求删除临时文件，先从 command 算出删除目标，再看用户规则和当前档位。
     * 只有需要询问时才等待答复。用户等待期间可能切换档位或改动符号链接，因此答复后
     * 必须再看版本和目标，不能拿旧批准去操作新的文件。“允许此类”先写配置再重新匹配，
     * 落盘失败不会以一次临时批准掩盖。</p>
     * @param context 程序提供的任务来源、目标和审批通道；null 表示不能人工询问
     * @return null 代表许可，文字代表拒绝；外层配对 tool_result，并在真正执行前再次检查退出
     */
    public String check(String tool, ObjectNode input, ExecutionContext context) {
        return check(tool, input, context, com.jadecode.execution.ToolIdentity.builtin(tool));
    }

    /** 使用固定目录中的身份解释外部工具；模型输入内同名字段无法替代此参数。 */
    public String check(String tool, ObjectNode input, ExecutionContext context, com.jadecode.execution.ToolIdentity identity) {
        checked.remove();
        ObjectNode exact = input.deepCopy();
        while (!Thread.currentThread().isInterrupted() && (context == null || !context.isCancelled())) {
            if (!input.equals(exact)) return "The pending parameters changed; re-issue the tool call";
            // 1. 每次重判都重新解析真实路径，并同时拿到规则和版本的同一份快照。
            Analysis action;
            try { action = analyze(tool, exact, identity); }
            catch (RuntimeException invalid) { return "Cannot resolve tool target: " + invalid.getClass().getSimpleName(); }
            var snapshot = state.snapshot();
            var decision = evaluate(action, snapshot);
            if (decision.kind() == PermissionDecision.Kind.DENY) return decision.reason();
            if (decision.kind() == PermissionDecision.Kind.ALLOW) {
                if (state.snapshot().revision() != snapshot.revision()) continue;
                if (!input.equals(exact)) return "The pending parameters changed; re-issue the tool call";
                checked.set(new CheckedCall(tool, exact, context == null ? "" : context.id(), context == null ? "" : context.actorScope(), snapshot.revision(), false));
                return null;
            }
            // 2. 模型不能通过 input.source 把定时任务冒充成人工任务。
            Approval human = context != null && context.source() == ExecutionContext.Source.USER ? context.approval() : null;
            var suggestion = decision.humanOnly() ? null : suggestion(action);
            String requestActor = actor + (context == null || context.actorScope().isEmpty() ? "" : "/subagent/" + context.actorScope());
            var request = new ApprovalRequest(UUID.randomUUID().toString(), requestActor, context == null ? "" : context.id(),
                    tool, exact, action.cwd(), decision.reason() + " [" + decision.ruleId() + "]", snapshot.revision(), suggestion);
            var outcome = coordinator.request(request, decision, state, human,
                    (context == null ? "" : context.goal()) + "\nTask origin=" + (context == null ? "unknown" : context.source()));
            var answer = outcome.answer();
            if (answer == ApprovalAnswer.RECHECK) continue;
            if (context != null && context.isCancelled()) return "The session is closed";
            if (answer == ApprovalAnswer.DENY) return outcome.reason();
            if (answer != ApprovalAnswer.ALLOW_ONCE && answer != ApprovalAnswer.ALLOW_SIMILAR)
                return "Approval returned no valid answer; the operation was not executed";
            if (state.snapshot().revision() != snapshot.revision()) continue;
            if (!input.equals(exact)) return "Tool parameters changed during approval; the old approval no longer applies";
            // 仅本次和长期授权都要核对目标；不能先保存旧目标的批准，再发现链接已经变了。
            try { if (!action.equals(analyze(tool, exact, identity))) continue; }
            catch (RuntimeException changed) { return "Cannot re-resolve the tool target after approval"; }
            if (answer == ApprovalAnswer.ALLOW_SIMILAR) {
                // 3. 建议由解释过参数的权限层生成，界面只选答案，不能自行扩大范围。
                if (suggestion == null) return "This operation has no safe long-term scope to save";
                try { state.remember(suggestion, snapshot.revision()); }
                catch (java.io.IOException failed) { return "Rule was not saved; the operation was not executed: " + failed.getMessage(); }
                continue;
            }
            checked.set(new CheckedCall(tool, exact, context == null ? "" : context.id(), context == null ? "" : context.actorScope(), snapshot.revision(), true));
            return null;
        }
        return "The session is closed or execution was interrupted";
    }
    /** 解释各类已知工具；新工具未声明检查时进入不确定判断，不静默信任。 */
    private Analysis analyze(String tool, ObjectNode input, com.jadecode.execution.ToolIdentity identity) {
        if (identity.kind() == com.jadecode.execution.ToolIdentity.Kind.MCP) return mcp.analyze(tool, input, identity);
        if (Set.of("read_file", "write_file", "edit_file").contains(tool)) return files.analyze(tool, input);
        if (tool.equals("glob")) return files.analyzeGlob(input);
        if (tool.equals("bash")) return shell.analyze(tool, input);
        if (Set.of("import_resource","publish_result","prepare_environment","workspace_status").contains(tool))
            return new WorkspacePermissionCheck(resolver).analyze(tool,input);
        return new Analysis(tool, resolver.workspaceReal(), List.of(), List.of(), ADMIN_WRITES.contains(tool), true, !INTERNAL.contains(tool), "");
    }
    /** 显式拒绝/询问优先；默认硬保护不能被普通 allow 推翻。 */
    public PermissionDecision evaluate(Analysis action, PermissionState.Snapshot snapshot) {
        if (managedWorkspace && snapshot.profile()==PermissionProfile.FULL_ACCESS)
            return PermissionDecision.deny("workspace.sandbox-required","Managed workspaces require Docker; select Workspace or Approve for Me");
        if (!action.error().isEmpty()) return PermissionDecision.deny("input.invalid", action.error());
        if (memoryRoot != null && action.resources().stream().anyMatch(r -> !r.operation().equals("READ")
                && (r.path().startsWith(memoryRoot) || memoryRoot.startsWith(r.path()))))
            return PermissionDecision.deny("memory.read-only", "Formal memory is read-only; propose changes with memory_note");
        if (runtimeRoot != null && action.resources().stream().anyMatch(r -> !r.operation().equals("READ")
                && (r.path().startsWith(runtimeRoot) || runtimeRoot.startsWith(r.path()))))
            return PermissionDecision.deny("runtime.read-only", "Runtime state is managed by Harness tools and is read-only to file commands");
        var denied = matcher.first(snapshot.rules(), PermissionRule.Effect.DENY, action);
        if (denied != null) return PermissionDecision.deny(denied.id(), "Matched deny rule: " + denied.id());
        var asked = matcher.first(snapshot.rules(), PermissionRule.Effect.ASK, action);
        if (asked != null) return PermissionDecision.ask(asked.id(), "Matched required-ask rule: " + asked.id(), true);
        var protection = defaults.protectedDecision(action, snapshot);
        if (protection != null) return protection;
        if (action.uncertaintyReason().equals("managed.network"))
            return PermissionDecision.ask("workspace.network","Repository import requires network access; source and revision are shown in this request",false);
        // 尚未声明检查方式的新工具，不能因 Full Access 绕过集成时的显式确认。
        if (action.commands().isEmpty() && action.resources().isEmpty() && action.uncertain())
            return PermissionDecision.ask("tool.unknown", "This tool has not declared a permission check", true);
        if (action.tool().equals("read_file") && !action.resources().isEmpty()
                && action.resources().stream().allMatch(r -> r.operation().equals("READ") && (memoryReadable.test(r.path()) || artifactReadable.test(r.path()))))
            return PermissionDecision.allow();
        if (matcher.covers(snapshot.rules(), action)) return PermissionDecision.allow();
        return defaults.ordinary(action, snapshot, sandboxed);
    }
    /** 只为可准确表达的单命令/单文件生成建议；不保存重定向或复杂脚本的宽泛前缀。 */
    private PermissionRule suggestion(Analysis action) {
        if (action.uncertain()) return null;
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
