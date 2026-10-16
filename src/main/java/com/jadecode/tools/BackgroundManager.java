package com.jadecode.tools;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jadecode.agent.ExecutionContext;

/**
 * 会话后台命令所有者：启动回执、完成事件和任务结束是三个不同的阶段。
 *
 * <p>每条后台命令拥有独立 shell 资源，失败任务只清理自己的必等命令。工作者只登记结果和
 * 唤醒消费者，不写历史、不调用模型、不读取终端。运行中、待领取、已领取待确认的总数最多
 * 32 项；摘要保留 500 字符，不增加完整输出落盘或跨重启恢复。</p>
 *
 * <p>一条命令的记录流转为：start 登记拥有权 → 工作线程写 ready → collect 暂时领取
 * → 模型合法回复后 acknowledge 释放容量。jobs 是未确认记录总表，ready 只是已经完成且
 * 尚未领取的子集。因此“进程已退出”“结果已领取”“模型已收到”不能用同一个布尔值表达。
 * required 只要求任务处理最终结果，不要求命令必须成功；失败结果同样交回模型判断。</p>
 */
public final class BackgroundManager implements AutoCloseable {
    /** 运行中、完成待领取和领取待确认共同占用的上限，避免内存无限积累。 */
    private static final int MAX_PENDING = 32;
    /** 发送给模型的通知正文上限，按 Java 字符计数，不是 token 或 UTF-8 字节数。 */
    private static final int SUMMARY_CHARS = 500;
    /** 装配时绑定的工作区，模型不能借后台参数改换目录。 */
    private final Path cwd;
    /** 单条命令的最长执行秒数，由 ShellCommand 执行超时处理。 */
    private final long timeoutSeconds;
    /** 同步命令资源，与各后台命令独立，整个会话关闭时一并终止。 */
    private final ShellCommand foreground;
    /** 所有未确认命令；访问受本对象监视器保护，包含已完成但尚未让模型看到的命令。 */
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    /** 完成顺序排列的未领取结果，不进入新任务的 FIFO 收件箱。 */
    private final ArrayDeque<Completion> ready = new ArrayDeque<>();
    /** 只唤醒运行器，默认空操作；没有监听器时结果仍保留在 ready。 */
    private Runnable listener = () -> { };
    /** 会话内 bg_id 序号，不是跨重启唯一的会话标识。 */
    private long counter;
    /** 锁保护的资源关闭标记，关闭后工作线程不能再发布完成记录。 */
    private boolean closed;

    /**
     * 使用单命令 120 秒上限绑定工作区，不启动进程、线程或文件 IO。
     * @param cwd 同步/后台命令共同使用的工作目录
     */
    public BackgroundManager(Path cwd) { this(cwd, 120); }

    /**
     * 绑定工作目录及命令超时；真正执行时每个后台命令单独创建 ShellCommand 资源。
     * @param cwd 命令工作目录，生产装配传工作区路径
     * @param timeoutSeconds 正数秒数；ShellCommand 构造时校验，不是会话等待总时长
     * @throws IllegalArgumentException 超时不是正数
     */
    public BackgroundManager(Path cwd, long timeoutSeconds) {
        this.cwd = cwd;
        this.timeoutSeconds = timeoutSeconds;
        foreground = new ShellCommand(cwd, timeoutSeconds);
    }

    /**
     * 替换完成唤醒入口，不消费/丢弃已有结果，也不为历史结果补发信号。
     * @param listener 非 null、快速且不抛异常的唤醒函数，可能由任一后台工作线程调用。
     *                 回调只能发信号，不能直接写会话历史或调用模型
     * @throws NullPointerException listener 为 null
     */
    public synchronized void onCompletion(Runnable listener) {
        this.listener = java.util.Objects.requireNonNull(listener);
    }

    /**
     * 登记已获准的命令并立即返回 ID，不等待执行结束。
     *
     * <p>本方法不执行安全审批，调用方必须先经过 PreToolUse。登记后创建虚拟工作线程；
     * 若线程启动意外失败则移除登记并关闭该 shell，再传播异常。ID 返回时 shell 可能尚未
     * 真正启动，最终状态以 Completion 为准。</p>
     * @param command 非空、已通过 PreToolUse 的命令
     * @param context Harness 提供的所属身份，不从模型 JSON 读取
     * @param required 是否必须在该任务结束前处理最终结果
     * @return 启动回执 ID，不表示执行或业务成功
     * @throws IllegalStateException 已关闭或未确认结果达到上限
     * @throws IllegalArgumentException command 缺失或为空白
     * @throws NullPointerException context 为 null
     */
    public synchronized String start(String command, ExecutionContext context, boolean required) {
        if (command == null || command.isBlank()) throw new IllegalArgumentException("Bash command cannot be empty");
        java.util.Objects.requireNonNull(context, "executionContext");
        if (closed || jobs.size() >= MAX_PENDING) {
            throw new IllegalStateException(closed ? "Background session is closed" : "Too many pending background tasks");
        }
        String id = "bg_%04d".formatted(++counter);
        Job job = new Job(id, context.id(), required, command, new ShellCommand(cwd, timeoutSeconds));
        // 先登记再启动，极快命令和并发关闭都能找到自己的资源。
        jobs.put(id, job);
        try {
            Thread.ofVirtual().name("jade-" + id).start(() -> run(job));
        } catch (RuntimeException failure) {
            jobs.remove(id);
            job.shell().close();
            throw failure;
        }
        return id;
    }

