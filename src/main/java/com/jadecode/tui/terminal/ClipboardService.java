package com.jadecode.tui.terminal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 主动复制/读取文字剪贴板：优先桌面命令，失败回退 OSC52（只能发送请求，不能确认客户端收到）。
 *
 * <p>不自动读取敏感剪贴板；读取只在用户显式 Ctrl+V/图片粘贴时发生。命令用 ProcessBuilder
 * 参数执行，不经过 shell 插值。OSC52 输出成功只表示请求已写出，界面必须用真实反馈区分
 * desktop copy 与 remote request。图片不走本类，由 ImageClipboard 读取并保存临时文件。</p>
 */
public final class ClipboardService {
    /** 复制结果；OSC52_SENT 不代表远端已接收。 */
    public enum CopyResult { DESKTOP, OSC52_SENT, FAILED }

    private final Consumer<String> terminalWriter;
    private final boolean linksSupported;

    /** @param terminalWriter 唯一受信终端写入口（只写 OSC52） */
    public ClipboardService(Consumer<String> terminalWriter, boolean linksSupported) {
        this.terminalWriter = terminalWriter; this.linksSupported = linksSupported;
    }

    /** 复制文字；空文本失败。调用方应在 worker 执行以避免阻塞 UI。 */
    public CopyResult copy(String text) {
        if (text == null || text.isEmpty()) return CopyResult.FAILED;
        if (desktopCopy(text)) return CopyResult.DESKTOP;
        if (!linksSupported) return CopyResult.FAILED;
        try {
            byte[] encoded = Base64.getEncoder().encode(text.getBytes(StandardCharsets.UTF_8));
            // OSC52 长度受限；超长文本仍可由桌面命令复制。这里拒绝而不是截断。
            if (encoded.length > 100_000) return CopyResult.FAILED;
            terminalWriter.accept("\u001b]52;c;" + new String(encoded, StandardCharsets.US_ASCII) + "\u0007");
            return CopyResult.OSC52_SENT;
        } catch (RuntimeException failure) { return CopyResult.FAILED; }
    }

    /** @return 文字剪贴板；无可用后端/空内容返回空。调用方应在 worker 执行。 */
    public Optional<String> readText() {
        for (List<String> command : List.of(List.of("wl-paste", "--no-newline"),
                List.of("xclip", "-selection", "clipboard", "-o"),
                List.of("xsel", "--clipboard", "--output"))) {
            try {
                Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                byte[] bytes = process.getInputStream().readAllBytes();
                if (process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0 && bytes.length > 0)
                    return Optional.of(new String(bytes, StandardCharsets.UTF_8));
            } catch (Exception unavailable) { /* 尝试下一个后端。 */ }
        }
        return Optional.empty();
    }

    /** @return 是否有桌面读取后端（用于图片粘贴错误提示）。 */
    public boolean desktopReadAvailable() {
        return executable("wl-paste") || executable("xclip") || executable("xsel");
    }

    private static boolean desktopCopy(String text) {
        for (List<String> command : List.of(List.of("wl-copy"), List.of("xclip", "-selection", "clipboard"),
                List.of("xsel", "--clipboard", "--input"))) {
            try {
                Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                try (var input = process.getOutputStream()) {
                    input.write(text.getBytes(StandardCharsets.UTF_8)); input.flush();
                }
                if (process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0) return true;
            } catch (IOException | InterruptedException unavailable) {
                if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }
        return false;
    }

    private static boolean executable(String name) {
        try {
            Process process = new ProcessBuilder("sh", "-c", "command -v " + name)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception unavailable) { return false; }
    }
}
