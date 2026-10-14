package com.jadecode.memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * 已有记忆的只读入口：为一次召回提供稳定编号所需的记录列表。
 *
 * <p>记忆文件存在并不等于模型已经看见。本类只回答“工作区里有哪些合法记录”，
 * {@link MemoryRecallService} 再决定哪些记录进入下一次请求；本类不理解内容的长期价值，
 * 不使用临时关键词否决记录，也不自动提取、保存或整理记忆。</p>
 *
 * <p>库位于注入工作区的 {@code .memory} 下，只有一层 Markdown 文件。
 * 每次调用重新读取，返回不可修改的列表与不可变字符串；不缓存上一会话的选择。
 * 快照表示本次读到的内容，并不是对并发文件修改提供事务隔离。</p>
 */
public final class MemoryStore {
    /** 延用 s07 的 Jackson YAML 依赖，不另引入格式解析器。 */
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    /** 类型描述知识用途，不代表指令优先级。M1 只接受课程规定的四类。 */
    private static final Set<String> TYPES = Set.of("user", "feedback", "project", "reference");

    /** 规范化后的实际工作区；检查库根链接时使用这一围栏。 */
    private final Path workspaceRoot;
    /** 尚可不存在的库目录；只读召回不会为它自动 mkdir。 */
    private final Path memoryDir;

    /**
     * 把只读库绑定到一个确实存在的工作区，不读取进程全局 cwd。
     *
     * @param workspaceRoot 调用方确定的工作区目录，不能为 null
     * @throws IOException 工作区不存在、无法解析或不是目录；不创建任何文件
     * @throws IllegalArgumentException 参数为 null
     */
    public MemoryStore(Path workspaceRoot) throws IOException {
        if (workspaceRoot == null) {
            throw new IllegalArgumentException("workspaceRoot 不能为 null");
        }
        // 先解析工作区自身的链接，再派生 .memory；比较的是实际路径，不是字符串前缀。
        this.workspaceRoot = workspaceRoot.toRealPath();
        if (!Files.readAttributes(this.workspaceRoot, BasicFileAttributes.class).isDirectory()) {
            throw new IOException("Memory 工作区不是目录: " + this.workspaceRoot);
        }
        this.memoryDir = this.workspaceRoot.resolve(".memory");
    }

    /**
     * 按文件名字典序读取合法记忆，供召回服务建立索引 → 记录的固定对应关系。
     *
     * <p>库不存在返回空列表；索引 MEMORY.md、非 Markdown、非普通文件、越界链接和
     * 格式非法的记录不进入结果。库根逃出工作区、目录列举或真实读取故障抛 IOException，
     * 避免把“无法读取知识”静默解释为“用户没有知识”。悬空链接或列举后消失的文件跳过。</p>
     *
     * @return 不可修改的记录列表，原文包含 frontmatter，读取操作没有写入副作用
     * @throws IOException 库根非法或真实文件系统读取失败
     */
    public List<MemoryRecord> listRecords() throws IOException {
        // 1. 新工作区通常还没有记忆，这是合法状态；notExists 无法判定时继续，让 IO 报错。
        if (Files.notExists(memoryDir)) {
            return List.of();
        }
        Path root = memoryDir.toRealPath();
        if (!root.startsWith(workspaceRoot)
                || !Files.readAttributes(root, BasicFileAttributes.class).isDirectory()) {
            throw new IOException("Memory 库根不是工作区内的目录: " + memoryDir);
        }

        // 2. 一次列举固定顺序。不要在模型回复后重新扫描，否则索引 0 可能已经指向另一条记录。
        List<MemoryRecord> records = new ArrayList<>();
        try (var entries = Files.list(root)) {
            for (Path entry : entries.sorted().toList()) {
                String filename = entry.getFileName().toString();
                if (filename.equals("MEMORY.md") || !filename.endsWith(".md")) {
                    continue;
                }
                try {
                    // 3. 先检查真实位置与文件类型，再读正文；目录分组本身不能防止越界软链。
                    Path real = entry.toRealPath();
                    if (!real.startsWith(root)) {
                        System.err.println("[Memory] 忽略越界记忆文件: " + filename);
                        continue;
                    }
                    if (!Files.readAttributes(real, BasicFileAttributes.class).isRegularFile()) {
                        continue;
                    }
                    String content = Files.readString(real, StandardCharsets.UTF_8);
                    MemoryRecord record = parseRecord(filename, content);
                    if (record == null) {
                        // 只报文件名，不回显原文；坏文件不妨碍其它合法知识被召回。
                        System.err.println("[Memory] 忽略格式无效的记忆文件: " + filename);
                    } else {
                        records.add(record);
                    }
                } catch (NoSuchFileException disappeared) {
                    // 悬空链接 / 列举后删除，与权限或磁盘故障不同，不制造不存在的记录。
                }
            }
        } catch (UncheckedIOException listingFailure) {
            // Files.list 的延迟遍历可把 IO 包装成运行期异常；恢复 IOException 契约，保留原 cause。
            throw listingFailure.getCause();
        }
        return List.copyOf(records);
    }

