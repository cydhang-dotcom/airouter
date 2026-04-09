package com.yowits.banbu.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yowits.banbu.ai.api.dto.ChatMessage;
import com.yowits.banbu.ai.api.dto.ChatRequest;
import com.yowits.banbu.ai.config.AiPolicyProperties;
import com.yowits.banbu.ai.config.ProviderRegistry;
import com.yowits.banbu.ai.config.AiRoutingProperties;
import com.yowits.banbu.ai.router.ModelRouter;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiChatServiceFallbackTest {

    @Test
    void nonStreaming_fallbacks_to_second_route_when_first_fails() {
        // Routing: primary a1:m1, fallback a2:m2
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

    private static ChatClient.ChatClientRequestSpec requestSpec(ChatClient.CallResponseSpec callSpec) {
        return proxy(ChatClient.ChatClientRequestSpec.class, (method, args) -> {
            return switch (method.getName()) {
                case "options", "system", "user", "messages", "advisors", "functions", "function" -> null;
                case "call" -> callSpec;
                default -> unsupported(method.getName());
            };
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
