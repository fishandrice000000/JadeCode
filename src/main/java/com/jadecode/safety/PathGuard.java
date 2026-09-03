package com.jadecode.safety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * PathGuard 路径围栏
 *
 * 所有文件工具的统一入口校验:来自模型的任何路径,解析后都必须仍在工作区内,
 * 否则抛 IllegalArgumentException(工具层负责转成错误字符串)。
 *
 * 三层防线(对应课程 safe_path 的 .resolve() + is_relative_to):
 * 1. 词法归一化:挡绝对路径与 .. 穿透
 * 2. 逐段解析符号链接(词法展开链接目标,悬空链接同样被防)——Java 没有
 * Python resolve() 的等价物,这一层是它的手工移植
 * 3. 与工作区真实路径比对
 */
public class PathGuard {
    private final Path workspace; // 词法工作区(启动时绝对路径,归一化后)
    private final Path workspaceReal; // 真实工作区(符号链接已解析)

    public PathGuard(Path workspace) {
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

    /**
     * 校验路径,返回可在工作区内安全使用的词法路径。
     * 越界抛 IllegalArgumentException,消息可直接展示给模型看。
     */
    public Path check(String path) {
        Path p = workspace.resolve(path).normalize();
        if (!p.startsWith(workspace)) {
            throw new IllegalArgumentException("路径越出工作区: " + path);
        }

        // 从工作区真实路径出发,逐段下探;每段若是符号链接,词法展开其目标再继续。
        // 悬空链接也能展开:isSymbolicLink 只看链接本身,不看目标是否存在。
        Path real = workspaceReal;
        for (Path seg : workspace.relativize(p)) {
            real = real.resolve(seg);
            if (Files.isSymbolicLink(real)) {
                real = real.getParent().resolve(readLink(real)).normalize();
            }
        }
        if (!real.startsWith(workspaceReal)) {
            throw new IllegalArgumentException("路径越出工作区: " + path);
        }
        return p;
    }

    private static Path readLink(Path link) {
        try {
            return Files.readSymbolicLink(link);
        } catch (IOException e) {
            throw new IllegalStateException("符号链接不可读: " + link, e);
        }
    }
}
