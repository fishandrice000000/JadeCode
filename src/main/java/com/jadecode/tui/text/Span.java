package com.jadecode.tui.text;

/** 已清理文字及样式名称；不保存 ANSI，文字宽度不会把色码算进去。 */
public record Span(String text, String style) { }
