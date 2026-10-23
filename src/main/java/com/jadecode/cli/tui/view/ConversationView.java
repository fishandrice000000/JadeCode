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
    /** 最多保留两种宽度（全宽/滚动条宽）；条目随转录回收，避免 512 条的循环淘汰。 */
    private final Map<Integer, Layout> layouts = new LinkedHashMap<>(4, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Layout> eldest) { return size() > 2; }
    };
    private long documentRevision;
    private List<StyledLine> lastDocument = List.of();
    private Map<String, Integer> entryStartRows = Map.of();

    /** 条目自身版本和解析版本独立：仅高亮结果到达时不改写业务消息版本。 */
    private record Cached(long version, long parsedRevision, String actor, List<StyledLine> lines) { }
    private record WelcomeKey(String model, String directory, boolean quiet, boolean expanded,
            int height, List<String> resources, long themeRevision, boolean thinkingVisible) { }
    /** 同一宽度下的条目行与整篇文档快照；纯滚动复用 document 的同一对象。 */
    private static final class Layout {
        final Map<String, Cached> entries = new LinkedHashMap<>();
        WelcomeKey key;
        List<StyledLine> document = List.of();
        Map<String, Integer> starts = Map.of();
    }

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
        Layout layout = layouts.computeIfAbsent(width, ignored -> new Layout());
        var entries = state.transcript.entries();
        var toolStates = new java.util.HashMap<String, ToolDisplayState>();
        for (ToolDisplayState tool : state.transcript.tools()) toolStates.put("tool:" + tool.key(), tool);
        var key = new WelcomeKey(state.model, state.directory, state.quietStartup, welcomeExpanded,
                context.height(), List.copyOf(resources), context.theme().revision(), state.thinkingVisible);
        boolean same = key.equals(layout.key) && layout.entries.size() == entries.size();
        if (same) {
            for (TranscriptEntry entry : entries) {
                if (!matches(layout.entries.get(entry.id()), entry, state, toolStates)) { same = false; break; }
            }
        }
        if (!same) rebuild(layout, key, entries, toolStates, state, context, resources, welcomeExpanded);
        // 纯滚动只换可见切片；完整文档与行位置复用，不为每个滚轮事件复制全部历史。
        lastDocument = layout.document;
        entryStartRows = layout.starts;
        documentRevision = state.transcript.revision();
        return lastDocument;
    }

    /** 比较轻量版本而非完整正文；JS 发布新 DTO 仅改变对应条目的 parsedRevision。 */
    private boolean matches(Cached cached, TranscriptEntry entry, TuiState state,
            Map<String, ToolDisplayState> tools) {
        if (cached == null) return false;
        if (entry.kind() == TranscriptEntry.Kind.TOOL) {
            ToolDisplayState tool = tools.get(entry.id());
            return tool != null && cached.version() == tool.version();
        }
        return cached.version() == entry.version() && cached.parsedRevision() == cards.contentRevision(entry.id())
                && cached.actor().equals(state.displayActor(entry.actor()));
    }

    /**
     * 仅在消息/主题/宽度等真正变化时重组文档；未变化的条目复用其行，保留间隔与历史锚点。
     * 每种宽度各持一份快照，内存随显示历史增长；会话失效/删除条目时一并回收。
     */
    private void rebuild(Layout layout, WelcomeKey key, List<TranscriptEntry> entries,
            Map<String, ToolDisplayState> toolStates, TuiState state, RenderContext context,
            List<String> resources, boolean welcomeExpanded) {
        boolean styleChanged = !key.equals(layout.key);
        var out = new ArrayList<StyledLine>();
        out.addAll(welcome.render(applicationVersion(), state.model, state.directory, List.of(),
                state.quietStartup, context, context.width()));
        if (welcomeExpanded) {
            out.add(StyledLine.empty());
            out.addAll(welcome.expanded(state.model, state.directory, resources, context, context.width()));
        }
        out.add(StyledLine.empty());
        var starts = new LinkedHashMap<String, Integer>();
        var retained = new java.util.HashSet<String>();
        for (TranscriptEntry entry : entries) {
            retained.add(entry.id()); starts.put(entry.id(), out.size());
            Cached cached = layout.entries.get(entry.id());
            if (!styleChanged && matches(cached, entry, state, toolStates)) {
                out.addAll(cached.lines()); continue;
            }
            List<StyledLine> lines;
            long version, parsedRevision = cards.contentRevision(entry.id());
            String actor = state.displayActor(entry.actor());
            if (entry.kind() == TranscriptEntry.Kind.TOOL) {
                ToolDisplayState tool = toolStates.get(entry.id());
                if (tool == null) continue;
                version = tool.version(); lines = tools.render(tool, context, context.width());
            } else {
                version = entry.version();
                // 在 render 前取解析 revision：worker 若此时完成，下一帧仍会发现需要更新。
                lines = new ArrayList<>(cards.render(entry, context, context.width(), state.thinkingVisible, actor));
                lines.add(StyledLine.empty());
            }
            lines = List.copyOf(lines);
            layout.entries.put(entry.id(), new Cached(version, parsedRevision, actor, lines));
            out.addAll(lines);
        }
        layout.entries.keySet().retainAll(retained);
        cards.retain(retained);
        layout.document = List.copyOf(out);
        // LinkedHashMap 保持条目顺序，entryIdAtRow 可在越过目标行时立即结束。
        layout.starts = java.util.Collections.unmodifiableMap(starts);
        layout.key = key;
    }

    /** @return 最近一次完整文档；测试/搜索使用。 */
    public List<StyledLine> document() { return lastDocument; }

    /** @return 最近文档对应的转录 revision。 */
    public long documentRevision() { return documentRevision; }

    /** @return 条目 ID 到文档起始行的只读快照；异步重排时用于保持阅读锚点。 */
    public Map<String, Integer> entryStartRows() { return entryStartRows; }

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
    public void invalidate() { layouts.clear(); entryStartRows = Map.of(); cards.invalidate(); lastDocument = List.of(); }

    /**
     * 只丢弃组件级排版缓存，保留已解析的固定引擎 DTO。异步解析结果到达时使用，
     * 不能调用 {@link #invalidate()}，否则会清掉刚解析好的结果并造成重复解析循环。
     */
    public void invalidateLayout() { layouts.clear(); entryStartRows = Map.of(); lastDocument = List.of(); }

    /** JadeCode 版本来自实际包元数据；缺失时返回空，不伪造成 Pi 版本。 */
    public static String applicationVersion() {
        String version = WelcomeView.class.getPackage().getImplementationVersion();
        if (version != null && !version.isBlank()) return version;
        return "0.1.0";
    }
}
