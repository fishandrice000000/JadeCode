package com.jadecode.team;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import com.jadecode.execution.ExecutionContext;
import com.jadecode.execution.AssignmentContext;
import com.jadecode.execution.AssignmentScope;
import com.jadecode.execution.ExecutionNotice;
import com.jadecode.execution.ExecutionObserver;
import com.jadecode.session.SessionEvent.Incoming;
import com.jadecode.session.SessionRuntime;
import com.jadecode.tasks.Task;
import com.jadecode.tasks.TaskStore;
import com.jadecode.util.Json;

/**
 * 管理一个 Leader 的常驻队友、派工记录和文件邮箱，不执行模型循环。
 *
 * <p>Leader 先创建持久任务，再由 assign 替队友认领并投递。队友使用自己的 SessionRuntime，
 * 完成后通过 submitResult 同时保存报告、更新状态、通知 Leader。所有身份由工具装配时固定，
 * 不让模型填写 fromAgentId。方法内的同步保护本进程登记，不提供跨进程或文件系统沙箱。</p>
 *
 * <p>每次派工有独立 ID。重复报告只能重试同一份内容，不重做业务；旧派工永远不能改新派工。
 * 多文件写入不是事务，所以每阶段留下实际完成的材料，出错明确返回并允许继续提交。</p>
 */
public final class TeamManager implements AutoCloseable {
    public static final String LEADER = "leader";
    private final TaskStore tasks;
    private final TeamMailbox mailbox;
    private final Consumer<String> errors;
    private final Map<String, Member> members = new LinkedHashMap<>();
    private final Map<String, Assignment> assignments = new LinkedHashMap<>();
    private final Map<String, String> current = new LinkedHashMap<>();
    private final Map<String, Incoming> pending = new LinkedHashMap<>();
    /** 重试沿用原始权限来源，不能把旧定时邮件改成新用户授权。函数不写入文件。 */
    private final Map<String, ExecutionContext> origins = new LinkedHashMap<>();
    private final java.util.Set<String> delivered = new java.util.HashSet<>();
    private final Map<String, Incoming> communications = new LinkedHashMap<>();
    private final Map<String, String> replies = new LinkedHashMap<>();
    private ExecutionObserver observer = ExecutionObserver.NONE;
    private SessionRuntime leader;

    /** 装配只读通知端口；状态变化触发快照，通知异常不改变已经执行的业务。 */
    public void observe(ExecutionObserver value) { observer = value; }

    /** 普通状态仅刷新快照，关键问题/报告才产生可回看的通知。 */
    private void changed(String actor, String id, String title, String detail, String state) {
        observer.publish(ExecutionNotice.event(ExecutionNotice.Kind.TEAM, id, title, detail, state).actor(actor));
    }

    private BiFunction<String, String, SessionRuntime> factory;
    private boolean closed;

    /** 仅绑定依赖，不创建队友、不发模型请求、不恢复旧会话。 */
    public TeamManager(com.jadecode.config.WorkspacePaths paths, TaskStore tasks, Consumer<String> errors) {
        this.tasks = tasks;
        this.mailbox = new TeamMailbox(paths);
        this.errors = errors;
    }

    /** 工厂先装配 Leader，再安装队友工厂；启动模型前只能绑定一次。 */
    public synchronized void bind(SessionRuntime leader, BiFunction<String, String, SessionRuntime> factory) {
        if (this.leader != null) throw new IllegalStateException("Team is already bound");
        this.leader = leader;
        this.factory = factory;
        leader.teamView(this::snapshot);
        leader.teamReports(this::reportText);
    }

    /** 共享同一存储实例，让 Leader 与队友的状态更新使用同一进程锁。 */
    public TaskStore tasks() { return tasks; }

    /** 常驻队友即使 IDLE 仍是旧会话资源；切换前由 Leader 显式关闭，不暗中丢掉它们。 */
    public synchronized boolean hasLiveMembers() {
        return members.values().stream().anyMatch(member -> !member.closed || !member.runtime.isStopped());
    }

