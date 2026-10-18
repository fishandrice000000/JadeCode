package com.jadecode.util;

/**
 * 终端 ANSI 色码:harness 自己的输出(hook 行、拦截行、提示符)上色用。
 * 初始按 System.console 判断，CLI 创建 JLine 后改用实际终端的颜色支持情况。
 * 文件/管道、NO_COLOR 或 TERM=dumb 下使用纯文本，避免日志里出现转义字符。
 * 模型回答正文不上色，保持可复制。
 * 色码用 Java 八进制转义 \033(即 ESC,课程 Python 的 \033 同款)。
 */
public final class Ansi {
    private static volatile boolean enabled = System.console() != null;

    /** 纯静态工具类,禁止实例化 */
    private Ansi() {
    }

    /** 红：硬性规则阻止行 [Blocked]。 */
    public static String red(String s) {
        return color("31", s);
    }

    /** 黄：审批与警告。 */
    public static String yellow(String s) {
        return color("33", s);
    }

    /** 青:REPL 输入提示符 */
    public static String cyan(String s) {
        return color("36", s);
    }

    /** 灰：请求、工具调用等普通执行日志。 */
    public static String gray(String s) {
        return color("90", s);
    }

    /** 洋红：一次性子代理开始、返回或停止。 */
    public static String magenta(String s) {
        return color("35", s);
    }

    /** 绿：突出回复标题，只表示这里是模型回复，不代表任务成功。 */
    public static String green(String s) {
        return color("32", s);
    }

    /** 加粗：启动标题和输入提示符；不支持颜色或用户禁用时保持普通文字。 */
    public static String bold(String s) {
        return color("1", s);
    }

    /**
     * CLI 建好或关闭终端时更新是否可以使用色码，不改变任何机器或用户配置。
     * @param supported JLine 已确认终端支持颜色；非交互输出传 false
     * @return 修改前的开关，调用者关闭终端时恢复它
     */
    public static boolean useTerminal(boolean supported) {
        boolean previous = enabled;
        enabled = supported;
        return previous;
    }

    /** 包一层 ANSI 色码;非交互终端(重定向)直接返回原文 */
    private static String color(String code, String s) {
        boolean colors = enabled && System.getenv("NO_COLOR") == null && !"dumb".equals(System.getenv("TERM"));
        return colors ? "\033[" + code + "m" + s + "\033[0m" : s;
    }
}
