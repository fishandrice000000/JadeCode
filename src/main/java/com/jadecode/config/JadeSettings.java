package com.jadecode.config;

import com.jadecode.context.CompactionLimits;
import com.jadecode.context.CompactionPolicy;

/**
 * 一次启动采用的非秘密配置快照。前端与运行装配共享它，不在每个请求中反复读配置文件。
 * 模型、认证和 endpoint 继续由 AppConfig 管理，不因统一 UI／压缩设置而搬动凭据。
 */
public record JadeSettings(Tui tui, CompactionPolicy compaction, CompactionLimits limits, Memory memory) {
    /** 兼容已有离线装配；未指定记忆设置时使用相同默认值。 */
    public JadeSettings(Tui tui, CompactionPolicy compaction, CompactionLimits limits) {
        this(tui, compaction, limits, new Memory(8000));
    }
    /** 仅是界面的初始选择；本次运行中的快捷键切换不会改写这些默认值。 */
    public record Tui(String theme, boolean thinkingVisible, boolean toolsExpanded, boolean commandSuggestions) { }

    /** 辅助提取请求的最大输出额度，单位 token；不改变主任务 MAX_TOKENS。 */
    public record Memory(int extractionMaxOutputTokens) {
        public Memory { if (extractionMaxOutputTokens < 1) throw new IllegalArgumentException("memory.extractionMaxOutputTokens 必须为正整数"); }
    }

    /** 无配置文件或某项未填写时使用；离线装配也从此处取得与机器无关的默认值。 */
    public static JadeSettings defaults() {
        return new JadeSettings(new Tui("dark", true, false, true), CompactionPolicy.defaults(), CompactionLimits.defaults());
    }
}
