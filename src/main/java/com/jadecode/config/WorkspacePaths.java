package com.jadecode.config;

import java.io.IOException;
import java.nio.file.*;
import com.jadecode.util.Json;

/**
 * 某个项目在用户数据根中的分区。workspace 永远用于业务命令，root 只用于 Harness 持久状态。
 * 构造解析已有工作区真实路径但不写盘；不同 checkout/worktree 按真实路径哈希隔离。
 */
public record WorkspacePaths(Path workspace, JadePaths jade) {
    public WorkspacePaths {
        try { workspace = workspace.toRealPath(); }
        catch (IOException invalid) { throw new IllegalArgumentException("Workspace is inaccessible", invalid); }
        java.util.Objects.requireNonNull(jade);
        if (!Files.isDirectory(workspace)) throw new IllegalArgumentException("Workspace must be a directory");
    }
    /** 生产默认用户根；离线调用者应显式提供临时 JadePaths。 */
    public WorkspacePaths(Path workspace) { this(workspace, JadePaths.defaults()); }
    public String id() { return JadePaths.identity(workspace); }
    public Path root() { return jade.workspaces().resolve(id()); }
    public Path sessions() { return root().resolve("sessions"); }
    public Path tasks() { return root().resolve("tasks"); }
    public Path transcripts() { return root().resolve("transcripts"); }
    public Path toolResults() { return root().resolve("tool-results"); }
    public Path teams() { return root().resolve("teams"); }
    public Path workflows() { return root().resolve("workflows"); }
    public Path cronFile() { return root().resolve("cron/jobs.json"); }

    /** 首次实际使用注册项目归属；已有元数据必须匹配，不凭目录名猜测或覆盖其它项目。 */
    public void register() throws IOException {
        // 所有注册先经过祖先目录检查，不能把注册文件写进一个链接分区。
        jade.directory(root(), true);
        Path file = root().resolve("workspace.json");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            // 已有分区只比对，不覆写用户修改或其它项目的身份。
            regular(file);
            var saved = Json.mapper().readTree(Files.readString(file));
            if (saved == null || !saved.path("id").asText().equals(id()) || !saved.path("workspace").asText().equals(workspace.toString()))
                throw new IOException("Workspace registry identity mismatch");
        } else {
            var value = Json.mapper().createObjectNode().put("id", id()).put("workspace", workspace.toString());
            // 注册内容由真实路径唯一确定；同项目并发初始化得到相同内容。
            JadePaths.atomicWrite(file, value.toString());
        }
    }

    /** 只允许访问当前分区；创建目录前先登记项目元数据，读取不隐式建空库。 */
    public void directory(Path directory, boolean create) throws IOException {
        Path target = directory.toAbsolutePath().normalize();
        if (!target.startsWith(root())) throw new IOException("Data directory is outside this workspace partition");
        if (create) register();
        jade.directory(target, create);
    }

    /** 模型默认只读当前项目的可阅读产物，状态、会话原始日志、锁及其它项目不在此授权内。 */
    public boolean readableArtifact(Path original) {
        try {
            Path path = original.toAbsolutePath().normalize();
            if (!path.startsWith(root())) return false;
            String relative = root().relativize(path).toString().replace('\\', '/');
            // 白名单只包含交给模型消费的产物，不因同属 ~/.jadecode 就开放会话、锁或配置。
            boolean allowed = relative.matches("transcripts/[^/]+\\.jsonl") || relative.matches("tool-results/[^/]+\\.txt")
                    || relative.matches("teams/team_[0-9a-f-]+/messages/mail_[0-9a-f-]+\\.md")
                    || relative.matches("workflows/wf_[0-9a-f]{32}/output\\.json");
            if (!allowed) return false;
            directory(path.getParent(), false); regular(path); return true;
        } catch (IOException | RuntimeException invalid) { return false; }
    }

    /** 普通文件检查不跟随最终链接；不把目录或不存在资料当作可读产物。 */
    private static void regular(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Runtime data is not a regular file");
    }
}
