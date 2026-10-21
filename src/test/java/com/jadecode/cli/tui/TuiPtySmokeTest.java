package com.jadecode.cli.tui;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 真实 PTY 冒烟：启动、输入、脚本回复、/quit、终端恢复和退出码。
 *
 * <p>依赖 Linux util-linux 的 script；缺失时跳过并报告，不把跳过当通过。不使用真实模型/
 * 真实配置，只验证终端生命周期与 UI 环路。</p>
 */
class TuiPtySmokeTest {
    @Test void realPtyStartInputReplyAndQuit(@TempDir Path dir) throws Exception {
        Path script = Path.of("/usr/bin/script");
        assumeTrue(Files.isExecutable(script), "util-linux script is required for the PTY smoke test");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path preferences = dir.resolve("config/config.json");
        String command = java + " -cp " + shellQuote(classpath) + " " + TuiPtyDriver.class.getName()
                + " " + shellQuote(preferences.toString());
        ProcessBuilder builder = new ProcessBuilder(script.toString(), "-q", "-f", "-e", "-c", command, "/dev/null");
        builder.environment().put("TERM", "xterm-256color");
        builder.environment().remove("NO_COLOR");
        Process process = builder.start();
        var captured = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (InputStream input = process.getInputStream()) { input.transferTo(captured); }
            catch (Exception ignored) { }
        });
        Thread.sleep(2500);
        process.getOutputStream().write("hello\r".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
        Thread.sleep(2500);
        process.getOutputStream().write("/quit\r".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
        boolean exited = process.waitFor(15, TimeUnit.SECONDS);
        if (!exited) process.destroyForcibly();
        reader.join(2000);
        String output = captured.toString(StandardCharsets.UTF_8);
        assertTrue(exited, "TUI did not exit after /quit; output=" + output);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("JadeCode"), output);
        assertTrue(output.contains("PTY reply"), output);
        assertTrue(output.contains("Resume:"), output);
    }

    private static String shellQuote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
