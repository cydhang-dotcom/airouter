package com.yowits.banbu.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yowits.banbu.ai.api.dto.ChatMessage;
import com.yowits.banbu.ai.api.dto.ChatRequest;
import com.yowits.banbu.ai.config.AiPolicyProperties;
import com.yowits.banbu.ai.config.ProviderRegistry;
import com.yowits.banbu.ai.config.AiRoutingProperties;
import com.yowits.banbu.ai.router.ModelRouter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

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
