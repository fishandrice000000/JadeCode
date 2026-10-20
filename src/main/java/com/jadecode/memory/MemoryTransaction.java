package com.jadecode.memory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import com.jadecode.util.Json;

/**
 * 整理整库前的磁盘备份与中断恢复，只由持有库锁的 MemoryStore 使用。
 *
 * <p>先把旧正文、新正文和文件清单写入专属备份目录并刷新，再写 READY；只有这之后才可改旧库。
 * 最后写 COMMITTED。下次操作遇到 READY 却没有完成标记时恢复旧正文，避免进程退出后只能靠内存恢复。
 * 成功或回滚后都保留备份，不自动清理用户的恢复材料。不能保证恶意并发修改或硬件不兑现刷新时的数据安全。</p>
 */
final class MemoryTransaction {
    private final Path root, backup;
    private final Map<String, String> originals, replacements;
    private final String oldIndex, newIndex;

    private MemoryTransaction(Path root, Path backup, Map<String, String> originals,
            Map<String, String> replacements, String oldIndex, String newIndex) {
        this.root = root; this.backup = backup; this.originals = originals;
        this.replacements = replacements; this.oldIndex = oldIndex; this.newIndex = newIndex;
    }

    /**
     * 保存完整可恢复材料；本方法失败时旧库完全未动，不创建 READY。
     * 文件名已经由 Store 分配，本层仍检查单层文件名以防将来调用者把目录写进清单。
     */
    static MemoryTransaction prepare(Path root, Map<String, String> originals, Map<String, String> replacements, String newIndex) throws IOException {
        Path backup = Files.createTempDirectory(root, ".memory-backup-");
        Path old = Files.createDirectory(backup.resolve("old")), next = Files.createDirectory(backup.resolve("new"));
        for (var entry : originals.entrySet()) write(old.resolve(filename(entry.getKey())), entry.getValue());
        for (var entry : replacements.entrySet()) write(next.resolve(filename(entry.getKey())), entry.getValue());
        Path index = root.resolve("MEMORY.md");
        String oldIndex = Files.exists(index, LinkOption.NOFOLLOW_LINKS) ? readPlain(index) : null;
        if (oldIndex != null) write(backup.resolve("index-old.txt"), oldIndex);
        write(backup.resolve("index-new.txt"), newIndex);
        var manifest = Json.mapper().createObjectNode();
        manifest.put("version", 1).put("hadIndex", oldIndex != null);
        var oldNames = manifest.putArray("originals");
        var newNames = manifest.putArray("replacements");
        originals.keySet().forEach(oldNames::add); replacements.keySet().forEach(newNames::add);
        write(backup.resolve("manifest.json"), manifest.toString());
        force(old); force(next); force(backup); force(root);
        write(backup.resolve("READY"), "prepared\n"); force(backup);
        return new MemoryTransaction(root, backup, Map.copyOf(originals), Map.copyOf(replacements), oldIndex, newIndex);
    }

    /** 新记录与索引已经发布后刷新目录，再记录成功。标记前崩溃仍按旧库恢复。 */
    void commit() throws IOException {
        force(root); write(backup.resolve("COMMITTED"), "committed\n"); force(backup);
    }

