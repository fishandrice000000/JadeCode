package com.jadecode.team;

/**
 * 解析 spawn_teammate 的一个 role 字符串：首行为英文显示名，后续为职责。
 * 两者只是成员资料；路由和权限使用程序生成的 ID。构造无 IO，不改变调用方字符串。
 */
public record TeammateRole(String name, String responsibilities) {
    /**
     * 在创建成员之前调用。兼容 CRLF；名称限制为 1–40 个可见 ASCII 字符并至少含一个字母。
     * 缺少换行、空职责或控制字符明确报错，模型可以依据工具错误修正，无需新增工具字段。
     */
    public static TeammateRole parse(String input) {
        if (input != null && input.length() > 4000) throw invalid();
        String value = input == null ? "" : input.replace("\r\n", "\n").strip();
        int split = value.indexOf('\n');
        if (value.length() > 4000 || split < 1) throw invalid();
        String name = value.substring(0, split).strip();
        String body = value.substring(split + 1).strip();
        if (name.length() > 40 || !name.matches("[ -~]+") || !name.matches(".*[A-Za-z].*") || body.isBlank()
                || body.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')) throw invalid();
        return new TeammateRole(name, body);
    }

    /** 错误包含格式和例子；调用者应把它作为工具结果交给模型，而不是擅自补齐职责。 */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("role must contain a short English name (1-40 characters) on the first line "
                + "and non-empty responsibilities on subsequent lines. Example: Explorer\\nInvestigate execution flow.");
    }
}