    /**
     * 创建没有业务请求的空闲队友，历史不复制 Leader。创建成功后登记名册，等待明确派工。
     * 已关闭成员不占三个常驻名额；新 ID 不能复用旧身份以免接收旧信。
     */
    public synchronized String spawn(String role) throws IOException {
        ensureOpen();
        TeammateRole parsed = TeammateRole.parse(role);
        if (members.values().stream().filter(m -> !m.closed).count() >= 3) throw new IllegalStateException("At most three persistent teammates");
        String id;
        do { id = "agent_" + UUID.randomUUID().toString().substring(0, 8); }
        while (members.containsKey(id));
        String normalized = parsed.name() + "\n" + parsed.responsibilities();
        SessionRuntime runtime = factory.apply(id, normalized);
        Member member = new Member(id, normalized, parsed.name(), parsed.responsibilities(), runtime);
        members.put(id, member);
        try {
            saveRoster();
            runtime.start(false);
        } catch (IOException | RuntimeException failure) {
            members.remove(id);
            runtime.close();
            throw failure;
        }
        changed(id, id, "Team", "", "UPDATED");
        return id;
    }

    /** 返回实际运行状态，不调用队友模型，也不把空闲误认为任务业务成功。 */
    public synchronized List<Map<String, Object>> list() {
        return members.values().stream().map(m -> Map.<String, Object>of(
                "agent_id", m.id, "name", m.name, "role", m.role, "state", m.runtime.state().name(), "closed", m.closed)).toList();
    }

    /**
     * 先认领，再持久化派工并投递。Inbox 明确拒绝时恢复 pending；已经入队但执行失败不撤销。
     * 新派工固定非插入式，队友正忙时只排队，不能替换它正在做的另一个任务。
     */
    public synchronized String assign(String to, String taskId, String summary, String body, ExecutionContext context) throws IOException {
        return assign(to, taskId, summary, body, context, true, AssignmentScope.EMPTY);
    }

    /**
     * 每次派工保存独立的范围与原执行编号。先登记等待再投递，快速完成也不会漏掉唤醒。
     * 范围只是分工数据，实际权限仍由 Gate 检查；本方法不重放失败业务。
     */
    public synchronized String assign(String to, String taskId, String summary, String body, ExecutionContext context,
            boolean required, AssignmentScope scope) throws IOException {
        ensureOpen();
        if (scope == null) throw new IllegalArgumentException("Assignment scope is required");
        if (context == null || context.isCancelled()) throw new IllegalStateException("Assignment source does not exist or has exited");
        member(to);
        Task task = tasks.claim(taskId, to);
        String id = "assignment_" + UUID.randomUUID();
        Assignment assignment = new Assignment(id, taskId, to, task.subject(), context, required, scope);
        assignments.put(id, assignment);
        current.put(taskId, id);
        try {
            if (required) leader.expectTeamResult(id, context.id());
            assignment.mail = prepare(LEADER, to, taskId, id, "assignment", summary,
                    "Task: " + task.subject() + "\n\n" + task.description() + "\n\n" + body + "\n\nAssigned scope: " + Json.mapper().writeValueAsString(scope), false, true);
            persist(assignment);
            CompletableFuture<String> result = deliver(assignment.mail, context);
            // 异常完成只表示接单后出错，不能重新执行已经做过的工具。
            result.whenComplete((answer, failure) -> {
                if (failure != null) executionFailed(id, failure);
                else returned(id, answer);
            });
            changed(to, id, "Assignment queued", summary, "UPDATED");
            return id;
        } catch (IOException | RuntimeException failure) {
            // 只有尚未入队才允许撤销。已交付后的 pending 清理失败由 deliver 内单独报告。
            if (assignment.mail == null || !delivered.contains(assignment.mail.id())) {
                assignment.abandoned = true;
                leader.forgetTeamResult(id);
                try { tasks.release(taskId, to); }
                catch (IOException | RuntimeException rollback) { failure.addSuppressed(rollback); }
                try { persist(assignment); }
                catch (IOException | RuntimeException recording) { failure.addSuppressed(recording); }
            }
            throw failure;
        }
    }

