package com.jadecode.util;

/** 终端 ANSI 色码:harness 自己的输出(hook 行、拦截行、提示符)上色用。
 * 只在绑定了交互终端时输出色码;输出被重定向到文件/管道时 System.console()
 * 为 null,自动降级为纯文本,避免日志文件里全是转义垃圾(课程 Python 无条件
 * 输出色码,此处是不学它的糙处)。模型回答文本不上色,保持可解析。
 * 色码用 Java 八进制转义 \033(即 ESC,课程 Python 的 \033 同款)。 */
public final class Ansi {
    private static final boolean ENABLED = System.console() != null;

    private Ansi() {
    }

    /** 红:闸门 1 硬拒行 [blocked] */
    public static String red(String s) {
        return color("31", s);
    }

    /** 黄:权限询问 [permission] 与大输出提醒 */
    public static String yellow(String s) {
        return color("33", s);
    }

    /** 青:REPL 输入提示符 */
    public static String cyan(String s) {
        return color("36", s);
    }

    /** 灰:hook 日志行 [HOOK] */
    public static String gray(String s) {
        return color("90", s);
    }

    private static String color(String code, String s) {
        return ENABLED ? "\033[" + code + "m" + s + "\033[0m" : s;
    }
}
