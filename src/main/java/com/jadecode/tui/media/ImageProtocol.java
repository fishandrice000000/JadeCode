package com.jadecode.tui.media;

import java.util.Base64;

/**
 * Kitty/iTerm2 图片协议的纯编码：不读文件、不联网、不持有终端。
 *
 * <p>Kitty 使用 4096 字符分块，按图像 ID 传输/删除；只发送 PNG。iTerm2 使用 OSC 1337
 * inline 参数，全屏 iTerm2 路径由调用方按 Pi 规则禁用。任何模型/工具正文都不能直接构造
 * 这些 APC/OSC 序列；只有 {@link ImageService} 的受信本地图像可进入本类。</p>
 */
public final class ImageProtocol {
    /** 传输结果：协议字符串与图像 ID。 */
    public record Encoded(String sequence, int imageId) { }

    private ImageProtocol() { }

    /**
     * 编码 Kitty 图片。
     * @param png PNG 字节（调用方已转码）
     * @param id 图像 ID
     * @param columns 显示列
     * @param rows 显示行
     */
    public static Encoded kitty(byte[] png, int id, int columns, int rows) {
        String payload = Base64.getEncoder().encodeToString(png);
        var out = new StringBuilder(payload.length() + 128);
        int offset = 0;
        boolean first = true;
        do {
            int end = Math.min(payload.length(), offset + 4096);
            String chunk = payload.substring(offset, end);
            boolean more = end < payload.length();
            out.append("\u001b_G");
            if (first) out.append("a=T,f=100,i=").append(id).append(",c=").append(Math.max(1, columns))
                    .append(",r=").append(Math.max(1, rows)).append(",q=2,");
            out.append("m=").append(more ? 1 : 0).append(';').append(chunk).append("\u001b\\");
            offset = end; first = false;
        } while (offset < payload.length());
        return new Encoded(out.toString(), id);
    }

    /** Kitty 删除指定图像 ID；退出/失效时必须调用，不能只靠重绘清屏。 */
    public static String deleteKitty(int id) { return "\u001b_Ga=d,d=I,i=" + id + "\u001b\\"; }

    /** 编码 iTerm2 inline 图片；仅 PNG/已转码数据。 */
    public static Encoded iterm2(byte[] png, int id, int columns, int rows, String name) {
        String payload = Base64.getEncoder().encodeToString(png);
        String safeName = (name == null ? "image.png" : name).replaceAll("[^A-Za-z0-9._-]", "_");
        String sequence = "\u001b]1337;File=inline=1;width=" + Math.max(1, columns) + ";height="
                + Math.max(1, rows) + ";preserveAspectRatio=1;name=" + safeName + ":" + payload + "\u0007";
        return new Encoded(sequence, id);
    }

    /** @return 是否为 Kitty 分块格式（测试用），校验每块 <=4096。 */
    public static boolean validKittyChunks(String sequence) {
        if (sequence == null || !sequence.startsWith("\u001b_G")) return false;
        var chunks = sequence.split("\u001b\\\\", -1);
        int seen = 0;
        for (String chunk : chunks) {
            int semicolon = chunk.indexOf(';');
            if (semicolon < 0) continue;
            int length = chunk.length() - semicolon - 1;
            if (length > 4096) return false;
            seen += length;
        }
        return seen > 0;
    }
}