    /**
     * 普通信件只允许 Leader↔队友。正文先落盘，摘要再进入目标 Inbox；返回真实消息 ID。
     * 不允许队友私自给另一个队友派活；也不把通信正文解释成新的权限授权。
     */
    public synchronized String send(String from, String to, String taskId, String assignmentId, String kind,
            String summary, String body, boolean insertion, boolean wake, ExecutionContext context) throws IOException {
        return send(from, to, taskId, assignmentId, kind, summary, body, insertion, wake, context, null);
    }

    /** 回复只能关联双方一致、同一派工且已经投递的问题；发送失败不提前标记已回复。 */
    public synchronized String send(String from, String to, String taskId, String assignmentId, String kind,
            String summary, String body, boolean insertion, boolean wake, ExecutionContext context, String replyTo) throws IOException {
        ensureOpen();
        if (replyTo != null) {
            Incoming question = communications.get(replyTo);
            if (question == null || !question.kind().equals("question") || !delivered.contains(replyTo)
                    || !question.toAgentId().equals(from) || !question.fromAgentId().equals(to)
                    || !java.util.Objects.equals(question.assignmentId(), assignmentId)
                    || !java.util.Objects.equals(question.taskId(), taskId))
                throw new IllegalArgumentException("reply_to must reference a delivered question between these actors and this assignment");
        }
        if (!from.equals(LEADER) && !to.equals(LEADER)) throw new IllegalArgumentException("Teammates may only message the Leader");
        if (from.equals(to)) throw new IllegalArgumentException("Cannot deliver a message to self");
        if (!from.equals(LEADER)) member(from);
        if (!to.equals(LEADER)) member(to);
        if (taskId != null) tasks.load(taskId);
        if (assignmentId != null) {
            Assignment assignment = assignment(assignmentId);
            if (!assignment.owner.equals(from.equals(LEADER) ? to : from)
                    || !assignment.task.equals(taskId)) throw new IllegalArgumentException("Task or assignment does not match the communication target");
        }
        Incoming incoming = prepare(from, to, taskId, assignmentId, kind, summary, body, insertion, wake);
        if (replyTo != null) {
            // 关联先落盘，只有实际投递后才在快照里视为已答复；重试投递沿用这份关系。
            write(mailbox.root().resolve("threads").resolve(incoming.id() + ".json"), Map.of("reply_to", replyTo, "message_id", incoming.id()));
            replies.put(incoming.id(), replyTo);
        }
        deliver(incoming, context);
        changed(from, incoming.id(), kind.equals("question") ? "Question" : "Team", summary,
                kind.equals("question") ? "QUESTION" : "UPDATED");
        return incoming.id();
    }

    /**
     * 提交一份最终报告：正文 → 任务状态 → Leader 通知。三步成功才返回成功。
     * 部分成功后可以用相同参数重试；已保存的报告不可换正文，避免同一提交前后含义不同。
     * 只有 completed 满足依赖；failed/blocked 均等待 Leader 裁决下一步。
     */
    public synchronized String submitResult(String actor, String id, Task.Status status, String summary, String body) throws IOException {
        ensureOpen();
        Assignment assignment = assignment(id);
        if (!assignment.owner.equals(actor) || !id.equals(current.get(assignment.task)) || assignment.abandoned) {
            throw new IllegalArgumentException("An old assignment or non-owner cannot update task status");
        }
        if (status != Task.Status.COMPLETED && status != Task.Status.FAILED && status != Task.Status.BLOCKED) {
            throw new IllegalArgumentException("Report status must be completed/failed/blocked");
        }
        if (status == Task.Status.COMPLETED && member(actor).runtime.hasRequiredBackground()) {
            throw new IllegalStateException("The assignment still has required background results; cannot report success yet");
        }
        if (assignment.report == null) {
            assignment.report = prepare(actor, LEADER, assignment.task, id, "result", summary, body, true, true);
            assignment.status = status;
            assignment.body = body;
            persist(assignment);
        } else if (assignment.status != status || !assignment.report.summary().equals(summary) || !assignment.body.equals(body)) {
            throw new IllegalArgumentException("Retries must use the same report; send additional content as a separate message");
        }
        Task task = tasks.load(assignment.task);
        if (task.status() == Task.Status.IN_PROGRESS) {
            if (status == Task.Status.COMPLETED) tasks.complete(task.id(), actor);
            else tasks.stop(task.id(), actor, status);
        } else if (task.status() != status || !actor.equals(task.owner())) {
            throw new IllegalArgumentException("Task status changed; cannot overwrite it");
        }
        persist(assignment);
        deliver(assignment.report, assignment.context);
        assignment.finished = true;
        assignment.deliveryError = "";
        persist(assignment);
        changed(actor, assignment.report.id(), "Report submitted", summary, "REPORT");
        return assignment.report.id();
    }

