package com.jadecode.workspace;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.util.Json;

/**
 * 将 Agent 明确选中的成品文件另存为可下载版本，一份文件对应一个成果编号。
 * 文件应在生成完成后发布；这里只串行登记成果，不占用整个工作区的执行屏障。
 * 发布后修改工作文件不影响交付副本；不读取 Git 基线，不生成补丁，也不自动打包目录。
 */
public final class ArtifactService {
    private final ManagedWorkspace workspace;

    ArtifactService(ManagedWorkspace workspace) {
        this.workspace = workspace;
    }

    /** 列出已登记文件的名称、大小和摘要；不返回私有副本路径或半成品。 */
    public synchronized ObjectNode list() throws IOException {
        var result = Json.mapper().createObjectNode();
        var rows = result.putArray("artifacts");
        for (Path record : workspace.records("artifacts")) {
            String id = record.getFileName().toString().replace(".json", "");
            rows.add(description(metadata(id)));
        }
        return result;
    }

    /**
     * 按服务分配的编号读取文件元数据；缺失必要字段时明确报错，不猜测文件类型。
     * 返回值为刚读出的对象，调用者修改它不会改写磁盘登记。
     */
    public synchronized ObjectNode metadata(String id) throws IOException {
        ManagedWorkspaceStore.id(id);
        var record = ManagedWorkspaceStore.read(workspace.metadata().resolve("artifacts").resolve(id + ".json"));
        if (!record.path("artifactId").asText().equals(id) || !record.path("filename").isTextual()
                || !record.path("size").isIntegralNumber() || !record.path("size").canConvertToLong()
                || record.path("size").asLong() < 0 || record.path("size").asLong() > WorkspaceFiles.MAX_BYTES
                || !record.path("sha256").asText().matches("[a-f0-9]{64}"))
            throw new IOException("Invalid published file metadata");
        return record;
    }

    /** 按编号取得固定文件副本；文件名只用于展示，不能作为服务器路径输入。 */
    public synchronized Path file(String id) throws IOException {
        metadata(id);
        Path file = WorkspaceFiles.resolve(workspace.metadata(), "artifacts/" + id + "/file");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Published file is unavailable");
        return file;
    }

