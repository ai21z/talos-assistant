package dev.talos.runtime.command;

import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.safety.SafeLogFormatter;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Bounded argv-only process runner. This class is internal and not a tool surface. */
public final class ProcessCommandRunner implements CommandRunner {
    @Override
    public CommandResult run(CommandPlan plan) {
        long start = System.nanoTime();
        if (plan == null) {
            return CommandResult.internalFailure(null, 0, "Command plan is required.");
        }
        // T752: try-with-resources (Java 21 AutoCloseable ExecutorService)
        // with the deliberate shutdownNow() in finally preserved - close()
        // then only awaits the already-interrupted capture tasks briefly,
        // keeping the aggressive timeout-kill semantics intact.
        try (var executor = Executors.newFixedThreadPool(2)) {
        Process process = null;
        try {
            List<String> command = new ArrayList<>();
            command.add(plan.executable());
            command.addAll(plan.argv());

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(plan.cwd().toFile());
            configureEnvironment(builder.environment());

            process = builder.start();
            Process started = process;
            var stdoutFuture = executor.submit(captureTask(
                    started.getInputStream(), plan.outputLimits().stdoutLimitBytes()));
            var stderrFuture = executor.submit(captureTask(
                    started.getErrorStream(), plan.outputLimits().stderrLimitBytes()));

            boolean finished = process.waitFor(plan.timeoutMs(), TimeUnit.MILLISECONDS);
            boolean killed = false;
            int exitCode;
            if (!finished) {
                killed = true;
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                exitCode = -1;
            } else {
                exitCode = process.exitValue();
            }

            Capture stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
            Capture stderr = stderrFuture.get(5, TimeUnit.SECONDS);
            long durationMs = elapsedMs(start);
            return new CommandResult(
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
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            return CommandResult.internalFailure(
                    plan,
                    elapsedMs(start),
                    "Command execution failed: " + e.getClass().getSimpleName() + ": "
                            + SafeLogFormatter.throwableMessage(e));
        } finally {
            executor.shutdownNow();
        }
        }
    }

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
