package com.yowits.banbu.ai.service;

public class TenantRateLimitExceededException extends RuntimeException {
    public TenantRateLimitExceededException(String message) {
        super(message);
    }
}
