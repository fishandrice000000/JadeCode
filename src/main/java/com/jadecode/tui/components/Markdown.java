package com.jadecode.tui.components;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import com.jadecode.tui.core.Component;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.markdown.MarkdownFragments;
import com.jadecode.tui.markdown.JsTextEngine;
import com.jadecode.tui.markdown.MarkdownLayout;
import com.jadecode.tui.markdown.TextRenderCache;
import com.jadecode.tui.markdown.TextRenderResult;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.text.TextSanitizer;
import com.jadecode.tui.theme.Theme;

/**
 * Markdown 投影：UI 立即显示可读回退，固定引擎在后台补齐精确语义。
 *
 * <p>每个消息身份只有一个在途解析；它运行期间的新前缀只替换最新期望正文，完成后直接解析
 * 最新前缀。这样不排队计算已经看不到的中间版本。后台仅发布 DTO/revision，不写 UI
 * 排版缓存；固定结果与回退使用不同布局键，结果到达不必清空其它消息。</p>
 */
public final class Markdown implements Component {
    private static final int RESULT_CAPACITY = 128;
    private final TextRenderCache cache = new TextRenderCache();
    private final Supplier<JsTextEngine> engine;
    private final Runnable repaint;
    private final Map<String, TextRenderResult> results = new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, TextRenderResult> eldest) {
            return size() > RESULT_CAPACITY;
        }
    };
    /** 消息与转录同生命周期；每项只留当前正文/回退，不保留所有流式前缀。 */
    private final Map<String, ParseState> documents = new LinkedHashMap<>();
    private long epoch;
    private String text = "";
    private boolean thinking;
    private List<StyledLine> rendered = List.of();

    /** 一个稳定消息身份的最新期望值及在途状态；仅 fallback 的建立留在 UI 线程。 */
    private static final class ParseState {
        String source = "", key = "", failedKey = null;
        boolean active;
        long revision;
        TextRenderResult fallback;
        CompletableFuture<TextRenderResult> future;
        final MarkdownFragments fragments = new MarkdownFragments();
        /** 每消息最多四组布局参数；主题变化立即丢弃旧色行。仅 UI 线程读写。 */
        long layoutTheme = Long.MIN_VALUE;
        final Map<LayoutKey, MarkdownLayout> layouts = new LinkedHashMap<>(8, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<LayoutKey, MarkdownLayout> eldest) {
                return size() > 4;
            }
        };
    }

    private record LayoutKey(int width, boolean thinking, boolean fixed) { }

    /** 无引擎时使用 Java 回退，不把它当成固定引擎通过证据。 */
    public Markdown() { this(() -> null, () -> { }); }

    /**
     * @param engine 可晚到的固定引擎供应者；null 表示仅用可读回退
     * @param repaint 固定结果发布后的通知，在 worker 调用，必须线程安全
     */
    public Markdown(Supplier<JsTextEngine> engine, Runnable repaint) {
        this.engine = engine == null ? () -> null : engine;
        this.repaint = repaint == null ? () -> { } : repaint;
    }

    /** 用正文构造无引擎组件；不修改传入字符串。 */
    public Markdown(String text) { this(); this.text = text == null ? "" : text; }
    /** @return 当前组件正文。 */
    public String text() { return text; }
    /** 替换组件正文；下一次 render 才更新期望版本，不在这里执行后台任务。 */
    public void text(String value) { text = value == null ? "" : value; }
    /** 标记思考样式；同正文的思考/普通结果使用不同缓存键。 */
    public void thinking(boolean value) { thinking = value; }
    /** @return 最近一次 render 的不可变显示行。 */
    public List<StyledLine> lines() { return rendered; }

    @Override public List<StyledLine> render(RenderContext context, int width) {
        return render(text, width, context.theme(), thinking);
    }

    /** 单组件入口：连续文本视为同一消息，保留既有调用方。 */
    public List<StyledLine> render(String value, int width, Theme theme, boolean isThinking) {
        return render("component", value, width, theme, isThinking);
    }

    /**
     * 按稳定消息身份排版；不同消息不得合并其解析任务。
     * @param identity 同一组件内唯一的消息 ID
     * @param value 完整最新正文，调用方文本不被修改
     * @param width 终端列宽；至少一列
     * @param theme 同帧主题快照
     * @param isThinking 是否使用思考样式
     * @return 完整当前正文的显示行；不阻塞等待 JS，不执行任何模型/外部 IO
     */
    public List<StyledLine> render(String identity, String value, int width, Theme theme, boolean isThinking) {
        String source = value == null ? "" : value;
        String sourceKey = (isThinking ? "thinking:" : "normal:") + source;
        ParseState state;
        TextRenderResult fixed;
        synchronized (this) {
            state = documents.computeIfAbsent(identity, ignored -> new ParseState());
            if (!state.key.equals(sourceKey)) {
                state.source = source; state.key = sourceKey;
                state.fallback = null; state.failedKey = null;
            }
            fixed = results.get(sourceKey);
        }
        // 即使命中回退布局，也要尝试接上晚到的引擎；同消息 active 可防止重复提交。
        if (fixed == null) request(identity, state);
        var key = new TextRenderCache.Key(source, Math.max(1, width), theme.revision(),
                (isThinking ? "thinking:" : "normal:") + (fixed == null ? "fallback" : "fixed"));
        List<StyledLine> cached = cache.get(key);
        if (cached != null) { rendered = cached; return cached; }
        Style base = isThinking ? theme.style("thinkingText") : theme.style("text");
        TextRenderResult parsed = fixed;
        if (parsed == null) {
            // 回退 DTO 只在 UI 线程建立，同一源文跨宽度/主题复用；不重复执行整篇 Java 解析。
            if (state.fallback == null)
                state.fallback = state.fragments.parseJava(TextSanitizer.display(source));
            parsed = state.fallback;
        }
        if (state.layoutTheme != theme.revision()) {
            state.layouts.clear(); state.layoutTheme = theme.revision();
        }
        var layoutKey = new LayoutKey(Math.max(1, width), isThinking, fixed != null);
        MarkdownLayout layout = state.layouts.computeIfAbsent(layoutKey, ignored -> new MarkdownLayout(theme));
        // 首次返回与缓存命中返回同一不可变列表，避免调用者持有可变布局工作副本。
        rendered = layout.layoutCached(parsed, Math.max(1, width), base, isThinking);
        // 两类键分开：缓存回退不会遮住后来到达的固定结果。
        cache.put(key, rendered);
        return rendered;
    }

    /** @return 指定消息固定结果的修订号；供上层只失效对应消息。 */
    public synchronized long contentRevision(String identity) {
        ParseState state = documents.get(identity);
        return state == null ? 0 : state.revision;
    }

    /**
     * 启动该消息当前版本；在途时只由 render 更新期望正文，不创建第二个任务。
     * 完成回调仅接受仍存活的同一状态；旧版本完成后只启动最新版本，不发全局重排。
     */
    private void request(String identity, ParseState state) {
        JsTextEngine current = engine.get();
        if (current == null) return;
        String sourceKey, source;
        long ticket;
        synchronized (this) {
            if (documents.get(identity) != state || state.active || state.key.equals(state.failedKey)
                    || results.containsKey(state.key)) return;
            sourceKey = state.key; source = state.source; ticket = epoch;
            state.active = true;
            state.future = current.lex(source, 0);
        }
        state.future.whenComplete((result, failure) -> {
            boolean publish = false, next;
            synchronized (this) {
                if (epoch != ticket || documents.get(identity) != state) return;
                state.active = false;
                next = !sourceKey.equals(state.key);
                if (!next && failure == null && result != null) {
                    results.put(sourceKey, result); state.revision++; publish = true;
                } else if (!next && failure != null) {
                    // 相同失败正文不在每次滚动时重试；新正文或显式 invalidate 后可再请求。
                    state.failedKey = sourceKey;
                    Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                            ? failure.getCause() : failure;
                    System.getLogger("jadecode.tui.markdown").log(System.Logger.Level.DEBUG,
                            "text engine lex failed: " + cause.getMessage());
                }
            }
            if (next) request(identity, state);
            if (publish) repaint.run();
        });
    }

    /** @return 排版缓存诊断；生产仅由 UI 线程访问。 */
    public TextRenderCache cache() { return cache; }
    /** @return 已接受的固定引擎结果数；过时前缀不计入。 */
    public synchronized int parsedCount() { return results.size(); }

    /** 删除已不在转录中的消息并取消未执行解析；不会取消其它消息的工作。 */
    public synchronized void retain(Set<String> identities) {
        var removed = new java.util.ArrayList<CompletableFuture<TextRenderResult>>();
        documents.entrySet().removeIf(entry -> {
            if (identities.contains(entry.getKey())) return false;
            if (entry.getValue().future != null) removed.add(entry.getValue().future);
            return true;
        });
        // 先移除身份再取消，取消回调就不能把已经删除的消息重新加入任务队列。
        for (var future : removed) future.cancel(false);
    }

    /** 会话/显式失效：先推进代次再取消 future，避免取消回调重新提交旧任务。 */
    public void invalidate() {
        cache.clear(); rendered = List.of();
        synchronized (this) {
            epoch++;
            for (ParseState state : documents.values()) if (state.future != null) state.future.cancel(false);
            documents.clear(); results.clear();
        }
    }
}
