package com.jadecode.context;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

import com.jadecode.messages.ContentBlock;
import com.jadecode.messages.Message;
import com.jadecode.messages.Role;
import com.jadecode.messages.TextBlock;

/**
 * TranscriptStore -- 完整历史的 JSONL 归档与归档标记的可信识别。
 *
 * <p>
 * 只持有注入的 transcript 目录。<b>不决定何时归档、归档哪一段、是否重复归档</b>——
 * 那些是 snip / 响应式压缩等策略的职责。
 * </p>
 *
 * <p>
 * 本类<b>没有可变会话状态</b>,父子循环可以安全共享同一个实例。
 * </p>
 */
final class TranscriptStore {

    /** transcript 归档目录 */
    private final Path transcriptDir;

    /**
     * @param transcriptDir transcript 归档目录
     */
    TranscriptStore(Path transcriptDir) {
        this.transcriptDir = transcriptDir;
    }

    /**
     * 把完整历史写成 UTF-8 JSONL(一行一条完整序列化消息)。
     *
     * <p>
     * 文件名必须保证<b>绝不覆盖</b>既有 transcript:{@link Files#createTempFile} 底层为 CREATE_NEW,
     * 同名即换名重试 —— 同内容、连续调用、名称碰撞都落不到既有归档上,不是概率,也不是"先查再写"。
     * </p>
     *
     * <p>
     * 用 writer <b>流式</b>逐条写,不先把全部历史拼成一个巨型字符串;消息顺序原样保留,
     * 消息文本里的换行由 JSON 序列化转义,不会破坏"一行一条"。
     * </p>
     *
     * <p>
     * 失败时只清理<b>本次自己创建</b>的那个新文件:目录/读写失败走 {@link IOException} 分支,
     * 序列化失败(非受检的 {@link IllegalStateException})单独处理——这条路径同样会留下半成品文件。
     * 两条路径都保留原始异常链,清理失败挂成 suppressed。
     * </p>
     *
     * @param messages 消息列表
     * @return 实际写出的文件路径
     * @throws IllegalStateException 建目录、写文件或序列化失败(带 cause)
     */
    Path writeTranscript(List<Message> messages) {
        // 1. 建目录。失败即抛出,绝不返回一个写不进去的路径。
        try {
            Files.createDirectories(transcriptDir);
        } catch (IOException e) {
            throw new IllegalStateException("创建 transcript 目录失败: " + transcriptDir, e);
        }

        // 2. 先创建本次专属的新文件(createTempFile 底层 CREATE_NEW,同名换名重试),
        // 再往这个刚建出来的文件里流式逐条写;不筛选消息,不计算压缩范围。
        Path created = null;
        try {
            created = Files.createTempFile(transcriptDir, "transcript-", ".jsonl");
            try (BufferedWriter writer = Files.newBufferedWriter(created, StandardCharsets.UTF_8)) {
                for (Message message : messages) {
                    writer.write(ContextSerialization.toJson(message));
                    writer.newLine();
                }
            }
            return created.toAbsolutePath();
        } catch (IOException e) {
            deleteCreated(created, e);
            throw new IllegalStateException("写入 transcript 失败: " + transcriptDir, e);
        } catch (IllegalStateException e) {
            // 序列化失败:同样只清理本次自己创建的新文件,并原样抛出保留原始 cause。
            deleteCreated(created, e);
            throw e;
        }
    }

    /**
     * 清理本次自己创建的精确文件;清理失败挂成 suppressed,不掩盖原始异常。
     *
     * @param created  本次创建的文件;null 表示还没建出来,直接返回
     * @param original 需要保留的原始异常
     */
    private static void deleteCreated(Path created, Throwable original) {
        if (created == null) {
            return;
        }
        try {
            Files.deleteIfExists(created);
        } catch (IOException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    /**
     * 判断一条消息是否是<b>可信</b>的 archive marker:由本 compactor 生成,
     * 且指向 transcript 目录之内的既有普通文件。
     *
     * <p>
     * 只认 role=USER、<b>恰好一个</b> TextBlock、且全文严格等于固定形状
     * {@code [N messages archived at <绝对路径>]} 的消息;不从任意正文、多个 block 或工具结果里
     * 抽取"看起来像标记"的子串。N 只校验是非负十进制整数 —— <b>不</b>读归档核对,它是被替换中段的条数,
     * 不是完整归档行数。
     * </p>
     *
     * <p>
     * 路径段<b>允许合法的方括号</b>(例如 {@code /tmp/project[demo]/...})——最外层那对方括号是标记
     * 自身的定界符,已经在取候选时剥掉;要不要信这条路径由真实路径校验决定,不由字符白名单决定。
     * 但候选里出现换行即判不可信(那说明吞了尾随正文)。
     * </p>
     *
     * <p>
     * 与 {@link ToolOutputStore#persistedOutputPath} 同样 fail-closed:形状不符、路径非绝对、解析失败、
     * 落在 transcript 目录之外、不是普通文件 —— 一律 false。
     * </p>
     *
     * @param message 候选消息
     * @return true = 可信的归档标记
     */
    boolean isArchiveMarker(Message message) {
        // 1. 只认 role=USER 且恰好一个 TextBlock 的消息。
        if (message == null || message.role() != Role.USER) {
            return false;
        }
        List<ContentBlock> content = message.content();
        if (content == null || content.size() != 1 || !(content.getFirst() instanceof TextBlock text)) {
            return false;
        }
        String marker = text.text();
        if (marker == null) {
            return false;
        }

        // 2. 全文严格匹配固定形状:不做子串搜索,不吞尾随正文或换行。
        final String separated = " messages archived at ";
        if (!marker.startsWith("[") || !marker.endsWith("]")) {
            return false;
        }
        int separatedAt = marker.indexOf(separated, 1);
        if (separatedAt < 0) {
            return false;
        }

        // 3. N 必须是非负十进制整数;非法或溢出都判 false(只校验形状,不用它的值)。
        String count = marker.substring(1, separatedAt);
        if (count.isEmpty()) {
            return false;
        }
        for (int i = 0; i < count.length(); i++) {
            char c = count.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        try {
            Integer.parseInt(count);
        } catch (NumberFormatException e) {
            return false; // 超出 int 范围
        }

        // 4. 剥掉最外层 '[' 与最后 ']' 之后就是候选路径;方括号本身合法,只有换行说明吞了正文。
        String candidate = marker.substring(separatedAt + separated.length(), marker.length() - 1);
        if (candidate.isEmpty() || candidate.indexOf('\n') >= 0) {
            return false;
        }

        // 5. 候选必须原本就是绝对路径。
        final Path path;
        try {
            path = Path.of(candidate);
        } catch (InvalidPathException e) {
            return false; // 非法路径文本
        }
        if (!path.isAbsolute()) {
            return false;
        }

        // 6. 真实路径校验:候选与归档目录都解析到真实路径后再判包含关系。
        // toRealPath 会把符号链接解到最终目标,所以"指向目录外的软链"在这里被拒。
        try {
            Path realDir = transcriptDir.toRealPath();
            Path realFile = path.toRealPath();
            return realFile.startsWith(realDir) && Files.isRegularFile(realFile);
        } catch (IOException e) {
            // 解析失败或文件读不到 = 无法判定 → fail-closed。
            return false;
        }
    }
}
