package com.jadecode.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.messages.*;
import com.jadecode.session.history.JournalEntry;
import com.jadecode.util.Json;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 为记忆提取构造有来源的证据，而不是把当前压缩历史当作完整会话。
 * 原始日志只读；用户原话优先、正文其次、工具结果有单条预算，最后恢复时间顺序。
 * 脱敏是多层防护的一环，不声称正则能识别所有秘密。
 */
final class MemoryEvidence {
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)((?:api[_-]?key|access[_-]?token|authorization|password|secret)\\s*[=:]\\s*[\\\"']?)([^\\s\\\"',;}]+)");
    private static final Pattern TOKEN = Pattern.compile("\\b(?:sk-[A-Za-z0-9_-]{12,}|gh[pousr]_[A-Za-z0-9_]{15,})\\b");
    private static final Pattern URL_SECRET = Pattern.compile("(?i)([?&](?:token|key|signature|password|access_token)=)[^&#\\s]+");
    private record Item(int priority, long sequence, String source, String text) { }
    /** 只记录被过滤调用的名称和路径，不保留其正文或整份工具参数。 */
    private record FilteredCall(String tool, String path, boolean projectInstructions) { }
    /** 来源用户原话与完整性分开传递；空列表不能自动被解释成用户从未表达过要求。 */
    record HumanEvidence(List<String> requests, boolean complete) { }

    private MemoryEvidence() { }

    /** 输入/输出均用同一脱敏规则；日志、slug 和候选摘要不会绕开处理。 */
    static String redact(String text) {
        String safe = CREDENTIAL.matcher(text).replaceAll("$1[REDACTED_SECRET]");
        safe = TOKEN.matcher(safe).replaceAll("[REDACTED_SECRET]");
        return URL_SECRET.matcher(safe).replaceAll("$1[REDACTED_SECRET]");
    }

    /**
     * 为整理器提取指定来源区间的真实用户文字，防止模型总结把实现细节错归为用户要求。
     * 只读当前工作区的会话事件，不读取模型提供的任意路径；工具结果、助手文字和通知不算人类请求。
     * 返回脱敏且有界的独立列表；被过滤/截断的内容不表示未发生，也不向整理器提供新的执行指令。
     */
    static HumanEvidence humanRequests(ObjectNode source, com.jadecode.config.WorkspacePaths paths, int budget) throws IOException {
        var requests = new ArrayList<String>();
        String journal = source.path("journal").asText();
        if (!journal.isEmpty()) {
            Path file = Path.of(journal).toAbsolutePath().normalize();
            if (!file.startsWith(paths.sessions()) || !file.getFileName().toString().equals("events.jsonl"))
                throw new IOException("Human evidence is outside the session store");
            MemoryStore.directory(file.getParent(), false);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Human evidence is not a regular file");
            try (var reader = Files.newBufferedReader(file)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    JournalEntry event = Json.mapper().readValue(line, JournalEntry.class);
                    if (event.sequence() <= source.path("start_sequence").asLong()) continue;
                    if (event.sequence() > source.path("end_sequence").asLong()) break;
                    if (event.kind() != JournalEntry.Kind.APPEND || !event.reason().equals("request")) continue;
                    for (Message message : event.messages()) if (message.role() == Role.USER && message.source() == null
                            && !message.extractText().isBlank()) requests.add(message.extractText());
                }
            }
        }
        // 忙时用户补充由后端另行绑定；无日志嵌入调用也通过该字段保留人类证据。
        for (JsonNode human : source.path("human_inputs")) if (human.isTextual()) requests.add(human.asText());
        var selected = new ArrayList<String>(); boolean complete = true; int remaining = Math.max(0, budget);
        for (int i = requests.size() - 1; i >= 0; i--) {
            String safe = redact(requests.get(i));
            if (safe.length() <= remaining) { selected.add(safe); remaining -= safe.length(); }
            else {
                complete = false;
                // 优先较新的纠正；只有能放下明确的截断标记时才保留部分原话。
                String marker = "\n[user request truncated by budget]";
                if (remaining > marker.length() + 128) {
                    selected.add(safe.substring(0, remaining - marker.length()) + marker); remaining = 0;
                }
            }
        }
        Collections.reverse(selected);
        return new HumanEvidence(List.copyOf(selected), complete);
    }

    /**
     * 读取固定的任务日志区间；只允许当前工作区 session 事件文件，不能让作业指定任意秘密路径。
     * 原始 APPEND 事件在压缩后仍存在，因此早期反馈不会被最后一次 history 替换抹掉。
     * 无日志的显式离线入口使用登记时的有界快照，来源会明确标为 snapshot。
     */
    static String render(ObjectNode job, com.jadecode.config.WorkspacePaths paths, int budget) throws IOException {
        Path workspace = paths.workspace();
        List<Item> items = new ArrayList<>();
        String memoryRoot = job.path("memory_root").asText();
        String location = job.path("journal").asText();
        if (!location.isEmpty()) {
            Path path = Path.of(location).toAbsolutePath().normalize();
            Path sessions = paths.sessions();
            if (!path.startsWith(sessions) || !path.getFileName().toString().equals("events.jsonl"))
                throw new IOException("Memory evidence is outside the session store");
            MemoryStore.directory(path.getParent(), false);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Memory evidence is not a regular file");
            Map<String, FilteredCall> memoryCalls = new HashMap<>();
            try (var reader = Files.newBufferedReader(path)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    JournalEntry entry = Json.mapper().readValue(line, JournalEntry.class);
                    if (entry.sequence() <= job.path("start_sequence").asLong()) continue;
                    if (entry.sequence() > job.path("end_sequence").asLong()) break;
                    if (entry.kind() != JournalEntry.Kind.APPEND) continue;
                    if (Set.of("compaction", "memory_reference", "stop", "notification").contains(entry.reason())) continue;
                    for (Message message : entry.messages())
                        add(items, message, entry.sequence(), location + ":event=" + entry.sequence(), entry.reason().equals("request"), memoryCalls, memoryRoot, workspace);
                }
            }
        } else {
            long sequence = 0;
            Map<String, FilteredCall> memoryCalls = new HashMap<>();
            for (JsonNode item : job.path("snapshot")) {
                Message message = Json.mapper().treeToValue(item, Message.class);
                add(items, message, ++sequence, "snapshot:" + job.path("id").asText() + ":" + sequence,
                        message.role() == Role.USER, memoryCalls, memoryRoot, workspace);
            }
        }
        // 忙时追加的人类输入由 Session 另行提供，避免把整封后台通知误当成人类原话。
        long userSequence = job.path("end_sequence").asLong() + 1;
        for (JsonNode human : job.path("human_inputs"))
            items.add(new Item(0, userSequence++, "user-input:" + job.path("task_id").asText(), redact(human.asText())));

        // 按来源等级保住人类证据；同等级优先新内容，再按原始顺序交给提取模型。
        items.sort(Comparator.comparingInt(Item::priority).thenComparing(Comparator.comparingLong(Item::sequence).reversed()));
        var selected = new ArrayList<Item>(); int remaining = Math.max(0, budget - 80);
        boolean omitted = false;
        for (Item item : items) {
            String body = item.text();
            if (body.length() + item.source().length() + 48 > remaining) {
                omitted = true;
                // 单条超长用户请求保留有标记的前缀；不能因一条太大把全部人类证据丢掉。
                int room = remaining - item.source().length() - 100;
                if (item.priority() == 0 && room > 128) {
                    body = body.substring(0, Math.min(room, body.length())) + "\n[human input truncated by budget]";
                    item = new Item(item.priority(), item.sequence(), item.source(), body);
                } else continue;
            }
            selected.add(item); remaining -= body.length() + item.source().length() + 48;
        }
        selected.sort(Comparator.comparingLong(Item::sequence));
        StringBuilder result = new StringBuilder();
        if (omitted) result.append("[... response items omitted by the evidence budget ...]\n");
        for (Item item : selected) result.append('[').append(item.priority() == 0 ? "human user" : item.priority() == 1 ? "assistant" : "tool")
                .append("; source=").append(item.source()).append("]\n").append(item.text()).append("\n\n");
        return result.toString();
    }

    /**
     * 只采集有用文本，排除思考、旧记忆和指令正文；过滤正文不等于抹去操作事实。
     * 同一调用 ID 跨 assistant/tool 消息关联，只有收到实际结果才说明项目指令是否读取成功。
     * 该标记仍是工具证据，不是人类原话，也不允许辅助模型恢复被隐藏的规则内容。
     */
    private static void add(List<Item> items, Message message, long sequence, String source, boolean human,
            Map<String, FilteredCall> memoryCalls, String memoryRoot, Path workspace) {
        if (message.source() == Message.Source.MEMORY_REFERENCE) return;
        for (ContentBlock block : message.content()) {
            if (block instanceof ToolUseBlock call) {
                String path = call.input().path("path").asText();
                boolean instructions = path.endsWith("/AGENTS.md") || path.equals("AGENTS.md");
                if (call.name().equals("memory_note") || call.name().equals("load_skill")
                        || isMemoryPath(path, memoryRoot, workspace) || instructions)
                    memoryCalls.put(call.id(), new FilteredCall(call.name(), path, instructions));
                else items.add(new Item(2, sequence, source, redact("Tool request " + call.name() + ": " + call.input())));
                continue;
            }
            if (block instanceof TextBlock text && !text.text().isBlank()) {
                String value = text.text();
                // 旧 run(messages) 无来源标记，保守排除明显的框架/压缩参考，不能据其推断用户偏好。
                if (value.startsWith("[Compacted]") || value.startsWith("[Scheduled]") || value.startsWith("[Goal")) continue;
                items.add(new Item(human ? 0 : 1, sequence, source, redact(value)));
            } else if (block instanceof ToolResultBlock tool) {
                FilteredCall filtered = memoryCalls.get(tool.toolUseId());
                if (filtered != null) {
                    // 旧记忆/候选笔记不构成新的独立事实；项目指令只保留成功与否，永不复制正文。
                    if (filtered.projectInstructions()) {
                        var observation = Json.mapper().createObjectNode().put("type", "tool_observation")
                                .put("tool", filtered.tool()).put("path", filtered.path())
                                .put("succeeded", !tool.isError() && !tool.content().startsWith("Error")
                                        && !tool.content().startsWith("Permission denied"))
                                .put("body_omitted", "project_instructions");
                        items.add(new Item(2, sequence, source, redact(observation.toString())));
                    }
                } else {
                    String body = tool.content();
                    if (body.length() > 8000) body = body.substring(0, 8000) + "\n[tool output truncated]";
                    items.add(new Item(2, sequence, source, redact(body)));
                }
            }
        }
    }

    /** 注入的全局根也可能是测试目录；按真实解析路径识别，不能只靠 ~/.jadecode 字样。 */
    private static boolean isMemoryPath(String path, String memoryRoot, Path workspace) {
        if (path.isBlank()) return false;
        if (path.contains("/.jadecode/memory/")) return true;
        if (memoryRoot.isBlank()) return false;
        try { return new com.jadecode.util.PathResolver(workspace).resolve(path).startsWith(Path.of(memoryRoot)); }
        catch (RuntimeException invalid) { return false; }
    }

    /** 在模型输出边界按 UTF-8 字节检查；不把截断到一半的回顾伪装成完整材料。 */
    static int bytes(String text) { return text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length; }
}
