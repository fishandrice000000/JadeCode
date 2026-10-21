package com.jadecode.tui.completion;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 按上下文组合 slash 命令与实际路径候选。
 *
 * <p>草稿以 `/` 开头且没有空白时只给命令名；命令后带空格时给该命令参数/路径；`@` 或普通
 * 路径片段给文件候选。候选来源由注入的 provider 决定（CommandCatalog/PathCompletionProvider），
 * 本类不维护静态命令数组，也不执行任何命令。CompletableFuture 组合保持原请求身份。</p>
 */
public final class CompositeCompletionProvider implements CompletionProvider {
    private final CompletionProvider commands;
    private final CompletionProvider paths;

    public CompositeCompletionProvider(CompletionProvider commands, CompletionProvider paths) {
        this.commands = commands == null ? CompletionProvider.NONE : commands;
        this.paths = paths == null ? CompletionProvider.NONE : paths;
    }

    @Override public CompletableFuture<CompletionRequest.Result> complete(CompletionRequest.Request request) {
        String beforeCaret = request.text().substring(0, Math.min(request.caret(), request.text().length()));
        if (beforeCaret.startsWith("/") && !beforeCaret.contains(" ") && !beforeCaret.contains("\n")) {
            return commands.complete(request);
        }
        // 命令参数中同时允许路径；合并时保持命令候选优先、路径去重。
        if (beforeCaret.startsWith("/") && beforeCaret.contains(" ")) {
            int space = beforeCaret.indexOf(' ');
            String tail = beforeCaret.substring(space + 1);
            if (tail.startsWith("@") || tail.contains("/") || tail.contains(".")) {
                return paths.complete(request);
            }
            return commands.complete(request);
        }
        return paths.complete(request);
    }

    /** 合并两组候选，按 id 去重；命令在前。 */
    public static List<CompletionRequest.Candidate> merge(List<CompletionRequest.Candidate> first,
            List<CompletionRequest.Candidate> second) {
        var result = new ArrayList<CompletionRequest.Candidate>();
        var seen = new java.util.HashSet<String>();
        for (var candidate : first) if (seen.add(candidate.id())) result.add(candidate);
        for (var candidate : second) if (seen.add(candidate.id())) result.add(candidate);
        return result;
    }
}
