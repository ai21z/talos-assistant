package dev.talos.engine.llamacpp;

import dev.talos.spi.types.ProviderProcessLaunchReceipt;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Explicit synthetic process/listener identity for tests whose HTTP server runs in the test JVM. */
final class SyntheticManagedServer {
    static final LlamaCppEndpointOwnerProbe.ObserverIdentity OBSERVER =
            new LlamaCppEndpointOwnerProbe.ObserverIdentity(
                    ProviderProcessLaunchReceipt.EndpointObserverKind.LINUX_PROCFS_MANAGED_PROCESS_TREE_V1,
                    "a".repeat(64));
    static final LlamaCppEndpointOwnerProbe.Probe OWNER = (host, port, pid, started, timeout) ->
            pid == 0 ? LlamaCppEndpointOwnerProbe.Observation.absent(OBSERVER)
                    : LlamaCppEndpointOwnerProbe.Observation.exact(pid, started, OBSERVER);

    static LlamaCppServerManager manager(LlamaCppConfig config, LlamaCppProcessLauncher launcher, HttpClient http) {
        return manager(config, launcher, http, Duration.ofMinutes(2), Duration.ofMillis(500), null);
    }

    static LlamaCppServerManager manager(LlamaCppConfig config, LlamaCppProcessLauncher launcher, HttpClient http,
                                        Duration timeout, Duration poll, Path logs) {
        return new LlamaCppServerManager(config, observedLauncher(launcher), http, timeout, poll, logs, OWNER);
    }

    static LlamaCppProcessLauncher observedLauncher(LlamaCppProcessLauncher delegate) {
        return new LlamaCppProcessLauncher() {
            public LlamaCppProcess start(List<String> command, Path log) throws IOException {
                return start(command, log, Map.of());
            }
            public LlamaCppProcess start(List<String> command, Path log, Map<String, String> environment)
                    throws IOException {
                LlamaCppProcess child = delegate.start(command, log, environment);
                if (child == null) return null;
                LlamaCppProcessLaunchObservation observation = new LlamaCppProcessLaunchObservation(
                        1L, 1L, 2L, UUID.randomUUID().toString().replace("-", ""),
                        "b".repeat(64), "c".repeat(64));
                return new LlamaCppProcess() {
                    public boolean isAlive() { return child.isAlive(); }
                    public void destroy() { child.destroy(); }
                    public void destroyForcibly() { child.destroyForcibly(); }
                    public boolean waitFor(Duration timeout) throws InterruptedException { return child.waitFor(timeout); }
                    public boolean outputCaptureHealthy() { return child.outputCaptureHealthy(); }
                    public boolean suppressOutputRetention() { return child.suppressOutputRetention(); }
                    public LlamaCppProcessTerminationOwner.Lease terminationLease() { return child.terminationLease(); }
                    public LlamaCppProcessLaunchObservation launchObservation() { return observation; }
                };
            }
        };
    }
}
