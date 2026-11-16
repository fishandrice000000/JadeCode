package com.jadecode.session.history;

import java.time.Instant;
import java.util.UUID;
import com.jadecode.llm.ThinkingSettings;
import com.jadecode.session.SessionUsage;

/**
 * 会话列表使用的轻量清单，不保存认证和旧权限。日志才是历史内容的事实来源。
 * 时间保存为 ISO 文本，避免将共享 JSON 配置改成依赖 Java 时间模块。
 */
public record SessionMetadata(int version, String id, String workspace, String title,
        String createdAt, String updatedAt, String model, boolean customTitle, SessionUsage.Totals usage,
        ThinkingSettings thinking, String backendIdentity) {
    /** 当前唯一的会话格式版本；读取器拒绝其它版本，避免猜补旧记录。 */
    public static final int VERSION = 5;

    /** 校验文件身份；不允许把任意路径字符串当成会话 ID。 */
    public SessionMetadata {
        requireId(id);
        if (version != VERSION || workspace == null || workspace.isBlank() || title == null
                || model == null)
            throw new IllegalArgumentException("Session manifest fields or version are invalid");
        if (usage == null)
            usage = SessionUsage.Totals.empty();
        if (backendIdentity == null)
            backendIdentity = "";
        // 只接受本项目生成的无秘密摘要，拒绝将原 URL、Key 或任意多行文字混入身份。
        if (!backendIdentity.isEmpty()
                && (!backendIdentity.matches("(anthropic-compatible|opencode-go):messages:[0-9a-f]{64}")))
            throw new IllegalArgumentException("Invalid session backend identity");
        Instant.parse(createdAt);
        Instant.parse(updatedAt);
    }

    /** UUID.fromString 容忍部分短写形式，这里额外要求标准的小写完整形式。 */
    public static void requireId(String id) {
        if (id == null || !UUID.fromString(id).toString().equals(id))
            throw new IllegalArgumentException("Session ID must be a complete UUID");
    }

    /** 新日志采用首条请求作为标题；以后只更新时间，不为标题额外请求模型。 */
    SessionMetadata updated(String firstPrompt, String time) {
        String next = title;
        if (!customTitle && isDefaultTitle(title) && firstPrompt != null && !firstPrompt.isBlank()) {
            next = firstPrompt.replaceAll("\\s+", " ").strip();
            next = next.substring(0, Math.min(100, next.length()));
        }
        return new SessionMetadata(VERSION, id, workspace, next, createdAt, time, model, customTitle, usage, thinking,
                backendIdentity);
    }

    /**
     * 只有当前默认标题视为未命名；其它标题均保留原文。
     */
    private static boolean isDefaultTitle(String value) {
        return value.equals("New session");
    }

    /** 写入用量后更新列表用的汇总；逐次日志仍是恢复统计的依据。 */
    SessionMetadata withUsage(SessionUsage.Totals value) {
        return new SessionMetadata(VERSION, id, workspace, title, createdAt, updatedAt, model, customTitle, value,
                thinking, backendIdentity);
    }

    /** 用户命名只改清单字段，不把名称拼进路径；名称允许重复，由 CLI 再消歧。 */
    SessionMetadata renamed(String value) {
        return new SessionMetadata(VERSION, id, workspace, validTitle(value), createdAt, updatedAt, model, true, usage,
                thinking, backendIdentity);
    }

    /** null 表示尚未保存会话覆盖，启动时仍采用 config.json 的默认值。 */
    SessionMetadata withThinking(ThinkingSettings value) {
        return new SessionMetadata(VERSION, id, workspace, title, createdAt, updatedAt, model, customTitle, usage,
                value, backendIdentity);
    }

    /** 拒绝控制字符、换行及超长名称，避免列表与退出提示被名称内容扰乱。 */
    public static String validTitle(String value) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Session name cannot be empty");
        String title = value.strip();
        if (title.codePointCount(0, title.length()) > 100 || title.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(
                    "Session name is limited to 100 characters and cannot contain newlines or control characters");
        return title;
    }
}
