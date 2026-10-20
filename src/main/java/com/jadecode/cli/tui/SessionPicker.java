package com.jadecode.cli.tui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import com.jadecode.session.history.SessionMetadata;
import com.jadecode.tui.components.SelectList;

/**
 * 会话选择器只显示标题和距上次使用的时间，UUID 留作内部选择值，不占终端宽度。
 * 两条同名且更新时间相同的会话仍是两个选项，不能用显示标签作 Map 的键而丢掉其中一条。
 */
public final class SessionPicker {
    private final Map<String, String> labels = new LinkedHashMap<>();

    /** 使用打开列表时的当前时间生成标签；读取全部已提供清单，不查日志、不启动会话。 */
    public SessionPicker(List<SessionMetadata> sessions) {
        this(sessions, Clock.systemUTC());
    }

    /** 注入时钟便于检查“刚刚/分钟前”等显示；同一次打开只取一次时间，不让相邻项跨分钟跳变。 */
    SessionPicker(List<SessionMetadata> sessions, Clock clock) {
        Instant now = clock.instant();
        for (var session : sessions) labels.put(session.id(),
                com.jadecode.tui.text.DisplayWidth.clean(session.title()).replace('\n', ' ')
                + " · " + elapsedSince(Instant.parse(session.updatedAt()), now));
    }

    /** 返回按可读标签筛选、按真实 ID 选择的列表；显示被裁短也不影响选中哪条会话。 */
    public SelectList choices() { return new SelectList(List.copyOf(labels.keySet()), labels::get); }

    /** 只接受本次清单中的内部选择值，不根据可能重名的标题猜测会话。 */
    public String id(String selection) { return labels.containsKey(selection) ? selection : null; }

    /**
     * 把已验证的更新时间排成相对时间，不显示 UTC 时间戳或小数秒。
     * 不足一分钟显示“刚刚”；分钟/小时/天取已过去的整数单位。
     * 系统校时后旧记录可能暂时在未来，此时也显示“刚刚”，不显示负数。
     */
    private static String elapsedSince(Instant updatedAt, Instant now) {
        long seconds = Math.max(0, Duration.between(updatedAt, now).getSeconds());
        if (seconds < 60) return "刚刚";
        if (seconds < 3600) return seconds / 60 + " 分钟前";
        if (seconds < 86_400) return seconds / 3600 + " 小时前";
        return seconds / 86_400 + " 天前";
    }
}
