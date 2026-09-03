package com.jadecode.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.safety.PathGuard;

public class ReadFileTool implements Tool {
    private PathGuard guard;

    public ReadFileTool(PathGuard guard) {
        this.guard = guard;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "Read file contents.";
    }

    @Override
    public Map<String, String> properties() {
        return Map.of("path", "string", "limit", "integer");
    }

    @Override
    public List<String> required() {
        return List.of("path");
    }

    @Override
    public String execute(ObjectNode input) {
        for (String key : required()) {
            if (input.get(key) == null) {
                return "Error: Missing required parameter: " + key;
            }
        }

        return run(input.get("path").asText(), input.get("limit") == null ? null : input.get("limit").asInt());
    }

    private String run(String path, Integer limit) {
        List<String> lines;
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

        // 2. 读全部行
        try {
            lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }

        // 3. 超过 limit 的行数截断
        int total = lines.size();
        if (limit != null && limit > 0 && total > limit) {
            lines.subList(limit, total).clear();
            lines.add("... (" + (total - limit) + " more lines)");
        }

        // 4. 返回内容
        return String.join("\n", lines);
    }

}
