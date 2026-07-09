package dev.talos.engine.llamacpp;

import dev.talos.spi.EngineConfig;

import java.nio.file.Path;

/**
 * Public seams for facts the managed llama.cpp runtime owns, so evidence
 * consumers (doctor, tune) resolve them exactly as the engine does instead
 * of re-deriving their own copies.
 */
public final class LlamaCppRuntimePaths {

    private LlamaCppRuntimePaths() {}

    /**
     * The managed port exactly as the engine resolves it: explicit
     * {@code port} key, else a port embedded in {@code host}, else 8080.
     */
    public static int effectivePort(EngineConfig cfg) {
        return LlamaCppConfig.from(cfg).port();
    }

    /** Single owner of the managed server log naming convention. */
    public static Path managedLogFile(Path logDir, int port) {
        return logDir.resolve("llama_cpp-" + port + ".log");
    }
}
