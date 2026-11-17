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
 * 从持久事件区间或嵌入式历史快照构造记忆证据。按照用户原话、助手正文、工具资料的优先级筛选内容，脱敏并控制预算，
 * 再恢复时间顺序；独立用户证据通道供知识整理核对经验摘要的归因。
 */
final class MemoryEvidence {
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)((?:api[_-]?key|access[_-]?token|authorization|password|secret)\\s*[=:]\\s*[\\\"']?)([^\\s\\\"',;}]+)");
    private static final Pattern TOKEN = Pattern.compile("\\b(?:sk-[A-Za-z0-9_-]{12,}|gh[pousr]_[A-Za-z0-9_]{15,})\\b");
    private static final Pattern URL_SECRET = Pattern
            .compile("(?i)([?&](?:token|key|signature|password|access_token)=)[^&#\\s]+");

    /**
     * 保存一段证据的原始顺序、选择优先级和脱敏文本，供预算筛选后恢复对话中的先后次序。
     *
     * @param priority 证据优先级；用户原话为 0，助手正文为 1，工具资料为 2
     * @param sequence 持久事件序号或原始证据顺序号
     * @param source 此条证据的日志事件或快照来源定位文本
     * @param text 需要脱敏或按 UTF-8 计量的原始文本
     */
    private record Item(int priority, long sequence, String source, String text) {
    }

    /**
     * 记录需要过滤正文的工具调用及其路径，projectInstructions 标记用于把项目指令读取转换成仅含成功状态的操作事实。
     *
     * @param tool 模型调用的工具注册名
     * @param path 本次读取、授权或校验的路径，按方法说明解析到实际目标
     * @param projectInstructions 该工具调用是否读取了项目指令文件
     */
    private record FilteredCall(String tool, String path, boolean projectInstructions) {
    }

    /**
     * 保存脱敏后的用户原话列表和预算完整性标记，供整理器区分完整证据与部分证据。
     *
     * @param requests 按原顺序保留的脱敏用户原话
     * @param complete 原始用户文字是否全部纳入本次预算
     */
    record HumanEvidence(List<String> requests, boolean complete) {
    }

    /**
     * 汇集会话证据读取、筛选、脱敏和预算控制的静态方法。
     */
    private MemoryEvidence() {
    }

    /**
     * 依次识别键值凭据、常见 token 和 URL 查询参数中的秘密，以 REDACTED_SECRET 标记替换其值，返回处理后的文本。
     *
     * @param text 需要脱敏或按 UTF-8 计量的原始文本
     * @return 按统一规则替换已识别凭据后的文本
     */
    static String redact(String text) {
        String safe = CREDENTIAL.matcher(text).replaceAll("$1[REDACTED_SECRET]");
        safe = TOKEN.matcher(safe).replaceAll("[REDACTED_SECRET]");
        return URL_SECRET.matcher(safe).replaceAll("$1[REDACTED_SECRET]");
    }

    /**
     * 从经验来源关联的真实日志独立提取用户原话，供整理器核对摘要中的归因。
     *
     * <p>处理过程：</p>
     * <ol>
     * <li>把 journal 限定到当前工作区的 sessions/events.jsonl，逐行读取来源声明的左开右闭序号区间。</li>
     * <li>选择 APPEND 且 reason 为 request 的普通 USER 文本，再合并后端单独保存的 human_inputs。</li>
     * <li>从新到旧脱敏和选择预算内文本，能保留部分长原话时附加截断标记，并把 complete 设为 false。</li>
     * <li>恢复选中原话的原始顺序，返回独立列表及完整性状态。</li>
     * </ol>
     *
     * @param source 经验来源元数据，包含可信日志位置、事件区间和用户补充
     * @param paths 证据所属工作区的数据布局，用于限定 sessions 读取范围
     * @param budget 本次可保留的字符数预算
     * @return 预算内脱敏用户原话及 complete 完整性标记
     * @throws IOException 来源超出当前 sessions、目标不是普通日志或事件读取解析失败
     */
    static HumanEvidence humanRequests(ObjectNode source, com.jadecode.config.WorkspacePaths paths, int budget)
            throws IOException {
        var requests = new ArrayList<String>();
        String journal = source.path("journal").asText();
        if (!journal.isEmpty()) {
            Path file = Path.of(journal).toAbsolutePath().normalize();
            if (!file.startsWith(paths.sessions()) || !file.getFileName().toString().equals("events.jsonl"))
                throw new IOException("Human evidence is outside the session store");
            MemoryStore.directory(file.getParent(), false);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Human evidence is not a regular file");
            try (var reader = Files.newBufferedReader(file)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    JournalEntry event = Json.mapper().readValue(line, JournalEntry.class);
                    if (event.sequence() <= source.path("start_sequence").asLong())
                        continue;
                    if (event.sequence() > source.path("end_sequence").asLong())
                        break;
                    if (event.kind() != JournalEntry.Kind.APPEND || !event.reason().equals("request"))
                        continue;
                    for (Message message : event.messages())
                        if (message.role() == Role.USER && message.source() == null
                                && !message.extractText().isBlank())
                            requests.add(message.extractText());
                }
            }
        }
        // 忙时用户补充由后端另行绑定；无日志嵌入调用也通过该字段保留人类证据。
        for (JsonNode human : source.path("human_inputs"))
            if (human.isTextual())
                requests.add(human.asText());
        var selected = new ArrayList<String>();
        boolean complete = true;
        int remaining = Math.max(0, budget);
        for (int i = requests.size() - 1; i >= 0; i--) {
            String safe = redact(requests.get(i));
            if (safe.length() <= remaining) {
                selected.add(safe);
                remaining -= safe.length();
            } else {
                complete = false;
                // 优先较新的纠正；只有能放下明确的截断标记时才保留部分原话。
                String marker = "\n[user request truncated by budget]";
                if (remaining > marker.length() + 128) {
                    selected.add(safe.substring(0, remaining - marker.length()) + marker);
                    remaining = 0;
                }
            }
        }
        Collections.reverse(selected);
        return new HumanEvidence(List.copyOf(selected), complete);
    }

    /**
     * 把一个轮次的日志证据整理为提取模型可读取的有界文本。
     *
     * <p>处理过程：</p>
     * <ol>
     * <li>使用作业固定的日志区间读取原始 APPEND；无日志入口则读取登记快照。</li>
     * <li>过滤压缩、旧记忆、Stop 与普通通知，把真实人类正文、助手正文和工具资料分级，补上独立用户补充。</li>
     * <li>先按优先级、再按同级新旧选取预算内内容；过长人类文本保留带标记的前缀。</li>
     * <li>按原序号恢复事件顺序，逐项附来源位置和角色，存在遗漏时加总预算提示。</li>
     * </ol>
     *
     * @param job 包含日志区间、用户补充和可选快照的提取作业
     * @param paths 证据所属工作区的数据布局，用于限定 sessions 读取范围
     * @param budget 本次可保留的字符数预算
     * @return 按原始顺序排列、包含来源和截断标记的提取证据文本
     * @throws IOException 作业日志路径非法、日志或快照解析失败
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
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Memory evidence is not a regular file");
            Map<String, FilteredCall> memoryCalls = new HashMap<>();
            try (var reader = Files.newBufferedReader(path)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    JournalEntry entry = Json.mapper().readValue(line, JournalEntry.class);
                    if (entry.sequence() <= job.path("start_sequence").asLong())
                        continue;
                    if (entry.sequence() > job.path("end_sequence").asLong())
                        break;
                    if (entry.kind() != JournalEntry.Kind.APPEND)
                        continue;
                    if (Set.of("compaction", "memory_reference", "stop", "notification").contains(entry.reason()))
                        continue;
                    for (Message message : entry.messages())
                        add(items, message, entry.sequence(), location + ":event=" + entry.sequence(),
                                entry.reason().equals("request"), memoryCalls, memoryRoot, workspace);
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
            items.add(
                    new Item(0, userSequence++, "user-input:" + job.path("task_id").asText(), redact(human.asText())));

        // 按来源等级保住人类证据；同等级优先新内容，再按原始顺序交给提取模型。
        items.sort(Comparator.comparingInt(Item::priority)
                .thenComparing(Comparator.comparingLong(Item::sequence).reversed()));
        var selected = new ArrayList<Item>();
        int remaining = Math.max(0, budget - 80);
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
                } else
                    continue;
            }
            selected.add(item);
            remaining -= body.length() + item.source().length() + 48;
        }
        selected.sort(Comparator.comparingLong(Item::sequence));
        StringBuilder result = new StringBuilder();
        if (omitted)
            result.append("[... response items omitted by the evidence budget ...]\n");
        for (Item item : selected)
            result.append('[').append(item.priority() == 0 ? "human user" : item.priority() == 1 ? "assistant" : "tool")
                    .append("; source=").append(item.source()).append("]\n").append(item.text()).append("\n\n");
        return result.toString();
    }

    /**
     * 把一条消息展开为待选证据，追加到 items 并维护需过滤的工具调用映射。普通正文脱敏后按人类或助手分级，
     * 工具请求与结果列为较低优先级；记忆、技能和项目指令正文过滤，项目指令读取结果保留为成功状态，
     * 普通工具结果最多保留 8000 字符。
     *
     * @param items 本次待筛选的证据列表，方法按实际内容追加条目
     * @param message 待编码、校验或投影的完整消息
     * @param sequence 持久事件序号或原始证据顺序号
     * @param source 此条证据的日志事件或快照来源定位文本
     * @param human 此消息正文是否来自真实用户请求
     * @param memoryCalls 按工具调用 ID 保存的正文过滤记录，供结果块关联来源
     * @param memoryRoot 本次证据或权限检查绑定的长期记忆根目录
     * @param workspace 会话绑定的真实工作区目录
     */
    private static void add(List<Item> items, Message message, long sequence, String source, boolean human,
            Map<String, FilteredCall> memoryCalls, String memoryRoot, Path workspace) {
        if (message.source() == Message.Source.MEMORY_REFERENCE)
            return;
        for (ContentBlock block : message.content()) {
            if (block instanceof ToolUseBlock call) {
                String path = call.input().path("path").asText();
                boolean instructions = path.endsWith("/AGENTS.md") || path.equals("AGENTS.md");
                if (call.name().equals("memory_note") || call.name().equals("load_skill")
                        || isMemoryPath(path, memoryRoot, workspace) || instructions)
                    memoryCalls.put(call.id(), new FilteredCall(call.name(), path, instructions));
                else
                    items.add(
                            new Item(2, sequence, source, redact("Tool request " + call.name() + ": " + call.input())));
                continue;
            }
            if (block instanceof TextBlock text && !text.text().isBlank()) {
                String value = text.text();
                // 框架与压缩参考不能作为新的偏好证据；真正人类原话由轮次入口单独绑定。
                if (value.startsWith("[Compacted]") || value.startsWith("[Scheduled]") || value.startsWith("[Goal"))
                    continue;
                items.add(new Item(human ? 0 : 1, sequence, source, redact(value)));
            } else if (block instanceof ToolResultBlock tool) {
                FilteredCall filtered = memoryCalls.get(tool.toolUseId());
                if (filtered != null) {
                    // 旧记忆/候选笔记不构成新的独立事实；项目指令只保留成功与否，永不复制正文。
                    if (filtered.projectInstructions()) {
                        var observation = Json.mapper().createObjectNode().put("type", "tool_observation")
                                .put("tool", filtered.tool()).put("path", filtered.path())
                                .put("succeeded", !tool.isError())
                                .put("body_omitted", "project_instructions");
                        items.add(new Item(2, sequence, source, redact(observation.toString())));
                    }
                } else {
                    String body = tool.content();
                    if (body.length() > 8000)
                        body = body.substring(0, 8000) + "\n[tool output truncated]";
                    items.add(new Item(2, sequence, source, redact(body)));
                }
            }
        }
    }

    /**
     * 结合常见记忆目录标识与注入的记忆根判断路径归属；相对路径按工作区解析，空路径、缺失根或解析错误返回 false。
     *
     * @param path 本次读取、授权或校验的路径，按方法说明解析到实际目标
     * @param memoryRoot 本次证据或权限检查绑定的长期记忆根目录
     * @param workspace 会话绑定的真实工作区目录
     * @return 目标解析后属于已知记忆根时为 true
     */
    private static boolean isMemoryPath(String path, String memoryRoot, Path workspace) {
        if (path.isBlank())
            return false;
        if (path.contains("/.jadecode/memory/"))
            return true;
        if (memoryRoot.isBlank())
            return false;
        try {
            return new com.jadecode.util.PathResolver(workspace).resolve(path).startsWith(Path.of(memoryRoot));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /**
     * 计算文本按 UTF-8 编码后的字节数，供摘要和用户证据的存储预算检查使用。
     *
     * @param text 需要脱敏或按 UTF-8 计量的原始文本
     * @return 文本采用 UTF-8 编码时的字节数
     */
    static int bytes(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
}
