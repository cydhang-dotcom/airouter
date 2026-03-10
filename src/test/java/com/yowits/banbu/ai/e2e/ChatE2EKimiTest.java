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
                try {
                    var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
                    String data = node.path("data").asText("");
                    if (data != null) {
                        String snippet = data.replaceAll("\\n"," ");
                        if (snippet.length() > 200) snippet = snippet.substring(0, 200);
                        System.out.println("KIMI_NONSTREAM_DATA_SNIPPET: " + snippet);
                    }
                    org.assertj.core.api.Assertions.assertThat(data).isNotBlank();
                } catch (Exception e) {
                    throw new AssertionError("Failed to parse response JSON", e);
                }
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
                try {
                    var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
                    var data = node.path("data");
                    org.assertj.core.api.Assertions.assertThat(data.isObject()).isTrue();
                    org.assertj.core.api.Assertions.assertThat(data.path("summary").asText("")).isNotBlank();
                    org.assertj.core.api.Assertions.assertThat(data.path("language").asText("")).isNotBlank();
                    org.assertj.core.api.Assertions.assertThat(data.has("officeFit")).isTrue();
                } catch (Exception e) {
                    throw new AssertionError("Failed to parse structured JSON response", e);
                }
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
    void kimi_chat_rejects_stream_flag() {
        ChatRequest req = baseRequest(true, "这个请求不应该走到模型。");

        webTestClient.post().uri("/ai/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody(String.class)
            .value(body -> org.assertj.core.api.Assertions.assertThat(body).contains("/ai/chat/stream"));
    }

    @Test
    void kimi_chat_rejects_invalid_request() {
        ChatRequest req = baseRequest(false, "这个请求缺少 scene。");
        req.setScene(" ");

        webTestClient.post().uri("/ai/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isBadRequest();
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
}
