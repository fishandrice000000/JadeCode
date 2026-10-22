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

    /** 只读取清单，名称重复时由前端展示全部候选，不能替用户猜中某个 UUID。 */
    public List<SessionMetadata> find(String name) throws IOException { return store.find(name); }

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

    /** 退出可与后台加载并发：先置关闭标志，尚未提交的候选会话会在 open 中自行清理。 */
    @Override public void close() {
        closed.set(true);
        synchronized (this) { if (current != null) current.close(); }
    }
}
