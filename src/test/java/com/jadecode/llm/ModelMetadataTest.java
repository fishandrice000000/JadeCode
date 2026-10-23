package com.jadecode.llm;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.jadecode.config.AppConfig;

/** 输出预算只依赖已知元数据；不读取缓存、凭据或发送请求。 */
class ModelMetadataTest {
    /** 已知上限才用于 auto；显式额度只会被压低，辅助额度不被放大。 */
    @Test void knownOutputLimitResolvesAutoAndCapsExplicitBudgets() {
        var metadata = new ModelMetadata(new ModelMetadata.Limits(1048576, null, 65536), "test");
        assertEquals(65536, metadata.formalOutputBudget(AppConfig.AUTO_MAX_TOKENS));
        assertEquals(1234, metadata.formalOutputBudget(1234));
        assertEquals(65536, metadata.formalOutputBudget(100000));
        assertEquals(2048, metadata.capOutput(2048));
    }

    /** 上下文窗口即使很大也不是输出上限，未知时只采用明确的兼容回退。 */
    @Test void missingOutputLimitFallsBackWithoutGuessingFromContext() {
        var metadata = new ModelMetadata(new ModelMetadata.Limits(1048576, null, null), "test");
        assertEquals(8000, metadata.formalOutputBudget(AppConfig.AUTO_MAX_TOKENS));
        assertEquals(32768, metadata.formalOutputBudget(32768));
        assertEquals(2048, metadata.capOutput(2048));
    }

    /** 模型限额可以小于回退值；已经解析的请求仍必须满足真实上限。 */
    @Test void aSmallKnownLimitWinsOverTheUnknownFallback() {
        var metadata = new ModelMetadata(new ModelMetadata.Limits(null, null, 1024), "test");
        assertEquals(1024, metadata.formalOutputBudget(AppConfig.AUTO_MAX_TOKENS));
        assertEquals(1024, metadata.capOutput(2048));
    }

    /** 阻止配置标记漏到实际请求；0 不等于允许 API 省略 max_tokens。 */
    @Test void invalidOrUnresolvedRequestBudgetsAreRejected() {
        var metadata = new ModelMetadata(ModelMetadata.Limits.unknown(), "test");
        assertThrows(IllegalArgumentException.class, () -> metadata.formalOutputBudget(-1));
        assertThrows(IllegalArgumentException.class, () -> metadata.capOutput(0));
        assertThrows(IllegalArgumentException.class, () -> metadata.capOutput(-1));
    }
}
