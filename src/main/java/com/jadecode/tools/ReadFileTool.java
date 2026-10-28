package com.jadecode.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.PathResolver;

/**
 * read_file:按 UTF-8 读文本文件,返回内容(逐行 join);可选 limit 截断行数。
 *
 * <p>路径解析交给 PathResolver，正式调用前由安全闸门裁决是否允许访问；本工具按解析结果读文件；
 * 读不到(不存在/无权限)回 "Error: ..." 文案。</p>
 */
public class ReadFileTool implements Tool {
    /** 路径解析器:解析目标路径;越界与否由闸门裁决 */
    private final PathResolver resolver;

    /**
     * @param resolver 路径解析器，由 JadeCodeFactory 装配时注入
     */
    public ReadFileTool(PathResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * @return 工具名 "read_file"
     */
    @Override
    public String name() {
        return "read_file";
    }

    /**
     * @return 给模型的说明:读文件内容
     */
    @Override
    public String description() {
        return "Read file contents.";
    }

    /**
     * @return 参数表:path(string,必填)、limit(integer,可选,只读前 N 行)
     */
    @Override
    public Map<String, String> properties() {
        return Map.of("path", "string", "limit", "integer");
    }

    /**
     * @return 必填 [path];limit 缺省读全文
     */
    @Override
    public List<String> required() {
        return List.of("path");
    }

    /**
     * 按 required() 检查缺失参数,然后透传 {@link #run(String, Integer)}。
     *
     * @param input 模型参数,须含 path;limit 可选
     * @return 文件文本;错误 "Error: ..." 文案
     */
    @Override
    public String execute(ObjectNode input) {
        return executeOutcome(input, null).content();
    }

    /** 文件正文可能以 Error 开头，状态必须来自实际 IO 结果而不是内容关键词。 */
    @Override public ToolOutcome executeOutcome(ObjectNode input, com.jadecode.execution.ExecutionContext context) {
        for (String key : required()) {
            if (input.get(key) == null) {
                return new ToolOutcome("Error: Missing required parameter: " + key, true);
            }
        }

        return run(input.get("path").asText(), input.get("limit") == null ? null : input.get("limit").asInt());
    }

    /**
     * 读文件并应用截断:仅当 limit 非 null 且大于 0、行数确实超出时才截,
     * 尾部补一行说明省略了多少行;空路径或 IO 失败走 "Error: ..."。
     */
    private ToolOutcome run(String path, Integer limit) {
        List<String> lines;
        // 1.1 检查路径是否为空
        if (path == null || path.isBlank()) {
            return new ToolOutcome("Error: Path is empty or missing", true);
        }

        // 1.2 解析路径(词法 + 逐段符号链接);是否越界由闸门裁决
        Path p = resolver.resolve(path);

        // 2. 读全部行
        try {
            lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new ToolOutcome("Error: " + e.getMessage(), true);
        }

        // 3. 超过 limit 的行数截断
        int total = lines.size();
        if (limit != null && limit > 0 && total > limit) {
            lines.subList(limit, total).clear();
            lines.add("... (" + (total - limit) + " more lines)");
        }

        // 4. 返回内容
        return new ToolOutcome(String.join("\n", lines), false);
    }

}
