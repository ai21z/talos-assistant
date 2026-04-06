package dev.talos.engine.stubs.gpt4all;

import dev.talos.spi.ModelCatalog;
import dev.talos.spi.types.ModelRef;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @deprecated Stub implementation moved to engine.stubs. Not functional.
 */
@Deprecated(since = "0.1.0", forRemoval = true)
final class Gpt4AllCatalog implements ModelCatalog {
    @Override public List<ModelRef> installed() {
        String env = System.getenv("TALOS_GPT4ALL_MODELS");
        if (env == null || env.isBlank()) return List.of();
        return Arrays.stream(env.split("[,\\s]+")).filter(s -> !s.isBlank())
                .map(n -> ModelRef.of("gpt4all", n)).collect(Collectors.toList());
    }
    @Override public Optional<ModelRef> find(String name) {
        return installed().stream().filter(m -> m.name().equals(name)).findFirst();
    }
}
