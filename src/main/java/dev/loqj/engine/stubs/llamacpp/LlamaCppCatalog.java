package dev.loqj.engine.llamacpp;

import dev.loqj.spi.ModelCatalog;
import dev.loqj.spi.types.ModelRef;
import java.util.*;
import java.util.stream.Collectors;

final class LlamaCppCatalog implements ModelCatalog {
    @Override public List<ModelRef> installed() {
        // optional: models from env (space/comma-separated)
        String env = System.getenv("LOQJ_LLAMACPP_MODELS");
        if (env == null || env.isBlank()) return List.of();
        return Arrays.stream(env.split("[,\\s]+")).filter(s -> !s.isBlank())
                .map(n -> ModelRef.of("llamacpp", n)).collect(Collectors.toList());
    }
    @Override public Optional<ModelRef> find(String name) {
        return installed().stream().filter(m -> m.name().equals(name)).findFirst();
    }
}
