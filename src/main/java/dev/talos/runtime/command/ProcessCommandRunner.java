package dev.talos.runtime.command;

import dev.talos.runtime.TurnFailureClassifier;
import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.safety.SafeLogFormatter;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded argv-only process runner. This class is internal and not a tool surface. */
public final class ProcessCommandRunner implements CommandRunner {
    @FunctionalInterface
    interface ProcessStarter {
        Process start(ProcessBuilder builder) throws Exception;
    }

    private final ProcessStarter processStarter;
    private final long drainTimeoutMs;

    public ProcessCommandRunner() {
        this(ProcessBuilder::start);
    }

    ProcessCommandRunner(ProcessStarter processStarter) {
        this(processStarter, 5_000);
    }

    ProcessCommandRunner(ProcessStarter processStarter, long drainTimeoutMs) {
        this.processStarter = processStarter == null ? ProcessBuilder::start : processStarter;
        if (drainTimeoutMs <= 0) throw new IllegalArgumentException("drain timeout must be positive");
        this.drainTimeoutMs = drainTimeoutMs;
    }

    @Override
    public CommandResult run(CommandPlan plan) {
        long start = System.nanoTime();
        if (plan == null) {
            return CommandResult.internalFailure(null, 0, "Command plan is required.");
        }
        // ExecutorService.close() waits without a deadline, even after shutdownNow().
        // Pipe reads and even close() can ignore interruption. Keep stream ownership
        // explicit and allow bounded cleanup on daemon workers, never on the caller.
        var executor = Executors.newFixedThreadPool(5,
                Thread.ofPlatform().daemon(true).name("talos-command-io-", 0).factory());
        Process process = null;
        List<Closeable> streams = new ArrayList<>();
        List<Future<?>> captures = new ArrayList<>();
        CommandResult result;
        boolean settled;
        boolean timedOut = false;
        boolean killed = false;
        try {
            List<String> command = new ArrayList<>();
            command.add(plan.executable());
            command.addAll(plan.argv());

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(plan.cwd().toFile());
            configureEnvironment(builder.environment());

            process = processStarter.start(builder);
            InputStream stdoutStream = process.getInputStream();
            InputStream stderrStream = process.getErrorStream();
            var stdinStream = process.getOutputStream();
            streams.add(stdoutStream);
            streams.add(stderrStream);
            streams.add(stdinStream);
            var stdoutFuture = executor.submit(captureTask(
                    stdoutStream, plan.outputLimits().stdoutLimitBytes()));
            captures.add(stdoutFuture);
            var stderrFuture = executor.submit(captureTask(
                    stderrStream, plan.outputLimits().stderrLimitBytes()));
            captures.add(stderrFuture);
            // Profiles do not accept interactive stdin. EOF must not wait until timeout.
            executor.submit(() -> { stdinStream.close(); return null; })
                    .get(drainTimeoutMs, TimeUnit.MILLISECONDS);

            boolean finished = process.waitFor(plan.timeoutMs(), TimeUnit.MILLISECONDS);
            timedOut = !finished;
            int exitCode;
            if (!finished) {
                killed = true;
                destroyProcessTree(process);
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    throw new java.io.IOException("Command process termination did not settle");
                }
                exitCode = -1;
            } else {
                exitCode = process.exitValue();
            }

            long drainDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(drainTimeoutMs);
            Capture stdout = stdoutFuture.get(Math.max(1, drainDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            Capture stderr = stderrFuture.get(Math.max(1, drainDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            long durationMs = elapsedMs(start);
            result = new CommandResult(
                    plan,
                    exitCode,
                    durationMs,
                    !finished,
                    killed,
                    stdout.text(),
                    stderr.text(),
                    stdout.truncated(),
                    stderr.truncated(),
                    stdout.redacted() || stderr.redacted(),
                    "");
        } catch (Exception e) {
            destroyProcessTree(process);
            if (TurnFailureClassifier.isCancellation(e)
                    || TurnFailureClassifier.isInterruption(e)) {
                throw TurnFailureClassifier.propagateControlFlow(e);
            }
            result = new CommandResult(plan, -1, elapsedMs(start), timedOut, killed,
                    "", "", false, false, false,
                    "Command execution failed: " + e.getClass().getSimpleName() + ": "
                            + SafeLogFormatter.throwableMessage(e)
                            + "; process-tree quiescence is unconfirmed.");
        } finally {
            captures.forEach(future -> future.cancel(true));
            settled = settleStreams(executor, streams);
        }
        if (!settled) {
            return new CommandResult(plan, -1, elapsedMs(start), timedOut, killed,
                    "", "", false, false, false,
                    "Command stream cleanup did not settle; process-tree quiescence is unconfirmed.");
        }
        return new CommandResult(result.plan(), result.exitCode(), elapsedMs(start),
                result.timedOut(), result.killed(), result.stdout(), result.stderr(),
                result.stdoutTruncated(), result.stderrTruncated(), result.redactionApplied(), result.errorMessage());
    }

    private static boolean settleStreams(ExecutorService executor, List<Closeable> streams) {
        AtomicBoolean closeFailed = new AtomicBoolean();
        for (Closeable stream : streams) {
            executor.submit(() -> {
                try { stream.close(); }
                catch (Exception ignored) { closeFailed.set(true); }
            });
        }
        executor.shutdown();
        try {
            return executor.awaitTermination(1, TimeUnit.SECONDS) && !closeFailed.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            executor.shutdownNow();
        }
    }

    private static void destroyProcessTree(Process process) {
        if (process == null) return;
        List<BoundDescendant> descendants = List.of();
        try {
            // A numeric parent PID is not an ownership lease after Process exit.
            // In particular, Windows may retain stale parent IDs in later snapshots.
            if (process.isAlive()) {
                ProcessHandle root = process.toHandle();
                Instant rootStart = root.info().startInstant().orElse(null);
                if (rootStart != null && root.isAlive()) {
                    List<BoundDescendant> snapshot;
                    try (var handles = process.descendants()) {
                        snapshot = handles.map(handle -> bindDescendant(handle, root, rootStart))
                                .filter(java.util.Objects::nonNull).toList();
                    }
                    // Discard the entire newly discovered set if the owned Process
                    // exited during discovery, even if its PID now names a live process.
                    if (process.isAlive() && root.isAlive()
                            && root.info().startInstant().filter(rootStart::equals).isPresent()) {
                        descendants = snapshot;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Unknown descendant ownership is not permission to terminate it.
        }
        descendants.forEach(ProcessCommandRunner::destroyForcibly);
        try {
            if (process.isAlive()) process.destroyForcibly();
        } catch (RuntimeException ignored) {
            // Do not replace the original command failure with cleanup failure.
        }
    }

    private static BoundDescendant bindDescendant(ProcessHandle handle, ProcessHandle root, Instant rootStart) {
        if (handle == null || handle.pid() == root.pid() || !handle.isAlive()) return null;
        Instant started = handle.info().startInstant().orElse(null);
        return started == null || started.isBefore(rootStart) ? null : new BoundDescendant(handle, started);
    }

    private static void destroyForcibly(BoundDescendant descendant) {
        try {
            ProcessHandle handle = descendant.handle();
            if (handle.isAlive()
                    && handle.info().startInstant().filter(descendant.started()::equals).isPresent()) {
                handle.destroyForcibly();
            }
        } catch (RuntimeException ignored) {
            // Best effort continues across the remaining captured descendants.
        }
    }

    private record BoundDescendant(ProcessHandle handle, Instant started) {}

    private static void configureEnvironment(Map<String, String> environment) {
        environment.clear();
        environment.putAll(filteredEnvironment(System.getenv(), CommandRuntimePlatform.current()));
    }

    static Map<String, String> filteredEnvironment(
            Map<String, String> source,
            CommandRuntimePlatform platform
    ) {
        Map<String, String> filtered = new LinkedHashMap<>();
        if (source == null) {
            return filtered;
        }
        CommandRuntimePlatform effectivePlatform = platform == null
                ? CommandRuntimePlatform.current()
                : platform;
        for (String key : effectivePlatform.environmentAllowlist()) {
            String value = source.get(key);
            if (value != null && !value.isBlank()) {
                filtered.put(key, value);
            }
        }
        return Map.copyOf(filtered);
    }

    private static Callable<Capture> captureTask(InputStream stream, int limitBytes) {
        return () -> capture(stream, limitBytes);
    }

    private static Capture capture(InputStream stream, int limitBytes) throws Exception {
        int limit = limitBytes > 0 ? limitBytes : CommandOutputLimits.DEFAULT_STREAM_LIMIT_BYTES;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(limit, 4096));
        boolean truncated = false;
        int next;
        while ((next = stream.read()) >= 0) {
            if (bytes.size() < limit) {
                bytes.write(next);
            } else {
                truncated = true;
            }
        }
        String raw = bytes.toString(StandardCharsets.UTF_8);
        Redaction redaction = redact(raw);
        return new Capture(redaction.text(), truncated, redaction.redacted());
    }

    private static Redaction redact(String value) {
        if (value == null || value.isBlank()) return new Redaction("", false);
        String redacted = ProtectedContentPolicy.sanitizeText(value);
        return new Redaction(redacted, !redacted.equals(value));
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private record Capture(String text, boolean truncated, boolean redacted) {
        Capture {
            text = text == null ? "" : text;
        }
    }

    private record Redaction(String text, boolean redacted) {
        Redaction {
            text = text == null ? "" : text;
        }
    }
}