    /** Leader 显式恢复 failed/blocked 任务；旧派工随之失效，新派工将分配新编号。 */
    public synchronized Task reopen(String taskId) throws IOException {
        Task task = tasks.load(taskId);
        if (task.status() != Task.Status.FAILED && task.status() != Task.Status.BLOCKED) {
            throw new IllegalArgumentException("Only failed/blocked tasks can be reopened");
        }
        Task reopened = tasks.release(taskId, task.owner());
        current.remove(taskId);
        return reopened;
    }

    /**
     * 发送失败后只重试投递，不运行报告所描述的业务。旧派工/已撤销派工不能借重试重新启动。
     * 结果报告若任务状态尚未更新，须重试 submit_task_result，不能跳过状态登记。
     */
    public synchronized String retry(String actor, String messageId, ExecutionContext context) throws IOException {
        Incoming incoming = pending.get(messageId);
        if (incoming == null || !incoming.fromAgentId().equals(actor)) throw new IllegalArgumentException("No retryable message for this sender");
        if (incoming.assignmentId() != null) {
            Assignment assignment = assignment(incoming.assignmentId());
            if (assignment.abandoned || !assignment.id.equals(current.get(assignment.task))) throw new IllegalArgumentException("Assignment is no longer valid");
            if (incoming.kind().equals("result") && tasks.load(assignment.task).status() != assignment.status) {
                throw new IllegalStateException("Retry submit_task_result with the original arguments first to register the task status");
            }
        }
        deliver(incoming, context);
        if (incoming.kind().equals("result")) {
            Assignment assignment = assignment(incoming.assignmentId());
            assignment.finished = true;
            persist(assignment);
        }
        changed(actor, messageId, "Team", "", "UPDATED");
        return messageId;
    }

    /**
     * Leader 关闭指定成员。先禁止新投递并在锁外关闭运行时，再记录未完成派工 blocked。
     * 不持有管理器锁等待线程退出，否则队友恰好提交报告会互相等待。
     * 即使状态写盘失败，队友也已停止；异常如实交给调用方，不能假称状态也已保存。
     */
    public void shutdown(String id) throws IOException {
        Member member;
        synchronized (this) {
            member = member(id);
            member.closed = true;
        }
        member.runtime.close();
        synchronized (this) {
            IOException recordingFailure = null;
            for (Assignment assignment : assignments.values()) {
                if (!assignment.owner.equals(id) || assignment.finished || assignment.abandoned) continue;
                try {
                    assignment.closeReason = "Leader/session closed teammate before completion";
                    if (!closed) {
                        // Leader 仍存活时提交 blocked 报告；即使记录失败也必须解除无解释的等待。
                        if (assignment.report == null) submitResult(id, assignment.id, Task.Status.BLOCKED, "Teammate closed", assignment.closeReason);
                        else submitResult(id, assignment.id, assignment.status, assignment.report.summary(), assignment.body);
                    } else {
                        Task task = tasks.load(assignment.task);
                        if (task.status() == Task.Status.IN_PROGRESS && assignment.id.equals(current.get(assignment.task)))
                            tasks.stop(task.id(), id, Task.Status.BLOCKED);
                        persist(assignment);
                    }
                } catch (IOException | RuntimeException failure) {
                    if (!closed) deliveryFailure(assignment, failure);
                    if (recordingFailure == null) recordingFailure = new IOException("Could not record all closed assignments", failure);
                    else recordingFailure.addSuppressed(failure);
                }
            }
            saveRoster();
            changed(id, id, "Team", "", "UPDATED");
            if (recordingFailure != null) throw recordingFailure;
        }
    }

