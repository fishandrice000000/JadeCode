package com.jadecode.config;

import java.io.IOException;
import java.nio.file.*;
import com.jadecode.util.Json;

/**
 * 某个项目在用户数据根中的分区。workspace 永远用于业务命令，root 只用于 Harness 持久状态。
 * 构造解析已有工作区真实路径但不写盘；不同 checkout/worktree 按真实路径哈希隔离。
 *
 * @param workspace 会话绑定的真实工作区目录
 * @param jade 执行机器的用户数据布局，提供配置和持久数据目录
 */
public record WorkspacePaths(Path workspace, JadePaths jade) {
    /**
     * 解析工作区真实路径并确认目标为目录，绑定用户数据根。路径不可访问或目标类型错误时抛出 IllegalArgumentException，
     * 后续数据分区使用此真实路径计算身份。
     *
     * @param workspace 会话绑定的真实工作区目录
     * @param jade 执行机器的用户数据布局，提供配置和持久数据目录
     * @throws IllegalArgumentException 工作区不可访问或不是目录
     * @throws NullPointerException jade 为 null
     */
    public WorkspacePaths {
        try {
            workspace = workspace.toRealPath();
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Workspace is inaccessible", invalid);
        }
        java.util.Objects.requireNonNull(jade);
        if (!Files.isDirectory(workspace))
            throw new IllegalArgumentException("Workspace must be a directory");
    }

    /**
     * 生产默认用户根；离线调用者应显式提供临时 JadePaths。
     *
     * @param workspace 会话绑定的真实工作区目录
     */
    public WorkspacePaths(Path workspace) {
        this(workspace, JadePaths.defaults());
    }

    /**
     * 以工作区真实路径计算稳定哈希 ID，供会话、工具输出和记忆作用域使用。
     *
     * @return 真实工作区路径的稳定哈希分区 ID
     */
    public String id() {
        return JadePaths.identity(workspace);
    }

    /**
     * 返回用户数据根中属于此工作区的独立分区目录 workspaces/id。
     *
     * @return 此工作区专属的数据分区目录
     */
    public Path root() {
        return jade.workspaces().resolve(id());
    }

    /**
     * 返回此工作区的会话清单与事件日志目录。
     *
     * @return 会话清单与 events.jsonl 所在目录
     */
    public Path sessions() {
        return root().resolve("sessions");
    }

    /**
     * 返回此工作区的持久任务目录。
     *
     * @return 持久任务目录
     */
    public Path tasks() {
        return root().resolve("tasks");
    }

    /**
     * 返回此工作区的压缩前历史归档目录。
     *
     * @return 压缩前完整消息归档目录
     */
    public Path transcripts() {
        return root().resolve("transcripts");
    }

    /**
     * 返回此工作区的大工具输出存储目录，供上下文中的文件引用定位原文。
     *
     * @return 大工具输出文件目录
     */
    public Path toolResults() {
        return root().resolve("tool-results");
    }

    /**
     * 返回此工作区的队友邮箱与协作数据目录。
     *
     * @return Team 邮件与协作数据目录
     */
    public Path teams() {
        return root().resolve("teams");
    }

    /**
     * 返回此工作区的 Workflow 持久状态目录。
     *
     * @return Workflow 持久状态目录
     */
    public Path workflows() {
        return root().resolve("workflows");
    }

    /**
     * 返回此工作区定时任务定义文件 cron/jobs.json 的路径。
     *
     * @return 当前工作区的 cron/jobs.json 路径
     */
    public Path cronFile() {
        return root().resolve("cron/jobs.json");
    }

    /**
     * 首次实际使用注册项目归属；已有元数据必须匹配，不凭目录名猜测或覆盖其它项目。
     *
     * @throws IOException 工作区数据分区创建、真实路径核对或登记文件保存失败
     */
    public void register() throws IOException {
        // 所有注册先经过祖先目录检查，不能把注册文件写进一个链接分区。
        jade.directory(root(), true);
        Path file = root().resolve("workspace.json");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            // 已有分区只比对，不覆写用户修改或其它项目的身份。
            regular(file);
            var saved = Json.mapper().readTree(Files.readString(file));
            if (saved == null || !saved.path("id").asText().equals(id())
                    || !saved.path("workspace").asText().equals(workspace.toString()))
                throw new IOException("Workspace registry identity mismatch");
        } else {
            var value = Json.mapper().createObjectNode().put("id", id()).put("workspace", workspace.toString());
            // 注册内容由真实路径唯一确定；同项目并发初始化得到相同内容。
            JadePaths.atomicWrite(file, value.toString());
        }
    }

    /**
     * 只允许访问当前分区；创建目录前先登记项目元数据，读取不隐式建空库。
     *
     * @param directory 需要逐级检查、创建或刷新目录项的目标目录
     * @param create 是否为缺失路径创建目录
     * @throws IOException 数据子目录不在当前分区内，或逐级检查创建失败
     */
    public void directory(Path directory, boolean create) throws IOException {
        Path target = directory.toAbsolutePath().normalize();
        if (!target.startsWith(root()))
            throw new IOException("Data directory is outside this workspace partition");
        if (create)
            register();
        jade.directory(target, create);
    }

    /**
     * 模型默认只读当前项目的可阅读产物，状态、会话原始日志、锁及其它项目不在此授权内。
     *
     * @param original 原执行上下文或原始资源，供派生对象沿用身份
     * @return 该路径属于此工作区可阅读产物且通过路径检查时为 true
     */
    public boolean readableArtifact(Path original) {
        try {
            Path path = original.toAbsolutePath().normalize();
            if (!path.startsWith(root()))
                return false;
            String relative = root().relativize(path).toString().replace('\\', '/');
            // 白名单只包含交给模型消费的产物，不因同属 ~/.jadecode 就开放会话、锁或配置。
            boolean allowed = relative.matches("transcripts/[^/]+\\.jsonl")
                    || relative.matches("tool-results/[^/]+\\.txt")
                    || relative.matches("teams/team_[0-9a-f-]+/messages/mail_[0-9a-f-]+\\.md")
                    || relative.matches("workflows/wf_[0-9a-f]{32}/output\\.json");
            if (!allowed)
                return false;
            directory(path.getParent(), false);
            regular(path);
            return true;
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    /**
     * 普通文件检查不跟随最终链接；不把目录或不存在资料当作可读产物。
     *
     * @param path 当前工作区分区内需要核对的数据文件路径
     * @throws IOException 目标不是允许的普通数据文件或路径检查失败
     */
    private static void regular(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Runtime data is not a regular file");
    }
}
