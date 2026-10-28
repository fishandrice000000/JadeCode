package com.jadecode.tools;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.AccessDeniedException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.PathResolver;

/**
 * glob:按模式在指定 root（缺省工作区）找文件，输出相对该目录的路径，每行一条、字典序。
 *
 * <p>从 root 递归，默认不跟随符号链接目录；模式用相对路径匹配，
 * {@code **} 跨任意层目录。结果超过 200 条截断并提示收窄。</p>
 */
public class GlobTool implements Tool {
    private static final int MAX_MATCHES = 200;

    /** 工作区根，由 JadeCodeFactory 装配时注入；本类不保存全局状态。 */
    private final PathResolver resolver;

    /**
     * @param resolver 共享路径解析器，提供预先确定的工作区根；本工具不负责安全裁决
     */
    public GlobTool(PathResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * @return 工具名 "glob"
     */
    @Override
    public String name() {
        return "glob";
    }

    /**
     * @return 给模型的说明：root 可选择已授权的外部目录，** 支持递归
     */
    @Override
    public String description() {
        return "Find files matching a glob pattern; ** matches recursively. Optional root selects a directory and requires its read permission.";
    }

    /**
     * @return pattern 必填，root 为可选目录路径；两者均为字符串
     */
    @Override
    public Map<String, String> properties() {
        return Map.of("pattern", "string", "root", "string");
    }

    /**
     * @return 必填 [pattern]
     */
    @Override
    public List<String> required() {
        return List.of("pattern");
    }

    /**
     * 按 required() 检查缺失参数，再按指定 root 收集相对路径。
     *
     * @param input 模型参数,须含 pattern 字符串
     * @return 匹配的路径列表文本;无匹配 "(no matches)";错误 "Error: ..." 文案
     */
    @Override
    public String execute(ObjectNode input) {
        return executeOutcome(input, null).content();
    }
    /** 将匹配内容与 IO 状态分开，文件名中的 Error 不参与权限/失败判断。 */
    @Override public ToolOutcome executeOutcome(ObjectNode input, com.jadecode.execution.ExecutionContext context) {
        for (String key : required()) {
            if (input.get(key) == null) {
                return new ToolOutcome("Error: Missing required parameter: " + key, true);
            }
        }
        Path root = input.has("root") ? resolver.resolve(input.path("root").asText()) : resolver.workspace();
        return run(input.get("pattern").asText(), root);
    }

    /**
     * 从 root 收集匹配路径；不可读子树跳过并注明结果不完整，不能因沙箱遮蔽一个目录就放弃整个项目。
     * root 自身不可读和其它 IO 错误仍失败；空结果回 no matches，超过 200 条只展示前 200。
     */
    private ToolOutcome run(String pattern, Path root) {
        if (pattern.isBlank()) {
            return new ToolOutcome("Error: Pattern is empty or missing", true);
        }

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        List<String> matches = new ArrayList<>();
        boolean[] omitted = { false };
        // 不跟随链接；访问失败按条目处理，权限遮蔽点不应变成其它可读文件的可用性故障。
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                private void collect(Path path) {
                    if (!path.equals(root) && matcher.matches(root.relativize(path))) matches.add(root.relativize(path).toString());
                }
                @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) {
                    collect(path); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                    collect(path); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path path, IOException failure) throws IOException {
                    if (!path.equals(root) && failure instanceof AccessDeniedException) { omitted[0] = true; return FileVisitResult.CONTINUE; }
                    throw failure;
                }
            });
        } catch (IOException e) {
            return new ToolOutcome("Error: " + e.getMessage(), true);
        }

        // 排序后再截断保持稳定输出；同时保留权限跳过提示，不能把局部搜索宣称为全目录完整结果。
        matches.sort(String::compareTo);
        List<String> shown = matches;
        if (matches.size() > MAX_MATCHES) {
            shown = new ArrayList<>(matches.subList(0, MAX_MATCHES));
            shown.add("... (more matches omitted; narrow the pattern)");
        }
        if (shown.isEmpty()) shown.add("(no matches)");
        if (omitted[0]) shown.add("... (unreadable paths omitted)");
        return new ToolOutcome(String.join("\n", shown), false);
    }
}
