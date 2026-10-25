package com.jadecode.session;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import com.jadecode.llm.ModelResponse;
import com.jadecode.llm.UsageRecord;
import com.jadecode.session.history.SessionJournal;

/**
 * 当前用户会话的用量账本。Leader、队友和辅助调用共享它，各自的消息历史仍独立。
 * record 串行保存一次事实再更新内存；UI 只读取不可变 Snapshot，不自己累加通知。
 * 不回放工具、不触发模型，也不因历史压缩撤销已经发生的调用。
 */
public final class SessionUsage {
    /** 累计的是 token 数，不是金额；输入包含缓存，缓存字段另留作分类查看。 */
    public record Totals(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens,
            long reportedRequests, long missingRequests) {
        public Totals {
            if (inputTokens < 0 || outputTokens < 0 || cacheReadTokens < 0 || cacheWriteTokens < 0 || reportedRequests < 0 || missingRequests < 0)
                throw new IllegalArgumentException("Accumulated usage cannot be negative");
        }
        public static Totals empty() { return new Totals(0, 0, 0, 0, 0, 0); }
        /** 缺报告也记一次，避免把“未报告”算成一次已报告为零的请求。 */
        public Totals add(ModelResponse.Usage usage) {
            return usage == null ? new Totals(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens,
                    reportedRequests, Math.addExact(missingRequests, 1))
                    : new Totals(Math.addExact(inputTokens, usage.totalInputTokens()), Math.addExact(outputTokens, usage.outputTokens()),
                            Math.addExact(cacheReadTokens, usage.cacheReadInputTokens()), Math.addExact(cacheWriteTokens, usage.cacheCreationInputTokens()),
                            Math.addExact(reportedRequests, 1), missingRequests);
        }
    }
    /** 分类不写入大段清单；需要时可从逐次记录重建，避免 session.json 随队友调用无限膨胀。 */
    public record Snapshot(Totals total, Map<String, Totals> byActor, Map<String, Totals> byPurpose, Map<String, Totals> byModel,
            ModelResponse.Usage recentLeaderUsage) {
        public Snapshot { byActor = Map.copyOf(byActor); byPurpose = Map.copyOf(byPurpose); byModel = Map.copyOf(byModel); }
        /** 没有逐次报告来源的旧调用不得用累计值推算最近请求命中率。 */
        public Snapshot(Totals total, Map<String, Totals> byActor, Map<String, Totals> byPurpose, Map<String, Totals> byModel) {
            this(total, byActor, byPurpose, byModel, null);
        }
    }
    private final Set<String> seen = new HashSet<>();
    private final Map<String, Totals> actors = new LinkedHashMap<>(), purposes = new LinkedHashMap<>(), models = new LinkedHashMap<>();
    private volatile Snapshot snapshot = new Snapshot(Totals.empty(), Map.of(), Map.of(), Map.of());
    private volatile boolean failed;
    private SessionJournal journal;
    private boolean closed, lateWarning;
    private final Consumer<String> warnings;

    public SessionUsage(Consumer<String> warnings) { this.warnings = java.util.Objects.requireNonNull(warnings); }
    public Snapshot snapshot() { return snapshot; }
    public boolean failed() { return failed; }

    /** 恢复只读的旧事实，不再写一次日志；必须在新请求开始前调用。 */
    public synchronized void restore(SessionJournal writer, Iterable<com.jadecode.session.history.JournalEntry> entries) {
        if (journal != null || !seen.isEmpty() || closed) throw new IllegalStateException("Usage ledger is already in use");
        for (var entry : entries) if (entry.usage() != null) adopt(entry.usage());
        journal = java.util.Objects.requireNonNull(writer);
    }

    /** 请求前检查；不能在日志已经损坏后继续付费请求而只显示一份内存累计数。 */
    public synchronized void ensureOpen() {
        if (closed || failed) throw new IllegalStateException("Session usage ledger is closed or failed to save");
    }

    /**
     * 由任意模型调用线程提交。相同 requestId 只保存一次，且不读取该线程不拥有的 Todo。
     * 关闭后迟到报告不能写进另一个会话；仅提示一次，保留“统计可能不完整”的事实。
     */
    public synchronized void record(UsageRecord record) {
        if (closed) {
            if (!lateWarning) warnings.accept("[Warning] Session is closed; late model usage was not saved. Statistics may be incomplete.");
            lateWarning = true; return;
        }
        ensureOpen();
        if (seen.contains(record.requestId())) return;
        try {
            if (journal != null) journal.usage(record);
            adopt(record);
        } catch (IOException | RuntimeException failure) {
            failed = true;
            warnings.accept("[Error] Failed to save session usage; later model requests are stopped.");
            throw new IllegalStateException("Failed to save session usage", failure);
        }
    }

    /** 更新总量与分类视图；发布快照后界面才看得到，不暴露内部可改 Map。 */
    private void adopt(UsageRecord record) {
        if (!seen.add(record.requestId())) return;
        Totals total = snapshot.total().add(record.usage());
        actors.put(record.actor(), actors.getOrDefault(record.actor(), Totals.empty()).add(record.usage()));
        String purpose = record.purpose().name();
        purposes.put(purpose, purposes.getOrDefault(purpose, Totals.empty()).add(record.usage()));
        models.put(record.model(), models.getOrDefault(record.model(), Totals.empty()).add(record.usage()));
        // 页脚 CH 只取 Leader 最近一次正式请求，Memory/队友不能覆盖它。
        // 正式请求缺报时清空旧值；restore 也经过这里，按日志事实重建而非额外保存一份统计。
        var recent = record.purpose() == UsageRecord.Purpose.FORMAL && record.actor().equals("leader")
                ? record.usage() : snapshot.recentLeaderUsage();
        snapshot = new Snapshot(total, actors, purposes, models, recent);
    }

    /** 只关闭账本，日志仍由拥有它的主 SessionRuntime 关闭并释放文件锁。 */
    public synchronized void close() { closed = true; }
}
