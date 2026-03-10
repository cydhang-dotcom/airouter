package com.yowits.banbu.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

@Component
public class ProviderRegistry {
    private final Map<String, ChatClient> clientsByAlias;
    private final Map<String, String> typeByAlias; // alias -> type

    public ProviderRegistry() {
        this.clientsByAlias = Collections.emptyMap();
        this.typeByAlias = Collections.emptyMap();
    }

    public ProviderRegistry(Map<String, ChatClient> clientsByAlias, Map<String, String> typeByAlias) {
        this.clientsByAlias = Map.copyOf(clientsByAlias);
        this.typeByAlias = Map.copyOf(typeByAlias);
    }

    public ChatClient get(String alias) { return clientsByAlias.get(alias); }
    public String typeOf(String alias) { return typeByAlias.get(alias); }

    public Map<String, ChatClient> all() { return clientsByAlias; }
}
