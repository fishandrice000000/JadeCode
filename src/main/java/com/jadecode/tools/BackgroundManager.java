package com.jadecode.tools;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * s11 后台命令管理器：慢 shell 不阻塞 Agent，结果在后续请求边界变成通知。
 *
 * <p>每个父 CLI 会话一份，REPL 各次 run 共用；子代理不共享此队列。bg_id 不是 s10 持久任务 ID，
 * 不持久化、不跨重启恢复、不调度任务图。工作者只执行命令和登记结果，不修改消息历史。</p>
 * <p>沿用课程的有界通知摘要，不保存完整输出文件、不唤醒空闲 Agent、不阻止 Stop。
 * 退出会话会终止剩余命令，不承诺提交后一定执行完。权限须由启动前的 PreToolUse 检查。</p>
 */
public final class BackgroundManager implements AutoCloseable {
    private static final int MAX_PENDING = 32;
    private static final int SUMMARY_CHARS = 500;
    private final ShellCommand shell;
    /** 同一锁保护任务和完成队列，确保发布与收集各一次。 */
    private final Map<String, String> tasks = new HashMap<>();
    private final ArrayDeque<Completion> ready = new ArrayDeque<>();
    private long counter;
    private boolean closed;

    /** 生产默认每条命令 120 秒超时，构造没有线程/进程/文件副作用。 */
    public BackgroundManager(Path cwd) {
        this(cwd, 120);
    }

    /**
     * 绑定工作区与单命令超时，也供离线验证缩短等待。
     * @param cwd 所有命令的初始目录
     * @param timeoutSeconds 正数，超时返回 failed 而非成功
     */
    public BackgroundManager(Path cwd, long timeoutSeconds) {
        shell = new ShellCommand(cwd, timeoutSeconds);
    }

    /**
     * 登记命令、启动虚拟线程并立即返回 ID，不等待命令完成。
     *
     * <p>调用前须完成权限检查，不在后台重问审批。先登记再启动，极快命令也不会找不到记录。
     * 未收集任务（运行中加已完成）上限 32，避免模型无限启动耗尽进程/结果内存。</p>
     * @param command 非空、已获准的不可变命令文本
     * @return 本会话唯一 bg_0001 等 ID，不表示业务成功
     * @throws IllegalArgumentException 空命令
     * @throws IllegalStateException 已关闭或任务达到上限
     */
    public synchronized String start(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("Bash command cannot be empty");
        }
        if (closed || tasks.size() >= MAX_PENDING) {
            throw new IllegalStateException(closed ? "Background session is closed" : "Too many pending background tasks");
        }
        String id = "bg_%04d".formatted(++counter);
        tasks.put(id, command);
        try {
            Thread.ofVirtual().name("jade-" + id).start(() -> run(id, command));
        } catch (RuntimeException failure) {
            tasks.remove(id);
            throw failure;
        }
        return id;
    }

    /** 父 bash 的同步路径也交给同一进程所有者，CLI 退出时统一清理。 */
    String runForeground(String command) {
        return shell.run(command).text();
    }

    /** 耗时 IO 放在锁外；工作者不碰历史，只发布不可变完成事件。 */
    private void run(String id, String command) {
        Completion completion;
        try {
            ShellCommand.Result result = shell.run(command);
            String status = Integer.valueOf(0).equals(result.exitCode()) ? "completed" : "failed";
            completion = new Completion(id, command, status, result.text(), result.truncated());
        } catch (RuntimeException failure) {
            completion = new Completion(id, command, "failed", "Error: " + failure.getMessage(), false);
        }
        synchronized (this) {
            // 已关闭的会话不会再收结果，不能在清空后重新留下待消费记录。
            if (!closed) {
                ready.addLast(completion);
            }
        }
    }

    /**
     * 非阻塞地取走此刻完成的所有通知，按完成入队顺序返回，运行中的任务不等待。
     * @return 独立文本事件；同一事件最多返回一次，调用方决定如何注入会话
     */
    public synchronized List<String> collect() {
        List<String> notifications = new ArrayList<>();
        while (!ready.isEmpty()) {
            Completion completion = ready.removeFirst();
            tasks.remove(completion.id());
            notifications.add(completion.notification());
        }
        return List.copyOf(notifications);
    }

    /** 线程间只传不可变完成值，不暴露内部可变表。 */
    private record Completion(String id, String command, String status, String output, boolean truncated) {
        /** 标签是可读数据格式，转义避免正文伪造结构，不宣称可阻断所有提示注入。 */
        String notification() {
            String summary = output.length() > SUMMARY_CHARS ? output.substring(0, SUMMARY_CHARS) : output;
            if (output.length() > SUMMARY_CHARS || truncated) {
                summary += "\n[Output truncated; no full output file is retained.]";
            }
            return "<task_notification>\n<task_id>" + id + "</task_id>\n<status>" + status
                    + "</status>\n<command>" + escape(command) + "</command>\n<summary>" + escape(summary)
                    + "</summary>\n</task_notification>";
        }
    }

    /** 先转义 &，避免后加的 &lt; 被二次转义。 */
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 幂等取消会话：禁止启动/投递，清空通知，终止原命令组；不等待自然完成或回滚文件写入。
     * 不删除命令产生的任何用户文件。
     */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            tasks.clear();
            ready.clear();
        }
        shell.close();
    }
}
