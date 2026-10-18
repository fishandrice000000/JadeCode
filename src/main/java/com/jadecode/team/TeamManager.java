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
    private SessionRuntime leader;
    private BiFunction<String, String, SessionRuntime> factory;
    private boolean closed;

    /** 仅绑定依赖，不创建队友、不发模型请求、不恢复旧会话。 */
    public TeamManager(Path workspace, TaskStore tasks, Consumer<String> errors) {
        this.tasks = tasks;
        this.mailbox = new TeamMailbox(workspace);
        this.errors = errors;
    }

    /** 工厂先装配 Leader，再安装队友工厂；启动模型前只能绑定一次。 */
    public synchronized void bind(SessionRuntime leader, BiFunction<String, String, SessionRuntime> factory) {
        if (this.leader != null) throw new IllegalStateException("Team 已绑定");
        this.leader = leader;
        this.factory = factory;
    }

    /** 共享同一存储实例，让 Leader 与队友的状态更新使用同一进程锁。 */
    public TaskStore tasks() { return tasks; }

    /**
     * 创建没有业务请求的空闲队友，历史不复制 Leader。创建成功后登记名册，等待明确派工。
     * 已关闭成员不占三个常驻名额；新 ID 不能复用旧身份以免接收旧信。
     */
    public synchronized String spawn(String role) throws IOException {
        ensureOpen();
        if (role == null || role.isBlank() || role.length() > 4000) throw new IllegalArgumentException("队友职责不能为空或过长");
        if (members.values().stream().filter(m -> !m.closed).count() >= 3) throw new IllegalStateException("至多三个常驻队友");
        String id;
        do { id = "agent_" + UUID.randomUUID().toString().substring(0, 8); }
        while (members.containsKey(id));
        SessionRuntime runtime = factory.apply(id, role);
        Member member = new Member(id, role, runtime);
        members.put(id, member);
        try {
            saveRoster();
            runtime.start(false);
        } catch (IOException | RuntimeException failure) {
            members.remove(id);
            runtime.close();
            throw failure;
        }
        return id;
    }

    /** 返回实际运行状态，不调用队友模型，也不把空闲误认为任务业务成功。 */
    public synchronized List<Map<String, Object>> list() {
        return members.values().stream().map(m -> Map.<String, Object>of(
                "agent_id", m.id, "role", m.role, "state", m.runtime.state().name(), "closed", m.closed)).toList();
    }

    /**
     * 先认领，再持久化派工并投递。Inbox 明确拒绝时恢复 pending；已经入队但执行失败不撤销。
     * 新派工固定非插入式，队友正忙时只排队，不能替换它正在做的另一个任务。
     */
    public synchronized String assign(String to, String taskId, String summary, String body, ExecutionContext context) throws IOException {
        ensureOpen();
        if (context == null || context.isCancelled()) throw new IllegalStateException("派工来源不存在或已退出");
        member(to);
        Task task = tasks.claim(taskId, to);
        String id = "assignment_" + UUID.randomUUID();
        Assignment assignment = new Assignment(id, taskId, to, context);
        assignments.put(id, assignment);
        current.put(taskId, id);
        try {
            assignment.mail = prepare(LEADER, to, taskId, id, "assignment", summary,
                    "Task: " + task.subject() + "\n\n" + task.description() + "\n\n" + body, false, true);
            persist(assignment);
            CompletableFuture<String> result = deliver(assignment.mail, context);
            // 异常完成只表示接单后出错，不能重新执行已经做过的工具。
            result.whenComplete((answer, failure) -> {
                if (failure != null) executionFailed(id, failure);
                else returned(id, answer);
            });
            return id;
        } catch (IOException | RuntimeException failure) {
            // 只有尚未入队才允许撤销。已交付后的 pending 清理失败由 deliver 内单独报告。
            if (assignment.mail == null || !delivered.contains(assignment.mail.id())) {
                assignment.abandoned = true;
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
        ensureOpen();
        if (!from.equals(LEADER) && !to.equals(LEADER)) throw new IllegalArgumentException("队友只能给 Leader 发消息");
        if (from.equals(to)) throw new IllegalArgumentException("不能给自己投递");
        if (!from.equals(LEADER)) member(from);
        if (!to.equals(LEADER)) member(to);
        if (taskId != null) tasks.load(taskId);
        if (assignmentId != null) {
            Assignment assignment = assignment(assignmentId);
            if (!assignment.owner.equals(from.equals(LEADER) ? to : from)
                    || !assignment.task.equals(taskId)) throw new IllegalArgumentException("任务或派工与通信对象不匹配");
        }
        Incoming incoming = prepare(from, to, taskId, assignmentId, kind, summary, body, insertion, wake);
        deliver(incoming, context);
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
            throw new IllegalArgumentException("旧派工或非原负责者不能更新任务状态");
        }
        if (status != Task.Status.COMPLETED && status != Task.Status.FAILED && status != Task.Status.BLOCKED) {
            throw new IllegalArgumentException("报告状态只能是 completed/failed/blocked");
        }
        if (status == Task.Status.COMPLETED && member(actor).runtime.hasRequiredBackground()) {
            throw new IllegalStateException("当前派工仍有必须处理的后台结果，不能先报告成功");
        }
        if (assignment.report == null) {
            assignment.report = prepare(actor, LEADER, assignment.task, id, "result", summary, body, true, true);
            assignment.status = status;
            assignment.body = body;
            persist(assignment);
        } else if (assignment.status != status || !assignment.report.summary().equals(summary) || !assignment.body.equals(body)) {
            throw new IllegalArgumentException("重试必须使用相同报告；补充内容请另发消息");
        }
        Task task = tasks.load(assignment.task);
        if (task.status() == Task.Status.IN_PROGRESS) {
            if (status == Task.Status.COMPLETED) tasks.complete(task.id(), actor);
            else tasks.stop(task.id(), actor, status);
        } else if (task.status() != status || !actor.equals(task.owner())) {
            throw new IllegalArgumentException("任务状态已改变，不能覆盖");
        }
        persist(assignment);
        deliver(assignment.report, assignment.context);
        assignment.finished = true;
        persist(assignment);
        return assignment.report.id();
    }

    /** Leader 显式恢复 failed/blocked 任务；旧派工随之失效，新派工将分配新编号。 */
    public synchronized Task reopen(String taskId) throws IOException {
        Task task = tasks.load(taskId);
        if (task.status() != Task.Status.FAILED && task.status() != Task.Status.BLOCKED) {
            throw new IllegalArgumentException("只能重新打开 failed/blocked 任务");
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
        if (incoming == null || !incoming.fromAgentId().equals(actor)) throw new IllegalArgumentException("没有本发送者可重试的消息");
        if (incoming.assignmentId() != null) {
            Assignment assignment = assignment(incoming.assignmentId());
            if (assignment.abandoned || !assignment.id.equals(current.get(assignment.task))) throw new IllegalArgumentException("派工已失效");
            if (incoming.kind().equals("result") && tasks.load(assignment.task).status() != assignment.status) {
                throw new IllegalStateException("先用原参数重试 submit_task_result，完成任务状态登记");
            }
        }
        deliver(incoming, context);
        if (incoming.kind().equals("result")) {
            Assignment assignment = assignment(incoming.assignmentId());
            assignment.finished = true;
            persist(assignment);
        }
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
            for (Assignment assignment : assignments.values()) {
                if (!assignment.owner.equals(id) || assignment.finished || assignment.abandoned) continue;
                Task task = tasks.load(assignment.task);
                if (task.status() == Task.Status.IN_PROGRESS && assignment.id.equals(current.get(assignment.task))) {
                    tasks.stop(task.id(), id, Task.Status.BLOCKED);
                }
                assignment.closeReason = "Leader/session closed teammate before completion";
                persist(assignment);
            }
            saveRoster();
        }
    }

    /** 保存正文和 pending 后才登记进程内索引，失败不伪装成已发送。 */
    private Incoming prepare(String from, String to, String task, String assignment, String kind,
            String summary, String body, boolean insertion, boolean wake) throws IOException {
        Incoming incoming = mailbox.prepare(from, to, task, assignment, kind, summary, body, insertion, wake);
        pending.put(incoming.id(), incoming);
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
        if (!stored.equals(incoming)) throw new IOException("邮箱元数据已改变，拒绝投递");
        ExecutionContext origin = origins.computeIfAbsent(incoming.id(), id -> context);
        var accepted = target.tryReceive(stored, origin);
        if (accepted == null) throw new IllegalStateException("消息 " + incoming.id() + " 未送达：接收方已关闭或 Inbox 已满；"
                + (incoming.kind().equals("assignment") ? "认领将尝试撤销，请重新 assign_task 并取得新编号" : "可重试提交/投递"));
        delivered.add(incoming.id());
        try { mailbox.delivered(incoming); }
        catch (IOException cleanup) { errors.accept("[Warning] Mail enqueued; pending cleanup failed: " + incoming.id()); }
        return accepted;
    }

    /** 会话异常结束时登记失败并通知 Leader，不自动重做已经执行过的业务。 */
    private synchronized void executionFailed(String id, Throwable failure) {
        Assignment assignment = assignments.get(id);
        if (closed || assignment == null || assignment.finished || assignment.abandoned || assignment.report != null
                || members.get(assignment.owner).closed) return;
        try {
            submitResult(assignment.owner, id, Task.Status.FAILED, "队友执行异常，需 Leader 决策",
                    "Execution failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        } catch (IOException | RuntimeException reportFailure) {
            errors.accept("[Error] Cannot register teammate failure for " + id + ": " + reportFailure.getMessage());
        }
    }

    /**
     * 一次执行正常返回后记录非唤起式空闲通知；返回文字本身不会自动把任务标记 completed。
     * 正式报告仍须 submit_task_result。通知失败只告警，不能因此重新执行队友工作。
     */
    private synchronized void returned(String id, String answer) {
        Assignment assignment = assignments.get(id);
        if (closed || assignment == null || assignment.abandoned || members.get(assignment.owner).closed) return;
        try {
            Incoming incoming = prepare(assignment.owner, LEADER, assignment.task, id, "idle",
                    "队友本轮已返回；这不等于任务验收通过，请结合正式报告与任务状态判断。",
                    answer == null || answer.isBlank() ? "No final text" : answer, false, false);
            deliver(incoming, assignment.context);
        } catch (IOException | RuntimeException failure) {
            errors.accept("[Warning] Teammate return notification not delivered: " + failure.getMessage());
        }
    }

    /** 每次只原子替换一份记录，不声称多个 JSON 文件形成跨文件事务。 */
    private void write(Path target, Object value) throws IOException {
        Files.createDirectories(target.getParent());
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
                .put("close_reason", assignment.closeReason);
        if (assignment.mail != null) record.put("task_mail", assignment.mail.id());
        if (assignment.report != null) record.put("report_mail", assignment.report.id()).put("outcome", assignment.status.name());
        write(mailbox.root().resolve("assignments").resolve(assignment.id + ".json"), record);
    }

    /** 名册只供查看，不在重启时假装成员线程依然存在。 */
    private void saveRoster() throws IOException { write(mailbox.root().resolve("config.json"), list()); }
    /** 拒绝不存在或已经关闭的身份，不能静默改投 Leader 或另一个队友。 */
    private Member member(String id) {
        Member member = members.get(id);
        if (member == null || member.closed) throw new IllegalArgumentException("队友不存在或已关闭: " + id);
        return member;
    }
    /** 查派工而不是猜测当前最近一项任务。 */
    private Assignment assignment(String id) {
        Assignment assignment = assignments.get(id);
        if (assignment == null) throw new IllegalArgumentException("未知派工: " + id);
        return assignment;
    }
    private void ensureOpen() { if (closed || leader == null) throw new IllegalStateException("Team 已关闭或未装配"); }

    /** 只管理一个独立 Session 的身份与生命周期，不另写一份 teammate loop。 */
    private static final class Member {
        final String id;
        final String role;
        final SessionRuntime runtime;
        boolean closed;
        Member(String id, String role, SessionRuntime runtime) { this.id = id; this.role = role; this.runtime = runtime; }
    }
    /** 可重试提交的进程内进度，context 不写盘；原始业务不会因提交重试再次执行。 */
    private static final class Assignment {
        final String id, task, owner;
        final ExecutionContext context;
        Incoming mail, report;
        Task.Status status;
        String body, closeReason;
        boolean finished, abandoned;
        Assignment(String id, String task, String owner, ExecutionContext context) {
            this.id = id; this.task = task; this.owner = owner; this.context = context;
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
