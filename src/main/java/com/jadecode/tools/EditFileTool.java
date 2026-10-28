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
 * edit_file:在文件里做精确文本替换,只替换第一处匹配。
 *
 * <p>old_text 找不到时整次调用失败并回 "Error: Text not found in ..."——
 * 找不到匹配时文件保持原样；普通整写并非原子替换，写入中的 IO 故障可能留下部分结果。</p>
 */
public class EditFileTool implements Tool {
    /** 路径解析器:解析目标路径;越界与否由闸门裁决 */
    private final PathResolver resolver;

    /**
     * @param resolver 路径解析器，由 JadeCodeFactory 装配时注入
     */
    public EditFileTool(PathResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * @return 工具名 "edit_file"
     */
    @Override
    public String name() {
        return "edit_file";
    }

    /**
     * @return 给模型的说明:精确替换一处文本
     */
    @Override
    public String description() {
        return "Replace exact text in a file once.";
    }

    /**
     * @return 参数表:path / old_text / new_text,均为 string 且必填
     */
    @Override
    public Map<String, String> properties() {
        return Map.of("path", "string", "old_text", "string", "new_text", "string");
    }

    /**
     * @return 必填 [path, old_text, new_text]
     */
    @Override
    public List<String> required() {
        return List.of("path", "old_text", "new_text");
    }

    /**
     * 按 required() 检查缺失参数,然后透传 {@link #run(String, String, String)}。
     *
     * @param input 模型参数,须含 path / old_text / new_text
     * @return 成功:"Edited {path}";错误 "Error: ..." 文案
     */
    @Override
    public String execute(ObjectNode input) {
        return executeOutcome(input, null).content();
    }
    /** 查找失败和 IO 失败单独标记，已发生的写入不伪称回滚。 */
    @Override public ToolOutcome executeOutcome(ObjectNode input, com.jadecode.execution.ExecutionContext context) {
        for (String key : required()) {
            if (input.get(key) == null) {
                return new ToolOutcome("Error: Missing required parameter: " + key, true);
            }
        }

        return run(input.get("path").asText(), input.get("old_text").asText(), input.get("new_text").asText());
    }

    /**
     * 读整文件 → 找第一处 old_text → 拼新文本 → 整写回。
     * 找不到匹配或读取失败时不写文件；写入阶段失败会返回错误，但不保证回滚部分写入。
     */
    private ToolOutcome run(String path, String oldText, String newText) {
        // 1.1 检查路径是否为空
        if (path == null || path.isBlank()) {
            return new ToolOutcome("Error: Path is empty or missing", true);
        }

        // 1.2 解析路径(词法 + 逐段符号链接);是否越界由闸门裁决
        Path p = resolver.resolve(path);

        // 2. 读文件, 找 oldText
        String text;
        try {
            text = Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new ToolOutcome("Error: " + e.getMessage(), true);
        }

        int idx = text.indexOf(oldText);
        if (idx < 0) {
            return new ToolOutcome("Error: Text not found in " + path, true);
        }

        // 3. 构建替换后的字符串
        String edited = text.substring(0, idx) + newText + text.substring(idx + oldText.length());

        // 4. 写回再返回
        try {
            Files.writeString(p, edited, StandardCharsets.UTF_8);
            return new ToolOutcome("Edited " + path, false);
        } catch (IOException e) {
            return new ToolOutcome("Error: " + e.getMessage(), true);
        }
    }

}
