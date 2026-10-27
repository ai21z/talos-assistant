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

    /**
     * Starts a qualification child with exactly the supplied environment.
     * Production launchers must clear inherited entries before applying the
     * canonical qualification allowlist. Windows callers must include the
     * observed {@code SYSTEMROOT}; the JDK injects it when omitted.
     */
    default LlamaCppProcess startWithExactEnvironment(
            List<String> command,
            Path logPath,
            Map<String, String> environment) throws IOException {
        throw new UnsupportedOperationException(
                "exact-environment process launch is not implemented");
    }

    /** Starts an exact-environment qualification child in the supplied directory. */
    default LlamaCppProcess startWithExactEnvironment(
            List<String> command,
            Path logPath,
            Map<String, String> environment,
            Path workingDirectory) throws IOException {
        throw new UnsupportedOperationException(
                "exact-environment working-directory process launch is not implemented");
    }
}

interface LlamaCppProcess {
    boolean isAlive();
    void destroy();
    boolean outputCaptureHealthy();
    /** Raw output retention is disabled irreversibly before restricted dispatch. */
    default boolean suppressOutputRetention() { return false; }
    default LlamaCppProcessLaunchObservation launchObservation() { return null; }
    default LlamaCppProcessTerminationOwner.Lease terminationLease() { return null; }
    default boolean waitFor(Duration timeout) throws InterruptedException {
        return !isAlive();
    }
    default void destroyForcibly() {
        destroy();
    }
}
