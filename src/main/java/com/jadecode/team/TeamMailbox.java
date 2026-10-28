package com.jadecode.team;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

import com.jadecode.session.SessionEvent.Incoming;
import com.jadecode.util.Json;

/**
 * 保存 Team 邮件的正文与待投递记录。短摘要进入 Inbox，长正文按需用 read_file 阅读。
 *
 * <p>课程使用 JSONL 邮箱；这里将每封信单独保存，避免清空邮箱时删掉模型仍引用的正文。
 * messages 保存不可变正文，pending 只表示还要交给接收会话。成功入队后可以删 pending，
 * 但不删正文。本阶段不恢复进程内 Session，因此不承诺重启后自动继续投递。</p>
 */
public final class TeamMailbox {
    private final Path root;
    private final com.jadecode.config.WorkspacePaths paths;

    /** 绑定本次 Team 的独立目录；构造不建文件，不扫描其它 Team 的历史邮件。 */
    public TeamMailbox(com.jadecode.config.WorkspacePaths paths) {
        this.paths = paths;
        root = paths.teams().resolve("team_" + UUID.randomUUID());
    }

    /**
     * 先写完整正文，再写可投递的元数据。失败保留已写材料，调用者不得报告已经送达。
     * 文件名与正文路径完全由程序生成；模型不能指定写盘位置或冒充发送者。
     * @return 已保存的短摘要、正文路径及来源信息；尚未进入接收方 Inbox
     */
    public Incoming prepare(String from, String to, String task, String assignment, String kind,
            String summary, String body, boolean insertion, boolean wake) throws IOException {
        if (summary == null || summary.isBlank() || summary.length() > 2000
                || body == null || body.isBlank() || body.length() > 200_000) {
            throw new IllegalArgumentException("Summary must be 1–2000 characters and body must be 1–200000 characters");
        }
        // teams 的祖先也检查，避免全局目录祖先被换成链接后写入其它位置。
        paths.directory(root.resolve("messages"), true);
        paths.directory(root.resolve("pending"), true);
        String id = "mail_" + UUID.randomUUID();
        Path path = root.resolve("messages").resolve(id + ".md");
        // CREATE_NEW 保护这份快照；重复提交不能覆盖模型曾经看到的原文。
        Files.writeString(path, body, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Incoming incoming = new Incoming(id, from, to, task, assignment, kind, summary, path.toString(), insertion, wake);
        Files.writeString(pending(id), Json.mapper().writeValueAsString(incoming), StandardOpenOption.CREATE_NEW);
        return incoming;
    }

    /** 成功交给 Inbox 后清除运输记录，正文必须继续存在；清理失败可再次调用。 */
    public void delivered(Incoming incoming) throws IOException { Files.deleteIfExists(pending(incoming.id())); }

    /** 名册与派工也共用本 Team 的受检目录，不能绕过全局目录祖先检查。 */
    void directory(Path directory) throws IOException {
        if (!directory.normalize().startsWith(root)) throw new IOException("Team data escapes its partition");
        paths.directory(directory, true);
    }

    /** 返回本次 Team 目录，供名册与派工记录使用，不把旧 Team 当作当前运行中的会话。 */
    public Path root() { return root; }

    /**
     * 详情页只读取本邮箱已登记的正文，拒绝替换成链接或异常膨胀的文件。
     * 校验目录时使用 false，不因只读查看而重建丢失目录；失败交给命令错误通道。
     */
    String readBody(Incoming incoming) throws IOException {
        Path file = root.resolve("messages").resolve(incoming.id() + ".md");
        if (!file.toString().equals(incoming.bodyPath())) throw new IOException("Report path does not match the mailbox record");
        paths.directory(root.resolve("messages"), false);
        if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 800_000)
            throw new IOException("Report body is missing, linked, or exceeds its size limit");
        String body = Files.readString(file);
        if (body.length() > 200_000) throw new IOException("Report body exceeds its character limit");
        return body;
    }

    /** 只接受程序格式 ID，不把模型字符串直接拼成任意文件路径。 */
    private Path pending(String id) {
        if (!id.matches("mail_[0-9a-f-]{36}")) throw new IllegalArgumentException("Invalid message ID");
        return root.resolve("pending").resolve(id + ".json");
    }
}
