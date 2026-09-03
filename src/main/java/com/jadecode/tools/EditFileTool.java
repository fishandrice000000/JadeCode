package com.jadecode.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.safety.PathGuard;

public class EditFileTool implements Tool {
    private final PathGuard guard;

    public EditFileTool(PathGuard guard) {
        this.guard = guard;
    }

    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public String description() {
        return "Replace exact text in a file once.";
    }

    @Override
    public Map<String, String> properties() {
        return Map.of("path", "string", "old_text", "string", "new_text", "string");
    }

    @Override
    public List<String> required() {
        return List.of("path", "old_text", "new_text");
    }

    @Override
    public String execute(ObjectNode input) {
        for (String key : required()) {
            if (input.get(key) == null) {
                return "Error: Missing required parameter: " + key;
            }
        }

        return run(input.get("path").asText(), input.get("old_text").asText(), input.get("new_text").asText());
    }

    private String run(String path, String oldText, String newText) {
        // 1.1 检查路径是否为空
        if (path == null || path.isBlank()) {
            return "Error: Path is empty or missing";
        }

        // 1.2 检查路径是否安全
        Path p;
        try {
            p = guard.check(path);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }

        // 2. 读文件, 找 oldText
        String text;
        try {
            text = Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }

        int idx = text.indexOf(oldText);
        if (idx < 0) {
            return "Error: Text not found in " + path;
        }

        // 3. 构建替换后的字符串
        String edited = text.substring(0, idx) + newText + text.substring(idx + oldText.length());

        // 4. 写回再返回
        try {
            Files.writeString(p, edited, StandardCharsets.UTF_8);
            return "Edited " + path;
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

}
