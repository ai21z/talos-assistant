package dev.talos.spi;

import dev.talos.core.Config;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.EmbeddingResult;
import dev.talos.spi.types.Health;
import dev.talos.spi.types.ModelRef;
import dev.talos.spi.types.TokenChunk;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

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

    @Test
    void modelEngineProviderBridgesLegacyConfigOverloads() {
        ModelEngineProvider provider = new LegacyConfigOnlyProvider();
        EngineConfig cfg = new Config();

        assertSame(LegacyConfigOnlyProvider.ENGINE, provider.create(cfg));
        assertSame(LegacyConfigOnlyProvider.CATALOG, provider.catalog(cfg));
    }

    private static final class LegacyConfigOnlyProvider implements ModelEngineProvider {
        static final ModelEngine ENGINE = new FakeModelEngine();
        static final ModelCatalog CATALOG = new FakeModelCatalog();

        @Override
        public String id() {
            return "legacy";
        }

        @SuppressWarnings("unused")
        public ModelEngine create(Config cfg) {
            return ENGINE;
        }

        @SuppressWarnings("unused")
        public ModelCatalog catalog(Config cfg) {
            return CATALOG;
        }
    }

    private static final class FakeModelCatalog implements ModelCatalog {
        @Override
        public List<ModelRef> installed() {
            return List.of();
        }

        @Override
        public Optional<ModelRef> find(String name) {
            return Optional.empty();
        }
    }

    private static final class FakeModelEngine implements ModelEngine {
        @Override
        public String id() {
            return "legacy";
        }

        @Override
        public Capabilities caps() {
            return Capabilities.of(true, true, true, 8192);
        }

        @Override
        public Health health() {
            return Health.ok("legacy", true);
        }

        @Override
        public String chat(ChatRequest req) {
            return "";
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest req) {
            return Stream.of(TokenChunk.eos());
        }

        @Override
        public EmbeddingResult embed(List<String> texts) {
            return new EmbeddingResult(List.of(), 0);
        }
    }
}
