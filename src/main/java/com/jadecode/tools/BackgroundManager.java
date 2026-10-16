package com.jadecode.tools;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jadecode.agent.ExecutionContext;

/**
 * 运行 shell 命令，并在内存中保存后台命令的结果，等待 Agent 读取。
 *
 * <p>例如 bash 启动构建后，立刻返回 bg_id，模型就可以先读别的文件。
 * 构建线程只负责跑命令、保存结果、叫醒 SessionRuntime，不直接请求模型或改 history。
 * required=true 表示当前任务做完前必须处理这个结果，不要求命令必须成功；
 * 失败结果也交给模型决定下一步。</p>
 *
 * <p>jobs 保存所有还没处理完的命令记录；ready 只放已经跑完、尚未取走的结果。
 * collect 取走结果后暂不删 jobs，模型正常回答后才 acknowledge 删除。
 * 所以命令跑完、结果取出、结果交给模型，是三件不同的事，不能只看进程是否还活着。</p>
 *
 * <p>这些记录总共最多 32 项，每条后台命令有自己的 ShellCommand，任务失败时可以只停自己
 * 必须等待的命令，不影响别的任务。通知只发前 500 字符，没有完整输出文件；
 * 退出后内存结果不会恢复。</p>
 */
public final class BackgroundManager implements AutoCloseable {
    /** 还在运行或结果尚未处理完的命令总数上限，避免一直占内存。 */
    private static final int MAX_PENDING = 32;
    /** 每条通知最多保留多少个输出字符，按 Java 字符计数，不是 token 或字节数。 */
    private static final int SUMMARY_CHARS = 500;
    /** 创建对象时确定的工作目录，模型不能通过后台参数换成另一个目录。 */
    private final Path cwd;
    /** 每条命令最多运行多少秒，超过后由 ShellCommand 停止。 */
    private final long timeoutSeconds;
    /** 执行同步命令的 ShellCommand；用户退出时也要停止。 */
    private final ShellCommand foreground;
    /** 所有尚未处理完的命令，读写要拿 synchronized 锁；跑完但模型没收到结果的也留在这里。 */
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    /** 跑完且还没取走的结果，按完成顺序放；不放到新任务的排队队列。 */
    private final ArrayDeque<Completion> ready = new ArrayDeque<>();
    /** 命令跑完时调用的函数，默认什么也不做；结果仍会保存在 ready 中。 */
    private Runnable listener = () -> { };
    /** 生成本次会话的 bg_id；重启后重新计数，不保证不同进程的 ID 不同。 */
    private long counter;
    /** 用户退出时设为 true，工作线程之后不能再添加结果；访问要拿锁。 */
    private boolean closed;

    /**
     * 使用默认的每条命令 120 秒超时，不启动线程、进程或读写文件。
     * @param cwd 同步和后台命令共同使用的工作目录
     */
    public BackgroundManager(Path cwd) { this(cwd, 120); }

    /**
     * 记住工作目录和命令超时时间，此时不执行命令。
     * 真正启动后台命令时，每条命令各用一个 ShellCommand，方便单独停止。
     * @param cwd 命令的工作目录
     * @param timeoutSeconds 每条命令最多运行多少秒，必须大于零；不是整个会话的等待时长
     * @throws IllegalArgumentException 超时不是正数
     */
    public BackgroundManager(Path cwd, long timeoutSeconds) {
        this.cwd = cwd;
        this.timeoutSeconds = timeoutSeconds;
        foreground = new ShellCommand(cwd, timeoutSeconds);
    }

    /**
     * 设置“命令跑完后做什么”的函数，SessionRuntime 传入的函数会叫醒处理线程。
     *
     * <p>更换函数不会取走或丢掉已有结果，也不会为已有结果重新调用一次。
     * 工作线程可能在任何时刻调用它，所以要尽快返回，不应抛异常；这里只叫醒线程，
     * 不能在函数里直接改 history 或调用模型。</p>
     * @param listener 命令跑完时调用的函数，不可为 null
     * @throws NullPointerException listener 为 null
     */
    public synchronized void onCompletion(Runnable listener) {
        this.listener = java.util.Objects.requireNonNull(listener);
    }

