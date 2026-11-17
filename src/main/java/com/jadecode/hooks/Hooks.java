package com.jadecode.hooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按事件名保存同步回调表，供用户提交、工具前后和轮次结束等边界调用。register 追加回调，trigger 按注册顺序执行；
 * PreToolUse、Stop 和自定义控制事件以首个非 null 文本结束，普通通知向全部订阅者广播。
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
    /** 本批工具目录提供的可信身份，不从模型 input 读取。 */
    public static final String PARAM_TOOL_IDENTITY = "toolIdentity";
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

    /** 内置通知没有控制返回值；权限检查、Stop 与自定义事件仍支持短路。 */
    private static final java.util.Set<String> NOTIFICATIONS = java.util.Set.of(USER_PROMPT_SUBMIT,
            POST_TOOL_USE, SUBAGENT_START, SUBAGENT_TOOL, SUBAGENT_END, TURN_END);

    // 每个事件名对应一组函数，按加入顺序保存。
    private final Map<String, List<Hook>> registry = new HashMap<>();

    /**
     * 一个要在特定时刻运行的函数，按 PARAM_* 名称从参数表读取信息。
     */
    @FunctionalInterface
    public interface Hook {
        /**
         * 处理当前事件的参数表。PreToolUse 的非 null 返回值为阻止原因，Stop 的非 null 返回值为续跑提示；
         * 内置通知忽略返回值，自定义事件沿用首个非 null 短路。用户提交带 query，工具事件带 block 及可选 output，
         * 子代理结束带 output 与 outcome。
         *
         * @param params 当前 Hook 事件携带的工具、上下文或结果参数表
         * @return 控制事件的阻止或续跑文本；null 表示该回调继续放行，普通通知忽略返回值
         */
        String run(Map<String, ?> params);
    }

    /**
     * 在开始任务前把回调追加到指定事件的有序列表。后续 trigger 按顺序执行，权限回调放在工具运行展示回调之前，
     * 供阻止结果先于运行状态作出。
     *
     * @param event 本次注册的 Hook 事件名，如 PreToolUse 或 Stop
     * @param hook 本事件需要按注册顺序执行的回调
     */
    public void register(String event, Hook hook) {
        if (!registry.containsKey(event)) {
            registry.put(event, new ArrayList<>()); // 注意:不能 List.of——不可变,第二个 hook 追加会抛异常
        }
        registry.get(event).add(hook);
    }

    /**
     * 装配阶段把可信控制检查放在现有扩展之前，例如权限闸门必须先于展示与用户扩展。
     * 只改变指定事件的首位，其余注册顺序不变；不执行回调，不允许运行中并发修改。
     *
     * @param event 本次注册的 Hook 事件名，如 PreToolUse 或 Stop
     * @param hook 本事件需要按注册顺序执行的回调
     */
    public void registerFirst(String event, Hook hook) {
        registry.computeIfAbsent(event, ignored -> new ArrayList<>()).addFirst(hook);
    }

    /**
     * 按注册顺序同步执行。内置通知广播全部回调，控制事件和自定义事件遇非 null 停止。
     *
     * @param event 这次发生的事件名
     * @param params 当前事件定义的参数，任务上下文按事件契约提供
     * @return 控制事件和自定义事件的首个非 null 结果；内置通知始终返回 null
     * @throws RuntimeException 某函数意外抛异常，原样交给调用者，不当作检查通过
     */
    public String trigger(String event, Map<String, ?> params) {
        List<Hook> hooks = registry.get(event);
        if (hooks == null) {
            return null; // 没有为这个事件注册函数，直接返回 null。
        }
        for (Hook hook : hooks) {
            String result = hook.run(params); // lambda 的身体在这里执行
            if (result != null && !NOTIFICATIONS.contains(event)) {
                return result; // 只有具有控制含义的结果才能终止后续回调。
            }
        }
        return null; // 通知广播完成，或者没有产生控制结果。
    }
}
