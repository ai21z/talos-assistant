package dev.talos.engine.llamacpp;

import java.io.IOException;
import java.util.List;

interface LlamaCppProcessLauncher {
    LlamaCppProcess start(List<String> command) throws IOException;
}

interface LlamaCppProcess {
    boolean isAlive();
    void destroy();
}
