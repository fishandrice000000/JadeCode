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
        if (runtime == null || closed.get()) throw new IllegalStateException("尚未打开会话或已经退出");
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
        if (changing || !current().sessionId().equals(expectedId)) throw new IOException("会话已经切换，未修改另一会话的名称");
        current().rename(title);
    }

    /** CLI 在工作线程调用，当前网络响应不受影响，下一次请求采用新值。 */
    public synchronized void thinking(String expectedId, com.jadecode.llm.ThinkingSettings value) throws IOException {
        if (changing || !current().sessionId().equals(expectedId)) throw new IOException("会话已经切换，未修改另一会话的设置");
        current().changeThinking(value);
    }

    /** 退出后仍可读取已保存身份，不调用已关闭运行时的执行方法。 */
    public SessionRuntime.Display display() { SessionRuntime runtime = current; return runtime == null ? null : runtime.display(); }

    /**
     * 打开指定 UUID；id 为 null 时创建新会话。此方法不 start，UI 安装历史后才启动消费者。
     * 冻结检查包括队列、后台和队友，不会取消正在进行的工作来强行切换。
     * @throws IOException 目标不能恢复、当前不能安全切换或装配失败
     */
    public Opened open(String id) throws IOException {
        SessionRuntime old;
        long next;
        synchronized (this) {
            if (closed.get() || changing) throw new IOException("会话正在切换或 CLI 已退出");
            if (id != null && current != null && id.equals(current.sessionId())) throw new IOException("该会话已经打开");
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
                if (!frozen) throw new IOException("当前仍有任务、排队消息、后台结果或存活队友，请处理完后再 /resume");
            }
            candidate = factory.create(next);
            if (prepared == null) prepared = store.create(candidate.model());
            candidate.restore(prepared.journal(), prepared.restored());
            synchronized (this) {
                if (closed.get()) throw new IOException("CLI 已退出，取消会话切换");
                if (old != null) {
                    old.close();
                    if (!old.isStopped()) throw new IOException("旧会话线程尚未停止，未启用目标会话；请退出 CLI 后恢复");
                }
                current = candidate; committed = true;
            }
            return new Opened(next, prepared.restored(), candidate.model());
        } catch (IOException | RuntimeException failure) {
            throw new IOException("打开会话失败: " + failure.getMessage(), failure);
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
