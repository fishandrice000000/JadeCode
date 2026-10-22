package com.jadecode.cli.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import com.jadecode.tui.completion.CompletionProvider;
import com.jadecode.tui.completion.CompletionRequest;
import com.jadecode.tui.completion.PathCompletionProvider;

/**
 * 本地命令的唯一目录：实际执行、补全、参数帮助、可见性来自同一张表。
 *
 * <p>只登记 JadeCode 当前有真实后端的命令；旧 `/help`、`/exit`、`/usage`、`/theme`、`/rename`
 * 等别名已按 C21 移除。未知 slash 由控制器返回本地英文错误并保留输入，绝不偷偷发给模型。
 * 补全 provider 从本表生成命令名候选；不含不存在的命令或后端管理菜单。</p>
 */
public final class CommandCatalog {
    /**
     * 一个命令。
     * @param name 含 `/` 的规范名
     * @param arguments 参数提示（不含命令名）
     * @param description 英文说明
     * @param takesArguments 是否接受参数
     */
    public record Command(String name, String arguments, String description, boolean takesArguments) {
        /** @return 帮助行。 */
        public String usage() { return arguments.isBlank() ? name : name + " " + arguments; }
    }

    private static final List<Command> COMMANDS = List.of(
            new Command("/settings", "", "Open TUI settings and theme/mode menus", false),
            new Command("/name", "[title]", "Rename the current session", true),
            new Command("/session", "", "Show session identity and usage", false),
            new Command("/hotkeys", "", "Show keys and available commands", false),
            new Command("/copy", "", "Copy the latest assistant reply", false),
            new Command("/new", "", "Start a new session", false),
            new Command("/resume", "[id|name]", "Resume a session in this workspace", true),
            new Command("/quit", "", "Close the session and exit", false),
            new Command("/model", "", "Show model metadata (read-only)", false),
            new Command("/thinking", "[level]", "Change the thinking level for this session", true),
            new Command("/permission", "[profile]", "Change permission profile", true),
            new Command("/mcp", "", "Show MCP servers and tools (read-only)", false));

    private final CompletionProvider pathCompletion;

    public CommandCatalog(java.nio.file.Path workspace) {
        this.pathCompletion = new PathCompletionProvider(workspace);
    }

    /** @return 全部可见命令。 */
    public List<Command> commands() { return COMMANDS; }

    /** @return 按规范名查找；未知 null。 */
    public Command find(String name) {
        for (Command command : COMMANDS) if (command.name().equals(name)) return command;
        return null;
    }

    /** @return 是否为本目录内的命令；不包含旧别名。 */
    public boolean isKnown(String name) { return find(name) != null; }

    /** @return 帮助行（含用法与英文说明）。 */
    public List<String> helpLines() {
        var lines = new ArrayList<String>();
        for (Command command : COMMANDS) lines.add(command.usage() + " · " + command.description());
        return lines;
    }

    /** @return 命令名补全；参数路径候选只在命令接受参数时提供。 */
    public CompletionProvider completionProvider() {
        return request -> {
            String text = request.text();
            int from = request.replaceFrom(), to = request.replaceTo();
            String token = text.substring(Math.max(0, from), Math.min(to, text.length()));
            if (text.startsWith("/") && !text.contains(" ")) {
                var candidates = new ArrayList<CompletionRequest.Candidate>();
                for (Command command : COMMANDS) {
                    if (com.jadecode.tui.completion.PathCompletionProvider.fuzzyScore(token, command.name()) < 0) continue;
                    candidates.add(new CompletionRequest.Candidate(command.name(), command.name() + " ",
                            command.description(), from, to, true));
                }
                return CompletableFuture.completedFuture(CompletionRequest.Result.of(request, candidates));
            }
            // 只有真实支持路径/名称参数的命令才请求路径候选；普通命令参数仍可手输。
            Command command = COMMANDS.stream().filter(item -> text.startsWith(item.name() + " ")).findFirst().orElse(null);
            if (command == null || !command.takesArguments())
                return CompletableFuture.completedFuture(CompletionRequest.Result.of(request, List.of()));
            return pathCompletion.complete(request);
        };
    }

    /** 释放路径扫描线程。 */
    public void close() {
        if (pathCompletion instanceof PathCompletionProvider provider) provider.close();
    }
}
