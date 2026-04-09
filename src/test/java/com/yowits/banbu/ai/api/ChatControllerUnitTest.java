package com.yowits.banbu.ai.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yowits.banbu.ai.config.AiGuardProperties;
import com.yowits.banbu.ai.api.dto.ChatMessage;
import com.yowits.banbu.ai.api.dto.ChatRequest;
import com.yowits.banbu.ai.config.AiRoutingProperties;
import com.yowits.banbu.ai.router.ModelRouter;
import com.yowits.banbu.ai.service.AiChatService;
import com.yowits.banbu.ai.service.TenantGuardService;
import com.yowits.banbu.ai.service.UpstreamServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CompletableFuture;

class ChatControllerUnitTest {

    static class StubAiChatService extends AiChatService {
        public StubAiChatService() {
            super(new com.yowits.banbu.ai.config.ProviderRegistry(java.util.Map.of(), java.util.Map.of()), new ModelRouter(new AiRoutingProperties()), new ObjectMapper(), new com.yowits.banbu.ai.config.AiPolicyProperties());
        }
        @Override
        public CompletableFuture<org.springframework.ai.chat.model.ChatResponse> chat(ChatRequest req) {
            var metadata = org.springframework.ai.chat.metadata.ChatResponseMetadata.builder().withModel("stub-model").build();
            var response = new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("stub-text"))), metadata);
            return CompletableFuture.completedFuture(response);
        }
        @Override
        public Flux<String> chatStream(ChatRequest req) {
            return Flux.just("hello", "world");
        }
    }

    @Test
    void chat_withStreamFlag_returnsBadRequest() {
        ChatController controller = new ChatController(new StubAiChatService(), new ObjectMapper(), new TenantGuardService(new AiGuardProperties()));
        WebTestClient client = WebTestClient.bindToController(controller).controllerAdvice(new GlobalExceptionHandler()).build();

        ChatRequest req = new ChatRequest();
        req.setScene("CONTRACT_SUMMARY");
        req.setTenantId("t001");
        req.setUserId("u123");
        req.setMessages(List.of(new ChatMessage("user", "hello")));
        req.setStream(true);

        client.post().uri("/ai/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
            .jsonPath("$.message").value(body -> org.assertj.core.api.Assertions.assertThat(body.toString())
                    .contains("Use /ai/chat/stream"));
    }

    @Test
    void chatStream_returnsSSE() {
        ChatController controller = new ChatController(new StubAiChatService(), new ObjectMapper(), new TenantGuardService(new AiGuardProperties()));
        WebTestClient client = WebTestClient.bindToController(controller).controllerAdvice(new GlobalExceptionHandler()).build();

        ChatRequest req = new ChatRequest();
        req.setScene("CONTRACT_SUMMARY");
        req.setTenantId("t001");
        req.setUserId("u123");
        req.setMessages(List.of(new ChatMessage("user", "hi")));
        req.setStream(true);

        client.post().uri("/ai/chat/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(req)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .expectBody(String.class)
            .value(body -> {
                org.assertj.core.api.Assertions.assertThat(body).contains("event:message");
                org.assertj.core.api.Assertions.assertThat(body).contains("data:hello");
                org.assertj.core.api.Assertions.assertThat(body).contains("data:world");
                org.assertj.core.api.Assertions.assertThat(body).contains("event:done");
            });
    }

    @Test
    void chat_whenTenantHitsRateLimit_returnsTooManyRequests() {
        AiGuardProperties props = new AiGuardProperties();
        props.setDefaultRequestsPerMinute(1);
        ChatController controller = new ChatController(new StubAiChatService(), new ObjectMapper(), new TenantGuardService(props));
        WebTestClient client = WebTestClient.bindToController(controller).controllerAdvice(new GlobalExceptionHandler()).build();

        ChatRequest req = new ChatRequest();
        req.setScene("CONTRACT_SUMMARY");
        req.setTenantId("t001");
        req.setUserId("u123");
        req.setMessages(List.of(new ChatMessage("user", "hello")));

        client.post().uri("/ai/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isOk();

        client.post().uri("/ai/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isEqualTo(429)
            .expectBody()
            .jsonPath("$.code").isEqualTo("AI_RATE_LIMITED");
    }

    @Test
    void chat_whenProviderIsOverloaded_returnsWrappedServiceUnavailable() {
        AiChatService service = new StubAiChatService() {
            @Override
            public CompletableFuture<org.springframework.ai.chat.model.ChatResponse> chat(ChatRequest req) {
                CompletableFuture<org.springframework.ai.chat.model.ChatResponse> future = new CompletableFuture<>();
                future.completeExceptionally(new UpstreamServiceException(
                        503,
                        "AI_PROVIDER_OVERLOADED",
                        "Upstream AI provider is overloaded, please retry later",
                        new RuntimeException("429 engine_overloaded_error")
                ));
                return future;
            }
        };
        ChatController controller = new ChatController(service, new ObjectMapper(), new TenantGuardService(new AiGuardProperties()));
        WebTestClient client = WebTestClient.bindToController(controller).controllerAdvice(new GlobalExceptionHandler()).build();

        ChatRequest req = new ChatRequest();
        req.setScene("CONTRACT_SUMMARY");
        req.setTenantId("t001");
        req.setUserId("u123");
        req.setMessages(List.of(new ChatMessage("user", "hello")));

        client.post().uri("/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.code").isEqualTo("AI_PROVIDER_OVERLOADED")
                .jsonPath("$.message").isEqualTo("Upstream AI provider is overloaded, please retry later");
    }
}