    /**
     * 记录一条后台命令并启动线程，立即返回 bg_id，不等命令跑完。
     *
     * <p>调用者必须已经检查过权限；这里不再次询问用户。先把 Job 放进 jobs，再启动虚拟线程，
     * 这样命令很快跑完或用户同时退出时，都能找到这条命令。线程没能启动就移除 Job、关闭
     * 它的 shell，再抛出异常。返回 ID 时，线程可能还没来得及真正启动 shell 进程。</p>
     * @param command 已经过权限检查的非空白命令
     * @param context 程序提供的当前任务信息，用任务 ID 记录这条命令属于谁
     * @param required 当前任务结束前是否必须让模型看见这条命令的结果
     * @return bg_id，只表示程序接下了这条命令，不表示命令或业务成功
     * @throws IllegalStateException 已退出，或已有 32 项记录尚未删除
     * @throws IllegalArgumentException command 为 null 或空白
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
        // 先存 Job 再开线程，免得命令很快跑完或用户同时退出时，管理器里还找不到它。
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
     * 在当前线程运行一条同步命令，等它结束、失败或超时再返回，不产生后台通知。
     * @param command BashTool 已检查过的命令原文
     * @return 输出或 Error 文字，不修改消息列表
     */
    String runForeground(String command) { return foreground.run(command).text(); }

    /**
     * 后台线程实际执行命令，收集输出后把结果放进 ready。
     *
     * <p>退出码为 0 记 completed，其它情况记 failed；拿不到退出码就保留 null，
     * 不能编造成功。意外运行异常也记成失败。若任务出错或用户退出时已经删掉这条 Job，
     * 现在才返回的结果就不要再放回来。</p>
     *
     * <p>结果放好后先松开管理器的锁，再调用叫醒函数。这样不会拿着这里的锁又去等队列的锁，
     * 避免两个线程互相卡住。这里不请求模型或修改历史。</p>
     * @param job 已登记的命令，带它自己使用的 shell，执行后会关闭这个 shell
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
            // 任务失败或用户退出时可能已删掉 Job；现在才返回的结果不要再加回去。
            if (closed || !jobs.containsKey(job.id())) return;
            ready.addLast(result);
            signal = listener;
        }
        signal.run();
    }

    /**
     * 取出现在已完成、适合交给当前任务的结果，暂时没有结果就直接返回空列表。
     *
     * <p>取走时从 ready 移除，所以再调用不会重复取到；但 jobs 中先保留记录。
     * 模型正常回复后用 acknowledge 删除，第一次回复前失败则可 restore 放回。
     * 不这样做，就无法区分“刚取出来”和“已经交给模型”。调用者不能一直取着不处理。</p>
     * @param owner 当前任务的 run_ ID，必须等待的结果只取同一任务的
     * @param includeDetached true 时也取以前“不必等它”的命令结果，作为参考；
     *                        false 时只取当前任务必须等待的结果
     * @return 这次取出的结果列表，不等仍在运行的命令
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
     * 看当前任务是否还有必须让模型处理的后台结果，不是只看进程是否仍在跑。
     * @param owner 当前任务的 run_ ID
     * @return 运行中、跑完没取走、取走没确认的命令都算；明确说“不必等它”的命令不算
     */
    public synchronized boolean hasRequired(String owner) {
        return jobs.values().stream().anyMatch(job -> job.required() && job.owner().equals(owner));
    }

    /**
     * 删除这些命令在 jobs 中的记录，腾出可启动新命令的名额，不再运行任何命令。
     * @param results collect 取出的原记录。通常是模型已正常回复后删除；任务失败时，
     *                若通知仍留在历史中也可以删除。不能根据模型输出编造记录来调用这里。
     *                删除不表示模型已正确完成业务
     */
    public synchronized void acknowledge(List<Completion> results) {
        results.forEach(result -> jobs.remove(result.id()));
    }

