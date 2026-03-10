package com.yowits.banbu.ai.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AiConfigValidator {

    private final AiRoutingProperties routingProperties;
    private final ProvidersProperties providersProperties;

    public AiConfigValidator(AiRoutingProperties routingProperties, ProvidersProperties providersProperties) {
        this.routingProperties = routingProperties;
        this.providersProperties = providersProperties;
    }

    @PostConstruct
    public void validate() {
        Set<String> aliases = providersProperties.getClients().keySet();
        if (aliases.isEmpty()) {
            throw new ConfigValidationException("providers.clients must not be empty");
        }
        providersProperties.getClients().forEach(this::validateProviderClient);
        validateRoute("ai.default-model", routingProperties.getDefaultModel(), aliases);
        for (Map.Entry<String, String> entry : routingProperties.getRoutes().entrySet()) {
            validateRoute("ai.routes." + entry.getKey(), entry.getValue(), aliases);
        }
        for (Map.Entry<String, List<String>> entry : routingProperties.getChains().entrySet()) {
            List<String> chain = entry.getValue();
            if (chain == null || chain.isEmpty()) {
                throw new ConfigValidationException("ai.chains." + entry.getKey() + " must not be empty");
            }
            for (int i = 0; i < chain.size(); i++) {
                validateRoute("ai.chains." + entry.getKey() + "[" + i + "]", chain.get(i), aliases);
            }
        }
    }

    private void validateProviderClient(String alias, ProvidersProperties.OpenAiCompat cfg) {
        if (alias == null || alias.isBlank()) {
            throw new ConfigValidationException("providers.clients contains blank alias");
        }
        if (cfg == null) {
            throw new ConfigValidationException("providers.clients." + alias + " must not be null");
        }
        String type = cfg.getType() == null ? "" : cfg.getType().trim().toLowerCase();
        if (type.isBlank()) {
            throw new ConfigValidationException("providers.clients." + alias + ".type must not be blank");
        }
        if ("openai-compat".equals(type) && (cfg.getBaseUrl() == null || cfg.getBaseUrl().isBlank())) {
            throw new ConfigValidationException("providers.clients." + alias + ".base-url must not be blank");
        }
    }

    private void validateRoute(String key, String value, Set<String> aliases) {
        if (value == null || value.isBlank()) {
            throw new ConfigValidationException(key + " must not be blank");
        }
        int idx = value.indexOf(':');
        if (idx <= 0 || idx == value.length() - 1) {
            throw new ConfigValidationException(key + " must use alias:model format, got: " + value);
        }
        String alias = value.substring(0, idx);
        String model = value.substring(idx + 1);
        if (!aliases.contains(alias)) {
            throw new ConfigValidationException(key + " references unknown alias: " + alias);
        }
        if (model.isBlank()) {
            throw new ConfigValidationException(key + " has blank model: " + value);
        }
    }

    public Set<String> registeredAliases() {
        return new LinkedHashSet<>(providersProperties.getClients().keySet());
    }
}
