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
        // 一行只有在换行符也写入后才算完整。UTF-8 错误不能被替换字符悄悄掩盖。
        for (int end = 0; end < bytes.length; end++) {
            if (bytes[end] != '\n') continue;
            try {
                String line = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, start, end - start)).toString();
                com.fasterxml.jackson.databind.JsonNode node = Json.mapper().reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(line);
                if (node == null || !node.path("sequence").isIntegralNumber() || !node.path("version").isInt())
                    throw new IllegalArgumentException("日志序号或版本不是整数");
                JournalEntry entry = Json.mapper().readerFor(JournalEntry.class)
                        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(line);
                if (!entry.sessionId().equals(metadata.id()) || entry.sequence() != sequence + 1)
                    throw new IllegalArgumentException("日志身份不符或序号不连续");
                // 只恢复实际快照；用独立临时账本验证，不能部分更新调用者的 TodoManager。
                new TodoManager().restore(entry.todos());
                switch (entry.kind()) {
                    case APPEND -> history.add(entry.messages().getFirst());
                    case REPLACE_LAST -> {
                        if (history.isEmpty()) throw new IllegalArgumentException("空历史不能替换末条");
                        history.set(history.size() - 1, entry.messages().getFirst());
                    }
                    case REPLACE_HISTORY -> { history.clear(); history.addAll(entry.messages()); }
                    case TURN_STARTED -> unfinished = true;
                    case TURN_FINISHED, TURN_FAILED -> unfinished = false;
                }
                todos = entry.todos(); entries.add(entry); sequence++;
            } catch (Exception invalid) {
                throw new IOException("会话第 " + (sequence + 1) + " 条记录损坏，已保留原文件", invalid);
            }
            start = end + 1;
        }
        if (start != bytes.length) warnings.add("上次日志末尾写入中断；仅恢复完整记录，原文件将保留恢复副本。");
        boolean repaired = repairTail(history);
        try { ToolExchangeProtocol.validateHistory(history); }
        catch (IllegalArgumentException invalid) { throw new IOException("历史中存在非末尾工具交换损坏，不能自动恢复", invalid); }
        if (repaired) warnings.add("上次工具调用结果未完整记录，操作可能已执行；已补中断说明，不会重跑工具。");
        if (unfinished) warnings.add("上次大轮次未正常结束；恢复后等待新输入，不自动继续执行。");
        if (!entries.isEmpty()) warnings.add("旧后台进程、队友、排队输入和审批授权未恢复；工作区文件不会回滚。");
        // 清单可能在日志写完之后更新失败。从日志重算标题/更新时间，不让旧索引成为历史事实。
        SessionMetadata rebuilt = metadata;
        for (JournalEntry entry : entries) rebuilt = rebuilt.updated(
                entry.reason().equals("request") && !entry.messages().isEmpty() ? entry.messages().getFirst().extractText() : null, entry.time());
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
            if (!uses.get(i).id().equals(results.get(i).toolUseId())) throw new IOException("末尾工具结果 ID 错序，不能自动修复");
        }
        List<ContentBlock> blocks = new ArrayList<>(results);
        for (int i = results.size(); i < uses.size(); i++) blocks.add(new ToolResultBlock(uses.get(i).id(),
                "[Interrupted previous session: result was not recorded. The operation may already have executed; verify actual state before retrying.]") );
        if (last != calls) last.content().stream().filter(b -> !(b instanceof ToolResultBlock)).forEach(blocks::add);
        Message replacement = Message.userBlocks(List.copyOf(blocks));
        if (last == calls) history.add(replacement);
        else history.set(index, replacement);
        return true;
    }
}