    /**
     * 把取出但没能交给模型的结果放回 ready，不重新执行命令。
     *
     * <p>主要用于定时任务在第一次模型回复前失败的情况。原 Job 仍在 jobs，
     * 所以只是放回结果，不新占一个名额。按原顺序放到队首，已经在队列中的不重复放，
     * 已经删掉的 Job 不再恢复；整个会话退出后也不恢复。</p>
     * @param results 原先 collect 取出的结果，不是模型生成的通知文字
     */
    public synchronized void restore(List<Completion> results) {
        if (closed) return;
        // 从后往前 addFirst：原来取出 [A,B]，放回后仍是 A 在 B 前面。
        for (int i = results.size() - 1; i >= 0; i--) {
            Completion result = results.get(i);
            if (jobs.containsKey(result.id()) && ready.stream().noneMatch(item -> item.id().equals(result.id()))) {
                ready.addFirst(result);
            }
        }
    }

    /**
     * 任务无法继续时，只停掉它必须等待的命令，保留明确“不必等它”的命令。
     *
     * <p>先在锁内删 Job 和待取结果，再到锁外关闭每条命令的 shell。
     * 以后工作线程即使才返回，也因 Job 已删除而不会再添加结果。已经写入的文件不会还原。</p>
     * @param owner 失败任务的 run_ ID，不是定时定义 ID 或某个 bg_id
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
     * 记住一条命令属于哪项任务，以及如何单独停止它；不会把这个对象发给模型。
     * @param id 命令的 bg_id
     * @param owner 启动它的任务 run_ ID
     * @param required 任务做完前是否必须处理结果
     * @param command 实际命令原文
     * @param shell 仅这条命令使用的 ShellCommand，不与其它后台命令共用
     */
    private record Job(String id, String owner, boolean required, String command, ShellCommand shell) { }

    /**
     * 后台命令跑完后的记录，保存在内存中，不是会话日志。
     * @param id 启动时返回的 bg_id
     * @param owner 启动它的任务 run_ ID，供查找结果和在通知中注明来源
     * @param required true 表示原任务必须处理，false 表示以后顺便给模型看
     * @param command 执行过的命令原文
     * @param status 退出码为零是 completed；非零或没正常完成是 failed，不代表业务结论正确
     * @param exitCode 实际退出码，超时、IO 或中断等情况可能为 null，不能假写成 0
     * @param output ShellCommand 留下的输出，有长度限制，不一定完整，也没有另存文件
     * @param truncated ShellCommand 是否已截断输出，发通知时也要告知模型
     */
    public record Completion(String id, String owner, boolean required, String command,
            String status, Integer exitCode, String output, boolean truncated) {
        /**
         * 把结果写成准备发给模型的说明文字，不执行命令、不写文件，也不删除记录。
         *
         * <p>输出只留前 500 个 Java 字符，并注明原任务、命令、状态和退出码。
         * 原 bash 调用已经用“已启动”回答过，不能拿同一 tool_use_id 再回一个 tool_result，
         * 所以这次把文字作为后台完成通知加入消息。</p>
         * @return 带 task_notification 标签的文字；截断时说明没有完整输出文件。
         *         标签中的特殊字符会转义，但不保证命令输出里一定没有误导模型的内容
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
     * 把文字中的特殊字符写成实体，避免正文里的尖括号被误认成通知标签。
     * 先替换 {@code &}，免得把后面新生成的实体再替换一次。
     * @param value 要放进标签的非 null 文字
     * @return 转义后的文字，只处理标签格式，不判断文字是否可信
     */
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 用户退出时停止全部同步和后台命令，清空内存记录，重复调用也可以。
     *
     * <p>先标记已退出、清掉 Job 和 ready，再到锁外关闭 shell。
     * 设置“不必等它”的命令也要停，因为它只是当前任务不用等，不是退出 CLI 后还要继续。
     * 不等命令自然跑完，不删除或还原命令生成的文件，也不保存记录供下次恢复。</p>
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
