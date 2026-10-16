package com.jadecode.cli;

import java.nio.file.Path;
import java.util.List;

import com.jadecode.hooks.Hooks;
import com.jadecode.messages.Message;
import com.jadecode.messages.ToolResultBlock;
import com.jadecode.messages.ToolUseBlock;
import com.jadecode.subagent.SubagentOutcome;
import com.jadecode.util.Ansi;

/**
 * 安装 CLI 的普通展示 hook，不决定权限、不执行工具或修改消息历史。
 * 保留原终端文字、颜色、输出截断和父子展示规则；权限 hook 由工厂先行注册。
 */
public final class CliHooks {
    private static final String TODO_WRITE_TOOL = "todo_write";

    /** 静态展示适配，无需实例化。 */
    private CliHooks() { }

    /**
     * 在会话开始前安装展示；执行时仍由原 hook 所在线程同步输出。
     * @param hooks 工厂已注册成员检查与权限检查的表，不在运行期并发修改
     * @param cwd UserPromptSubmit 要显示的工作目录，不读取或修改目录
     */
    public static void register(Hooks hooks, Path cwd) {
        hooks.register(Hooks.USER_PROMPT_SUBMIT, promptContextHook(cwd));
        hooks.register(Hooks.PRE_TOOL_USE, logCallHook());
        hooks.register(Hooks.POST_TOOL_USE, largeOutputHook());
        hooks.register(Hooks.POST_TOOL_USE, todoStatusHook());
        hooks.register(Hooks.SUBAGENT_START, subagentStartHook());
        hooks.register(Hooks.SUBAGENT_TOOL, subagentToolHook());
        hooks.register(Hooks.SUBAGENT_END, subagentEndHook());
        hooks.register(Hooks.STOP, summaryHook());
    }

    /** UserPromptSubmit:记录当前工作目录。返回值调用方忽略 */
    private static Hooks.Hook promptContextHook(Path cwd) {
        return params -> {
            System.out.println(Ansi.gray("[HOOK] UserPromptSubmit: working in " + cwd));
            return null;
        };
    }

    /** PreToolUse 日志:注册在权限之后 → 被拦的工具不出日志行 */
    private static Hooks.Hook logCallHook() {
        return params -> {
            ToolUseBlock block = (ToolUseBlock) params.get(Hooks.PARAM_BLOCK);
            System.out.println(Ansi.gray("[HOOK] " + block.name()));
            return null;
        };
    }

    /** PostToolUse:大输出提醒 */
    private static Hooks.Hook largeOutputHook() {
        return params -> {
            ToolUseBlock block = (ToolUseBlock) params.get(Hooks.PARAM_BLOCK);
            String output = (String) params.get(Hooks.PARAM_OUTPUT);
            if (output.length() > 100000) {
                System.out.println(Ansi.yellow("[HOOK] WARNING: Large output from " + block.name()));
            }
            return null;
        };
    }

    /**
     * PostToolUse 状态展示(s05 D2):todo_write 的输出就是渲染好的任务列表
     * (TodoManager.render 的文本),用黄色 "## Current Tasks" 标题打在终端上,
     * 让用户随时看得到模型的计划。工具本身保持纯净(execute 只返回字符串),
     * 终端展示全归 hook。
     */
    private static Hooks.Hook todoStatusHook() {
        return params -> {
            ToolUseBlock block = (ToolUseBlock) params.get(Hooks.PARAM_BLOCK);
            String output = (String) params.get(Hooks.PARAM_OUTPUT);
            if (block.name().equals(TODO_WRITE_TOOL)) {
                System.out.println("\n" + Ansi.yellow("## Current Tasks\n" + output));
            }
            return null;
        };
    }

    /** SubagentStart:委派开始,洋红横幅(D2) */
    private static Hooks.Hook subagentStartHook() {
        return params -> {
            System.out.println(Ansi.magenta("[Subagent started]"));
            return null;
        };
    }

    /** SubagentTool:每条子工具调用后一行灰色摘要(D2);输出取前 100 字符 */
    private static Hooks.Hook subagentToolHook() {
        return params -> {
            ToolUseBlock block = (ToolUseBlock) params.get(Hooks.PARAM_BLOCK);
            String output = (String) params.get(Hooks.PARAM_OUTPUT);
            System.out.println(Ansi.gray("  [sub] " + block.name() + ": " + truncate(output, 100)));
            return null;
        };
    }

    /** SubagentEnd:委派结束,终态决定横幅文案(D2) */
    private static Hooks.Hook subagentEndHook() {
        return params -> {
            SubagentOutcome outcome = (SubagentOutcome) params.get(Hooks.PARAM_OUTCOME);
            String banner = switch (outcome) {
                case DONE -> "[Subagent done]";
                case STOPPED -> "[Subagent stopped]";
                case FAILED -> "[Subagent failed]";
            };
            System.out.println(Ansi.magenta(banner));
            return null;
        };
    }

    /** 取字符串前 max 个字符(子代理工具调用摘要用,课程同款 output[:100]) */
    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Stop:收尾统计(tool_result 计数),恒返回 null = 允许退出 */
    private static Hooks.Hook summaryHook() {
        return params -> {
            @SuppressWarnings("unchecked")
            List<Message> messages = (List<Message>) params.get(Hooks.PARAM_MESSAGES);
            long n = messages.stream().flatMap(m -> m.content().stream())
                    .filter(b -> b instanceof ToolResultBlock).count();
            System.out.println(Ansi.gray("[HOOK] Stop: session used " + n + " tool calls"));
            return null;
        };
    }

}
