package com.jadecode.tui.components;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import com.jadecode.tui.core.Component;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.markdown.JavaMarkdownParser;
import com.jadecode.tui.markdown.JsTextEngine;
import com.jadecode.tui.markdown.MarkdownLayout;
import com.jadecode.tui.markdown.TextRenderCache;
import com.jadecode.tui.markdown.TextRenderResult;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.Theme;

/**
 * Markdown 显示组件：固定 JS 纯计算引擎优先，未就绪/失败时保留可读回退。
 *
 * <p>UI 线程从不等待 Graal：首次遇到源文时立即返回 Java 解析器的行，同时在单 JS 工作线程
 * 请求固定 marked/highlight/mermaid 结果；完成后缓存 DTO 并触发一次重排。结果按源文缓存，
 * 排版按宽度/主题 revision 缓存。引擎失败保留原文回退并报告，不把 Java 解析器冒充成
 * C04 的固定引擎。组件不执行 HTML/图片/模型请求；thinking 不运行 Mermaid。</p>
 */
public final class Markdown implements Component {
    /** 已解析 DTO 的有界缓存；键为完整源文，避免流式前缀无限保留。 */
    private static final int RESULT_CAPACITY = 128;

    private final TextRenderCache cache = new TextRenderCache();
    private final Supplier<JsTextEngine> engine;
    private final Runnable repaint;
    private final Map<String, TextRenderResult> results = new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, TextRenderResult> eldest) {
            return size() > RESULT_CAPACITY;
        }
    };
    private final Set<String> pending = new java.util.HashSet<>();
    private String text = "";
    private boolean thinking;
    private List<StyledLine> rendered = List.of();

    /** 无引擎组件（测试/离线）：始终使用 Java 回退。 */
    public Markdown() { this(() -> null, () -> { }); }

    /**
     * @param engine 固定 JS 引擎供应者；返回 null 时使用回退
     * @param repaint 引擎结果到达后的重绘请求；在 worker 线程调用，必须线程安全
     */
    public Markdown(Supplier<JsTextEngine> engine, Runnable repaint) {
        this.engine = engine == null ? () -> null : engine;
        this.repaint = repaint == null ? () -> { } : repaint;
    }

    /** 用正文构造（无引擎）。 */
    public Markdown(String text) { this(); this.text = text == null ? "" : text; }

    /** @return 当前原文。 */
    public String text() { return text; }

    /** 替换原文；结果缓存按源文键自然失效。 */
    public void text(String value) { text = value == null ? "" : value; }

    /** 标记 thinking：布局使用斜体基础样式，不生成 Mermaid。 */
    public void thinking(boolean value) { thinking = value; }

    /** @return 最近一次排版结果（与 render 同源）。 */
    public List<StyledLine> lines() { return rendered; }

    @Override public List<StyledLine> render(RenderContext context, int width) {
        return render(text, width, context.theme(), thinking);
    }

    /**
     * 排版给定正文。
     * @param value 原始 Markdown
     * @param width 可用列数
     * @param theme 当前主题
     * @param isThinking thinking 使用斜体基础样式并跳过图形
     */
    public List<StyledLine> render(String value, int width, Theme theme, boolean isThinking) {
        String source = value == null ? "" : value;
        int columns = Math.max(1, width);
        var key = new TextRenderCache.Key(source, columns, theme.revision(), isThinking ? "thinking" : "normal");
        var cached = cache.get(key);
        if (cached != null) { rendered = cached; return cached; }
        Style base = isThinking ? theme.style("thinkingText") : Style.PLAIN;
        TextRenderResult result = resultFor(source, isThinking);
        List<StyledLine> lines;
        if (result != null) {
            lines = new MarkdownLayout(theme).layout(result, columns, base, isThinking);
            cache.put(key, lines);
        } else {
            // 引擎未就绪/失败：保留可读回退并后台请求；回退不能写进缓存，
            // 否则结果到达后同一宽度会一直命中旧回退、看不到固定引擎排版。
            request(source, isThinking);
            lines = new MarkdownLayout(theme).render(source, columns, base);
        }
        rendered = lines;
        return lines;
    }

    /** @return 已缓存的固定引擎 DTO；thinking 与普通消息分开缓存。 */
    private synchronized TextRenderResult resultFor(String source, boolean isThinking) {
        return results.get((isThinking ? "thinking:" : "normal:") + source);
    }

    /** 后台请求一次解析；同一源文去重，完成后重绘。 */
    private void request(String source, boolean isThinking) {
        JsTextEngine current = engine.get();
        if (current == null) return;
        String key = (isThinking ? "thinking:" : "normal:") + source;
        synchronized (this) {
            if (results.containsKey(key) || !pending.add(key)) return;
        }
        current.lex(source, 0).whenComplete((result, failure) -> {
            synchronized (this) { pending.remove(key); }
            if (failure == null && result != null) {
                synchronized (this) { results.put(key, result); }
            } else if (failure != null) {
                // 引擎失败保留回退；由启动警告/测试报告真实失败，不静默换成另一种解析器。
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                        ? failure.getCause() : failure;
                System.getLogger("jadecode.tui.markdown").log(System.Logger.Level.DEBUG,
                        "text engine lex failed: " + cause.getMessage());
            }
            repaint.run();
        });
    }

    /** @return 排版缓存诊断。 */
    public TextRenderCache cache() { return cache; }

    /** @return 已缓存的固定引擎 DTO 数量，测试/诊断。 */
    public synchronized int parsedCount() { return results.size(); }

    /** 清空缓存；主题结构/会话切换时调用。 */
    public void invalidate() {
        cache.clear(); rendered = List.of();
        synchronized (this) { results.clear(); pending.clear(); }
    }
}