    /**
     * 发布 1–64 个工作区相对文件路径，返回 artifacts 数组，每项均可单独下载。
     * 输入只读；权限回调逐文件执行，invalid 同时观察调用取消和权限版本变化。
     * 先准备全部副本再登记，失败撤销本次已登记文件；不改变工作文件或关闭会话。
     * 不等待工作区全局锁，调用方应先结束文件生成，再调用本工具。
     */
    public synchronized ObjectNode publish(ObjectNode input, ExecutionContext context, BooleanSupplier invalid,
            WorkspaceFiles.Check check) throws Exception {
        if (!input.path("paths").isArray() || input.path("paths").isEmpty() || input.path("paths").size() > 64)
            throw new IOException("Publish requires 1..64 relative file paths");
        var names = new LinkedHashSet<String>();
        for (var item : input.path("paths")) {
            if (!item.isTextual())
                throw new IOException("Artifact paths must be strings");
            String name = WorkspaceFiles.relative(item.asText()).toString();
            if (name.isEmpty() || name.equals(".") || !names.add(name))
                throw new IOException("Select distinct files, not a directory or duplicate path");
        }
        String summary = input.path("summary").asText();
        if (summary.length() > 8192)
            throw new IOException("Artifact summary limit exceeded");
        if (workspace.records("artifacts").size() + names.size() > 64)
            throw new IOException("Published file limit reached (64)");

        Path artifacts = WorkspaceFiles.directory(workspace.metadata().resolve("artifacts"));
        Path staging = Files.createTempDirectory(artifacts, ".pending-");
        var prepared = new LinkedHashMap<String, ObjectNode>();
        var registered = new ArrayList<String>();
        try {
            long remaining = WorkspaceFiles.MAX_BYTES;
            for (String name : names) {
                ensureValid(context, invalid);
                String id = UUID.randomUUID().toString();
                Path directory = WorkspaceFiles.directory(staging.resolve(id));
                long size = copyFile(name, directory.resolve("file"), remaining, context, invalid, check);
                remaining -= size;
                prepared.put(id,
                        Json.mapper().createObjectNode().put("artifactId", id).put("workspaceId", workspace.id())
                                .put("filename", Path.of(name).getFileName().toString()).put("size", size)
                                .put("sha256", WorkspaceFiles.sha256(directory.resolve("file")))
                                .put("created", Instant.now().toString()).put("summary", summary));
            }
            // 每个文件关闭并算好摘要后才对外登记；下载只读取登记过的固定副本。
            for (var entry : prepared.entrySet()) {
                ensureValid(context, invalid);
                String id = entry.getKey();
                Files.move(staging.resolve(id), artifacts.resolve(id), StandardCopyOption.ATOMIC_MOVE);
                registered.add(id);
                ManagedWorkspaceStore.write(artifacts.resolve(id + ".json"), entry.getValue());
            }
            var result = Json.mapper().createObjectNode();
            var rows = result.putArray("artifacts");
            prepared.values().forEach(record -> rows.add(description(record)));
            return result;
        } catch (Exception failure) {
            // 只清理由本次调用新建的成果，不触碰以前发布的版本。
            for (String id : registered) {
                try {
                    Files.deleteIfExists(artifacts.resolve(id + ".json"));
                    WorkspaceFiles.delete(artifacts.resolve(id));
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        } finally {
            WorkspaceFiles.delete(staging);
        }
    }

    /** 多文件会重复返回说明；展示时截到 512 字符，避免列表或工具结果随文件数膨胀。 */
    private static ObjectNode description(ObjectNode record) {
        ObjectNode result = record.deepCopy();
        String summary = record.path("summary").asText();
        result.put("summary", summary.substring(0, Math.min(512, summary.length())))
                .put("summaryTruncated", summary.length() > 512);
        return result;
    }

    /**
     * 检查明确选中的普通文件并流式复制；不扫描其他目录，不读取制作素材或导入基线。
     * 权限检查可能等待，因此在它之后重新解析路径；拒绝链接、目录和受保护入口。
     * remaining 是本次发布剩余字节额度，复制中持续检查，避免增长文件绕过上限。
     */
    private long copyFile(String name, Path target, long remaining, ExecutionContext context, BooleanSupplier invalid,
            WorkspaceFiles.Check check) throws IOException {
        if (WorkspaceFiles.excluded(Path.of(name)))
            throw new IOException("Protected or environment files cannot be published");
        Path source = WorkspaceFiles.resolve(workspace.path(), name);
        check.accept(source, name);
        ensureValid(context, invalid);
        source = WorkspaceFiles.resolve(workspace.path(), name);
        var attributes = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()
                || ((Number) Files.getAttribute(source, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).intValue() != 1)
            throw new IOException("Publish requires an ordinary, unlinked file: " + name);
        if (attributes.size() > remaining)
            throw new IOException("Published files exceed the size limit");
        long copied = 0;
        try (var in = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
                var out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[WorkspaceFiles.CHUNK];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                ensureValid(context, invalid);
                copied += count;
                if (copied > remaining)
                    throw new IOException("Published files exceed the size limit");
                out.write(buffer, 0, count);
            }
        }
        if (copied != attributes.size())
            throw new IOException("File size changed during publication; finish generation and retry");
        return copied;
    }

    /** 复制和登记之间仍可能取消；发现取消或权限失效时抛错，不发布不完整文件。 */
    private static void ensureValid(ExecutionContext context, BooleanSupplier invalid) throws IOException {
        if (context.isCancelled() || invalid.getAsBoolean())
            throw new IOException("Publication cancelled or authorization changed");
    }
}
