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
    private static final Set<String> QUERY = Set.of("pwd", "echo", "printf", "true", "false", "ls", "cat", "head",
            "tail", "wc", "stat", "readlink");

    /**
     * 绑定工作区路径解析器，供Shell 分析按各命令段的工作目录解析读写目标。
     *
     * @param resolver 绑定真实工作区的路径解析器，用于定位相对路径和链接目标
     */
    public ShellPermissionCheck(PathResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * 为 bash 工具提取可识别读写目标、各段 argv 和工作目录，供规则层匹配。
     * 解析器不执行变量/脚本；无法知道一般程序的内部行为时不把它标成只读。
     * 参数错误与复杂语法分别保存在 error/uncertain；调用方不会因结果为空就视为安全。
     *
     * @param tool 模型调用的工具注册名
     * @param input 本次工具参数 JSON 对象，包含该工具 schema 约定的字段
     * @return 本次工具的真实工作目录、资源、读写与不确定属性；输入错误保存在 error
     */
    @Override
    public Analysis analyze(String tool, ObjectNode input) {
        Path cwd = resolver.workspaceReal();
        if (!input.path("command").isTextual())
            return new Analysis(tool, cwd, List.of(), List.of(), false, false, false, "Missing textual command", "");
        var parsed = parser.parse(input.path("command").textValue());
        var resources = new ArrayList<Resource>();
        var commands = new ArrayList<Command>();
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
                if (argv.getFirst().contains("=") || Set.of("eval", "exec", "source", ".", "for", "while", "if", "env",
                        "command", "xargs", "sh", "bash").contains(name)) {
                    uncertain = true;
                    reasons.add(
                            "Contains environment assignment, indirect execution or a nested shell; outer arguments alone cannot determine the operation");
                }
                boolean deletes = name.equals("rm") || name.equals("rmdir") || name.equals("mv");
                boolean writes = deletes || Set.of("mkdir", "touch", "cp", "chmod", "chown", "dd", "tee").contains(name)
                        || name.equals("sed")
                                && argv.stream().anyMatch(a -> a.equals("--in-place") || a.startsWith("-i"));
                mutating |= writes;
                boolean paths = writes || Set.of("cat", "head", "tail", "wc", "ls", "stat", "readlink").contains(name);
                // cp/mv 的最后一个普通参数通常是目标；来源和目标不能都说成删除。
                // 带 -t/--target-directory 的变体另标不确定，不假装完整理解 GNU 选项。
                int destination = -1;
                if (name.equals("cp") || name.equals("mv")) {
                    for (int k = 1; k < argv.size(); k++) {
                        if (argv.get(k).equals("-t") || argv.get(k).startsWith("--target-directory"))
                            uncertain = true;
                        if (!argv.get(k).startsWith("-"))
                            destination = k;
                    }
                }
                if (paths)
                    for (int i = 1; i < argv.size(); i++) {
                        String arg = argv.get(i);
                        if (arg.startsWith("-"))
                            continue;
                        // 只跳过已识别选项的值，不把 rm 123 中的数字文件名误当作选项。
                        if ((name.equals("head") || name.equals("tail")) && i > 1
                                && Set.of("-n", "-c", "--lines", "--bytes").contains(argv.get(i - 1)))
                            continue;
                        if (name.equals("dd")) {
                            if (!arg.startsWith("of="))
                                continue;
                            arg = arg.substring(3);
                        }
                        // 标准输入 '-' 和选项在上面处理；一般命令不据此冒充已知全部副作用。
                        if (arg.isBlank())
                            continue;
                        String operation = deletes ? "DELETE" : writes ? "WRITE" : "READ";
                        if (name.equals("cp") || name.equals("mv"))
                            operation = i == destination ? "WRITE" : name.equals("mv") ? "DELETE" : "READ";
                        addResource(resources, current, arg, operation);
                    }
                if (name.equals("cd")) {
                    if (argv.size() != 2 || !segment.follows().equals("&&"))
                        uncertain = true;
                    else {
                        addResource(resources, current, argv.get(1), "READ");
                        current = resolve(current, argv.get(1));
                    }
                }
            }
            for (var redirect : segment.redirects()) {
                if (redirect.operator().contains("&")) {
                    if (!redirect.target().matches("[0-9-]+"))
                        uncertain = true;
                    continue;
                }
                Path target = resolve(segmentCwd, redirect.target());
                boolean write = redirect.operator().contains(">");
                // /dev/null 只作为丢弃输出，不以整个 /dev 目录关键词制造一次审批。
                if (target.equals(Path.of("/dev/null")))
                    continue;
                addResource(resources, segmentCwd, redirect.target(), write ? "WRITE" : "READ");
                mutating |= write;
            }
        }
        if (uncertain && reasons.isEmpty())
            reasons.add("Contains an unsupported option, directory change or redirection form");
        return new Analysis(tool, cwd, resources, commands, mutating, readOnly, uncertain, parsed.error(),
                String.join("; ", reasons));
    }

    /**
     * 先按当前命令段的工作目录拼接路径，再用共享解析器取得真实目标，供 cd、文件参数和重定向分析使用。
     *
     * @param cwd 本次操作起始工作目录，相对路径以此为基准
     * @param text 本次处理的完整文本，按该入口执行编码、脱敏、显示或组装
     * @return 按此命令段工作目录解析后的真实目标
     */
    private Path resolve(Path cwd, String text) {
        return resolver.resolve(cwd.resolve(text));
    }

    /**
     * 原始路径和链接目标都保留，后面的 deny/ask 任一命中即成立。
     *
     * @param resources 本次 Shell 分析累计的资源列表，方法向其中追加原始路径和真实目标
     * @param cwd 本次操作起始工作目录，相对路径以此为基准
     * @param text 本次处理的完整文本，按该入口执行编码、脱敏、显示或组装
     * @param operation 在已取得记忆库短锁后执行的本地读写操作
     */
    private void addResource(List<Resource> resources, Path cwd, String text, String operation) {
        Path original = cwd.resolve(text).normalize(), real = resolve(cwd, text);
        resources.add(new Resource(real, operation));
        if (!original.equals(real))
            resources.add(new Resource(original, operation));
    }

    /**
     * 从 argv[0] 的最后一个斜杠后提取可执行文件名，供命令读写分类及危险命令匹配使用。
     *
     * @param value 命令 argv 的第一个元素，可包含可执行文件路径
     * @return argv[0] 最后一个斜杠后的程序名
     */
    public static String executable(String value) {
        int slash = value.lastIndexOf('/');
        return value.substring(slash + 1);
    }
}
