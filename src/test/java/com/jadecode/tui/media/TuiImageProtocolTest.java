package com.jadecode.tui.media;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import com.jadecode.tui.terminal.TerminalCapabilities;

/** 图片协议分块/ID/删除/尺寸/EXIF 方向与 PNG 转码测试。 */
class TuiImageProtocolTest {
    @Test void kittyChunksStayWithin4096AndDeleteUsesSameId() {
        byte[] data = new byte[12_000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        var encoded = ImageProtocol.kitty(data, 42, 10, 5);
        assertTrue(ImageProtocol.validKittyChunks(encoded.sequence()));
        assertTrue(encoded.sequence().contains("i=42"));
        assertTrue(encoded.sequence().contains("m=0"));
        assertTrue(ImageProtocol.deleteKitty(42).contains("i=42"));
    }

    @Test void iterm2EncodesInlinePng() {
        var encoded = ImageProtocol.iterm2(new byte[]{1, 2, 3}, 7, 4, 2, "a b.png");
        assertTrue(encoded.sequence().startsWith("\u001b]1337;File=inline=1;"));
        assertTrue(encoded.sequence().contains("name=a_b.png"));
        assertTrue(encoded.sequence().endsWith("\u0007"));
    }

    @Test void serviceConvertsPngAndComputesCellOccupancy() throws Exception {
        var image = new BufferedImage(90, 36, BufferedImage.TYPE_INT_ARGB);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        var service = new ImageService(TerminalCapabilities.detect(java.util.Map.of("TERM", "xterm-kitty")));
        var prepared = service.prepare(output.toByteArray(), 60, 9, 18).orElseThrow();
        assertEquals(90, prepared.pixelWidth());
        assertEquals(36, prepared.pixelHeight());
        assertEquals(10, prepared.columns());
        assertEquals(2, prepared.rows());
        assertTrue(service.cachedPng(prepared.id()).isPresent());
        assertTrue(service.release(prepared.id()).contains("a=d"));
    }

    @Test void exifOrientationIsAppliedExplicitly() {
        byte[] jpeg = syntheticExifJpeg(6);
        assertEquals(6, ImageService.jpegOrientation(jpeg));
        var source = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        var rotated = ImageService.orient(source, 6);
        assertEquals(1, rotated.getWidth());
        assertEquals(2, rotated.getHeight());
        assertEquals(1, ImageService.jpegOrientation(new byte[]{(byte) 0xff, (byte) 0xd8}));
    }

    @Test void webpReaderProviderIsPackaged() {
        assertTrue(ImageIO.getImageReadersByFormatName("webp").hasNext());
        assertTrue(ImageIO.getImageReadersByFormatName("jpeg").hasNext());
    }

    private static byte[] syntheticExifJpeg(int orientation) {
        var out = new ByteArrayOutputStream();
        out.write(0xff); out.write(0xd8);
        out.write(0xff); out.write(0xe1);
        int tiffLength = 8 + 2 + 12 + 4;
        int length = 2 + 6 + tiffLength;
        out.write((length >> 8) & 0xff); out.write(length & 0xff);
        out.writeBytes("Exif".getBytes()); out.write(0); out.write(0);
        out.write('I'); out.write('I'); out.write(0x2a); out.write(0); out.write(8); out.write(0); out.write(0); out.write(0);
        out.write(1); out.write(0);
        out.write(0x12); out.write(0x01); out.write(3); out.write(0); out.write(1); out.write(0); out.write(0); out.write(0);
        out.write(orientation); out.write(0); out.write(0); out.write(0);
        out.write(0); out.write(0); out.write(0); out.write(0);
        return out.toByteArray();
    }
}
