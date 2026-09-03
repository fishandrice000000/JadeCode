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
import com.jadecode.safety.PathGuard;

public class GlobTool implements Tool {
    private static final int MAX_MATCHES = 200;

    private final PathGuard guard;

    public GlobTool(PathGuard guard) {
        this.guard = guard;
    }

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public String description() {
        return "Find files matching a glob pattern; ** matches recursively.";
    }

    @Override
    public Map<String, String> properties() {
        return Map.of("pattern", "string");
    }

    @Override
    public List<String> required() {
        return List.of("pattern");
    }

    @Override
    public String execute(ObjectNode input) {
        for (String key : required()) {
            if (input.get(key) == null) {
                return "Error: Missing required parameter: " + key;
            }
        }
        return run(input.get("pattern").asText());
    }

    private String run(String pattern) {
        if (pattern.isBlank()) {
            return "Error: Pattern is empty or missing";
        }

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        List<String> matches = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(guard.workspace())) {
            paths.forEach(p -> {
                if (p.equals(guard.workspace())) {
                    return; // 工作区本身不是匹配对象
                }
                String rel = guard.workspace().relativize(p).toString();
                if (matcher.matches(Path.of(rel))) {
                    // 符号链接穿透过滤(课程同款):越界项静默跳过,不是报错
                    try {
                        guard.check(rel);
                    } catch (IllegalArgumentException e) {
                        return;
                    }
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
