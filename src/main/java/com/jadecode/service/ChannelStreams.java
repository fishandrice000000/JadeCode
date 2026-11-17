package com.jadecode.service;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * UDS 的独立读写适配，避免 Channels 流适配器的 blockingLock 阻塞双向通信。
 */
public final class ChannelStreams {
    /**
     * 汇集 Unix socket 的输入流和输出流适配方法。
     */
    private ChannelStreams() {
    }

    /**
     * 仅读取给定连接，不隐式关闭或访问其他连接。
     *
     * @param channel 正文 REPLY 或思考 THINKING 通道
     * @return 直接读取该 socket 的独立输入流适配器
     */
    public static InputStream input(SocketChannel channel) {
        return new InputStream() {
            /**
             * 从连接读取一个字节；流结束返回 -1，正常字节转为 0–255 的无符号整数。连接读取失败抛出 IOException。
             *
             * @return 0–255 的单字节值；流结束时为 -1
             * @throws IOException 底层 Unix socket 读取失败
             */
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
            }

            /**
             * 把给定数组区间包装为字节缓冲，直接从 socket 读取并返回实际字节数；零长度区间返回 0，流结束返回 -1。
             * 数据写入调用方数组，读取失败抛出 IOException。
             *
             * @param bytes 需要解码、校验或计算哈希的完整字节数组
             * @param off 调用方数组中本次读写的起始字节下标
             * @param len 本次允许读写的字节数量
             * @return 实际读入数组的字节数；流结束时为 -1，len 为 0 时为 0
             * @throws IOException 底层 Unix socket 读取失败
             */
            @Override
            public int read(byte[] bytes, int off, int len) throws IOException {
                if (len == 0)
                    return 0;
                return channel.read(ByteBuffer.wrap(bytes, off, len));
            }
        };
    }

    /**
     * 独立写方向保证单次完整发送；上层负责单写者和背压。
     *
     * @param channel 正文 REPLY 或思考 THINKING 通道
     * @return 把数组区间完整写到该 socket 的独立输出流适配器
     */
    public static OutputStream output(SocketChannel channel) {
        return new OutputStream() {
            /**
             * 将整数的低八位作为单个字节交给数组写入口，写到当前连接。连接写入失败抛出 IOException。
             *
             * @param value 要写入的整数，实际发送其低八位
             * @throws IOException 底层 Unix socket 写入失败
             */
            @Override
            public void write(int value) throws IOException {
                write(new byte[] { (byte) value });
            }

            /**
             * 把给定数组区间包装为字节缓冲，循环写 socket 直到该区间全部发送。写入失败以 IOException 向上传递，
             * 由协议层结束连接。
             *
             * @param bytes 需要解码、校验或计算哈希的完整字节数组
             * @param off 调用方数组中本次读写的起始字节下标
             * @param len 本次允许读写的字节数量
             * @throws IOException 底层 Unix socket 写入失败
             */
            @Override
            public void write(byte[] bytes, int off, int len) throws IOException {
                var buffer = ByteBuffer.wrap(bytes, off, len);
                while (buffer.hasRemaining())
                    channel.write(buffer);
            }
        };
    }
}
