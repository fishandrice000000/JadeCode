package com.jadecode.tui.theme;

import java.util.Map;
import com.jadecode.tui.text.StyledLine;

/** 不可变主题，只把命名样式编码为终端色彩；不访问偏好文件或 Agent。 */
public record Theme(String name, boolean colors) {
    private static final Map<String, String> DARK = Map.ofEntries(
            Map.entry("normal", "39"), Map.entry("muted", "90"), Map.entry("accent", "96"),
            Map.entry("heading", "1;96"), Map.entry("user", "1;94"), Map.entry("thinking", "90;3"),
            Map.entry("tool", "36"), Map.entry("error", "91"), Map.entry("warning", "93"),
            Map.entry("code", "93"), Map.entry("keyword", "95"), Map.entry("string", "92"),
            Map.entry("comment", "90;3"), Map.entry("selected", "7"));
    /** 编码一行并重置样式；无颜色模式完全不产生 SGR 色码。 */
    public String encode(StyledLine line) {
        var out = new StringBuilder();
        for (var span : line.spans()) {
            if (colors) {
                String code = DARK.getOrDefault(span.style(), "39");
                if (name.equals("light")) code = code.replace("96", "36").replace("94", "34")
                        .replace("91", "31").replace("93", "33").replace("95", "35").replace("92", "32");
                out.append("\033[").append(code).append('m');
            }
            out.append(span.text());
        }
        if (colors) out.append("\033[0m");
        return out.toString();
    }
}
