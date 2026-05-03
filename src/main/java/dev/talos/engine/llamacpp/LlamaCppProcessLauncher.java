package dev.talos.engine.llamacpp;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

interface LlamaCppProcessLauncher {
    LlamaCppProcess start(List<String> command, Path logPath) throws IOException;
}

interface LlamaCppProcess {
    boolean isAlive();
    void destroy();
}
