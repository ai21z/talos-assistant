package dev.talos.runtime.policy;

import dev.talos.core.CfgUtil;
import dev.talos.core.Config;
import dev.talos.core.privacy.PrivacyConfigFacts;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Config-backed policy for what an approved protected read is allowed to do. */
public final class ProtectedReadScopePolicy {
    private ProtectedReadScopePolicy() {}

    public enum ProtectedReadScope {
        LOCAL_DISPLAY_ONLY,
        SEND_TO_MODEL_CONTEXT
    }

    public static boolean privateMode(Config cfg) {
        return PrivacyConfigFacts.privateMode(cfg);
    }

    public static ProtectedReadScope defaultScope(Config cfg) {
        Map<String, Object> protectedRead = CfgUtil.map(privacy(cfg).get("protected_read"));
        Object configured = protectedRead.get("default_scope");
        if (configured != null) {
            String value = String.valueOf(configured).strip().toUpperCase(Locale.ROOT);
            if ("SEND_TO_MODEL_CONTEXT".equals(value)) return ProtectedReadScope.SEND_TO_MODEL_CONTEXT;
            if ("LOCAL_DISPLAY_ONLY".equals(value)) return ProtectedReadScope.LOCAL_DISPLAY_ONLY;
        }
        return privateMode(cfg)
                ? ProtectedReadScope.LOCAL_DISPLAY_ONLY
                : ProtectedReadScope.SEND_TO_MODEL_CONTEXT;
    }

    public static boolean sendApprovedProtectedReadToModel(Config cfg) {
        ProtectedReadScope scope = defaultScope(cfg);
        if (scope != ProtectedReadScope.SEND_TO_MODEL_CONTEXT) return false;
        if (!privateMode(cfg)) return true;
        Map<String, Object> protectedRead = CfgUtil.map(privacy(cfg).get("protected_read"));
        return CfgUtil.boolAt(protectedRead, "allow_send_to_model", false);
    }

    public static boolean persistRawArtifacts(Config cfg) {
        Map<String, Object> protectedRead = CfgUtil.map(privacy(cfg).get("protected_read"));
        return CfgUtil.boolAt(protectedRead, "persist_raw_artifacts", false);
    }

    public static boolean ragEnabledInPrivateMode(Config cfg) {
        return PrivacyConfigFacts.ragEnabledInPrivateMode(cfg);
    }

    public static void setPrivateMode(Config cfg, boolean enabled) {
        Map<String, Object> privacy = mutableSection(cfg.data, "privacy");
        privacy.put("mode", enabled ? "private" : "developer");

        Map<String, Object> protectedRead = mutableSection(privacy, "protected_read");
        protectedRead.put("default_scope", enabled ? "LOCAL_DISPLAY_ONLY" : "SEND_TO_MODEL_CONTEXT");
        protectedRead.put("allow_send_to_model", Boolean.FALSE);
        protectedRead.putIfAbsent("persist_raw_artifacts", Boolean.FALSE);

        Map<String, Object> rag = mutableSection(privacy, "rag");
        rag.putIfAbsent("enabled_in_private_mode", Boolean.FALSE);
    }

    public static String approvedProtectedReadModelHandoffNote(Config cfg) {
        if (sendApprovedProtectedReadToModel(cfg)) {
            return "Approval scope: SEND_TO_MODEL_CONTEXT. The protected file contents may be sent to model context for this turn. Raw persistence remains redacted unless explicitly enabled by maintainer config.";
        }
        return "Approval scope: LOCAL_DISPLAY_ONLY. The protected file contents will be read locally but withheld from model context and persisted artifacts.";
    }

    private static Map<String, Object> privacy(Config cfg) {
        if (cfg == null) return Map.of();
        return CfgUtil.map(cfg.data.get("privacy"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutableSection(Map<String, Object> root, String key) {
        Object raw = root.get(key);
        if (raw instanceof Map<?, ?> map) {
            if (raw instanceof LinkedHashMap<?, ?>) {
                return (Map<String, Object>) raw;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    copy.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            root.put(key, copy);
            return copy;
        }
        Map<String, Object> created = new LinkedHashMap<>();
        root.put(key, created);
        return created;
    }
}
