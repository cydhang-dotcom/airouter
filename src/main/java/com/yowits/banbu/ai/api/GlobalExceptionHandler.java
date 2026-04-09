package com.yowits.banbu.ai.api;

import com.yowits.banbu.ai.service.InvalidChatRequestException;
import com.yowits.banbu.ai.service.TenantRateLimitExceededException;
import com.yowits.banbu.ai.service.UpstreamServiceException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

import java.time.Instant;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TenantRateLimitExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleRateLimit(TenantRateLimitExceededException ex, ServerWebExchange exchange) {
        return build(exchange, HttpStatus.TOO_MANY_REQUESTS, "AI_RATE_LIMITED", ex.getMessage());
    }

    @ExceptionHandler(InvalidChatRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(InvalidChatRequestException ex, ServerWebExchange exchange) {
        return build(exchange, HttpStatus.BAD_REQUEST, "INVALID_REQUEST", ex.getMessage());
    }

    @ExceptionHandler(UpstreamServiceException.class)
    public ResponseEntity<ApiErrorResponse> handleUpstreamService(UpstreamServiceException ex, ServerWebExchange exchange) {
        return build(exchange, HttpStatus.valueOf(ex.getStatusCode()), ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, WebExchangeBindException.class})
    public ResponseEntity<ApiErrorResponse> handleBinding(Exception ex, ServerWebExchange exchange) {
        String message;
        if (ex instanceof MethodArgumentNotValidException manv) {
            message = validationMessage(manv.getBindingResult().getFieldErrors().stream()
                    .map(err -> err.getField() + " " + err.getDefaultMessage())
                    .collect(Collectors.toList()));
        } else {
            WebExchangeBindException bind = (WebExchangeBindException) ex;
            message = validationMessage(bind.getFieldErrors().stream()
                    .map(this::fieldMessage)
                    .collect(Collectors.toList()));
        }
        return build(exchange, HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    @ExceptionHandler({ServerWebInputException.class, ConstraintViolationException.class})
    public ResponseEntity<ApiErrorResponse> handleInput(Exception ex, ServerWebExchange exchange) {
        return build(exchange, HttpStatus.BAD_REQUEST, "INVALID_REQUEST", ex.getMessage());
    }

    @ExceptionHandler({CompletionException.class, ExecutionException.class})
    public ResponseEntity<ApiErrorResponse> handleAsyncWrapper(Exception ex, ServerWebExchange exchange) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        return handleThrowable(cause, exchange);
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ApiErrorResponse> handleThrowable(Throwable ex, ServerWebExchange exchange) {
        if (ex instanceof TenantRateLimitExceededException limit) {
            return handleRateLimit(limit, exchange);
        }
        if (ex instanceof InvalidChatRequestException invalid) {
            return handleInvalidRequest(invalid, exchange);
        }
        if (ex instanceof UpstreamServiceException upstream) {
            return handleUpstreamService(upstream, exchange);
        }
        if (ex instanceof TimeoutException) {
            return build(exchange, HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT", "AI request timed out");
        }
        if (ex instanceof IllegalStateException) {
            return build(exchange, HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", ex.getMessage());
        }
        return build(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "AI_INTERNAL_ERROR", ex.getMessage());
    }

    private ResponseEntity<ApiErrorResponse> build(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        String safeMessage = (message == null || message.isBlank()) ? status.getReasonPhrase() : message;
        ApiErrorResponse body = new ApiErrorResponse(
                Instant.now(),
                exchange.getRequest().getPath().value(),
                status.value(),
                status.getReasonPhrase(),
                code,
                safeMessage,
                exchange.getRequest().getId()
        );
        return ResponseEntity.status(status).body(body);
    }

    private String validationMessage(java.util.List<String> errors) {
        if (errors.isEmpty()) {
            return "Request validation failed";
        }
        return String.join("; ", errors);
    }

    private String fieldMessage(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }
}
