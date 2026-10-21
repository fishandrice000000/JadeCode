package com.jadecode.tui.media;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import com.jadecode.tui.terminal.TerminalCapabilities;

/**
 * 受信本地图像的尺寸/EXIF/PNG 转码/终端占位与缓存；不联网下载 Markdown URL，也不赋予 Agent
 * 看图能力。
 *
 * <p>只接受明确来源（剪贴板/本地文件）的字节，限制输入大小；ImageIO 使用 JDK PNG/GIF 与
 * 打包的 JPEG/WebP provider。JPEG EXIF orientation 在解码后显式旋转，不能假设 provider
 * 自动应用。Kitty 只发送 PNG，其它格式先转码；cell 像素未知时使用 Pi 的 9×18 默认值。
 * 缓存有 32 张/总字节上限，失败返回空并显示文字回退，不伪造传图成功。</p>
 */
public final class ImageService {
    /** 单张输入上限，防止一个坏图无限分配。 */
    private static final int MAX_INPUT_BYTES = 12 * 1024 * 1024;
    private static final int MAX_CACHE_IMAGES = 32;
    private static final long MAX_CACHE_BYTES = 64L * 1024 * 1024;
    private static final int DEFAULT_CELL_WIDTH = 9;
    private static final int DEFAULT_CELL_HEIGHT = 18;

    /** 已准备显示的图片。 */
    public record Prepared(int id, byte[] png, int pixelWidth, int pixelHeight, int columns, int rows) { }

    private record Cached(byte[] png, int pixelWidth, int pixelHeight) { }

    private final TerminalCapabilities capabilities;
    private final LinkedHashMap<Integer, Cached> cache = new LinkedHashMap<>(16, 0.75f, true);
    private long cachedBytes;
    private int nextId = 1;

    public ImageService(TerminalCapabilities capabilities) { this.capabilities = capabilities; }

    /** @return 当前终端是否有图片协议；false 时调用方显示文字回退。 */
    public boolean available() { return capabilities.imagesAvailable(); }

