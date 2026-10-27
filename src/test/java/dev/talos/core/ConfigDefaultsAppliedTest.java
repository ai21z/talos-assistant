package dev.talos.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A default the config reports as applied is present in the effective configuration. */
class ConfigDefaultsAppliedTest {
    private static final List<String> SECTIONS = List.of(
            "rag", "document_extraction", "llm", "embed", "net",
            "privacy", "limits", "ui", "tools", "session");

    @TempDir
    Path home;

    @Test
    void defaultsForMissingKeysOfAnExistingSectionAreKept() {
        Map<String, Object> bundled = new LinkedHashMap<>();
        bundled.put("privacy", new LinkedHashMap<>(Map.of("mode", "private")));

        Config config = new Config(bundled, home.resolve("absent.yaml"));

        Map<?, ?> privacy = assertInstanceOf(Map.class, config.data.get("privacy"));
        assertEquals("private", privacy.get("mode"));
        Map<?, ?> protectedRead = assertInstanceOf(Map.class, privacy.get("protected_read"));
        assertEquals(Boolean.FALSE, protectedRead.get("allow_send_to_model"));
        assertTrue(config.getReport().defaultedKeys.contains("privacy.protected_read"));
    }

    @Test
    void everyReportedDefaultIsPresentInTheEffectiveConfig() {
        Map<String, Object> bundled = new LinkedHashMap<>();
        for (String section : SECTIONS) bundled.put(section, new LinkedHashMap<>());

        Config config = new Config(bundled, home.resolve("absent.yaml"));

        List<String> defaulted = config.getReport().defaultedKeys;
        assertFalse(defaulted.isEmpty());
        for (String dotted : defaulted) {
            assertTrue(present(config.data, dotted), dotted + " is reported as defaulted but absent");
        }
    }

    @Test
    void theBundledDefaultsLeaveNothingToDefault() {
        Config config = new Config(home.resolve("absent.yaml"));

        assertEquals("classpath:config/default-config.yaml", config.getReport().loadedFrom);
        assertEquals(List.of(), config.getReport().defaultedKeys);
    }

    private static boolean present(Map<String, Object> data, String dotted) {
        Object current = data;
        for (String key : dotted.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(key)) return false;
            current = map.get(key);
        }
        return true;
    }
}
