package com.jadecode.context;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * 工具结果的存盘、预览外壳，以及判断一段文本里的存盘路径可不可信。
 *
 * <p>只保存注入的 tool-results 目录，不保存阈值。预览留多少字符由调用方传进来，
 * “多大才值得存盘”由各个压缩策略自己决定。本类不保存会话状态，父子循环可以安全共享同一个实例。
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
            throw new IllegalStateException("Failed to create tool result directory: " + toolResultsDir, e);
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
            throw new IllegalStateException("Failed to write tool result: " + toolResultsDir, e);
        }
    }

    /**
     * 判断一段工具结果里的存盘路径是不是可信（也就是本压缩器自己写的）。
     *
     * <p>只认两种由本压缩器生成的外壳（见 {@link #persistedPreview} 和 {@link MicroCompactor} 的占位）。
     * 工具输出里自己伪造的 {@code Full output: /tmp/...} 不认。外壳必须从字符串开头逐字匹配，
     * 路径只在固定位置取，不扫描正文或预览里的同名行，否则伪造的文本就能冒充。
     * 候选路径必须是绝对路径，解析真实路径后仍在 tool-results 目录下，而且确实是普通文件；
     * 包含关系用 {@link Path#startsWith} 逐段比较，不用字符串前缀。
     *
     * <p>拿不准就当不可信返回 null（形状不符、没有候选、路径非法、越界、不是普通文件、解析失败都一样），
     * 调用方会重新存盘。最坏多一个文件，不会丢数据。
     *
     * @param output 工具结果文本
     * @return 可信的绝对路径字符串；不可信或无法判断时为 null
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
            Path actual = path;
            Path parent = toolResultsDir.toAbsolutePath().getParent();
            if (parent != null && parent.getFileName().toString().equals(".jadecode"))
                actual = new com.jadecode.config.WorkspacePaths(parent.getParent()).legacyRead(path);
            Path realFile = actual.toRealPath();
            if (!realFile.startsWith(realDir) || !Files.isRegularFile(realFile)) {
                return null;
            }
            return realFile.toString();
        } catch (IOException e) {
            // 解析失败或文件读不到 = 无法判断,当作不可信。
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
            throw new IllegalArgumentException("Preview length cannot be negative: " + previewChars);
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
            throw new IllegalStateException("Failed to read persisted preview: " + path, e);
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
            throw new IllegalArgumentException("Preview length cannot be negative: " + previewChars);
        }
        return output.length() <= previewChars ? output : output.substring(0, previewChars);
    }

    /**
     * 生成存盘预览外壳：已经有可信存盘路径的就复用（并从文件里读回预览），否则先存盘再取预览。
     *
     * @param toolUseId    工具调用 ID
     * @param output       完整原文
     * @param previewChars 预览保留的字符数
     * @return 存盘预览外壳文本
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
