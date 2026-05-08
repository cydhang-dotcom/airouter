package com.yowits.banbu.ai.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiPolicyPropertiesTest {

    @Test
    void resolve_priorityTenantScene_overridesScene_overridesDefault() {
        AiPolicyProperties props = new AiPolicyProperties();

        var def = new AiPolicyProperties.Policy();
        def.setTimeoutMs(30000);
        props.setDefaultPolicy(def);

        var scenePolicy = new AiPolicyProperties.Policy();
        scenePolicy.setTimeoutMs(40000);
        props.setScenes(Map.of("S1", scenePolicy));

        var tenantDefault = new AiPolicyProperties.Policy();
        tenantDefault.setTimeoutMs(20000);
        var tenantScene = new AiPolicyProperties.Policy();
        tenantScene.setTimeoutMs(15000);
        var tp = new AiPolicyProperties.TenantPolicy();
        tp.setDefaultPolicy(tenantDefault);
        tp.setScenes(Map.of("S1", tenantScene));
        props.setTenants(Map.of("t001", tp));

        // tenant scene override
        assertThat(props.resolve("t001", "S1").getTimeoutMs()).isEqualTo(15000);
        // tenant default override
        assertThat(props.resolve("t001", "S2").getTimeoutMs()).isEqualTo(20000);
        // global scene override
        assertThat(props.resolve("tXXX", "S1").getTimeoutMs()).isEqualTo(40000);
        // global default
        assertThat(props.resolve(null, "S9").getTimeoutMs()).isEqualTo(30000);
    }

    @Test
    void policy_defaults_to_sequential_and_can_switch_to_fastestWins() {
        AiPolicyProperties.Policy policy = new AiPolicyProperties.Policy();
        assertThat(policy.isFastestWins()).isFalse();
        assertThat(policy.getRaceMaxCandidates()).isEqualTo(2);

        policy.setRoutingMode(AiPolicyProperties.Policy.ROUTING_MODE_FASTEST_WINS);
        policy.setRaceMaxCandidates(3);

        assertThat(policy.isFastestWins()).isTrue();
        assertThat(policy.getRaceMaxCandidates()).isEqualTo(3);
    }
}