    /**
     * 从一个文件分离 YAML 元数据，原文另行保留；仅做结构检查，不判断内容是否值得记住。
     *
     * <p>开头与结尾分隔符都必须独占一行，支持 LF / CRLF。
     * 名称、描述、类型必须为非空字符串，正文必须非空；缺失、非法 YAML 或未知类型返回 null。
     * 与课程的缺字段兜底不同，这里不把坏元数据猜成默认项目知识。</p>
     */
    private static MemoryRecord parseRecord(String filename, String content) {
        // 只为分隔符识别归一化行尾。返回的 content 仍是 UTF-8 文件原文，不改变 CRLF。
        String[] lines = content.replace("\r\n", "\n").split("\n", -1);
        if (!lines[0].equals("---")) {
            return null;
        }
        int closing = -1;
        for (int i = 1; i < lines.length; i++) {
            // 缩进的 --- 可能是 YAML 文本内容，不能当成结束分隔符。
            if (lines[i].equals("---")) {
                closing = i;
                break;
            }
        }
        if (closing < 0
                || String.join("\n", Arrays.copyOfRange(lines, closing + 1, lines.length)).isBlank()) {
            return null;
        }
        String frontmatter = String.join("\n", Arrays.copyOfRange(lines, 1, closing)) + "\n";
        try {
            JsonNode metadata = YAML.readTree(frontmatter);
            if (metadata == null || !metadata.isObject()) {
                return null;
            }
            String name = textField(metadata, "name");
            String description = textField(metadata, "description");
            String type = textField(metadata, "type");
            if (name == null || description == null || type == null || !TYPES.contains(type)) {
                return null;
            }
            return new MemoryRecord(filename, name, description, type, content);
        } catch (JsonProcessingException invalidYaml) {
            // 这是内容格式问题；读取文件的 IOException 不在这里捕获，仍由上层明确报告。
            return null;
        }
    }

    /** 只认非空文本字段，不把数字、数组或 null 强制转成“看起来可用”的元数据。 */
    private static String textField(JsonNode metadata, String field) {
        JsonNode value = metadata.get(field);
        return value != null && value.isTextual() && !value.textValue().isBlank()
                ? value.textValue().strip() : null;
    }

    /**
     * 一次扫描读取的记忆值；字符串不可变，不包含外部可修改的 Map 或消息列表。
     *
     * @param filename 库中实际扫描到的单层文件名，不是模型指定的读取路径
     * @param name 记忆名称
     * @param description 供模型选择的短描述
     * @param type user / feedback / project / reference
     * @param content 完整文件原文（含 frontmatter），只在选中后发送给主任务背景
     */
    public record MemoryRecord(String filename, String name, String description, String type, String content) {
    }
}
