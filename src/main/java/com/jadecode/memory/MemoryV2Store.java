package com.jadecode.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Json;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/**
 * v2 的全局文件库：正文、来源、待处理作业和反馈分开保存。
 *
 * <p>模型只提出候选；文件名、作用域、版本及发布顺序由本类确定。普通操作持短文件锁，
 * 不在此锁内等待模型。正式手册与短摘要复用旧库的备份事务，失败不覆盖未知外部修改。
 * 这是应用内存储约束，不是对任意 Shell 的操作系统沙箱。</p>
 */
final class MemoryV2Store {
    static final String EMPTY_SUMMARY = "v2\n\n# Memory routes\n\nNo consolidated memories yet.\n";
    static final String EMPTY_HANDBOOK = "# Memory handbook\n\nNo consolidated memories yet.\n";
    private final Path root, workspace;
    private final String scope;
    private final Consumer<String> warnings;

    /** 只绑定显式目录；构造不创建用户文件，也不读取模型配置。 */
    MemoryV2Store(Path root, Path workspace, Consumer<String> warnings) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.workspace = workspace.toRealPath();
        this.scope = hash(this.workspace.toString());
        this.warnings = warnings;
    }

    /** 全局默认位置；测试通过构造参数注入临时目录，不修改 user.home。 */
    static Path defaultRoot() { return Path.of(System.getProperty("user.home"), ".jadecode", "memory"); }
    Path root() { return root; }
    String scope() { return scope; }
    Path workspace() { return workspace; }

    /**
     * 首次实际使用才建立目录与空基线；每一级都拒绝符号链接。
     * 库锁内只初始化缺失文件，不覆盖既有手册；残缺或被外部替换的库在后续检查中报错，不猜测修复正文。
     */
    void initialize() throws IOException {
        directory(root, true);
        MemoryLibraryLock.run(root, () -> {
            for (String dir : List.of("rollout_summaries", "pending", "events", "legacy", "state/jobs", "state/materials"))
                directory(root.resolve(dir), true);
            MemoryTransaction.recover(root, warnings);
            Path manifest = root.resolve("memory_manifest.json");
            if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
                // 未识别旧全局库不能套空清单，否则首次整理可能覆盖用户已有手册。
                for (var entry : Map.of("MEMORY.md", EMPTY_HANDBOOK, "memory_summary.md", EMPTY_SUMMARY).entrySet()) {
                    Path existing = root.resolve(entry.getKey());
                    if (Files.exists(existing, LinkOption.NOFOLLOW_LINKS) && !text(existing).equals(entry.getValue()))
                        throw new IOException("Unrecognized existing global memory; explicit import required");
                }
                create(manifest, "{\"revision\":0,\"sections\":{}}");
            } else if (json(manifest).path("revision").asLong() > 0
                    && (!Files.exists(root.resolve("MEMORY.md"), LinkOption.NOFOLLOW_LINKS)
                    || !Files.exists(root.resolve("memory_summary.md"), LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("Published memory artifact is missing; review required");
            }
            create(root.resolve("MEMORY.md"), EMPTY_HANDBOOK);
            create(root.resolve("memory_summary.md"), EMPTY_SUMMARY);
            return null;
        });
    }

    /** 一次锁内读取同一代正文和清单；返回不可变字符串，不把磁盘可变对象交给模型。 */
    Snapshot snapshot() throws IOException {
        return MemoryLibraryLock.run(root, () -> {
            MemoryTransaction.recover(root, warnings);
            JsonNode manifest = json(root.resolve("memory_manifest.json"));
            if (!manifest.path("revision").isIntegralNumber() || !manifest.path("sections").isObject())
                throw new IOException("Invalid memory manifest");
            JsonNode local = manifest.path("sections").path(scope), global = manifest.path("sections").path("global");
            return new Snapshot(manifest.path("revision").asLong(), text(root.resolve("memory_summary.md")),
                    text(root.resolve("MEMORY.md")), local.path("baseline").asText(), local.path("updated_at").asText("1970-01-01T00:00:00Z"),
                    local.path("summary").asText(EMPTY_SUMMARY), local.path("handbook").asText(EMPTY_HANDBOOK),
                    global.path("summary").asText(""), global.path("handbook").asText(""),
                    java.util.stream.StreamSupport.stream(local.path("processed_notes").spliterator(), false).map(JsonNode::asText).toList(),
                    hash(text(root.resolve("memory_manifest.json"))));
        });
    }

    record Snapshot(long revision, String summary, String handbook, String baseline, String updatedAt,
            String workspaceSummary, String workspaceHandbook, String globalSummary, String globalHandbook,
            List<String> processedNotes, String manifestHash) { }
    record Artifacts(String summary, String handbook) { }

    /**
     * 只发布已校验候选。模型等待期间清单或正文有变化就拒绝，不能以旧快照盖掉新修正。
     * 先准备可恢复备份，再替换摘要/清单/手册，最后提交；恢复材料保留用于诊断。
     */
    void publish(Snapshot expected, Map<String, Artifacts> changes, String baseline, List<String> sources) throws IOException {
        publish(expected, changes, baseline, sources, expected.processedNotes());
    }

    /** 成功发布同时登记已处理笔记；失败时不提前把纠正标成已应用。 */
    void publish(Snapshot expected, Map<String, Artifacts> changes, String baseline, List<String> sources, List<String> processedNotes) throws IOException {
        MemoryLibraryLock.run(root, () -> {
            MemoryTransaction.recover(root, warnings);
            Path manifest = root.resolve("memory_manifest.json"), shortFile = root.resolve("memory_summary.md");
            JsonNode current = json(manifest);
            if (current.path("revision").asLong() != expected.revision()
                    || !hash(text(manifest)).equals(expected.manifestHash())
                    || !text(shortFile).equals(expected.summary()) || !text(root.resolve("MEMORY.md")).equals(expected.handbook()))
                throw new IOException("Memory changed while consolidation was running");
            var next = (ObjectNode) current.deepCopy();
            next.put("revision", expected.revision() + 1);
            ObjectNode sections = (ObjectNode) next.path("sections");
            // 仅允许本工作区及明确的全局用户知识变化，其它项目的块由程序原样保留。
            for (var change : changes.entrySet()) {
                if (!change.getKey().equals(scope) && !change.getKey().equals("global")) throw new IOException("Cannot edit another workspace's memory");
                ObjectNode section = sections.putObject(change.getKey());
                section.put("summary", change.getValue().summary()).put("handbook", change.getValue().handbook())
                        .put("workspace", change.getKey().equals("global") ? "global" : workspace.toString())
                        .put("updated_at", Instant.now().toString()).put("baseline", baseline);
                section.set("sources", Json.mapper().valueToTree(sources));
            }
            // 即使没有正文改动也保存本工作区的处理基线，避免同一份材料反复调用模型。
            ObjectNode local = sections.path(scope) instanceof ObjectNode object ? object : sections.putObject(scope);
            local.put("baseline", baseline).put("updated_at", Instant.now().toString());
            local.set("processed_notes", Json.mapper().valueToTree(processedNotes));
            StringBuilder summary = new StringBuilder("v2\n\n# Memory routes\n");
            StringBuilder handbook = new StringBuilder("# Memory handbook\n");
            var names = new ArrayList<String>(); sections.fieldNames().forEachRemaining(names::add); Collections.sort(names);
            for (String name : names) {
                JsonNode section = sections.get(name);
                String heading = "\n## Scope: " + name + "\nWorkspace: " + section.path("workspace").asText() + "\n\n";
                summary.append(heading).append(section.path("summary").asText());
                handbook.append(heading).append(section.path("handbook").asText());
            }
            Map<String, String> originals = Map.of("memory_summary.md", expected.summary(), "memory_manifest.json", text(manifest));
            Map<String, String> replacements = Map.of("memory_summary.md", summary.toString(), "memory_manifest.json", next.toString());
            var transaction = MemoryTransaction.prepare(root, originals, replacements, handbook.toString());
            try {
                for (var item : replacements.entrySet()) MemoryTransaction.atomicReplace(root.resolve(item.getKey()), item.getValue());
                MemoryTransaction.atomicReplace(root.resolve("MEMORY.md"), handbook.toString());
                transaction.commit();
            } catch (IOException | RuntimeException failure) {
                try { transaction.rollback(); } catch (IOException recovery) { failure.addSuppressed(recovery); }
                throw failure;
            }
            return null;
        });
    }

    /** JSON 作业用程序计算的哈希命名；同任务重复登记不会覆盖已经处理的作业。 */
    void enqueue(ObjectNode job) throws IOException {
        MemoryLibraryLock.run(root, () -> {
            create(root.resolve("state/jobs/" + id(job.path("id").asText()) + ".json"), job.toString());
            return null;
        });
    }

    /** 返回待处理作业的独立副本；进程退出遗留的 running 由持有维护锁的新进程接管。 */
    List<ObjectNode> jobs() throws IOException {
        return MemoryLibraryLock.run(root, () -> nodes(root.resolve("state/jobs")));
    }

    /** 已持有维护锁的 worker 保存重试状态；文件锁只覆盖短写入。 */
    void saveJob(ObjectNode job) throws IOException {
        MemoryLibraryLock.run(root, () -> {
            replace(root.resolve("state/jobs/" + id(job.path("id").asText()) + ".json"), job.toString());
            return null;
        });
    }

    /**
     * 保存不可变回顾及来源元数据。正文先发布、元数据后发布，只有完整元数据才进入整理输入。
     * 同 ID 不允许换内容；重试遇到相同正文正常复用，遇到未知内容明确失败。
     */
    void material(String materialId, String summary, ObjectNode metadata) throws IOException {
        String name = id(materialId);
        MemoryLibraryLock.run(root, () -> {
            immutable(root.resolve("rollout_summaries/" + name + ".md"), summary);
            Path target = root.resolve("state/materials/" + name + ".json");
            String encoded = metadata.toString();
            immutable(target, encoded);
            return null;
        });
    }

    /** 当前工作区及全局材料可见，其它项目材料不会进入本次整理工作集。 */
    List<ObjectNode> materials() throws IOException {
        return MemoryLibraryLock.run(root, () -> nodes(root.resolve("state/materials")).stream()
                .filter(n -> n.path("scope").asText().equals(scope) || n.path("scope").asText().equals("global")).toList());
    }

    /**
     * 用户纠正单独入箱，源请求与内容分开保存；这里只承认候选，不假装正式记忆已更新。
     * 重复工具调用使用同一个笔记 ID 时只保存一份，正文改变则报错。
     */
    String note(String noteId, String kind, String body, String userRequest, String session, String turn) throws IOException {
        String name = id(noteId);
        ObjectNode note = Json.mapper().createObjectNode();
        note.put("id", name).put("scope", scope).put("kind", kind).put("body", body).put("user_request", userRequest)
                .put("session_id", session).put("turn_id", turn);
        MemoryLibraryLock.run(root, () -> { immutable(root.resolve("pending/" + name + ".json"), note.toString()); return null; });
        return name;
    }

    /** 保留原始纠正材料，是否已处理由成功发布的 baseline 表达，不能先删除再整理。 */
    List<ObjectNode> notes() throws IOException {
        return MemoryLibraryLock.run(root, () -> nodes(root.resolve("pending")).stream()
                .filter(n -> n.path("scope").asText().equals(scope) || n.path("scope").asText().equals("global")).toList());
    }

    /**
     * 反馈采用独立追加日志；主键由来源事件计算。日志不复制记忆正文，失败不能改变工具结果。
     * 汇总按 event_id 去重，避免重复投递凭空增加使用次数。
     */
    void feedback(ObjectNode event) throws IOException {
        MemoryLibraryLock.run(root, () -> {
            Path path = root.resolve("events/feedback.jsonl");
            plainIfExists(path);
            try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                var bytes = StandardCharsets.UTF_8.encode(event.toString() + "\n");
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            return null;
        });
    }

    /** 反馈只用于排序和复核；尾部不完整行不计数，也不据此删除任何正式记忆。 */
    List<ObjectNode> feedback() throws IOException {
        return MemoryLibraryLock.run(root, () -> {
            Path path = root.resolve("events/feedback.jsonl");
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return List.of();
            var unique = new LinkedHashMap<String, ObjectNode>();
            for (String line : text(path).lines().toList()) {
                try {
                    JsonNode node = Json.mapper().readTree(line);
                    if (node instanceof ObjectNode object && object.path("event_id").isTextual())
                        unique.putIfAbsent(object.path("event_id").asText(), object);
                } catch (IOException malformed) { warnings.accept("[Warning] [Memory] Ignored a malformed feedback event"); }
            }
            return List.copyOf(unique.values());
        });
    }

    /**
     * 旧项目库只复制留档：按正文哈希命名，保留映射和项目作用域，原文件不移动、不删除。
     * 旧库中的泛化偏好或教程不会因迁移自动成为正式手册内容。
     */
    void archiveLegacy() throws IOException {
        Path legacy = workspace.resolve(".jadecode/memory");
        if (legacy.equals(root) || !Files.exists(legacy, LinkOption.NOFOLLOW_LINKS)) return;
        directory(legacy, false);
        Path target = root.resolve("legacy/" + scope);
        directory(target, true);
        try (var files = Files.list(legacy)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList()) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Legacy memory is not a regular file");
                String body = text(file), name = hash(body);
                MemoryLibraryLock.run(root, () -> {
                    immutable(target.resolve(name + ".md"), body);
                    var mapping = Json.mapper().createObjectNode();
                    mapping.put("original", file.toString()).put("scope", scope).put("sha256", name).put("status", "legacy-unverified");
                    immutable(target.resolve(hash(file.toString() + ":" + name) + ".json"), mapping.toString());
                    return null;
                });
            }
        }
    }

    /** 只读入口仅允许正式 Markdown 与回顾；配置、状态和备份不因记忆权限而暴露。 */
    boolean readable(Path path) {
        Path p = path.toAbsolutePath().normalize();
        if (!p.startsWith(root)) return false;
        String relative = root.relativize(p).toString().replace('\\', '/');
        if (!relative.equals("MEMORY.md") && !relative.equals("memory_summary.md")
                && !relative.matches("rollout_summaries/[a-f0-9]{64}\\.md")) return false;
        try { directory(p.getParent(), false); plainIfExists(p); return Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS); }
        catch (IOException invalid) { return false; }
    }

    /** 根据模型给出的受限相对名读取来源；绝对路径和父目录跳转不会交给文件系统。 */
    String readSource(String relative) throws IOException {
        if (!relative.matches("rollout_summaries/[a-f0-9]{64}\\.md")) throw new IOException("Invalid memory source reference");
        directory(root.resolve("rollout_summaries"), false);
        return text(root.resolve(relative));
    }

    /** 每个项目持久保存整理失败退避，避免重启或每秒轮询重复付费；成功后随新基线重置。 */
    ObjectNode maintenanceState() throws IOException {
        return MemoryLibraryLock.run(root, () -> {
            Path path = root.resolve("state/maintenance-" + scope + ".json");
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Json.mapper().createObjectNode();
            JsonNode node = json(path);
            if (!(node instanceof ObjectNode object)) throw new IOException("Invalid maintenance retry state");
            return object;
        });
    }

    /** 在维护锁内调用，仅短暂占用文件库锁；保留尝试次数及下一次允许时间。 */
    void maintenanceState(ObjectNode state) throws IOException {
        MemoryLibraryLock.run(root, () -> {
            Path path = root.resolve("state/maintenance-" + scope + ".json");
            plainIfExists(path); replace(path, state.toString()); return null;
        });
    }

    /** 扫描只接受普通 JSON；坏状态明确失败，不把材料丢失伪装成没有内容。 */
    private static List<ObjectNode> nodes(Path dir) throws IOException {
        directory(dir, false);
        var result = new ArrayList<ObjectNode>();
        try (var files = Files.list(dir)) {
            for (Path path : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                JsonNode value = json(path);
                if (!(value instanceof ObjectNode object)) throw new IOException("Invalid memory state object");
                result.add(object);
            }
        }
        return List.copyOf(result);
    }

    /** 逐级拒绝符号链接；并发创建同名普通目录允许复用，不接受其它对象。 */
    static void directory(Path path, boolean create) throws IOException {
        Path absolute = path.toAbsolutePath().normalize(), cursor = absolute.getRoot();
        for (Path part : absolute) {
            cursor = cursor.resolve(part);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Memory path contains a non-directory or symlink");
            } else if (create) {
                try {
                    try { Files.createDirectory(cursor, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))); }
                    catch (UnsupportedOperationException unsupported) { Files.createDirectory(cursor); }
                }
                catch (FileAlreadyExistsException raced) { if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) throw raced; }
            } else throw new NoSuchFileException(cursor.toString());
        }
    }

    /** 只读有限大小的普通 UTF-8 文件；不跟随最终符号链接。 */
    static String text(Path path) throws IOException {
        plainIfExists(path);
        if (Files.size(path) > 8_000_000) throw new IOException("Memory file exceeds the input limit");
        return Files.readString(path, StandardCharsets.UTF_8);
    }
    static JsonNode json(Path path) throws IOException {
        JsonNode value = Json.mapper().readTree(text(path));
        if (value == null) throw new IOException("Empty memory state");
        return value;
    }
    private static void plainIfExists(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Memory path is not a regular file");
    }
    private static void create(Path path, String content) throws IOException {
        plainIfExists(path);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        // 调用者持库锁；先写临时完整文件再原子发布，崩溃不能留下半份作业 JSON。
        replace(path, content);
    }
    /** 原子替换并刷新目录项，用于持久作业状态；正式三文件发布仍必须走恢复事务。 */
    private static void replace(Path path, String content) throws IOException {
        MemoryTransaction.atomicReplace(path, content);
        try (var directory = FileChannel.open(path.getParent(), StandardOpenOption.READ)) { directory.force(true); }
    }
    private static void immutable(Path path, String content) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!text(path).equals(content)) throw new IOException("Immutable memory evidence changed");
        } else create(path, content);
    }
    private static String id(String id) throws IOException {
        if (!id.matches("[a-f0-9]{64}")) throw new IOException("Invalid memory identity");
        return id;
    }
    /** UTF-8 SHA-256 仅用于身份、版本与去重，不代表内容可信，也不用于隐匿秘密。 */
    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
