package com.yowits.banbu.ai.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yowits.banbu.ai.api.dto.ChatMessage;
import com.yowits.banbu.ai.api.dto.ChatRequest;
import com.yowits.banbu.ai.config.AiRoutingProperties;
import com.yowits.banbu.ai.router.ModelRouter;
import com.yowits.banbu.ai.service.AiChatService;
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
            throw new UnsupportedOperationException("not used in this unit test");
        }
        @Override
        public Flux<String> chatStream(ChatRequest req) {
            return Flux.just("hello", "world");
        }
    }

    @Test
    void chat_withStreamFlag_returnsBadRequest() {
        ChatController controller = new ChatController(new StubAiChatService(), new ObjectMapper());
        WebTestClient client = WebTestClient.bindToController(controller).build();

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
            .expectBody(String.class)
            .value(body -> org.assertj.core.api.Assertions.assertThat(body)
                    .contains("Use /ai/chat/stream"));
    }

    @Test
    void chatStream_returnsSSE() {
        ChatController controller = new ChatController(new StubAiChatService(), new ObjectMapper());
        WebTestClient client = WebTestClient.bindToController(controller).build();

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
}
