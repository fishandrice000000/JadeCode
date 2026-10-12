package com.jadecode.context;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.List;

import com.jadecode.llm.LlmClient;
import com.jadecode.llm.LlmException;
import com.jadecode.messages.Message;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ContextCompactor 对外 API 可见性契约测试。
 *
 * <p>
 * 固定本次收敛的结果:loop 集成需要的外部入口保持 public;管线内部层与工程工具
 * 收敛为包内可见(同包测试仍可直接调用)。误改可见性会在这里显式失败。
 * </p>
 */
class ContextCompactorApiTest {

    /** 反射:public 方法必须存在且可见 */
    private static boolean isPublic(String name, Class<?>... parameterTypes) throws Exception {
        return Modifier.isPublic(ContextCompactor.class.getMethod(name, parameterTypes).getModifiers());
    }

    /** 反射:包内方法(仅要求"存在且不是 public") */
    private static Method declared(String name, Class<?>... parameterTypes) throws Exception {
        return ContextCompactor.class.getDeclaredMethod(name, parameterTypes);
    }

    /** 构造 API、规格常量与 loop 集成入口保持 public */
    @Test
    void loopIntegrationEntriesStayPublic() throws Exception {
        assertTrue(Modifier.isPublic(ContextCompactor.class
                .getConstructor(LlmClient.class, Path.class, Path.class).getModifiers()));
        assertTrue(Modifier.isPublic(ContextCompactor.class
                .getConstructor(LlmClient.class, Path.class, Path.class, CompactionLimits.class).getModifiers()));
        assertTrue(Modifier.isPublic(ContextCompactor.class
                .getField("MAX_REACTIVE_RETRIES").getModifiers()));

        assertTrue(isPublic("limits"));
        assertTrue(isPublic("prepare", List.class, String.class));
        // 混合 compact 批次要单独处理最新结果预算,该能力必须保持可达
        assertTrue(isPublic("toolResultBudget", List.class));
        // 手动压缩入口:纯 compact 批次走 compactAll,混合批次走 compactHistory
        assertTrue(isPublic("compactAll", List.class, String.class));
        assertTrue(isPublic("compactHistory", List.class, String.class));
        // 响应式补救与父子共用判定
        assertTrue(isPublic("reactiveCompact", List.class, String.class));
        assertTrue(isPublic("extractActiveRequest", List.class));
        assertTrue(isPublic("isContextOverflow", LlmException.class));
    }

    /** 管线内部层与工程工具不再是对外 public API */
    @Test
    void layerInternalsAreNotPublicApi() throws Exception {
        assertFalse(Modifier.isPublic(declared("snipCompact", List.class).getModifiers()));
        assertFalse(Modifier.isPublic(declared("microCompact", List.class, Integer.class).getModifiers()));
        assertFalse(Modifier.isPublic(declared("fitToolResults", List.class, int.class).getModifiers()));
        assertFalse(Modifier.isPublic(declared("persistLargeOutput", String.class, String.class).getModifiers()));
        assertFalse(Modifier.isPublic(declared("summarizeHistory", List.class).getModifiers()));
        assertFalse(Modifier.isPublic(declared("summaryInput", List.class).getModifiers()));
        assertFalse(Modifier.isPublic(declared("writeTranscript", List.class).getModifiers()));
        assertFalse(Modifier.isPublic(declared("saveOutput", String.class, String.class).getModifiers()));
        assertFalse(Modifier.isPublic(declared("persistedOutputPath", String.class).getModifiers()));
        assertFalse(Modifier.isPublic(declared("persistedPreview", String.class, String.class, int.class).getModifiers()));
        assertFalse(Modifier.isPublic(declared("isArchiveMarker", Message.class).getModifiers()));
        assertFalse(Modifier.isPublic(declared("estimateChars", List.class).getModifiers()));
        assertFalse(Modifier.isPublic(declared("hasToolUse", Message.class).getModifiers()));
        assertFalse(Modifier.isPublic(declared("isToolResult", Message.class).getModifiers()));
        assertFalse(Modifier.isPublic(declared("isClosedToolExchange", List.class, int.class).getModifiers()));
        assertFalse(Modifier.isPublic(declared("safeTailStart", List.class, int.class).getModifiers()));
        assertFalse(Modifier.isPublic(declared("safeHeadEnd", List.class, int.class).getModifiers()));
    }
}
