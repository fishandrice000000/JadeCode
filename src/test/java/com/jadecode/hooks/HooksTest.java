package com.jadecode.hooks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HooksTest {

    /** ① 注册顺序即触发顺序:先注册的先执行,全 null 时不打断 */
    @Test
    void hooksRunInRegistrationOrder() {
        Hooks hooks = new Hooks();
        List<String> calls = new ArrayList<>();
        hooks.register(Hooks.PRE_TOOL_USE, params -> { calls.add("first"); return null; });
        hooks.register(Hooks.PRE_TOOL_USE, params -> { calls.add("second"); return null; });

        assertNull(hooks.trigger(Hooks.PRE_TOOL_USE, Map.of()));
        assertEquals(List.of("first", "second"), calls);
    }

    /** ② 首个非 null 短路:return 非 null 后,后面的 hook 不再执行(PreToolUse 拦截语义) */
    @Test
    void firstNonNullShortCircuitsLaterHooks() {
        Hooks hooks = new Hooks();
        List<String> calls = new ArrayList<>();
        hooks.register(Hooks.PRE_TOOL_USE, params -> { calls.add("first"); return "blocked"; });
        hooks.register(Hooks.PRE_TOOL_USE, params -> { calls.add("second"); return null; });

        assertEquals("blocked", hooks.trigger(Hooks.PRE_TOOL_USE, Map.of()));
        assertEquals(List.of("first"), calls);
    }

    /** ③ 全 null → null;从未注册的事件 → null(合法空操作,不 NPE) */
    @Test
    void allNullOrUnregisteredEventReturnsNull() {
        Hooks hooks = new Hooks();
        hooks.register(Hooks.PRE_TOOL_USE, params -> null);

        assertNull(hooks.trigger(Hooks.PRE_TOOL_USE, Map.of()));
        assertNull(hooks.trigger("NeverRegistered", Map.of()));
    }

    /** ④ 不同事件互不相干:注册表按事件名隔离 */
    @Test
    void eventsAreIndependent() {
        Hooks hooks = new Hooks();
        hooks.register(Hooks.PRE_TOOL_USE, params -> "blocked");
        hooks.register(Hooks.STOP, params -> null);

        assertEquals("blocked", hooks.trigger(Hooks.PRE_TOOL_USE, Map.of()));
        assertNull(hooks.trigger(Hooks.STOP, Map.of()));
    }

    /** ⑤ 参数 Map 原样透传:回调收到与触发点同一个实例,不复制不包装 */
    @Test
    void paramsMapIsPassedThroughUntouched() {
        Hooks hooks = new Hooks();
        AtomicReference<Map<String, ?>> received = new AtomicReference<>();
        hooks.register(Hooks.POST_TOOL_USE, params -> { received.set(params); return null; });

        Map<String, ?> sent = Map.of(Hooks.PARAM_BLOCK, "a-block", Hooks.PARAM_OUTPUT, "out");
        hooks.trigger(Hooks.POST_TOOL_USE, sent);

        assertSame(sent, received.get());
    }

    /** ⑥ 新事件零成本:事件集合是开放的,常量表之外的新事件名直接注册即可触发,不改 Hooks 类 */
    @Test
    void newEventWorksWithoutChangingHooks() {
        Hooks hooks = new Hooks();
        hooks.register("CustomEvent", params -> (String) params.get("a") + (Integer) params.get("n"));

        Map<String, Object> params = Map.of("a", "hi", "n", 1);
        assertEquals("hi1", hooks.trigger("CustomEvent", params));
    }
}
