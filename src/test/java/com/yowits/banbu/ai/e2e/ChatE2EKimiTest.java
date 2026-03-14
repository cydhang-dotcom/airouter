package com.yowits.banbu.ai.e2e;

import com.yowits.banbu.ai.AiServiceApplication;
import com.yowits.banbu.ai.api.dto.ChatMessage;
import com.yowits.banbu.ai.api.dto.ChatRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        // 显式从系统属性读取并注入 Spring AI 所需配置
        "spring.ai.openai.base-url=${openai.base.url}",
        "spring.ai.openai.api-key=${openai.api.key}"
    }
)
@EnabledIfSystemProperty(named = "e2e.kimi", matches = "(?i:true|1|yes)")
class ChatE2EKimiTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void kimi_nonstream_chat_ok() {
        ChatRequest req = baseRequest(false, "用20字总结：Kimi 是什么？");
        req.setResponseFormat("text");

        webTestClient.post().uri("/ai/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> {
                var node = readJson(body);
                String data = node.path("data").asText("");
                if (data != null) {
                    String snippet = data.replaceAll("\\n"," ");
                    if (snippet.length() > 200) snippet = snippet.substring(0, 200);
                    System.out.println("KIMI_NONSTREAM_DATA_SNIPPET: " + snippet);
                }
                org.assertj.core.api.Assertions.assertThat(data).isNotBlank();
                org.assertj.core.api.Assertions.assertThat(node.path("model").asText("")).containsIgnoringCase("kimi");
            });
    }

    @Test
    void kimi_nonstream_chat_with_system_message_and_options_ok() {
        ChatRequest req = baseRequest(false, "请用一句中文说明 Kimi 是否适合作为办公助手。");
        req.setResponseFormat("text");
        req.setMessages(List.of(
            new ChatMessage("system", "你是企业内部 AI 助手，请简洁、直接作答。"),
            new ChatMessage("user", "请用一句中文说明 Kimi 是否适合作为办公助手。")
        ));
        req.setOptions(Map.of("temperature", 0.2, "maxTokens", 80));

        webTestClient.post().uri("/ai/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> {
                var node = readJson(body);
                org.assertj.core.api.Assertions.assertThat(node.path("data").asText("")).isNotBlank();
                org.assertj.core.api.Assertions.assertThat(node.path("model").asText("")).containsIgnoringCase("kimi");
            });
    }

    @Test
    void kimi_nonstream_json_schema_ok() {
        ChatRequest req = baseRequest(false, "请介绍 Kimi，并返回摘要、语言和是否适合办公场景。");
        req.setResponseFormat("json");
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "summary", Map.of("type", "string"),
            "language", Map.of("type", "string"),
            "officeFit", Map.of("type", "boolean")
        ));
        schema.put("required", List.of("summary", "language", "officeFit"));
        req.setResponseSchema(schema);

        webTestClient.post().uri("/ai/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> {
                var node = readJson(body);
                var data = node.path("data");
                org.assertj.core.api.Assertions.assertThat(data.isObject()).isTrue();
                org.assertj.core.api.Assertions.assertThat(data.path("summary").asText("")).isNotBlank();
                org.assertj.core.api.Assertions.assertThat(data.path("language").asText("")).isNotBlank();
                org.assertj.core.api.Assertions.assertThat(data.path("officeFit").isBoolean()).isTrue();
                org.assertj.core.api.Assertions.assertThat(node.path("model").asText("")).containsIgnoringCase("kimi");
            });
    }

    @Test
    void kimi_stream_chat_ok() {
        ChatRequest req = baseRequest(true, "请流式回答：Kimi 的特点？");

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
                org.assertj.core.api.Assertions.assertThat(body).contains("[DONE]");
            });
    }

    @Test
    void kimi_stream_json_schema_ok() {
        ChatRequest req = baseRequest(true, "请流式输出一个 JSON，对 Kimi 给出 summary 和 officeFit。");
        req.setResponseFormat("json");
        req.setResponseSchema(Map.of(
            "type", "object",
            "properties", Map.of(
                "summary", Map.of("type", "string"),
                "officeFit", Map.of("type", "boolean")
            ),
            "required", List.of("summary", "officeFit")
        ));

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
                org.assertj.core.api.Assertions.assertThat(body).contains("[DONE]");
                org.assertj.core.api.Assertions.assertThat(body).contains("summary");
            });
    }

    @Test
    void kimi_chat_rejects_stream_flag() {
        ChatRequest req = baseRequest(true, "这个请求不应该走到模型。");

        webTestClient.post().uri("/ai/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
            .jsonPath("$.message").value(body -> org.assertj.core.api.Assertions.assertThat(body.toString()).contains("/ai/chat/stream"));
    }

    @Test
    void kimi_chat_rejects_invalid_request() {
        ChatRequest req = baseRequest(false, "这个请求缺少 scene。");
        req.setScene(" ");

        webTestClient.post().uri("/ai/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST");
    }

    @Test
    void kimi_chat_rejects_empty_messages() {
        ChatRequest req = baseRequest(false, "这个请求缺少消息列表。");
        req.setMessages(List.of());

        webTestClient.post().uri("/ai/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
            .jsonPath("$.message").value(body -> org.assertj.core.api.Assertions.assertThat(body.toString()).contains("messages"));
    }

    @Test
    void kimi_chat_rejects_blank_message_content() {
        ChatRequest req = baseRequest(false, "这个请求消息内容为空。");
        req.setMessages(List.of(new ChatMessage("user", " ")));

        webTestClient.post().uri("/ai/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
            .jsonPath("$.message").value(body -> org.assertj.core.api.Assertions.assertThat(body.toString()).contains("content"));
    }

    private static ChatRequest baseRequest(boolean stream, String prompt) {
        ChatRequest req = new ChatRequest();
        req.setScene("CONTRACT_SUMMARY");
        req.setTenantId("t-e2e");
        req.setUserId("u-e2e");
        req.setMessages(List.of(new ChatMessage("user", prompt)));
        req.setStream(stream);
        return req;
    }

    private static com.fasterxml.jackson.databind.JsonNode readJson(String body) {
        try {
            return OBJECT_MAPPER.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("Failed to parse response JSON", e);
        }
    }
}
