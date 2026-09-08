package com.jadecode.safety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * PathResolver 路径解析器
 *
 * 类 Python Path.resolve() 的机制层:词法归一化 + 逐段解析符号链接。
 * 比 toRealPath 强在悬空链接也能追——逐段 isSymbolicLink + readSymbolicLink
 * 只读链接本身,不要求目标存在(s02 PathGuard 手工走法的抽取)。
 *
 * 只提供机制,不做政策:越界、高危的判断由 PathGuard 负责。
 */
public class PathResolver {
    private final Path workspace; // 词法工作区(绝对路径,归一化后)
    private final Path workspaceReal; // 真实工作区(符号链接已解析)

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