    /**
     * 先完整检查当前文件，再恢复旧库；未知外部内容不覆盖，备份留给用户人工处理。
     * 已恢复一半又退出时可重入：旧正文、新正文或缺失三种状态均可继续恢复。
     */
    void rollback() throws IOException {
        // commit 的最后一次目录刷新也可能失败。先取消可能已写入的成功标记，
        // 若本次回滚再次中断，下次仍必须按 READY 恢复，不能误以为发布已完整结束。
        Files.deleteIfExists(backup.resolve("COMMITTED")); force(backup);
        var known = new java.util.LinkedHashSet<>(originals.keySet()); known.addAll(replacements.keySet());
        for (String name : known) {
            Path file = root.resolve(filename(name));
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                String current = readPlain(file);
                if (!current.equals(originals.get(name)) && !current.equals(replacements.get(name)))
                    throw new IOException("记忆恢复发现未知外部修改；磁盘备份保留，未覆盖该文件");
            }
        }
        Path index = root.resolve("MEMORY.md");
        if (Files.exists(index, LinkOption.NOFOLLOW_LINKS)) {
            String current = readPlain(index);
            if (!current.equals(oldIndex) && !current.equals(newIndex))
                throw new IOException("记忆恢复发现未知索引修改；磁盘备份保留，未覆盖该文件");
        }
        // 不能先删除所有文件再写回；逐项原子替换，恢复过程中也尽量保留可用正文。
        for (var entry : originals.entrySet()) atomicReplace(root.resolve(entry.getKey()), entry.getValue());
        for (String name : replacements.keySet()) if (!originals.containsKey(name)) Files.deleteIfExists(root.resolve(name));
        if (oldIndex == null) Files.deleteIfExists(index); else atomicReplace(index, oldIndex);
        force(root); write(backup.resolve("ROLLED_BACK"), "restored\n"); force(backup);
    }

    /**
     * 每次库操作前检查未完成事务；没有 READY 的目录从未获准修改旧库，不自动删除它。
     * 清单和旧/新文件均只读普通文件，不跟随链接；坏清单会明确报 IO 故障而非静默丢数据。
     */
    static void recover(Path root, Consumer<String> warnings) throws IOException {
        List<Path> backups;
        try (var entries = Files.list(root)) {
            backups = entries.filter(p -> p.getFileName().toString().startsWith(".memory-backup-")).sorted().toList();
        }
        for (Path backup : backups) {
            if (!Files.isDirectory(backup, LinkOption.NOFOLLOW_LINKS)) throw new IOException("记忆备份目录不是普通目录");
            if (!Files.exists(backup.resolve("READY"), LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(backup.resolve("COMMITTED"), LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(backup.resolve("ROLLED_BACK"), LinkOption.NOFOLLOW_LINKS)) continue;
            var manifest = Json.mapper().readTree(readPlain(backup.resolve("manifest.json")));
            if (manifest == null || manifest.path("version").asInt() != 1 || !manifest.path("hadIndex").isBoolean())
                throw new IOException("记忆恢复清单格式无效");
            Map<String, String> old = readDocuments(backup, "old", manifest.get("originals"));
            Map<String, String> next = readDocuments(backup, "new", manifest.get("replacements"));
            new MemoryTransaction(root, backup, old, next,
                    manifest.path("hadIndex").booleanValue() ? readPlain(backup.resolve("index-old.txt")) : null,
                    readPlain(backup.resolve("index-new.txt"))).rollback();
            warnings.accept("[Warning] [Memory] 已从磁盘备份恢复一次未完成的整理；备份仍保留");
        }
    }

    /** 校验清单后再读恢复正文，拒绝目录逃逸、重名或空清单。 */
    private static Map<String, String> readDocuments(Path backup, String directory, com.fasterxml.jackson.databind.JsonNode names) throws IOException {
        if (names == null || !names.isArray() || names.isEmpty()) throw new IOException("记忆恢复文件清单无效");
        Path dir = backup.resolve(directory);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw new IOException("记忆恢复正文目录无效");
        var documents = new LinkedHashMap<String, String>();
        for (var item : names) {
            if (!item.isTextual()) throw new IOException("记忆恢复文件名不是文字");
            String name = filename(item.textValue());
            if (documents.putIfAbsent(name, readPlain(dir.resolve(name))) != null) throw new IOException("记忆恢复文件名重复");
        }
        return documents;
    }

    private static String filename(String value) throws IOException {
        if (value.isEmpty() || !value.endsWith(".md") || value.equals("MEMORY.md") || value.contains("/") || value.contains("\\"))
            throw new IOException("记忆恢复文件名必须是单层记录名");
        return value;
    }

    private static String readPlain(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("记忆备份或恢复目标不是普通文件");
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** 新文件排他创建并刷新正文；恢复和索引使用同目录临时文件再原子替换。 */
    static void write(Path file, String text) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var data = StandardCharsets.UTF_8.encode(text);
            while (data.hasRemaining()) channel.write(data);
            channel.force(true);
        }
    }

    static void atomicReplace(Path target, String text) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("记忆发布目标不是普通文件");
        Path temporary = Files.createTempFile(target.getParent(), ".memory-publish-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var data = StandardCharsets.UTF_8.encode(text);
                while (data.hasRemaining()) channel.write(data);
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    /** Linux 本地文件系统上的目录刷新；不支持时明确失败，不能假称备份持久化完成。 */
    private static void force(Path directory) throws IOException {
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }
}
