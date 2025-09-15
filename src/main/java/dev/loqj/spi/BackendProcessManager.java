package dev.loqj.spi;

import dev.loqj.spi.types.BackendSpec;

/** Starts/stops local model processes; must enforce loopback binds. */
public interface BackendProcessManager {
    void ensureStarted(BackendSpec spec) throws Exception;
    void stop(String backendId) throws Exception;
}
