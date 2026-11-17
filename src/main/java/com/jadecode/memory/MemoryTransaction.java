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
 * 管理正式知识多文件发布的磁盘备份与恢复。
 *
 * <p>MemoryStore 持有库锁时先 prepare 保存新旧正文与清单并写 READY，再替换正式文件，最后 commit 写 COMMITTED。
 * 发布异常或后续访问发现未完成事务时，按备份恢复上一代并写 ROLLED_BACK。成功和回滚后的备份均保留。</p>
 *
 * @see MemoryStore
 */
final class MemoryTransaction {
    private final Path root, backup;
    private final Map<String, String> originals, replacements;
    private final String oldIndex, newIndex;

    /**
     * 保存记忆库、备份目录及新旧文件内容，组成一份已准备的发布事务；后续 commit 标记成功，
     * rollback 按这些内容恢复上一代。
     *
     * @param root 正式记忆库根目录，调用方已经持有库短锁
     * @param backup 保存本次新旧正文与恢复清单的事务备份目录
     * @param originals 发布前附属文件名到原正文的映射
     * @param replacements 本次附属文件名到候选正文的映射
     * @param oldIndex 发布前的 MEMORY.md 正文；原文件缺失时为 null
     * @param newIndex 本次候选 MEMORY.md 完整正文
     */
    private MemoryTransaction(Path root, Path backup, Map<String, String> originals,
            Map<String, String> replacements, String oldIndex, String newIndex) {
        this.root = root;
        this.backup = backup;
        this.originals = originals;
        this.replacements = replacements;
        this.oldIndex = oldIndex;
        this.newIndex = newIndex;
    }

    /**
     * 创建专属备份目录，把附属文件新旧内容、手册新旧正文和恢复清单写盘并刷新，最后写入 READY，返回发布事务。
     * 单层文件名和备份 IO 失败以 IOException 返回，此阶段正式库保持原内容。
     *
     * @param root 正式记忆库根目录，调用方已经持有库短锁
     * @param originals 发布前附属文件名到原正文的映射
     * @param replacements 本次附属文件名到候选正文的映射
     * @param newIndex 本次候选 MEMORY.md 完整正文
     * @return 已经写好并刷新恢复材料、带 READY 标记的发布事务
     * @throws IOException 恢复文件名非法或备份正文、清单、READY 写入刷新失败
     */
    static MemoryTransaction prepare(Path root, Map<String, String> originals, Map<String, String> replacements,
            String newIndex) throws IOException {
        Path backup = Files.createTempDirectory(root, ".memory-backup-");
        Path old = Files.createDirectory(backup.resolve("old")), next = Files.createDirectory(backup.resolve("new"));
        for (var entry : originals.entrySet())
            write(old.resolve(filename(entry.getKey())), entry.getValue());
        for (var entry : replacements.entrySet())
            write(next.resolve(filename(entry.getKey())), entry.getValue());
        Path index = root.resolve("MEMORY.md");
        String oldIndex = Files.exists(index, LinkOption.NOFOLLOW_LINKS) ? readPlain(index) : null;
        if (oldIndex != null)
            write(backup.resolve("index-old.txt"), oldIndex);
        write(backup.resolve("index-new.txt"), newIndex);
        var manifest = Json.mapper().createObjectNode();
        manifest.put("version", 1).put("hadIndex", oldIndex != null);
        var oldNames = manifest.putArray("originals");
        var newNames = manifest.putArray("replacements");
        originals.keySet().forEach(oldNames::add);
        replacements.keySet().forEach(newNames::add);
        write(backup.resolve("manifest.json"), manifest.toString());
        force(old);
        force(next);
        force(backup);
        force(root);
        write(backup.resolve("READY"), "prepared\n");
        force(backup);
        return new MemoryTransaction(root, backup, Map.copyOf(originals), Map.copyOf(replacements), oldIndex, newIndex);
    }

    /**
     * 正式文件替换后刷新库目录，写入并刷新 COMMITTED 标记，确认这一代发布完成。
     * 标记完成前的中断由后续 recover 按旧代恢复。
     *
     * @throws IOException 正式目录刷新、COMMITTED 写入或备份目录刷新失败
     */
    void commit() throws IOException {
        force(root);
        write(backup.resolve("COMMITTED"), "committed\n");
        force(backup);
    }

