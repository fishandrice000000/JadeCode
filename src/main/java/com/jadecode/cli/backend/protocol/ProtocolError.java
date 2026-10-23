package com.jadecode.cli.backend.protocol;

/** 协议层可报告的错误类别；载荷/关联错误不执行任何业务，连接级错误会关闭连接。 */
public enum ProtocolError {
    /** 帧不是合法 JSON、缺少必填字段或字段类型错误。 */
    INVALID_MESSAGE(false),
    /** 协议版本不一致，当前前端不能与这个后端配对。 */
    VERSION_MISMATCH(false),
    /** 构建身份不一致（JAR 与前端不是同一次成套构建）。 */
    BUILD_MISMATCH(false),
    /** 握手凭据错误或缺失。 */
    UNAUTHORIZED(false),
    /** 快照尚未 ready，此时不接受会话命令。 */
    NOT_READY(true),
    /** 命令带了旧会话/旧代次身份。 */
    STALE_GENERATION(true),
    /** 设置/权限请求基于旧 revision。 */
    STALE_REVISION(true),
    /** 同一 commandId 或 seq 重复到达。 */
    DUPLICATE(true),
    /** 会话正在切换或队列不可用。 */
    BUSY(true),
    /** 该能力在本次装配中不可用（例如模型不支持该思考等级）。 */
    UNSUPPORTED(true),
    /** 单个逻辑载荷超过限额。 */
    PAYLOAD_TOO_LARGE(true),
    /** 后端输出积压超过预算，连接将收口。 */
    OVERLOADED(false),
    /** 会话日志或配置写入失败。 */
    PERSISTENCE_FAILED(true),
    /** 连接已经关闭。 */
    CLOSED(false);

    private final boolean recoverable;

    ProtocolError(boolean recoverable) { this.recoverable = recoverable; }

    /** @return true 表示 Node 可以继续使用同一连接并展示可重试错误。 */
    public boolean recoverable() { return recoverable; }
}
