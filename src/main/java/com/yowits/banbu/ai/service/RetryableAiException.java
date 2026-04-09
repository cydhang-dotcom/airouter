package com.yowits.banbu.ai.service;

public class RetryableAiException extends RuntimeException {
    private final int statusCode;

    public RetryableAiException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
