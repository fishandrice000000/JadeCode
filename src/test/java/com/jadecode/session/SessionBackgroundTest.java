package com.jadecode.session;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jadecode.agent.AgentExecutor;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.execution.ExecutionNotice;
import com.jadecode.hooks.Hooks;
import com.jadecode.llm.ModelResponse;
import com.jadecode.messages.Message;
import com.jadecode.messages.TextBlock;
import com.jadecode.messages.ToolExchangeProtocol;
import com.jadecode.messages.ToolUseBlock;
import com.jadecode.process.BackgroundManager;
import com.jadecode.process.ShellCommand;
import com.jadecode.tools.BashTool;
import com.jadecode.util.Json;

/** 用受控 Shell 和模型替身验证真实 Inbox/Runtime 生命周期，不调用外部模型。 */
class SessionBackgroundTest {
    @TempDir
    Path workspace;

    /** 后台运行期间模型能推进其他工作，但候选结束不能越过未交付结果。 */
    @Test
    void startingTurnWaitsAndAdoptsFullOutputAfterToolBatch() throws Exception {
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        String evidence = "x".repeat(700) + "BUILD_FAILURE_AT_END";
        try (var background = new BackgroundManager(workspace, 10, (command, cwd, timeout, context) -> {
            await(release);
            return new ShellCommand.Result(evidence, 1, false);
        })) {
            var engine = new AgentExecutor(request -> {
                ToolExchangeProtocol.validateHistory(request.messages());
                return switch (calls.getAndIncrement()) {
                    case 0 -> launch();
                    case 1 -> text("candidate answer before build completes");
                    case 2 -> {
                        assertTrue(request.messages().toString().contains("BUILD_FAILURE_AT_END"));
                        assertTrue(request.messages().toString().contains("<exit_code>1</exit_code>"));
                        yield text("build failure observed");
                    }
                    default -> throw new AssertionError("Unexpected model polling");
                };
            }, Map.of("bash", new BashTool(background)), "test", 1000, new Hooks());
            try (var runtime = new SessionRuntime(engine, null, background, ignored -> {
            }, ignored -> {
            })) {
                runtime.start();
                var result = runtime.submit("build and inspect", null);
                until(() -> runtime.state() == SessionRuntime.State.WAITING_BACKGROUND);
                assertEquals(2, calls.get());
                assertFalse(result.isDone());
                release.countDown();
                assertEquals("build failure observed", result.get(5, TimeUnit.SECONDS));
                assertFalse(background.hasPending());
                assertEquals(3, calls.get());
            }
        } finally {
            release.countDown();
        }
    }

