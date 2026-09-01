package com.jadecode.llm;

/**
 * LlmException Llm 调用相关的异常
 */
public class LlmException extends Exception {
    // Http 状态码
    private final int statusCode;

    private static final int DEFAULT_STATUS_CODE = 0;

    public LlmException(String message) {
        this(message, DEFAULT_STATUS_CODE);
    }

    public LlmException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public LlmException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
