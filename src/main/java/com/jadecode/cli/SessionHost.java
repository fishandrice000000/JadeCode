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
    /**
     * 按会话代次创建运行时的工厂，供宿主打开新会话或恢复历史时装配独立的执行对象。
     */
    @FunctionalInterface
    public interface Factory {
        /**
         * 为 generation 对应的新代次创建会话运行时；宿主随后恢复日志，并在客户端 ready 后启动消费线程。
         *
         * @param generation 客户端看到的会话代次，切换会话后随新代次更新
         * @return 与指定代次绑定、等待恢复和启动的会话运行时
         */
        SessionRuntime create(long generation);
    }

    /**
     * 只把恢复资料和代次交给 UI，不把历史的写入权交出去。
     *
     * @param generation 客户端看到的会话代次，切换会话后随新代次更新
     * @param restored 由持久日志恢复的历史、元数据、Todo 和告警
     * @param currentModel 新会话当前使用的模型名
     */
    public record Opened(long generation, RestoredSession restored, String currentModel) {
    }

    private final SessionStore store;
    private final Factory factory;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile SessionRuntime current;
    private long generation;
    /** 已经登记过初始任务的代次；-1 表示当前代次尚未登记。 */
    private long initialPromptGeneration = -1;
    private boolean changing;

    /**
     * 用当前工作区的数据布局建立会话存储，保存运行时工厂；后续 open 负责创建或恢复会话。存储告警交给 warnings。
     *
     * @param paths 真实工作区对应的持久数据分区布局
     * @param factory 根据会话代次创建完整运行时的工厂
     * @param warnings 接收文件或维护告警的文本出口
     */
    public SessionHost(WorkspacePaths paths, Factory factory, Consumer<String> warnings) {
        store = new SessionStore(paths, warnings);
        this.factory = factory;
    }

    /**
     * 返回宿主是否已经持有一份会话运行时，供状态采集选择是否读取当前会话。
     *
     * @return 宿主已持有会话运行时时为 true
     */
    public boolean hasCurrent() {
        return current != null;
    }

    /**
     * 关闭后检查不能调用 current()；保留未完成清理的失败状态供服务拒绝释放占用。
     *
     * @return 未持有运行时或当前运行时已经停止时为 true
     */
    public boolean isStopped() {
        return current == null || current.isStopped();
    }

    /**
     * 获取当前运行时只用于提交新输入；未选定会话时不会隐式创建或调用模型。
     *
     * @return 当前可提交工作的会话运行时
     * @throws IllegalStateException 尚未打开会话或宿主已经关闭
     */
    public SessionRuntime current() {
        SessionRuntime runtime = current;
        if (runtime == null || closed.get())
            throw new IllegalStateException("No session is open, or it has exited");
        return runtime;
    }

    /**
     * 当前会话不出现在候选中；只读取清单，不解析每份完整日志。
     *
     * @return 当前工作区中除已打开会话以外的历史元数据列表
     * @throws IOException 会话目录或元数据清单无法读取
     */
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
            if (!exactId.isEmpty())
                return exactId;
        }
        return store.find(query);
    }

    /**
     * 本地元数据操作仍落在当前运行时；与切换互斥，不把改名写入稍后打开的别的会话。
     *
     * @param expectedId 调用方看到的当前会话 ID
     * @param title 用户选择的会话标题或当前界面条目的标题
     * @throws IOException 会话身份已改变或标题持久保存失败
     */
    public synchronized void rename(String expectedId, String title) throws IOException {
        if (changing || !current().sessionId().equals(expectedId))
            throw new IOException("The session switched; another session's name was not changed");
        current().rename(title);
    }

    /**
     * CLI 在工作线程调用，当前网络响应不受影响，下一次请求采用新值。
     *
     * @param expectedId 调用方看到的当前会话 ID
     * @param value 需要保存到当前会话的新思考档位
     * @throws IOException 会话身份已改变或思考档位保存失败
     */
    public synchronized void thinking(String expectedId, com.jadecode.llm.ThinkingSettings value) throws IOException {
        if (changing || !current().sessionId().equals(expectedId))
            throw new IOException("The session switched; another session's settings were not changed");
        current().changeThinking(value);
    }

    /**
     * 退出后仍可读取已保存身份，不调用已关闭运行时的执行方法。
     *
     * @return 当前会话的展示资料；尚无会话时为 null
     */
    public SessionRuntime.Display display() {
        SessionRuntime runtime = current;
        return runtime == null ? null : runtime.display();
    }

    /**
     * 采集当前会话 MCP 服务状态，供界面列出已配置和已连接的服务。
     *
     * @return 当前会话的 MCP 只读服务快照；未打开会话时为空，不连接服务。
     */
    public java.util.List<com.jadecode.mcp.McpManager.ServerSnapshot> mcpServers() {
        SessionRuntime runtime = current;
        return runtime == null ? java.util.List.of() : runtime.mcpServers();
    }

    /**
     * 只读权限快照在关闭期间仍可显示；不能用要求“会话可操作”的 current() 来刷新页脚。
     *
     * @return 当前会话的权限快照；尚无会话时为 null
     */
    public com.jadecode.safety.PermissionState.Snapshot permissionSnapshot() {
        SessionRuntime runtime = current;
        return runtime == null ? null : runtime.permissionState().snapshot();
    }

    /**
     * 把宿主从当前空闲会话切换到新会话或指定历史，并返回新代次的打开结果。
     *
     * <p>处理过程：</p>
     * <ol>
     * <li>在宿主锁内标记切换中，固定旧运行时并分配下一代次。指定历史时先读取目标日志。</li>
     * <li>要求旧运行时冻结成功；队列、后台或队友尚有工作时，打开流程返回失败。</li>
     * <li>调用工厂创建候选运行时，创建或采用目标日志，将恢复历史交给候选。</li>
     * <li>回到宿主锁内关闭旧运行时，确认其停止后发布 candidate 为 current。</li>
     * <li>失败时关闭候选运行时与目标写者，解冻旧会话；最后清除 changing 标记。</li>
     * </ol>
     *
     * <p>本方法包含日志读取、锁领取与资源关闭。成功返回的是准备完成的会话，消费者由客户端通过快照 ready 后启动。</p>
     *
     * @param id 待打开的持久会话 ID；null 表示创建新会话
     * @return 新代次、恢复结果和模型名，消费线程等待后续 start
     * @throws IOException 目标不能恢复、当前不能安全切换或装配失败
     */
    public Opened open(String id) throws IOException {
        SessionRuntime old;
        long next;
        synchronized (this) {
            if (closed.get() || changing)
                throw new IOException("The session is switching or the CLI has exited");
            if (id != null && current != null && id.equals(current.sessionId()))
                throw new IOException("That session is already open");
            changing = true;
            old = current;
            next = ++generation;
        }
        SessionStore.Opened prepared = null;
        SessionRuntime candidate = null;
        boolean frozen = false, committed = false;
        try {
            // 先验证已有目标，失败不冻结原会话，也不让工厂重新读取认证/模型配置。
            if (id != null)
                prepared = store.open(id);
            if (old != null) {
                frozen = old.freezeForSwitch();
                if (!frozen)
                    throw new IOException(
                            "Tasks, queued messages, background results or live teammates remain; finish them before /resume");
            }
            candidate = factory.create(next);
            if (prepared == null)
                prepared = store.create(candidate.model(), candidate.backendIdentity());
            candidate.restore(prepared.journal(), prepared.restored());
            synchronized (this) {
                if (closed.get())
                    throw new IOException("CLI exited; session switch cancelled");
                if (old != null) {
                    old.close();
                    if (!old.isStopped())
                        throw new IOException(
                                "The old session thread has not stopped; the target session was not enabled. Exit the CLI and resume");
                }
                current = candidate;
                committed = true;
            }
            return new Opened(next, prepared.restored(), candidate.model());
        } catch (IOException | RuntimeException failure) {
            throw new IOException("Failed to open session: " + failure.getMessage(), failure);
        } finally {
            if (!committed) {
                try {
                    if (candidate != null)
                        candidate.close();
                    if (prepared != null)
                        prepared.close();
                } finally {
                    if (frozen)
                        old.unfreeze();
                    synchronized (this) {
                        changing = false;
                    }
                }
            }
            synchronized (this) {
                changing = false;
            }
        }
    }

    /**
     * 在 UI 已采用相同代次的历史之后调用，避免新事件抢在历史清屏之前出现。
     *
     * @param expectedGeneration 客户端所见的会话代次
     */
    public synchronized void start(long expectedGeneration) {
        if (!closed.get() && current != null && generation == expectedGeneration)
            current.start();
    }

    /**
     * 核对当前代次后登记一次首轮请求，再启动消费者，返回其整轮结果 Future。相同代次已登记或会话已变更时报错；
     * 提交阶段发生运行异常时撤销登记标记，供调用方重试。人工审批为 null 时由权限流程按无通道处理。
     *
     * @param expectedGeneration 客户端所见的会话代次
     * @param prompt 用户输入的任务正文
     * @param approval 本轮人工审批通道；null 表示本轮没有人工通道
     * @return 首轮任务的最终回答 Future，执行失败时以异常完成
     */
    public synchronized java.util.concurrent.CompletableFuture<String> startWithInitial(long expectedGeneration,
            String prompt, com.jadecode.safety.approval.Approval approval) {
        if (closed.get() || current == null || generation != expectedGeneration)
            throw new IllegalStateException("The session generation changed before its initial prompt could start");
        if (initialPromptGeneration == expectedGeneration)
            throw new IllegalStateException("The initial prompt for this session was already registered");
        initialPromptGeneration = expectedGeneration;
        try {
            var result = current.submit(prompt, approval);
            current.start();
            return result;
        } catch (RuntimeException failure) {
            // 登记失败就不启动模型；允许调用方以同一代次重试一次，而不是留下半启动状态。
            initialPromptGeneration = -1;
            throw failure;
        }
    }

    /**
     * 退出可与后台加载并发：先置关闭标志，尚未提交的候选会话会在 open 中自行清理。
     */
    @Override
    public void close() {
        closed.set(true);
        synchronized (this) {
            if (current != null)
                current.close();
        }
    }
}
