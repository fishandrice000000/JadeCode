package com.jadecode.tui.terminal;

/**
 * 终端读取线程交给 UI 的原始事件，不包含任何已解码动作。
 *
 * <p>读取线程只负责把字节块、尺寸变化、EOF 和故障按顺序发出；解码、粘贴边界、终端回复
 * 分流都在 UI 侧完成。这样读取线程不会操作组件、直接审批或写屏；只有 UI 线程拥有状态。
 * 每个事件带递增序号和 terminalEpoch，UI 可据此丢弃过期输入。</p>
 */
public sealed interface TerminalEvent permits TerminalEvent.InputChunk, TerminalEvent.Resize,
        TerminalEvent.Eof, TerminalEvent.Failure {
    /** @return 该终端事件的生产序号。 */
    long sequence();

    /** @return 产生事件时的终端代次；暂停/恢复或 resize 后递增。 */
    long terminalEpoch();

    /** 原始输入字节；数组在构造时复制，消费方不能再修改读取缓冲。 */
    record InputChunk(byte[] bytes, long sequence, long terminalEpoch) implements TerminalEvent {
        public InputChunk {
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    /** 终端尺寸变化；columns/rows 已按真实值提供，0 表示未知等待查询。 */
    record Resize(int columns, int rows, long sequence, long terminalEpoch) implements TerminalEvent { }

    /** 输入流结束（用户关闭/TERM/HUP）；只应产生一次。 */
    record Eof(long sequence, long terminalEpoch) implements TerminalEvent { }

    /** 读取或终端故障；消息用于诊断，不进入对话正文。 */
    record Failure(String message, long sequence, long terminalEpoch) implements TerminalEvent { }
}
