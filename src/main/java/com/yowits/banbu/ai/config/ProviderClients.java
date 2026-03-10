package com.yowits.banbu.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class ProviderClients {

    @Bean
    public ProviderRegistry providerRegistry(ProvidersProperties props, ApplicationContext ctx) {
        Map<String, ChatClient> map = new HashMap<>();
        Map<String, String> types = new HashMap<>();

        props.getClients().forEach((alias, cfg) -> {
            String type = (cfg.getType() == null ? "openai-compat" : cfg.getType()).toLowerCase();
            try {
                switch (type) {
                    case "dashscope" -> {
                        // 使用 Spring 上下文查找 DashScope ChatModel（由 Starter 自动装配）。
                        try {
                            var candidates = ctx.getBeansOfType(org.springframework.ai.chat.model.ChatModel.class);
                            org.springframework.ai.chat.model.ChatModel found = null;
                            for (var entry : candidates.entrySet()) {
                                String cls = entry.getValue().getClass().getName().toLowerCase();
                                if (cls.contains("dashscope")) { found = entry.getValue(); break; }
                            }
                            if (found != null) {
                                map.put(alias, ChatClient.builder(found).build());
                                types.put(alias, "dashscope");
                            }
                        } catch (Throwable ignore) {
                            // 未引入 dashscope 依赖或未自动装配，跳过该 alias
                        }
                    }
                    default -> {
                        // OpenAI 兼容（Kimi/GLM 等）
                        if (cfg.getBaseUrl() != null && cfg.getApiKey() != null) {
                            OpenAiApi api = new OpenAiApi(cfg.getBaseUrl(), cfg.getApiKey());
                            OpenAiChatModel model = new OpenAiChatModel(api, OpenAiChatOptions.builder().build());
                            map.put(alias, ChatClient.builder(model).build());
                            types.put(alias, "openai-compat");
                        }
                    }
                }
            } catch (Throwable t) {
                // ignore faulty alias, continue
            }
        });
        return new ProviderRegistry(map, types);
    }
}
