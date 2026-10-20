package com.jadecode.memory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
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
 * 工作区记忆的读写入口：读出合法记录，或安全新增记录并更新索引。
 *
 * <p>文件里有记录不等于模型已经看到。本类只负责回答“库里有哪些合法记录”，
 * 具体哪些进入下一次请求由 {@link MemoryRecallService} 决定。本类不判断内容的长期价值，
 * 不用关键词否决记录；只有调用方明确要求新增或替换时才写入，不自动提取、不自动整理。
 *
 * <p>库在注入工作区的 {@code .jadecode/memory} 目录下，只有一层 Markdown 文件。每次读取都重新扫描，
 * 返回不可修改的列表，不缓存上次的结果。协作进程的文件操作互斥，模型调用不持锁；
 * 外部编辑器不遵守锁时仍可能改变库，整理发布前另核对输入快照。
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
    private final java.util.function.Consumer<String> warnings;
    /** 同一 Store 对同一坏文件只提示一次；读回合法内容后清除，之后再次损坏仍可告警。 */
    private final Set<String> reportedProblems = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 把记忆库绑定到一个确实存在的工作区，不读取进程全局 cwd；构造本身不写盘。
     *
     * @param workspaceRoot 调用方确定的工作区目录，不能为 null
     * @throws IOException 工作区不存在、无法解析或不是目录；不创建任何文件
     * @throws IllegalArgumentException 参数为 null
     */
    public MemoryStore(Path workspaceRoot) throws IOException {
        this(workspaceRoot, System.err::println);
    }

    /** 将坏文件与恢复提示送到所属 Agent 的告警通道，构造不读正文、不写盘。 */
    public MemoryStore(Path workspaceRoot, java.util.function.Consumer<String> warnings) throws IOException {
        if (workspaceRoot == null) {
            throw new IllegalArgumentException("workspaceRoot 不能为 null");
        }
        // 先解析工作区自身的链接，再派生内部记忆目录；比较实际路径，不是字符串前缀。
        this.workspaceRoot = workspaceRoot.toRealPath();
        if (!Files.readAttributes(this.workspaceRoot, BasicFileAttributes.class).isDirectory()) {
            throw new IOException("Memory 工作区不是目录: " + this.workspaceRoot);
        }
        this.memoryDir = new com.jadecode.config.WorkspacePaths(this.workspaceRoot).memory();
        this.warnings = java.util.Objects.requireNonNull(warnings);
    }

    /**
     * 按文件名字典序读取合法记忆，供召回服务建立索引 → 记录的固定对应关系。
     *
     * <p>库不存在返回空列表；索引 MEMORY.md、非 Markdown、非普通文件、越界链接和
     * 格式非法的记录不进入结果。库根逃出工作区、目录列举或真实读取故障抛 IOException，
     * 避免把“无法读取知识”静默解释为“用户没有知识”。悬空链接或列举后消失的文件跳过。</p>
     *
     * @return 不可修改的记录列表，原文包含 frontmatter；已有库会创建/使用锁文件，
     *         遇到中断的整理会先从备份恢复，缺库仍不创建目录
     * @throws IOException 库根非法或真实文件系统读取失败
     */
    public List<MemoryRecord> listRecords() throws IOException {
        new com.jadecode.config.WorkspacePaths(workspaceRoot).directory(memoryDir, false);
        // 1. 新工作区通常还没有记忆，这是合法状态；notExists 无法判定时继续，让 IO 报错。
        if (Files.notExists(memoryDir)) {
            return List.of();
        }
        Path root = checkedRoot();
        return MemoryLibraryLock.run(root, () -> { MemoryTransaction.recover(root, warnings); return scanRecords(); });
    }

    /** 已持有文件锁后扫描；内部写入流程直接调用它，避免重新取得同一个 OS 文件锁。 */
    private List<MemoryRecord> scanRecords() throws IOException {
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
                        warnOnce(filename, "忽略越界记忆文件");
                        continue;
                    }
                    if (!Files.readAttributes(real, BasicFileAttributes.class).isRegularFile()) {
                        continue;
                    }
                    String content = Files.readString(real, StandardCharsets.UTF_8);
                    MemoryRecord record = parseRecord(filename, content);
                    if (record == null) {
                        // 只报文件名，不回显原文；坏文件不妨碍其它合法知识被召回。
                        warnOnce(filename, "忽略格式无效的记忆文件");
                    } else {
                        reportedProblems.remove(filename);
                        records.add(record);
                    }
                } catch (NoSuchFileException disappeared) {
                    // 悬空链接 / 列举后删除，与权限或磁盘故障不同，不制造不存在的记录。
                } catch (CharacterCodingException badEncoding) {
                    // 坏 UTF-8 与坏 YAML 一样只是这一条不合法；权限/磁盘故障仍向上抛出。
                    warnOnce(filename, "忽略非 UTF-8 记忆文件");
                }
            }
        } catch (UncheckedIOException listingFailure) {
            // Files.list 的延迟遍历可把 IO 包装成运行期异常；恢复 IOException 契约，保留原 cause。
            throw listingFailure.getCause();
        }
        return List.copyOf(records);
    }

    private void warnOnce(String filename, String reason) {
        if (reportedProblems.add(filename)) warnings.accept("[Warning] [Memory] " + reason + ": " + filename);
    }

    /** 返回工具能直接读取的当前工作区相对路径，不再制造旧 .memory/ 引用。 */
    public String sourcePath(MemoryRecord record) { return workspaceRoot.relativize(memoryDir.resolve(record.filename())).toString(); }

    /**
     * 新增一批已经完成结构 / scope 检查的记忆，成功后更新 MEMORY.md。
     *
     * <p>模型不指定路径；名称转换成单层文件名，用 CREATE_NEW 防止任何同名对象被覆盖。
     * 冲突条目跳过，不改旧记录。新文件和索引全部完成后才返回本批真正新增的文件名。</p>
     *
     * <p>普通 IO 失败时尝试删除本批自己创建的新记录与临时索引，清理异常作为 suppressed
     * 保留；旧记录不删除。索引在同目录原子替换，不支持原子移动的文件系统会明确失败。
     * 新增不是多文件崩溃事务；协作进程的操作互斥，不对外部恶意路径竞态提供完整保证。</p>
     *
     * @param drafts 合法新增草稿；不能为空引用或含 null，空列表无 IO 副作用
     * @return 不可修改的实际新增文件名列表；完全同名跳过，不同名撞路径另分配文件名
     * @throws IOException 库根、记录写入或索引发布失败；不返回假成功
     * @throws NullPointerException drafts 或其中的草稿为 null（调用方错误）
     */
    public List<String> saveNew(List<MemoryDraft> drafts) throws IOException {
        return saveNewDetailed(drafts).filenames();
    }

    /**
     * 新增并区分“同名已存在”和真正保存；不同名字的文件名碰撞使用稳定哈希分开。
     * 整个扫描/写入/索引发布持有同一把库锁，避免多个进程用旧目录覆盖新索引。
     */
    public SaveResult saveNewDetailed(List<MemoryDraft> drafts) throws IOException {
        // 1. 固定本批输入并先检查 null。无候选不应仅为制造索引而创建运行目录。
        List<MemoryDraft> batch = List.copyOf(drafts);
        if (batch.isEmpty()) {
            return new SaveResult(List.of(), 0);
        }
        if (Files.notExists(memoryDir)) {
            try {
                new com.jadecode.config.WorkspacePaths(workspaceRoot).directory(memoryDir, true);
            } catch (FileAlreadyExistsException alreadyCreated) {
                // 别的操作可能刚创建它；继续检查真实根，而不是直接相信新出现的目录 / 链接。
            }
        }
        Path root = checkedRoot();
        return MemoryLibraryLock.run(root, () -> {
            MemoryTransaction.recover(root, warnings);
            return saveNewLocked(root, batch);
        });
    }

    private SaveResult saveNewLocked(Path root, List<MemoryDraft> batch) throws IOException {
        Path index = root.resolve("MEMORY.md");
        checkIndexTarget(index);
        var names = new java.util.HashSet<String>();
        var occupied = new java.util.HashSet<String>();
        for (var record : scanRecords()) names.add(record.name());
        try (var entries = Files.list(root)) { entries.forEach(p -> occupied.add(p.getFileName().toString())); }
        int duplicates = 0;

        List<Path> created = new ArrayList<>();
        Path temporaryIndex = null;
        try {
            // 2. 只新增。先编码完整文档，再排他创建；存在文件 / 目录 / 链接时绝不覆盖。
            for (MemoryDraft draft : batch) {
                if (!names.add(draft.name())) { duplicates++; continue; }
                String document = memoryDocument(draft);
                String filename = availableFilename(draft.name(), occupied);
                Path target = root.resolve(filename);
                BufferedWriter writer;
                try {
                    writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                } catch (FileAlreadyExistsException conflict) {
                    // 外部非协作写者抢占了已分配的目标。不能把它说成“同名记忆”，也不能覆盖。
                    throw new IOException("记忆写入目标在分配后被占用", conflict);
                }
                // 创建成功即登记，即使 write 或 close 失败，也能清理自己的半成品。
                created.add(target);
                try (writer) {
                    writer.write(document);
                }
            }
            if (created.isEmpty()) {
                return new SaveResult(List.of(), duplicates);
            }

            // 3. 索引由现在的合法文件重建，不采纳模型自己生成的索引或 Markdown 路径。
            StringBuilder catalog = new StringBuilder();
            for (MemoryRecord record : scanRecords()) {
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
            return new SaveResult(saved, duplicates);
        } catch (IOException | RuntimeException failure) {
            // 4. 只清理本次排他创建的路径；不回滚用户旧记录，不删除整个库。
            deleteCreated(temporaryIndex, failure);
            for (Path path : created) {
                deleteCreated(path, failure);
            }
            throw failure;
        }
    }

    /** filenames 是实际已发布的新文件；duplicates 只数完全同名，不混同坏字段。 */
    public record SaveResult(List<String> filenames, int duplicates) {
        public SaveResult { filenames = List.copyOf(filenames); }
    }

    /**
     * 把整理模型产生的整份候选库发布到磁盘，仅供同包整理服务调用。
     *
     * <p>expected 是模型实际看过的记录快照。发布前重新扫描并比较，库已经改变就拒绝，
     * 不用旧模型结论覆盖新内容。候选必须非空且生成的文件名唯一；模型不能指定删除路径。
     * 只替换快照中的合法普通文件，坏文件、目录和链接占位不删除、不覆盖。</p>
     *
     * <p>先在专属备份目录保存旧库、新库、索引和清单，刷新后才删除旧记录。
     * 普通失败立即恢复；进程中断则下次库操作恢复未完成的发布。备份不自动删除。
     * 恢复针对正文，不保证原文件权限等元数据；外部未知修改会使恢复停止并保留备份，
     * 不是允许任意外部写者并发修改的数据库事务，也不保证任意硬件断电行为。</p>
     *
     * @param expected 本次辅助请求使用的完整合法记录快照
     * @param drafts 整理后的完整库，不是增量更新
     * @throws IOException 库变更、对象类型冲突、写入或发布失败
     * @throws IllegalArgumentException 候选为空或不同名称生成同一文件名；检查在写盘前完成
     */
    void replaceRecords(List<MemoryRecord> expected, List<MemoryDraft> drafts) throws IOException {
        List<MemoryRecord> snapshot = List.copyOf(expected);
        List<MemoryDraft> candidates = List.copyOf(drafts);
        if (snapshot.isEmpty() || candidates.size() > snapshot.size())
            throw new IllegalArgumentException("整理只能替换非空旧库，不能增加记录数量");
        Path root = checkedRoot();
        MemoryLibraryLock.run(root, () -> {
            MemoryTransaction.recover(root, warnings);
            replaceLocked(root, snapshot, candidates);
            return null;
        });
    }

    /**
     * 已持有库锁后准备整库替换。先检查模型输入快照仍是当前库，再完整保存磁盘备份。
     * 候选不是增量，任何非法条目都拒绝整批，不能用丢弃坏条目的方式误删旧知识。
     */
    private void replaceLocked(Path root, List<MemoryRecord> snapshot, List<MemoryDraft> drafts) throws IOException {
        if (!snapshot.equals(scanRecords())) throw new IOException("整理期间记忆库发生变化，未发布旧快照的整理结果");
        var documents = new LinkedHashMap<String, String>();
        var occupied = new java.util.HashSet<String>();
        var preferred = new java.util.HashMap<String, String>();
        for (var record : snapshot) preferred.put(record.name(), record.filename());
        try (var entries = Files.list(root)) {
            entries.filter(p -> snapshot.stream().noneMatch(r -> r.filename().equals(p.getFileName().toString())))
                    .forEach(p -> occupied.add(p.getFileName().toString()));
        }
        var names = new java.util.HashSet<String>();
        for (MemoryDraft draft : drafts) {
            if (!names.add(draft.name())) throw new IllegalArgumentException("整理后的记忆名称重复");
            String oldName = preferred.get(draft.name());
            String filename = oldName != null && occupied.add(oldName) ? oldName : availableFilename(draft.name(), occupied);
            documents.put(filename, memoryDocument(draft));
        }
        if (documents.isEmpty()) throw new IllegalArgumentException("不允许用空整理结果清空记忆库");
        Path index = root.resolve("MEMORY.md");
        checkIndexTarget(index);
        var originals = new LinkedHashMap<String, String>();
        for (MemoryRecord record : snapshot) {
            Path path = root.resolve(record.filename());
            if (!Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
                throw new IOException("整理只允许替换普通非链接记忆文件: " + record.filename());
            }
            originals.put(record.filename(), record.content());
        }
        // prepare 返回之前没有删除任何旧记录。READY 与原文均在磁盘，下次读取可恢复中断。
        StringBuilder catalog = new StringBuilder();
        for (var entry : documents.entrySet()) {
            MemoryRecord record = parseRecord(entry.getKey(), entry.getValue());
            if (record == null) throw new IOException("候选编码后不能读回为合法记忆");
            catalog.append("- [").append(indexText(record.name())).append("](")
                    .append(record.filename()).append(") - ").append(indexText(record.description())).append('\n');
        }
        MemoryTransaction transaction = MemoryTransaction.prepare(root, originals, documents, catalog.toString());
        try {
            if (!snapshot.equals(scanRecords())) throw new IOException("准备整理候选期间记忆库发生变化");
            for (String name : originals.keySet()) Files.delete(root.resolve(name));
            for (var entry : documents.entrySet()) {
                // 候选先完整写到临时文件再原子发布，进程中断不会留下半个新正文妨碍恢复。
                MemoryTransaction.atomicReplace(root.resolve(entry.getKey()), entry.getValue());
            }
            checkIndexTarget(index);
            MemoryTransaction.atomicReplace(index, catalog.toString());
            transaction.commit();
        } catch (IOException | RuntimeException error) {
            try { transaction.rollback(); }
            catch (IOException restoreFailure) { error.addSuppressed(restoreFailure); }
            throw error;
        }
    }

    /** 读写共用实际库根围栏；调用方分别决定缺库返回空结果还是显式创建。 */
    private Path checkedRoot() throws IOException {
        new com.jadecode.config.WorkspacePaths(workspaceRoot).directory(memoryDir, false);
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
        // Linux 常见单个文件名上限为 255 字节，不是 255 个汉字。预留扩展名与哈希位置。
        // 保留完整码点；长名称的稳定哈希防止截成相同前缀后相互冲突。
        if (slug.getBytes(StandardCharsets.UTF_8).length > 180) slug = utf8Prefix(slug, 180) + "-" + nameHash(name);
        return slug + ".md";
    }

    /** 已有同名由上层跳过；这里只为不同名称分配不覆盖旧对象的文件名。 */
    private static String availableFilename(String name, Set<String> occupied) {
        String base = memoryFilename(name);
        if (occupied.add(base)) return base;
        String alternative = base.substring(0, base.length() - 3) + "-" + nameHash(name) + ".md";
        if (!occupied.add(alternative)) throw new IllegalArgumentException("不同记忆的目标文件仍冲突，未覆盖任何记录");
        return alternative;
    }

    /** 名称哈希只用于区分路径，不拿正文哈希代替记忆内容；不依赖平台默认编码。 */
    private static String nameHash(String name) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(name.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("JDK 缺少 SHA-256", impossible); }
    }

    /** 在 UTF-8 字节预算内取最长完整码点前缀，不会制造半个汉字或 emoji。 */
    private static String utf8Prefix(String text, int maxBytes) {
        int end = 0, bytes = 0;
        while (end < text.length()) {
            int code = text.codePointAt(end), next = Character.charCount(code);
            int size = text.substring(end, end + next).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > maxBytes) break;
            bytes += size; end += next;
        }
        return text.substring(0, end);
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
    private static void deleteCreated(Path path, Throwable original) {
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
        /** 辅助整理只需要正文；元数据另有字段，避免再发送一遍完整 frontmatter 浪费输入。 */
        public String body() {
            String normalized = content.replace("\r\n", "\n");
            int closing = normalized.indexOf("\n---\n");
            return closing < 0 ? content : normalized.substring(closing + 5).strip();
        }
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
