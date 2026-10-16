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
 *
 * <p>这里的“事件”是同步扩展点，不是 SessionInbox 的新任务或异步消费者。
 * trigger 在调用者线程依次执行回调，通常用于审批、日志及 Stop 续跑判定；父会话历史采用
 * 仍由 SessionRuntime 负责，子代理维护自己的局部历史。回调应遵守参数只读约定，
 * 不能自行往历史插入消息。</p>
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
    /**
     * Harness 提供的 ExecutionContext，不从 tool_use.input 中提取，模型不能伪造来源。
     * 父/子 PreToolUse 依此检查审批；父 PostToolUse 和人工入口也可携带。不是所有事件都含
     * 此键，例如 Stop 只看历史，不能因日志/hook 使用同一表就假定上下文总存在。
     */
    public static final String PARAM_CONTEXT = "executionContext";

    // 课程同款注册表:事件名 → 回调列表。同名事件按注册顺序追加
    private final Map<String, List<Hook>> registry = new HashMap<>();

    /** 一个事件回调。参数按名取(各事件的键见上方 PARAM_* 常量) */
    @FunctionalInterface
    public interface Hook {
        /**
         * 处理一次事件。各事件的参数与返回值语义:
         * <ul>
         * <li>UserPromptSubmit:参数 query:String、生产人工入口的 executionContext;返回值忽略</li>
         * <li>PreToolUse:参数 block:ToolUseBlock、生产执行入口的 executionContext;
         * 返回非 null = 拦截(作为 tool_result 回灌)</li>
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

    /**
     * 在装配阶段向指定事件的回调末尾追加，不立即触发。
     * 注册表不是并发容器，执行开始后不应从其它线程动态改写。
     * @param event 事件名，开放扩展，但其参数/返回语义须由触发点约定
     * @param hook 同步回调；顺序影响短路，权限回调应先于“已执行”日志
     */
    public void register(String event, Hook hook) {
        if (!registry.containsKey(event)) {
            registry.put(event, new ArrayList<>()); // 注意:不能 List.of——不可变,第二个 hook 追加会抛异常
        }
        registry.get(event).add(hook);
    }

    /**
     * 在当前线程按注册顺序调用，首个非 null 立即短路，不启动线程或修改历史。
     * @param event 需要触发的扩展点名称
     * @param params 该触发点约定的参数，不保证每个事件都有 ExecutionContext
     * @return 第一个非 null 结果；全为 null 或没有注册回调时为 null，含义由调用点决定
     * @throws RuntimeException 回调意外失败时原样传播，本表不将失败伪装成放行
     */
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
