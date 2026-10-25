package com.jadecode.cli;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import com.jadecode.config.WorkspacePaths;
import com.jadecode.session.SessionRuntime;
import com.jadecode.session.history.RestoredSession;
import com.jadecode.session.history.SessionMetadata;
import com.jadecode.session.history.SessionStore;

/**
 * 管理 CLI 当前打开哪一个会话，不参与模型循环和终端绘制。
 *
 * <p>切换先加载目标并冻结空闲旧会话，再装配新运行时。加载/装配失败时旧会话保持可用。
 * 所有可能读盘、装配客户端或等待关闭的方法由 CLI 工作线程调用，不占住界面线程。</p>
 */
public final class SessionHost implements AutoCloseable {
    @FunctionalInterface public interface Factory { SessionRuntime create(long generation); }
    /** 只把恢复资料和代次交给 UI，不把历史的写入权交出去。 */
    public record Opened(long generation, RestoredSession restored, String currentModel) { }
    private final SessionStore store;
    private final Factory factory;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile SessionRuntime current;
    private long generation;
    /** 已经登记过初始任务的代次；-1 表示当前代次尚未登记。 */
    private long initialPromptGeneration = -1;
    private boolean changing;

    public SessionHost(WorkspacePaths paths, Factory factory, Consumer<String> warnings) {
        store = new SessionStore(paths, warnings); this.factory = factory;
    }

    public boolean hasCurrent() { return current != null; }

    /** 获取当前运行时只用于提交新输入；未选定会话时不会隐式创建或调用模型。 */
    public SessionRuntime current() {
        SessionRuntime runtime = current;
        if (runtime == null || closed.get()) throw new IllegalStateException("No session is open, or it has exited");
        return runtime;
    }

    /** 当前会话不出现在候选中；只读取清单，不解析每份完整日志。 */
    public List<SessionMetadata> list() throws IOException {
        String id = current == null ? "" : current.sessionId();
        return store.list().stream().filter(item -> !item.id().equals(id)).toList();
    }

    /**
     * 查找 /resume 的输入：标准 UUID 先按身份匹配，未命中再按标题匹配。
     *
     * <p>UUID 仅接受 8-4-4-4-12 个十六进制字符的完整形式（大小写均可）；不能直接用
     * UUID.fromString 判断格式，因为它也接受部分短写。普通文字直接交给标题查找，
     * 保留精确标题优先、否则部分标题、重名全部返回的规则。这里只读取当前工作区清单，
     * 不把输入拼接成路径，不打开日志或启动模型，也不修改输入或会话。</p>
     *
     * @param name 用户输入的 UUID 或标题；首尾空白忽略，空白标题由存储层拒绝
     * @return ID 命中时的唯一清单，否则为标题候选（可能为空或重名）
     * @throws IOException 清单无法读取
     */
    public List<SessionMetadata> find(String name) throws IOException {
        String query = name == null ? null : name.trim();
        if (query != null && query.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            // 同名标题不能覆盖真实 ID；只有不存在这个 ID 时才退回标题语义。
            var exactId = store.list().stream().filter(item -> item.id().equalsIgnoreCase(query)).toList();
            if (!exactId.isEmpty()) return exactId;
        }
        return store.find(query);
    }

    /** 本地元数据操作仍落在当前运行时；与切换互斥，不把改名写入稍后打开的别的会话。 */
    public synchronized void rename(String expectedId, String title) throws IOException {
        if (changing || !current().sessionId().equals(expectedId)) throw new IOException("The session switched; another session's name was not changed");
        current().rename(title);
    }

    /** CLI 在工作线程调用，当前网络响应不受影响，下一次请求采用新值。 */
    public synchronized void thinking(String expectedId, com.jadecode.llm.ThinkingSettings value) throws IOException {
        if (changing || !current().sessionId().equals(expectedId)) throw new IOException("The session switched; another session's settings were not changed");
        current().changeThinking(value);
    }

    /** 退出后仍可读取已保存身份，不调用已关闭运行时的执行方法。 */
    public SessionRuntime.Display display() { SessionRuntime runtime = current; return runtime == null ? null : runtime.display(); }
    /** @return 当前会话的 MCP 只读服务快照；未打开会话时为空，不连接服务。 */
    public java.util.List<com.jadecode.mcp.McpManager.ServerSnapshot> mcpServers() {
        SessionRuntime runtime = current;
        return runtime == null ? java.util.List.of() : runtime.mcpServers();
    }

    /** 只读权限快照在关闭期间仍可显示；不能用要求“会话可操作”的 current() 来刷新页脚。 */
    public com.jadecode.safety.PermissionState.Snapshot permissionSnapshot() {
        SessionRuntime runtime = current; return runtime == null ? null : runtime.permissionState().snapshot();
    }