    /** 保存正文和 pending 后才登记进程内索引，失败不伪装成已发送。 */
    private Incoming prepare(String from, String to, String task, String assignment, String kind,
            String summary, String body, boolean insertion, boolean wake) throws IOException {
        Incoming incoming = mailbox.prepare(from, to, task, assignment, kind, summary, body, insertion, wake);
        pending.put(incoming.id(), incoming);
        communications.put(incoming.id(), incoming);
        return incoming;
    }

    /**
     * Harness 取出已持久化的邮件，送进接收方 Inbox。入队失败时保留 pending 和正文。
     * 成功后先记录 delivered，再清 pending，清理失败不会重复发送已入队消息。
     */
    private CompletableFuture<String> deliver(Incoming incoming, ExecutionContext context) throws IOException {
        if (delivered.contains(incoming.id())) return CompletableFuture.completedFuture("Already enqueued");
        SessionRuntime target = incoming.toAgentId().equals(LEADER) ? leader : member(incoming.toAgentId()).runtime;
        // 投递前重新读取并检查文件里的元数据，正文仍留给接收模型按需读取。
        Path pendingPath = mailbox.root().resolve("pending").resolve(incoming.id() + ".json");
        Incoming stored = Json.mapper().readValue(Files.readString(pendingPath), Incoming.class);
        if (!stored.equals(incoming)) throw new IOException("Mailbox metadata changed; delivery refused");
        ExecutionContext origin = origins.computeIfAbsent(incoming.id(), id -> context);
        // 向队友投递时绑定该次派工的固定范围；给 Leader 的报告不继承队友的执行身份。
        ExecutionContext routed = origin.withAssignment(null);
        if (!incoming.toAgentId().equals(LEADER) && incoming.assignmentId() != null) {
            Assignment a = assignment(incoming.assignmentId());
            routed = routed.withAssignment(new AssignmentContext(a.id, a.task, a.owner, a.context.id(), a.scope));
        }
        var accepted = incoming.kind().equals("result") && incoming.toAgentId().equals(LEADER)
                ? target.receiveTeamResult(stored, origin, incoming.assignmentId(), assignment(incoming.assignmentId()).required)
                : target.tryReceive(stored, routed);
        if (accepted == null) throw new IllegalStateException("Message " + incoming.id() + " was not delivered: the receiver is closed or the Inbox is full; "
                + (incoming.kind().equals("assignment") ? "the claim will be rolled back; assign_task again for a new assignment id" : "submission or delivery can be retried"));
        delivered.add(incoming.id());
        try { mailbox.delivered(incoming); }
        catch (IOException cleanup) { errors.accept("[Warning] Mail enqueued; pending cleanup failed: " + incoming.id()); }
        return accepted;
    }

