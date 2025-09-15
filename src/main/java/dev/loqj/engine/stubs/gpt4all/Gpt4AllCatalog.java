package dev.loqj.engine.gpt4all;

import dev.loqj.spi.ModelCatalog;
import dev.loqj.spi.types.ModelRef;
import java.util.*;
import java.util.stream.Collectors;

final class Gpt4AllCatalog implements ModelCatalog {
    @Override public List<ModelRef> installed() {
        String env = System.getenv("LOQJ_GPT4ALL_MODELS");
        if (env == null || env.isBlank()) return List.of();
        return Arrays.stream(env.split("[,\\s]+")).filter(s -> !s.isBlank())
                .map(n -> ModelRef.of("gpt4all", n)).collect(Collectors.toList());
    }
    @Override public Optional<ModelRef> find(String name) {
        return installed().stream().filter(m -> m.name().equals(name)).findFirst();
    }
}
