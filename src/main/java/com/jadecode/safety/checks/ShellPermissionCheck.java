package com.jadecode.safety.checks;

import java.nio.file.Path;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.PathResolver;

/**
 * 把已解析的命令转换为权限资料。只识别明确的文件操作；构建脚本未知副作用不冒充只读。
 * 记录重定向与 cd 后的工作目录，避免只看命令开头就漏掉真正写入目标。
 */
public final class ShellPermissionCheck implements ToolPermissionCheck {
    private final PathResolver resolver;
    private final ShellCommandParser parser = new ShellCommandParser();
    private static final Set<String> QUERY = Set.of("pwd", "echo", "printf", "true", "false", "ls", "cat", "head", "tail", "wc", "stat", "readlink");
    public ShellPermissionCheck(PathResolver resolver) { this.resolver = resolver; }
    /**
     * 为 bash 工具提取可识别读写目标、各段 argv 和工作目录，供规则层匹配。
     * 解析器不执行变量/脚本；无法知道一般程序的内部行为时不把它标成只读。
     * 参数错误与复杂语法分别保存在 error/uncertain；调用方不会因结果为空就视为安全。
     */
    @Override public Analysis analyze(String tool, ObjectNode input) {
        Path cwd = resolver.workspaceReal();
        if (!input.path("command").isTextual()) return new Analysis(tool, cwd, List.of(), List.of(), false, false, false, "缺少文本 command");
        var parsed = parser.parse(input.path("command").textValue());
        var resources = new ArrayList<Resource>(); var commands = new ArrayList<Command>();
        boolean uncertain = parsed.uncertain(), mutating = false, readOnly = true;
        var reasons = new LinkedHashSet<>(parsed.reasons());
        Path current = cwd;
        // 每段都检查，不能因第一段是 echo 就忽略后面的 rm 或重定向。
        for (var segment : parsed.segments()) {
            Path segmentCwd = current; // 重定向先于 cd 生效，不能错误使用 cd 后的目录。
            List<String> argv = segment.argv();
            if (!argv.isEmpty()) {
                commands.add(new Command(argv, current, !segment.redirects().isEmpty()));
                String name = executable(argv.getFirst());
                readOnly &= QUERY.contains(name) || name.equals("cd");
                if (argv.getFirst().contains("=") || Set.of("eval", "exec", "source", ".", "for", "while", "if", "env", "command", "xargs", "sh", "bash").contains(name)) {
                    uncertain = true; reasons.add("含环境赋值、间接执行或嵌套 shell，不能仅凭外层参数确定操作");
                }
                boolean deletes = name.equals("rm") || name.equals("rmdir") || name.equals("mv");
                boolean writes = deletes || Set.of("mkdir", "touch", "cp", "chmod", "chown", "dd", "tee").contains(name)
                        || name.equals("sed") && argv.stream().anyMatch(a -> a.equals("--in-place") || a.startsWith("-i"));
                mutating |= writes;
                boolean paths = writes || Set.of("cat", "head", "tail", "wc", "ls", "stat", "readlink").contains(name);
                // cp/mv 的最后一个普通参数通常是目标；来源和目标不能都说成删除。
                // 带 -t/--target-directory 的变体另标不确定，不假装完整理解 GNU 选项。
                int destination = -1;
                if (name.equals("cp") || name.equals("mv")) {
                    for (int k = 1; k < argv.size(); k++) {
                        if (argv.get(k).equals("-t") || argv.get(k).startsWith("--target-directory")) uncertain = true;
                        if (!argv.get(k).startsWith("-")) destination = k;
                    }
                }
                if (paths) for (int i = 1; i < argv.size(); i++) {
                    String arg = argv.get(i);
                    if (arg.startsWith("-")) continue;
                    // 只跳过已识别选项的值，不把 rm 123 中的数字文件名误当作选项。
                    if ((name.equals("head") || name.equals("tail")) && i > 1
                            && Set.of("-n", "-c", "--lines", "--bytes").contains(argv.get(i - 1))) continue;
                    if (name.equals("dd")) { if (!arg.startsWith("of=")) continue; arg = arg.substring(3); }
                    // 标准输入 '-' 和选项在上面处理；一般命令不据此冒充已知全部副作用。
                    if (arg.isBlank()) continue;
                    String operation = deletes ? "DELETE" : writes ? "WRITE" : "READ";
                    if (name.equals("cp") || name.equals("mv"))
                        operation = i == destination ? "WRITE" : name.equals("mv") ? "DELETE" : "READ";
                    addResource(resources, current, arg, operation);
                }
                if (name.equals("cd")) {
                    if (argv.size() != 2 || !segment.follows().equals("&&")) uncertain = true;
                    else { addResource(resources, current, argv.get(1), "READ"); current = resolve(current, argv.get(1)); }
                }
            }
            for (var redirect : segment.redirects()) {
                if (redirect.operator().contains("&")) { if (!redirect.target().matches("[0-9-]+")) uncertain = true; continue; }
                Path target = resolve(segmentCwd, redirect.target());
                boolean write = redirect.operator().contains(">");
                // /dev/null 只作为丢弃输出，不以整个 /dev 目录关键词制造一次审批。
                if (target.equals(Path.of("/dev/null"))) continue;
                addResource(resources, segmentCwd, redirect.target(), write ? "WRITE" : "READ"); mutating |= write;
            }
        }
        if (uncertain && reasons.isEmpty()) reasons.add("含未支持的选项、目录切换或重定向形式");
        return new Analysis(tool, cwd, resources, commands, mutating, readOnly, uncertain, parsed.error(), String.join("；", reasons));
    }
    private Path resolve(Path cwd, String text) { return resolver.resolve(cwd.resolve(text)); }
    /** 原始路径和链接目标都保留，后面的 deny/ask 任一命中即成立。 */
    private void addResource(List<Resource> resources, Path cwd, String text, String operation) {
        Path original = cwd.resolve(text).normalize(), real = resolve(cwd, text);
        resources.add(new Resource(real, operation));
        if (!original.equals(real)) resources.add(new Resource(original, operation));
    }
    /** 仅识别实际 argv[0]，而不是从整段输入搜索 sudo/shutdown 等片段。 */
    public static String executable(String value) { int slash = value.lastIndexOf('/'); return value.substring(slash + 1); }
}
