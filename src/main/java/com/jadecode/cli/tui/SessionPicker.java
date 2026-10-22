package com.jadecode.cli.tui;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import com.jadecode.session.history.SessionMetadata;
import com.jadecode.tui.components.SelectList;

/**
 * 当前工作区会话选择器：真实标题、英文相对时间、内部 UUID，支持 named-only/路径/最近排序。
 *
 * <p>不跨工作区、不删除会话、不以标题当 ID；同名会话各占一项。候选标签按打开时快照的
 * 当前时间生成，避免列表内相邻项跨分钟跳变；内部 ID 始终是完整 UUID，显示裁短不影响选择。
 * 不读取日志正文、不触发恢复/工具重跑。</p>
 */
public final class SessionPicker {
    private final List<Row> rows;
    private boolean namedOnly;
    private boolean showPath;
    private boolean recentFirst = true;

    private record Row(SessionMetadata metadata, String relative) { }

    public SessionPicker(List<SessionMetadata> sessions) { this(sessions, Clock.systemUTC()); }

    /** 注入时钟便于测试相对时间。 */
    SessionPicker(List<SessionMetadata> sessions, Clock clock) {
        Instant now = clock.instant();
        var result = new ArrayList<Row>();
        for (SessionMetadata session : sessions)
            result.add(new Row(session, UiMessages.relativeTime(Instant.parse(session.updatedAt()), now)));
        rows = List.copyOf(result);
    }

    /** @return 过滤/排序后的候选；ID 为会话 UUID。 */
    public SelectList choices() {
        var visible = rows.stream().filter(row -> !namedOnly || row.metadata().customTitle())
                .sorted(recentFirst
                        ? Comparator.comparing((Row row) -> Instant.parse(row.metadata().updatedAt())).reversed()
                        : Comparator.comparing(row -> row.metadata().title()))
                .toList();
        var items = new ArrayList<SelectList.Item>();
        for (Row row : visible) {
            String title = row.metadata().title().isBlank() || row.metadata().title().equals("新会话")
                    ? "New session" : row.metadata().title();
            String description = row.relative();
            if (showPath) description += " · " + row.metadata().workspace();
            items.add(new SelectList.Item(row.metadata().id(), title, description));
        }
        return new SelectList(items, true);
    }

    /** 切换只看用户命名的会话；返回是否变化。 */
    public boolean toggleNamedOnly() { namedOnly = !namedOnly; return true; }
    public boolean namedOnly() { return namedOnly; }
    /** 切换是否显示工作区路径。 */
    public void togglePath() { showPath = !showPath; }
    /** @return 会话行是否显示工作区路径。 */
    public boolean pathsVisible() { return showPath; }
    /** 切换最近/名称排序。 */
    public void toggleSort() { recentFirst = !recentFirst; }
    public boolean recentFirst() { return recentFirst; }

    /** 只接受本次清单中的 UUID；不根据可能重名的标题猜会话。 */
    public String id(String selection) {
        return rows.stream().anyMatch(row -> row.metadata().id().equals(selection)) ? selection : null;
    }
}
