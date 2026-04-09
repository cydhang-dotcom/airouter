package com.yowits.banbu.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * AI 调用错误分类器
 * 区分可重试错误和不可重试错误，避免无效重试
 */
public class AiErrorClassifier {
    private static final Logger log = LoggerFactory.getLogger(AiErrorClassifier.class);
    private static final String ENGINE_OVERLOADED_TYPE = "engine_overloaded_error";

    /**
     * 不可重试的 HTTP 状态码（4xx 客户端错误，除 429）
     */
    private static final Set<Integer> NON_RETRYABLE_STATUS_CODES = Set.of(
            400,  // Bad Request - 请求参数错误，重试也不会改变
            401,  // Unauthorized - 认证失败，重试也无效
            403,  // Forbidden - 权限不足，重试也无效
            404,  // Not Found - 资源不存在，重试无效
            405,  // Method Not Allowed
            408,  // Request Timeout
            411,  // Length Required
            412,  // Precondition Failed
            413,  // Payload Too Large
            414,  // URI Too Long
            415,  // Unsupported Media Type
            416,  // Range Not Satisfiable
            417,  // Expectation Failed
            422,  // Unprocessable Entity - 请求格式错误
            426,  // Upgrade Required
            428,  // Precondition Required
            431,  // Request Header Fields Too Large
            451    // Unavailable For Legal Reasons
    );

    /**
     * 可重试的 HTTP 状态码（5xx + 429）
     */
    private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(
            429,  // Too Many Requests - 限流，稍后重试可能成功
            500,  // Internal Server Error - 服务器临时错误
            502,  // Bad Gateway
            503,  // Service Unavailable
            504   // Gateway Timeout
    );

    /**
     * 判断异常是否可重试
     *
     * @param throwable 异常
     * @return true-可重试，false-不可重试
     */
    public static boolean isRetryable(Throwable throwable) {
        if (throwable == null) {
            return true; // 默认可重试
        }

        if (throwable instanceof RetryableAiException) {
            return true;
        }

        // 如果是 NonRetryableException，直接返回 false
        if (throwable instanceof NonRetryableException) {
            log.debug("NonRetryableException detected: {}", throwable.getMessage());
            return false;
        }

        // 尝试从异常中提取 HTTP 状态码
        Integer statusCode = extractStatusCode(throwable);
        if (statusCode != null) {
            boolean retryable = isRetryableStatusCode(statusCode);
            log.debug("HTTP status code {} -> retryable: {}", statusCode, retryable);
            return retryable;
        }

        // 根据异常类型判断
        String exceptionName = throwable.getClass().getSimpleName().toLowerCase();
        
        // 超时类错误可重试
        if (exceptionName.contains("timeout") || exceptionName.contains("timedout")) {
            log.debug("Timeout exception detected: {}", throwable.getClass().getSimpleName());
            return true;
        }

        // 网络连接类错误可重试
        if (exceptionName.contains("connection") || exceptionName.contains("network") 
                || exceptionName.contains("socket") || exceptionName.contains("host")) {
            log.debug("Network exception detected: {}", throwable.getClass().getSimpleName());
            return true;
        }

        // 未知异常，默认可重试（保守策略）
        log.warn("Unknown exception type: {}, defaulting to retryable", throwable.getClass().getName());
        return true;
    }

    /**
     * 判断 HTTP 状态码是否可重试
     */
    public static boolean isRetryableStatusCode(int statusCode) {
        // 429 Too Many Requests - 可重试（限流）
        // 5xx Server Errors - 可重试
        if (RETRYABLE_STATUS_CODES.contains(statusCode)) {
            return true;
        }
        // 4xx Client Errors - 不可重试
        if (NON_RETRYABLE_STATUS_CODES.contains(statusCode)) {
            return false;
        }
        // 其他情况默认不可重试
        return false;
    }

    /**
     * 从异常中提取 HTTP 状态码
     */
    private static Integer extractStatusCode(Throwable throwable) {
        // 遍历异常链
        Throwable current = throwable;
        while (current != null) {
            // 尝试从异常消息中提取状态码
            String message = current.getMessage();
            if (message != null) {
                // 常见格式: "429 Too Many Requests", "401 Unauthorized", "APIError: 400"
                for (int code : NON_RETRYABLE_STATUS_CODES) {
                    if (message.contains(" " + code + " ") || message.contains(code + " ")) {
                        return code;
                    }
                }
                for (int code : RETRYABLE_STATUS_CODES) {
                    if (message.contains(" " + code + " ") || message.contains(code + " ")) {
                        return code;
                    }
                }
                // 尝试直接匹配三位数状态码
                if (message.matches(".*\\b4\\d{2}\\b.*")) {
                    // 提取 4xx 状态码
                    java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\b(4\\d{2})\\b");
                    java.util.regex.Matcher m = p.matcher(message);
                    if (m.find()) {
                        return Integer.parseInt(m.group(1));
                    }
                }
                if (message.matches(".*\\b5\\d{2}\\b.*")) {
                    // 提取 5xx 状态码
                    java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\b(5\\d{2})\\b");
                    java.util.regex.Matcher m = p.matcher(message);
                    if (m.find()) {
                        return Integer.parseInt(m.group(1));
                    }
                }
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * 根据异常创建适当的异常类型
     * 用于包装原始异常，添加重试判断逻辑
     *
     * @param original 原始异常
     * @return 包装后的异常，如果是不可重试的错误返回 NonRetryableException
     */
    public static Throwable classify(Throwable original) {
        if (original == null) {
            return null;
        }

        Integer statusCode = extractStatusCode(original);
        if (statusCode != null && isRetryableStatusCode(statusCode)) {
            return new RetryableAiException(statusCode, original.getMessage(), original);
        }
        if (statusCode != null && !isRetryableStatusCode(statusCode)) {
            return new NonRetryableException(statusCode, original.getMessage(), original);
        }

        return original;
    }

    public static boolean isEngineOverloaded(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains(ENGINE_OVERLOADED_TYPE)
                        || (lower.contains("overloaded") && extractStatusCode(current) != null && extractStatusCode(current) == 429)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    public static Integer statusCodeOf(Throwable throwable) {
        return extractStatusCode(throwable);
    }
}
