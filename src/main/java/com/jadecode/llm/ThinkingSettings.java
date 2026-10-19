package com.jadecode.llm;

import java.util.Locale;

/**
 * 一次请求使用的思考强度，不是 TUI 中“是否显示思考”的开关。
 * DEFAULT 不发送额外参数，保留服务商原行为；其它值必须先通过客户端的能力列表检查。
 * 枚举可以写入非秘密配置和会话日志，不包含模型凭据或思考正文。
 */
public enum ThinkingSettings {
    DEFAULT, OFF, LOW, MEDIUM, HIGH, XHIGH, MAX;

    /** 配置和命令统一用小写名称；不把显示名称反向解析成任意请求字段。 */
    public String value() { return name().toLowerCase(Locale.ROOT); }

    /** 接受固定名称，拼错时明确失败，不静默当作默认强度。 */
    public static ThinkingSettings parse(String value) {
        if (value == null) throw new IllegalArgumentException("思考强度不能为空");
        try { return valueOf(value.strip().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("无效思考强度: " + value); }
    }
}
