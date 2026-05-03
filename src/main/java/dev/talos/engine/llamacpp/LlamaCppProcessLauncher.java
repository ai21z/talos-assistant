package dev.talos.engine.llamacpp;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

interface LlamaCppProcessLauncher {
    LlamaCppProcess start(List<String> command, Path logPath) throws IOException;
}

interface LlamaCppProcess {
    boolean isAlive();
    void destroy();
    default boolean waitFor(Duration timeout) throws InterruptedException {
        return !isAlive();
    }
    default void destroyForcibly() {
        destroy();
    }
}
