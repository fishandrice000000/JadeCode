package com.jadecode.cli.backend.protocol;

/**
 * 带错误类别的协议异常。
 *
 * <p>它只描述“这条消息/这次命令为什么不能继续”，不包含用户秘密。调用方决定是可恢复的
 * 命令错误（回 {@code command.error} 并继续读）还是连接级错误（回错误后关闭 socket）。</p>
 */
public final class ProtocolException extends Exception {
    private final ProtocolError error;

    public ProtocolException(ProtocolError error, String message) { this(error, message, null); }

    public ProtocolException(ProtocolError error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
    }

    /** @return 稳定的协议错误类别，Node 用它区分可重试与致命错误。 */
    public ProtocolError error() { return error; }
}
