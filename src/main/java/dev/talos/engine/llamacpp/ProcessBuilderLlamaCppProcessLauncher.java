package dev.talos.engine.llamacpp;

import dev.talos.spi.types.ProviderProcessLaunchReceipt;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class ProcessBuilderLlamaCppProcessLauncher implements LlamaCppProcessLauncher {
    @FunctionalInterface
    interface LaunchObservationFactory {
        LlamaCppProcessLaunchObservation observe(
                Process process,
                ProcessBuilder builder,
                long observedEpochMilli);
    }

    private final LaunchObservationFactory observationFactory;

    ProcessBuilderLlamaCppProcessLauncher() {
        this(ProcessBuilderLlamaCppProcessLauncher::observeLaunch);
    }

    ProcessBuilderLlamaCppProcessLauncher(LaunchObservationFactory observationFactory) {
        this.observationFactory = java.util.Objects.requireNonNull(
                observationFactory, "launch observation factory");
    }

    @Override
    public LlamaCppProcess start(List<String> command, Path logPath) throws IOException {
        return start(command, logPath, Map.of());
    }

    @Override
    public LlamaCppProcess start(List<String> command, Path logPath, Map<String, String> environment)
            throws IOException {
        return start(command, logPath, environment, false, null);
    }

    @Override
    public LlamaCppProcess startWithExactEnvironment(
            List<String> command,
            Path logPath,
            Map<String, String> environment) throws IOException {
        return start(command, logPath, environment, true, null);
    }

    @Override
    public LlamaCppProcess startWithExactEnvironment(
            List<String> command,
            Path logPath,
            Map<String, String> environment,
            Path workingDirectory) throws IOException {
        return start(command, logPath, environment, true, workingDirectory);
    }

    private LlamaCppProcess start(
            List<String> command,
            Path logPath,
            Map<String, String> environment,
            boolean exactEnvironment,
            Path workingDirectory) throws IOException {
        if (exactEnvironment) requireCanonicalQualificationEnvironment(environment);
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) builder.directory(workingDirectory.toFile());
        if (exactEnvironment) builder.environment().clear();
        if (environment != null && !environment.isEmpty()) {
            builder.environment().putAll(environment);
        }
        builder.redirectErrorStream(true);
        if (logPath != null) {
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()));
        }
        Process process = builder.start();
        try {
            long observed = Instant.now().toEpochMilli();
            LlamaCppProcessLaunchObservation observation = java.util.Objects.requireNonNull(
                    observationFactory.observe(process, builder, observed),
                    "launch observation");
            return new ProcessAdapter(process, observation);
        } catch (RuntimeException | Error observationFailure) {
            settleUnownedChild(process);
            throw observationFailure;
        }
    }

    private static void requireCanonicalQualificationEnvironment(
            Map<String, String> environment) {
        Map<String, String> supplied = java.util.Objects.requireNonNull(
                environment, "exact qualification environment");
        Map<String, String> projected = ProviderProcessLaunchReceipt
                .qualificationEffectiveEnvironment(supplied);
        if (!projected.equals(supplied)) {
            throw new IllegalArgumentException(
                    "exact qualification environment is not the canonical closed allowlist");
        }
        if (ProviderProcessLaunchReceipt.detectEnvironmentKeySemantics()
                        == ProviderProcessLaunchReceipt.EnvironmentKeySemantics.CASE_INSENSITIVE
                && (!supplied.containsKey("SYSTEMROOT")
                    || supplied.get("SYSTEMROOT").isBlank())) {
            throw new IllegalArgumentException(
                    "exact Windows qualification environment requires SYSTEMROOT");
        }
    }

    private static LlamaCppProcessLaunchObservation observeLaunch(
            Process process,
            ProcessBuilder builder,
            long observedEpochMilli) {
        long started = process.toHandle().info().startInstant()
                .map(Instant::toEpochMilli)
                .orElse(0L);
        long observed = Math.max(observedEpochMilli, started);
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        return new LlamaCppProcessLaunchObservation(
                process.pid(),
                started,
                observed,
                HexFormat.of().formatHex(nonce),
                ProviderProcessLaunchReceipt.digestOrderedValues(builder.command()),
                ProviderProcessLaunchReceipt.digestEffectiveEnvironment(builder.environment()));
    }

    private static void settleUnownedChild(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(250L, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(2L, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private record ProcessAdapter(
            Process process,
            LlamaCppProcessLaunchObservation launchObservation) implements LlamaCppProcess {
        @Override public boolean isAlive() { return process.isAlive(); }
        @Override public void destroy() { process.destroy(); }
        @Override public boolean waitFor(Duration timeout) throws InterruptedException {
            long millis = timeout == null ? 0L : Math.max(1L, timeout.toMillis());
            return process.waitFor(millis, TimeUnit.MILLISECONDS);
        }
        @Override public void destroyForcibly() { process.destroyForcibly(); }
    }
}
