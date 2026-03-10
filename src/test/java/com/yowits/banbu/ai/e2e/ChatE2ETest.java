package com.yowits.banbu.ai.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yowits.banbu.ai.AiServiceApplication;
import com.yowits.banbu.ai.api.dto.ChatMessage;
import com.yowits.banbu.ai.api.dto.ChatRequest;
import com.yowits.banbu.ai.service.AiChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

@SpringBootTest(
    classes = {AiServiceApplication.class, ChatE2ETest.StubConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
        // avoid real OpenAI autoconfig in tests
        "spring.autoconfigure.exclude=org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration"
    }
)
@AutoConfigureWebTestClient
class ChatE2ETest {

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        StubAiChatService stubAiChatService() {
            return new StubAiChatService();
        }

        @Bean
        @Primary
        ChatModel noopChatModel() {
            return new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    return new ChatResponse(List.of());
                }

                @Override
                public ChatOptions getDefaultOptions() {
                    return null;
                }
            };
        }
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private StubAiChatService stubAiChatService;

    @BeforeEach
    void resetStub() {
        stubAiChatService.reset();
    }

    @Test
    void health_up() {
        webTestClient.get().uri("/actuator/health")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void e2e_chat_ok_returns_text_payload() {
        webTestClient.post().uri("/ai/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(baseRequest(false))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.data").isEqualTo("stub-text")
            .jsonPath("$.model").isEqualTo("stub-model");
    }

    @Test
    void e2e_chat_parses_fenced_json_payload() {
        stubAiChatService.setChatHandler(req -> completedChat("```json\n{\"summary\":\"ok\",\"riskLevel\":\"LOW\"}\n```", "json-model"));
        ChatRequest req = baseRequest(false);
        req.setResponseFormat("json");

        webTestClient.post().uri("/ai/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.data.summary").isEqualTo("ok")
            .jsonPath("$.data.riskLevel").isEqualTo("LOW")
            .jsonPath("$.model").isEqualTo("json-model");
    }

    @Test
    void e2e_chat_rejects_invalid_request() {
        ChatRequest req = baseRequest(false);
        req.setScene(" ");

        webTestClient.post().uri("/ai/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void e2e_chat_surfaces_service_failure() {
        stubAiChatService.setChatHandler(req -> CompletableFuture.failedFuture(new IllegalStateException("stub-failure")));

        webTestClient.post().uri("/ai/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(baseRequest(false))
            .exchange()
            .expectStatus().is5xxServerError();
    }

    @Test
    void e2e_streaming_ok() {
        webTestClient.post().uri("/ai/chat/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(baseRequest(true))
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
        ChatRequest req = baseRequest(true);
        req.setResponseFormat("json");
        java.util.Map<String,Object> schema = new java.util.HashMap<>();
        schema.put("type","object");
        req.setResponseSchema(schema);

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
    void e2e_streaming_empty_flux_still_sends_done_event() {
        stubAiChatService.setStreamHandler(req -> Flux.empty());

        webTestClient.post().uri("/ai/chat/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(baseRequest(true))
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> {
                org.assertj.core.api.Assertions.assertThat(body).contains("event:done");
                org.assertj.core.api.Assertions.assertThat(body).doesNotContain("data:token-1");
            });
    }

    @Test
    void e2e_chat_rejects_stream_flag() {
        ChatRequest req = baseRequest(true);

        webTestClient.post().uri("/ai/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isBadRequest();
    }

    private static ChatRequest baseRequest(boolean stream) {
        ChatRequest req = new ChatRequest();
        req.setScene("CONTRACT_SUMMARY");
        req.setTenantId("t001");
        req.setUserId("u123");
        req.setMessages(List.of(new ChatMessage("user", "hi")));
        req.setStream(stream);
        return req;
    }

    private static CompletableFuture<ChatResponse> completedChat(String content, String model) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder().withModel(model).build();
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage(content))), metadata);
        return CompletableFuture.completedFuture(response);
    }

    static class StubAiChatService extends AiChatService {
        private Function<ChatRequest, CompletableFuture<ChatResponse>> chatHandler;
        private Function<ChatRequest, Flux<String>> streamHandler;

        StubAiChatService() {
            super(new com.yowits.banbu.ai.config.ProviderRegistry(java.util.Map.of(), java.util.Map.of()),
                    new com.yowits.banbu.ai.router.ModelRouter(new com.yowits.banbu.ai.config.AiRoutingProperties()),
                    new ObjectMapper(),
                    new com.yowits.banbu.ai.config.AiPolicyProperties());
            reset();
        }

        void reset() {
            this.chatHandler = req -> completedChat("stub-text", "stub-model");
            this.streamHandler = req -> Flux.just("token-1", "token-2");
        }

        void setChatHandler(Function<ChatRequest, CompletableFuture<ChatResponse>> chatHandler) {
            this.chatHandler = chatHandler;
        }

        void setStreamHandler(Function<ChatRequest, Flux<String>> streamHandler) {
            this.streamHandler = streamHandler;
        }

        @Override
        public CompletableFuture<ChatResponse> chat(ChatRequest req) {
            return chatHandler.apply(req);
        }

        @Override
        public Flux<String> chatStream(ChatRequest req) {
            return streamHandler.apply(req);
        }
    }
}
