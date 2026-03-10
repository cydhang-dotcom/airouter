package com.yowits.banbu.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "providers")
public class ProvidersProperties {

    /**
     * 动态定义多个客户端别名（如 kimi-main、kimi-256k、glm-main）。
     * key = 别名；value = OpenAI 兼容配置（baseUrl + apiKey）。
     */
    private Map<String, OpenAiCompat> clients = new LinkedHashMap<>();

    public Map<String, OpenAiCompat> getClients() { return clients; }
    public void setClients(Map<String, OpenAiCompat> clients) { this.clients = clients; }

    public static class OpenAiCompat {
        private String baseUrl;
        private String apiKey;
        /** provider 类型：openai-compat | dashscope */
        private String type = "openai-compat";

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
    }
}
