package com.yowits.banbu.ai.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiErrorClassifierTest {

    @Test
    void classify_marks429AsRetryableAnd400AsNonRetryable() {
        Throwable retryable = AiErrorClassifier.classify(
                new RuntimeException("429 - {\"error\":{\"message\":\"rate limited\"}}")
        );
        Throwable nonRetryable = AiErrorClassifier.classify(
                new RuntimeException("400 Bad Request")
        );

        assertThat(retryable).isInstanceOf(RetryableAiException.class);
        assertThat(nonRetryable).isInstanceOf(NonRetryableException.class);
        assertThat(AiErrorClassifier.isRetryable(retryable)).isTrue();
        assertThat(AiErrorClassifier.isRetryable(nonRetryable)).isFalse();
    }

    @Test
    void detectsEngineOverloadTimeoutAndNetworkSignals() {
        RuntimeException overloaded = new RuntimeException(
                "429 - {\"error\":{\"message\":\"The engine is currently overloaded, please try again later\",\"type\":\"engine_overloaded_error\"}}"
        );
        RuntimeException timeout = new RuntimeException("Read timed out while calling upstream");
        RuntimeException network = new RuntimeException("Connection refused: upstream proxy failed");

        assertThat(AiErrorClassifier.isEngineOverloaded(overloaded)).isTrue();
        assertThat(AiErrorClassifier.statusCodeOf(overloaded)).isEqualTo(429);
        assertThat(AiErrorClassifier.isTimeoutRelated(timeout)).isTrue();
        assertThat(AiErrorClassifier.isNetworkRelated(network)).isTrue();
    }

    @Test
    void retryableStatusCodeMatrixMatchesExpectedBehavior() {
        assertThat(AiErrorClassifier.isRetryableStatusCode(429)).isTrue();
        assertThat(AiErrorClassifier.isRetryableStatusCode(503)).isTrue();
        assertThat(AiErrorClassifier.isRetryableStatusCode(400)).isFalse();
        assertThat(AiErrorClassifier.isRetryableStatusCode(404)).isFalse();
    }
}
