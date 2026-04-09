package com.yowits.banbu.ai.service;

public class UpstreamServiceException extends RuntimeException {
    private final int statusCode;
    private final String errorCode;

    public UpstreamServiceException(int statusCode, String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
