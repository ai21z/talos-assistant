package dev.talos.runtime.policy;

import dev.talos.core.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Parsed permission config overlay from the existing Talos config map. */
public record PermissionConfig(List<PermissionRule> rules) {
    public PermissionConfig {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public static PermissionConfig from(Config config) {
        if (config == null || config.data == null) return new PermissionConfig(List.of());
        Object permissionsObj = config.data.get("permissions");
        if (!(permissionsObj instanceof Map<?, ?> permissions)) {
            return new PermissionConfig(List.of());
        }
        Object rulesObj = permissions.get("rules");
        if (!(rulesObj instanceof List<?> rawRules)) {
            return new PermissionConfig(List.of());
        }

        List<PermissionRule> parsed = new ArrayList<>();
        for (Object rawRule : rawRules) {
            if (rawRule instanceof Map<?, ?> ruleMap) {
                parsed.add(PermissionRule.fromMap(ruleMap));
            } else {
                parsed.add(PermissionRule.fromMap(Map.of(
                        "effect", "deny",
                        "reason", "Invalid permission rule entry")));
            }
        }
        return new PermissionConfig(parsed);
    }
}
