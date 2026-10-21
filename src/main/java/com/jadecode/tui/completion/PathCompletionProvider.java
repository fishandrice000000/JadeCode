package com.jadecode.tui.completion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * 工作区路径/@文件补全。只读取目录元数据，不读取文件全文、不联网、不执行 shell。
 *
 * <p>遍历有界（最大深度 {@value #MAX_DEPTH}、最多 {@value #MAX_ENTRIES} 项），跳过
 * `.git` 等版本库内部目录；隐藏文件保留但参与 fuzzy 过滤。候选替换范围只覆盖当前路径
 * 片段，保留引号/前导空格与光标。结果在 worker 线程计算；旧请求由 Editor 按版本丢弃，
 * 即使光标列相同、文本已变也不会串。</p>
 *
 * <p>当前工作区可能很大，本实现是有界遍历而非 Pi 的受控 fd 枚举；该回退差异记录在
 * design-deviations，搜索范围不隐藏。</p>
 */
public final class PathCompletionProvider implements CompletionProvider {
    private static final int MAX_DEPTH = 5;
    private static final int MAX_ENTRIES = 2000;
    private static final int MAX_RESULTS = 50;
    private static final java.util.Set<String> SKIP = java.util.Set.of(".git", "node_modules", "target", ".idea", ".gradle");

    private final Path root;
    private final java.util.concurrent.Executor executor;

    /** 使用工作区根目录；扫描在单线程执行器上运行，避免每次按键并发抢占。 */
    public PathCompletionProvider(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.executor = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "jade-path-completion");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override public CompletableFuture<CompletionRequest.Result> complete(CompletionRequest.Request request) {
        return CompletableFuture.supplyAsync(() -> compute(request), executor);
    }

    private CompletionRequest.Result compute(CompletionRequest.Request request) {
        String text = request.text();
        int from = request.replaceFrom(), to = Math.max(from, request.replaceTo());
        String prefix = text.substring(from, Math.min(to, text.length()));
        // 只处理最后一个路径片段；@ 前缀保留在替换范围之外。
        int slash = Math.max(prefix.lastIndexOf('/'), prefix.lastIndexOf('\\'));
        String directoryPart = slash >= 0 ? prefix.substring(0, slash + 1) : "";
        String namePart = slash >= 0 ? prefix.substring(slash + 1) : prefix;
        boolean at = directoryPart.isEmpty() && from > 0 && text.charAt(from - 1) == '@';
        Path base = root.resolve(directoryPart.startsWith("/") ? directoryPart.substring(1) : directoryPart)
                .normalize();
        if (!base.startsWith(root) || !Files.isDirectory(base)) {
            return CompletionRequest.Result.of(request, List.of());
        }
        var found = new ArrayList<CompletionRequest.Candidate>();
        try (var stream = Files.walk(base, MAX_DEPTH)) {
            var iterator = stream.iterator();
            int count = 0;
            while (iterator.hasNext() && count++ < MAX_ENTRIES) {
                Path path = iterator.next();
                if (path.equals(base)) continue;
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (relative.split("/").length > MAX_DEPTH) continue;
                if (containsSkipped(path)) continue;
                // 显示标签必须是相对工作区的完整路径；若只相对当前目录，编辑器应用候选时会
                // 丢掉用户已输入的 "src/"，把 "src/main/App.java" 替换成 "main/App.java"。
                String label = relative;
                boolean directory = Files.isDirectory(path);
                String display = directory ? label + "/" : label;
                int score = fuzzyScore(namePart, label);
                if (score < 0) continue;
                found.add(new CompletionRequest.Candidate(relative + (directory ? "/" : ""), display,
                        directory ? "directory" : (at ? "file" : "path"), from, to, false));
            }
        } catch (IOException | RuntimeException failure) {
            return CompletionRequest.Result.failed(request, "Path completion unavailable: " + failure.getMessage());
        }
        return CompletionRequest.Result.of(request, found.stream()
                .sorted(Comparator.comparingInt((CompletionRequest.Candidate candidate) -> fuzzyScore(namePart, candidate.label()))
                        .reversed().thenComparing(CompletionRequest.Candidate::label))
                .limit(MAX_RESULTS).toList());
    }

    private static boolean containsSkipped(Path path) {
        for (Path part : path) if (SKIP.contains(part.toString())) return true;
        return false;
    }

    /**
     * 简化 fuzzy：按顺序匹配查询字符，连续/开头命中得分更高；空查询全部命中。
     * @return 分数，-1 表示不匹配
     */
    public static int fuzzyScore(String query, String candidate) {
        if (query.isEmpty()) return 0;
        String needle = query.toLowerCase(Locale.ROOT), haystack = candidate.toLowerCase(Locale.ROOT);
        int score = 0, position = 0, streak = 0;
        for (int i = 0; i < needle.length(); i++) {
            int found = haystack.indexOf(needle.charAt(i), position);
            if (found < 0) return -1;
            streak = found == position ? streak + 1 : 0;
            score += 10 + streak * 5 - Math.min(5, found - position);
            position = found + 1;
        }
        if (haystack.startsWith(needle)) score += 30;
        return score;
    }

    /** 释放扫描线程；应用关闭时调用。 */
    public void close() { ((java.util.concurrent.ExecutorService) executor).shutdownNow(); }
}
