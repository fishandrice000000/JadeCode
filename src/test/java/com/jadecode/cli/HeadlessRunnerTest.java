package com.jadecode.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jadecode.bootstrap.JadeCodeFactory;
import com.jadecode.config.AppConfig;
import com.jadecode.config.WorkspacePaths;
import com.jadecode.llm.ModelResponse;
import com.jadecode.llm.ScriptedLlmClient;
import com.jadecode.messages.Message;
import com.jadecode.messages.TextBlock;
import com.jadecode.safety.PermissionProfile;
import com.jadecode.session.history.SessionMetadata;
import com.jadecode.session.history.SessionStore;

/**
 * 无界面语义回归：默认 auto-review、stdout 只有最终回答、会话持久化可恢复、无人工通道。
 *
 * <p>使用 Factory 注入 ScriptedLlmClient，不读取用户认证、不调用真实模型。</p>
 */
class HeadlessRunnerTest {
    @TempDir Path workspace;

    private PrintStream stream(ByteArrayOutputStream buffer) {
        return new PrintStream(buffer, true, StandardCharsets.UTF_8);
    }

    @Test
    void printsOnlyTheFinalAnswerAndSavesAResumableSession() throws Exception {
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        var client = new ScriptedLlmClient(new ModelResponse(
                Message.assistant(List.of(new TextBlock("Final answer from the fake model"))),
                new ModelResponse.Usage(5, 7), "end_turn"));
        var config = new AppConfig("dummy", "http://127.0.0.1:9", "smoke-model", 128);
        HeadlessRunner runner = new HeadlessRunner(stream(outBuffer), stream(errBuffer));
        int code = runner.execute(LaunchOptions.parse(new String[] { "--headless", "say hi" }), ignored ->
                new SessionHost(new WorkspacePaths(workspace),
                        generation -> JadeCodeFactory.create(config, workspace, client),
                        warning -> { }));
        String stdout = outBuffer.toString(StandardCharsets.UTF_8);
        String stderr = errBuffer.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertEquals("Final answer from the fake model\n", stdout, "stdout must contain exactly the final reply");
        assertFalse(stderr.contains("[Tool]"), "headless must not print tool progress: " + stderr);
        assertFalse(stderr.contains("Thinking"), "headless must not print thinking: " + stderr);
        SessionStore store = new SessionStore(new WorkspacePaths(workspace), warning -> { });
        List<SessionMetadata> sessions = store.list();
        assertEquals(1, sessions.size(), "the headless run must create one resumable session");
        try (Stream<Path> files = Files.walk(workspace.resolve(".jadecode"))) {
            assertTrue(files.anyMatch(path -> path.toString().endsWith("events.jsonl")));
        }
    }

    @Test
    void headlessDefaultsToAutoReviewAndHonoursAnExplicitOverride() {
        assertEquals(PermissionProfile.AUTO_REVIEW, HeadlessRunner.headlessProfile(null));
        for (PermissionProfile profile : PermissionProfile.values()) {
            assertEquals(profile, HeadlessRunner.headlessProfile(profile));
        }
    }

    @Test
    void blankPromptIsAUsageError() throws Exception {
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        HeadlessRunner runner = new HeadlessRunner(stream(outBuffer), stream(errBuffer));
        assertThrows(LaunchOptions.UsageException.class,
                () -> LaunchOptions.parse(new String[] { "--headless", "   " }));
        // 直接构造空任务也必须在启动会话前拒绝（不读配置、不建日志）。
        assertEquals(2, runner.run(new LaunchOptions(LaunchOptions.Mode.HEADLESS, "", null, null)));
        assertTrue(errBuffer.toString(StandardCharsets.UTF_8).contains("non-empty task"));
    }
}
