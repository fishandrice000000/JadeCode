package com.jadecode.tui.text;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pi 固定版本 `get-east-asian-width@1.6.0` 的 East Asian Width 数据。
 *
 * <p>不能直接用当前 JDK/ICU 的 EastAsianWidth 属性：终端列宽必须与固定 Pi 相同时才可能逐列
 * 对照，而 Unicode 各版本会新增宽字符或修改属性。资源文件记录版本、来源和许可；这里只做
 * 只读区间查询，不联网、不执行外部脚本。数据只覆盖 fullwidth/wide（返回 2），其余返回 1；
 * ambiguous 默认按窄字符处理，与 Pi 的默认调用一致。</p>
 */
public final class TerminalWidthTable {
    private static final String RESOURCE = "/com/jadecode/tui/text/east-asian-width-1.6.0.json";
    private static final String VERSION;
    private static final int[] WIDE;
    private static final int[] FULLWIDTH;
    private static final int[] AMBIGUOUS;

    static {
        try (InputStream input = TerminalWidthTable.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing terminal width table resource: " + RESOURCE);
            JsonNode root = new ObjectMapper().readTree(input);
            VERSION = root.path("version").asText("unknown");
            WIDE = ranges(root.path("wide"));
            FULLWIDTH = ranges(root.path("fullwidth"));
            AMBIGUOUS = ranges(root.path("ambiguous"));
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private TerminalWidthTable() { }

    /** @return 打包数据的版本号，用于 manifest/测试和诊断。 */
    public static String version() { return VERSION; }

    /**
     * @param codePoint Unicode 码点（不是 UTF-16 单元）
     * @return 固定 Pi 数据下的显示列宽：wide/fullwidth 为 2，其余为 1。
     *         组合/控制字符的 0 宽处理在 {@link DisplayWidth} 完成。
     */
    public static int eastAsianWidth(int codePoint) {
        return contains(WIDE, codePoint) || contains(FULLWIDTH, codePoint) ? 2 : 1;
    }

    /** @return ambiguous 字符在严格模式下是否为宽；默认终端宽度不使用该模式。 */
    public static boolean isAmbiguousWide(int codePoint) { return contains(AMBIGUOUS, codePoint); }

    /** @return 数据是否确认该码点属于 wide/fullwidth；诊断/测试用。 */
    public static boolean isWide(int codePoint) { return eastAsianWidth(codePoint) == 2; }

    private static int[] ranges(JsonNode node) {
        if (!node.isArray()) throw new IllegalStateException("Width table ranges must be arrays");
        int[] values = new int[node.size()];
        int i = 0;
        for (JsonNode item : node) values[i++] = item.asInt();
        if (values.length % 2 != 0) throw new IllegalStateException("Width table ranges must come in pairs");
        return values;
    }

    /** 有序、互不重叠区间的二分查询。 */
    private static boolean contains(int[] ranges, int codePoint) {
        int low = 0, high = ranges.length / 2 - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int start = ranges[middle * 2], end = ranges[middle * 2 + 1];
            if (codePoint < start) high = middle - 1;
            else if (codePoint > end) low = middle + 1;
            else return true;
        }
        return false;
    }

    /** @return 资源内声明的来源/许可摘要，供第三方清单核对。 */
    public static String provenance() {
        return "get-east-asian-width@" + VERSION + " (MIT, Sindre Sorhus); data copied without modification";
    }
}
