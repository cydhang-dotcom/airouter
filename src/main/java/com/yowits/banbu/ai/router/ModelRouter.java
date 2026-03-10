package com.yowits.banbu.ai.router;

import com.yowits.banbu.ai.config.AiRoutingProperties;
import org.springframework.stereotype.Component;

@Component
public class ModelRouter {
    public record Route(String alias, String model) {}

    private final AiRoutingProperties props;

    public ModelRouter(AiRoutingProperties props) { this.props = props; }

    public Route choose(String sceneCode) {
        String value;
        if (sceneCode == null || sceneCode.isBlank()) value = props.getDefaultModel();
        else value = props.getRoutes().getOrDefault(sceneCode, props.getDefaultModel());

        // format: alias:model  e.g., kimi-main:kimi-k2.5
        if (value != null && value.contains(":")) {
            String[] parts = value.split(":", 2);
            return new Route(parts[0], parts[1]);
        }
        // fallback: alias 未指定，使用默认 alias 名称 "default"
        return new Route("default", value);
    }

    public java.util.List<Route> chooseChain(String sceneCode) {
        java.util.List<String> list = props.getChains().get(sceneCode);
        java.util.List<Route> out = new java.util.ArrayList<>();
        if (list != null) {
            for (String item : list) {
                if (item != null && item.contains(":")) {
                    String[] parts = item.split(":", 2);
                    out.add(new Route(parts[0], parts[1]));
                }
            }
        }
        // ensure primary route at head if not present
        Route primary = choose(sceneCode);
        if (primary != null) {
            boolean exists = out.stream().anyMatch(r -> r.alias().equals(primary.alias()) && r.model().equals(primary.model()));
            if (!exists) out.add(0, primary);
        }
        return out;
    }
}
