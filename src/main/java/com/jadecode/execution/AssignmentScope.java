package com.jadecode.execution;

import java.nio.file.Path;
import java.util.List;

/**
 * 一次派工的固定文件分工快照，供派工正文、界面以及未来的权限/进程隔离共用。
 *
 * <p>路径相对工作区，目录表示其子树；本类只校验并保存范围，不读取文件，也不授予权限。
 * 当前限制仍由 PermissionGate 决定。空列表表示没有声明该类范围，不表示允许所有路径。</p>
 * @param readPaths 可供参考的相对路径，不修改调用方列表
 * @param writePaths 分配给本次派工修改的相对路径，不修改调用方列表
 */
public record AssignmentScope(List<String> readPaths, List<String> writePaths) {
    public static final AssignmentScope EMPTY = new AssignmentScope(List.of(), List.of());

    /** 先逐项校验再复制，避免后续派工或模型改动原列表而改变已经运行的范围。 */
    public AssignmentScope {
        readPaths = paths(readPaths);
        writePaths = paths(writePaths);
        if (java.util.stream.Stream.concat(readPaths.stream(), writePaths.stream()).mapToInt(String::length).sum() > 4096)
            throw new IllegalArgumentException("Assignment scope paths exceed the 4096-character budget");
    }

    /** 路径只接受有界的工作区相对表示；实际符号链接和读写授权仍在执行时检查。 */
    private static List<String> paths(List<String> values) {
        if (values == null || values.size() > 64) throw new IllegalArgumentException("Scope must contain at most 64 paths per list");
        return values.stream().map(value -> {
            if (value == null || value.isBlank() || value.length() > 2048 || value.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Scope paths must be non-empty relative paths");
            Path path = Path.of(value);
            if (path.isAbsolute() || path.normalize().startsWith(".."))
                throw new IllegalArgumentException("Scope paths must stay within the workspace");
            return path.normalize().toString().isEmpty() ? "." : path.normalize().toString();
        }).distinct().toList();
    }
}