    /**
     * 打开指定 UUID；id 为 null 时创建新会话。此方法不 start，UI 安装历史后才启动消费者。
     * 冻结检查包括队列、后台和队友，不会取消正在进行的工作来强行切换。
     * @throws IOException 目标不能恢复、当前不能安全切换或装配失败
     */
    public Opened open(String id) throws IOException {
        SessionRuntime old;
        long next;
        synchronized (this) {
            if (closed.get() || changing) throw new IOException("The session is switching or the CLI has exited");
            if (id != null && current != null && id.equals(current.sessionId())) throw new IOException("That session is already open");
            changing = true; old = current; next = ++generation;
        }
        SessionStore.Opened prepared = null;
        SessionRuntime candidate = null;
        boolean frozen = false, committed = false;
        try {
            // 先验证已有目标，失败不冻结原会话，也不让工厂重新读取认证/模型配置。
            if (id != null) prepared = store.open(id);
            if (old != null) {
                frozen = old.freezeForSwitch();
                if (!frozen) throw new IOException("Tasks, queued messages, background results or live teammates remain; finish them before /resume");
            }
            candidate = factory.create(next);
            if (prepared == null) prepared = store.create(candidate.model());
            candidate.restore(prepared.journal(), prepared.restored());
            synchronized (this) {
                if (closed.get()) throw new IOException("CLI exited; session switch cancelled");
                if (old != null) {
                    old.close();
                    if (!old.isStopped()) throw new IOException("The old session thread has not stopped; the target session was not enabled. Exit the CLI and resume");
                }
                current = candidate; committed = true;
            }
            return new Opened(next, prepared.restored(), candidate.model());
        } catch (IOException | RuntimeException failure) {
            throw new IOException("Failed to open session: " + failure.getMessage(), failure);
        } finally {
            if (!committed) {
                try {
                    if (candidate != null) candidate.close();
                    if (prepared != null) prepared.close();
                } finally {
                    if (frozen) old.unfreeze();
                    synchronized (this) { changing = false; }
                }
            }
            synchronized (this) { changing = false; }
        }
    }

    /** 在 UI 已采用相同代次的历史之后调用，避免新事件抢在历史清屏之前出现。 */
    public synchronized void start(long expectedGeneration) {
        if (!closed.get() && current != null && generation == expectedGeneration) current.start(true);
    }

    /**
     * 新前端的最小启动契约：界面 ready 之后、消费者启动之前，先登记唯一的一次初始任务，
     * 再按调用方选择启动消费者/cron。
     *
     * <p>为什么必须先入队再 start：{@code SessionRuntime.start(true)} 会立即启动 cron 轮询，
     * 若先 start 再 submit，一个已经到期的定时任务可能抢在用户首轮 prompt 之前执行。这里
     * 利用“已 restore 但尚未 start”的准备态允许 submit 入队（submit 本身只写 Inbox），然后
     * 启动消费者。重复 ready、重绘、fg、外部编辑器返回都不得重放初始任务，因此每个代次
     * 只允许登记一次。</p>
     *
     * @param expectedGeneration 调用方看到并已安装快照的代次
     * @param prompt             初始任务原文；空白任务不启动模型
     * @param approval           本次任务的审批通道（headless 传 null 表示无人工通道）
     * @param enableCron         交互模式为 true；headless 为 false，不运行未来定时任务
     * @return 该次初始任务的最终结果 Future；失败语义沿用 SessionRuntime.submit
     * @throws IllegalStateException 代次不匹配、会话已退出或已登记过初始任务
     */
    public synchronized java.util.concurrent.CompletableFuture<String> startWithInitial(long expectedGeneration,
            String prompt, com.jadecode.safety.approval.Approval approval, boolean enableCron) {
        if (closed.get() || current == null || generation != expectedGeneration)
            throw new IllegalStateException("The session generation changed before its initial prompt could start");
        if (initialPromptGeneration == expectedGeneration)
            throw new IllegalStateException("The initial prompt for this session was already registered");
        initialPromptGeneration = expectedGeneration;
        try {
            var result = current.submit(prompt, approval);
            current.start(enableCron);
            return result;
        } catch (RuntimeException failure) {
            // 登记失败就不启动模型；允许调用方以同一代次重试一次，而不是留下半启动状态。
            initialPromptGeneration = -1;
            throw failure;
        }
    }

    /** 退出可与后台加载并发：先置关闭标志，尚未提交的候选会话会在 open 中自行清理。 */
    @Override public void close() {
        closed.set(true);
        synchronized (this) { if (current != null) current.close(); }
    }
}
