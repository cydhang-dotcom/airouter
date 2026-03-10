package com.yowits.banbu.ai.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yowits.banbu.ai.AiServiceApplication;
import com.yowits.banbu.ai.api.ChatController;
import com.yowits.banbu.ai.api.dto.ChatMessage;
import com.yowits.banbu.ai.api.dto.ChatRequest;
import com.yowits.banbu.ai.service.AiChatService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@SpringBootTest(
    classes = {AiServiceApplication.class, ChatE2ETest.StubConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        // avoid real OpenAI autoconfig in tests
        "spring.autoconfigure.exclude=org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration"
    }
)
class ChatE2ETest {

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        AiChatService stubAiChatService() {
            return new AiChatService(new com.yowits.banbu.ai.config.ProviderRegistry(java.util.Map.of(), java.util.Map.of()), new com.yowits.banbu.ai.router.ModelRouter(new com.yowits.banbu.ai.config.AiRoutingProperties()), new ObjectMapper(), new com.yowits.banbu.ai.config.AiPolicyProperties()) {
                @Override
                public CompletableFuture<org.springframework.ai.chat.model.ChatResponse> chat(ChatRequest req) {
                    throw new UnsupportedOperationException("not used in this e2e");
                }

                @Override
                public Flux<String> chatStream(ChatRequest req) {
                    return Flux.just("token-1", "token-2");
                }
            };
        }

        @Bean
        @Primary
        org.springframework.ai.chat.model.ChatModel noopChatModel() {
            return org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        }
    }

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void health_up() {
        webTestClient.get().uri("/actuator/health")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void e2e_streaming_ok() {
        ChatRequest req = new ChatRequest();
        req.setScene("CONTRACT_SUMMARY");
        req.setTenantId("t001");
        req.setUserId("u123");
        req.setMessages(List.of(new ChatMessage("user", "hi")));
        req.setStream(true);

        webTestClient.post().uri("/ai/chat/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(req)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .expectBody(String.class)
            .value(body -> {
                org.assertj.core.api.Assertions.assertThat(body).contains("event:message");
                org.assertj.core.api.Assertions.assertThat(body).contains("data:token-1");
                org.assertj.core.api.Assertions.assertThat(body).contains("data:token-2");
                org.assertj.core.api.Assertions.assertThat(body).contains("event:done");
            });
    }

    @Test
    void e2e_streaming_with_schema_ok() {
        ChatRequest req = new ChatRequest();
        req.setScene("CONTRACT_SUMMARY");
        req.setTenantId("t001");
        req.setUserId("u123");
        req.setMessages(List.of(new ChatMessage("user", "hi")));
        req.setResponseFormat("json");
        java.util.Map<String,Object> schema = new java.util.HashMap<>();
        schema.put("type","object");
        req.setResponseSchema(schema);
        req.setStream(true);

        webTestClient.post().uri("/ai/chat/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(req)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .expectBody(String.class)
            .value(body -> {
                org.assertj.core.api.Assertions.assertThat(body).contains("event:message");
                org.assertj.core.api.Assertions.assertThat(body).contains("event:done");
            });
    }

    @Test
    void e2e_chat_rejects_stream_flag() {
        ChatRequest req = new ChatRequest();
        req.setScene("CONTRACT_SUMMARY");
        req.setTenantId("t001");
        req.setUserId("u123");
        req.setMessages(List.of(new ChatMessage("user", "hi")));
        req.setStream(true);

        webTestClient.post().uri("/ai/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isBadRequest();
    }
}
