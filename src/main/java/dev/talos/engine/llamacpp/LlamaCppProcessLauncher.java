package dev.talos.engine.llamacpp;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

interface LlamaCppProcessLauncher {
    LlamaCppProcess start(List<String> command, Path logPath) throws IOException;

    default LlamaCppProcess start(List<String> command, Path logPath, Map<String, String> environment)
            throws IOException {
        return start(command, logPath);
    }
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
