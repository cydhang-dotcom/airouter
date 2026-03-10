package com.yowits.banbu.ai.router;

import com.yowits.banbu.ai.config.AiRoutingProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRouterChainTest {

    @Test
    void chooseChain_includesPrimaryFirst_andKeepsConfiguredOrder() {
        AiRoutingProperties props = new AiRoutingProperties();
        props.setDefaultModel("def:default-model");
        props.setRoutes(Map.of("SCENE_A", "a1:m1"));
        props.setChains(Map.of("SCENE_A", List.of("a2:m2", "a3:m3")));

        ModelRouter router = new ModelRouter(props);
        var chain = router.chooseChain("SCENE_A");

        assertThat(chain).hasSize(3);
        assertThat(chain.get(0).alias()).isEqualTo("a1");
        assertThat(chain.get(0).model()).isEqualTo("m1");
        assertThat(chain.get(1).alias()).isEqualTo("a2");
        assertThat(chain.get(1).model()).isEqualTo("m2");
        assertThat(chain.get(2).alias()).isEqualTo("a3");
        assertThat(chain.get(2).model()).isEqualTo("m3");
    }

    @Test
    void chooseChain_doesNotDuplicatePrimary_ifPresent() {
        AiRoutingProperties props = new AiRoutingProperties();
        props.setRoutes(Map.of("SCENE_B", "b1:x1"));
        props.setChains(Map.of("SCENE_B", List.of("b1:x1", "b2:x2")));

        ModelRouter router = new ModelRouter(props);
        var chain = router.chooseChain("SCENE_B");

        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).alias()).isEqualTo("b1");
        assertThat(chain.get(0).model()).isEqualTo("x1");
        assertThat(chain.get(1).alias()).isEqualTo("b2");
        assertThat(chain.get(1).model()).isEqualTo("x2");
    }
}

