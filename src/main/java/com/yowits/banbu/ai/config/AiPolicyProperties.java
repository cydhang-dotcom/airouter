package com.yowits.banbu.ai.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.HashMap;
import java.util.Map;

@Component
@Validated
@ConfigurationProperties(prefix = "ai.policy")
public class AiPolicyProperties {

    @Valid
    private Policy defaultPolicy = new Policy();
    @Valid
    private Map<String, Policy> scenes = new HashMap<>();
    @Valid
    private Map<String, TenantPolicy> tenants = new HashMap<>();

    public Policy getDefaultPolicy() { return defaultPolicy; }
    public void setDefaultPolicy(Policy defaultPolicy) { this.defaultPolicy = defaultPolicy; }
    public Map<String, Policy> getScenes() { return scenes; }
    public void setScenes(Map<String, Policy> scenes) { this.scenes = scenes; }

    public Policy resolve(String tenantId, String scene) {
        if (tenantId != null) {
            TenantPolicy tp = tenants.get(tenantId);
            if (tp != null) {
                if (tp.getScenes().get(scene) != null) return tp.getScenes().get(scene);
                if (tp.getDefaultPolicy() != null) return tp.getDefaultPolicy();
            }
        }
        if (scenes.get(scene) != null) return scenes.get(scene);
        return defaultPolicy;
    }

    public Map<String, TenantPolicy> getTenants() { return tenants; }
    public void setTenants(Map<String, TenantPolicy> tenants) { this.tenants = tenants; }

    public static class Policy {
        public static final String ROUTING_MODE_SEQUENTIAL = "sequential";
        public static final String ROUTING_MODE_FASTEST_WINS = "fastest-wins";

        private boolean allowFallback = true;
        @Min(1)
        private int timeoutMs = 30000;
        @Min(1)
        private int perRouteMaxAttempts = 1; // 每条链路的最大尝试次数
        private String routingMode = ROUTING_MODE_SEQUENTIAL;
        @Min(1)
        private int raceMaxCandidates = 2;

        public boolean isAllowFallback() { return allowFallback; }
        public void setAllowFallback(boolean allowFallback) { this.allowFallback = allowFallback; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getPerRouteMaxAttempts() { return perRouteMaxAttempts; }
        public void setPerRouteMaxAttempts(int perRouteMaxAttempts) { this.perRouteMaxAttempts = perRouteMaxAttempts; }
        public String getRoutingMode() { return routingMode; }
        public void setRoutingMode(String routingMode) { this.routingMode = routingMode; }
        public int getRaceMaxCandidates() { return raceMaxCandidates; }
        public void setRaceMaxCandidates(int raceMaxCandidates) { this.raceMaxCandidates = raceMaxCandidates; }

        public boolean isFastestWins() {
            return ROUTING_MODE_FASTEST_WINS.equalsIgnoreCase(routingMode);
        }
    }

    public static class TenantPolicy {
        @Valid
        private Policy defaultPolicy = new Policy();
        @Valid
        private Map<String, Policy> scenes = new HashMap<>();

        public Policy getDefaultPolicy() { return defaultPolicy; }
        public void setDefaultPolicy(Policy defaultPolicy) { this.defaultPolicy = defaultPolicy; }
        public Map<String, Policy> getScenes() { return scenes; }
        public void setScenes(Map<String, Policy> scenes) { this.scenes = scenes; }
    }
}
