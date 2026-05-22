package dev.talos.spi;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EngineSpiConfigOwnershipTest {

    @Test
    void engineSpiUsesSpiOwnedConfigViewInsteadOfCoreConfig() throws Exception {
        String provider = Files.readString(Path.of("src/main/java/dev/talos/spi/ModelEngineProvider.java"));
        String registry = Files.readString(Path.of("src/main/java/dev/talos/core/engine/EngineRegistry.java"));
        String config = Files.readString(Path.of("src/main/java/dev/talos/core/Config.java"));
        String baseline = Files.readString(Path.of("config/architecture-boundary-baseline.txt"));

        assertTrue(Files.exists(Path.of("src/main/java/dev/talos/spi/EngineConfig.java")),
                "engine SPI should own the provider-facing config view");
        assertFalse(Files.exists(Path.of("src/main/java/dev/talos/spi/EngineRegistry.java")),
                "EngineRegistry is core orchestration, not an SPI contract");
        assertTrue(Files.exists(Path.of("src/main/java/dev/talos/core/engine/EngineRegistry.java")),
                "EngineRegistry should live with core engine orchestration");
        assertTrue(provider.contains("ModelEngine create(EngineConfig cfg)"), provider);
        assertTrue(provider.contains("ModelCatalog catalog(EngineConfig cfg)"), provider);
        assertTrue(config.contains("implements EngineConfig"), config);

        assertFalse(provider.contains("dev.talos.core.Config"), provider);
        assertTrue(registry.contains("dev.talos.core.Config"), registry);
        assertTrue(registry.contains("dev.talos.core.EngineRuntimeConfig"), registry);
        assertFalse(baseline.contains("|dev.talos.core.Config"), baseline);
        assertFalse(baseline.contains("|dev.talos.core.EngineRuntimeConfig"), baseline);
    }
}