    /**
     * 同步执行一条已获准命令，阻塞至结束/失败/超时，不创建 bg_id 或后台通知。
     * @param command shell 命令原文，由 BashTool 完成非空检查
     * @return ShellCommand 给出的输出或 Error 文本；本方法不修改模型历史
     */
    String runForeground(String command) { return foreground.run(command).text(); }

    /**
     * 后台工作线程入口：执行 shell IO、关闭其独立资源，再发布一次完成值。
     *
     * <p>零退出码记 completed，其余（包括无法取得退出码）记 failed。执行期运行异常
     * 转为失败记录；若任务取消/会话关闭已移除拥有权，晚到结果直接丢弃，不复活任务。
     * 发布后锁外唤醒，避免工作者拿着管理器锁再申请收件箱锁。这里只登记结果，不写历史。</p>
     * @param job start 已登记且只供此线程执行的命令及其 shell 资源
     */
    private void run(Job job) {
        Completion result;
        try (ShellCommand shell = job.shell()) {
            ShellCommand.Result output = shell.run(job.command());
            result = new Completion(job.id(), job.owner(), job.required(), job.command(),
                    Integer.valueOf(0).equals(output.exitCode()) ? "completed" : "failed",
                    output.exitCode(), output.text(), output.truncated());
        } catch (RuntimeException failure) {
            result = new Completion(job.id(), job.owner(), job.required(), job.command(),
                    "failed", null, "Error: " + failure.getMessage(), false);
        }
        Runnable signal;
        synchronized (this) {
            // 任务失败/会话关闭已撤销拥有权时，晚到结果不能复活任务。
            if (closed || !jobs.containsKey(job.id())) return;
            ready.addLast(result);
            signal = listener;
        }
        signal.run();
    }

    /**
     * 领取结果但暂不释放其记录：模型有效回应后才 acknowledge，交付前失败可以 restore。
     *
     * <p>collect 从 ready 移除但保留 jobs，所以重复 collect 不返回已领取项；领取者必须
     * 最终 acknowledge、restore 或按任务取消，不能把领取当成容量已释放。必等结果只取
     * 当前 owner，脱离结果可来自旧 owner，并保留来源标签。</p>
     * @param owner 当前执行 ID
     * @param includeDetached true 才同时领取会话级通知，准备随正常请求发送
     * @return 已完成结果，不等待运行中命令
     */
    public synchronized List<Completion> collect(String owner, boolean includeDetached) {
        List<Completion> collected = new ArrayList<>();
        var iterator = ready.iterator();
        while (iterator.hasNext()) {
            Completion result = iterator.next();
            if (result.required() ? result.owner().equals(owner) : includeDetached) {
                iterator.remove();
                collected.add(result);
            }
        }
        return List.copyOf(collected);
    }

    /**
     * 判断当前任务是否还有未确认的必等命令，供运行器决定能否真正结束。
     * @param owner 当前执行 ID
     * @return 有运行中、待领取或领取待确认的必等项时为 true；脱离命令不计入
     */
    public synchronized boolean hasRequired(String owner) {
        return jobs.values().stream().anyMatch(job -> job.required() && job.owner().equals(owner));
    }

    /**
     * 移除已交付记录、释放容量，不再次执行 shell，也不检查模型的业务判断是否正确。
     * @param results collect 得到的可信领取记录；通常在合法正式回复后确认，普通失败时若
     *                通知已保留在失败历史中也可清理。调用方不能用模型文本伪造记录
     */
    public synchronized void acknowledge(List<Completion> results) {
        results.forEach(result -> jobs.remove(result.id()));
    }

