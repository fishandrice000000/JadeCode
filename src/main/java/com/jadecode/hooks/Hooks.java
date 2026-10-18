package com.jadecode.hooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 保存一些在特定时刻要调用的函数，比如执行工具前查权限、执行后显示结果。
 *
 * <p>register 把函数加进表，trigger 按加入顺序执行。某函数返回非 null 就不再调用后面的。
 * PreToolUse 返回文字表示拒绝这次工具调用；Stop 返回文字表示任务还没做完，
 * 要把这段提示交给模型继续；其它事件的返回文字虽然通常不用，也会使后面的函数不再执行。</p>
 *
 * <p>这些函数在调用 trigger 的同一线程执行，不是任务队列，也不自动开线程。
 * 父 history 由 SessionRuntime 修改，子 Agent 修改自己的消息列表；
 * hook 只检查参数或显示信息，不应直接给 history 插消息。</p>
 */
public class Hooks {
    // 用常量统一事件名，避免拼错；需要新事件时也可以注册新名字。
    public static final String USER_PROMPT_SUBMIT = "UserPromptSubmit";
    public static final String PRE_TOOL_USE = "PreToolUse";
    public static final String POST_TOOL_USE = "PostToolUse";
    public static final String STOP = "Stop";
    public static final String SUBAGENT_START = "SubagentStart";
    public static final String SUBAGENT_TOOL = "SubagentTool";
    public static final String SUBAGENT_END = "SubagentEnd";
    /** 大轮次实际结束后用于展示计数，不参与 Stop 是否续跑的决策。 */
    public static final String TURN_END = "TurnEnd";
    public static final String PARAM_TOOL_COUNT = "toolCount";

    // 调用者和 hook 都用这些参数名，免得一边传 block，另一边却按别的名字读取。
    public static final String PARAM_QUERY = "query"; // UserPromptSubmit
    public static final String PARAM_BLOCK = "block"; // PreToolUse / PostToolUse
    public static final String PARAM_OUTPUT = "output"; // PostToolUse
    public static final String PARAM_MESSAGES = "messages"; // Stop
    public static final String PARAM_OUTCOME = "outcome";
    /**
     * 程序传来的 ExecutionContext，用来判断任务来自用户还是定时器，不从工具 input 读取。
     *
     * <p>父子 Agent 执行工具前都会传；父 Agent 执行后和收到用户请求时也可以传。
     * 不是每个 hook 都有它，例如 Stop 只传消息列表，写函数时不能默认此参数总存在。</p>
     */
    public static final String PARAM_CONTEXT = "executionContext";

    // 每个事件名对应一组函数，按加入顺序保存。
    private final Map<String, List<Hook>> registry = new HashMap<>();

    /**
     * 一个要在特定时刻运行的函数，按 PARAM_* 名称从参数表读取信息。
     */
    @FunctionalInterface
    public interface Hook {
        /**
         * 处理一次通知。调用者传什么参数、返回文字怎么用，取决于事件名。
         *
         * <ul>
         * <li>UserPromptSubmit：query 是用户请求，CLI 还传 executionContext；返回文字不使用。</li>
         * <li>PreToolUse：block 是 ToolUseBlock，CLI 父子 Agent 还传 executionContext；
         * 返回文字就阻止工具，把原因作为 tool_result 发给模型。</li>
         * <li>PostToolUse：block 和 output 是调用及结果，只展示或记录。</li>
         * <li>Stop：messages 是当前历史；返回文字就让模型继续，不返回则不再要求继续。</li>
         * <li>SubagentStart：没有参数。</li>
         * <li>SubagentTool：block 和 output 是子工具调用及结果。</li>
         * <li>SubagentEnd：output 是最终文字，outcome 是 DONE/STOPPED/FAILED。</li>
         * </ul>
         * @param params 当前事件的参数表，只读，不保证每次都有 executionContext
         * @return 拒绝原因或让模型继续的提示；其它情况返回 null，避免阻止后面的函数
         */
        String run(Map<String, ?> params);
    }

    /**
     * 把一个函数加在指定事件的最后，之后 trigger 时才执行。
     *
     * <p>应在开始任务前注册。这个表不是线程安全的，不要边执行边从其它线程增加函数。
     * 顺序会影响结果：权限检查要放在“调用了哪个工具”的日志前面，免得被拒绝也显示成执行过。</p>
     * @param event 事件名，也可以新增名字，但调用者要讲清参数和返回文字的用途
     * @param hook 要执行的函数，不会立即调用
     */
    public void register(String event, Hook hook) {
        if (!registry.containsKey(event)) {
            registry.put(event, new ArrayList<>()); // 注意:不能 List.of——不可变,第二个 hook 追加会抛异常
        }
        registry.get(event).add(hook);
    }

    /**
     * 按注册顺序在当前线程执行函数，遇到第一个非 null 返回值就停止。
     * @param event 这次发生的事件名
     * @param params 给函数读取的参数，不保证每个事件都有任务信息
     * @return 第一段非 null 文字；没有函数或全返回 null 时为 null，怎样使用由调用者决定
     * @throws RuntimeException 某函数意外抛异常，原样交给调用者，不当作检查通过
     */
    public String trigger(String event, Map<String, ?> params) {
        List<Hook> hooks = registry.get(event);
        if (hooks == null) {
            return null; // 没有为这个事件注册函数，直接返回 null。
        }
        for (Hook hook : hooks) {
            String result = hook.run(params); // lambda 的身体在这里执行
            if (result != null) {
                return result; // 已经返回了处理意见，不再运行后面的函数。
            }
        }
        return null; // 所有函数都没返回处理意见。
    }
}
