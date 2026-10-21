package com.jadecode.tui.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 用户 Ctrl+V 图片时读取桌面剪贴板、保存为应用拥有的临时文件并返回可插入路径。
 *
 * <p>这不是 Agent 图像消息：编辑器只插入文件路径，界面不会声称模型已看图。读取只在用户
 * 显式操作时发生；MIME 优先 PNG/JPEG/WebP，命令使用参数列表，无 shell 插值。无桌面读取
 * 后端时明确失败，不假称成功；临时文件在会话可能仍使用时保留，关闭时只清理本次登记且
 * 属于本次创建的文件，不删除用户文件。</p>
 */
public final class ImageClipboard implements AutoCloseable {
    /** PNG/JPEG/WebP 的受控 MIME 顺序。 */
    private static final List<Mime> MIMES = List.of(
            new Mime("image/png", ".png"), new Mime("image/jpeg", ".jpg"), new Mime("image/webp", ".webp"));

    private record Mime(String type, String extension) { }

    private final Path directory;
    private final List<Path> owned = new ArrayList<>();

    /** @param directory 应用拥有的临时目录；不存在时按需创建。 */
    public ImageClipboard(Path directory) { this.directory = directory; }

    /**
     * 读取剪贴板图片并保存。
     * @return 临时文件路径；无后端/无图片/保存失败为空
     */
    public Optional<Path> saveFromClipboard() {
        for (Mime mime : MIMES) {
            for (List<String> command : commands(mime.type())) {
                try {
                    Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    byte[] data = process.getInputStream().readAllBytes();
                    if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0
                            || data.length == 0 || data.length > 12 * 1024 * 1024) continue;
                    if (!looksLikeImage(data)) continue;
                    Files.createDirectories(directory);
                    Path file = directory.resolve("clipboard-" + java.util.UUID.randomUUID() + mime.extension());
                    Files.write(file, data);
                    owned.add(file);
                    return Optional.of(file.toAbsolutePath().normalize());
                } catch (Exception unavailable) { /* 尝试下一种后端/MIME。 */ }
            }
        }
        return Optional.empty();
    }

    private static List<List<String>> commands(String mime) {
        return List.of(List.of("wl-paste", "--type", mime, "--no-newline"),
                List.of("xclip", "-selection", "clipboard", "-t", mime, "-o"),
                List.of("xsel", "--clipboard", "--output"));
    }

    private static boolean looksLikeImage(byte[] data) {
        if (data.length < 12) return false;
        boolean png = (data[0] & 0xff) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G';
        boolean jpeg = (data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0xd8;
        boolean webp = data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
        return png || jpeg || webp;
    }

    /** @return 当前登记且仍存在的临时文件；测试/清理报告。 */
    public List<Path> ownedFiles() {
        return owned.stream().filter(Files::exists).toList();
    }

    /** 清理本次创建的文件；不处理目录中他人/用户文件。 */
    @Override public void close() {
        for (Path file : List.copyOf(owned)) {
            try { Files.deleteIfExists(file); } catch (IOException ignored) { }
        }
        owned.clear();
    }
}
