package com.jadecode.support;

import java.util.List;
import com.jadecode.agent.AgentExecutor;
import com.jadecode.context.ContextCompactor;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.llm.LlmException;
import com.jadecode.messages.Message;

/** 压缩内核测试只组合正式执行步骤，保留“未变化的尾部按原引用保留”等算法断言。 */
public final class ExecutorSteps {
    /**
     * 由测试拥有历史，逐步采用 request/executeTools/compactAfterBatch 的结果。
     * 不模拟会话队列、日志或 Todo 提醒；这些职责由 SessionRuns 的真实会话测试覆盖。
     */
    public static void run(AgentExecutor executor, List<Message> history) throws LlmException {
        String goal = ContextCompactor.extractActiveRequest(history);
        var context = ExecutionContext.create(ExecutionContext.Source.USER, null);
        java.util.function.Consumer<List<Message>> adopt = candidate -> {
            if (candidate != history) {
                history.clear();
                history.addAll(candidate);
            }
        };
        while (true) {
            var reply = executor.request(history, goal, executor.system(), adopt);
            history.add(reply.message());
            if (reply.calls().isEmpty()) {
                var continuation = executor.stop(history);
                if (continuation == null)
                    return;
                history.add(Message.userText(continuation.prompt()));
            } else {
                var batch = executor.executeTools(reply, context);
                history.add(Message.userBlocks(batch.results()));
                if (batch.failure() != null)
                    throw batch.failure();
                executor.compactAfterBatch(batch, history, goal, adopt);
            }
        }
    }

    private ExecutorSteps() {
    }
}
