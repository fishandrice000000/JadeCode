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
    /** 全新会话直接逐个打开四页，覆盖短文档装配；不先向脚本模型发消息。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void freshSessionShowsAllFourPanelsBeforeAnyModelMessage(boolean regular, @TempDir Path dir) throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/script")));
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String driver = regular ? TuiRegularPtyDriver.class.getName() : TuiPtyDriver.class.getName();
        String command = "stty cols 80 rows 24; exec " + shellQuote(java) + " -cp " + shellQuote(classpath)
                + " " + driver + " " + shellQuote(dir.resolve("config/config.json").toString());
        var builder = new ProcessBuilder("/usr/bin/script", "-q", "-f", "-e", "-c", command, "/dev/null");
        builder.environment().put("TERM", "xterm-256color");
        builder.environment().remove("NO_COLOR");
        Process process = builder.start();
        var captured = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var input = process.getInputStream()) { input.transferTo(captured); }
            catch (Exception ignored) { }
        });
        try {
            Thread.sleep(2500);
            String[] commands = {"/hotkeys", "/model", "/session", "/mcp"};
            String[] titles = {"Hotkeys", "Model", "Session", "MCP servers"};
            for (int i = 0; i < commands.length; i++) {
                captured.reset();
                process.getOutputStream().write((commands[i] + "\r").getBytes(StandardCharsets.UTF_8));
                process.getOutputStream().flush(); Thread.sleep(650);
                String output = captured.toString(StandardCharsets.UTF_8)
                        .replaceAll("\u001b\\[[0-?]*[ -/]*[@-~]", "");
                // S93：Pi 上下横线 + 右上标题；PTY 只验证真实终端里出现标题与横线，
                // 不假设旧的闭合四角。
                assertTrue(output.contains(" " + titles[i] + " "), commands[i] + " title missing: " + tail(output));
                assertTrue(output.contains("─"), commands[i] + " section rule missing: " + tail(output));
                // legacy SSH 的孤立 Esc 有 100ms 期限和 200ms 续尾宽限，下一命令不能在其中插入。
                process.getOutputStream().write(27); process.getOutputStream().flush(); Thread.sleep(650);
            }
            process.getOutputStream().write("/quit\r".getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
        } finally { if (process.isAlive()) process.destroyForcibly(); reader.join(2000); }
    }
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

    @Test void regularAssemblyWritesFullDocumentTailAndDock(@TempDir Path dir) throws Exception {
        Path script = Path.of("/usr/bin/script");
        assumeTrue(Files.isExecutable(script), "util-linux script is required for the PTY smoke test");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path preferences = dir.resolve("config/config.json");
        String command = java + " -cp " + shellQuote(classpath) + " " + TuiRegularPtyDriver.class.getName()
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
        Thread.sleep(2200);
        process.getOutputStream().write("long output\r".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
        Thread.sleep(3000);
        process.getOutputStream().write("/quit\r".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
        boolean exited = process.waitFor(15, TimeUnit.SECONDS);
        if (!exited) process.destroyForcibly();
        reader.join(2000);
        String output = captured.toString(StandardCharsets.UTF_8);
        assertTrue(exited, "regular TUI did not exit; output tail=" + tail(output));
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("line-79"), "tail beyond three screens must reach the renderer: " + tail(output));
        assertTrue(output.contains("New session") || output.contains("Session:"), "dock/session status missing");
        assertTrue(output.contains("Resume:"), "farewell after regular exit missing");
    }

    private static String tail(String value) {
        return value.length() <= 4000 ? value : value.substring(value.length() - 4000);
    }

    @Test void realPtyCommandPanelOpensInFullscreenWithoutTranscript(@TempDir Path dir) throws Exception {
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
        process.getOutputStream().write("/hotkeys\r".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
        Thread.sleep(1500);
        process.getOutputStream().write("\u001b".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
        Thread.sleep(500);
        process.getOutputStream().write("/quit\r".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
        boolean exited = process.waitFor(15, TimeUnit.SECONDS);
        if (!exited) process.destroyForcibly();
        reader.join(2000);
        String output = captured.toString(StandardCharsets.UTF_8);
        assertTrue(exited, "TUI did not exit after closing the panel; output=" + output);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("Hotkeys"), "command panel title missing: " + output);
        assertTrue(output.contains("Common") || output.contains("Submit input"), "command panel groups missing: " + output);
    }

    @Test void realPtyCommandPanelOpensInRegularAndRestoresDocument(@TempDir Path dir) throws Exception {
        Path script = Path.of("/usr/bin/script");
        assumeTrue(Files.isExecutable(script), "util-linux script is required for the PTY smoke test");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path preferences = dir.resolve("config/config.json");
        String command = java + " -cp " + shellQuote(classpath) + " " + TuiRegularPtyDriver.class.getName()
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
        process.getOutputStream().write("regular output\r".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
        Thread.sleep(2500);
        process.getOutputStream().write("/hotkeys\r".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
        Thread.sleep(1500);
        process.getOutputStream().write("\u001b".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
        Thread.sleep(500);
        process.getOutputStream().write("/quit\r".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
        boolean exited = process.waitFor(15, TimeUnit.SECONDS);
        if (!exited) process.destroyForcibly();
        reader.join(2000);
        String output = captured.toString(StandardCharsets.UTF_8);
        assertTrue(exited, "regular TUI did not exit; output tail=" + tail(output));
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("Hotkeys"), "regular panel title missing: " + tail(output));
        assertTrue(output.contains("line-79"), "regular document history must remain after closing the panel: " + tail(output));
    }

    private static String shellQuote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
