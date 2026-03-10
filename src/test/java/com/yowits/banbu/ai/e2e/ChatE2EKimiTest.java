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

import java.util.List;

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
        ChatRequest req = new ChatRequest();
        req.setScene("CONTRACT_SUMMARY");
        req.setTenantId("t-e2e");
        req.setUserId("u-e2e");
        req.setMessages(List.of(new ChatMessage("user", "用20字总结：Kimi 是什么？")));
        req.setResponseFormat("text");
        req.setStream(false);

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
    void kimi_stream_chat_ok() {
        ChatRequest req = new ChatRequest();
        req.setScene("CONTRACT_SUMMARY");
        req.setTenantId("t-e2e");
        req.setUserId("u-e2e");
        req.setMessages(List.of(new ChatMessage("user", "请流式回答：Kimi 的特点？")));
        req.setStream(true);

        webTestClient.post().uri("/ai/chat/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(req)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .expectBody(String.class)
            .value(body -> org.assertj.core.api.Assertions.assertThat(body).contains("event:message"));
    }
}
