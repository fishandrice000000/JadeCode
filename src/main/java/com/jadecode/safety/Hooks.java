package com.jadecode.safety;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件表:harness 的可扩展点。s04 起循环不再直接调用任何检查函数,只触发事件;
 * 内置事件见下方常量,新事件直接 register 新名字即可。
 *
 * <p>
 * 注册顺序 = 触发顺序,首个非 null 短路。返回值语义随事件而异:
 * PreToolUse 非 null = 拦截,Stop 非 null = 强制续跑,
 * UserPromptSubmit / PostToolUse / Subagent* 的返回值调用方忽略。
 * </p>
 */
public class Hooks {
    // 事件名常量(防手滑);事件集合本身是开放的——新事件直接 register 新名字即可
    public static final String USER_PROMPT_SUBMIT = "UserPromptSubmit";
    public static final String PRE_TOOL_USE = "PreToolUse";
    public static final String POST_TOOL_USE = "PostToolUse";
    public static final String STOP = "Stop";
    public static final String SUBAGENT_START = "SubagentStart";
    public static final String SUBAGENT_TOOL = "SubagentTool";
    public static final String SUBAGENT_END = "SubagentEnd";

    // 内置事件参数键常量(触发点与回调共用,打错字编译期抓不到,靠常量收敛)
    public static final String PARAM_QUERY = "query"; // UserPromptSubmit
    public static final String PARAM_BLOCK = "block"; // PreToolUse / PostToolUse
    public static final String PARAM_OUTPUT = "output"; // PostToolUse
    public static final String PARAM_MESSAGES = "messages"; // Stop
    public static final String PARAM_OUTCOME = "outcome";

    // 课程同款注册表:事件名 → 回调列表。同名事件按注册顺序追加
    private final Map<String, List<Hook>> registry = new HashMap<>();

    /** 一个事件回调。参数按名取(各事件的键见上方 PARAM_* 常量) */
    @FunctionalInterface
    public interface Hook {
        /**
         * 处理一次事件。各事件的参数与返回值语义:
         * <ul>
         * <li>UserPromptSubmit:参数 query:String;返回值忽略</li>
         * <li>PreToolUse:参数 block:ToolUseBlock;返回非 null = 拦截(作为 tool_result 回灌)</li>
         * <li>PostToolUse:参数 block:ToolUseBlock、output:String;返回值忽略</li>
         * <li>Stop:参数 messages:List&lt;Message&gt;;返回非 null = 强制续跑(作为 user 消息注入)</li>
         * <li>SubagentStart:参数无;返回值忽略</li>
         * <li>SubagentTool:参数 block:ToolUseBlock、output:String;返回值忽略</li>
         * <li>SubagentEnd:参数 output:String、outcome:SubagentOutcome;返回值忽略</li>
         * </ul>
         *
         * @param params 事件参数
         * @return 拦截 / 强制续跑的文案;放行或无关事件返回 null
         */
        String run(Map<String, ?> params);
    }

    /** 注册:往事件对应的表尾追加(注册顺序 = 触发顺序) */
    public void register(String event, Hook hook) {
        if (!registry.containsKey(event)) {
            registry.put(event, new ArrayList<>()); // 注意:不能 List.of——不可变,第二个 hook 追加会抛异常
        }
        registry.get(event).add(hook);
    }

    /** 触发:按注册顺序依次调用,首个非 null 立即短路返回;全部 null(或事件未注册)→ null */
    public String trigger(String event, Map<String, ?> params) {
        List<Hook> hooks = registry.get(event);
        if (hooks == null) {
            return null; // 事件从未注册过:没有可触发的 hook,直接放行
        }
        for (Hook hook : hooks) {
            String result = hook.run(params); // lambda 的身体在这里执行
            if (result != null) {
                return result; // 首个非 null 短路:后面的 hook 不再执行
            }
        }
        return null; // 全部返回 null → 放行
    }
}