    /**
     * 定时首回复前失败时归还实际领取过的事件，不重跑命令、不接受模型构造的字符串通知。
     * 拥有权仍在 jobs 内，恢复只移动完成记录，不创建假的进程或改变总容量。
     * @param results 原 collect 记录，按原有顺序放回队首；已无拥有权或已在队列中的项不重复放入。
     *                已关闭时不恢复，不能据此跨进程重建任何命令
     */
    public synchronized void restore(List<Completion> results) {
        if (closed) return;
        // 逆序逐个 addFirst：例如 [A,B] 归还后仍排为 A、B，而不会颠倒成本轮的新顺序。
        for (int i = results.size() - 1; i >= 0; i--) {
            Completion result = results.get(i);
            if (jobs.containsKey(result.id()) && ready.stream().noneMatch(item -> item.id().equals(result.id()))) {
                ready.addFirst(result);
            }
        }
    }

    /**
     * 失败任务只清理自己的必等命令；先摘除拥有权，再锁外终止 IO。
     * 已发生的文件副作用不回滚，脱离任务的命令仍归会话拥有。
     * @param owner 失败任务的执行 ID，不是 cron 定义 ID；只删除匹配且 required 的记录
     */
    public void cancelRequired(String owner) {
        List<Job> cancelled;
        synchronized (this) {
            cancelled = jobs.values().stream().filter(job -> job.required() && job.owner().equals(owner)).toList();
            cancelled.forEach(job -> jobs.remove(job.id()));
            ready.removeIf(result -> result.required() && result.owner().equals(owner));
        }
        cancelled.forEach(job -> job.shell().close());
    }

    /**
     * 管理器持有的命令资源，不能序列化进模型上下文。
     * @param id 会话内 bg_id
     * @param owner 发起任务的 run_ ID
     * @param required 是否必须交回发起任务处理最终结果
     * @param command 已获准的 shell 原文
     * @param shell 此命令独占资源，按 owner 取消时不会误杀其它脱离命令
     */
    private record Job(String id, String owner, boolean required, String command, ShellCommand shell) { }

    /**
     * 一条命令的最终内存记录；状态与业务是否达标是两件事，不存整份会话历史。
     * @param id 原 bg_id，供模型关联启动回执
     * @param owner 发起执行 ID，避免把旧任务结果误认为当前目标
     * @param required true 为发起任务必等，false 为会话参考通知
     * @param command 实际执行的命令
     * @param status completed 表示退出码为零，failed 表示非零或无法正常完成
     * @param exitCode 真实退出码；超时、IO、中断等可能为 null，不能伪造为零
     * @param output ShellCommand 保留的有界输出，不保证是完整命令输出，也不另行落盘
     * @param truncated ShellCommand 是否已经截断输出，通知进一步截断时仍须告知模型
     */
    public record Completion(String id, String owner, boolean required, String command,
            String status, Integer exitCode, String output, boolean truncated) {
        /**
         * 将完成记录渲染为有界参考文本，供运行器合并进下一次模型上下文。
         *
         * <p>仅保留正文前 500 个 Java 字符，且保留来源、命令、状态和退出码。这里只构造字符串，
         * 不写文件、不确认领取、不触发模型。原 bash 的 tool_use 已由启动回执闭合，故通知
         * 不能再冒充同一调用的第二个 tool_result。</p>
         * @return 带 task_notification 标记的文本；截断会明确说明没有另存完整输出文件。
         *         标记转义只是降低文本混淆，不是对输出内容的安全背书
         */
        public String notification() {
            String summary = output.length() > SUMMARY_CHARS ? output.substring(0, SUMMARY_CHARS) : output;
            if (output.length() > SUMMARY_CHARS || truncated) {
                summary += "\n[Output truncated; no full output file is retained.]";
            }
            return "<task_notification>\n<task_id>" + id + "</task_id>\n<source_run>" + escape(owner)
                    + "</source_run>\n<status>" + status + "</status>\n<exit_code>" + exitCode
                    + "</exit_code>\n<command>" + escape(command) + "</command>\n<summary>" + escape(summary)
                    + "</summary>\n</task_notification>";
        }
    }

    /**
     * 转义参考文本中的标记边界字符，先处理 {@code &}，避免二次转义刚生成的实体。
     * @param value 非 null 的来源/命令/输出文本
     * @return 适合嵌入当前标签格式的文本；不等于阻止所有提示注入
     */
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 幂等关闭整个命令管理器：先撤销拥有权与结果队列，再锁外终止同步及后台 shell。
     *
     * <p>包含 required 和 detached 命令，detached 只是脱离当前任务，不是脱离 CLI 进程。
     * 工作线程晚到结果因失去拥有权不再发布；不等待命令自然结束，不删除/回滚命令产物，
     * 也不把未完成记录持久化成可恢复队列。</p>
     */
    @Override
    public void close() {
        List<Job> active;
        synchronized (this) {
            if (closed) return;
            closed = true;
            active = List.copyOf(jobs.values());
            jobs.clear();
            ready.clear();
        }
        foreground.close();
        active.forEach(job -> job.shell().close());
    }
}
