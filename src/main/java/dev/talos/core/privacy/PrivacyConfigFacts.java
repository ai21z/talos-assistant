package dev.talos.core.privacy;

import dev.talos.core.CfgUtil;
import dev.talos.core.Config;

import java.util.Locale;
import java.util.Map;

/** Read-only privacy configuration facts shared by core, tools, and runtime. */
public final class PrivacyConfigFacts {
    private PrivacyConfigFacts() {}

    public static boolean privateMode(Config cfg) {
        Map<String, Object> privacy = privacy(cfg);
        String mode = String.valueOf(privacy.getOrDefault("mode", "developer"))
                .strip()
                .toLowerCase(Locale.ROOT);
        return "private".equals(mode) || "strict".equals(mode) || "strict_privacy".equals(mode);
    }

    public static boolean ragEnabledInPrivateMode(Config cfg) {
        if (!privateMode(cfg)) return true;
        Map<String, Object> rag = CfgUtil.map(privacy(cfg).get("rag"));
        return CfgUtil.boolAt(rag, "enabled_in_private_mode", false);
    }

    private static Map<String, Object> privacy(Config cfg) {
        if (cfg == null) return Map.of();
        return CfgUtil.map(cfg.data.get("privacy"));
    }
}
