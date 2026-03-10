package com.yowits.banbu.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

@Component
public class AiConfigSummaryLogger implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AiConfigSummaryLogger.class);

    private final AiRoutingProperties routingProperties;
    private final AiPolicyProperties policyProperties;
    private final AiGuardProperties guardProperties;
    private final AiConfigValidator configValidator;

    public AiConfigSummaryLogger(AiRoutingProperties routingProperties,
                                 AiPolicyProperties policyProperties,
                                 AiGuardProperties guardProperties,
                                 AiConfigValidator configValidator) {
        this.routingProperties = routingProperties;
        this.policyProperties = policyProperties;
        this.guardProperties = guardProperties;
        this.configValidator = configValidator;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("config_summary aliases={} defaultModel={} routes={} chains={}",
                configValidator.registeredAliases(),
                routingProperties.getDefaultModel(),
                routingProperties.getRoutes(),
                routingProperties.getChains());
        log.info("config_summary policyDefault=allowFallback:{} timeoutMs:{} perRouteMaxAttempts:{} tenantPolicies={} scenePolicies={}",
                policyProperties.getDefaultPolicy().isAllowFallback(),
                policyProperties.getDefaultPolicy().getTimeoutMs(),
                policyProperties.getDefaultPolicy().getPerRouteMaxAttempts(),
                policyProperties.getTenants().keySet(),
                policyProperties.getScenes().keySet());
        log.info("config_summary guardEnabled={} defaultRequestsPerMinute={} tenantGuardOverrides={}",
                guardProperties.isEnabled(),
                guardProperties.getDefaultRequestsPerMinute(),
                guardProperties.getTenants().entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getRequestsPerMinute())));
    }
}
