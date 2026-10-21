package com.jadecode.tui.terminal;

import java.util.List;
import java.util.Optional;

/**
 * Ubuntu 作业控制的最小桥：只查询本 JVM 的 PID/PGID/前台组，并只对本进程发 TSTP。
 *
 * <p>使用受控 `ps`/`kill` 子进程，不引入 JNI/preview；命令缺失或结果不合法时明确失败。
 * 不猜 pid==pgid，也不向无关组发信号；只有本 JVM 进程组确实是终端前台组时才暂退。
 * 恢复由 JLine CONT 处理器/宿主完成，本类不关闭 Session。
 */
public final class PosixJobControl {
    /** 查询结果；unknown 表示 ps 不可用/输出不合法。 */
    public record Groups(long pid, long pgid, long foregroundPgid, String tty) {
        public boolean foreground() { return foregroundPgid == pgid; }
    }

    /** @return 当前进程的 pid/pgid/前台组；查询失败为空。 */
    public Optional<Groups> groups() {
        long pid = ProcessHandle.current().pid();
        try {
            Process process = new ProcessBuilder(List.of("ps", "-o", "pid=,pgid=,tpgid=,tty=", "-p", Long.toString(pid)))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String output = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).strip();
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0
                    || output.isBlank()) return Optional.empty();
            String[] parts = output.split("\\s+");
            if (parts.length < 3) return Optional.empty();
            long processId = Long.parseLong(parts[0]);
            long groupId = Long.parseLong(parts[1]);
            long foreground = Long.parseLong(parts[2]);
            String tty = parts.length > 3 ? parts[3] : "";
            if (processId != pid) return Optional.empty();
            return Optional.of(new Groups(processId, groupId, foreground, tty));
        } catch (Exception unavailable) { return Optional.empty(); }
    }

    /**
     * 向本 JVM 发送 TSTP。调用前终端已恢复原属性/停止读取；失败明确返回原因。
     * 只发给 pid，不发给进程组，避免把当前 shell/工具子进程一起停止。
     */
    public String suspend() {
        Optional<Groups> groups = groups();
        if (groups.isEmpty()) return "Cannot verify the foreground process group (ps unavailable)";
        if (!groups.get().foreground()) return "JadeCode is not the foreground job; use fg first";
        try {
            Process process = new ProcessBuilder("kill", "-TSTP", Long.toString(groups.get().pid()))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0)
                return "Failed to suspend the process";
            return "";
        } catch (Exception failure) { return "Failed to suspend: " + failure.getMessage(); }
    }

    /** @return 是否可用（仅诊断，不用于假装已暂退）。 */
    public boolean available() { return groups().isPresent(); }
}
