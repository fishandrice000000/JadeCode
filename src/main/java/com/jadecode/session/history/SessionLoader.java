package com.jadecode.session.history;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.jadecode.messages.*;
import com.jadecode.todo.TodoManager;
import com.jadecode.util.Json;

/**
 * 按顺序重建一次会话，不执行记录中的工具，也不恢复旧审批。
 *
 * <p>只容忍没有换行结尾的最后一段。中间坏行不能跳过：它可能恰好是一次已执行操作的结果。
 * 重建完成后才检查工具配对，只修复最后一次未闭合交换，明确说明结果未知。</p>
 */
public final class SessionLoader {
    private SessionLoader() { }

    /**
     * 解析存储层在持有写锁时读到的字节。此方法只计算候选，不修改文件。
     * @throws IOException 中间损坏、序号缺失、会话身份不符、计划非法或非末尾工具结构损坏
     */
    public static RestoredSession load(SessionMetadata metadata, byte[] bytes) throws IOException {
        List<JournalEntry> entries = new ArrayList<>();
        List<Message> history = new ArrayList<>();
        var todos = Json.mapper().createArrayNode();
        List<String> warnings = new ArrayList<>();
        long sequence = 0;
        int start = 0;
        boolean unfinished = false;
        // 清单可能在某次写盘后未能更新。用日志重建用量，不能把清单总量再加一遍。
        var usage = new com.jadecode.session.SessionUsage(ignored -> { });
        SessionMetadata rebuilt = metadata.withUsage(com.jadecode.session.SessionUsage.Totals.empty());
        // 一行只有在换行符也写入后才算完整。UTF-8 错误不能被替换字符悄悄掩盖。
        for (int end = 0; end < bytes.length; end++) {
            if (bytes[end] != '\n') continue;
            try {
                String line = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, start, end - start)).toString();
                com.fasterxml.jackson.databind.JsonNode node = Json.mapper().reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(line);
                if (node == null || !node.path("sequence").isIntegralNumber() || !node.path("version").isInt())
                    throw new IllegalArgumentException("Journal sequence or version is not an integer");
                if (node.path("kind").asText().equals("GOAL")) {
                    // Jackson 默认会把字符串/小数转成部分 Java 标量；目标日志不能靠这种宽松转换过关。
                    var goal = node.path("goal");
                    if (!goal.isObject() || !goal.path("evaluations").isIntegralNumber() || !goal.path("evaluations").canConvertToInt())
                        throw new IllegalArgumentException("Goal metadata requires an integer evaluation count");
                    for (String field : List.of("id", "condition", "status", "startedAt", "reason"))
                        if (!goal.path(field).isTextual()) throw new IllegalArgumentException("Goal metadata requires text " + field);
                }
                JournalEntry entry = Json.mapper().readerFor(JournalEntry.class)
                        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(line);
                if (!entry.sessionId().equals(metadata.id()) || entry.sequence() != sequence + 1)
                    throw new IllegalArgumentException("Journal identity mismatch or non-consecutive sequence");
                // 只恢复实际快照；用独立临时账本验证，不能部分更新调用者的 TodoManager。
                if (!entry.metadataOnly()) new TodoManager().restore(entry.todos());
                switch (entry.kind()) {
                    case APPEND -> history.add(entry.messages().getFirst());
                    case REPLACE_LAST -> {
                        if (history.isEmpty()) throw new IllegalArgumentException("Empty history cannot replace the last entry");
                        history.set(history.size() - 1, entry.messages().getFirst());
                    }
                    case REPLACE_HISTORY -> { history.clear(); history.addAll(entry.messages()); }
                    case TURN_STARTED -> unfinished = true;
                    case TURN_FINISHED, TURN_FAILED -> unfinished = false;
                    case USAGE -> usage.record(entry.usage());
                    case RENAMED -> rebuilt = rebuilt.renamed(entry.title());
                    case THINKING -> rebuilt = rebuilt.withThinking(entry.thinking());
                    // 状态由 Session.restore 采用；重放历史时不将条件变成新请求，也不自动执行。
                    case GOAL -> { }
                }
                if (!entry.metadataOnly()) todos = entry.todos();
                rebuilt = rebuilt.updated(entry.reason().equals("request") && !entry.messages().isEmpty()
                        ? entry.messages().getFirst().extractText() : null, entry.time());
                entries.add(entry); sequence++;
            } catch (Exception invalid) {
                throw new IOException("Session record " + (sequence + 1) + " is corrupted; the original file was preserved", invalid);
            }
            start = end + 1;
        }
        if (start != bytes.length) warnings.add("The last journal write was interrupted; only complete records were recovered and a recovery copy is preserved.");
        boolean repaired = repairTail(history);
        try { ToolExchangeProtocol.validateHistory(history); }
        catch (IllegalArgumentException invalid) { throw new IOException("History contains a non-terminal corrupted tool exchange and cannot be recovered automatically", invalid); }
        if (repaired) warnings.add("The previous tool result was not fully recorded; the operation may have run. An interruption note was added and the tool will not be rerun.");
        if (unfinished) warnings.add("The previous large turn did not finish normally; after recovery, new input is awaited and execution is not resumed automatically.");
        // 不为每次正常恢复添加固定提示。上面的日志中断、工具结果缺失等实际异常仍须告知用户；
        // 这里只减少界面噪声，不改变恢复范围，也不让旧进程、审批或业务文件回到过去的状态。
        // 清单可能在日志写完之后更新失败。从日志重算标题/更新时间，不让旧索引成为历史事实。
        rebuilt = rebuilt.withUsage(usage.snapshot().total());
        return new RestoredSession(rebuilt, history, todos, entries, warnings, sequence, start,
                start != bytes.length, repaired);
    }

    /**
     * 仅修复末尾缺结果/只有部分结果的交换。已有结果须按原调用顺序构成前缀，错 ID 不猜配。
     * 返回 true 说明存储层还须把这次修复写回日志，下一次恢复才能看到同一份合法历史。
     */
    private static boolean repairTail(List<Message> history) throws IOException {
        if (history.isEmpty()) return false;
        int index = history.size() - 1;
        Message last = history.get(index);
        Message calls = last.role() == Role.ASSISTANT ? last : index > 0 ? history.get(index - 1) : null;
        if (calls == null || calls.role() != Role.ASSISTANT) return false;
        var uses = calls.blocksOfType(ToolUseBlock.class);
        if (uses.isEmpty()) return false;
        var results = last == calls ? List.<ToolResultBlock>of() : last.blocksOfType(ToolResultBlock.class);
        if (results.size() >= uses.size()) return false;
        for (int i = 0; i < results.size(); i++) {
            if (!uses.get(i).id().equals(results.get(i).toolUseId())) throw new IOException("Trailing tool result ids are out of order and cannot be repaired automatically");
        }
        List<ContentBlock> blocks = new ArrayList<>(results);
        for (int i = results.size(); i < uses.size(); i++) blocks.add(new ToolResultBlock(uses.get(i).id(),
                "[Interrupted previous session: result was not recorded. The operation may already have executed; verify actual state before retrying.]", true));
        if (last != calls) last.content().stream().filter(b -> !(b instanceof ToolResultBlock)).forEach(blocks::add);
        Message replacement = Message.userBlocks(List.copyOf(blocks));
        if (last == calls) history.add(replacement);
        else history.set(index, replacement);
        return true;
    }
}
