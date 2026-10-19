package com.jadecode.cli.tui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.jadecode.session.history.SessionMetadata;
import com.jadecode.tui.components.SelectList;

/** 会话列表的显示标签与真实 ID 分开保存，不从被终端裁短的标题中猜 UUID。 */
public final class SessionPicker {
    private final Map<String, String> ids = new LinkedHashMap<>();

    /** 使用全部轻量清单供 SelectList 本地筛选，不照搬固定首批 50 条的限制。 */
    public SessionPicker(List<SessionMetadata> sessions) {
        for (var session : sessions) ids.put(com.jadecode.tui.text.DisplayWidth.clean(session.title())
                + " · " + session.updatedAt() + " · " + session.id(), session.id());
    }
    public SelectList choices() { return new SelectList(List.copyOf(ids.keySet())); }
    public String id(String label) { return ids.get(label); }
}
