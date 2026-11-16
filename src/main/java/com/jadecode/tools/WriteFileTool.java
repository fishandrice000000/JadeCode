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
 * write_file:把整段内容写入文件(UTF-8,覆盖式)。
 *
 * <p>父目录不存在会自动创建(createDirectories);写入成功后回给模型
 * 内容字节数与目标路径。路径解析交给 PathResolver，正式调用前由安全闸门裁决是否允许访问。</p>
 */
public class WriteFileTool implements Tool {
    /** 路径解析器:解析目标路径;越界与否由闸门裁决 */
    private final PathResolver resolver;

    /**
     * @param resolver 路径解析器，由 JadeCodeFactory 装配时注入
     */
    public WriteFileTool(PathResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * @return 工具名 "write_file"
     */
    @Override
    public String name() {
        return "write_file";
    }

    /**
     * @return 给模型的说明:把内容写进文件
     */
    @Override
    public String description() {
        return "Write content to a file.";
    }

    /**
     * @return 参数表:path(string)、content(string),均必填
     */

    private Map<String, String> properties() {
        return Map.of("path", "string", "content", "string");
    }

    /**
     * @return 必填 [path, content]
     */

    private List<String> required() {
        return List.of("path", "content");
    }

    /** 保存实际 IO 状态，宿主与容器使用同一份实现。 * @param context Harness 提供的任务上下文；不从模型参数读取，离线只读调用可按工具契约传 null
     * @param input 模型参数对象，只读取；具体字段由工具自行校验
     */
    @Override
    public ToolOutcome executeOutcome(ObjectNode input, com.jadecode.execution.ExecutionContext context) {
        for (String key : required()) {
            if (input.get(key) == null) {
                return new ToolOutcome("Error: Missing required parameter: " + key, true);
            }
        }

        return run(input.get("path").asText(), input.get("content").asText());
    }

    /**
     * 建父目录并写文件;空路径直接拒绝,IO 失败(如无权限)回 "Error: ..."。
     */
    private ToolOutcome run(String path, String content) {
        // 1.1 检查路径是否为空
        if (path == null || path.isBlank()) {
            return new ToolOutcome("Error: Path is empty or missing", true);
        }

        // 1.2 解析路径(词法 + 逐段符号链接);是否越界由闸门裁决
        Path p = resolver.resolve(path);

        // 2. 创建父目录并写入文件
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, content);
        } catch (IOException e) {
            return new ToolOutcome("Error: " + e.getMessage(), true);
        }

        // 3. 返回内容
        return new ToolOutcome("Wrote " + content.getBytes(StandardCharsets.UTF_8).length + " bytes to " + path, false);
    }

    /** 提供完整参数结构；标量构造器只负责组装 Schema，不授予执行权限。 */
    @Override
    public com.fasterxml.jackson.databind.node.ObjectNode inputSchema() {
        return com.jadecode.tools.ToolSchema.objectSchema(properties(), required());
    }
}
