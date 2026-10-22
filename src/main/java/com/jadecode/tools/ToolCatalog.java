package com.jadecode.tools;

import com.jadecode.execution.ToolIdentity;
import com.jadecode.llm.ToolDefinition;
import com.jadecode.mcp.McpToolDescriptor;
import java.util.*;
import java.util.function.Supplier;

/**
 * 合并工具来源并为一次模型请求生成固定目录，不启动服务、不执行工具。
 *
 * <p>连接新服务只改变下一次 snapshot；一次回复中的调用仍从原 Snapshot 查找。
 * 因此同一批中先 connect 再猜一个新工具名，也不能绕过“先发现、下次请求才可用”的边界。</p>
 */
public final class ToolCatalog {
    private final Map<String, Tool> builtins;
    private final Supplier<List<Entry>> external;

    /** 保存固定内置表与外部目录读取函数；读取函数应快速、无 IO。 */
    public ToolCatalog(Map<String, Tool> builtins, Supplier<List<Entry>> external) {
        this.builtins = Map.copyOf(builtins); this.external = Objects.requireNonNull(external);
        builtins.forEach((name, tool) -> {
            if (!name.equals(tool.name()) || name.startsWith("mcp__")) throw new IllegalArgumentException("Invalid builtin tool name or it occupies the MCP namespace");
        });
    }
    /** 兼容旧工具表与离线调用；不增加外部服务或读取配置。 */
    public static ToolCatalog fixed(Map<String, Tool> tools) { return new ToolCatalog(tools, List::of); }
    /** 构造时检查 compact 所需依赖，不触发外部目录或生成模型定义。 */
    public boolean hasBuiltin(String name) { return builtins.containsKey(name); }

    /**
     * 先合并并检查名字，再生成模型定义。定义列表和执行表来自同一批 Entry，不发布可变 Map。
     * schema 由 ToolDefinition 深复制；Tool 本身仍是执行对象，不能写入 CompleteRequest。
     */
    public Snapshot snapshot() {
        Map<String, Entry> entries = new LinkedHashMap<>();
        builtins.forEach((name, tool) -> entries.put(name, new Entry(tool, ToolIdentity.builtin(name))));
        for (Entry entry : external.get()) {
            String name = entry.tool().name();
            if (entries.putIfAbsent(name, entry) != null) throw new IllegalArgumentException("Tool name conflict: " + name);
        }
        List<ToolDefinition> definitions = new ArrayList<>();
        entries.forEach((name, entry) -> {
            ToolDefinition definition = entry.tool().definition();
            if (!name.equals(definition.name())) throw new IllegalArgumentException("Tool name changed while generating its definition");
            definitions.add(definition);
        });
        return new Snapshot(entries, definitions);
    }

    /** 服务与原始工具名分别规范化；不从规范化结果还原真实身份，冲突必须另行检查。 */
    public static String mcpName(String server, String tool) {
        String name = "mcp__" + server.replaceAll("[^a-zA-Z0-9_-]", "_") + "__" + tool.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (name.length() > 64) throw new IllegalArgumentException("Normalized MCP tool name exceeds 64 characters");
        return name;
    }

    /** 发布连接前核对所有 MCP 工具，防止 docs.one 与 docs_one 等规范化后互相覆盖。 */
    public static void validateMcpNames(List<McpToolDescriptor> descriptors) {
        Set<String> names = new HashSet<>();
        for (var descriptor : descriptors) {
            if (!names.add(mcpName(descriptor.serverName(), descriptor.name()))) throw new IllegalArgumentException("Normalized MCP tool names conflict");
        }
    }

    /** 注册项含可执行对象与可信身份，只存在内存中，不是模型可填写的数据。 */
    public record Entry(Tool tool, ToolIdentity identity) {
        public Entry { Objects.requireNonNull(tool); Objects.requireNonNull(identity); }
    }
    /** 一次请求与其返回工具批次共同持有的不可变目录，不持久化工具实例。 */
    public record Snapshot(Map<String, Entry> entries, List<ToolDefinition> definitions) {
        public Snapshot { entries = Map.copyOf(entries); definitions = List.copyOf(definitions); }
    }
}
