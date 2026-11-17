package com.jadecode.sandbox;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * 扫描计划挂载中已有路径的名称和文件属性，生成敏感入口隐藏与配置只读保护点，供 DockerRuntime 创建容器时叠加挂载。
 * 符号链接保护点、硬链接和扫描预算超限通过 IOException 报告。
 */
public final class PathProtection {
    /**
     * 汇集宿主路径识别和容器挂载保护扫描方法。
     */
    private PathProtection() {
    }

    /**
     * 表示一个需要隐藏或只读覆盖的挂载保护点，记录目标路径、隐藏方式和文件类型，供容器创建时叠加保护挂载。
     *
     * @param target 需要叠加隐藏或只读挂载的绝对路径
     * @param hidden 该保护点是否需要隐藏；false 表示覆盖为只读
     * @param directory 该保护点是否为目录，决定使用空目录还是空文件遮蔽
     */
    record Guard(Path target, boolean hidden, boolean directory) {
    }

    private static final Set<String> HIDDEN = Set.of(".ssh", ".aws", ".gnupg", ".jadecode", ".codex", ".docker",
            ".kube",
            ".config", ".m2", ".npmrc", ".pypirc", ".netrc", ".git-credentials");
    private static final Set<String> READ_ONLY = Set.of(".git", ".agents", ".claude", ".vscode", ".idea");

    /**
     * 按路径组成判断是否属于已知敏感入口，供权限、挂载与服务端文件名补全共同筛选目标。
     *
     * @param path 需要按路径组成识别敏感入口的目标路径
     * @return 路径中存在已知敏感入口时为 true
     */
    public static boolean sensitive(Path path) {
        if (path.getFileName() == null)
            return true;
        for (Path part : path) {
            String name = part.toString();
            if (HIDDEN.contains(name) || READ_ONLY.contains(name) || name.equals(".env") || name.startsWith(".env."))
                return true;
        }
        return Set.of(".bashrc", ".zshrc", ".profile").contains(path.getFileName().toString());
    }

    /**
     * 遍历有界且不跟随目录链接；硬链接可能绕过按路径的保护，因此拒绝这种布局。
     *
     * @param roots 本次计划需要扫描保护点的业务挂载根
     * @return 所有业务挂载中识别出的隐藏和只读保护点
     * @throws IOException 挂载根不可访问、保护点为链接、存在硬链接或扫描数量超限
     */
    static List<Guard> scan(List<ExecutionPlan.Mount> roots) throws IOException {
        var found = new LinkedHashMap<Path, Guard>();
        int[] count = { 0 };
        for (var root : roots) {
            Path source = root.source();
            if (!source.toRealPath().equals(source))
                throw new IOException("Mount source changed or contains a symbolic link: " + source);
            if (!Files.isDirectory(source) && !Files.isRegularFile(source))
                throw new IOException("Only ordinary files and directories may be exposed");
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                /**
                 * 统计扫描条目并识别敏感文件、控制通道和只读配置，将保护点加入 found。隐藏目录返回 true，供遍历器跳过其子树；
                 * 扫描超过 200000 项、保护点为符号链接或普通文件存在硬链接时抛出 IOException。
                 *
                 * @param path 需要按路径组成识别敏感入口的目标路径
                 * @param attrs 遍历器读取的当前路径文件属性
                 * @return 当前目标需要隐藏、其目录子树可跳过时为 true
                 * @throws IOException 条目超预算、保护点是链接或普通文件存在硬链接
                 */
                private boolean check(Path path, BasicFileAttributes attrs) throws IOException {
                    if (++count[0] > 200_000)
                        throw new IOException("Sandbox protection scan exceeds 200000 entries; narrow the workspace");
                    String name = path.getFileName().toString();
                    // 宿主 socket/FIFO/设备即使在工作区内也不是普通资料，不能暴露控制通道。
                    boolean hidden = HIDDEN.contains(name) || name.equals(".env") || name.startsWith(".env.")
                            || attrs.isOther();
                    boolean ro = READ_ONLY.contains(name) || Set.of(".bashrc", ".zshrc", ".profile").contains(name);
                    if ((hidden || ro) && !path.equals(source)) {
                        if (attrs.isSymbolicLink())
                            throw new IOException("Protected symbolic-link entry cannot be mounted safely: " + path);
                        found.put(path, new Guard(path, hidden, attrs.isDirectory()));
                        return hidden;
                    }
                    if (attrs.isRegularFile()
                            && ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS))
                                    .intValue() > 1)
                        throw new IOException("Hard-linked files require a separate workspace copy: " + path);
                    return false;
                }

                /**
                 * 检查进入的目录是否属于隐藏保护点；隐藏目录跳过整棵子树，其他目录继续向下扫描。
                 *
                 * @param p 遍历器当前访问的路径
                 * @param a 当前目录或文件的基础属性
                 * @return 隐藏目录返回 SKIP_SUBTREE，其余目录返回 CONTINUE
                 * @throws IOException 目录保护属性检查失败
                 */
                @Override
                public FileVisitResult preVisitDirectory(Path p, BasicFileAttributes a) throws IOException {
                    return check(p, a) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                /**
                 * 检查文件并登记需要的隐藏或只读保护点，检查成功后继续遍历下一个条目。
                 *
                 * @param p 遍历器当前访问的路径
                 * @param a 当前目录或文件的基础属性
                 * @return 检查完成后返回 CONTINUE，继续遍历
                 * @throws IOException 文件保护属性或硬链接检查失败
                 */
                @Override
                public FileVisitResult visitFile(Path p, BasicFileAttributes a) throws IOException {
                    check(p, a);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return found.values().stream().sorted(Comparator.comparingInt(g -> g.target().getNameCount())).toList();
    }
}
