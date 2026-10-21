package com.jadecode.tui.components;

import java.util.List;
import com.jadecode.tui.core.Component;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.markdown.JavaMarkdownParser;
import com.jadecode.tui.markdown.MarkdownLayout;
import com.jadecode.tui.markdown.TextRenderCache;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.Theme;

/**
 * Markdown 显示组件：把原文排版成富文本，不执行 HTML/图片/模型请求。
 *
 * <p>当前使用 {@link JavaMarkdownParser} 产生与固定 JS 引擎相同结构的 DTO，再由
 * {@link MarkdownLayout} 统一排版；缓存按源文/宽度/主题 revision/基础样式复用。thinking
 * 不运行 Mermaid（当前本地解析器也不生成图），未知语法原样显示。后续接入固定 GraalJS
 * bundle 时只替换解析来源，布局与组件契约不变。</p>
 */
public final class Markdown implements Component {
    private final TextRenderCache cache = new TextRenderCache();
    private String text = "";
    private boolean thinking;
    private List<StyledLine> rendered = List.of();
    private int lastWidth = -1;
    private long lastThemeRevision = -1;
    private boolean expanded = true;

    /** 空组件。 */
    public Markdown() { }

    /** 用正文构造。 */
    public Markdown(String text) { this.text = text == null ? "" : text; }

    /** @return 当前原文。 */
    public String text() { return text; }

    /** 替换原文；清空缓存由 key 版本自然失效。 */
    public void text(String value) { text = value == null ? "" : value; }

    /** 标记 thinking：布局使用斜体基础样式，不生成 Mermaid。 */
    public void thinking(boolean value) { thinking = value; }

    /** 折叠/展开；折叠在组件层由宿主处理。 */
    public void expanded(boolean value) { expanded = value; }

    /** @return 是否展开。 */
    public boolean expanded() { return expanded; }

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
        var key = new TextRenderCache.Key(source, columns, theme.revision(),
                isThinking ? "thinking" : "normal");
        var cached = cache.get(key);
        if (cached != null) { rendered = cached; lastWidth = columns; lastThemeRevision = theme.revision(); return cached; }
        Style base = isThinking ? theme.style("thinkingText") : Style.PLAIN;
        List<StyledLine> result = new MarkdownLayout(theme).render(source, columns, base);
        cache.put(key, result);
        rendered = result; lastWidth = columns; lastThemeRevision = theme.revision();
        return result;
    }

    /** @return 缓存诊断。 */
    public TextRenderCache cache() { return cache; }

    /** 清空缓存；主题结构/会话切换时调用。 */
    public void invalidate() { cache.clear(); rendered = List.of(); }
}
