package com.jadecode.tui.markdown;

import java.util.List;

/**
 * Markdown 解析/高亮/图形纯计算结果的不可变 DTO 边界。
 *
 * <p>Java 布局层只读取这些 DTO，不接触 GraalJS Value/Context；结果可安全跨线程复制。
 * 未来由 {@code JsTextEngine} 按固定 marked 版本产生同样结构；当前本地布局使用同一 DTO，
 * 以便替换解析器时无需改产品组件。undefined/失败规范为 null 或空列表，不伪造成功。
 *
 * @param tokens 顶层块 token
 * @param sourceVersion 源文版本（用于缓存失效）
 * @param warnings 非致命问题（如 Mermaid 过宽退回源码）；原文仍保留
 */
public record TextRenderResult(List<Token> tokens, long sourceVersion, List<String> warnings) {
    public TextRenderResult {
        tokens = tokens == null ? List.of() : List.copyOf(tokens);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** 空结果；解析失败时保留原文由调用方回退。 */
    public static TextRenderResult empty(long version) { return new TextRenderResult(List.of(), version, List.of()); }

    /** 块/行内 token。字段均为纯文本，不含可执行对象。 */
    public sealed interface Token permits Document, Heading, Paragraph, Text, Space, Code, Strong, Emphasis,
            Strikethrough, Link, ListBlock, ListItem, Blockquote, Rule, Table, Latex, Html, Break {
        /** 子节点；叶子返回空列表。 */
        default List<Token> children() { return List.of(); }
    }

    /** 整篇文档。 */
    public record Document(List<Token> items) implements Token {
        public Document { items = List.copyOf(items); }
        @Override public List<Token> children() { return items; }
    }

    /** 标题；depth 1-6。 */
    public record Heading(int depth, List<Token> items) implements Token {
        public Heading { items = List.copyOf(items); }
        @Override public List<Token> children() { return items; }
    }

    /** 段落。 */
    public record Paragraph(List<Token> items) implements Token {
        public Paragraph { items = List.copyOf(items); }
        @Override public List<Token> children() { return items; }
    }

    /** 普通文字（已净化，不含控制序列）。 */
    public record Text(String value) implements Token { }

    /** 单个空格/换行分隔，用于保持行内词边界。 */
    public record Space() implements Token { }

    /** 一个高亮/图形作用域；cls 为 highlight.js 的 `hljs-*` 或 grok-mermaid 的语义类。 */
    public record Scope(String text, String cls) { }

    /**
     * 行内或围栏代码；block=true 时 text 保留原始换行/空白。
     * highlight 是按行组织的语义作用域；为空时按纯文本显示。
     */
    public record Code(String text, String language, boolean block, List<List<Scope>> highlight) implements Token {
        public Code {
            text = text == null ? "" : text;
            language = language == null ? "" : language;
            highlight = highlight == null ? List.of() : List.copyOf(highlight);
        }
        /** 兼容没有高亮作用域的调用方。 */
        public Code(String text, String language, boolean block) { this(text, language, block, List.of()); }
    }

    public record Strong(List<Token> items) implements Token {
        public Strong { items = List.copyOf(items); }
        @Override public List<Token> children() { return items; }
    }

    public record Emphasis(List<Token> items) implements Token {
        public Emphasis { items = List.copyOf(items); }
        @Override public List<Token> children() { return items; }
    }

    public record Strikethrough(List<Token> items) implements Token {
        public Strikethrough { items = List.copyOf(items); }
        @Override public List<Token> children() { return items; }
    }

    /** 链接；href 已通过 LinkTarget 校验，非法时为 null（只显示文字）。 */
    public record Link(String href, List<Token> items) implements Token {
        public Link { items = List.copyOf(items); }
        @Override public List<Token> children() { return items; }
    }

    /** 列表块；items 的 depth 为 0 基。 */
    public record ListBlock(boolean ordered, int start, List<ListItem> items) implements Token {
        public ListBlock { items = List.copyOf(items); }
        @Override public List<Token> children() { return List.copyOf(items); }
    }

    /** 列表项；depth=0 为顶层，task/checked 来自 `- [ ]`/`- [x]`。 */
    public record ListItem(int depth, boolean ordered, int index, boolean task, boolean checked, List<Token> items)
            implements Token {
        public ListItem { items = List.copyOf(items); }
        @Override public List<Token> children() { return items; }
    }

    /** 引用块。 */
    public record Blockquote(List<Token> items) implements Token {
        public Blockquote { items = List.copyOf(items); }
        @Override public List<Token> children() { return items; }
    }

    /** 分隔线。 */
    public record Rule() implements Token { }

    /** 表格；单元格为行内 token。 */
    public record Table(List<List<Token>> header, List<List<List<Token>>> rows, List<String> align) implements Token {
        public Table {
            header = List.copyOf(header); rows = List.copyOf(rows); align = List.copyOf(align);
        }
        @Override public List<Token> children() { return List.of(); }
    }

    /** 数学公式；display 表示块级。未支持时布局回退原始文字。 */
    public record Latex(String text, boolean display) implements Token { }

    /** 原始 HTML；按纯文字显示，不执行/渲染。 */
    public record Html(String raw) implements Token { }

    /** 显式换行（两个以上空格或反斜杠行尾）。 */
    public record Break() implements Token { }
}
