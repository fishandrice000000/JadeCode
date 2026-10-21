package com.jadecode.cli.tui.view;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.jadecode.cli.tui.TuiState;
import com.jadecode.cli.tui.model.ToolDisplayState;
import com.jadecode.cli.tui.model.TranscriptEntry;
import com.jadecode.cli.tui.tools.ToolRendererRegistry;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.StyledLine;

/**
 * 组装欢迎区和全部转录条目；只读模型，缓存按条目版本/宽度/主题 revision 失效。
 *
 * <p>不再负责编辑器/footer/审批，也不生成旧的 bottomStartRow 底锚。工具条目按可信身份交给
 * Registry，普通消息交给 MessageCard（C13 卡容器）；历史/实时状态由条目自身保存，不会被
 * 渲染缓存改写。空转录时显示 WelcomeView。</p>
 */
public final class ConversationView {
    private final MessageCard cards = new MessageCard();
    private final ToolRendererRegistry tools = new ToolRendererRegistry();
    private final WelcomeView welcome = new WelcomeView();
    private final Map<String, Cached> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) { return size() > 512; }
    };
    private long documentRevision;
    private List<StyledLine> lastDocument = List.of();

    private record Cached(long version, int width, long themeRevision, boolean thinkingVisible, List<StyledLine> lines) { }

    /**
     * @param state 当前显示状态
     * @param context 同帧上下文（决定宽度/主题）
     * @param resources 欢迎区真实资源
     * @param welcomeExpanded Ctrl+O 资源帮助
     */
    public List<StyledLine> render(TuiState state, RenderContext context, List<String> resources,
            boolean welcomeExpanded) {
        int width = context.width();
        var out = new ArrayList<StyledLine>();
        out.addAll(welcome.render(applicationVersion(), state.model, state.directory, List.of(),
                false, context, width));
        if (welcomeExpanded) {
            out.add(StyledLine.empty());
            out.addAll(welcome.expanded(state.model, state.directory, resources, context, width));
        }
        out.add(StyledLine.empty());
        for (TranscriptEntry entry : state.transcript.entries()) {
            if (entry.kind() == TranscriptEntry.Kind.TOOL) {
                ToolDisplayState tool = toolFor(state, entry);
                if (tool == null) continue;
                String key = entry.id();
                Cached cached = cache.get(key);
                if (cached != null && cached.version() == tool.version() && cached.width() == width
                        && cached.themeRevision() == context.theme().revision()) {
                    out.addAll(cached.lines()); continue;
                }
                List<StyledLine> lines = tools.render(tool, context, width);
                cache.put(key, new Cached(tool.version(), width, context.theme().revision(), true, lines));
                out.addAll(lines);
                continue;
            }
            String key = entry.id();
            Cached cached = cache.get(key);
            if (cached != null && cached.version() == entry.version() && cached.width() == width
                    && cached.themeRevision() == context.theme().revision()
                    && cached.thinkingVisible() == state.thinkingVisible) {
                out.addAll(cached.lines()); continue;
            }
            String actor = state.displayActor(entry.actor());
            List<StyledLine> lines = cards.render(entry, context, width, state.thinkingVisible, actor);
            cache.put(key, new Cached(entry.version(), width, context.theme().revision(), state.thinkingVisible, lines));
            out.addAll(lines);
            out.add(StyledLine.empty());
        }
        lastDocument = List.copyOf(out);
        documentRevision = state.transcript.revision();
        return lastDocument;
    }

    /** @return 最近一次完整文档；测试/搜索使用。 */
    public List<StyledLine> document() { return lastDocument; }

    /** @return 最近文档对应的转录 revision。 */
    public long documentRevision() { return documentRevision; }

    /** 主题/会话切换时丢弃排版缓存；不删除原文。 */
    public void invalidate() { cache.clear(); cards.invalidate(); lastDocument = List.of(); }

    private static ToolDisplayState toolFor(TuiState state, TranscriptEntry entry) {
        for (ToolDisplayState tool : state.transcript.tools())
            if (("tool:" + tool.key()).equals(entry.id())) return tool;
        return null;
    }

    /** JadeCode 版本来自实际包元数据；缺失时返回空，不伪造成 Pi 版本。 */
    public static String applicationVersion() {
        String version = WelcomeView.class.getPackage().getImplementationVersion();
        if (version != null && !version.isBlank()) return version;
        return "0.1.0";
    }
}