    /**
     * 读取尺寸、应用 EXIF 方向并准备 PNG/占位。
     * @param data 受信本地图像字节
     * @param maxWidthCells 组件允许的最大列（默认 60，同时受窗口限制）
     * @param cellWidth cell 像素宽；0 用默认 9
     * @param cellHeight cell 像素高；0 用默认 18
     * @return 准备结果；格式不支持/超限/损坏为空
     */
    public Optional<Prepared> prepare(byte[] data, int maxWidthCells, int cellWidth, int cellHeight) {
        if (data == null || data.length == 0 || data.length > MAX_INPUT_BYTES) return Optional.empty();
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) return Optional.empty();
            int orientation = jpegOrientation(data);
            if (orientation != 1) image = orient(image, orientation);
            byte[] png = toPng(image);
            if (png.length == 0) return Optional.empty();
            int cw = cellWidth > 0 ? cellWidth : DEFAULT_CELL_WIDTH;
            int ch = cellHeight > 0 ? cellHeight : DEFAULT_CELL_HEIGHT;
            int columns = Math.max(1, Math.min(Math.max(1, maxWidthCells), Math.max(1, image.getWidth() / cw)));
            int rows = Math.max(1, (int) Math.ceil(image.getHeight() * (double) columns * cw / image.getWidth() / ch));
            int id = nextId++;
            putCache(id, png, image.getWidth(), image.getHeight());
            return Optional.of(new Prepared(id, png, image.getWidth(), image.getHeight(), columns, rows));
        } catch (IOException | RuntimeException failure) { return Optional.empty(); }
    }

    /** 生成当前终端协议序列；不支持时返回空串。 */
    public String encode(Prepared prepared, String name) {
        if (prepared == null) return "";
        return switch (capabilities.images()) {
            case KITTY -> ImageProtocol.kitty(prepared.png(), prepared.id(), prepared.columns(), prepared.rows()).sequence();
            case ITERM2 -> ImageProtocol.iterm2(prepared.png(), prepared.id(), prepared.columns(), prepared.rows(), name).sequence();
            case NONE -> "";
        };
    }

    /** 释放图片 ID/缓存；退出或图像失效时调用。 */
    public String release(int id) {
        cache.remove(id);
        if (capabilities.images() == TerminalCapabilities.ImageSupport.KITTY)
            return ImageProtocol.deleteKitty(id);
        return "";
    }

    /** @return 缓存中的 PNG；不存在空。 */
    public Optional<byte[]> cachedPng(int id) {
        Cached value = cache.get(id);
        return value == null ? Optional.empty() : Optional.of(value.png().clone());
    }

    /** @return 缓存数量/字节，测试与压力诊断。 */
    public int cacheSize() { return cache.size(); }

    private void putCache(int id, byte[] png, int width, int height) {
        cache.put(id, new Cached(png, width, height)); cachedBytes += png.length;
        while (cache.size() > MAX_CACHE_IMAGES || cachedBytes > MAX_CACHE_BYTES) {
            var iterator = cache.entrySet().iterator();
            if (!iterator.hasNext()) break;
            var eldest = iterator.next();
            cachedBytes -= eldest.getValue().png().length; iterator.remove();
        }
    }

    private static byte[] toPng(BufferedImage image) throws IOException {
        var output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) return new byte[0];
        return output.toByteArray();
    }

    /** 解码后按 EXIF orientation 1-8 旋转/镜像；不修改缓存原图。 */
    static BufferedImage orient(BufferedImage source, int orientation) {
        int width = source.getWidth(), height = source.getHeight();
        boolean swap = orientation >= 5 && orientation <= 8;
        var result = new BufferedImage(swap ? height : width, swap ? width : height,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            AffineTransform transform = switch (orientation) {
                case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);
                case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
                case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);
                case 5 -> new AffineTransform(0, -1, -1, 0, height, width);
                case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
                case 7 -> new AffineTransform(0, 1, 1, 0, 0, 0);
                case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
                default -> new AffineTransform();
            };
            graphics.drawImage(source, transform, null);
        } finally { graphics.dispose(); }
        return result;
    }

    /**
     * 读取 JPEG APP1 EXIF orientation；没有/非法时返回 1。只扫描标记段，不依赖第三方解析器。
     */
    static int jpegOrientation(byte[] data) {
        if (data.length < 4 || (data[0] & 0xff) != 0xff || (data[1] & 0xff) != 0xd8) return 1;
        int offset = 2;
        while (offset + 4 <= data.length) {
            if ((data[offset] & 0xff) != 0xff) return 1;
            int marker = data[offset + 1] & 0xff;
            offset += 2;
            if (marker == 0xd8 || marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue;
            if (marker == 0xda || marker == 0xd9) return 1;
            int length = ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
            if (length < 2 || offset + length > data.length) return 1;
            if (marker == 0xe1 && length > 8 && data[offset + 2] == 'E' && data[offset + 3] == 'x'
                    && data[offset + 4] == 'i' && data[offset + 5] == 'f') {
                int tiff = offset + 8;
                return tiffOrientation(data, tiff, offset + length);
            }
            offset += length;
        }
        return 1;
    }

    /** TIFF header 内查找 orientation tag (0x0112)。 */
    private static int tiffOrientation(byte[] data, int start, int end) {
        if (start + 8 > end) return 1;
        boolean little = data[start] == 'I' && data[start + 1] == 'I';
        if (!little && !(data[start] == 'M' && data[start + 1] == 'M')) return 1;
        int ifd = start + int32(data, start + 4, little);
        if (ifd + 2 > end) return 1;
        int count = int16(data, ifd, little);
        for (int i = 0; i < count; i++) {
            int entry = ifd + 2 + i * 12;
            if (entry + 12 > end) break;
            if (int16(data, entry, little) == 0x0112) {
                int value = int16(data, entry + 8, little);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static int int16(byte[] data, int offset, boolean little) {
        int a = data[offset] & 0xff, b = data[offset + 1] & 0xff;
        return little ? a | b << 8 : a << 8 | b;
    }

    private static int int32(byte[] data, int offset, boolean little) {
        return little
                ? (data[offset] & 0xff) | (data[offset + 1] & 0xff) << 8 | (data[offset + 2] & 0xff) << 16 | (data[offset + 3] & 0xff) << 24
                : (data[offset] & 0xff) << 24 | (data[offset + 1] & 0xff) << 16 | (data[offset + 2] & 0xff) << 8 | (data[offset + 3] & 0xff);
    }

    /** @return 某个文件扩展名对应的语言提示（仅测试/工具 renderer 使用）。 */
    public static String languageFor(String path) {
        if (path == null) return "";
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        return dot < 0 ? "" : lower.substring(dot + 1);
    }

    /** @return 当前 cell 尺寸/能力快照，供组件占位。 */
    public TerminalCapabilities capabilities() { return capabilities; }
}
