package dev.talos.core.privacy;

import dev.talos.core.Config;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrivacyConfigFactsTest {

    @Test
    void developer_mode_is_not_private_by_default() {
        assertFalse(PrivacyConfigFacts.privateMode(new Config(null)));
    }

    @Test
    void private_strict_and_strict_privacy_modes_are_private() {
        assertTrue(PrivacyConfigFacts.privateMode(configWithPrivacyMode("private")));
        assertTrue(PrivacyConfigFacts.privateMode(configWithPrivacyMode("strict")));
        assertTrue(PrivacyConfigFacts.privateMode(configWithPrivacyMode("strict_privacy")));
    }

    @Test
    void private_mode_rag_is_disabled_by_default_and_can_be_explicitly_enabled() {
        assertFalse(PrivacyConfigFacts.ragEnabledInPrivateMode(configWithPrivacyMode("private")));

        Config cfg = configWithPrivacyMode("private");
        cfg.data.put("privacy", new LinkedHashMap<>(Map.of(
                "mode", "private",
                "rag", new LinkedHashMap<>(Map.of("enabled_in_private_mode", Boolean.TRUE)))));

        assertTrue(PrivacyConfigFacts.ragEnabledInPrivateMode(cfg));
    }

    @Test
    void developer_mode_rag_is_enabled_for_privacy_fact_consumers() {
        assertTrue(PrivacyConfigFacts.ragEnabledInPrivateMode(new Config(null)));
    }

    private static Config configWithPrivacyMode(String mode) {
        Config cfg = new Config(null);
        cfg.data.put("privacy", new LinkedHashMap<>(Map.of("mode", mode)));
        return cfg;
    }
}
