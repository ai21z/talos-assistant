package dev.loqj.spi;

import dev.loqj.spi.types.ModelRef;
import java.util.List;
import java.util.Optional;

public interface ModelCatalog {
    List<ModelRef> installed();
    Optional<ModelRef> find(String name);
}
