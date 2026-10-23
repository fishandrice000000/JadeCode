package com.jadecode.tui.markdown;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import com.jadecode.tui.components.Markdown;
import com.jadecode.tui.theme.Theme;
import com.jadecode.tui.theme.ThemePalette;

/** 受控 worker 验证调度契约；不用真实模型，也不依靠机器快慢猜是否产生积压。 */
class MarkdownSchedulingTest {
    private JsTextEngine engine;
    private static final Theme THEME = Theme.from(ThemePalette.builtin("dark"), true, 1);

    @BeforeEach void start() throws Exception { engine = JsTextEngine.create(); }
    @AfterEach void stop() { engine.close(); }

    /** 占住 worker，但不模拟坏 JS；后续公开 lex 请求必须正常排队而非误触计算超时。 */
    private CountDownLatch holdWorker() throws Exception {
        Field field = JsTextEngine.class.getDeclaredField("worker"); field.setAccessible(true);
        ExecutorService worker = (ExecutorService) field.get(engine);
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        worker.execute(() -> {
            started.countDown();
            try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        return release;
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("async result did not arrive");
            Thread.sleep(10);
        }
    }

    @Test void manyPrefixesPublishOnlyTheLatestAndKeepDifferentMessagesIndependent() throws Exception {
        var release = holdWorker(); var paints = new AtomicInteger();
        var markdown = new Markdown(() -> engine, paints::incrementAndGet);
        try {
            for (int i = 0; i < 80; i++) markdown.render("reply", "prefix " + i, 80, THEME, false);
            markdown.render("other", "independent message", 80, THEME, false);
        } finally { release.countDown(); }
        await(() -> paints.get() == 2);
        assertEquals(2, markdown.parsedCount(), "obsolete prefix must not publish or retain its DTO");
        assertEquals(1, markdown.contentRevision("reply"));
        assertEquals(1, markdown.contentRevision("other"));
        assertEquals("prefix 79", markdown.render("reply", "prefix 79", 80, THEME, false).getFirst().text());
        assertEquals("independent message", markdown.render("other", "independent message", 80, THEME, false).getFirst().text());
    }

    @Test void invalidatingWhileQueuedCannotResurrectTheOldSession() throws Exception {
        var release = holdWorker(); var paints = new AtomicInteger();
        var markdown = new Markdown(() -> engine, paints::incrementAndGet);
        markdown.render("old", "old reply", 80, THEME, false);
        markdown.invalidate(); release.countDown();
        engine.lex("barrier", 1).get(15, TimeUnit.SECONDS);
        assertEquals(0, paints.get()); assertEquals(0, markdown.parsedCount());
        markdown.render("new", "new reply", 80, THEME, false);
        await(() -> paints.get() == 1);
        assertEquals(1, markdown.contentRevision("new"));
    }

    @Test void cachedFallbackStillUpgradesWhenTheEngineArrives() throws Exception {
        var available = new java.util.concurrent.atomic.AtomicReference<JsTextEngine>();
        var paints = new AtomicInteger();
        var markdown = new Markdown(available::get, paints::incrementAndGet);
        String source = "```java\nint x = 1;\n```";
        var first = markdown.render(source, 80, THEME, false);
        assertSame(first, markdown.render(source, 80, THEME, false));
        available.set(engine); markdown.render(source, 80, THEME, false);
        await(() -> paints.get() == 1);
        assertTrue(markdown.render(source, 80, THEME, false).stream().flatMap(line -> line.spans().stream())
                .anyMatch(span -> span.style().fg().equals(THEME.color("syntaxKeyword"))));
    }

    @Test void waitingMoreThanFiveSecondsDoesNotTimeoutOrBreakAnotherTask() throws Exception {
        var release = holdWorker();
        var queued = engine.lex("still valid", 1);
        try {
            // 这是曾经触发误关闭 Context 的真实期限；需要跨过它才能验证队列与计算分离。
            Thread.sleep(5200);
            assertFalse(queued.isDone(), "queue wait is not execution time");
        } finally { release.countDown(); }
        assertFalse(queued.get(15, TimeUnit.SECONDS).tokens().isEmpty());
        assertTrue(engine.ready());
        assertFalse(engine.lex("next request", 2).get(15, TimeUnit.SECONDS).tokens().isEmpty());
    }

    @Test void genuinelyRunningGuestLoopStillTimesOutAndTheNextRequestRecovers() throws Exception {
        // 私有 submit 只用于测试夹具：在真实沙箱执行死循环，不能给产品增加执行任意 JS 的入口。
        var submit = JsTextEngine.class.getDeclaredMethod("submit", Callable.class); submit.setAccessible(true);
        Field context = JsTextEngine.class.getDeclaredField("context"); context.setAccessible(true);
        Callable<Object> loop = () -> ((org.graalvm.polyglot.Context) context.get(engine)).eval("js", "while (true) {}");
        var future = (CompletableFuture<?>) submit.invoke(engine, loop);
        assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
        assertFalse(engine.lex("recovered", 3).get(15, TimeUnit.SECONDS).tokens().isEmpty());
        assertTrue(engine.ready());
    }
}
