package com.yowits.banbu.ai.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiConfigValidatorTest {

    @Test
    void validate_acceptsKnownAliasesInRoutesAndChains() {
        AiRoutingProperties routing = new AiRoutingProperties();
        routing.setDefaultModel("kimi-main:moonshot-v1-8k");
        routing.setRoutes(Map.of("CONTRACT_SUMMARY", "kimi-main:moonshot-v1-8k"));
        routing.setChains(Map.of("CUSTOMER_FOLLOWUP", List.of("glm-main:glm-4", "kimi-main:moonshot-v1-8k")));

        ProvidersProperties providers = new ProvidersProperties();
        ProvidersProperties.OpenAiCompat kimi = new ProvidersProperties.OpenAiCompat();
        kimi.setType("openai-compat");
        kimi.setBaseUrl("https://api.moonshot.cn");
        kimi.setApiKey("sk-kimi");
        ProvidersProperties.OpenAiCompat glm = new ProvidersProperties.OpenAiCompat();
        glm.setType("openai-compat");
        glm.setBaseUrl("https://open.bigmodel.cn/api/paas");
        glm.setApiKey("sk-glm");
        providers.setClients(Map.of("kimi-main", kimi, "glm-main", glm));

        AiConfigValidator validator = new AiConfigValidator(routing, providers);
        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void validate_rejectsUnknownAlias() {
        AiRoutingProperties routing = new AiRoutingProperties();
        routing.setDefaultModel("kimi-main:moonshot-v1-8k");
        routing.setRoutes(Map.of("CONTRACT_SUMMARY", "missing:model"));

        ProvidersProperties providers = new ProvidersProperties();
        ProvidersProperties.OpenAiCompat kimi = new ProvidersProperties.OpenAiCompat();
        kimi.setType("openai-compat");
        kimi.setBaseUrl("https://api.moonshot.cn");
        kimi.setApiKey("sk-kimi");
        providers.setClients(Map.of("kimi-main", kimi));

        AiConfigValidator validator = new AiConfigValidator(routing, providers);
        assertThatThrownBy(validator::validate)
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("unknown alias");
    }
}
