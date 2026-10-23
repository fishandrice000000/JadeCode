package com.jadecode.tui.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.ibm.icu.text.BreakIterator;

/**
 * Unicode 17 字素分段与终端列宽的唯一计算入口。
 *
 * <p>编辑器移动/删除、Markdown 折行、光标定位、命中测试和复制范围都调用这里，避免各模块
 * 使用不同的 char 下标或宽度近似。分段使用 ICU 78.3（锁定 Unicode 17）；列宽按固定 Pi
 * 算法：ASCII 1 列，组合/控制簇 0 列，终端会分配格子的 spacing mark 逐个计列，RGI emoji 2 列，
 * 其它字符查固定 1.6.0 East Asian Width 表。ICU 的 `Intl.Segmenter` 在 Guest JS 中与 Java
 * 存在已知差异，因此 JS 侧必须通过受限 ICU 桥使用同一分段结果，不能各算一套。</p>
 *
 * <p>索引约定：所有输入/返回的偏移都是 UTF-16 下标（与 Java String、编辑器光标一致）；
 * “列”是终端显示宽度；“字素簇”是完整用户感知字符。三者不能混用。</p>
 */
public final class DisplayWidth {
    /** 非 ASCII 宽度缓存；流式内容常重复查询同一簇，限制大小避免无限增长。 */
    private static final int CACHE_SIZE = 512;
    private static final Map<String, Integer> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                    return size() > CACHE_SIZE;
                }
            });

    private DisplayWidth() { }

    /** ICU 迭代器按线程复用；每次结束清掉文本，避免工作线程长期引用整篇回复。 */
    private static final ThreadLocal<BreakIterator> ITERATORS = ThreadLocal.withInitial(
            () -> BreakIterator.getCharacterInstance(java.util.Locale.ROOT));
    private static final String[] ASCII = new String[128];
    static {
        for (int i = 0; i < ASCII.length; i++) ASCII[i] = String.valueOf((char) i);
    }

    /**
     * 按扩展字素簇分段。空串返回空列表；换行符是独立簇。
     * ICU BreakIterator 不是线程安全的，因此每个线程独享实例，返回前释放输入引用。
     */
    public static List<String> clusters(String text) {
        if (text == null || text.isEmpty()) return List.of();
        BreakIterator iterator = ITERATORS.get();
        iterator.setText(text);
        try {
            var result = new ArrayList<String>();
            int start = iterator.first();
            for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next())
                result.add(end == start + 1 && text.charAt(start) < 128
                        ? ASCII[text.charAt(start)] : text.substring(start, end));
            return result;
        } finally { iterator.setText(""); }
    }

    /** @return 一个完整字素占用的终端列数；不能传入半个字素。 */
    public static int clusterWidth(String cluster) {
        if (cluster == null || cluster.isEmpty()) return 0;
        if (cluster.length() == 1) {
            char c = cluster.charAt(0);
            if (c >= 0x20 && c <= 0x7e) return 1;
            if (c == '\t') return 3; // Pi Markdown 的 Tab 宽度；编辑器在插入时展开为 4 空格。
            if (c == '\n' || c == '\r') return 0;
        }
        Integer cached = CACHE.get(cluster);
        if (cached != null) return cached;
        int width = computeClusterWidth(cluster);
        CACHE.put(cluster, width);
        return width;
    }

    private static int computeClusterWidth(String cluster) {
        int first = cluster.codePointAt(0);
        // 孤立的 spacing mark（没有基字符）在部分终端仍占格；Pi 按码点数量计列。
        if (isTerminalSpacingMark(first)) {
            boolean allSpacing = cluster.codePoints().allMatch(DisplayWidth::isTerminalSpacingMark);
            if (allSpacing) return cluster.codePointCount(0, cluster.length());
        }
        if (isZeroWidthCluster(cluster)) return 0;
        if (couldBeEmoji(cluster) && isRgiEmojiLike(cluster)) return 2;

        String base = stripLeadingNonPrinting(cluster);
        if (base.isEmpty()) return 0;
        int basePoint = base.codePointAt(0);
        // 独立区域指示符在流式输出时常被终端按 emoji 渲染，保守按 2 列避免累积漂移。
        if (basePoint >= 0x1f1e6 && basePoint <= 0x1f1ff) return 2;
        int width = TerminalWidthTable.eastAsianWidth(basePoint);

        boolean followsMark = false;
        int offset = Character.charCount(basePoint);
        while (offset < base.length()) {
            int c = base.codePointAt(offset);
            offset += Character.charCount(c);
            if (isTerminalSpacingMark(c)) { width += 1; followsMark = false; }
            else if (isMark(c)) { followsMark = true; }
            else if (!isNonPrinting(c)) {
                if (followsMark || (c >= 0xff00 && c <= 0xffef)) width += TerminalWidthTable.eastAsianWidth(c);
                else if (c == 0x0e33 || c == 0x0eb3) width += 1;
                followsMark = false;
            }
        }
        return width;
    }

    /**
     * @return 整段文字的显示列数；换行为 0 列。多行文本应先用 {@link #lines(String)} 拆分，
     *         否则这里只会把换行当零宽字符相加。
     */
    public static int width(String text) {
        if (text == null || text.isEmpty()) return 0;
        // 纯 ASCII 普通文字直接计列；一旦遇到非 ASCII/特殊控制字符，整段交回 ICU，
        // 不在混合文本中错误拆开 e+组合音标、ZWJ 或 CRLF 字素。
        int asciiWidth = 0;
        boolean ascii = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x20 && c <= 0x7e) asciiWidth++;
            else if (c == '\t') asciiWidth += 3;
            else if (c != '\r' && c != '\n') { ascii = false; break; }
        }
        if (ascii) return asciiWidth;
        BreakIterator iterator = ITERATORS.get();
        iterator.setText(text);
        try {
            int total = 0, start = iterator.first();
            for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next())
                total += clusterWidth(end == start + 1 && text.charAt(start) < 128
                        ? ASCII[text.charAt(start)] : text.substring(start, end));
            return total;
        } finally { iterator.setText(""); }
    }

    /**
     * 按列截取一行，不添加省略号，不拆字素。宽字素放不下时停止（宁可少一列）。
     * @param columns 允许的最大列数；<=0 返回空串
     */
    public static String take(String text, int columns) {
        if (text == null || columns <= 0) return "";
        var out = new StringBuilder();
        int used = 0;
        for (String cluster : clusters(text)) {
            int cells = clusterWidth(cluster);
            if (used + cells > columns) break;
            out.append(cluster); used += cells;
        }
        return out.toString();
    }

    /** 从右侧按列截取，保留尾部完整字素；用于 bash 结果“最后 N 行”的列裁剪。 */
    public static String takeRight(String text, int columns) {
        if (text == null || columns <= 0) return "";
        var clusters = clusters(text);
        var out = new java.util.ArrayDeque<String>();
        int used = 0;
        for (int i = clusters.size() - 1; i >= 0; i--) {
            String cluster = clusters.get(i);
            int cells = clusterWidth(cluster);
            if (used + cells > columns) break;
            out.addFirst(cluster); used += cells;
        }
        return String.join("", out);
    }

    /**
     * 单行摘要省略：放不下时保留 columns-1 列并追加一个省略号（宽度按 1 列）。
     * 已经符合宽度时原样返回；不清理控制序列，调用方需先使用 {@link TextSanitizer#singleLine}。
     */
    public static String ellipsize(String text, int columns) {
        if (text == null || columns <= 0) return "";
        if (width(text) <= columns) return text;
        return take(text, columns - 1) + "…";
    }

    /** 按 `\n` 拆分，保留空行；不执行 Unicode 折行（那属于布局）。 */
    public static List<String> lines(String text) {
        var result = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') { result.add(text.substring(start, i)); start = i + 1; }
        }
        result.add(text.substring(start));
        return result;
    }

    /**
     * 找到终端列 {@code column} 对应的 UTF-16 偏移（光标/鼠标命中）。
     * 列落在宽字素中间时返回该字素的起始偏移；列超出行宽时返回文本长度。
     */
    public static int offsetAtColumn(String text, int column) {
        if (text == null) return 0;
        int used = 0, offset = 0;
        for (String cluster : clusters(text)) {
            int cells = clusterWidth(cluster);
            if (column < used + cells) return offset;
            used += cells; offset += cluster.length();
        }
        return text.length();
    }

    /** @return 给定 UTF-16 偏移所在显示列的起始列；偏移不在边界时先向后取整。 */
    public static int columnAtOffset(String text, int offset) {
        if (text == null || offset <= 0) return 0;
        int clamped = Math.min(offset, text.length());
        int column = 0, position = 0;
        for (String cluster : clusters(text)) {
            if (position >= clamped) break;
            column += clusterWidth(cluster);
            position += cluster.length();
        }
        return column;
    }

    /** @return 从 offset 起下一个字素的起始偏移；到末尾返回 text.length()。 */
    public static int nextOffset(String text, int offset) {
        if (text == null || offset >= text.length()) return text == null ? 0 : text.length();
        String rest = text.substring(offset);
        for (String cluster : clusters(rest)) return offset + cluster.length();
        return text.length();
    }

    /** @return 从 offset 向前一个字素的起始偏移；到开头返回 0。 */
    public static int previousOffset(String text, int offset) {
        if (text == null || offset <= 0) return 0;
        String before = text.substring(0, Math.min(offset, text.length()));
        List<String> parts = clusters(before);
        return parts.isEmpty() ? 0 : offset - parts.getLast().length();
    }

    /** 只保留完整字素地裁掉尾部空白；不改变正文内部空格。 */
    public static String stripTrailingSpaces(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == ' ') end--;
        return text.substring(0, end);
    }

    // ===== 字符分类辅助：与固定 Pi utils.ts 的规则对应 =====

    /** Pi 认为终端会单独分配格子的 spacing mark；少数例外不占列。 */
    private static boolean isTerminalSpacingMark(int c) {
        if (c == 0x1734 || c == 0x302e || c == 0x302f) return false;
        if (c >= 0x065f && c <= 0x065f) return true;
        if (c == 0x0f7f || c == 0x102b || c == 0x102c || c == 0x1031) return true;
        if (c >= 0x1033 && c <= 0x1035) return true;
        if (c == 0x1038 || (c >= 0x103a && c <= 0x103e)) return true;
        return Character.getType(c) == Character.COMBINING_SPACING_MARK;
    }

    private static boolean isMark(int c) {
        int type = Character.getType(c);
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK;
    }

    /** 近似 Default_Ignorable/Control/Format/Mark/Surrogate 的零宽判定。 */
    private static boolean isNonPrinting(int c) {
        if (Character.isISOControl(c)) return true;
        return isMark(c) || isDefaultIgnorable(c);
    }

    private static boolean isDefaultIgnorable(int c) {
        return (c >= 0x200b && c <= 0x200f) || (c >= 0x202a && c <= 0x202e)
                || (c >= 0x2060 && c <= 0x206f) || c == 0x00ad || c == 0x034f || c == 0x061c
                || (c >= 0x180b && c <= 0x180d) || (c >= 0xfe00 && c <= 0xfe0f)
                || c == 0xfeff || (c >= 0xfff0 && c <= 0xfff8)
                || (c >= 0x1bca0 && c <= 0x1bca3) || (c >= 0x1d173 && c <= 0x1d17a)
                || (c >= 0xe0000 && c <= 0xe0fff) || (c >= 0xe0100 && c <= 0xe01ef)
                || (c >= 0xf0000 && c <= 0x10ffff && (c & 0xfffe) == 0xfffe);
    }

    private static boolean isZeroWidthCluster(String cluster) {
        return cluster.codePoints().allMatch(c -> isMark(c) || Character.isISOControl(c)
                || isDefaultIgnorable(c) || Character.getType(c) == Character.SURROGATE);
    }

    private static String stripLeadingNonPrinting(String cluster) {
        int offset = 0;
        while (offset < cluster.length()) {
            int c = cluster.codePointAt(offset);
            if (!isMark(c) && !Character.isISOControl(c) && !isDefaultIgnorable(c)
                    && Character.getType(c) != Character.SURROGATE) break;
            offset += Character.charCount(c);
        }
        return cluster.substring(offset);
    }

    private static boolean couldBeEmoji(String cluster) {
        int cp = cluster.codePointAt(0);
        return (cp >= 0x1f000 && cp <= 0x1fbff) || (cp >= 0x2300 && cp <= 0x23ff)
                || (cp >= 0x2600 && cp <= 0x27bf) || (cp >= 0x2b50 && cp <= 0x2b55)
                || cluster.indexOf('\uFE0F') >= 0 || cluster.codePointCount(0, cluster.length()) > 2;
    }

    /**
     * 判断簇是否按 RGI emoji 处理。完整 RGI 数据库需要 Unicode 17 属性表；这里使用
     * 终端实际渲染所需的安全近似：VS16、ZWJ、肤色、区域指示符对、keycap 或默认 emoji 表现
     * 的常见码点块按 2 列。普通符号（如不带 VS16 的 ©）仍按 East Asian Width 处理。
     * 这个近似与 Pi 的 RGI 正则存在极少数边界差异，已在第三方/偏差报告记录。
     */
    private static boolean isRgiEmojiLike(String cluster) {
        if (cluster.indexOf('\uFE0F') >= 0 || cluster.indexOf('\u200D') >= 0) return true;
        int count = cluster.codePointCount(0, cluster.length());
        int first = cluster.codePointAt(0);
        boolean regional = first >= 0x1f1e6 && first <= 0x1f1ff;
        if (regional && count >= 4) return true;
        if (isKeycap(cluster)) return true;
        for (int i = 0; i < cluster.length();) {
            int c = cluster.codePointAt(i);
            i += Character.charCount(c);
            if (c >= 0x1f3fb && c <= 0x1f3ff) return true;
            if ((c >= 0x1f000 && c <= 0x1faff) && defaultEmojiPresentation(c)) return true;
        }
        return defaultEmojiPresentation(first) && count == 1 || first >= 0x1f000 && first <= 0x1faff && count == 1;
    }

    /** Emoji_Presentation=Yes 的常见码点（未包含的全部按 EAW/普通符号处理）。 */
    private static boolean defaultEmojiPresentation(int c) {
        return (c >= 0x1f300 && c <= 0x1f5ff) || (c >= 0x1f600 && c <= 0x1f64f)
                || (c >= 0x1f680 && c <= 0x1f6ff) || (c >= 0x1f900 && c <= 0x1f9ff)
                || (c >= 0x1fa70 && c <= 0x1faff) || (c >= 0x1f000 && c <= 0x1f0ff)
                || (c >= 0x1f100 && c <= 0x1f1e5) || c == 0x231a || c == 0x231b
                || (c >= 0x23e9 && c <= 0x23ec) || c == 0x23f0 || c == 0x23f3
                || (c >= 0x25fd && c <= 0x25fe) || (c >= 0x2614 && c <= 0x2615)
                || (c >= 0x2648 && c <= 0x2653) || c == 0x267f || c == 0x2693 || c == 0x26a1
                || (c >= 0x26aa && c <= 0x26ab) || (c >= 0x26bd && c <= 0x26be)
                || (c >= 0x26c4 && c <= 0x26c5) || c == 0x26ce || c == 0x26d4 || c == 0x26ea
                || (c >= 0x26f2 && c <= 0x26f3) || c == 0x26f5 || c == 0x26fa || c == 0x26fd
                || c == 0x2705 || (c >= 0x270a && c <= 0x270b) || c == 0x2728 || c == 0x274c
                || c == 0x274e || (c >= 0x2753 && c <= 0x2755) || c == 0x2757
                || (c >= 0x2795 && c <= 0x2797) || c == 0x27b0 || c == 0x27bf
                || (c >= 0x2b1b && c <= 0x2b1c) || c == 0x2b50 || c == 0x2b55;
    }

    private static boolean isKeycap(String cluster) {
        return cluster.length() >= 2 && cluster.charAt(0) >= '0' && cluster.charAt(0) <= '9'
                && cluster.indexOf('\u20e3') >= 0;
    }
}
