package com.jadecode.llm;

/**
 * LlmException Llm 调用相关的异常
 */
public class LlmException extends Exception {
    /** HTTP 状态码;0 = 无状态码(网络类异常) */
    private final int statusCode;

    private static final int DEFAULT_STATUS_CODE = 0;

    /** 无状态码(网络类异常):statusCode 归 0 */
    public LlmException(String message) {
        this(message, DEFAULT_STATUS_CODE);
    }

    /**
     * @param message    错误描述
     * @param statusCode HTTP 状态码;0 表示无状态码
     */
    public LlmException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    /**
     * @param message    错误描述
     * @param statusCode HTTP 状态码;0 表示无状态码
     * @param cause      原始异常(SDK 侧)
     */
    public LlmException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** 带原因、无状态码(网络类异常) */
    public LlmException(String message, Throwable cause) {
        this(message, DEFAULT_STATUS_CODE, cause);
    }

    /** @return HTTP 状态码;0 表示无状态码(网络类异常) */
    public int statusCode() {
        return statusCode;
    }
}
