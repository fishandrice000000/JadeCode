package com.jadecode.tools;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.PathResolver;

/**
 * glob:按 glob 模式在工作区内找文件,输出匹配的相对路径(每行一条,字典序)。
 *
 * <p>Files.walk 从工作区根递归,默认不跟随符号链接目录;模式用相对路径匹配,
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
     * @return 给模型的说明:glob 模式找文件,含 ** 递归
     */
    @Override
    public String description() {
        return "Find files matching a glob pattern; ** matches recursively.";
    }

    /**
     * @return 单参数 pattern(string)
     */
    @Override
    public Map<String, String> properties() {
        return Map.of("pattern", "string");
    }

    /**
     * @return 必填 [pattern]
     */
    @Override
    public List<String> required() {
        return List.of("pattern");
    }

    /**
     * 按 required() 检查缺失参数,然后透传 {@link #run(String)}。
     *
     * @param input 模型参数,须含 pattern 字符串
     * @return 匹配的路径列表文本;无匹配 "(no matches)";错误 "Error: ..." 文案
     */
    @Override
    public String execute(ObjectNode input) {
        for (String key : required()) {
            if (input.get(key) == null) {
                return "Error: Missing required parameter: " + key;
            }
        }
        return run(input.get("pattern").asText());
    }

    /**
     * 在工作区递归里收集匹配路径:空白模式直接拒绝;IO 异常(如目录不可读)
     * 回错误文案;空结果回 "(no matches)";超过 200 条只展示前 200 并提示收窄。
     */
    private String run(String pattern) {
        if (pattern.isBlank()) {
            return "Error: Pattern is empty or missing";
        }

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        List<String> matches = new ArrayList<>();
        // Files.walk 默认不跟随符号链接目录;列举本身无副作用,路径安全由闸门在真正读写时裁决
        try (Stream<Path> paths = Files.walk(resolver.workspace())) {
            paths.forEach(p -> {
                if (p.equals(resolver.workspace())) {
                    return; // 工作区本身不是匹配对象
                }
                String rel = resolver.workspace().relativize(p).toString();
                if (matcher.matches(Path.of(rel))) {
                    matches.add(rel);
                }
            });
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }

        if (matches.isEmpty()) {
            return "(no matches)";
        }
        matches.sort(String::compareTo);
        if (matches.size() > MAX_MATCHES) {
            List<String> shown = new ArrayList<>(matches.subList(0, MAX_MATCHES));
            shown.add("... (more matches omitted; narrow the pattern)");
            return String.join("\n", shown);
        }
        return String.join("\n", matches);
    }
}
