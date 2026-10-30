package com.jadecode.cli;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jadecode.agent.AgentExecutor;
import com.jadecode.config.WorkspacePaths;
import com.jadecode.cron.CronScheduler;
import com.jadecode.hooks.Hooks;
import com.jadecode.llm.ModelResponse;
import com.jadecode.messages.Message;
import com.jadecode.messages.TextBlock;
import com.jadecode.session.SessionRuntime;

/** 首轮只登记一次；磁盘中的已到期 cron 不再接入运行时，不调用外部模型。 */
class SessionHostStartupTest {
    @Test void initialPromptRunsOnceWithoutDeliveringStoredCron(@TempDir Path workspace) throws Exception {
        var scheduler = new CronScheduler(com.jadecode.support.TestPaths.workspace(workspace));
        scheduler.schedule("* * * * *", "DUE_CRON", false, true);
        scheduler.pollDue(); // 先将任务持久化为待交付，模拟启动前已经到期。
        var requests = new CopyOnWriteArrayList<String>();
        var cronSeen = new CountDownLatch(1);
        var errors = new CopyOnWriteArrayList<String>();
        try (var host = new SessionHost(com.jadecode.support.TestPaths.workspace(workspace), generation -> {
            var engine = new AgentExecutor(request -> {
                // 保存每次模型实际看见的历史；保存的 cron 不应在新会话自动执行。
                String history = request.messages().toString();
                requests.add(history);
                if (history.contains("DUE_CRON")) cronSeen.countDown();
                return new ModelResponse(Message.assistant(List.of(new TextBlock("done"))),
                        new ModelResponse.Usage(1, 1), "end_turn");
            }, Map.of(), "test", 1000, new Hooks());
            return new SessionRuntime(engine, ignored -> { }, errors::add);
        }, errors::add)) {
            var opened = host.open(null);
            var result = host.startWithInitial(opened.generation(), "INITIAL_USER", null);
            assertEquals("done", result.get(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,
                    () -> host.startWithInitial(opened.generation(), "DUPLICATE", null));
            assertEquals(1, cronSeen.getCount(), "stored cron must not be wired into this session");
            assertTrue(requests.get(0).contains("INITIAL_USER"));
            assertFalse(requests.get(0).contains("DUE_CRON"), "cron overtook the initial user prompt");
            assertTrue(requests.stream().noneMatch(request -> request.contains("DUPLICATE")));
            // 模型入口已被观察不代表日志提交完成；先等消费者空闲，避免清理与落盘竞争。
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (host.current().state() != SessionRuntime.State.IDLE && System.nanoTime() < deadline)
                Thread.sleep(5);
            assertEquals(SessionRuntime.State.IDLE, host.current().state());
        }
        assertTrue(errors.isEmpty(), errors.toString());
    }
}
