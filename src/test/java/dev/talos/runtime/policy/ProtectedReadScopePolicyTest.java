package dev.talos.runtime.policy;

import dev.talos.core.Config;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtectedReadScopePolicyTest {

    @Test
    void default_developer_mode_allows_explicit_approved_protected_read_model_context() {
        Config cfg = new Config(null);

        assertFalse(ProtectedReadScopePolicy.privateMode(cfg));
        assertTrue(ProtectedReadScopePolicy.sendApprovedProtectedReadToModel(cfg));
    }

    @Test
    void private_mode_direct_protected_read_is_local_display_only_by_default() {
        Config cfg = new Config(null);
        cfg.data.put("privacy", Map.of("mode", "private"));

        assertTrue(ProtectedReadScopePolicy.privateMode(cfg));
        assertFalse(ProtectedReadScopePolicy.sendApprovedProtectedReadToModel(cfg));
    }

    @Test
    void approved_protected_read_send_to_model_requires_explicit_scope_in_private_mode() {
        Config cfg = new Config(null);
        cfg.data.put("privacy", new LinkedHashMap<>(Map.of(
                "mode", "private",
                "protected_read", new LinkedHashMap<>(Map.of(
                        "default_scope", "SEND_TO_MODEL_CONTEXT",
                        "allow_send_to_model", true)))));

        assertTrue(ProtectedReadScopePolicy.sendApprovedProtectedReadToModel(cfg));
    }

    @Test
    void persist_raw_artifacts_is_denied_by_default() {
        Config cfg = new Config(null);

        assertFalse(ProtectedReadScopePolicy.persistRawArtifacts(cfg));
    }
}
