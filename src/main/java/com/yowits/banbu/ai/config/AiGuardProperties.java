package com.yowits.banbu.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "ai.guard")
public class AiGuardProperties {

    private boolean enabled = true;
    private int defaultRequestsPerMinute = 120;
    private Map<String, TenantGuardPolicy> tenants = new HashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDefaultRequestsPerMinute() {
        return defaultRequestsPerMinute;
    }

    public void setDefaultRequestsPerMinute(int defaultRequestsPerMinute) {
        this.defaultRequestsPerMinute = defaultRequestsPerMinute;
    }

    public Map<String, TenantGuardPolicy> getTenants() {
        return tenants;
    }

    public void setTenants(Map<String, TenantGuardPolicy> tenants) {
        this.tenants = tenants;
    }

    public int resolveRequestsPerMinute(String tenantId) {
        TenantGuardPolicy tenantPolicy = tenantId != null ? tenants.get(tenantId) : null;
        if (tenantPolicy != null && tenantPolicy.getRequestsPerMinute() > 0) {
            return tenantPolicy.getRequestsPerMinute();
        }
        return defaultRequestsPerMinute;
    }

    public static class TenantGuardPolicy {
        private int requestsPerMinute;

        public int getRequestsPerMinute() {
            return requestsPerMinute;
        }

        public void setRequestsPerMinute(int requestsPerMinute) {
            this.requestsPerMinute = requestsPerMinute;
        }
    }
}
