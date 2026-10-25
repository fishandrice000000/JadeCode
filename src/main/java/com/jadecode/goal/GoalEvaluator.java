package com.jadecode.goal;

import java.util.List;
import com.jadecode.messages.Message;

/**
 * 独立完成判断端口：只读目标和对话，不获得工具或修改历史的引用。
 * 生产用 PromptGoalEvaluator；测试可注入明确结果，不把离线测试当作真实判断质量证据。
 */
@FunctionalInterface
public interface GoalEvaluator {
    /** 执行一次判断；调用者在请求前后检查会话/目标身份。网络/格式故障原样交回，不自动放行。 */
    Evaluation evaluate(String condition, List<Message> messages) throws Exception;

    /** ok 与 impossible 互斥；未完成必须提供原因，供原循环继续工作或交给用户。 */
    record Evaluation(boolean ok, String reason, boolean impossible) {
        public Evaluation {
            if (reason == null || reason.isBlank() || reason.length() > 4000 || ok && impossible)
                throw new IllegalArgumentException("Invalid goal evaluation");
            reason = reason.strip();
        }
    }
}
