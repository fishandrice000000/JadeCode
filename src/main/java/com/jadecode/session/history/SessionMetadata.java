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
        ThinkingSettings thinking) {
    public static final int VERSION = 2;

    /** 兼容旧创建代码和版本 1 文件；旧文件没有用量，并不代表真实花费为零。 */
    public SessionMetadata(int version, String id, String workspace, String title, String createdAt, String updatedAt, String model) {
        this(version, id, workspace, title, createdAt, updatedAt, model, false, SessionUsage.Totals.empty(), null);
    }

    /** 校验文件身份；不允许把任意路径字符串当成会话 ID。 */
    public SessionMetadata {
        requireId(id);
        if (version < 1 || version > VERSION || workspace == null || workspace.isBlank() || title == null || model == null)
            throw new IllegalArgumentException("会话清单字段或版本无效");
        if (usage == null) usage = SessionUsage.Totals.empty();
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
        if (!customTitle && title.equals("新会话") && firstPrompt != null && !firstPrompt.isBlank()) {
            next = firstPrompt.replaceAll("\\s+", " ").strip();
            next = next.substring(0, Math.min(100, next.length()));
        }
        return new SessionMetadata(VERSION, id, workspace, next, createdAt, time, model, customTitle, usage, thinking);
    }

    /** 写入用量后更新列表用的汇总；逐次日志仍是恢复统计的依据。 */
    SessionMetadata withUsage(SessionUsage.Totals value) {
        return new SessionMetadata(VERSION, id, workspace, title, createdAt, updatedAt, model, customTitle, value, thinking);
    }
    /** 用户命名只改清单字段，不把名称拼进路径；名称允许重复，由 CLI 再消歧。 */
    SessionMetadata renamed(String value) {
        return new SessionMetadata(VERSION, id, workspace, validTitle(value), createdAt, updatedAt, model, true, usage, thinking);
    }
    /** null 表示尚未保存会话覆盖，启动时仍采用 config.json 的默认值。 */
    SessionMetadata withThinking(ThinkingSettings value) {
        return new SessionMetadata(VERSION, id, workspace, title, createdAt, updatedAt, model, customTitle, usage, value);
    }
    /** 拒绝控制字符、换行及超长名称，避免列表与退出提示被名称内容扰乱。 */
    public static String validTitle(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("会话名称不能为空");
        String title = value.strip();
        if (title.codePointCount(0, title.length()) > 100 || title.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("会话名称限 100 个字符，不能包含换行或控制字符");
        return title;
    }
}
