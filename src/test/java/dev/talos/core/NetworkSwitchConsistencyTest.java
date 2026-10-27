package dev.talos.core;

import dev.talos.core.net.NetPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every reader of net.enabled agrees, and a value Talos does not recognize turns the network off. */
class NetworkSwitchConsistencyTest {
    private static final Object MISSING = new Object();

    @TempDir
    Path home;

    static Stream<Arguments> values() {
        return Stream.of(
                Arguments.of("boolean true", Boolean.TRUE, true),
                Arguments.of("boolean false", Boolean.FALSE, false),
                Arguments.of("string true", "true", true),
                Arguments.of("string false", "false", false),
                Arguments.of("quoted string off", "off", false),
                Arguments.of("string no", "no", false),
                Arguments.of("string yes", "yes", true),
                Arguments.of("numeric override 0", 0L, false),
                Arguments.of("numeric override 1 is not a boolean", 1L, false),
                Arguments.of("unrecognized string", "maybe", false),
                Arguments.of("unrecognized number", 2L, false),
                Arguments.of("missing", MISSING, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("values")
    void everyReaderOfTheNetworkSwitchAgrees(String label, Object value, boolean expected) {
        Config cfg = new Config(null);
        Map<String, Object> net = new LinkedHashMap<>();
        if (value != MISSING) net.put("enabled", value);
        cfg.data.put("net", net);

        assertReadersAgree(cfg, expected);
    }

    @Test
    void unquotedOffInTheUserConfigIsReadAsABooleanByEveryReader() throws Exception {
        Path userConfig = home.resolve("config.yaml");
        Files.writeString(userConfig, "net:\n  enabled: off\n");

        Config cfg = new Config(userConfig);

        assertTrue(cfg.getReport().userConfigLoaded, cfg.getReport().userConfigError);
        assertReadersAgree(cfg, false);
    }

    @Test
    void quotedFalseInTheUserConfigTurnsTheNetworkOffForEveryReader() throws Exception {
        Path userConfig = home.resolve("config.yaml");
        Files.writeString(userConfig, "net:\n  enabled: \"false\"\n");

        Config cfg = new Config(userConfig);

        assertTrue(cfg.getReport().userConfigLoaded, cfg.getReport().userConfigError);
        assertEquals("false", ((java.util.Map<?, ?>) cfg.data.get("net")).get("enabled"));
        assertReadersAgree(cfg, false);
    }

    private static void assertReadersAgree(Config cfg, boolean expected) {
        assertEquals(expected, cfg.view().net().enabled(), "ConfigView");
        assertEquals(expected, new NetPolicy(cfg).enabled, "NetPolicy");
        String label = EngineRuntimeConfig.from(cfg).policyLabel();
        assertTrue(label.startsWith(expected ? "network on" : "network off"), label);
    }
}
