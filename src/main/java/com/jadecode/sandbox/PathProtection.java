package com.jadecode.sandbox;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * 将工作区中已有的敏感入口转为只读或不可读挂载，扫描只读取目录/属性，不读取正文。
 * Docker 目录挂载不是按文件名过滤器；显式拒绝的缺失路径不能精确表达时由调用方拒绝。
 * 本类不承诺抵抗同权限宿主进程在校验与 daemon 挂载之间恶意替换目录。
 */
public final class PathProtection {
    private PathProtection() { }
    record Guard(Path target, boolean hidden, boolean directory) { }
    private static final Set<String> HIDDEN = Set.of(".ssh", ".aws", ".gnupg", ".jadecode", ".codex", ".docker", ".kube",
            ".config", ".m2", ".npmrc", ".pypirc", ".netrc", ".git-credentials");
    private static final Set<String> READ_ONLY = Set.of(".git", ".agents", ".claude", ".vscode", ".idea");
    /** 供服务端文件名补全隐藏已知敏感入口；此判断不代表任意路径已获得访问授权。 */
    public static boolean sensitive(Path path) {
        if (path.getFileName() == null) return true;
        for (Path part : path) {
            String name = part.toString();
            if (HIDDEN.contains(name) || READ_ONLY.contains(name) || name.equals(".env") || name.startsWith(".env.")) return true;
        }
        return Set.of(".bashrc", ".zshrc", ".profile").contains(path.getFileName().toString());
    }
    /** 遍历有界且不跟随目录链接；硬链接可能绕过按路径的保护，因此拒绝这种布局。 */
    static List<Guard> scan(List<ExecutionPlan.Mount> roots) throws IOException {
        var found = new LinkedHashMap<Path, Guard>(); int[] count = {0};
        for (var root : roots) {
            Path source = root.source();
            if (!source.toRealPath().equals(source)) throw new IOException("Mount source changed or contains a symbolic link: " + source);
            if (!Files.isDirectory(source) && !Files.isRegularFile(source)) throw new IOException("Only ordinary files and directories may be exposed");
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                private boolean check(Path path, BasicFileAttributes attrs) throws IOException {
                    if (++count[0] > 200_000) throw new IOException("Sandbox protection scan exceeds 200000 entries; narrow the workspace");
                    String name = path.getFileName().toString();
                    // 宿主 socket/FIFO/设备即使在工作区内也不是普通资料，不能暴露控制通道。
                    boolean hidden = HIDDEN.contains(name) || name.equals(".env") || name.startsWith(".env.") || attrs.isOther();
                    boolean ro = READ_ONLY.contains(name) || Set.of(".bashrc", ".zshrc", ".profile").contains(name);
                    if ((hidden || ro) && !path.equals(source)) {
                        if (attrs.isSymbolicLink()) throw new IOException("Protected symbolic-link entry cannot be mounted safely: " + path);
                        found.put(path, new Guard(path, hidden, attrs.isDirectory())); return hidden;
                    }
                    if (attrs.isRegularFile() && ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).intValue() > 1)
                        throw new IOException("Hard-linked files require a separate workspace copy: " + path);
                    return false;
                }
                @Override public FileVisitResult preVisitDirectory(Path p, BasicFileAttributes a) throws IOException {
                    return check(p, a) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path p, BasicFileAttributes a) throws IOException {
                    check(p, a); return FileVisitResult.CONTINUE;
                }
            });
        }
        return found.values().stream().sorted(Comparator.comparingInt(g -> g.target().getNameCount())).toList();
    }
}