    /** 会话异常结束时登记失败并通知 Leader，不自动重做已经执行过的业务。 */
    private synchronized void executionFailed(String id, Throwable failure) {
        Assignment assignment = assignments.get(id);
        if (closed || assignment == null || assignment.finished || assignment.abandoned
                || members.get(assignment.owner).closed) return;
        if (assignment.report != null) {
            // 工具异常前已经保存过报告时只续办提交；不能把原报告替换成另一份失败正文。
            try { submitResult(assignment.owner, id, assignment.status, assignment.report.summary(), assignment.body); }
            catch (IOException | RuntimeException reporting) { deliveryFailure(assignment, reporting); }
            return;
        }
        try {
            submitResult(assignment.owner, id, Task.Status.FAILED, "Teammate execution failed; Leader decision required",
                    "Execution failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        } catch (IOException | RuntimeException reportFailure) {
            deliveryFailure(assignment, reportFailure);
        }
    }

    /**
     * 一次执行正常返回后记录非唤起式空闲通知；返回文字本身不会自动把任务标记 completed。
     * 正式报告仍须 submit_task_result。通知失败只告警，不能因此重新执行队友工作。
     */
    private synchronized void returned(String id, String answer) {
        Assignment assignment = assignments.get(id);
        if (closed || assignment == null || assignment.abandoned || members.get(assignment.owner).closed) return;
        if (assignment.report == null) {
            // 正常返回却未交报告不是成功：显式 blocked，让 Leader 决定补问或重新派工。
            try { submitResult(assignment.owner, id, Task.Status.BLOCKED, "Missing report",
                    "The teammate returned without submit_task_result. Final text: " + (answer == null ? "" : answer)); }
            catch (IOException | RuntimeException failure) { deliveryFailure(assignment, failure); }
            return;
        }
        if (!assignment.finished) {
            // 报告已保存但先前投递失败，只重试交付，不能重新执行业务。
            try { submitResult(assignment.owner, id, assignment.status, assignment.report.summary(), assignment.body); }
            catch (IOException | RuntimeException failure) { deliveryFailure(assignment, failure); }
            return;
        }
        try {
            Incoming incoming = prepare(assignment.owner, LEADER, assignment.task, id, "idle",
                    "The teammate returned this turn; this is not task acceptance. Use the formal report and task status.",
                    answer == null || answer.isBlank() ? "No final text" : answer, false, false);
            deliver(incoming, assignment.context);
        } catch (IOException | RuntimeException failure) {
            errors.accept("[Warning] Teammate return notification not delivered: " + failure.getMessage());
        }
    }

    /**
     * 记录/投递故障也必须叫醒必要等待。通知说明未知状态，保留原材料供重试，绝不伪称已提交。
     * 该兜底不依赖磁盘再次写入，因此磁盘故障不会让 Leader 永远等不到解释。
     */
    private void deliveryFailure(Assignment a, Exception failure) {
        a.deliveryError = "Report recording/delivery failed: " + failure.getMessage();
        errors.accept("[Error] " + a.id + ": " + a.deliveryError);
        Incoming notice = new Incoming("failure_" + a.id, a.owner, LEADER, a.task, a.id, "result_error",
                a.deliveryError, null, true, true);
        leader.receiveTeamResult(notice, a.context, a.id, a.required);
        changed(a.owner, a.id, "Report delivery failed", a.deliveryError, "ERROR");
    }

    /** Leader 显式记录已核对的具体报告；不改变 completed 的任务图契约，不证明内容正确。 */
    public synchronized String review(String id, String reportId, String notes) throws IOException {
        ensureOpen();
        Assignment a = assignment(id);
        if (a.report == null || !a.report.id().equals(reportId) || !delivered.contains(reportId))
            throw new IllegalArgumentException("Review must reference a delivered report for this assignment");
        if (notes == null || notes.isBlank() || notes.length() > 4000) throw new IllegalArgumentException("Review notes must contain 1-4000 characters");
        String previous = a.reviewNotes;
        a.reviewNotes = notes;
        try { persist(a); } catch (IOException failure) { a.reviewNotes = previous; throw failure; }
        changed(LEADER, reportId, "Report reviewed", notes, "UPDATED");
        return reportId;
    }

    /**
     * 纯内存只读视图：保存所有派工在磁盘，界面每个成员至多展示最近 8 项加正在执行的一项。
     * 状态来自 runtime，当前派工来自执行上下文，不能把排队的最新派工误当作正在执行。
     */
    public synchronized List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Member m : members.values()) {
            // 1. 成员身份与运行状态独立于任务结果；空闲不表示任务已经提交或核对。
            var row = new LinkedHashMap<String, Object>();
            row.put("id", m.id); row.put("name", m.name); row.put("responsibilities", m.responsibilities);
            row.put("state", m.runtime.state().name()); row.put("closed", m.closed);
            row.put("currentAssignment", m.runtime.currentAssignmentId());
            var generalQuestions = communications.values().stream().filter(mail -> mail.kind().equals("question")
                    && mail.assignmentId() == null && delivered.contains(mail.id())
                    && (mail.fromAgentId().equals(m.id) || mail.toAgentId().equals(m.id))).toList();
            row.put("questions", generalQuestions.stream().skip(Math.max(0, generalQuestions.size() - 8))
                    .map(mail -> Map.of("id", mail.id(), "summary", preview(mail.summary()), "to", mail.toAgentId(),
                            "answered", replies.entrySet().stream().anyMatch(reply -> reply.getValue().equals(mail.id()) && delivered.contains(reply.getKey())))).toList());
            // 2. 磁盘保留全部派工。显示窗口只取近期记录，但较旧的正在执行项仍必须可见。
            List<Assignment> owned = assignments.values().stream().filter(a -> a.owner.equals(m.id)).toList();
            List<Map<String, Object>> history = new ArrayList<>();
            for (int i = 0; i < owned.size(); i++) {
                Assignment a = owned.get(i);
                if (i < owned.size() - 8 && !a.id.equals(m.runtime.currentAssignmentId())) continue;
                // 3. 每项复制自己的标题、范围和报告事实，不能借用成员最新派工的范围。
                var entry = new LinkedHashMap<String, Object>();
                entry.put("id", a.id); entry.put("taskId", a.task); entry.put("subject", a.subject);
                entry.put("leaderRunId", a.context.id()); entry.put("required", a.required);
                entry.put("scope", a.scope); entry.put("finished", a.finished); entry.put("abandoned", a.abandoned);
                entry.put("outcome", a.status == null ? "" : a.status.name());
                entry.put("reportId", a.report == null ? "" : a.report.id());
                entry.put("reportSummary", a.report == null ? "" : preview(a.report.summary()));
                entry.put("reportPath", a.report == null ? "" : a.report.bodyPath());
                entry.put("reviewNotes", a.reviewNotes); entry.put("deliveryError", a.deliveryError);
                // 4. 只采用已经投递且属于这次派工的通信；待投递答复不能把问题标成已回答。
                var messages = communications.values().stream().filter(mail -> a.id.equals(mail.assignmentId())
                        && delivered.contains(mail.id()) && !List.of("assignment", "idle", "result").contains(mail.kind())).toList();
                entry.put("progress", messages.isEmpty() ? "" : preview(messages.getLast().summary()));
                entry.put("questions", messages.stream().filter(mail -> mail.kind().equals("question")).skip(Math.max(0, messages.stream().filter(mail -> mail.kind().equals("question")).count() - 8))
                        .map(mail -> Map.of("id", mail.id(), "summary", preview(mail.summary()), "to", mail.toAgentId(),
                            "answered", replies.entrySet().stream().anyMatch(reply -> reply.getValue().equals(mail.id()) && delivered.contains(reply.getKey())))).toList());
                history.add(entry);
            }
            // 5. 计数基于完整记录，不受显示窗口裁剪影响；必要等待只计算当前 Leader 轮次。
            row.put("pendingReports", owned.stream().filter(a -> a.required && !a.finished && !a.abandoned
                    && a.deliveryError.isEmpty() && a.context.id().equals(leader.currentExecutionId())).count());
            row.put("toReview", owned.stream().filter(a -> a.finished && a.reviewNotes.isEmpty()).count());
            row.put("openQuestions", communications.values().stream().filter(mail -> mail.kind().equals("question")
                    && mail.fromAgentId().equals(m.id) && mail.toAgentId().equals(LEADER) && delivered.contains(mail.id()))
                    .filter(mail -> replies.entrySet().stream().noneMatch(reply -> reply.getValue().equals(mail.id()) && delivered.contains(reply.getKey()))).count());
            row.put("assignments", history); row.put("historyCount", owned.size()); result.add(Map.copyOf(row));
        }
        return List.copyOf(result);
    }

    /** 快照只带有界摘要，完整报告由显式只读端口读取，历史正文仍保留。 */
    private static String preview(String text) { return text.length() <= 512 ? text : text.substring(0, 512) + "…"; }

    /** 只读查看本 Team 的已登记报告，路径从记录取得，调用方不能指定任意文件。 */
    public synchronized String reportText(String id) throws IOException {
        Assignment a = assignment(id);
        if (a.report == null) throw new IllegalArgumentException("No report has been submitted");
        return mailbox.readBody(a.report);
    }

    /** 每次只原子替换一份记录，不声称多个 JSON 文件形成跨文件事务。 */
    private void write(Path target, Object value) throws IOException {
        mailbox.directory(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".update-", ".tmp");
        try {
            Files.writeString(temp, Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value));
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    /** 派工元数据只写可序列化事实，不把审批函数、客户端或整个 Session 写进文件。 */
    private void persist(Assignment assignment) throws IOException {
        var record = Json.mapper().createObjectNode();
        record.put("assignment_id", assignment.id).put("task_id", assignment.task).put("owner", assignment.owner)
                .put("finished", assignment.finished).put("abandoned", assignment.abandoned)
                .put("close_reason", assignment.closeReason)
                .put("subject", assignment.subject).put("leader_run_id", assignment.context.id())
                .put("wait_for_completion", assignment.required).put("review_notes", assignment.reviewNotes);
        record.set("scope", Json.mapper().valueToTree(assignment.scope));
        if (assignment.mail != null) record.put("task_mail", assignment.mail.id());
        if (assignment.report != null) record.put("report_mail", assignment.report.id()).put("outcome", assignment.status.name());
        write(mailbox.root().resolve("assignments").resolve(assignment.id + ".json"), record);
    }

    /** 名册只供查看，不在重启时假装成员线程依然存在。 */
    private void saveRoster() throws IOException { write(mailbox.root().resolve("config.json"), list()); }
    /** 拒绝不存在或已经关闭的身份，不能静默改投 Leader 或另一个队友。 */
    private Member member(String id) {
        Member member = members.get(id);
        if (member == null || member.closed) throw new IllegalArgumentException("Teammate does not exist or is closed: " + id);
        return member;
    }
    /** 查派工而不是猜测当前最近一项任务。 */
    private Assignment assignment(String id) {
        Assignment assignment = assignments.get(id);
        if (assignment == null) throw new IllegalArgumentException("Unknown assignment: " + id);
        return assignment;
    }
    private void ensureOpen() { if (closed || leader == null) throw new IllegalStateException("Team is closed or not wired"); }

    /** 只管理一个独立 Session 的身份与生命周期，不另写一份 teammate loop。 */
    private static final class Member {
        final String id;
        final String role, name, responsibilities;
        final SessionRuntime runtime;
        boolean closed;
        Member(String id, String role, String name, String responsibilities, SessionRuntime runtime) {
            this.id = id; this.role = role; this.name = name; this.responsibilities = responsibilities; this.runtime = runtime;
        }
    }
    /** 可重试提交的进程内进度，context 不写盘；原始业务不会因提交重试再次执行。 */
    private static final class Assignment {
        final String id, task, owner, subject;
        final boolean required;
        final AssignmentScope scope;
        final ExecutionContext context;
        Incoming mail, report;
        Task.Status status;
        String body, closeReason;
        String reviewNotes = "", deliveryError = "";
        boolean finished, abandoned;
        Assignment(String id, String task, String owner, String subject, ExecutionContext context,
                boolean required, AssignmentScope scope) {
            this.id = id; this.task = task; this.owner = owner; this.subject = subject; this.context = context;
            this.required = required; this.scope = scope;
        }
    }

    /** Leader 会话退出时关闭全部成员；即使某份状态写盘失败，也必须停止后续工具与进程。 */
    @Override public void close() {
        List<Member> closing;
        synchronized (this) {
            if (closed) return;
            // 先拒绝新派工和创建，再在锁外逐个停会话，避免关闭过程中又漏进新成员。
            closed = true;
            closing = new ArrayList<>(members.values());
        }
        for (Member member : closing) {
            if (member.closed) continue;
            try { shutdown(member.id); }
            catch (IOException | RuntimeException failure) {
                errors.accept("[Error] Team close metadata failed: " + failure.getMessage());
                member.runtime.close();
            }
        }
    }
}
