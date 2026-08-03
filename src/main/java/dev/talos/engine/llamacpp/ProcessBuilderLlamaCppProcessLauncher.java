package dev.talos.engine.llamacpp;

import dev.talos.spi.types.ProviderProcessLaunchReceipt;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class ProcessBuilderLlamaCppProcessLauncher implements LlamaCppProcessLauncher {
    private static final LlamaCppProcessTerminationOwner.Policy
            FAILED_HANDOFF_TERMINATION =
            new LlamaCppProcessTerminationOwner.Policy(
                    Duration.ofMillis(250),
                    Duration.ofSeconds(2));
    private static final LlamaCppProcessTerminationOwner.Policy
            DIRECT_FORCED_TERMINATION =
            new LlamaCppProcessTerminationOwner.Policy(
                    Duration.ZERO,
                    Duration.ofSeconds(2));
    private static final LlamaCppProcessTerminationOwner.Policy
            NATURAL_EXIT_SETTLEMENT =
            new LlamaCppProcessTerminationOwner.Policy(
                    Duration.ZERO,
                    Duration.ZERO,
                    Duration.ofSeconds(2));

    @FunctionalInterface
    interface LaunchObservationFactory {
        LlamaCppProcessLaunchObservation observe(
                Process process,
                ProcessBuilder builder,
                long observedEpochMilli);
    }

    private final LaunchObservationFactory observationFactory;
    private final LlamaCppProcessTerminationOwner terminationOwner;

    ProcessBuilderLlamaCppProcessLauncher() {
        this(
                ProcessBuilderLlamaCppProcessLauncher::observeLaunch,
                LlamaCppProcessTerminationOwner.system());
    }

    ProcessBuilderLlamaCppProcessLauncher(LaunchObservationFactory observationFactory) {
        this(
                observationFactory,
                LlamaCppProcessTerminationOwner.system());
    }

    ProcessBuilderLlamaCppProcessLauncher(
            LaunchObservationFactory observationFactory,
            LlamaCppProcessTerminationOwner terminationOwner) {
        this.observationFactory = java.util.Objects.requireNonNull(
                observationFactory, "launch observation factory");
        this.terminationOwner = java.util.Objects.requireNonNull(
                terminationOwner, "process termination owner");
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
        builder.redirectOutput(ProcessBuilder.Redirect.PIPE);

        LlamaCppProcessTerminationOwner.LaunchPermit permit =
                terminationOwner.retryRetainedAndAcquire(
                        FAILED_HANDOFF_TERMINATION);
        LaunchOutputCapture outputCapture = null;
        Process process = null;
        LlamaCppProcessTerminationOwner.Lease terminationLease = null;
        try {
            outputCapture = LaunchOutputCapture.prepare(logPath);
            process = builder.start();
            terminationLease = permit.adopt(process, outputCapture);
            if (!terminationLease.owns(process, outputCapture)) {
                throw new IllegalStateException(
                        "process termination lease does not own the launched process resources");
            }
            outputCapture.start(process);
            long observed = Instant.now().toEpochMilli();
            LlamaCppProcessLaunchObservation observation = java.util.Objects.requireNonNull(
                    observationFactory.observe(process, builder, observed),
                    "launch observation");
            return new ProcessAdapter(
                    process,
                    observation,
                    terminationLease,
                    outputCapture);
        } catch (IOException startFailure) {
            settleFailedLaunch(
                    permit,
                    process,
                    outputCapture,
                    terminationLease,
                    startFailure);
            throw startFailure;
        } catch (RuntimeException | Error launchFailure) {
            settleFailedLaunch(
                    permit,
                    process,
                    outputCapture,
                    terminationLease,
                    launchFailure);
            throw launchFailure;
        } finally {
            permit.close();
        }
    }

    private static void settleFailedLaunch(
            LlamaCppProcessTerminationOwner.LaunchPermit permit,
            Process process,
            LaunchOutputCapture outputCapture,
            LlamaCppProcessTerminationOwner.Lease knownLease,
            Throwable launchFailure) {
        if (process == null) {
            if (outputCapture != null) {
                try {
                    outputCapture.settle(Duration.ZERO);
                } catch (RuntimeException cleanupFailure) {
                    launchFailure.addSuppressed(cleanupFailure);
                }
            }
            return;
        }

        LlamaCppProcessTerminationOwner.ResourceSettlement resources =
                outputCapture == null
                        ? LlamaCppProcessTerminationOwner.ResourceSettlement.NONE
                        : outputCapture;
        LlamaCppProcessTerminationOwner.Lease rescueLease = knownLease;
        if (rescueLease == null) rescueLease = permit.adoptedLease();
        if (rescueLease == null) {
            try {
                rescueLease = permit.adopt(process, resources);
            } catch (RuntimeException | Error rescueFailure) {
                rescueFailure.addSuppressed(launchFailure);
                throw rescueFailure;
            }
        }
        if (!rescueLease.owns(process, resources)) {
            throw new IllegalStateException(
                    "failed launch retained a lease for different process resources",
                    launchFailure);
        }
        LlamaCppProcessTerminationOwner.Outcome outcome =
                rescueLease.terminate(FAILED_HANDOFF_TERMINATION);
        if (!outcome.terminated()) {
            throw new LlamaCppProcessTerminationOwner.Unsettled(
                    "launched llama.cpp process termination is unproven after launch failure",
                    launchFailure,
                    outcome,
                    rescueLease);
        }
    }

    @FunctionalInterface
    interface OutputSinkFactory {
        OutputStream open() throws IOException;
    }

    @FunctionalInterface
    interface CaptureThreadFactory {
        Thread unstarted(String name, Runnable task);
    }

    @FunctionalInterface
    interface CaptureAttemptCompletion {
        CaptureAttemptCompletion NONE = () -> {};

        void beforeComplete();
    }

    static final class LaunchOutputCapture
            implements LlamaCppProcessTerminationOwner.ResourceSettlement {
        private final OutputSinkFactory sinkFactory;
        private final CaptureThreadFactory threadFactory;
        private final CaptureAttemptCompletion attemptCompletion;
        private final CountDownLatch pumpDone = new CountDownLatch(1);
        private final AtomicReference<Throwable> captureFailure =
                new AtomicReference<>();
        private final AtomicReference<Throwable> resourceFailure =
                new AtomicReference<>();
        private volatile boolean started;
        private volatile boolean captureComplete;
        private volatile boolean sourceClosed = true;
        private volatile boolean sinkClosed = true;
        private InputStream source;
        private OutputStream sink;
        private CountDownLatch closeAttempt;

        LaunchOutputCapture(OutputSinkFactory sinkFactory) {
            this(
                    sinkFactory,
                    (name, task) -> Thread.ofPlatform()
                            .daemon(true)
                            .name(name)
                            .unstarted(task),
                    CaptureAttemptCompletion.NONE);
        }

        LaunchOutputCapture(
                OutputSinkFactory sinkFactory,
                CaptureThreadFactory threadFactory) {
            this(sinkFactory, threadFactory, CaptureAttemptCompletion.NONE);
        }

        LaunchOutputCapture(
                OutputSinkFactory sinkFactory,
                CaptureThreadFactory threadFactory,
                CaptureAttemptCompletion attemptCompletion) {
            this.sinkFactory = java.util.Objects.requireNonNull(
                    sinkFactory, "launch output sink factory");
            this.threadFactory = java.util.Objects.requireNonNull(
                    threadFactory, "launch output thread factory");
            this.attemptCompletion = java.util.Objects.requireNonNull(
                    attemptCompletion, "capture attempt completion");
        }

        static LaunchOutputCapture prepare(Path logPath) {
            return new LaunchOutputCapture(() -> logPath == null
                    ? OutputStream.nullOutputStream()
                    : Files.newOutputStream(
                            logPath,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.APPEND));
        }

        synchronized void start(Process process) throws IOException {
            if (started || pumpDone.getCount() == 0L) {
                throw new IllegalStateException(
                        "launch output capture is already started or settled");
            }
            started = true;
            try {
                source = java.util.Objects.requireNonNull(
                        process, "launched process").getInputStream();
                sourceClosed = false;
                sink = java.util.Objects.requireNonNull(
                        sinkFactory.open(), "launch output sink");
                sinkClosed = false;
                Thread pump = threadFactory.unstarted(
                        "talos-llama-output-" + process.pid(),
                        this::pump);
                pump.start();
            } catch (IOException startFailure) {
                record(captureFailure, startFailure);
                pumpDone.countDown();
                throw startFailure;
            } catch (RuntimeException | Error startFailure) {
                record(captureFailure, startFailure);
                pumpDone.countDown();
                throw startFailure;
            }
        }

        private void pump() {
            try {
                source.transferTo(sink);
                sink.flush();
                captureComplete = true;
            } catch (Throwable unavailable) {
                record(captureFailure, unavailable);
            } finally {
                closeOwnedHandles();
                pumpDone.countDown();
            }
        }

        @Override
        public boolean settle(Duration timeout) {
            if (!started) pumpDone.countDown();
            boolean interrupted = false;
            long deadline = deadlineAfter(timeout);
            AwaitResult pump = await(pumpDone, deadline);
            interrupted |= pump.interrupted();
            if (!pump.completed()) {
                restoreBeforeFailure(interrupted);
                throw new IllegalStateException(
                        "launch output pump settlement timed out");
            }

            CountDownLatch retry = requestCloseAttempt();
            AwaitResult close = await(retry, deadline);
            interrupted |= close.interrupted();
            if (!close.completed()) {
                restoreBeforeFailure(interrupted);
                throw new IllegalStateException(
                        "launch output handle closure timed out",
                        resourceFailure.get());
            }
            if (!handlesClosed()) {
                restoreBeforeFailure(interrupted);
                throw new IllegalStateException(
                        "launch output handles remain unsettled",
                        resourceFailure.get());
            }
            return interrupted;
        }

        @Override
        public boolean ownsProcessStdout() {
            return true;
        }

        boolean healthy() {
            return captureFailure.get() == null
                    && (pumpDone.getCount() != 0L || captureComplete);
        }

        boolean captureComplete() {
            return captureComplete;
        }

        boolean sourceClosed() {
            return sourceClosed;
        }

        boolean sinkClosed() {
            return sinkClosed;
        }

        Throwable captureFailure() {
            return captureFailure.get();
        }

        private CountDownLatch requestCloseAttempt() {
            synchronized (this) {
                if (closeAttempt != null && closeAttempt.getCount() != 0L) {
                    return closeAttempt;
                }
                if (handlesClosed()) return new CountDownLatch(0);
                CountDownLatch exact = new CountDownLatch(1);
                closeAttempt = exact;
                try {
                    Thread closer = threadFactory.unstarted(
                            "talos-llama-output-close",
                            () -> {
                                try {
                                    closeOwnedHandles();
                                } finally {
                                    try {
                                        attemptCompletion.beforeComplete();
                                    } finally {
                                        exact.countDown();
                                    }
                                }
                            });
                    closer.start();
                } catch (RuntimeException | Error startFailure) {
                    record(resourceFailure, startFailure);
                    exact.countDown();
                }
                return exact;
            }
        }

        private void closeOwnedHandles() {
            InputStream exactSource;
            OutputStream exactSink;
            synchronized (this) {
                exactSource = sourceClosed ? null : source;
                exactSink = sinkClosed ? null : sink;
            }
            if (exactSource != null) {
                try {
                    exactSource.close();
                    sourceClosed = true;
                } catch (Throwable closeFailure) {
                    record(resourceFailure, closeFailure);
                }
            }
            if (exactSink != null) {
                try {
                    exactSink.close();
                    sinkClosed = true;
                } catch (Throwable closeFailure) {
                    record(resourceFailure, closeFailure);
                }
            }
        }

        private boolean handlesClosed() {
            return sourceClosed && sinkClosed;
        }

        private static AwaitResult await(CountDownLatch latch, long deadline) {
            boolean interrupted = false;
            boolean completed = latch.getCount() == 0L;
            while (!completed) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) break;
                try {
                    completed = latch.await(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException stop) {
                    interrupted = true;
                    Thread.interrupted();
                }
            }
            if (!completed && latch.getCount() == 0L) {
                while (!completed) {
                    try {
                        latch.await();
                        completed = true;
                    } catch (InterruptedException stop) {
                        interrupted = true;
                        Thread.interrupted();
                    }
                }
            }
            return new AwaitResult(completed, interrupted);
        }

        private record AwaitResult(boolean completed, boolean interrupted) {}

        private static void record(
                AtomicReference<Throwable> destination,
                Throwable failure) {
            Throwable exact = java.util.Objects.requireNonNull(
                    failure, "capture failure");
            Throwable previous = destination.get();
            if (previous == null && destination.compareAndSet(null, exact)) return;
            previous = destination.get();
            if (previous != null && previous != exact) {
                synchronized (previous) {
                    previous.addSuppressed(exact);
                }
            }
        }

        private static void restoreBeforeFailure(boolean interrupted) {
            if (interrupted) Thread.currentThread().interrupt();
        }

        private static long deadlineAfter(Duration timeout) {
            Duration exact = timeout == null ? Duration.ZERO : timeout;
            long now = System.nanoTime();
            long nanos;
            try {
                nanos = exact.toNanos();
            } catch (ArithmeticException overflow) {
                return Long.MAX_VALUE;
            }
            if (nanos <= 0L) return now;
            long deadline = now + nanos;
            return deadline < 0L || deadline < now
                    ? Long.MAX_VALUE
                    : deadline;
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

    static LlamaCppProcessLaunchObservation observeLaunch(
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

    private record ProcessAdapter(
            Process process,
            LlamaCppProcessLaunchObservation launchObservation,
            LlamaCppProcessTerminationOwner.Lease terminationLease,
            LaunchOutputCapture outputCapture)
            implements LlamaCppProcess {
        @Override public boolean isAlive() { return process.isAlive(); }
        @Override public boolean outputCaptureHealthy() {
            return outputCapture.healthy();
        }
        @Override public void destroy() {
            requireTerminated(
                    terminationLease.terminate(FAILED_HANDOFF_TERMINATION));
        }
        @Override public boolean waitFor(Duration timeout) throws InterruptedException {
            long millis = timeout == null ? 0L : Math.max(1L, timeout.toMillis());
            if (!process.waitFor(millis, TimeUnit.MILLISECONDS)) return false;
            LlamaCppProcessTerminationOwner.Outcome outcome =
                    terminationLease.terminate(NATURAL_EXIT_SETTLEMENT);
            if (!outcome.terminated()) {
                throw new LlamaCppProcessTerminationOwner.Unsettled(
                        "naturally exited llama.cpp process ownership "
                                + "remains unproven",
                        null,
                        outcome,
                        terminationLease);
            }
            return true;
        }
        @Override public void destroyForcibly() {
            requireTerminated(
                    terminationLease.terminate(DIRECT_FORCED_TERMINATION));
        }

        private void requireTerminated(
                LlamaCppProcessTerminationOwner.Outcome outcome) {
            if (!outcome.terminated()) {
                throw new LlamaCppProcessTerminationOwner.Unsettled(
                        "launched llama.cpp process termination is unproven",
                        null,
                        outcome,
                        terminationLease);
            }
        }
    }
}
