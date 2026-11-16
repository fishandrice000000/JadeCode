package com.jadecode.support;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import com.jadecode.agent.AgentExecutor;
import com.jadecode.config.*;
import com.jadecode.context.ContextCompactor;
import com.jadecode.llm.LlmException;
import com.jadecode.messages.Message;
import com.jadecode.session.SessionRuntime;
import com.jadecode.session.history.*;
import com.jadecode.util.Json;

/**
 * 旧同步测试的适配边界：使用正式 restore/start/submit，而不在产品中保留借用外部 history 的入口。
 * 每个测试结束关闭自己创建的会话和临时日志；调用后从日志还原已采用历史，保留原有协议断言。
 */
public final class SessionRuns {
    private record Fixture(Path root, WorkspacePaths paths, SessionStore.Opened opened) {
    }

    private static final Map<SessionRuntime, Fixture> fixtures = new IdentityHashMap<>();
    private static final Map<AgentExecutor, SessionRuntime> runtimes = new IdentityHashMap<>();

    /** 同一执行器的多次任务沿用同一正式会话，便于验证重试计数不会跨任务残留。 */
    public static void run(AgentExecutor engine, List<Message> history) throws LlmException {
        var runtime = runtimes.computeIfAbsent(engine, key -> new SessionRuntime(key, null, null, ignored -> {
        }));
        run(runtime, history);
    }

    /** 恢复已有前缀后只提交末条人类请求；读取日志也在任务 Future 完成之后进行。 */
    public static void run(SessionRuntime runtime, List<Message> history) throws LlmException {
        run(runtime, history, null);
    }

    /** 记忆集成测试使用相同项目分区保存日志，维护者才可核对真实 journal 证据。 */
    public static void run(SessionRuntime runtime, List<Message> history, WorkspacePaths evidencePaths)
            throws LlmException {
        String prompt = ContextCompactor.extractActiveRequest(history);
        Fixture fixture;
        try {
            fixture = fixtures.get(runtime);
            if (fixture == null) {
                Path root = Files.createTempDirectory("jade-session-test-");
                var paths = evidencePaths == null ? new WorkspacePaths(root, new JadePaths(root.resolve("data")))
                        : evidencePaths;
                var store = new SessionStore(paths, ignored -> {
                });
                var opened = store.create("fixture", "");
                var initial = opened.restored();
                List<Message> prefix = List.copyOf(history.subList(0, history.size() - 1));
                opened.journal().append(JournalEntry.Kind.REPLACE_HISTORY, prefix, Json.mapper().createArrayNode(),
                        "fixture");
                var restored = new RestoredSession(initial.metadata(), prefix, initial.todos(), List.of(), List.of(),
                        1, 0, false, false);
                runtime.restore(opened.journal(), restored);
                fixture = new Fixture(root, paths, opened);
                fixtures.put(runtime, fixture);
                runtime.start();
            }
            try {
                runtime.submit(prompt, null).get(20, TimeUnit.SECONDS);
            } catch (ExecutionException failed) {
                if (failed.getCause() instanceof LlmException error)
                    throw error;
                if (failed.getCause() instanceof RuntimeException error)
                    throw error;
                throw new IllegalStateException(failed.getCause());
            } finally {
                // 成功和失败都检查真实日志中采用的消息；不读取或改写运行时私有字段。
                var metadata = fixture.opened().journal().metadata();
                byte[] bytes = Files
                        .readAllBytes(fixture.paths().sessions().resolve(metadata.id()).resolve("events.jsonl"));
                var saved = SessionLoader.load(metadata, bytes).history();
                history.clear();
                history.addAll(saved);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        } catch (java.io.IOException | TimeoutException error) {
            throw new IllegalStateException(error);
        }
    }

    /** 由各测试的 AfterEach 调用；先停止会话再删除本辅助器自己创建的临时区域。 */
    public static void closeAll() throws Exception {
        for (var entry : fixtures.entrySet()) {
            entry.getKey().close();
            entry.getValue().opened().close();
            try (var files = Files.walk(entry.getValue().root())) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(file);
            }
        }
        fixtures.clear();
        runtimes.clear();
    }

    private SessionRuns() {
    }
}
