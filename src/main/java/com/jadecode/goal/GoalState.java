package com.jadecode.goal;

import java.time.Instant;
import java.util.UUID;

/**
 * 会话目标的不可变事实，与当前请求的 task.goal（任务说明）不同。
 * 条件来自显式用户命令；判断次数和原因来自宿主，不从主模型自述推算完成状态。
 * 保存的是墙上时间，恢复不恢复线程/授权；重新开始计数由 Controller.restore 决定。
 */
public record GoalState(String id, String condition, Status status, int evaluations, String startedAt, String reason) {
    public enum Status { ACTIVE, ACHIEVED, IMPOSSIBLE, CLEARED }

    /** 日志与新输入使用同一校验；null、空条件和非法状态不能悄悄恢复成活跃目标。 */
    public GoalState {
        if (id == null || !UUID.fromString(id).toString().equals(id) || status == null || evaluations < 0 || reason == null)
            throw new IllegalArgumentException("Invalid goal state");
        condition = validCondition(condition);
        Instant.parse(startedAt);
    }

    /** 仅裁去首尾空白，保留用户条件原意；按 Java 字符数限制输入和持久化体积。 */
    public static String validCondition(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 4000)
            throw new IllegalArgumentException("Goal condition must contain 1 to 4000 characters");
        return value.strip();
    }

    /** @return 是否仍欠完成判断；失败/清除不会在恢复聊天时重新启动。 */
    public boolean active() { return status == Status.ACTIVE; }
}
