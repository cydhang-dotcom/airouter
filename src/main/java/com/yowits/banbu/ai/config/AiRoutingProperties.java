package com.yowits.banbu.ai.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@Validated
@ConfigurationProperties(prefix = "ai")
public class AiRoutingProperties {

    /** Default model id if scene mapping not found */
    @NotBlank
    private String defaultModel = "gpt-4o-mini";

    /** sceneCode -> modelId mapping */
    private Map<String, String> routes = new HashMap<>();

    /** sceneCode -> fallback chain list, each item format: alias:model */
    private Map<String, java.util.List<String>> chains = new HashMap<>();

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public Map<String, String> getRoutes() {
        return routes;
    }

    public void setRoutes(Map<String, String> routes) {
        this.routes = routes;
    }

    public Map<String, java.util.List<String>> getChains() { return chains; }
    public void setChains(Map<String, java.util.List<String>> chains) { this.chains = chains; }
}
