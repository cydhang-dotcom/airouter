package com.yowits.banbu.ai.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yowits.banbu.ai.api.dto.ChatMessage;
import com.yowits.banbu.ai.api.dto.ChatRequest;
import com.yowits.banbu.ai.config.AiPolicyProperties;
import com.yowits.banbu.ai.config.AiRoutingProperties;
import com.yowits.banbu.ai.config.ProviderRegistry;
import com.yowits.banbu.ai.router.ModelRouter;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiChatServiceFallbackTest {

    @Test
    void nonStreaming_fallbacks_to_second_route_when_first_fails() {
        AiRoutingProperties rp = new AiRoutingProperties();
        rp.setRoutes(Map.of("S", "a1:m1"));
        rp.setChains(Map.of("S", List.of("a1:m1", "a2:m2")));
        ModelRouter router = new ModelRouter(rp);

        ChatResponse ok = new ChatResponse(java.util.List.of());
        AtomicInteger secondRouteCalls = new AtomicInteger();
        ChatClient client1 = failingClient();
        ChatClient client2 = successClient(ok, secondRouteCalls);

        ProviderRegistry registry = new ProviderRegistry(
                Map.of("a1", client1, "a2", client2),
                Map.of("a1", "openai-compat", "a2", "openai-compat")
        );

        AiPolicyProperties policy = new AiPolicyProperties();
        AiChatService svc = new AiChatService(registry, router, new ObjectMapper(), policy);

        ChatRequest req = new ChatRequest();
        req.setScene("S");
        req.setTenantId("t");
        req.setUserId("u");
        req.setMessages(List.of(new ChatMessage("user", "hi")));
        req.setStream(false);

        ChatResponse res = CompletableFuture.supplyAsync(() -> svc.chat(req).join()).join();
        assertThat(res).isNotNull();
        assertThat(secondRouteCalls.get()).isEqualTo(1);
    }

    @Test
    void nonStreaming_logs_usage_when_provider_returns_it() {
        AiRoutingProperties rp = new AiRoutingProperties();
        rp.setRoutes(Map.of("S", "a1:m1"));
        rp.setChains(Map.of("S", List.of("a1:m1")));
        ModelRouter router = new ModelRouter(rp);

        ChatResponse ok = new ChatResponse(
                java.util.List.of(),
                ChatResponseMetadata.builder()
                        .withModel("m1")
                        .withUsage(new Usage() {
                            @Override
                            public Long getPromptTokens() {
                                return 11L;
                            }

                            @Override
                            public Long getGenerationTokens() {
                                return 7L;
                            }

                            @Override
                            public Long getTotalTokens() {
                                return 18L;
                            }
                        })
                        .build()
        );
        ChatClient client = successClient(ok, new AtomicInteger());

        ProviderRegistry registry = new ProviderRegistry(
                Map.of("a1", client),
                Map.of("a1", "openai-compat")
        );

        AiPolicyProperties policy = new AiPolicyProperties();
        AiChatService svc = new AiChatService(registry, router, new ObjectMapper(), policy);

        ChatRequest req = new ChatRequest();
        req.setScene("S");
        req.setTenantId("tenant-1");
        req.setUserId("user-1");
        req.setMessages(List.of(new ChatMessage("user", "hi")));
        req.setStream(false);

        Logger logger = (Logger) LoggerFactory.getLogger(AiChatService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            svc.chat(req).join();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("promptTokens=11")
                        && message.contains("generationTokens=7")
                        && message.contains("totalTokens=18"));
    }

    @Test
    void nonStreaming_retries_same_route_when_provider_is_overloaded() {
        AiRoutingProperties rp = new AiRoutingProperties();
        rp.setRoutes(Map.of("S", "a1:m1"));
        rp.setChains(Map.of("S", List.of("a1:m1")));
        ModelRouter router = new ModelRouter(rp);

        AtomicInteger calls = new AtomicInteger();
        ChatResponse ok = new ChatResponse(java.util.List.of());
        ChatClient client = failTwiceThenSucceedClient(ok, calls);

        ProviderRegistry registry = new ProviderRegistry(
                Map.of("a1", client),
                Map.of("a1", "openai-compat")
        );

        AiPolicyProperties policy = new AiPolicyProperties();
        policy.getDefaultPolicy().setPerRouteMaxAttempts(3);
        AiChatService svc = new AiChatService(registry, router, new ObjectMapper(), policy);

        ChatRequest req = new ChatRequest();
        req.setScene("S");
        req.setTenantId("t");
        req.setUserId("u");
        req.setMessages(List.of(new ChatMessage("user", "hi")));
        req.setStream(false);

        ChatResponse res = svc.chat(req).join();
        assertThat(res).isNotNull();
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void nonStreaming_fastestWins_returns_faster_backup_without_waiting_for_primary_failure() throws Exception {
        AiRoutingProperties rp = new AiRoutingProperties();
        rp.setRoutes(Map.of("S", "a1:m1"));
        rp.setChains(Map.of("S", List.of("a1:m1", "a2:m2")));
        ModelRouter router = new ModelRouter(rp);

        ChatResponse slow = responseWithModel("m1");
        ChatResponse fast = responseWithModel("m2");
        CountDownLatch slowStarted = new CountDownLatch(1);
        ChatClient client1 = delayedSuccessClient(slow, 200, slowStarted);
        ChatClient client2 = delayedSuccessClient(fast, 20, new CountDownLatch(0));

        ProviderRegistry registry = new ProviderRegistry(
                Map.of("a1", client1, "a2", client2),
                Map.of("a1", "openai-compat", "a2", "openai-compat")
        );

        AiPolicyProperties policy = new AiPolicyProperties();
        policy.getDefaultPolicy().setRoutingMode(AiPolicyProperties.Policy.ROUTING_MODE_FASTEST_WINS);
        policy.getDefaultPolicy().setRaceMaxCandidates(2);
        AiChatService svc = new AiChatService(registry, router, new ObjectMapper(), policy);

        ChatRequest req = new ChatRequest();
        req.setScene("S");
        req.setTenantId("t");
        req.setUserId("u");
        req.setMessages(List.of(new ChatMessage("user", "hi")));
        req.setStream(false);

        ChatResponse res = svc.chat(req).join();
        assertThat(slowStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(res.getMetadata().getModel()).isEqualTo("m2");
    }

    @Test
    void nonStreaming_fastestWins_falls_back_to_later_chain_when_race_candidates_fail() {
        AiRoutingProperties rp = new AiRoutingProperties();
        rp.setRoutes(Map.of("S", "a1:m1"));
        rp.setChains(Map.of("S", List.of("a1:m1", "a2:m2", "a3:m3")));
        ModelRouter router = new ModelRouter(rp);

        AtomicInteger thirdRouteCalls = new AtomicInteger();
        ProviderRegistry registry = new ProviderRegistry(
                Map.of("a1", failingClient(), "a2", failingClient(), "a3", successClient(responseWithModel("m3"), thirdRouteCalls)),
                Map.of("a1", "openai-compat", "a2", "openai-compat", "a3", "openai-compat")
        );

        AiPolicyProperties policy = new AiPolicyProperties();
        policy.getDefaultPolicy().setRoutingMode(AiPolicyProperties.Policy.ROUTING_MODE_FASTEST_WINS);
        policy.getDefaultPolicy().setRaceMaxCandidates(2);
        AiChatService svc = new AiChatService(registry, router, new ObjectMapper(), policy);

        ChatRequest req = new ChatRequest();
        req.setScene("S");
        req.setTenantId("t");
        req.setUserId("u");
        req.setMessages(List.of(new ChatMessage("user", "hi")));
        req.setStream(false);

        ChatResponse res = svc.chat(req).join();
        assertThat(res.getMetadata().getModel()).isEqualTo("m3");
        assertThat(thirdRouteCalls.get()).isEqualTo(1);
    }

    @Test
    void nonStreaming_fastestWins_doesNotUse_laterFallback_whenDisabled() {
        AiRoutingProperties rp = new AiRoutingProperties();
        rp.setRoutes(Map.of("S", "a1:m1"));
        rp.setChains(Map.of("S", List.of("a1:m1", "a2:m2", "a3:m3")));
        ModelRouter router = new ModelRouter(rp);

        AtomicInteger thirdRouteCalls = new AtomicInteger();
        ProviderRegistry registry = new ProviderRegistry(
                Map.of(
                        "a1", failingClient(),
                        "a2", failingClient(),
                        "a3", successClient(responseWithModel("m3"), thirdRouteCalls)
                ),
                Map.of("a1", "openai-compat", "a2", "openai-compat", "a3", "openai-compat")
        );

        AiPolicyProperties policy = new AiPolicyProperties();
        policy.getDefaultPolicy().setRoutingMode(AiPolicyProperties.Policy.ROUTING_MODE_FASTEST_WINS);
        policy.getDefaultPolicy().setRaceMaxCandidates(2);
        policy.getDefaultPolicy().setAllowFallback(false);
        AiChatService svc = new AiChatService(registry, router, new ObjectMapper(), policy);

        ChatRequest req = new ChatRequest();
        req.setScene("S");
        req.setTenantId("t");
        req.setUserId("u");
        req.setMessages(List.of(new ChatMessage("user", "hi")));
        req.setStream(false);

        assertThatThrownBy(() -> svc.chat(req).join())
                .hasCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("fail-first");
        assertThat(thirdRouteCalls.get()).isZero();
    }

    @Test
    void nonStreaming_fastestWins_respects_raceCandidateLimit() {
        AiRoutingProperties rp = new AiRoutingProperties();
        rp.setRoutes(Map.of("S", "a1:m1"));
        rp.setChains(Map.of("S", List.of("a1:m1", "a2:m2")));
        ModelRouter router = new ModelRouter(rp);

        AtomicInteger secondRouteCalls = new AtomicInteger();
        ProviderRegistry registry = new ProviderRegistry(
                Map.of(
                        "a1", delayedSuccessClient(responseWithModel("m1"), 50, new CountDownLatch(0)),
                        "a2", successClient(responseWithModel("m2"), secondRouteCalls)
                ),
                Map.of("a1", "openai-compat", "a2", "openai-compat")
        );

        AiPolicyProperties policy = new AiPolicyProperties();
        policy.getDefaultPolicy().setRoutingMode(AiPolicyProperties.Policy.ROUTING_MODE_FASTEST_WINS);
        policy.getDefaultPolicy().setRaceMaxCandidates(1);
        AiChatService svc = new AiChatService(registry, router, new ObjectMapper(), policy);

        ChatRequest req = new ChatRequest();
        req.setScene("S");
        req.setTenantId("t");
        req.setUserId("u");
        req.setMessages(List.of(new ChatMessage("user", "hi")));
        req.setStream(false);

        ChatResponse res = svc.chat(req).join();
        assertThat(res.getMetadata().getModel()).isEqualTo("m1");
        assertThat(secondRouteCalls.get()).isZero();
    }

    @Test
    void nonStreaming_fastestWins_retries_each_race_candidate_before_sequentialFallback() {
        AiRoutingProperties rp = new AiRoutingProperties();
        rp.setRoutes(Map.of("S", "a1:m1"));
        rp.setChains(Map.of("S", List.of("a1:m1", "a2:m2", "a3:m3")));
        ModelRouter router = new ModelRouter(rp);

        AtomicInteger firstRouteCalls = new AtomicInteger();
        AtomicInteger secondRouteCalls = new AtomicInteger();
        AtomicInteger thirdRouteCalls = new AtomicInteger();
        ProviderRegistry registry = new ProviderRegistry(
                Map.of(
                        "a1", alwaysOverloadedClient(firstRouteCalls),
                        "a2", alwaysOverloadedClient(secondRouteCalls),
                        "a3", successClient(responseWithModel("m3"), thirdRouteCalls)
                ),
                Map.of("a1", "openai-compat", "a2", "openai-compat", "a3", "openai-compat")
        );

        AiPolicyProperties policy = new AiPolicyProperties();
        policy.getDefaultPolicy().setRoutingMode(AiPolicyProperties.Policy.ROUTING_MODE_FASTEST_WINS);
        policy.getDefaultPolicy().setRaceMaxCandidates(2);
        policy.getDefaultPolicy().setPerRouteMaxAttempts(2);
        AiChatService svc = new AiChatService(registry, router, new ObjectMapper(), policy);

        ChatRequest req = new ChatRequest();
        req.setScene("S");
        req.setTenantId("t");
        req.setUserId("u");
        req.setMessages(List.of(new ChatMessage("user", "hi")));
        req.setStream(false);

        ChatResponse res = svc.chat(req).join();
        assertThat(res.getMetadata().getModel()).isEqualTo("m3");
        assertThat(firstRouteCalls.get()).isEqualTo(2);
        assertThat(secondRouteCalls.get()).isEqualTo(2);
        assertThat(thirdRouteCalls.get()).isEqualTo(1);
    }

    @Test
    void nonStreaming_wraps_provider_overloaded_after_retries_exhausted() {
        AiRoutingProperties rp = new AiRoutingProperties();
        rp.setRoutes(Map.of("S", "a1:m1"));
        rp.setChains(Map.of("S", List.of("a1:m1")));
        ModelRouter router = new ModelRouter(rp);

        AtomicInteger calls = new AtomicInteger();
        ChatClient client = alwaysOverloadedClient(calls);

        ProviderRegistry registry = new ProviderRegistry(
                Map.of("a1", client),
                Map.of("a1", "openai-compat")
        );

        AiPolicyProperties policy = new AiPolicyProperties();
        policy.getDefaultPolicy().setPerRouteMaxAttempts(2);
        AiChatService svc = new AiChatService(registry, router, new ObjectMapper(), policy);

        ChatRequest req = new ChatRequest();
        req.setScene("S");
        req.setTenantId("t");
        req.setUserId("u");
        req.setMessages(List.of(new ChatMessage("user", "hi")));
        req.setStream(false);

        assertThatThrownBy(() -> svc.chat(req).join())
                .hasCauseInstanceOf(UpstreamServiceException.class)
                .satisfies(ex -> {
                    UpstreamServiceException cause = (UpstreamServiceException) ex.getCause();
                    assertThat(cause.getStatusCode()).isEqualTo(503);
                    assertThat(cause.getErrorCode()).isEqualTo("AI_PROVIDER_OVERLOADED");
                });
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void nonStreaming_wraps_network_errors_after_retries_exhausted() {
        AiRoutingProperties rp = new AiRoutingProperties();
        rp.setRoutes(Map.of("S", "a1:m1"));
        rp.setChains(Map.of("S", List.of("a1:m1")));
        ModelRouter router = new ModelRouter(rp);

        AtomicInteger calls = new AtomicInteger();
        ChatClient client = alwaysFailingClient(calls, "Connection refused: upstream proxy failed");

        ProviderRegistry registry = new ProviderRegistry(
                Map.of("a1", client),
                Map.of("a1", "openai-compat")
        );

        AiPolicyProperties policy = new AiPolicyProperties();
        policy.getDefaultPolicy().setPerRouteMaxAttempts(2);
        AiChatService svc = new AiChatService(registry, router, new ObjectMapper(), policy);

        ChatRequest req = new ChatRequest();
        req.setScene("S");
        req.setTenantId("t");
        req.setUserId("u");
        req.setMessages(List.of(new ChatMessage("user", "hi")));
        req.setStream(false);

        assertThatThrownBy(() -> svc.chat(req).join())
                .hasCauseInstanceOf(UpstreamServiceException.class)
                .satisfies(ex -> {
                    UpstreamServiceException cause = (UpstreamServiceException) ex.getCause();
                    assertThat(cause.getStatusCode()).isEqualTo(502);
                    assertThat(cause.getErrorCode()).isEqualTo("AI_UPSTREAM_NETWORK_ERROR");
                });
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void nonStreaming_wraps_timeout_errors_after_retries_exhausted() {
        AiRoutingProperties rp = new AiRoutingProperties();
        rp.setRoutes(Map.of("S", "a1:m1"));
        rp.setChains(Map.of("S", List.of("a1:m1")));
        ModelRouter router = new ModelRouter(rp);

        AtomicInteger calls = new AtomicInteger();
        ChatClient client = alwaysFailingClient(calls, "Read timed out while calling upstream");

        ProviderRegistry registry = new ProviderRegistry(
                Map.of("a1", client),
                Map.of("a1", "openai-compat")
        );

        AiPolicyProperties policy = new AiPolicyProperties();
        policy.getDefaultPolicy().setPerRouteMaxAttempts(2);
        AiChatService svc = new AiChatService(registry, router, new ObjectMapper(), policy);

        ChatRequest req = new ChatRequest();
        req.setScene("S");
        req.setTenantId("t");
        req.setUserId("u");
        req.setMessages(List.of(new ChatMessage("user", "hi")));
        req.setStream(false);

        assertThatThrownBy(() -> svc.chat(req).join())
                .hasCauseInstanceOf(UpstreamServiceException.class)
                .satisfies(ex -> {
                    UpstreamServiceException cause = (UpstreamServiceException) ex.getCause();
                    assertThat(cause.getStatusCode()).isEqualTo(504);
                    assertThat(cause.getErrorCode()).isEqualTo("AI_UPSTREAM_TIMEOUT");
                });
        assertThat(calls.get()).isEqualTo(2);
    }

    private static ChatClient failingClient() {
        ChatClient.CallResponseSpec callSpec = proxy(ChatClient.CallResponseSpec.class, (method, args) -> {
            if ("chatResponse".equals(method.getName())) {
                throw new RuntimeException("fail-first");
            }
            return unsupported(method.getName());
        });
        ChatClient.ChatClientRequestSpec requestSpec = requestSpec(callSpec);
        return proxy(ChatClient.class, (method, args) -> {
            if ("prompt".equals(method.getName())) {
                return requestSpec;
            }
            return unsupported(method.getName());
        });
    }

    private static ChatClient successClient(ChatResponse response, AtomicInteger counter) {
        ChatClient.CallResponseSpec callSpec = proxy(ChatClient.CallResponseSpec.class, (method, args) -> {
            if ("chatResponse".equals(method.getName())) {
                counter.incrementAndGet();
                return response;
            }
            return unsupported(method.getName());
        });
        ChatClient.ChatClientRequestSpec requestSpec = requestSpec(callSpec);
        return proxy(ChatClient.class, (method, args) -> {
            if ("prompt".equals(method.getName())) {
                return requestSpec;
            }
            return unsupported(method.getName());
        });
    }

    private static ChatClient failTwiceThenSucceedClient(ChatResponse response, AtomicInteger counter) {
        ChatClient.CallResponseSpec callSpec = proxy(ChatClient.CallResponseSpec.class, (method, args) -> {
            if ("chatResponse".equals(method.getName())) {
                int current = counter.incrementAndGet();
                if (current <= 2) {
                    throw new RuntimeException("429 - {\"error\":{\"message\":\"The engine is currently overloaded, please try again later\",\"type\":\"engine_overloaded_error\"}}");
                }
                return response;
            }
            return unsupported(method.getName());
        });
        ChatClient.ChatClientRequestSpec requestSpec = requestSpec(callSpec);
        return proxy(ChatClient.class, (method, args) -> {
            if ("prompt".equals(method.getName())) {
                return requestSpec;
            }
            return unsupported(method.getName());
        });
    }

    private static ChatClient alwaysOverloadedClient(AtomicInteger counter) {
        ChatClient.CallResponseSpec callSpec = proxy(ChatClient.CallResponseSpec.class, (method, args) -> {
            if ("chatResponse".equals(method.getName())) {
                counter.incrementAndGet();
                throw new RuntimeException("429 - {\"error\":{\"message\":\"The engine is currently overloaded, please try again later\",\"type\":\"engine_overloaded_error\"}}");
            }
            return unsupported(method.getName());
        });
        ChatClient.ChatClientRequestSpec requestSpec = requestSpec(callSpec);
        return proxy(ChatClient.class, (method, args) -> {
            if ("prompt".equals(method.getName())) {
                return requestSpec;
            }
            return unsupported(method.getName());
        });
    }

    private static ChatClient alwaysFailingClient(AtomicInteger counter, String message) {
        ChatClient.CallResponseSpec callSpec = proxy(ChatClient.CallResponseSpec.class, (method, args) -> {
            if ("chatResponse".equals(method.getName())) {
                counter.incrementAndGet();
                throw new RuntimeException(message);
            }
            return unsupported(method.getName());
        });
        ChatClient.ChatClientRequestSpec requestSpec = requestSpec(callSpec);
        return proxy(ChatClient.class, (method, args) -> {
            if ("prompt".equals(method.getName())) {
                return requestSpec;
            }
            return unsupported(method.getName());
        });
    }

    private static ChatClient delayedSuccessClient(ChatResponse response, long delayMs, CountDownLatch started) {
        ChatClient.CallResponseSpec callSpec = proxy(ChatClient.CallResponseSpec.class, (method, args) -> {
            if ("chatResponse".equals(method.getName())) {
                started.countDown();
                Thread.sleep(delayMs);
                return response;
            }
            return unsupported(method.getName());
        });
        ChatClient.ChatClientRequestSpec requestSpec = requestSpec(callSpec);
        return proxy(ChatClient.class, (method, args) -> {
            if ("prompt".equals(method.getName())) {
                return requestSpec;
            }
            return unsupported(method.getName());
        });
    }

    private static ChatResponse responseWithModel(String model) {
        return new ChatResponse(
                java.util.List.of(),
                ChatResponseMetadata.builder().withModel(model).build()
        );
    }

    private static ChatClient.ChatClientRequestSpec requestSpec(ChatClient.CallResponseSpec callSpec) {
        return proxy(ChatClient.ChatClientRequestSpec.class, (method, args) -> switch (method.getName()) {
            case "options", "system", "user", "messages", "advisors", "functions", "function" -> null;
            case "call" -> callSpec;
            default -> unsupported(method.getName());
        });
    }

    private static Object unsupported(String method) {
        throw new UnsupportedOperationException("Unexpected method: " + method);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> type.getSimpleName() + "Proxy";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    Object result = invocation.invoke(method, args);
                    if (result == null && method.getReturnType().isInstance(proxy)) {
                        return proxy;
                    }
                    return result;
                }
        );
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }
}
