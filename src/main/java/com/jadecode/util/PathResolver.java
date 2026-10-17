package com.jadecode.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * 把字符串路径解析成真实的可操作路径，包括把路径里的符号链接逐段展开。
 *
 * <p>和 {@code toRealPath()} 不同，即使链接指向一个还不存在的文件也能解析出来：
 * 逐个目录看过去，遇到链接就读它的目标继续走，不要求目标已经存在。
 * 本类只管怎么把路径算出来，不管能不能访问；越界和高危目录的判断在 safety.PathGuard。
 */
public class PathResolver {
    /** 词法工作区(绝对路径,归一化后) */
    private final Path workspace;
    /** 真实工作区(符号链接已解析) */
    private final Path workspaceReal;

    /**
     * @param workspace 工作区根目录;须存在且可访问
     * @throws IllegalStateException 工作区解析不出真实路径
     */
    public PathResolver(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
        try {
            this.workspaceReal = this.workspace.toRealPath();
        } catch (IOException e) {
            // 工作区都解析不了,程序没必要继续跑
            throw new IllegalStateException("工作区不存在或不可访问: " + workspace, e);
        }
    }

    /** 工作区词法路径(GlobTool 这类需要遍历工作区的工具用) */
    public Path workspace() {
        return workspace;
    }

    /** 工作区真实路径(PathGuard 分类比对用) */
    public Path workspaceReal() {
        return workspaceReal;
    }

    /** 解析工作区相对路径字符串(绝对路径原样接管),返回可操作的路径 */
    public Path resolve(String raw) {
        return resolve(workspace.resolve(raw));
    }

    /** 同上,已 Path 化的入口 */
    public Path resolve(Path p) {
        Path joined = workspace.resolve(p).normalize();
        if (joined.startsWith(workspace)) {
            // 词法上在工作区内:从真实工作区出发走相对段——工作区自身若是链接也能解析对
            return walk(workspaceReal, workspace.relativize(joined));
        }
        // 词法上在工作区外:从根逐段走
        return walk(joined.getRoot(), joined.getRoot().relativize(joined));
    }

    /**
     * 逐段下探:每段若是符号链接,读其目标后从目标继续(相对目标相对链接所在目录)。
     * 悬空链接同样能展开;读不到目标(罕见 IO 错误)时保持词法路径继续。
     * 用迭代器消费段——"没有下一段"由 hasNext() 表达,不造空路径:
     * Path.of("") 有 1 个空名元素(死循环),subpath(1,1) 抛 IllegalArgumentException。
     */
    private static Path walk(Path base, Path segments) {
        return walkSegments(base, segments.iterator());
    }

    /** walk 的递归体:逐段消费迭代器,符号链接读目标后从目标继续 */
    private static Path walkSegments(Path base, Iterator<Path> segments) {
        if (!segments.hasNext()) {
            return base;
        }
        Path candidate = base.resolve(segments.next());
        if (Files.isSymbolicLink(candidate)) {
            try {
                Path target = Files.readSymbolicLink(candidate);
                Path next = target.isAbsolute() ? target : base.resolve(target);
                return walkSegments(next.normalize(), segments);
            } catch (IOException e) {
                return walkSegments(candidate, segments);
            }
        }
        return walkSegments(candidate, segments);
    }
}
