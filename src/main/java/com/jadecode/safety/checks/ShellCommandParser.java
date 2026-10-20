package com.jadecode.safety.checks;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.nio.file.Path;

/**
 * 解释 /bin/sh 的有限语法子集：字面参数、引号、转义、连接符与重定向。
 * 不展开变量、不运行 shell，也不把复杂结构强行解释成安全命令；不确定性会交回审批策略。
 */
public final class ShellCommandParser {
    public record Redirect(String operator, String target) { }
    public record Segment(List<String> argv, List<Redirect> redirects, String follows) {
        public Segment { argv = List.copyOf(argv); redirects = List.copyOf(redirects); }
    }
    /** reasons 只包含程序生成的语法说明，不带脚本正文或变量值。 */
    public record Parsed(List<Segment> segments, boolean uncertain, String error, List<String> reasons) {
        public Parsed { segments = List.copyOf(segments); reasons = List.copyOf(reasons); }
        public Parsed(List<Segment> segments, boolean uncertain, String error) { this(segments, uncertain, error, List.of()); }
    }
    private record Token(String value, boolean operator) { }

    /**
     * 把命令文字拆成命令段与重定向，不运行任何部分，也不修改原字符串。
     * 引号内的空格、分号是参数内容，不能简单按空格或分号 split。
     * 先扫描成带“操作符/参数”区别的 token，再把 token 组成各段；变量、循环等保留为
     * uncertain，而非法引号/缺少重定向目标返回 error。策略层再决定询问还是拒绝。
     */
    public Parsed parse(String text) {
        // 1. quote 记录当前是否在引号里；started 用来区分空字符串参数与没有参数。
        var tokens = new ArrayList<Token>(); var word = new StringBuilder();
        var reasons = new LinkedHashSet<String>();
        char quote = 0; boolean started = false, uncertain = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) { quote = 0; continue; }
                if (quote == '"' && (c == '$' || c == '`')) { uncertain = true; reasons.add("双引号内含变量或命令替换"); }
                if (quote == '"' && c == '\\' && i + 1 < text.length()) {
                    char next = text.charAt(i + 1);
                    if (next == '$' || next == '`' || next == '"' || next == '\\' || next == '\n') {
                        i++; if (next != '\n') word.append(next); continue;
                    }
                }
                word.append(c); continue;
            }
            if (c == '\'' || c == '"') { quote = c; started = true; continue; }
            if (c == '\\') {
                if (++i == text.length()) return new Parsed(List.of(), true, "shell 转义不完整");
                if (text.charAt(i) != '\n') { word.append(text.charAt(i)); started = true; }
                continue;
            }
            if (c == '#' && !started) { while (i < text.length() && text.charAt(i) != '\n') i++; c = '\n'; }
            // ~/ 只有位于未引用参数开头时才由 shell 展开。这里在去掉引号前处理，
            // 所以 '~/a'、"~/a"、\~/a 始终保持字面路径，不能错误指向用户目录。
            if (c == '~' && !started) {
                boolean plainHome = i + 1 == text.length() || text.charAt(i + 1) == '/'
                        || Character.isWhitespace(text.charAt(i + 1)) || ";&|<>".indexOf(text.charAt(i + 1)) >= 0;
                String home = System.getenv("HOME");
                if (plainHome && home != null && !home.isBlank() && Path.of(home).isAbsolute()) {
                    word.append(home); started = true; continue;
                }
                uncertain = true; reasons.add(plainHome ? "无法确定 shell 的 HOME 目录" : "含未支持的用户家目录展开或引用组合");
            }
            if (c == '$' || c == '`') { uncertain = true; reasons.add("含变量或命令替换"); }
            if (c == '(' || c == ')' || c == '{' || c == '}') { uncertain = true; reasons.add("含分组、函数或复合 shell 结构"); }
            if (c == '*' || c == '?' || c == '[') { uncertain = true; reasons.add("含运行时文件名通配展开"); }
            boolean op = c == ';' || c == '&' || c == '|' || c == '<' || c == '>' || c == '\n';
            if (op || Character.isWhitespace(c)) {
                // 2>/dev/null 的 2 是文件描述符，不是命令参数；但 echo 2 >file 中的 2 仍是参数。
                String fd = op && (c == '<' || c == '>') && started && word.toString().matches("[0-9]+") ? word.toString() : "";
                if (started && fd.isEmpty()) tokens.add(new Token(word.toString(), false));
                word.setLength(0); started = false;
                if (!op) continue;
                String operator = String.valueOf(c);
                if (i + 1 < text.length() && text.charAt(i + 1) == c && (c == '&' || c == '|' || c == '>' || c == '<')) { operator += c; i++; }
                if ((c == '<' || c == '>') && i + 1 < text.length() && text.charAt(i + 1) == '&') { operator += '&'; i++; }
                if (operator.equals("&") || operator.startsWith("<<")) {
                    uncertain = true; reasons.add(operator.equals("&") ? "含 shell 后台执行符 &" : "含 here-document 输入");
                }
                tokens.add(new Token(fd + operator, true));
            } else { word.append(c); started = true; }
        }
        if (quote != 0) return new Parsed(List.of(), true, "shell 引号不完整");
        if (started) tokens.add(new Token(word.toString(), false));
        // 2. 只有操作符 token 才能分段；例如 echo ';' 中的分号仍属于 argv。
        var segments = new ArrayList<Segment>(); var argv = new ArrayList<String>(); var redirects = new ArrayList<Redirect>();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (!token.operator()) { argv.add(token.value()); continue; }
            String value = token.value();
            if (value.contains(">") || value.contains("<")) {
                if (++i >= tokens.size() || tokens.get(i).operator()) return new Parsed(List.of(), true, "重定向缺少目标");
                redirects.add(new Redirect(value, tokens.get(i).value()));
            } else {
                // 连续空行合法；缺少命令的 &&、||、| 等不做“已可靠解析”的假设。
                if (argv.isEmpty() && redirects.isEmpty() && !value.equals("\n")) uncertain = true;
                if (!argv.isEmpty() || !redirects.isEmpty()) segments.add(new Segment(argv, redirects, value));
                argv = new ArrayList<>(); redirects = new ArrayList<>();
            }
        }
        if (!argv.isEmpty() || !redirects.isEmpty()) segments.add(new Segment(argv, redirects, ""));
        if (segments.isEmpty()) return new Parsed(List.of(), true, "命令不能为空");
        if (List.of("&&", "||", "|").contains(segments.getLast().follows())) uncertain = true;
        if (uncertain && reasons.isEmpty()) reasons.add("连接符缺少可确定的命令");
        return new Parsed(List.copyOf(segments), uncertain, "", List.copyOf(reasons));
    }
}
