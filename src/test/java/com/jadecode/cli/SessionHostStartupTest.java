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

/** 首轮与已到期 cron 同时存在时，验证真实 Host/Inbox/消费者的采用顺序，不调用外部模型。 */
class SessionHostStartupTest {
    @Test void initialPromptPrecedesDueCronAndCannotBeRegisteredTwice(@TempDir Path workspace) throws Exception {
        var scheduler = new CronScheduler(workspace);
        scheduler.schedule("* * * * *", "DUE_CRON", false, true);
        scheduler.pollDue(); // 先将任务持久化为待交付，模拟启动前已经到期。
        var requests = new CopyOnWriteArrayList<String>();
        var cronSeen = new CountDownLatch(1);
        var errors = new CopyOnWriteArrayList<String>();
        try (var host = new SessionHost(new WorkspacePaths(workspace), generation -> {
            var engine = new AgentExecutor(request -> {
                // 保存每次模型实际看见的历史；第二次含 cron 不代表它先于用户首轮被采用。
                String history = request.messages().toString();
                requests.add(history);
                if (history.contains("DUE_CRON")) cronSeen.countDown();
                return new ModelResponse(Message.assistant(List.of(new TextBlock("done"))),
                        new ModelResponse.Usage(1, 1), "end_turn");
            }, Map.of(), "test", 1000, new Hooks());
            return new SessionRuntime(engine, null, null, scheduler, ignored -> { }, errors::add);
        }, errors::add)) {
            var opened = host.open(null);
            var result = host.startWithInitial(opened.generation(), "INITIAL_USER", null, true);
            assertEquals("done", result.get(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,
                    () -> host.startWithInitial(opened.generation(), "DUPLICATE", null, true));
            assertTrue(cronSeen.await(5, TimeUnit.SECONDS), "the due cron was never delivered");
            assertTrue(requests.get(0).contains("INITIAL_USER"));
            assertFalse(requests.get(0).contains("DUE_CRON"), "cron overtook the initial user prompt");
            assertTrue(requests.stream().noneMatch(request -> request.contains("DUPLICATE")));
            // 模型入口已被观察不代表日志/cron 确认完成；先等消费者空闲，避免清理与落盘竞争。
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (host.current().state() != SessionRuntime.State.IDLE && System.nanoTime() < deadline)
                Thread.sleep(5);
            assertEquals(SessionRuntime.State.IDLE, host.current().state());
        }
        assertTrue(errors.isEmpty(), errors.toString());
    }
}
