package com.jadecode.cli.tui;

import com.jadecode.session.ContextUsage;
import com.jadecode.session.SessionUsage;

/**
 * 真实 token/耗时/context/cache 的统一显示格式；FooterView、SessionInfoView、StatusView 共用。
 *
 * <p>不累计、不算不存在的费用/订阅、不修改压缩阈值。数值单位规则与 Pi 对齐：小于 1000
 * 显示原值；K/M/B/T/Q 按固定分段，10 以下保留一位小数，10 及以上取整。用户要求
 * 100000→100K（K 大写）；不因四舍五入自动切换单位。耗时始终一位小数（29.0s）。
 * context 显示估计占用/窗口，不把“剩余比例”换标签后冒充占用；缺报告不当 0。</p>
 */
public final class UsageFormatter {
    private UsageFormatter() { }

    /**
     * @param value 非负 token 数
     * @return 999、1.0K、10K、100K、1.0M、10M 形式
     */
    public static String tokens(long value) {
        if (value < 1000) return Long.toString(Math.max(0, value));
        double scaledValue = value;
        String[] units = {"K", "M", "B", "T", "Q"};
        int unit = -1;
        while (scaledValue >= 1000 && unit < units.length - 1) { scaledValue /= 1000; unit++; }
        // 999999→1000K 不自动进位；K 从 1000 开始。
        if (unit < 0) { scaledValue = value / 1000.0; unit = 0; }
        return (scaledValue < 10
                ? String.format(java.util.Locale.ROOT, "%.1f", scaledValue)
                : Long.toString(Math.round(scaledValue))) + units[unit];
    }

    /**
     * 整轮耗时：不足一分钟一位小数（50ms→0.1s，29.0s 保留小数）；分钟以上紧凑整数。
     * @param nanos 单调时钟纳秒；负值按 0
     */
    public static String turnDuration(long nanos) {
        long millis = Math.max(0, nanos) / 1_000_000;
        if (millis < 60_000) {
            long tenths = (millis + 50) / 100;
            if (tenths < 600) return (tenths / 10) + "." + (tenths % 10) + "s";
            millis = 60_000;
        }
        long seconds = millis / 1000;
        long days = seconds / 86_400, hours = seconds / 3600 % 24, minutes = seconds / 60 % 60;
        seconds %= 60;
        if (days > 0) return String.format(java.util.Locale.ROOT, "%dd%02dh%02dm%02ds", days, hours, minutes, seconds);
        if (hours > 0) return String.format(java.util.Locale.ROOT, "%dh%02dm%02ds", hours, minutes, seconds);
        return String.format(java.util.Locale.ROOT, "%dm%02ds", minutes, seconds);
    }

    /** 工具耗时：小于一秒显示 ms，其余用 turnDuration 的紧凑形式。 */
    public static String toolDuration(long nanos) {
        long millis = Math.max(0, nanos) / 1_000_000;
        if (millis < 1000) return millis + "ms";
        return turnDuration(nanos);
    }

    /**
     * context 占用显示：`12.3%/32K`。只有 ContextUsage 真实估计才调用；未知/窗口<=0 返回空，
     * 由 footer 省略而不是画 0%。
     */
    public static String context(ContextUsage usage) {
        if (usage == null || usage.windowTokens() <= 0) return "";
        int percentTimesTen = (int) Math.round(usage.occupiedTokens() * 1000.0 / usage.windowTokens());
        return (percentTimesTen / 10) + "." + (percentTimesTen % 10) + "%/" + tokens(usage.windowTokens());
    }

    /** @return `↑1.2K ↓345`，只包含真实报告字段；都为 0 返回空。 */
    public static String tokenCounts(SessionUsage.Totals total) {
        if (total == null) return "";
        var parts = new java.util.ArrayList<String>();
        if (total.inputTokens() > 0 || total.outputTokens() > 0) {
            parts.add("↑" + tokens(total.inputTokens()));
            parts.add("↓" + tokens(total.outputTokens()));
        }
        return String.join(" ", parts);
    }

    /**
     * @return `R1.2K W345` 缓存读写；没有报告字段返回空。缓存读/写不重复加到 token 总数。
     */
    public static String cacheCounts(SessionUsage.Totals total) {
        if (total == null) return "";
        var parts = new java.util.ArrayList<String>();
        if (total.cacheReadTokens() > 0) parts.add("R" + tokens(total.cacheReadTokens()));
        if (total.cacheWriteTokens() > 0) parts.add("W" + tokens(total.cacheWriteTokens()));
        return String.join(" ", parts);
    }

    /**
     * cache hit 比例：只有存在最近请求分母（reportedRequests>0）时显示；否则空。
     * 不能画 0% 冒充“没有缓存命中数据”。
     */
    public static String cacheHit(SessionUsage.Totals total) {
        if (total == null || total.reportedRequests() <= 0) return "";
        long denominator = total.inputTokens() + total.cacheReadTokens() + total.cacheWriteTokens();
        if (denominator <= 0) return "";
        int percent = (int) Math.round(total.cacheReadTokens() * 100.0 / denominator);
        return "CH" + percent + "%";
    }

    /** @return 缺失用量报告的请求数；0 时不显示。 */
    public static String missing(SessionUsage.Totals total) {
        return total == null || total.missingRequests() <= 0 ? "" : "missing " + total.missingRequests();
    }
}
