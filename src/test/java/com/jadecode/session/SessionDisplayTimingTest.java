package com.jadecode.session;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jadecode.agent.AgentExecutor;
import com.jadecode.execution.ExecutionNotice;
import com.jadecode.hooks.Hooks;
import com.jadecode.llm.ModelResponse;
import com.jadecode.llm.ScriptedLlmClient;
import com.jadecode.memory.MemoryManager;
import com.jadecode.config.JadeSettings;
import com.jadecode.messages.Message;

/** 主会话与真实 Memory 调用边界的离线集成验证；辅助请求使用脚本，不访问模型服务。 */
class SessionDisplayTimingTest {
    /** 候选回答后的 Stop 边界接收补充；记忆只在整轮真正结束后登记一次。 */
    @Test void inputPromotedAtTerminalBoundaryContinuesOneTurnAndQueuesOneMemoryJob(@TempDir Path dir) throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var extracted = new CountDownLatch(1); var calls = new AtomicInteger();
        var memory = new MemoryManager(request -> {
            calls.incrementAndGet(); extracted.countDown();
            return response("{\"rollout_summary\":\"\",\"rollout_slug\":\"\"}");
        }, com.jadecode.support.TestPaths.workspace(dir), new JadeSettings.Memory(1000), ignored -> { }, ignored -> { });
        var hooks = new Hooks(); var stops = new AtomicInteger();
        hooks.register(Hooks.STOP, ignored -> {
            if (stops.getAndIncrement() == 0) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Stop release missing"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }
            return null;
        });
        var notices = new CopyOnWriteArrayList<ExecutionNotice>();
        var engine = new AgentExecutor(new ScriptedLlmClient(response("first"), response("last")), Map.of(), "test", 1000, hooks);
        engine.observe(notices::add);
        try (var runtime = new SessionRuntime(engine, memory, null, null, ignored -> { }, ignored -> { })) {
            runtime.start(false); var first = runtime.submit("question", null);
            assertTrue(entered.await(5, TimeUnit.SECONDS)); assertEquals(0, calls.get());
            var late = runtime.submitUser("more detail", null); assertTrue(runtime.promoteUserBuffer()); release.countDown();
            assertEquals("last", first.get(5, TimeUnit.SECONDS)); assertEquals("last", late.get(5, TimeUnit.SECONDS));
            assertEquals(1, notices.stream().filter(n -> n.kind() == ExecutionNotice.Kind.TURN_START).count());
            assertEquals(1, notices.stream().filter(n -> n.kind() == ExecutionNotice.Kind.TURN_DISPLAY_RESUME).count());
            assertTrue(extracted.await(5, TimeUnit.SECONDS)); assertEquals(1, calls.get());
        } finally { release.countDown(); }
    }
    private static ModelResponse response(String text) {
        return new ModelResponse(Message.assistant(List.of(new com.jadecode.messages.TextBlock(text))),
                new ModelResponse.Usage(0, 0), "end_turn");
    }

    @Test void backgroundMemoryDoesNotKeepRuntimeOrFutureBusy(@TempDir Path dir) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var notices = new CopyOnWriteArrayList<ExecutionNotice>();
        var output = new CopyOnWriteArrayList<String>();
        var errors = new CopyOnWriteArrayList<String>();
        var memory = new MemoryManager(request -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test did not release Memory"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            return response("{\"rollout_summary\":\"\",\"rollout_slug\":\"\"}");
        }, com.jadecode.support.TestPaths.workspace(dir), new JadeSettings.Memory(1000), output::add, errors::add);
        var engine = new AgentExecutor(new ScriptedLlmClient(response("final answer")), Map.of(), "test", 1000, new Hooks());
        engine.observe(notices::add);
        try (var runtime = new SessionRuntime(engine, memory, null, null, output::add, errors::add)) {
            runtime.start(false);
            var future = runtime.submit("question", null);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(1, output.stream().filter(line -> line.equals("[Reply] final answer")).count());
                assertEquals("final answer", future.get(5, TimeUnit.SECONDS), "后台记忆未完成也不能占用任务 future");
                assertEquals(SessionRuntime.State.IDLE, runtime.state());
                assertTrue(notices.stream().anyMatch(n -> n.kind() == ExecutionNotice.Kind.TURN_DISPLAY_STOP));
                assertTrue(notices.stream().anyMatch(n -> n.kind() == ExecutionNotice.Kind.TURN_END));
            } finally { release.countDown(); }
            assertEquals("final answer", future.get(5, TimeUnit.SECONDS));
            long cutoff = notices.stream().filter(n -> n.kind() == ExecutionNotice.Kind.TURN_DISPLAY_STOP)
                    .findFirst().orElseThrow().elapsedNanos();
            assertEquals(cutoff, notices.stream().filter(n -> n.kind() == ExecutionNotice.Kind.TURN_END)
                    .findFirst().orElseThrow().elapsedNanos(), "Memory cannot add duration at final settlement");
            assertTrue(errors.isEmpty(), errors.toString());
        } finally { release.countDown(); }
    }

    @Test void stopContinueResumesSameTurnAndFallbackRepliesAreNeverRepeated() throws Exception {
        var checks = new AtomicInteger();
        var hooks = new Hooks();
        hooks.register(Hooks.STOP, ignored -> checks.getAndIncrement() == 0 ? "continue" : null);
        var notices = new java.util.ArrayList<ExecutionNotice>();
        var output = new java.util.ArrayList<String>();
        var engine = new AgentExecutor(new ScriptedLlmClient(response("first"), response("last")), Map.of(), "test", 1000, hooks);
        engine.observe(notices::add);
        try (var runtime = new SessionRuntime(engine, output::add, ignored -> { })) {
            runtime.run(new java.util.ArrayList<>(List.of(Message.userText("question"))));
            assertEquals(List.of("[Reply] first", "[Reply] last"), output.stream().filter(s -> s.startsWith("[Reply]")).toList());
            assertEquals(1, notices.stream().filter(n -> n.kind() == ExecutionNotice.Kind.TURN_START).count());
            assertEquals(2, notices.stream().filter(n -> n.kind() == ExecutionNotice.Kind.TURN_DISPLAY_STOP).count());
            assertEquals(1, notices.stream().filter(n -> n.kind() == ExecutionNotice.Kind.TURN_DISPLAY_RESUME).count());
            assertEquals(1, notices.stream().filter(n -> n.kind() == ExecutionNotice.Kind.TURN_END).count());
        }
    }
}
