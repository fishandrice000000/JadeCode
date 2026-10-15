package com.jadecode.cron;

import java.time.OffsetDateTime;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 一项定时 prompt 的不可变快照，不是 s10 业务任务，也不是 s11 已启动命令。
 *
 * @param id cron_ 加八位十六进制字符，分配后不变
 * @param cron 五段时间规则，使用进程本地时间
 * @param prompt 到时交给模型的新任务，不是 shell 脚本
 * @param recurring 首次收到后是否保留，false 表示一次性交付
 * @param durable 是否跨进程保存定义与待交付状态
 * @param pendingDelivery 已到期但尚未成功确认模型接收；不表示业务在执行或成功
 * @param lastFired 上次匹配的分钟标记，带时区偏移；从未触发时为 null
 */
public record CronJob(String id, String cron, String prompt, boolean recurring, boolean durable,
        @JsonProperty("pending_delivery") boolean pendingDelivery, @JsonProperty("last_fired") String lastFired) {
    public static final String ID_PATTERN = "cron_[0-9a-f]{8}";

    /**
     * 所有来源统一校验，包括模型创建和磁盘恢复；不修改文件或启动线程。
     * 非法记录明确失败，不能反序列化出绕过规则的状态。
     */
    public CronJob {
        if (id == null || !id.matches(ID_PATTERN)) throw new IllegalArgumentException("Invalid cron job ID");
        CronExpression.parse(cron);
        if (prompt == null || prompt.isBlank() || prompt.length() > 16000) {
            throw new IllegalArgumentException("prompt must contain 1-16000 characters");
        }
        if (lastFired != null) OffsetDateTime.parse(lastFired);
        if (pendingDelivery && lastFired == null) {
            throw new IllegalArgumentException("Pending cron requires a last-fired minute");
        }
    }

    /** 同一个定义变更交付状态，不变更 ID、时间规则或用户任务正文。 */
    CronJob delivery(boolean pending, String minute) {
        return new CronJob(id, cron, prompt, recurring, durable, pending, minute);
    }
}