    /**
     * 撤销成功标记后核对所有当前文件属于备份中的旧版或候选版，再逐项原子恢复旧文件、删除本次新增文件并恢复旧手册，
     * 最后写 ROLLED_BACK。发现未知外部正文时报错并保留备份；中途退出后可继续同一恢复过程。
     *
     * @throws IOException 当前内容包含未知外部修改，或旧正文恢复和标记写入失败
     */
    void rollback() throws IOException {
        // commit 的最后一次目录刷新也可能失败。先取消可能已写入的成功标记，
        // 若本次回滚再次中断，下次仍必须按 READY 恢复，不能误以为发布已完整结束。
        Files.deleteIfExists(backup.resolve("COMMITTED"));
        force(backup);
        var known = new java.util.LinkedHashSet<>(originals.keySet());
        known.addAll(replacements.keySet());
        for (String name : known) {
            Path file = root.resolve(filename(name));
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                String current = readPlain(file);
                if (!current.equals(originals.get(name)) && !current.equals(replacements.get(name)))
                    throw new IOException(
                            "Memory recovery found unknown external changes; the disk backup is preserved and the file was not overwritten");
            }
        }
        Path index = root.resolve("MEMORY.md");
        if (Files.exists(index, LinkOption.NOFOLLOW_LINKS)) {
            String current = readPlain(index);
            if (!current.equals(oldIndex) && !current.equals(newIndex))
                throw new IOException(
                        "Memory recovery found unknown index changes; the disk backup is preserved and the file was not overwritten");
        }
        // 不能先删除所有文件再写回；逐项原子替换，恢复过程中也尽量保留可用正文。
        for (var entry : originals.entrySet())
            atomicReplace(root.resolve(entry.getKey()), entry.getValue());
        for (String name : replacements.keySet())
            if (!originals.containsKey(name))
                Files.deleteIfExists(root.resolve(name));
        if (oldIndex == null)
            Files.deleteIfExists(index);
        else
            atomicReplace(index, oldIndex);
        force(root);
        write(backup.resolve("ROLLED_BACK"), "restored\n");
        force(backup);
    }

    /**
     * 按目录顺序扫描备份，选择已 READY 且尚无 COMMITTED 或 ROLLED_BACK 标记的事务。校验恢复清单、读取新旧材料，
     * 重建事务并回滚，随后报告恢复告警；文件或清单异常以 IOException 返回。
     *
     * @param root 正式记忆库根目录，调用方已经持有库短锁
     * @param warnings 接收文件或维护告警的文本出口
     * @throws IOException 备份目录或恢复清单不合法，材料读取失败或回滚失败
     */
    static void recover(Path root, Consumer<String> warnings) throws IOException {
        List<Path> backups;
        try (var entries = Files.list(root)) {
            backups = entries.filter(p -> p.getFileName().toString().startsWith(".memory-backup-")).sorted().toList();
        }
        for (Path backup : backups) {
            if (!Files.isDirectory(backup, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Memory backup path is not a regular directory");
            if (!Files.exists(backup.resolve("READY"), LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(backup.resolve("COMMITTED"), LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(backup.resolve("ROLLED_BACK"), LinkOption.NOFOLLOW_LINKS))
                continue;
            var manifest = Json.mapper().readTree(readPlain(backup.resolve("manifest.json")));
            if (manifest == null || manifest.path("version").asInt() != 1 || !manifest.path("hadIndex").isBoolean())
                throw new IOException("Memory recovery manifest has an invalid format");
            Map<String, String> old = readDocuments(backup, "old", manifest.get("originals"));
            Map<String, String> next = readDocuments(backup, "new", manifest.get("replacements"));
            new MemoryTransaction(root, backup, old, next,
                    manifest.path("hadIndex").booleanValue() ? readPlain(backup.resolve("index-old.txt")) : null,
                    readPlain(backup.resolve("index-new.txt"))).rollback();
            warnings.accept(
                    "[Warning] [Memory] Recovered an unfinished consolidation from the disk backup; the backup is still retained");
        }
    }

    /**
     * 校验恢复清单为非空文件名数组，读取指定备份子目录中的各份正文，返回按清单顺序排列的映射；
     * 目录、文件名、重复项或文件读取错误抛出 IOException。
     *
     * @param backup 保存本次新旧正文与恢复清单的事务备份目录
     * @param directory 备份内的 old 或 new 子目录名
     * @param names 恢复清单中需要读取的单层文件名数组
     * @return 恢复清单中各文件名到完整备份正文的有序映射
     * @throws IOException 恢复文件名数组为空、类型不符、重名，或备份正文无法读取
     */
    private static Map<String, String> readDocuments(Path backup, String directory,
            com.fasterxml.jackson.databind.JsonNode names) throws IOException {
        if (names == null || !names.isArray() || names.isEmpty())
            throw new IOException("Memory recovery file list is invalid");
        Path dir = backup.resolve(directory);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Memory recovery body directory is invalid");
        var documents = new LinkedHashMap<String, String>();
        for (var item : names) {
            if (!item.isTextual())
                throw new IOException("Memory recovery filename is not text");
            String name = filename(item.textValue());
            if (documents.putIfAbsent(name, readPlain(dir.resolve(name))) != null)
                throw new IOException("Memory recovery filename is duplicated");
        }
        return documents;
    }

    /**
     * 校验恢复清单中的单层文件名，接受附属 Markdown 和固定的 memory_manifest.json；手册 MEMORY.md 由独立字段处理。
     * 非法名称抛出 IOException。
     *
     * @param value 恢复清单中待核验的单层文件名
     * @return 通过单层名称检查的原始文件名
     * @throws IOException 恢复名包含路径、为空或不是允许的单层资料名
     */
    private static String filename(String value) throws IOException {
        // 记忆清单与正文必须属于同一恢复事务；只额外开放这个固定 JSON 名称，不能任意恢复路径。
        if (value.isEmpty() || (!value.endsWith(".md") && !value.equals("memory_manifest.json"))
                || value.equals("MEMORY.md") || value.contains("/") || value.contains("\\"))
            throw new IOException("Memory recovery filename must be a single-level record name");
        return value;
    }

    /**
     * 以 UTF-8 读取备份或恢复目标的普通文件；目标类型不符时抛出 IOException，返回文本供新旧内容比较。
     *
     * @param file 本次读取、备份或原子替换的目标文件
     * @return 普通文件中的完整 UTF-8 文本
     * @throws IOException 目标不是普通文件或 UTF-8 正文读取失败
     */
    private static String readPlain(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Memory backup or recovery target is not a regular file");
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /**
     * 以 CREATE_NEW 创建备份文件，将 UTF-8 文本完整写入并刷新。目标已存在或写入失败以 IOException 返回，
     * 供 prepare 和状态标记使用。
     *
     * @param file 本次读取、备份或原子替换的目标文件
     * @param text 本次要写入并刷新到目标文件的完整 UTF-8 正文
     * @throws IOException 目标已存在、正文写入或文件刷新失败
     */
    static void write(Path file, String text) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var data = StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(text));
            while (data.hasRemaining())
                channel.write(data);
            channel.force(true);
        }
    }

    /**
     * 在目标同目录创建临时文件，写完 UTF-8 文本并刷新磁盘后，用原子移动替换目标；最后清理临时文件。
     * 已有目标类型错误或写入、刷新、替换失败时抛出 IOException。
     *
     * @param target 本次操作的目标绝对路径
     * @param text 本次要写入并刷新到目标文件的完整 UTF-8 正文
     * @throws IOException 现有目标类型不合法，或临时文件写入、刷新、原子移动失败
     */
    static void atomicReplace(Path target, String text) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Memory publish target is not a regular file");
        Path temporary = Files.createTempFile(target.getParent(), ".memory-publish-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var data = StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(text));
                while (data.hasRemaining())
                    channel.write(data);
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * 打开并刷新目录，使此前文件和标记的目录项变更持久化；文件系统操作失败以 IOException 返回。
     *
     * @param directory 需要逐级检查、创建或刷新目录项的目标目录
     * @throws IOException 目录无法打开或文件系统刷新失败
     */
    private static void force(Path directory) throws IOException {
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
