package com.jadecode.cli;

import java.nio.file.Path;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.jadecode.hooks.Hooks;
import com.jadecode.messages.ToolUseBlock;
import com.jadecode.subagent.SubagentOutcome;

/**
 * 安装 CLI 的普通展示 hook，不决定权限、不执行工具或修改消息历史。
 * 通过工厂传入的会话输出函数显示工具、计划与统计，让它们和回复带有相同的 Agent 身份。
 * CliOutput 统一排版和上色；权限 hook 仍由工厂先行注册，不因日志展示绕过权限检查。
 */
public final class CliHooks {
    private static final String TODO_WRITE_TOOL = "todo_write";

    /** 静态展示适配，无需实例化。 */
    private CliHooks() { }

    /**
     * 在会话开始前安装展示；执行时仍由原 hook 所在线程同步输出。
     * @param hooks 工厂已注册成员检查与权限检查的表，不在运行期并发修改
     * @param cwd UserPromptSubmit 要显示的工作目录，不读取或修改目录
     * @param output 属于本会话的整条输出函数；不直接写全局流，以免队友日志失去身份
     */
    public static void register(Hooks hooks, Path cwd, Consumer<String> output) {
        hooks.register(Hooks.USER_PROMPT_SUBMIT, promptContextHook(cwd, output));
        hooks.register(Hooks.PRE_TOOL_USE, logCallHook(output));
        hooks.register(Hooks.POST_TOOL_USE, largeOutputHook(output));
        hooks.register(Hooks.POST_TOOL_USE, todoStatusHook(output));
        hooks.register(Hooks.SUBAGENT_START, subagentStartHook(output));
        hooks.register(Hooks.SUBAGENT_TOOL, subagentToolHook(output));
        hooks.register(Hooks.SUBAGENT_END, subagentEndHook(output));
        hooks.register(Hooks.TURN_END, summaryHook(output));
    }

    /** UserPromptSubmit:记录当前工作目录。返回值调用方忽略 */
    private static Hooks.Hook promptContextHook(Path cwd, Consumer<String> output) {
        return params -> {
            output.accept("[Request] Working in " + cwd);
            return null;
        };
    }

    /** PreToolUse 日志:注册在权限之后 → 被拦的工具不出日志行 */
    private static Hooks.Hook logCallHook(Consumer<String> output) {
        return params -> {
            ToolUseBlock block = (ToolUseBlock) params.get(Hooks.PARAM_BLOCK);
            output.accept("[Tool] " + block.name() + callDetail(block));
            return null;
        };
    }

    /** PostToolUse:大输出提醒 */
    private static Hooks.Hook largeOutputHook(Consumer<String> display) {
        return params -> {
            ToolUseBlock block = (ToolUseBlock) params.get(Hooks.PARAM_BLOCK);
            String output = (String) params.get(Hooks.PARAM_OUTPUT);
            if (output.length() > 100000) {
                display.accept("[Warning] Large output from " + block.name());
            }
            return null;
        };
    }

    /**
     * PostToolUse 状态展示(s05 D2):todo_write 的输出就是渲染好的任务列表
     * (TodoManager.render 的文本),交给 CliOutput 用独立的 Plan 标题显示，
     * 让用户随时看得到模型的计划。工具本身保持纯净(execute 只返回字符串),
     * 终端展示全归 hook。
     */
    private static Hooks.Hook todoStatusHook(Consumer<String> display) {
        return params -> {
            ToolUseBlock block = (ToolUseBlock) params.get(Hooks.PARAM_BLOCK);
            String output = (String) params.get(Hooks.PARAM_OUTPUT);
            if (block.name().equals(TODO_WRITE_TOOL)) {
                display.accept("[Plan]\n" + output);
            }
            if (block.name().equals("bash") && output.startsWith("[Background task ")) {
                display.accept("[Background] 1 command submitted");
            }
            return null;
        };
    }

    /** SubagentStart:委派开始,洋红横幅(D2) */
    private static Hooks.Hook subagentStartHook(Consumer<String> output) {
        return params -> {
            output.accept("[Subagent started]");
            return null;
        };
    }

    /** SubagentTool：显示所属 Agent、子工具名和最多 100 个 Unicode 码点的单行摘要。 */
    private static Hooks.Hook subagentToolHook(Consumer<String> display) {
        return params -> {
            ToolUseBlock block = (ToolUseBlock) params.get(Hooks.PARAM_BLOCK);
            String output = (String) params.get(Hooks.PARAM_OUTPUT);
            // 工具正文只取一行摘要，避免多行结果把下一位 Agent 的日志挤成一段无法辨认的文字。
            display.accept("[Subagent tool] " + block.name() + ": " + singleLine(output, 100));
            return null;
        };
    }

    /** SubagentEnd:委派结束,终态决定横幅文案(D2) */
    private static Hooks.Hook subagentEndHook(Consumer<String> output) {
        return params -> {
            SubagentOutcome outcome = (SubagentOutcome) params.get(Hooks.PARAM_OUTCOME);
            String banner = switch (outcome) {
                case DONE -> "[Subagent returned]";
                case STOPPED -> "[Subagent stopped] Reached the 30-turn limit";
                case FAILED -> "[Subagent failed] " + params.get(Hooks.PARAM_OUTPUT);
            };
            output.accept(banner);
            return null;
        };
    }

    /**
     * 只展示便于分辨操作的少量参数，不把完整提示词、写入正文或 bash 命令复制到普通日志。
     * @param call 已通过成员与权限检查的调用，参数只读
     * @return 文件路径或操作对象的单行摘要；其它工具返回空串
     */
    private static String callDetail(ToolUseBlock call) {
        String key = switch (call.name()) {
            case "read_file", "write_file", "edit_file" -> "path";
            case "glob" -> "pattern";
            case "assign_task", "reopen_task", "get_task", "claim_task", "complete_task", "update_task" -> "task_id";
            case "shutdown_teammate" -> "agent_id";
            case "submit_task_result" -> "status";
            default -> "";
        };
        JsonNode input = call.input();
        if (key.isEmpty() || input == null || !input.path(key).isTextual()) return "";
        return " · " + singleLine(input.path(key).asText(), 100);
    }

    /**
     * 把日志摘要限制在一行，保留中文和其它完整 Unicode 字符，不截断代理对。
     * 控制字符换成空格，避免路径或工具输出中的换行、色码变成伪造的系统日志。
     * @param text 原始文字，不修改工具真正返回给模型的内容
     * @param max 最多展示的 Unicode 码点数量，不是字节或 token 数
     * @return 超长时带省略号的显示副本
     */
    private static String singleLine(String text, int max) {
        String clean = text.replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]", " ");
        int count = clean.codePointCount(0, clean.length());
        return count <= max ? clean : clean.substring(0, clean.offsetByCodePoints(0, max)) + "…";
    }

    /** 大轮次结束后展示独立计数；不是 Stop 判定，也不依赖历史中还保留多少结果。 */
    private static Hooks.Hook summaryHook(Consumer<String> output) {
        return params -> {
            // 使用本轮独立计数，不从可能已经被压缩的历史反推累计调用次数。
            output.accept("[Turn] " + params.get(Hooks.PARAM_TOOL_COUNT) + " tool calls in this turn");
            return null;
        };
    }

}
