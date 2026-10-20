package com.jadecode.config;

import com.jadecode.context.CompactionLimits;
import com.jadecode.context.CompactionPolicy;
import com.jadecode.llm.ModelMetadata.Limits;
import java.util.Map;

/**
 * 一次启动采用的非秘密配置快照。前端与运行装配共享它，不在每个请求中反复读配置文件。
 * 模型、认证和 endpoint 继续由 AppConfig 管理，不因统一 UI／压缩设置而搬动凭据。
 */
public record JadeSettings(Tui tui, CompactionPolicy compaction, CompactionLimits limits, Memory memory, Models modelMetadata,
        com.jadecode.llm.ThinkingSettings thinking, Permissions permissions) {
    /** 旧装配保持 Workspace 默认，不因新增配置而读取用户文件。 */
    public JadeSettings(Tui tui, CompactionPolicy compaction, CompactionLimits limits, Memory memory, Models models,
            com.jadecode.llm.ThinkingSettings thinking) {
        this(tui, compaction, limits, memory, models, thinking, new Permissions(com.jadecode.safety.PermissionProfile.WORKSPACE, java.util.List.of(), java.util.Set.of()));
    }
    /** 老配置/离线装配缺少思考设置时不改变服务商默认行为。 */
    public JadeSettings(Tui tui, CompactionPolicy compaction, CompactionLimits limits, Memory memory, Models modelMetadata) {
        this(tui, compaction, limits, memory, modelMetadata, com.jadecode.llm.ThinkingSettings.DEFAULT);
    }
    public JadeSettings { java.util.Objects.requireNonNull(thinking, "thinking"); java.util.Objects.requireNonNull(permissions, "permissions"); }
    /** 启动默认与长期规则；不能在模型参数中伪造这份配置。 */
    public record Permissions(com.jadecode.safety.PermissionProfile defaultProfile,
            java.util.List<com.jadecode.safety.rules.PermissionRule> rules, java.util.Set<String> disabledDefaults) {
        public Permissions {
            java.util.Objects.requireNonNull(defaultProfile); rules = java.util.List.copyOf(rules); disabledDefaults = java.util.Set.copyOf(disabledDefaults);
            if (!com.jadecode.safety.rules.DefaultPermissionRules.IDS.containsAll(disabledDefaults)) throw new IllegalArgumentException("未知默认权限规则编号");
            if (rules.stream().map(com.jadecode.safety.rules.PermissionRule::id).distinct().count() != rules.size()) throw new IllegalArgumentException("权限规则 id 重复");
        }
        public com.jadecode.safety.PermissionState state(com.jadecode.safety.rules.PermissionRuleStore store) {
            return new com.jadecode.safety.PermissionState(defaultProfile, rules, disabledDefaults, store);
        }
    }
    /** 兼容已有离线装配；未指定记忆设置时使用相同默认值。 */
    public JadeSettings(Tui tui, CompactionPolicy compaction, CompactionLimits limits) {
        this(tui, compaction, limits, new Memory(8000));
    }
    /** 不指定参数发现设置的旧调用仍使用默认值；离线工厂不会因此自行联网。 */
    public JadeSettings(Tui tui, CompactionPolicy compaction, CompactionLimits limits, Memory memory) {
        this(tui, compaction, limits, memory, new Models(true, 24, 2000, Map.of()));
    }
    /** 仅是界面的初始选择；本次运行中的快捷键切换不会改写这些默认值。 */
    public record Tui(String theme, boolean thinkingVisible, boolean toolsExpanded, boolean commandSuggestions) { }

    /**
     * 记忆辅助请求的独立额度；token 是输出上限，char 是 UTF-16 输入预算，不改变正式请求。
     * enabled=false 只停用自动召回/提取/整理，不删除已有知识；缺省保持启用。
     */
    public record Memory(boolean enabled, int extractionMaxOutputTokens, int recallMaxOutputTokens,
            int consolidationMaxOutputTokens, int consolidationInputChars, int extractionCatalogChars) {
        /** 旧装配只指定提取额度时，为其余设置提供与生产相同的默认值。 */
        public Memory(int extractionMaxOutputTokens) { this(true, extractionMaxOutputTokens, 2048, 16384, 128000, 128000); }
        public Memory {
            if (extractionMaxOutputTokens < 1 || recallMaxOutputTokens < 1 || consolidationMaxOutputTokens < 1
                    || consolidationInputChars < 1 || extractionCatalogChars < 1)
                throw new IllegalArgumentException("memory 输出与输入额度必须为正整数");
        }
    }

    /**
     * 参数发现只在生产启动执行；overrides 按精确模型 ID 区分，换模型后不套用旧覆盖。
     * 手动值只影响限额，不选择 provider、模型或认证。映射复制后不允许运行中修改。
     */
    public record Models(boolean autoDiscover, int cacheTtlHours, int timeoutMillis, Map<String, Limits> overrides) {
        public Models {
            if (cacheTtlHours < 1 || timeoutMillis < 1) throw new IllegalArgumentException("模型缓存时效和超时必须为正整数");
            overrides = Map.copyOf(overrides);
        }
    }

    /** 无配置文件或某项未填写时使用；离线装配也从此处取得与机器无关的默认值。 */
    public static JadeSettings defaults() {
        return new JadeSettings(new Tui("dark", true, false, true), CompactionPolicy.defaults(), CompactionLimits.defaults());
    }
}
