package com.jadecode.cli.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 全屏滚轮行数策略：固定 Pi `wheel-scroll.ts` 的自动加速与 Alt 倍速语义。
 *
 * <p>配置用 0 表示 auto、1-100 表示固定行数（旧哨兵兼容）。auto 只对“终端不自行加速”
 * 的环境生效；Linux/SSH 通常一次滚轮事件只代表一个 notch，所以需要按事件间隔放大。
 * 手势状态与偏移余量保存在本对象中：反向、停顿超过 200ms 或设置变化会重置，避免跨手势
 * 错误累计。regular 模式不调用本类，保留终端原生滚动。</p>
 *
 * <p>本类只在 UI 线程使用；不写终端、不读配置、不持久化。</p>
 */
public final class WheelScrollPolicy {
    /** Pi ALT 滚轮倍速：加速行数计算后只乘一次。 */
    public static final int ALT_MULTIPLIER = 5;
    /** 同一 notch 的高分辨率事件间隔上限；小于该值不加速。 */
    private static final long BURST_GAP_MS = 5;
    /** 超过该间隔视为新手势。 */
    private static final long GESTURE_GAP_MS = 200;
    /** 平均间隔 100ms 对应每事件 1 行。 */
    private static final double REFERENCE_GAP_MS = 100;
    /** auto 每事件上限。 */
    private static final int MAX_AUTO_LINES = 6;

    /** Pi 设置页的固定候选；0 表示 auto。 */
    private static final List<Integer> PRESETS = List.of(1, 2, 3, 5, 10);

    private int storedLines;
    private final boolean accelerate;
    private long lastTime = Long.MIN_VALUE;
    private int lastDirection;
    private Double averageGap;
    private double carry;

    /** 生产构造：按平台/SSH 环境决定是否启用 auto 加速。 */
    public WheelScrollPolicy(int storedLines) {
        this(storedLines, defaultAccelerate());
    }

    /**
     * 可注入构造，供测试固定终端行为。
     * @param storedLines 0 为 auto，否则 1-100
     * @param accelerate auto 是否按事件速度放大
     */
    public WheelScrollPolicy(int storedLines, boolean accelerate) {
        this.accelerate = accelerate;
        setLines(storedLines);
    }

    /**
     * Pi 的终端判定：仅本地 macOS 终端通常已由 OS 加速；SSH 连接（包括 VS Code Remote）
     * 仍由服务端按 notch 计数，因此需要应用层加速。
     * @return auto 是否启用速度策略
     */
    public static boolean defaultAccelerate() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean mac = os.startsWith("mac");
        boolean ssh = System.getenv("SSH_CONNECTION") != null
                || System.getenv("SSH_CLIENT") != null
                || System.getenv("SSH_TTY") != null;
        return !(mac && !ssh);
    }

    /** 更新设置；任何变化都清除旧手势与小数余量。 */
    public void setLines(int storedLines) {
        this.storedLines = storedLines == 0 ? 0 : Math.max(1, Math.min(100, storedLines));
        resetGesture();
    }

    /** @return 当前存储值（0=auto）。 */
    public int storedLines() { return storedLines; }

    /** 显式清除手势；模式切换/恢复后调用，避免旧间隔影响新画面。 */
    public void resetGesture() {
        lastTime = Long.MIN_VALUE;
        lastDirection = 0;
        averageGap = null;
        carry = 0;
    }

    /**
     * 计算一个滚轮事件应滚动的行数（未乘 Alt）。
     * @param direction -1 上、1 下
     * @param nowMillis 单调时间毫秒
     * @return 正数行数；固定值不受时间影响
     */
    public int lines(int direction, long nowMillis) {
        if (storedLines != 0) return storedLines;
        if (!accelerate) return 1;
        int sign = direction < 0 ? -1 : 1;
        long gap = nowMillis - lastTime;
        boolean sameGesture = sign == lastDirection && gap <= GESTURE_GAP_MS && lastTime != Long.MIN_VALUE;
        lastTime = nowMillis;
        lastDirection = sign;
        if (!sameGesture) {
            averageGap = null;
            carry = 0;
            return 1;
        }
        if (gap < BURST_GAP_MS) return 1;
        averageGap = averageGap == null ? gap : (averageGap + gap) / 2;
        double lines = Math.min(MAX_AUTO_LINES, Math.max(1, REFERENCE_GAP_MS / averageGap)) + carry;
        int whole = (int) Math.floor(lines);
        carry = lines - whole;
        return Math.max(1, whole);
    }

    /**
     * @param direction 滚轮方向
     * @param nowMillis 单调时间
     * @param alt 是否按住 Alt
     * @return 最终行数；Alt 倍速只在这里应用一次
     */
    public int lines(int direction, long nowMillis, boolean alt) {
        return lines(direction, nowMillis) * (alt ? ALT_MULTIPLIER : 1);
    }

    /**
     * @param current 当前存储值
     * @return Pi 候选（auto 在前，数值有序去重并保留合法自定义值）
     */
    public static List<Integer> candidates(int current) {
        var values = new ArrayList<Integer>();
        values.add(0);
        var numbers = new ArrayList<Integer>(PRESETS);
        if (current > 0 && !numbers.contains(current)) numbers.add(current);
        numbers.sort(Integer::compareTo);
        values.addAll(numbers);
        return List.copyOf(values);
    }

    /**
     * @param current 当前存储值
     * @return 下一个候选；10 或自定义末项之后回到 auto
     */
    public static int next(int current) {
        var values = candidates(current);
        int index = values.indexOf(current);
        return values.get((index + 1) % values.size());
    }
}
