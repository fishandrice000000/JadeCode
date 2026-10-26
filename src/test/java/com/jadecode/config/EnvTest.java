package com.jadecode.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** .env 文件解析入口已移除；保留配置快照测试，校验冻结数据与无隐式来源的边界。 */
class EnvTest {
    @Test
    void inputMapIsCopiedSoLaterChangesCannotAlterTheSnapshot() {
        Map<String, String> values = new HashMap<>(Map.of("MODEL_ID", "first-model"));
        Env snapshot = Env.of(values);
        values.put("MODEL_ID", "second-model");
        values.put("ANTHROPIC_API_KEY", "later-key");
        assertEquals("first-model", snapshot.get("MODEL_ID"));
        assertNull(snapshot.get("ANTHROPIC_API_KEY"));
    }

    @Test
    void missingValuesDoNotFallBackToTheRealProcessEnvironment() {
        Env snapshot = Env.of(Map.of());
        assertNull(snapshot.get("PATH"));
        assertNull(snapshot.get("ANTHROPIC_API_KEY"));
    }

    @Test
    void requiredValueReturnsTheSnapshotOrReportsOnlyTheMissingName() {
        Env snapshot = Env.of(Map.of("MODEL_ID", "fixture-model"));
        assertEquals("fixture-model", snapshot.required("MODEL_ID"));
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> snapshot.required("ANTHROPIC_API_KEY"));
        assertTrue(missing.getMessage().contains("ANTHROPIC_API_KEY"));
        assertFalse(missing.getMessage().contains("fixture-model"));
    }
}