    /** 取消 A 不杀后台；B 不等待旧工作；空闲通知不启动轮次，C 可被动采用其结果。 */
    @Test
    void cancelledTurnLeavesCommandRunningAndLaterTurnAdoptsWithoutWaiting() throws Exception {
        var release = new CountDownLatch(1);
        var completed = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        var execution = new AtomicReference<ExecutionContext>();
        var calls = new AtomicInteger();
        var notices = new CopyOnWriteArrayList<ExecutionNotice>();
        try (var background = new BackgroundManager(workspace, 10, (command, cwd, timeout, context) -> {
            execution.set(context);
            await(release);
            assertFalse(context.isCancelled(), "原轮次取消不能撤销已接纳后台命令");
            return new ShellCommand.Result("LATE_BUILD_RESULT", 0, false);
        })) {
            background.observe(event -> {
                if (event.state().equals("completed")) {
                    worker.set(Thread.currentThread());
                    completed.countDown();
                }
            });
            var engine = new AgentExecutor(request -> switch (calls.getAndIncrement()) {
                case 0 -> launch();
                case 1 -> text("A candidate");
                case 2 -> {
                    assertFalse(request.messages().toString().contains("LATE_BUILD_RESULT"));
                    yield text("B done");
                }
                case 3 -> {
                    String history = request.messages().toString();
                    assertTrue(history.contains("LATE_BUILD_RESULT"));
                    assertTrue(history.contains(execution.get().id()));
                    yield text("C used previous build");
                }
                default -> throw new AssertionError("Notification unexpectedly created a model turn");
            }, Map.of("bash", new BashTool(background)), "test", 1000, new Hooks());
            engine.observe(notices::add);
            var store = new com.jadecode.session.history.SessionStore(
                    com.jadecode.support.TestPaths.workspace(workspace), ignored -> {
                    });
            try (var opened = store.create("offline");
                    var runtime = new SessionRuntime(engine, null, background, ignored -> {
                    }, ignored -> {
                    })) {
                runtime.restore(opened.journal(), opened.restored());
                runtime.start();
                var first = runtime.submit("A build", null);
                until(() -> runtime.state() == SessionRuntime.State.WAITING_BACKGROUND);
                runtime.cancelCurrent();
                assertThrows(ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS));
                assertTrue(background.hasPending());
                assertEquals("B done", runtime.submit("B unrelated work", null).get(5, TimeUnit.SECONDS));
                release.countDown();
                assertTrue(completed.await(5, TimeUnit.SECONDS));
                // 等发布线程真正返回，确保通知已入 Inbox，而不仅是命令结果已生成。
                assertTrue(worker.get().join(Duration.ofSeconds(5)));
                assertEquals(3, calls.get());
                assertEquals(SessionRuntime.State.IDLE, runtime.state());
                assertEquals("C used previous build", runtime.submit("C continue", null).get(5, TimeUnit.SECONDS));
                assertFalse(background.hasPending());
                assertEquals(3, notices.stream().filter(n -> n.kind() == ExecutionNotice.Kind.TURN_START).count());
                String adoptedRun = notices.stream().filter(n -> n.kind() == ExecutionNotice.Kind.TURN_START).toList()
                        .getLast().id();
                var records = java.nio.file.Files.readAllLines(opened.journal().eventsPath()).stream()
                        .map(line -> {
                            try {
                                return Json.mapper().readTree(line);
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        }).toList();
                var delivery = records.stream().filter(r -> r.path("reason").asText().equals("notification")
                        && r.toString().contains("LATE_BUILD_RESULT")).findFirst().orElseThrow();
                assertEquals(adoptedRun, delivery.path("runId").asText());
                assertTrue(delivery.toString().contains(execution.get().id()), "保留来源 A 与采用 C 的关联");
                assertNotEquals(execution.get().id(), adoptedRun);
            }
        } finally {
            release.countDown();
        }
    }

    /** 候选回答返回期间到达的通知必须再交给模型，不能只检查无工具调用就结束。 */
    @Test
    void notificationArrivingDuringModelRequestForcesContinuation() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var engine = new AgentExecutor(request -> {
            if (calls.getAndIncrement() == 0) {
                entered.countDown();
                await(release);
                return text("candidate");
            }
            assertTrue(request.messages().toString().contains("TEAM_QUESTION"));
            return text("question handled");
        }, Map.of(), "test", 1000, new Hooks());
        try (var runtime = new SessionRuntime(engine, ignored -> {
        }, ignored -> {
        })) {
            runtime.start();
            var result = runtime.submit("work", null);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(runtime.receive(new SessionEvent.Incoming("q", "agent", "leader", null, null,
                    "question", "TEAM_QUESTION", null, true)));
            release.countDown();
            assertEquals("question handled", result.get(5, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
        } finally {
            release.countDown();
        }
    }

    /** 同一调用只有启动 tool_result；完成内容随后作为通知文本，不复用原 tool_use_id。 */
    private static ModelResponse launch() {
        return new ModelResponse(Message.assistant(List.of(new ToolUseBlock("launch", "bash",
                Json.mapper().createObjectNode().put("command", "fixture build").put("run_in_background", true)))),
                new ModelResponse.Usage(1, 1), "tool_use");
    }

    /** 生成普通正式回复，帮助区分候选回答与真正轮次结束。 */
    private static ModelResponse text(String value) {
        return new ModelResponse(Message.assistant(List.of(new TextBlock(value))), new ModelResponse.Usage(1, 1),
                "end_turn");
    }

    /** 屏障只控制替身时序；超时明确失败，不依赖不可见的后台 sleep 顺序。 */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("Test barrier timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 有限等待真实运行状态，失败保留明确断言。 */
    private static void until(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline)
            Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }
}
