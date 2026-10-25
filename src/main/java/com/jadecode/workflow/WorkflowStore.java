package com.jadecode.workflow;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.config.WorkspacePaths;
import com.jadecode.util.Json;
import static java.nio.file.StandardOpenOption.*;

/**
 * 每个 run 一个目录和文件锁：快照说明身份，journal 保存可复用步骤，output 保存最终产物。
 * 保存对话与保存步骤是不同责任，本类不使用 SessionJournal，也不执行模型或流程。
 * 文件大小有限、路径拒绝软链；这些检查不构成对恶意并发替换文件的沙箱。
 */
final class WorkflowStore {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final WorkspacePaths paths;

    /** 只绑定工作区布局，实际新建/读取在 open 时发生。 */
    WorkflowStore(Path workspace) { paths = new WorkspacePaths(workspace); }

    /**
     * 排他预留或打开 run，全程持锁；恢复材料全部验证通过后才交给运行时改写状态。
     * args=null 仅在恢复时表示使用原参数；恢复版本/模型身份变化或坏 journal 均拒绝。
     */
    Session open(WorkflowDefinition definition, ObjectNode args, String resume, String runner) throws IOException {
        // 先拒绝非法恢复 ID，再访问工作区；输入错误不能顺带创建运行目录。
        if (resume != null && !resume.matches("wf_[0-9a-f]{32}")) throw new IOException("Invalid workflow run ID");
        paths.directory(paths.workflows(), true);
        String id = resume;
        Path directory;
        if (id == null) {
            // 目录的排他创建就是 ID 预留；不能先打开 journal 再发现撞到了旧 run。
            for (int i = 0; ; i++) {
                if (i == 32) throw new IOException("Cannot reserve workflow run ID");
                id = "wf_" + UUID.randomUUID().toString().replace("-", "");
                directory = paths.workflows().resolve(id);
                try { Files.createDirectory(directory); break; }
                catch (FileAlreadyExistsException collision) { /* 只换新 ID，保留旧目录。 */ }
            }
        } else {
            directory = paths.workflows().resolve(id);
            paths.directory(directory, false);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Workflow run not found");
        }
        paths.directory(directory, false);
        Path lockPath = directory.resolve("run.lock");
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Workflow lock is not a regular file");
        FileChannel channel = FileChannel.open(lockPath, CREATE, WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock lock = null;
        try {
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException busy) { throw new IOException("Workflow run is already active"); }
            if (lock == null) throw new IOException("Workflow run is already active");
            ObjectNode header;
            var cache = new LinkedHashMap<String, JsonNode>();
            Path journal = directory.resolve("journal.jsonl");
            if (resume != null) {
                JsonNode saved = read(directory.resolve("snapshot.json"));
                if (!saved.isObject() || saved.path("format_version").asInt() != 1 || !id.equals(saved.path("run_id").asText())
                        || !definition.name().equals(saved.path("workflow").asText())
                        || !definition.version().equals(saved.path("workflow_version").asText())
                        || !runner.equals(saved.path("runner_identity").asText()) || !saved.path("args").isObject()
                        || !java.util.Set.of("running", "completed", "failed", "cancelled").contains(saved.path("status").asText()))
                    throw new IOException("Workflow resume identity or snapshot is invalid");
                if (args != null && !args.equals(saved.get("args"))) throw new IOException("Workflow resume args differ from original input");
                args = ((ObjectNode) saved.get("args")).deepCopy();
                WorkflowSchema.validate(args, definition.argsSchema());
                // 损坏材料不能靠“重跑一遍”修好，否则会覆盖旧成功证据；先完整读取再打开追加句柄。
                String records = readText(journal);
                if (!records.isEmpty() && !records.endsWith("\n")) throw new IOException("Incomplete workflow journal tail");
                for (String line : records.lines().toList()) {
                    JsonNode entry = WorkflowSchema.parse(line);
                    if (!entry.isObject() || entry.size() != 2 || !entry.path("key").asText().matches("[0-9a-f]{64}") || !entry.has("value"))
                        throw new IOException("Invalid workflow journal record");
                    String key = entry.get("key").asText();
                    JsonNode previous = cache.putIfAbsent(key, entry.get("value"));
                    if (previous != null && !previous.equals(entry.get("value"))) throw new IOException("Conflicting workflow journal records");
                }
                Path output = directory.resolve("output.json");
                if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) read(output);
                else if (saved.path("status").asText().equals("completed")) throw new IOException("Completed workflow output is missing");
                header = (ObjectNode) saved.deepCopy();
            } else {
                header = Json.mapper().createObjectNode().put("format_version", 1).put("run_id", id)
                        .put("workflow", definition.name()).put("workflow_version", definition.version()).put("runner_identity", runner);
                header.set("args", args.deepCopy());
            }
            FileChannel journalChannel = resume == null
                    ? FileChannel.open(journal, CREATE_NEW, WRITE, LinkOption.NOFOLLOW_LINKS)
                    : FileChannel.open(journal, WRITE, APPEND, LinkOption.NOFOLLOW_LINKS);
            return new Session(directory, header, cache, channel, lock, journalChannel);
        } catch (IOException | RuntimeException error) {
            if (lock != null) lock.release();
            channel.close();
            throw error;
        }
    }

    /** 用 NOFOLLOW 打开实际文件并限制读取量，拒绝软链、设备或超大材料；不创建缺失文件。 */
    private static String readText(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Workflow artifact is missing or not a regular file");
        try (FileChannel channel = FileChannel.open(path, READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() > MAX_BYTES) throw new IOException("Workflow artifact exceeds storage limit");
            var out = new java.io.ByteArrayOutputStream(); var buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) != -1) {
                buffer.flip(); out.write(buffer.array(), 0, buffer.remaining()); buffer.clear();
                if (out.size() > MAX_BYTES) throw new IOException("Workflow artifact grew beyond storage limit");
            }
            // 坏 UTF-8 不是一份可信缓存；严格解码，不用替换字符掩盖损坏。
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(out.toByteArray())).toString();
        }
    }

    /** 严格读取一个 JSON 产物；模型响应与磁盘采用同一完整值校验。 */
    private static JsonNode read(Path file) throws IOException { return WorkflowSchema.parse(readText(file)); }

    /**
     * 先写自己的临时文件并 force，再原子替换正式产物；失败不静默降级为直接覆盖。
     * 只清理本次创建的临时文件，旧 snapshot/output 在发布失败时保留；不承诺目录 fsync 的断电耐久。
     */
    private static void write(Path target, JsonNode value) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Workflow output target is not a regular file");
        byte[] bytes = (value.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Workflow output exceeds storage limit");
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel file = FileChannel.open(temporary, CREATE_NEW, WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) file.write(buffer); file.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    /** 持锁的一次运行。所有 journal/cache 访问同步，返回副本；关闭后迟到线程不能再写。 */
    static final class Session implements AutoCloseable {
        final Path directory;
        final ObjectNode header;
        private final Map<String, JsonNode> cache;
        private final FileChannel lockChannel, journal;
        private final FileLock lock;
        private boolean closed;

        /** 仅接管 open 已取得的资源；调用者必须 close，不能转交给另一次运行。 */
        Session(Path directory, ObjectNode header, Map<String, JsonNode> cache, FileChannel lockChannel, FileLock lock, FileChannel journal) {
            this.directory = directory; this.header = header; this.cache = cache;
            this.lockChannel = lockChannel; this.lock = lock; this.journal = journal;
        }

        /** 缓存不存在返回 Java null；缓存中的 JSON null 是合法值，两者必须区分。 */
        synchronized JsonNode cached(String key) { JsonNode value = cache.get(key); return value == null ? null : value.deepCopy(); }

        /** 先成功写盘再更新缓存；半次追加失败使本轮失败，恢复时拒绝坏尾部，不能假成功。 */
        synchronized void record(String key, JsonNode value) throws IOException {
            if (closed) throw new IOException("Workflow journal is closed");
            var entry = Json.mapper().createObjectNode().put("key", key); entry.set("value", value);
            byte[] bytes = (entry + "\n").getBytes(StandardCharsets.UTF_8);
            if (journal.size() + bytes.length > MAX_BYTES) throw new IOException("Workflow journal exceeds storage limit");
            ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) journal.write(buffer); journal.force(true);
            cache.put(key, value.deepCopy());
        }

        /** 保存状态；只有全部产物保存完成后，运行时才能返回 completed。 */
        synchronized void snapshot(String status, JsonNode usage) throws IOException {
            if (closed) throw new IOException("Workflow store is closed");
            ObjectNode state = header.deepCopy().put("status", status); state.set("usage", usage);
            write(directory.resolve("snapshot.json"), state);
        }

        /** 输出先发布，completed 状态后发布；任一步失败由外层返回错误而非伪造完成。 */
        synchronized void output(JsonNode result) throws IOException { write(directory.resolve("output.json"), result); }

        /** 即使关闭 journal 失败，也尝试释放锁；不删除运行证据或锁文件。 */
        @Override public synchronized void close() throws IOException {
            if (closed) return; closed = true;
            try { journal.close(); } finally { try { lock.release(); } finally { lockChannel.close(); } }
        }
    }
}
