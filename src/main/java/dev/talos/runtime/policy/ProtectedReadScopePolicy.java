package dev.talos.runtime.policy;

import dev.talos.core.CfgUtil;
import dev.talos.core.Config;

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
        Map<String, Object> privacy = privacy(cfg);
        String mode = String.valueOf(privacy.getOrDefault("mode", "developer"))
                .strip()
                .toLowerCase(Locale.ROOT);
        return "private".equals(mode) || "strict".equals(mode) || "strict_privacy".equals(mode);
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
        if (!privateMode(cfg)) return true;
        Map<String, Object> rag = CfgUtil.map(privacy(cfg).get("rag"));
        return CfgUtil.boolAt(rag, "enabled_in_private_mode", false);
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
}
