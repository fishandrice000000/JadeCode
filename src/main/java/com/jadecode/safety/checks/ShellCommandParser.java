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
    /**
     * 保存一项重定向的操作符和目标，供权限分析区分读取、写入及文件描述符转接。
     *
     * @param operator Shell 重定向操作符
     * @param target 重定向的目标文本，随后按所在命令段目录解析
     */
    public record Redirect(String operator, String target) {
    }

    /**
     * 保存一段 Shell 命令的参数、重定向和后继连接符，供权限分析逐段判断工作目录和资源。
     *
     * @param argv 解析后保留参数边界的命令参数列表
     * @param redirects 此命令段的重定向操作列表
     * @param follows 本段后面的命令连接符，用于判断 cd 与后续命令关系
     */
    public record Segment(List<String> argv, List<Redirect> redirects, String follows) {
        /**
         * 将命令参数和重定向列表复制为不可变集合，固定解析后的一个命令段。
         *
         * @param argv 解析后保留参数边界的命令参数列表
         * @param redirects 此命令段的重定向操作列表
         * @param follows 本段后面的命令连接符，用于判断 cd 与后续命令关系
         */
        public Segment {
            argv = List.copyOf(argv);
            redirects = List.copyOf(redirects);
        }
    }

    /**
     * reasons 只包含程序生成的语法说明，不带脚本正文或变量值。
     *
     * @param segments 按原顺序解析出的 Shell 命令段
     * @param uncertain 命令是否包含当前分析器无法确定资源的语法
     * @param error 本次操作产生的错误说明
     * @param reasons 导致命令分析不确定的具体原因列表
     */
    public record Parsed(List<Segment> segments, boolean uncertain, String error, List<String> reasons) {
        /**
         * 复制命令段和不确定原因列表，形成包含解析错误及不确定标记的完整解析结果。
         *
         * @param segments 按原顺序解析出的 Shell 命令段
         * @param uncertain 命令是否包含当前分析器无法确定资源的语法
         * @param error 本次操作产生的错误说明
         * @param reasons 导致命令分析不确定的具体原因列表
         */
        public Parsed {
            segments = List.copyOf(segments);
            reasons = List.copyOf(reasons);
        }

        /**
         * 用命令段、不确定标记和错误说明创建解析结果，缺省的不确定原因列表设为空。
         *
         * @param segments 按原顺序解析出的 Shell 命令段
         * @param uncertain 命令是否包含当前分析器无法确定资源的语法
         * @param error 本次操作产生的错误说明
         */
        public Parsed(List<Segment> segments, boolean uncertain, String error) {
            this(segments, uncertain, error, List.of());
        }
    }

    /**
     * 保存词法分析中的文本及其是否为 Shell 操作符，供 parse 组装参数、命令连接和重定向。
     *
     * @param value 扫描出的一个参数文本或 Shell 操作符
     * @param operator Shell 重定向操作符
     */
    private record Token(String value, boolean operator) {
    }

    /**
     * 把命令文字拆成命令段与重定向，不运行任何部分，也不修改原字符串。
     * 引号内的空格、分号是参数内容，不能简单按空格或分号 split。
     * 先扫描成带“操作符/参数”区别的 token，再把 token 组成各段；变量、循环等保留为
     * uncertain，而非法引号/缺少重定向目标返回 error。策略层再决定询问还是拒绝。
     *
     * @param text 本次处理的完整文本，按该入口执行编码、脱敏、显示或组装
     * @return 按原顺序解析的命令段、重定向、不确定原因与可选错误
     */
    public Parsed parse(String text) {
        // 1. quote 记录当前是否在引号里；started 用来区分空字符串参数与没有参数。
        var tokens = new ArrayList<Token>();
        var word = new StringBuilder();
        var reasons = new LinkedHashSet<String>();
        char quote = 0;
        boolean started = false, uncertain = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                    continue;
                }
                if (quote == '"' && (c == '$' || c == '`')) {
                    uncertain = true;
                    reasons.add("Variable or command substitution inside double quotes");
                }
                if (quote == '"' && c == '\\' && i + 1 < text.length()) {
                    char next = text.charAt(i + 1);
                    if (next == '$' || next == '`' || next == '"' || next == '\\' || next == '\n') {
                        i++;
                        if (next != '\n')
                            word.append(next);
                        continue;
                    }
                }
                word.append(c);
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                started = true;
                continue;
            }
            if (c == '\\') {
                if (++i == text.length())
                    return new Parsed(List.of(), true, "Incomplete shell escape");
                if (text.charAt(i) != '\n') {
                    word.append(text.charAt(i));
                    started = true;
                }
                continue;
            }
            if (c == '#' && !started) {
                while (i < text.length() && text.charAt(i) != '\n')
                    i++;
                c = '\n';
            }
            // ~/ 只有位于未引用参数开头时才由 shell 展开。这里在去掉引号前处理，
            // 所以 '~/a'、"~/a"、\~/a 始终保持字面路径，不能错误指向用户目录。
            if (c == '~' && !started) {
                boolean plainHome = i + 1 == text.length() || text.charAt(i + 1) == '/'
                        || Character.isWhitespace(text.charAt(i + 1)) || ";&|<>".indexOf(text.charAt(i + 1)) >= 0;
                String home = System.getenv("HOME");
                if (plainHome && home != null && !home.isBlank() && Path.of(home).isAbsolute()) {
                    word.append(home);
                    started = true;
                    continue;
                }
                uncertain = true;
                reasons.add(plainHome ? "Cannot determine the shell HOME directory"
                        : "Contains an unsupported home-directory expansion or quoting combination");
            }
            if (c == '$' || c == '`') {
                uncertain = true;
                reasons.add("Contains variable or command substitution");
            }
            if (c == '(' || c == ')' || c == '{' || c == '}') {
                uncertain = true;
                reasons.add("Contains grouping, functions or compound shell structure");
            }
            if (c == '*' || c == '?' || c == '[') {
                uncertain = true;
                reasons.add("Contains runtime filename glob expansion");
            }
            boolean op = c == ';' || c == '&' || c == '|' || c == '<' || c == '>' || c == '\n';
            if (op || Character.isWhitespace(c)) {
                // 2>/dev/null 的 2 是文件描述符，不是命令参数；但 echo 2 >file 中的 2 仍是参数。
                String fd = op && (c == '<' || c == '>') && started && word.toString().matches("[0-9]+")
                        ? word.toString()
                        : "";
                if (started && fd.isEmpty())
                    tokens.add(new Token(word.toString(), false));
                word.setLength(0);
                started = false;
                if (!op)
                    continue;
                String operator = String.valueOf(c);
                if (i + 1 < text.length() && text.charAt(i + 1) == c
                        && (c == '&' || c == '|' || c == '>' || c == '<')) {
                    operator += c;
                    i++;
                }
                if ((c == '<' || c == '>') && i + 1 < text.length() && text.charAt(i + 1) == '&') {
                    operator += '&';
                    i++;
                }
                if (operator.equals("&") || operator.startsWith("<<")) {
                    uncertain = true;
                    reasons.add(operator.equals("&") ? "Contains the shell background operator &"
                            : "Contains here-document input");
                }
                tokens.add(new Token(fd + operator, true));
            } else {
                word.append(c);
                started = true;
            }
        }
        if (quote != 0)
            return new Parsed(List.of(), true, "Incomplete shell quoting");
        if (started)
            tokens.add(new Token(word.toString(), false));
        // 2. 只有操作符 token 才能分段；例如 echo ';' 中的分号仍属于 argv。
        var segments = new ArrayList<Segment>();
        var argv = new ArrayList<String>();
        var redirects = new ArrayList<Redirect>();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (!token.operator()) {
                argv.add(token.value());
                continue;
            }
            String value = token.value();
            if (value.contains(">") || value.contains("<")) {
                if (++i >= tokens.size() || tokens.get(i).operator())
                    return new Parsed(List.of(), true, "Redirection is missing a target");
                redirects.add(new Redirect(value, tokens.get(i).value()));
            } else {
                // 连续空行合法；缺少命令的 &&、||、| 等不做“已可靠解析”的假设。
                if (argv.isEmpty() && redirects.isEmpty() && !value.equals("\n"))
                    uncertain = true;
                if (!argv.isEmpty() || !redirects.isEmpty())
                    segments.add(new Segment(argv, redirects, value));
                argv = new ArrayList<>();
                redirects = new ArrayList<>();
            }
        }
        if (!argv.isEmpty() || !redirects.isEmpty())
            segments.add(new Segment(argv, redirects, ""));
        if (segments.isEmpty())
            return new Parsed(List.of(), true, "Command cannot be empty");
        if (List.of("&&", "||", "|").contains(segments.getLast().follows()))
            uncertain = true;
        if (uncertain && reasons.isEmpty())
            reasons.add("A connector is missing a determinable command");
        return new Parsed(List.copyOf(segments), uncertain, "", List.copyOf(reasons));
    }
}
