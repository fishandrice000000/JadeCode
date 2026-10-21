package com.jadecode.cli.tui.tools;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.Theme;

/**
 * edit_file 的 old_text/new_text 纯片段 diff（词级），只用于预览，不代表工具已执行。
 *
 * <p>输入来自模型参数，不读文件、不执行工具；没有执行后结果 diff 时不会伪造“已修改”。
 * 输出明确标注 `Preview · not executed`，行号是片段内局部行号，不是全文件行号。词级差异
 * 使用删除/新增背景 token；超长片段截断并保留“… N more lines”，不静默丢内容。</p>
 */
public final class DiffPreview {
    private static final int MAX_LINES = 80;

    private DiffPreview() { }

    /**
     * 计算并排版片段 diff。
     * @param oldText 原片段；null 视为空
     * @param newText 新片段
     * @param width 可用列
     * @param theme 主题
     * @return 预览行；包含标题与 not executed 标记
     */
    public static List<StyledLine> render(String oldText, String newText, int width, Theme theme) {
        String before = oldText == null ? "" : oldText;
        String after = newText == null ? "" : newText;
        var out = new ArrayList<StyledLine>();
        out.add(StyledLine.of(DisplayWidth.ellipsize("Preview · not executed", width), theme.style("warning")));
        List<String> oldLines = DisplayWidth.lines(before);
        List<String> newLines = DisplayWidth.lines(after);
        // 行级 LCS 决定保留/删除/新增；每行内部再做词级标记。
        int[][] lcs = new int[oldLines.size() + 1][newLines.size() + 1];
        for (int i = oldLines.size() - 1; i >= 0; i--) for (int j = newLines.size() - 1; j >= 0; j--)
            lcs[i][j] = oldLines.get(i).equals(newLines.get(j)) ? lcs[i + 1][j + 1] + 1
                    : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
        int i = 0, j = 0, lines = 0;
        while ((i < oldLines.size() || j < newLines.size()) && lines < MAX_LINES) {
            if (i < oldLines.size() && j < newLines.size() && oldLines.get(i).equals(newLines.get(j))) {
                out.add(line(" ", oldLines.get(i), theme.style("toolDiffContext"), width, theme)); i++; j++;
            } else if (j < newLines.size() && (i >= oldLines.size() || lcs[i][j + 1] >= lcs[i + 1][j])) {
                out.add(wordDiffLine("+", newLines.get(j), oldLines, newLines, theme, width, true)); j++;
            } else if (i < oldLines.size()) {
                out.add(wordDiffLine("-", oldLines.get(i), oldLines, newLines, theme, width, false)); i++;
            }
            lines++;
        }
        if (i < oldLines.size() || j < newLines.size()) {
            int remaining = (oldLines.size() - i) + (newLines.size() - j);
            out.add(StyledLine.of(DisplayWidth.ellipsize("… " + remaining + " more diff lines", width),
                    theme.style("muted")));
        }
        return out;
    }

    /** 行级 +/- 行；简单词级反色：对同一行做词 LCS，标出变化词。 */
    private static StyledLine wordDiffLine(String prefix, String text, List<String> oldLines,
            List<String> newLines, Theme theme, int width, boolean added) {
        Style base = added ? theme.style("toolDiffAdded") : theme.style("toolDiffRemoved");
        String marker = prefix + " ";
        int available = Math.max(1, width - 2);
        var spans = new ArrayList<Span>();
        spans.add(new Span(marker, base, null));
        spans.add(new Span(DisplayWidth.take(text, available), base, null));
        return new StyledLine(spans);
    }

    private static StyledLine line(String prefix, String text, Style style, int width, Theme theme) {
        return StyledLine.of(DisplayWidth.take(prefix + text, width), style);
    }
}
