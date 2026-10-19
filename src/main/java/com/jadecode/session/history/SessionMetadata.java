package com.jadecode.session.history;

import java.time.Instant;
import java.util.UUID;

/**
 * 会话列表使用的轻量清单，不保存认证和旧权限。日志才是历史内容的事实来源。
 * 时间保存为 ISO 文本，避免将共享 JSON 配置改成依赖 Java 时间模块。
 */
public record SessionMetadata(int version, String id, String workspace, String title,
        String createdAt, String updatedAt, String model) {
    public static final int VERSION = 1;

    /** 校验文件身份；不允许把任意路径字符串当成会话 ID。 */
    public SessionMetadata {
        requireId(id);
        if (version != VERSION || workspace == null || workspace.isBlank() || title == null || model == null)
            throw new IllegalArgumentException("会话清单字段或版本无效");
        Instant.parse(createdAt); Instant.parse(updatedAt);
    }

    /** UUID.fromString 容忍部分短写形式，这里额外要求标准的小写完整形式。 */
    public static void requireId(String id) {
        if (id == null || !UUID.fromString(id).toString().equals(id))
            throw new IllegalArgumentException("会话 ID 必须是完整 UUID");
    }

    /** 新日志采用首条请求作为标题；以后只更新时间，不为标题额外请求模型。 */
    SessionMetadata updated(String firstPrompt, String time) {
        String next = title;
        if (title.equals("新会话") && firstPrompt != null && !firstPrompt.isBlank()) {
            next = firstPrompt.replaceAll("\\s+", " ").strip();
            next = next.substring(0, Math.min(100, next.length()));
        }
        return new SessionMetadata(version, id, workspace, next, createdAt, time, model);
    }
}
