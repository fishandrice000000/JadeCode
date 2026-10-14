package com.jadecode.memory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * 工作区记忆的读写入口：读取合法记录，或安全新增记录并更新派生索引。
 *
 * <p>记忆文件存在并不等于模型已经看见。读取入口回答“工作区里有哪些合法记录”，
 * {@link MemoryRecallService} 再决定哪些记录进入下一次请求；本类不理解内容的长期价值，
 * 不使用临时关键词否决记录；只有调用方显式调用 saveNew 才写入，不自动提取或整理记忆。</p>
 *
 * <p>库位于注入工作区的 {@code .memory} 下，只有一层 Markdown 文件。
 * 每次读取重新扫描，返回不可修改的列表与不可变字符串；不缓存上一会话的选择。
 * 快照表示本次读到的内容，并不是对并发文件修改提供事务隔离。</p>
 */
public final class MemoryStore {
    /** 延用 s07 的 Jackson YAML 依赖，不另引入格式解析器。 */
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    /** 类型描述知识用途，不代表指令优先级；读取与新增共用课程规定的四类。 */
    private static final Set<String> TYPES = Set.of("user", "feedback", "project", "reference");

    /** 规范化后的实际工作区；检查库根链接时使用这一围栏。 */
    private final Path workspaceRoot;
    /** 尚可不存在的库目录；只读召回不会 mkdir，显式新增才可能创建。 */
    private final Path memoryDir;

    /**
     * 把记忆库绑定到一个确实存在的工作区，不读取进程全局 cwd；构造本身不写盘。
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
        Path root = checkedRoot();

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
     * 新增一批已经完成结构 / scope 检查的记忆，成功后更新 MEMORY.md。
     *
     * <p>模型不指定路径；名称转换成单层文件名，用 CREATE_NEW 防止任何同名对象被覆盖。
     * 冲突条目跳过，不改旧记录。新文件和索引全部完成后才返回本批真正新增的文件名。</p>
     *
     * <p>普通 IO 失败时尝试删除本批自己创建的新记录与临时索引，清理异常作为 suppressed
     * 保留；旧记录不删除。索引在同目录原子替换，不支持原子移动的文件系统会明确失败。
     * 这不是多文件崩溃事务，也不对并发修改、断电或恶意路径竞态提供完整保证。</p>
     *
     * @param drafts 合法新增草稿；不能为空引用或含 null，空列表无 IO 副作用
     * @return 不可修改的实际新增文件名列表，重复 / 名称碰撞不计为新增
     * @throws IOException 库根、记录写入或索引发布失败；不返回假成功
     * @throws NullPointerException drafts 或其中的草稿为 null（调用方错误）
     */
    public List<String> saveNew(List<MemoryDraft> drafts) throws IOException {
        // 1. 固定本批输入并先检查 null。无候选不应仅为制造索引而创建运行目录。
        List<MemoryDraft> batch = List.copyOf(drafts);
        if (batch.isEmpty()) {
            return List.of();
        }
        if (Files.notExists(memoryDir)) {
            try {
                Files.createDirectory(memoryDir);
            } catch (FileAlreadyExistsException alreadyCreated) {
                // 别的操作可能刚创建它；继续检查真实根，而不是直接相信新出现的目录 / 链接。
            }
        }
        Path root = checkedRoot();
        Path index = root.resolve("MEMORY.md");
        checkIndexTarget(index);

        List<Path> created = new ArrayList<>();
        Path temporaryIndex = null;
        try {
            // 2. 只新增。先编码完整文档，再排他创建；存在文件 / 目录 / 链接时绝不覆盖。
            for (MemoryDraft draft : batch) {
                String document = memoryDocument(draft);
                Path target = root.resolve(memoryFilename(draft.name()));
                BufferedWriter writer;
                try {
                    writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                } catch (FileAlreadyExistsException conflict) {
                    continue;
                }
                // 创建成功即登记，即使 write 或 close 失败，也能清理自己的半成品。
                created.add(target);
                try (writer) {
                    writer.write(document);
                }
            }
            if (created.isEmpty()) {
                return List.of();
            }

            // 3. 索引由现在的合法文件重建，不采纳模型自己生成的索引或 Markdown 路径。
            StringBuilder catalog = new StringBuilder();
            for (MemoryRecord record : listRecords()) {
                catalog.append("- [").append(indexText(record.name())).append("](")
                        .append(record.filename()).append(") - ").append(indexText(record.description()))
                        .append('\n');
            }
            temporaryIndex = Files.createTempFile(root, ".memory-index-", ".tmp");
            Files.writeString(temporaryIndex, catalog.toString(), StandardCharsets.UTF_8);
            List<String> saved = created.stream().map(path -> path.getFileName().toString()).toList();
            // 再查目标类型；只允许替换派生的普通索引，不触碰索引路径上出现的链接或目录。
            checkIndexTarget(index);
            Files.move(temporaryIndex, index, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporaryIndex = null;
            return saved;
        } catch (IOException failure) {
            // 4. 只清理本次排他创建的路径；不回滚用户旧记录，不删除整个库。
            deleteCreated(temporaryIndex, failure);
            for (Path path : created) {
                deleteCreated(path, failure);
            }
            throw failure;
        }
    }

    /** 读写共用实际库根围栏；调用方分别决定缺库返回空结果还是显式创建。 */
    private Path checkedRoot() throws IOException {
        Path root = memoryDir.toRealPath();
        if (!root.startsWith(workspaceRoot)
                || !Files.readAttributes(root, BasicFileAttributes.class).isDirectory()) {
            throw new IOException("Memory 库根不是工作区内的目录: " + memoryDir);
        }
        return root;
    }

    /** 索引是 harness 派生文件；拒绝目录与所有软链，不跟随链接写到别处。 */
    private static void checkIndexTarget(Path index) throws IOException {
        try {
            if (!Files.readAttributes(index, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
                throw new IOException("Memory 索引不是普通非链接文件: " + index);
            }
        } catch (NoSuchFileException absent) {
            // 索引尚不存在，允许本次正常发布。
        }
    }

    /** 名称是标签，不是路径。保留中文等字母数字，去掉分隔符；索引保留名另加 record 前缀。 */
    private static String memoryFilename(String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}_]+", "-")
                .replaceAll("^[-_]+|[-_]+$", "");
        if (slug.isEmpty() || slug.equals("memory")) {
            slug = "record-memory";
        }
        return slug + ".md";
    }

