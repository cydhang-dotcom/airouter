package com.yowits.banbu.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yowits.banbu.ai.api.dto.ChatMessage;
import com.yowits.banbu.ai.api.dto.ChatRequest;
import com.yowits.banbu.ai.config.AiPolicyProperties;
import com.yowits.banbu.ai.config.AiRoutingProperties;
import com.yowits.banbu.ai.config.ProviderRegistry;
import com.yowits.banbu.ai.router.ModelRouter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AiChatServiceOptionsTest {

    @Test
    void kimi25StripsTemperatureAndTopPButKeepsMaxTokens() {
        AtomicReference<OpenAiChatOptions> captured = new AtomicReference<>();
        AiChatService service = serviceForModel("kimi-main", "kimi-k2.5", captured);

        ChatRequest req = baseRequest(Map.of(
                "temperature", 0.7,
                "topP", 0.8,
                "maxTokens", 256
        ));

        service.chat(req).join();

        OpenAiChatOptions options = captured.get();
        assertThat(options).isNotNull();
        assertThat(options.getModel()).isEqualTo("kimi-k2.5");
        assertThat(options.getMaxTokens()).isEqualTo(256);
        assertThat(options.getTemperature()).isNull();
        assertThat(options.getTopP()).isNull();
    }

    @Test
    void nonKimiModelKeepsTemperatureAndTopP() {
        AtomicReference<OpenAiChatOptions> captured = new AtomicReference<>();
        AiChatService service = serviceForModel("glm-main", "glm-4", captured);

        ChatRequest req = baseRequest(Map.of(
                "temperature", 0.4,
                "topP", 0.9,
                "maxTokens", 128
        ));

        service.chat(req).join();

        OpenAiChatOptions options = captured.get();
        assertThat(options).isNotNull();
        assertThat(options.getModel()).isEqualTo("glm-4");
        assertThat(options.getMaxTokens()).isEqualTo(128);
        assertThat(options.getTemperature()).isEqualTo(0.4f);
        assertThat(options.getTopP()).isEqualTo(0.9f);
    }

    private static AiChatService serviceForModel(String alias, String model, AtomicReference<OpenAiChatOptions> captured) {
        AiRoutingProperties routing = new AiRoutingProperties();
        routing.setRoutes(Map.of("S", alias + ":" + model));
        routing.setChains(Map.of("S", List.of(alias + ":" + model)));

        ProviderRegistry registry = new ProviderRegistry(
                Map.of(alias, capturingClient(captured)),
                Map.of(alias, "openai-compat")
        );

        return new AiChatService(registry, new ModelRouter(routing), new ObjectMapper(), new AiPolicyProperties());
    }

    private static ChatRequest baseRequest(Map<String, Object> options) {
        ChatRequest req = new ChatRequest();
        req.setScene("S");
        req.setTenantId("t");
        req.setUserId("u");
        req.setMessages(List.of(new ChatMessage("user", "hello")));
        req.setOptions(options);
        return req;
    }

    private static ChatClient capturingClient(AtomicReference<OpenAiChatOptions> captured) {
        ChatClient.CallResponseSpec callSpec = proxy(ChatClient.CallResponseSpec.class, (method, args) -> {
            if ("chatResponse".equals(method.getName())) {
                return new ChatResponse(
                        List.of(),
                        ChatResponseMetadata.builder().withModel("stub-model").build()
                );
            }
            return unsupported(method.getName());
        });
        ChatClient.ChatClientRequestSpec requestSpec = proxy(ChatClient.ChatClientRequestSpec.class, (method, args) -> {
            return switch (method.getName()) {
                case "options" -> {
                    captured.set((OpenAiChatOptions) args[0]);
                    yield null;
                }
                case "system", "user", "messages", "advisors", "functions", "function" -> null;
                case "call" -> callSpec;
                default -> unsupported(method.getName());
            };
        });
        return proxy(ChatClient.class, (method, args) -> {
            if ("prompt".equals(method.getName())) {
                return requestSpec;
            }
            return unsupported(method.getName());
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
