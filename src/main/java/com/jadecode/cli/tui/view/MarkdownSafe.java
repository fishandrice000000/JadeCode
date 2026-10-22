package com.jadecode.cli.tui.view;

/**
 * 命令帮助 Markdown 的安全拼装工具：外部名称/标题只作为 inline code，不当作 Markdown 或 ANSI。
 *
 * <p>换行、控制字符先压成空格；内容含反引号时选择更长的 code fence，避免闭合后变成任意
 * Markdown。这里只处理文本，不执行 HTML/链接，也不改变真实值。</p>
 */
final class MarkdownSafe {
    private MarkdownSafe() { }

    /** @return 适合放进 Markdown inline code 的单行文本；保留真实字符但去掉控制换行。 */
    static String code(String value) {
        String text = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').replaceAll("[\\p{Cntrl}]", " ");
        String fence = "`";
        while (text.contains(fence)) fence += "`";
        // CommonMark code span 两端加一个空格可安全包住以反引号开头/结尾的内容。
        return fence + " " + text + " " + fence;
    }
}
