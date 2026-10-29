package com.jadecode.service;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/** UDS 的独立读写适配，避免 Channels 流适配器的 blockingLock 阻塞双向通信。 */
public final class ChannelStreams {
    private ChannelStreams() { }
    /** 仅读取给定连接，不隐式关闭或访问其他连接。 */
    public static InputStream input(SocketChannel channel) {
        return new InputStream() {
            @Override public int read() throws IOException {
                byte[] one = new byte[1]; return read(one,0,1) < 0 ? -1 : one[0] & 255;
            }
            @Override public int read(byte[] bytes, int off, int len) throws IOException {
                if (len == 0) return 0;
                return channel.read(ByteBuffer.wrap(bytes,off,len));
            }
        };
    }
    /** 独立写方向保证单次完整发送；上层负责单写者和背压。 */
    public static OutputStream output(SocketChannel channel) {
        return new OutputStream() {
            @Override public void write(int value) throws IOException { write(new byte[]{(byte)value}); }
            @Override public void write(byte[] bytes, int off, int len) throws IOException {
                var buffer=ByteBuffer.wrap(bytes,off,len);
                while(buffer.hasRemaining()) channel.write(buffer);
            }
        };
    }
}
