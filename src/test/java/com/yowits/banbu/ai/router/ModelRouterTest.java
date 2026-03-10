package com.yowits.banbu.ai.router;

import com.yowits.banbu.ai.config.AiRoutingProperties;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ModelRouterTest {

    @Test
    void chooseModel_usesSceneRouteOrDefault() {
        AiRoutingProperties props = new AiRoutingProperties();
        props.setDefaultModel("default-model");
        props.setRoutes(Map.of("SCENE_A", "model-a"));

        ModelRouter router = new ModelRouter(props);
        assertThat(router.choose("SCENE_A").model()).isEqualTo("model-a");
        assertThat(router.choose("UNKNOWN_SCENE").model()).isEqualTo("default-model");
        assertThat(router.choose(null).model()).isEqualTo("default-model");
        assertThat(router.choose(" ").model()).isEqualTo("default-model");
    }
}
