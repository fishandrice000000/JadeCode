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
    private final MessageCard cards;
    private final ToolRendererRegistry tools = new ToolRendererRegistry();
    private final WelcomeView welcome = new WelcomeView();
    private final Map<String, Cached> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) { return size() > 512; }
    };
    private long documentRevision;
    private List<StyledLine> lastDocument = List.of();
    /** 每个条目在完整文档中的起始行；历史阅读锚点与缓存一致性使用。 */
    private final Map<String, Integer> entryStartRows = new LinkedHashMap<>();

    private record Cached(long version, int width, long themeRevision, boolean thinkingVisible, List<StyledLine> lines) { }

    /** 无固定引擎的组件（测试/离线）。 */
    public ConversationView() { this(() -> null, () -> { }); }

    /**
     * @param engine 固定 JS 文本引擎供应者；null 时消息卡使用 Java 回退
     * @param repaint 引擎结果到达后的重绘请求
     */
    public ConversationView(java.util.function.Supplier<com.jadecode.tui.markdown.JsTextEngine> engine, Runnable repaint) {
        this.cards = new MessageCard(engine, repaint);
    }

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
                state.quietStartup, context, width));
        if (welcomeExpanded) {
            out.add(StyledLine.empty());
            out.addAll(welcome.expanded(state.model, state.directory, resources, context, width));
        }
        out.add(StyledLine.empty());
        for (TranscriptEntry entry : state.transcript.entries()) {
            entryStartRows.put(entry.id(), out.size());
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
            List<StyledLine> lines = new java.util.ArrayList<>(
                    cards.render(entry, context, width, state.thinkingVisible, actor));
            // 条目间空行必须和消息行一起缓存；旧实现只在首次渲染追加空行，缓存命中时
            // 直接 continue，导致同一状态冷/热路径相差一行、历史阅读时上下跳变。
            lines.add(StyledLine.empty());
            cache.put(key, new Cached(entry.version(), width, context.theme().revision(), state.thinkingVisible, lines));
            out.addAll(lines);
        }
        lastDocument = List.copyOf(out);
        documentRevision = state.transcript.revision();
        return lastDocument;
    }

    /** @return 最近一次完整文档；测试/搜索使用。 */
    public List<StyledLine> document() { return lastDocument; }

    /** @return 最近文档对应的转录 revision。 */
    public long documentRevision() { return documentRevision; }

    /** @return 条目 ID 到文档起始行的只读快照；异步重排时用于保持阅读锚点。 */
    public Map<String, Integer> entryStartRows() { return Map.copyOf(entryStartRows); }

    /**
     * @return 覆盖指定文档行的最晚开始条目 ID；空文档/越界返回空串。
     * 历史阅读锚点用它定位“当前顶部属于哪个条目”。
     */
    public String entryIdAtRow(int row) {
        String found = "";
        for (var entry : entryStartRows.entrySet()) {
            if (entry.getValue() <= row) found = entry.getKey();
            else break;
        }
        return found;
    }

    /** 主题/会话切换时丢弃排版缓存；不删除原文。 */
    public void invalidate() { cache.clear(); entryStartRows.clear(); cards.invalidate(); lastDocument = List.of(); }

    /**
     * 只丢弃组件级排版缓存，保留已解析的固定引擎 DTO。异步解析结果到达时使用，
     * 不能调用 {@link #invalidate()}，否则会清掉刚解析好的结果并造成重复解析循环。
     */
    public void invalidateLayout() { cache.clear(); entryStartRows.clear(); lastDocument = List.of(); }

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
