package com.jadecode.tui.text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jline.utils.WCWidth;

/**
 * 按终端列而非 Java char 数处理文字。编辑与排版共用它，避免中文或 emoji 被切成两半。
 * 使用 JDK 的扩展字素分组；ZWJ、旗帜和带组合符的字符保持为一个编辑单位。
 * 输入中的终端控制序列只作为不可信文字处理，绝不透传到终端。
 */
public final class DisplayWidth {
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    private static final Pattern ESCAPES = Pattern.compile("\\x1B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\x07\\x1B]*(?:\\x07|\\x1B\\\\)|[@-_])");
    private DisplayWidth() { }

    /** 返回可安全显示的正文；保留换行，展开 Tab，不保留光标、剪贴板等控制指令。 */
    public static String clean(String value) {
        if (value == null) return "";
        return ESCAPES.matcher(value).replaceAll("").replace("\r\n", "\n").replace("\r", "")
                .replace("\t", "    ").replaceAll("[\\p{Cntrl}&&[^\\n]]", "")
                .replaceAll("[\\x{80}-\\x{9F}]", "");
    }

    /** 分组后不修改原文字；索引是 UTF-16 偏移，调用方不能将它当终端列。 */
    public static List<String> clusters(String text) {
        var result = new ArrayList<String>();
        var matcher = GRAPHEME.matcher(text);
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    /** 一个字素占多少列。组合修饰符不另占列，常见 emoji 序列按两列处理。 */
    public static int clusterWidth(String cluster) {
        boolean emoji = cluster.codePoints().anyMatch(c -> c == 0xFE0F || c == 0x200D
                || c >= 0x1F1E6 && c <= 0x1F1FF || c >= 0x1F300 && c <= 0x1FAFF);
        return emoji ? 2 : cluster.codePoints().map(c -> Math.max(0, WCWidth.wcwidth(c))).sum();
    }

    /** @return 整行的显示列数；调用者应先把换行拆成多行。 */
    public static int width(String text) { return clusters(text).stream().mapToInt(DisplayWidth::clusterWidth).sum(); }

    /** 按列截取一行，不截断字素；不会添加省略号，便于布局精确分配空间。 */
    public static String take(String text, int columns) {
        var out = new StringBuilder();
        int used = 0;
        for (String cluster : clusters(text)) {
            int next = clusterWidth(cluster);
            if (used + next > columns) break;
            out.append(cluster); used += next;
        }
        return out.toString();
    }

    /**
     * 显示摘要、标题或提示时使用的单行省略：放不下时留一列显示省略号。
     * 与 take 的精确裁列契约分开，避免代码块、表格等布局被自动添加的省略号挤坏。
     * 先移除控制指令并合并换行；中文和 emoji 按完整字素截取，不修改原文字。
     */
    public static String ellipsize(String text, int columns) {
        if (columns <= 0) return "";
        String line = clean(text).replace('\n', ' ');
        return width(line) <= columns ? line : take(line, columns - 1) + "…";
    }
}
