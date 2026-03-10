package com.yowits.banbu.ai.api;

import java.time.Instant;

public record ApiErrorResponse(
        Instant timestamp,
        String path,
        int status,
        String error,
        String code,
        String message,
        String requestId
) {
}
