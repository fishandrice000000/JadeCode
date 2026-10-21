package com.jadecode.tui.terminal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 暂退到外部编辑器或 shell job 并恢复：不关闭 Host/Session，也不在 UI 线程等子进程。
 *
 * <p>外部编辑器顺序为 `tui.externalEditor` → `VISUAL` → `EDITOR` → `nano`；按空格拆分
 * 程序/参数（不提供管道/引号 shell 语义），追加受控临时路径。成功后去掉 BOM 与单个末尾
 * 换行再回填；非零/启动失败保留原草稿并报告。暂退前先停读取/恢复终端属性，返回后重查
 * 尺寸/协议；handoff token 防止旧子进程结果覆盖新会话。</p>
 */
public final class TerminalHandoff implements AutoCloseable {
    private final TerminalSession terminal;
    private final PosixJobControl jobs;
    private long token;
    private boolean suspended;
    private final List<Path> temporaryFiles = new ArrayList<>();

    public TerminalHandoff(TerminalSession terminal, PosixJobControl jobs) {
        this.terminal = terminal; this.jobs = jobs;
    }

    /** @return 当前 handoff token；异步结果必须带回同一 token。 */
    public synchronized long token() { return token; }

    /** @return 是否已暂停终端等待 shell/编辑器。 */
    public synchronized boolean suspended() { return suspended; }

    /**
     * 外部编辑器往返。调用方在 worker 线程调用；成功回调只携带文本，由 UI 再校验 token/
     * 草稿版本后应用。失败回调携带真实原因；终端在任何路径都尝试恢复。
     */
    public void edit(String draft, String configuredCommand, Consumer<String> success, Consumer<String> failure) {
        long ticket;
        synchronized (this) { ticket = ++token; }
        Path file = null;
        try {
            file = Files.createTempFile("jadecode-draft-", ".md");
            temporaryFiles.add(file);
            Files.writeString(file, draft == null ? "" : draft, StandardCharsets.UTF_8);
            terminal.pauseForHandoff();
            synchronized (this) { suspended = true; }
            List<String> command = externalEditorCommand(configuredCommand);
            command.add(file.toString());
            Process process = new ProcessBuilder(command).inheritIO().start();
            int exit = process.waitFor();
            if (exit != 0) { failure.accept("External editor exited with status " + exit + "; draft kept"); return; }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            if (text.endsWith("\r\n")) text = text.substring(0, text.length() - 2);
            else if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
            success.accept(text);
        } catch (Exception failed) {
            failure.accept("External editor unavailable: " + failed.getMessage());
        } finally {
            resumeTerminal();
            cleanup(file);
        }
    }

    /** 按 Pi settings-manager 顺序选择编辑器；空/无效命令退回 nano。 */
    static List<String> externalEditorCommand(String configured) {
        List<String> result = new ArrayList<>();
        String value = configured;
        if (value == null || value.isBlank()) value = System.getenv("VISUAL");
        if (value == null || value.isBlank()) value = System.getenv("EDITOR");
        if (value == null || value.isBlank()) value = "nano";
        for (String part : value.strip().split("\\s+")) if (!part.isBlank()) result.add(part);
        if (result.isEmpty()) result.add("nano");
        return result;
    }

    /**
     * Ctrl+Z 暂退：暂停终端、核验前台组、发 TSTP。宿主应在注册 CONT 处理器后调用。
     * @return 错误文本；空表示信号已发（之后由 CONT 恢复）
     */
    public String suspend() {
        synchronized (this) { token++; }
        try { terminal.pauseForHandoff(); }
        catch (IOException failure) { return "Terminal pause failed: " + failure.getMessage(); }
        // 先标记暂退，再发 TSTP；fg 的 CONT 处理器可能在 kill 返回前就看到状态。
        synchronized (this) { suspended = true; }
        String error = jobs.suspend();
        if (!error.isEmpty()) { resumeTerminal(); return error; }
        return "";
    }

    /**
     * shell 的 `fg` 返回后由 CONT 处理器调用：重进 raw、重查尺寸、恢复 token 并请求完整重绘。
     * @return 是否确实从暂退恢复
     */
    public boolean resumeAfterContinue() {
        boolean wasSuspended;
        synchronized (this) { wasSuspended = suspended; suspended = false; token++; }
        if (!wasSuspended) return false;
        resumeTerminal();
        terminal.bumpEpoch();
        return true;
    }

    private void resumeTerminal() {
        synchronized (this) { suspended = false; }
        try { if (terminal.state() == TerminalSession.State.HANDOFF) terminal.resume(); }
        catch (IOException failure) {
            // 无法恢复 raw 时不能假装正常；宿主会通过输出失败进入关闭收口。
            throw new IllegalStateException("Terminal resume failed: " + failure.getMessage(), failure);
        }
    }

    /** 清理本次拥有的临时文件；不删除用户文件。 */
    public void cleanup(Path file) {
        if (file == null) return;
        temporaryFiles.remove(file);
        try { Files.deleteIfExists(file); } catch (IOException ignored) { }
    }

    /** 清理所有仍归属本次的临时文件。 */
    @Override public void close() {
        for (Path file : List.copyOf(temporaryFiles)) cleanup(file);
    }
}
