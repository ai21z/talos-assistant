package dev.talos.spi;

import java.util.Map;

/** Provider-facing read-only view of Talos engine configuration. */
public interface EngineConfig {
    Map<String, Object> data();

    static EngineConfig empty() {
        return Map::of;
    }
}