    /** 用 YAML 编码元数据而非字符串拼字段，防止换行 / 冒号变成伪造的 frontmatter 字段。 */
    private static String memoryDocument(MemoryDraft draft) throws JsonProcessingException {
        var metadata = new LinkedHashMap<String, String>();
        metadata.put("name", draft.name());
        metadata.put("description", draft.description());
        metadata.put("type", draft.type());
        String yaml = YAML.writeValueAsString(metadata);
        // Jackson 默认包含文档开始标记；移除一次后自行组装唯一的一对 frontmatter 分隔符。
        if (yaml.startsWith("---\n")) {
            yaml = yaml.substring(4);
        }
        return "---\n" + yaml + "---\n\n" + draft.body() + "\n";
    }

    /** 索引描述压成一行并转义链接标签；正文不因此被压缩或改写。 */
    private static String indexText(String text) {
        return text.replaceAll("\\s+", " ").replace("\\", "\\\\")
                .replace("[", "\\[").replace("]", "\\]");
    }

    /** 删除本批专属材料；清理失败不覆盖导致整个保存失败的原异常。 */
    private static void deleteCreated(Path path, IOException original) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
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

    /**
     * 已通过 scope 检查的待新增内容；构造只检查结构，不用关键词裁决长期价值。
     *
     * @param name 非空标签，存储层再转换成安全文件名
     * @param type user / feedback / project / reference
     * @param description 有助于后续召回的非空描述
     * @param body 聚焦一项知识的非空正文
     */
    public record MemoryDraft(String name, String type, String description, String body) {
        /** 统一结构契约，使直接使用存储 API 的调用方也不能生成缺字段的非法记忆。 */
        public MemoryDraft {
            if (name == null || name.isBlank() || type == null || description == null || description.isBlank()
                    || body == null || body.isBlank()) {
                throw new IllegalArgumentException("记忆 name / type / description / body 必须完整");
            }
            name = name.strip();
            type = type.strip();
            description = description.strip();
            body = body.strip();
            if (!TYPES.contains(type)) {
                throw new IllegalArgumentException("未知的记忆类型: " + type);
            }
        }
    }
}
