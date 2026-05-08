package com.yowits.banbu.ai.e2e;

import com.yowits.banbu.ai.api.dto.ChatMessage;
import com.yowits.banbu.ai.api.dto.ChatRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.openai.base-url=${deepseek.base.url}",
                "spring.ai.openai.api-key=${deepseek.api.key}",
                "providers.clients.deepseek-main.type=openai-compat",
                "providers.clients.deepseek-main.base-url=${deepseek.base.url}",
                "providers.clients.deepseek-main.api-key=${deepseek.api.key}",
                "ai.routes.CONTRACT_SUMMARY=deepseek-main:${deepseek.model:deepseek-v4-flash}",
                "ai.chains.CONTRACT_SUMMARY[0]=deepseek-main:${deepseek.model:deepseek-v4-flash}",
                "ai.policy.scenes.CONTRACT_SUMMARY.timeout-ms=45000"
        }
)
@EnabledIfSystemProperty(named = "e2e.deepseek", matches = "(?i:true|1|yes)")
class ChatE2EDeepSeekTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    void configureWebTestClient(WebTestClient client) {
        this.webTestClient = client.mutate().responseTimeout(Duration.ofSeconds(60)).build();
    }

    @Test
    void deepseek_v4_flash_nonstream_json_schema_ok() {
        ChatRequest req = new ChatRequest();
        req.setScene("CONTRACT_SUMMARY");
        req.setTenantId("t-e2e");
        req.setUserId("u-e2e");
        req.setStream(false);
        req.setResponseFormat("json");
        req.setMessages(List.of(new ChatMessage("user", """
                你是一位拥有 10 年经验的上海工商注册专家。对“企业代账服务”，依据中国大陆最新工商法规进行严格的合规性分析。

                直接返回 JSON，不要包含 Markdown，不要输出解释文字。
                字段名必须严格使用以下键名：
                - suggestedScope: string
                - needLicense: "yes" | "no"
                - licenseDetail: string
                - hasSensitiveTypes: "yes" | "no"
                - sensitiveTypes: string[]
                - otherSensitiveType: string
                """)));
        req.setResponseSchema(Map.of(
                "type", "object",
                "properties", Map.of(
                        "suggestedScope", Map.of("type", "string"),
                        "needLicense", Map.of("type", "string", "enum", List.of("yes", "no")),
                        "licenseDetail", Map.of("type", "string"),
                        "hasSensitiveTypes", Map.of("type", "string", "enum", List.of("yes", "no")),
                        "sensitiveTypes", Map.of("type", "array", "items", Map.of("type", "string")),
                        "otherSensitiveType", Map.of("type", "string")
                ),
                "required", List.of("suggestedScope", "needLicense", "licenseDetail", "hasSensitiveTypes", "sensitiveTypes", "otherSensitiveType")
        ));

        webTestClient.post().uri("/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    var node = readJson(body);
                    var data = node.path("data");
                    String model = node.path("model").asText("");
                    System.out.println("DEEPSEEK_MODEL: " + model);
                    System.out.println("DEEPSEEK_DATA: " + data.toPrettyString());

                    org.assertj.core.api.Assertions.assertThat(data.isObject()).isTrue();
                    org.assertj.core.api.Assertions.assertThat(model).containsIgnoringCase("deepseek");
                    org.assertj.core.api.Assertions.assertThat(data.path("suggestedScope").asText("")).isNotBlank();
                    org.assertj.core.api.Assertions.assertThat(data.path("needLicense").asText("")).isIn("yes", "no");
                    org.assertj.core.api.Assertions.assertThat(data.path("licenseDetail").isTextual()).isTrue();
                    org.assertj.core.api.Assertions.assertThat(data.path("hasSensitiveTypes").asText("")).isIn("yes", "no");
                    org.assertj.core.api.Assertions.assertThat(data.path("sensitiveTypes").isArray()).isTrue();
                    org.assertj.core.api.Assertions.assertThat(data.path("otherSensitiveType").isTextual()).isTrue();
                });
    }

    private static com.fasterxml.jackson.databind.JsonNode readJson(String body) {
        try {
            return OBJECT_MAPPER.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("Failed to parse response JSON", e);
        }
    }
}
