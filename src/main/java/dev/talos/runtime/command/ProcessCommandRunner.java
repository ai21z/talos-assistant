package dev.talos.runtime.command;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Bounded argv-only process runner. This class is internal and not a tool surface. */
public final class ProcessCommandRunner implements CommandRunner {
    private static final List<String> ENV_ALLOWLIST = List.of(
            "SystemRoot", "WINDIR", "ComSpec", "PATHEXT", "TEMP", "TMP", "JAVA_HOME", "PATH");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b([A-Z0-9_]*(?:SECRET|TOKEN|PASSWORD|CREDENTIAL|AUTH|KEY)[A-Z0-9_]*)=([^\\s\\r\\n]+)");

    @Override
    public CommandResult run(CommandPlan plan) {
        long start = System.nanoTime();
        if (plan == null) {
            return CommandResult.internalFailure(null, 0, "Command plan is required.");
        }
        var executor = Executors.newFixedThreadPool(2);
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
                    "Command execution failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            executor.shutdownNow();
        }
    }

    private static void configureEnvironment(Map<String, String> environment) {
        Map<String, String> source = System.getenv();
        environment.clear();
        for (String key : ENV_ALLOWLIST) {
            String value = source.get(key);
            if (value != null && !value.isBlank()) {
                environment.put(key, value);
            }
        }
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
        var matcher = SECRET_ASSIGNMENT.matcher(value);
        String redacted = matcher.replaceAll("$1=<redacted>");
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
