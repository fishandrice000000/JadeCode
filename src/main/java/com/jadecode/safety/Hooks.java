package com.jadecode.safety;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 事件表:s04 起,循环不再直接调用任何检查函数,只触发事件 */
public class Hooks {
    // 事件名常量(防手滑);事件集合本身是开放的——新事件直接 register 新名字即可
    public static final String USER_PROMPT_SUBMIT = "UserPromptSubmit";
    public static final String PRE_TOOL_USE = "PreToolUse";
    public static final String POST_TOOL_USE = "PostToolUse";
    public static final String STOP = "Stop";

    // 内置事件参数键常量(触发点与回调共用,打错字编译期抓不到,靠常量收敛)
    public static final String PARAM_QUERY = "query"; // UserPromptSubmit
    public static final String PARAM_BLOCK = "block"; // PreToolUse / PostToolUse
    public static final String PARAM_OUTPUT = "output"; // PostToolUse
    public static final String PARAM_MESSAGES = "messages"; // Stop

    // 课程同款注册表:事件名 → 回调列表。同名事件按注册顺序追加
    private final Map<String, List<Hook>> registry = new HashMap<>();

    @FunctionalInterface
    public interface Hook {
        // 参数按名取(各事件的键见上方常量):
        // UserPromptSubmit: query:String
        // PreToolUse: block:ToolUseBlock
        // PostToolUse: block:ToolUseBlock, output:String
        // Stop: messages:List<Message>
        // 返回非 null = 拦截(PreToolUse)/ 强制续跑(Stop);UserPromptSubmit / PostToolUse
        // 的返回值调用方忽略
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
