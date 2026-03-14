package com.yowits.banbu.ai.service;

/**
 * 非可重试异常，用于标识不应重试的错误类型
 * 包括：400 参数错误、401 鉴权失败、403 权限不足、422 请求格式错误
 */
public class NonRetryableException extends RuntimeException {
    private final int statusCode;
    private final String errorType;

    public NonRetryableException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
        this.errorType = classifyError(statusCode);
    }

    public NonRetryableException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorType = classifyError(statusCode);
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorType() {
        return errorType;
    }

    private static String classifyError(int statusCode) {
        return switch (statusCode) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 422 -> "UNPROCESSABLE_ENTITY";
            default -> "CLIENT_ERROR";
        };
    }

    @Override
    public String toString() {
        return "NonRetryableException{" +
                "statusCode=" + statusCode +
                ", errorType='" + errorType + '\'' +
                ", message='" + getMessage() + '\'' +
                '}';
    }
}
