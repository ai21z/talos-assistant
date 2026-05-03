package dev.talos.engine.llamacpp;

import java.io.IOException;
import java.util.List;

final class ProcessBuilderLlamaCppProcessLauncher implements LlamaCppProcessLauncher {
    @Override
    public LlamaCppProcess start(List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        return new ProcessAdapter(process);
    }

    private record ProcessAdapter(Process process) implements LlamaCppProcess {
        @Override public boolean isAlive() { return process.isAlive(); }
        @Override public void destroy() { process.destroy(); }
    }
}
