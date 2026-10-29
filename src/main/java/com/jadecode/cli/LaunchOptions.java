package com.jadecode.cli;

import com.jadecode.safety.PermissionProfile;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * 根 CLI 的不可变启动资料：模式、首轮任务、恢复目标与本次权限覆盖。
 *
 * <p>为什么不用 picocli 的子命令：新契约里没有 run/repl 子命令，而是“位置参数即首轮任务”，
 * 还要求 {@code --resume} 只接受 {@code =} 形式、{@code --} 之后全部为字面 prompt、未知 flag
 * 必须报错且不启动任务。手写解析能精确表达这些边界，也让测试不依赖框架行为。</p>
 *
 * <p>它不读取凭据、不打开会话、不校验终端；只做纯参数判断。非法组合抛
 * {@link UsageException}，由 {@link com.jadecode.Main} 转成退出码 2。</p>
 *
 * @param mode            运行模式
 * @param initialPrompt   首轮任务原文（多位置参数以单个空格连接）；无任务时为空串
 * @param resume          裸 {@code --resume} 为空串；带值时为 id/name；未指定为 null
 * @param permission      本次运行的权限覆盖；未指定为 null（保留已保存默认 / headless 缺省 auto-review）
 * @param modelConfig     本次选用的模型 JSON 路径；null 表示全局默认，解析时不读文件
 */
public record LaunchOptions(Mode mode, String initialPrompt, String resume, PermissionProfile permission, Path modelConfig) {
    /** 根入口的四种结果。 */
    public enum Mode { INTERACTIVE, HEADLESS, HELP, VERSION }

    /** 参数错误：调用方打印到 stderr 并返回 2，不启动模型或读取配置。 */
    public static final class UsageException extends Exception {
        public UsageException(String message) { super(message); }
    }

    /** @return 是否使用无界面模式。 */
    public boolean headless() { return mode == Mode.HEADLESS; }

    /** @return 是否有需要自动提交的首轮任务。 */
    public boolean hasPrompt() { return !initialPrompt.isBlank(); }

    /**
     * 解析命令行。
     *
     * <p>顺序规则：{@code --help}/{@code --version} 优先；未知 flag 在任何位置都报错；{@code --}
     * 之后不再识别选项。位置参数按出现顺序以单个空格连接，保留参数内部已有的换行/连续空格
     * （shell 已把空白保留在单个参数里时不再 trim）。</p>
     */
    public static LaunchOptions parse(String[] args) throws UsageException {
        boolean headless = false;
        boolean help = false;
        boolean version = false;
        String resume = null;
        PermissionProfile permission = null;
        Path modelConfig = null;
        StringBuilder prompt = new StringBuilder();
        boolean literal = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i] == null ? "" : args[i];
            if (!literal && arg.equals("--")) { literal = true; continue; }
            if (!literal && (arg.equals("--help") || arg.equals("-h"))) { help = true; continue; }
            if (!literal && (arg.equals("--version") || arg.equals("-V"))) { version = true; continue; }
            if (!literal && (arg.equals("--headless"))) { headless = true; continue; }
            if (!literal && (arg.equals("--model-config") || arg.startsWith("--model-config="))) {
                if (modelConfig != null) throw new UsageException("--model-config may only be specified once");
                String value;
                if (arg.equals("--model-config")) {
                    if (i + 1 >= args.length || args[i + 1] == null || args[i + 1].startsWith("-"))
                        throw new UsageException("--model-config requires a JSON file path");
                    value = args[++i];
                } else value = arg.substring("--model-config=".length());
                if (value == null || value.isBlank()) throw new UsageException("--model-config requires a JSON file path");
                try { modelConfig = Path.of(value); }
                catch (InvalidPathException invalid) { throw new UsageException("Invalid --model-config path"); }
                continue;
            }
            if (!literal && arg.startsWith("--permission=")) {
                permission = parsePermission(arg.substring("--permission=".length()));
                continue;
            }
            if (!literal && arg.equals("--permission")) {
                if (i + 1 >= args.length) throw new UsageException("--permission requires a value");
                permission = parsePermission(args[++i]);
                continue;
            }
            if (!literal && arg.startsWith("--resume=")) {
                String value = arg.substring("--resume=".length());
                if (value.isBlank()) throw new UsageException("--resume=<id|name> must not be empty; use --resume to choose");
                if (resume != null) throw new UsageException("--resume may only be specified once");
                resume = value;
                continue;
            }
            if (!literal && arg.equals("--resume")) {
                if (resume != null) throw new UsageException("--resume may only be specified once");
                // 裸 --resume 打开选择器；不接受 `--resume <value>`，避免吞掉首轮 prompt。
                resume = "";
                continue;
            }
            if (!literal && arg.startsWith("-") && arg.length() > 1) {
                throw new UsageException("Unknown option: " + arg);
            }
            if (prompt.length() > 0) prompt.append(' ');
            prompt.append(arg);
        }
        if (help) return new LaunchOptions(Mode.HELP, "", null, null, null);
        if (version) return new LaunchOptions(Mode.VERSION, "", null, null, null);
        String text = prompt.toString();
        if (headless && (text.isBlank())) throw new UsageException("--headless requires a non-empty task");
        if (!headless && !text.isEmpty() && text.isBlank())
            throw new UsageException("The task description must not be blank");
        if (resume != null && !text.isBlank())
            throw new UsageException("--resume cannot be combined with a new task prompt");
        if (resume != null && headless)
            throw new UsageException("--resume cannot be combined with --headless");
        return new LaunchOptions(headless ? Mode.HEADLESS : Mode.INTERACTIVE, text, resume, permission, modelConfig);
    }

    private static PermissionProfile parsePermission(String value) throws UsageException {
        try {
            return PermissionProfile.parse(value);
        } catch (IllegalArgumentException invalid) {
            throw new UsageException(invalid.getMessage());
        }
    }

    /** @return 根帮助文本；程序自有文案为英文。 */
    public static String help() {
        return """
                JadeCode - a CLI coding agent

                Usage:
                  jade                     Open the UI; reconnect this workspace if already live
                  jade <prompt>            Open the UI and submit a turn
                  jade --headless <prompt> Run the task without a UI and print only the final reply
                  jade --resume            Open the UI and choose a saved session
                  jade --resume=<id|name>  Resume a saved session directly
                  java -jar jadecode.jar <same arguments>

                Options:
                  --model-config <path>    Select a model JSON file (relative to the working directory)
                                           Default: ~/.jadecode/config/model.json
                  --permission <profile>   read-only | workspace | auto-review | full-access
                                           Applies to this run only; never saved as the default.
                  --                       Treat the remaining arguments as a literal prompt
                  -h, --help               Show this help (does not start a session)
                  -V, --version            Show the version (does not start a session)

                Headless mode defaults to auto-review. Operations that still require a human are
                denied and returned to the model; stdin is never read. Exit code 0 means the
                harness finished normally, not that the business goal was achieved.
                """;
    }
}
