package com.jadecode.workspace;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;
import com.jadecode.config.JadePaths;
import com.jadecode.sandbox.PathProtection;

/**
 * 工作区快照的有界文件操作。只处理普通文件，不执行仓库内容；不跟随符号链接或硬链接。
 * 默认跳过凭据/元数据和依赖环境。源目录属于已停写的受管工作区或用户明确选择的上传范围；
 * 不承诺抵抗同 UID 宿主进程在校验后恶意替换路径。
 */
public final class WorkspaceFiles {
    public static final long MAX_BYTES = 256L * 1024 * 1024;
    public static final int MAX_FILES = 30_000, CHUNK = 64 * 1024;
    private static final Set<String> ENVIRONMENTS = Set.of(".venv", "venv", "node_modules", "__pycache__");
    private WorkspaceFiles() { }
    /** 在读取/复制每个文件之前核验权限与取消；回调失败使整次快照失败。 */
    @FunctionalInterface public interface Check { void accept(Path source, String relative) throws IOException; }
    public record FileInfo(long size, String sha256, boolean executable) { }

    /** 创建 0700 私有目录并检查已有路径，不接管链接目录。 */
    public static Path directory(Path path) throws IOException {
        new JadePaths(path).directory(path, true);
        return path.toRealPath();
    }

    /** 只接受工作区相对名称；不把绝对路径、.. 或控制字符当成普通文件名。 */
    public static Path relative(String name) throws IOException {
        if (name == null || name.isBlank() || name.length()>4096 || name.indexOf('\\') >= 0 || name.chars().anyMatch(c -> c < 32))
            throw new IOException("Invalid workspace-relative path");
        Path value = Path.of(name);
        if (value.isAbsolute()) throw new IOException("Absolute paths are not accepted");
        for (Path part : value) if (part.toString().equals("..")) throw new IOException("Parent traversal is not accepted");
        return value.normalize();
    }

    /** 逐段拒绝链接，缺失尾部允许作为新目标；返回值始终位于给定真实根内。 */
    public static Path resolve(Path root, String name) throws IOException {
        Path rel = relative(name), result = root.resolve(rel).normalize();
        if (!result.startsWith(root)) throw new IOException("Path escapes workspace");
        Path cursor = root;
        for (Path part : root.relativize(result)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("Symbolic links are not accepted");
        }
        return result;
    }

    /** 默认不将凭据、Git 元数据、虚拟环境或依赖缓存变成输入/交付文件。 */
    public static boolean excluded(Path relative) {
        if (PathProtection.sensitive(relative)) return true;
        for (Path part : relative) if (ENVIRONMENTS.contains(part.toString())) return true;
        return false;
    }

    /** 收集有序文件清单，哈希来自实际字节；失败不返回不完整清单。 */
    public static SortedMap<String, FileInfo> inspect(Path root, Collection<String> selected, Check check) throws IOException {
        var result = new TreeMap<String, FileInfo>(); long[] bytes = {0}; int[] visited = {0};
        for (String selection : selected) {
            Path start = resolve(root, selection);
            if (excluded(root.relativize(start))) throw new IOException("Protected or environment paths cannot be bundled");
            if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) continue; // 删除项通过基线差异表达。
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (++visited[0] > 200_000) throw new IOException("Workspace entry limit exceeded");
                    return excluded(root.relativize(dir)) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (++visited[0] > 200_000) throw new IOException("Workspace entry limit exceeded");
                    Path rel = root.relativize(file); if (excluded(rel)) return FileVisitResult.CONTINUE;
                    String name = rel.toString(); relative(name);
                    if (!attrs.isRegularFile() || ((Number) Files.getAttribute(file,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).intValue() != 1)
                        throw new IOException("Only ordinary, unlinked files may be bundled: " + name);
                    if (result.containsKey(name)) return FileVisitResult.CONTINUE;
                    if (result.size() >= MAX_FILES || (bytes[0] += attrs.size()) > MAX_BYTES)
                        throw new IOException("Workspace snapshot limit exceeded");
                    check.accept(file, name);
                    result.put(name, new FileInfo(attrs.size(), sha256(file), Files.isExecutable(file)));
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return Collections.unmodifiableSortedMap(result);
    }

    /** 在私有目标复制选定文件，保留执行位；调用方负责以同一屏障保护清单与复制。 */
    public static void copy(Path root, Path target, Collection<String> names, Check check) throws IOException {
        for (String name : names) {
            Path source = resolve(root,name), dest = resolve(target,name); check.accept(source,name);
            Files.createDirectories(dest.getParent());
            try (InputStream in = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
                Files.copy(in,dest);
            }
            mode(dest,Files.isExecutable(source));
        }
    }

    /** 生成可重新上传的 ZIP；顺序与时间固定，重复上传相同内容可获得相同摘要。 */
    public static SortedMap<String, FileInfo> zip(Path root, Path zip, Check check) throws IOException {
        var files = inspect(root,List.of("."),check);
        try (var out = new ZipOutputStream(Files.newOutputStream(zip,StandardOpenOption.CREATE_NEW))) {
            for (var item : files.entrySet()) {
                Path source = resolve(root,item.getKey()); check.accept(source,item.getKey());
                var entry = new ZipEntry(item.getKey()); entry.setTime(0);
                if (item.getValue().executable()) entry.setComment("jade:executable");
                out.putNextEntry(entry);
                try (var input = Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS)) { input.transferTo(out); }
                out.closeEntry();
            }
        }
        if (Files.size(zip) > MAX_BYTES) throw new IOException("Archive limit exceeded");
        return files;
    }

