package com.jadecode.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.safety.PathGuard;

public class WriteFileTool implements Tool {
    private final PathGuard guard;

    public WriteFileTool(PathGuard guard) {
        this.guard = guard;
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "Write content to a file.";
    }

    @Override
    public Map<String, String> properties() {
        return Map.of("path", "string", "content", "string");
    }

    @Override
    public List<String> required() {
        return List.of("path", "content");
    }

    @Override
    public String execute(ObjectNode input) {
        for (String key : required()) {
            if (input.get(key) == null) {
                return "Error: Missing required parameter: " + key;
            }
        }

        return run(input.get("path").asText(), input.get("content").asText());
    }

    private String run(String path, String content) {
        Path p;

        // 1.1 检查路径是否为空
        if (path == null || path.isBlank()) {
            return "Error: Path is empty or missing";
        }

        // 1.2 检查路径是否安全
        try {
            p = guard.check(path);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }

        // 2. 创建父目录并写入文件
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, content);
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }

        // 3. 返回内容
        return "Wrote " + content.getBytes(StandardCharsets.UTF_8).length + " bytes to " + path;
    }

}
