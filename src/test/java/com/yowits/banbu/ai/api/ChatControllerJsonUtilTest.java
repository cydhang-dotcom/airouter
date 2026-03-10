package com.yowits.banbu.ai.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class ChatControllerJsonUtilTest {

    @Test
    void stripFence_and_tryParseJson_work() throws Exception {
        ChatController controller = new ChatController(null, new ObjectMapper());

        String fenced = "```json\n{\n  \"summary\": \"ok\",\n  \"riskLevel\": \"LOW\"\n}\n```";

        Method strip = ChatController.class.getDeclaredMethod("stripFence", String.class);
        strip.setAccessible(true);
        String stripped = (String) strip.invoke(controller, fenced);
        assertThat(stripped).contains("\"summary\": \"ok\"");

        Method parse = ChatController.class.getDeclaredMethod("tryParseJson", String.class);
        parse.setAccessible(true);
        JsonNode node = (JsonNode) parse.invoke(controller, fenced);
        assertThat(node.get("summary").asText()).isEqualTo("ok");
        assertThat(node.get("riskLevel").asText()).isEqualTo("LOW");
    }
}