    /** 校验 ZIP 路径/重复项/实际展开大小，始终写普通新文件，不解释 ZIP 中的链接属性。 */
    public static void unzip(Path zip, Path target, BooleanSupplier cancelled) throws IOException {
        unzip(zip,target,cancelled,name->true,(file,name)->{});
    }

    /** 成果只展开选定范围，读取每个条目正文之前先检查当前权限。 */
    public static void unzip(Path zip, Path target, BooleanSupplier cancelled,java.util.function.Predicate<String> include,Check check) throws IOException {
        // ZipFile 会先装载中央目录；先流式验证条目数和实际展开总量，阻止超多条目耗尽宿主堆。
        validateArchive(zip,cancelled);
        Set<String> names = new HashSet<>(); long total = 0;
        try (var input = new ZipFile(zip.toFile())) {
            var entries = input.entries();
            while (entries.hasMoreElements()) {
                if (cancelled.getAsBoolean()) throw new IOException("Snapshot extraction cancelled");
                var entry = entries.nextElement(); Path rel = relative(entry.getName());
                if (excluded(rel) || !names.add(rel.toString()) || names.size() > MAX_FILES)
                    throw new IOException("Protected, duplicate or excessive archive entries");
                if (!include.test(rel.toString())) continue;
                Path dest = resolve(target,rel.toString());
                if (entry.isDirectory()) { Files.createDirectories(dest); continue; }
                check.accept(dest,rel.toString());
                Files.createDirectories(dest.getParent());
                try (var in = input.getInputStream(entry); var out = Files.newOutputStream(dest,StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[CHUNK]; int size;
                    while ((size = in.read(buffer)) >= 0) {
                        if (cancelled.getAsBoolean() || (total += size) > MAX_BYTES) throw new IOException("Cancelled or excessive archive");
                        out.write(buffer,0,size);
                    }
                }
                mode(dest,"jade:executable".equals(entry.getComment()));
            }
        }
    }

    /** 只使用固定缓冲预检压缩输入，不生成文件，也不信任 ZIP 声明的未压缩长度。 */
    private static void validateArchive(Path zip,BooleanSupplier cancelled) throws IOException {
        if (Files.size(zip)>MAX_BYTES) throw new IOException("Archive size limit exceeded");
        long total=0; int count=0; Set<String> names=new HashSet<>();
        try (var stream=new ZipInputStream(Files.newInputStream(zip,LinkOption.NOFOLLOW_LINKS))) {
            ZipEntry entry; byte[] buffer=new byte[CHUNK];
            while ((entry=stream.getNextEntry())!=null) {
                Path name=relative(entry.getName());
                if (++count>MAX_FILES || excluded(name) || !names.add(name.toString())) throw new IOException("Invalid or excessive archive entries");
                int size;
                while ((size=stream.read(buffer))>=0) {
                    if (cancelled.getAsBoolean() || (total+=size)>MAX_BYTES) throw new IOException("Cancelled or excessive archive expansion");
                }
            }
        }
    }

    /** SHA-256 流式计算，不将整个资源读入堆内存。 */
    public static String sha256(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[CHUNK]; int size;
                while ((size = input.read(buffer)) >= 0) digest.update(buffer,0,size);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    /** 仅复制普通执行位，不复制 setuid 等特权位。 */
    private static void mode(Path path, boolean executable) throws IOException {
        Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(executable ? "rwx------" : "rw-------"));
    }
    /** 仅清理调用方创建的私有树；不跟随其内部链接。 */
    public static void delete(Path root) throws IOException {
        if (!Files.exists(root,LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root,new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path dir,IOException error) throws IOException {
                if (error != null) throw error; Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }
}
