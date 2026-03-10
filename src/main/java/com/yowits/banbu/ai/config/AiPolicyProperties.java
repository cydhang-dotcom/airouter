package com.yowits.banbu.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "ai.policy")
public class AiPolicyProperties {

    private Policy defaultPolicy = new Policy();
    private Map<String, Policy> scenes = new HashMap<>();
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
        private boolean allowFallback = true;
        private int timeoutMs = 30000;
        private int perRouteMaxAttempts = 1; // 每条链路的最大尝试次数

        public boolean isAllowFallback() { return allowFallback; }
        public void setAllowFallback(boolean allowFallback) { this.allowFallback = allowFallback; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getPerRouteMaxAttempts() { return perRouteMaxAttempts; }
        public void setPerRouteMaxAttempts(int perRouteMaxAttempts) { this.perRouteMaxAttempts = perRouteMaxAttempts; }
    }

    public static class TenantPolicy {
        private Policy defaultPolicy = new Policy();
        private Map<String, Policy> scenes = new HashMap<>();

        public Policy getDefaultPolicy() { return defaultPolicy; }
        public void setDefaultPolicy(Policy defaultPolicy) { this.defaultPolicy = defaultPolicy; }
        public Map<String, Policy> getScenes() { return scenes; }
        public void setScenes(Map<String, Policy> scenes) { this.scenes = scenes; }
    }
}
