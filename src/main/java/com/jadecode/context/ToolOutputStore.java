package com.jadecode.context;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * ToolOutputStore -- 工具结果的落盘、预览外壳与可信路径识别。
 *
 * <p>
 * 只持有注入的 tool-results 目录,<b>不持有阈值</b>:预览保留多少字符由调用方传进来,
 * "多大才值得落盘"这类策略判断留在各压缩策略里。
 * </p>
 *
 * <p>
 * 本类<b>没有可变会话状态</b>,父子循环可以安全共享同一个实例。
 * </p>
 */
final class ToolOutputStore {

    /** 工具结果落盘目录(可信路径必须位于它之下) */
    private final Path toolResultsDir;

    /**
     * @param toolResultsDir 工具结果落盘目录
     */
    ToolOutputStore(Path toolResultsDir) {
        this.toolResultsDir = toolResultsDir;
    }

    /**
     * 把一份完整工具结果落盘为 UTF-8 原文。
     *
     * <p>
     * 文件名先清洗 {@code tool_use_id},并且要保证同 ID 重复落盘或清洗后碰撞都
     * <b>不会覆盖</b>既有文件。
     * </p>
     *
     * <p>
     * <b>不覆盖是靠结构保证的</b>:{@link Files#createTempFile} 底层为 CREATE_NEW,同名即换名重试,
     * 所以"同 ID 再来一次"和"清洗后碰撞"都不可能落到既有文件上——不是概率,也不是"先查再写"。
     * ID 只作文件名前缀,白名单不含 {@code / \ .},不可能把路径引出注入目录。
     * </p>
     *
     * <p>
     * 建目录或写入失败一律带 cause 抛 {@link IllegalStateException};失败时只清理本次自己刚创建的文件。
     * </p>
     *
     * @param toolUseId 工具调用 ID
     * @param output    完整原文
     * @return 实际写出的文件路径
     * @throws IllegalStateException 建目录或写文件失败(带 cause)
     */
    Path saveOutput(String toolUseId, String output) {
        // 1. 清洗 ID:只留 ASCII 字母、数字、'-'、'_'。
        // '/'、'\'、'.' 都不在白名单里,所以结果既不含路径分隔符也不含 "..",
        // 不可能把路径引出 toolResultsDir;清洗后为空则退回固定前缀。
        String rawId = toolUseId == null ? "" : toolUseId;
        StringBuilder cleaned = new StringBuilder(Math.min(rawId.length(), 60));
        for (int i = 0; i < rawId.length() && cleaned.length() < 60; i++) {
            char c = rawId.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            cleaned.append(safe ? c : '_');
        }
        String prefix = cleaned.isEmpty() ? "tool-result" : cleaned.toString();
        if (prefix.length() < 2) {
            // createTempFile 要求前缀至少 3 个字符(下面还要拼一个 '-')。
            prefix = prefix + "_";
        }

        // 2. 建目录。失败即抛出,绝不返回一个写不进去的路径。
        try {
            Files.createDirectories(toolResultsDir);
        } catch (IOException e) {
            throw new IllegalStateException("创建工具结果目录失败: " + toolResultsDir, e);
        }

        // 3. 先创建一个只属于本次调用的新文件,再往这个刚建出来的文件里写。
        Path created = null;
        try {
            created = Files.createTempFile(toolResultsDir, prefix + "-", ".txt");
            Files.writeString(created, output, StandardCharsets.UTF_8);
            return created.toAbsolutePath();
        } catch (IOException e) {
            // 只清理本次自己创建的精确文件;清理失败挂成 suppressed,不掩盖原始写入异常。
            if (created != null) {
                try {
                    Files.deleteIfExists(created);
                } catch (IOException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            }
            throw new IllegalStateException("写入工具结果失败: " + toolResultsDir, e);
        }
    }

    /**
     * 识别一段工具结果里是否带有<b>本 compactor 生成的、可信的</b>落盘路径。
     *
     * <p>
     * 只认我们自己写的两种形状(见 {@link #persistedPreview} 与
     * {@link MicroCompactor#microCompact} 写出的占位)。工具输出里伪造的
     * {@code Full output: /tmp/...} 一律不认:候选路径必须是绝对的、
     * 解析后仍位于注入的 tool-results 目录之下,且<b>确实是普通文件</b>。
     * </p>
     *
     * <p>
     * <b>只认固定形状</b>:外壳必须从字符串<b>最开头</b>逐字匹配,路径取固定位置的那一行——
     * 绝不扫描正文或预览里的同名行,否则伪造的 {@code Full output: ...} 就能冒充 marker。
     * 候选必须原本就是绝对路径,解析真实路径后仍在真实 tool-results 目录之下,且确实是普通文件;
     * 包含判断用 {@link Path#startsWith}(逐段),不用字符串前缀比较。
     * </p>
     *
     * <p>
     * 这里是 fail-closed:形状不符、无候选、非法路径、越界、非普通文件、无法判定,
     * 一律当作不可信返回 null,调用方会重新落盘——最坏多一个文件,绝不丢数据。
     * </p>
     *
     * @param output 工具结果文本
     * @return 可信的绝对路径字符串;不可信或无法判定时为 null
     */
    String persistedOutputPath(String output) {
        if (output == null) {
            return null;
        }

        // 1. 两种固定外壳,都必须从字符串最开头匹配;路径只在固定位置取。
        final String shellHead = "<persisted-output>\nFull output: ";
        final String shellTail = "</persisted-output>";
        final String earlierHead = "[Earlier tool result saved at ";

        String candidate;
        if (output.startsWith(shellHead) && output.endsWith(shellTail)) {
            // Full output 那一行的行尾 = 外壳头之后第一个换行符。
            int pathEnd = output.indexOf('\n', shellHead.length());
            if (pathEnd < 0 || !output.startsWith("Preview:\n", pathEnd + 1)) {
                return null; // 外壳形状不符
            }
            candidate = output.substring(shellHead.length(), pathEnd);
        } else if (output.startsWith(earlierHead) && output.endsWith("]")) {
            candidate = output.substring(earlierHead.length(), output.length() - 1);
            if (candidate.indexOf('\n') >= 0) {
                return null; // micro 的占位必须是单行
            }
        } else {
            return null;
        }

        // 2. 候选必须原本就是绝对路径。
        final Path path;
        try {
            path = Path.of(candidate);
        } catch (InvalidPathException e) {
            return null; // 非法路径文本
        }
        if (!path.isAbsolute()) {
            return null;
        }

        // 3. 真实路径校验:候选与落盘目录都解析到真实路径后再判包含关系。
        // toRealPath 会把符号链接解到最终目标,所以"指向目录外的软链"会在这里被拒。
        try {
            Path realDir = toolResultsDir.toRealPath();
            Path realFile = path.toRealPath();
            if (!realFile.startsWith(realDir) || !Files.isRegularFile(realFile)) {
                return null;
            }
            return realFile.toString();
        } catch (IOException e) {
            // 解析失败或文件读不到 = 无法判定 → fail-closed。
            return null;
        }
    }

    /**
     * 读文件开头至多 {@code previewChars} 个 Java char。
     *
     * <p>
     * <b>有界读取</b>:按块读,凑够预览长度就停,不会为了 2000 个字符把整个大文件读进内存;
     * 文件比预览短时读到 EOF 自然结束。UTF-8 解码交给 reader。
     * </p>
     *
     * @param path         源文件
     * @param previewChars 预览长度(Java char 数,即 UTF-16 单元);0 返回空串
     * @return 文件开头至多 previewChars 个 char
     * @throws IllegalArgumentException previewChars 为负
     * @throws IllegalStateException    读取失败(带 cause)
     */
    private static String readHead(Path path, int previewChars) {
        if (previewChars < 0) {
            throw new IllegalArgumentException("预览长度不能为负: " + previewChars);
        }
        if (previewChars == 0) {
            return "";
        }

        StringBuilder head = new StringBuilder(Math.min(previewChars, 4096));
        char[] chunk = new char[Math.min(previewChars, 4096)];
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            while (head.length() < previewChars) {
                int want = Math.min(chunk.length, previewChars - head.length());
                int read = reader.read(chunk, 0, want);
                if (read < 0) {
                    break; // EOF:文件比预览短
                }
                head.append(chunk, 0, read);
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取落盘预览失败: " + path, e);
        }
        return head.toString();
    }

    /**
     * 取文本开头至多 {@code previewChars} 个 Java char;短文本原样返回。
     *
     * @param output       完整文本
     * @param previewChars 预览长度(Java char 数);0 返回空串
     * @return 开头至多 previewChars 个 char
     * @throws IllegalArgumentException previewChars 为负
     */
    private static String head(String output, int previewChars) {
        if (previewChars < 0) {
            throw new IllegalArgumentException("预览长度不能为负: " + previewChars);
        }
        return output.length() <= previewChars ? output : output.substring(0, previewChars);
    }

    /**
     * 生成落盘预览外壳:已有可信 marker 就复用它(并从中回读预览),否则先落盘再取预览。
     *
     * <p>
     * TODO(s08-user): 实现本方法,外壳格式见作业单。
     * </p>
     *
     * @param toolUseId    工具调用 ID
     * @param output       完整原文
     * @param previewChars 预览保留的字符数
     * @return 落盘预览外壳文本
     */
    String persistedPreview(String toolUseId, String output, int previewChars) {
        String saved = persistedOutputPath(output);
        Path path;
        String preview;
        if (saved != null) {
            path = Path.of(saved);
            preview = readHead(path, previewChars);
        } else {
            path = saveOutput(toolUseId, output);
            preview = head(output, previewChars);
        }
        return "<persisted-output>\n" + "Full output: " + path + "\nPreview:\n" + preview + "\n</persisted-output>";
    }
}
