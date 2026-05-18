package dev.talos.harness;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Small synchronized stdin/stdout driver for production CLI smoke audits.
 *
 * <p>This is not a true pseudo-terminal. It deliberately exercises the
 * redirected-stdin production path used by `talos run` while avoiding static
 * stdin drift: each scripted input line is written only after the expected
 * output marker has appeared.
 */
final class SynchronizedCliProcessDriver implements AutoCloseable {

    record Step(String waitFor, String sendLine) {
        Step {
            waitFor = Objects.toString(waitFor, "");
            sendLine = Objects.toString(sendLine, "");
            if (waitFor.isBlank()) {
                throw new IllegalArgumentException("waitFor is required");
            }
        }
    }

    private final InputStream stdout;
    private final OutputStream stdin;
    private final BooleanSupplier processAlive;
    private final StringBuilder transcript = new StringBuilder();
    private final AtomicReference<IOException> readFailure = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Thread readerThread;

    private SynchronizedCliProcessDriver(InputStream stdout, OutputStream stdin, BooleanSupplier processAlive) {
        this.stdout = Objects.requireNonNull(stdout, "stdout");
        this.stdin = Objects.requireNonNull(stdin, "stdin");
        this.processAlive = processAlive == null ? () -> true : processAlive;
        this.readerThread = new Thread(this::readLoop, "talos-cli-smoke-output-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    static SynchronizedCliProcessDriver start(InputStream stdout, OutputStream stdin) {
        return start(stdout, stdin, () -> true);
    }

    static SynchronizedCliProcessDriver start(
            InputStream stdout,
            OutputStream stdin,
            BooleanSupplier processAlive) {
        return new SynchronizedCliProcessDriver(stdout, stdin, processAlive);
    }

    void runSteps(List<Step> steps, Duration timeoutPerStep) throws IOException {
        List<Step> safeSteps = steps == null ? List.of() : List.copyOf(steps);
        Duration safeTimeout = timeoutPerStep == null ? Duration.ofSeconds(30) : timeoutPerStep;
        for (Step step : safeSteps) {
            await(step.waitFor(), safeTimeout);
            writeLine(step.sendLine());
        }
    }

    String transcript() {
        synchronized (transcript) {
            return transcript.toString();
        }
    }

    private void await(String marker, Duration timeout) throws IOException {
        long deadline = System.nanoTime() + Math.max(1L, timeout.toNanos());
        while (System.nanoTime() < deadline) {
            if (contains(marker)) return;
            if (!processAlive.getAsBoolean()) {
                throw new IOException("Expected output marker before process exited: " + marker
                        + "\nTranscript tail:\n" + transcriptTail());
            }
            IOException failure = readFailure.get();
            if (failure != null && !contains(marker)) {
                throw new IOException("Output reader failed while waiting for marker: " + marker
                        + "\nTranscript tail:\n" + transcriptTail(), failure);
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for output marker: " + marker, e);
            }
        }
        throw new IOException("Timed out waiting for output marker: " + marker
                + "\nTranscript tail:\n" + transcriptTail());
    }

    private boolean contains(String marker) {
        synchronized (transcript) {
            return transcript.indexOf(marker) >= 0;
        }
    }

    private void writeLine(String line) throws IOException {
        stdin.write((line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    private void readLoop() {
        try (InputStreamReader reader = new InputStreamReader(stdout, StandardCharsets.UTF_8)) {
            char[] buffer = new char[1024];
            int read;
            while (!closed.get() && (read = reader.read(buffer)) >= 0) {
                synchronized (transcript) {
                    transcript.append(buffer, 0, read);
                }
            }
        } catch (IOException e) {
            if (!closed.get()) {
                readFailure.compareAndSet(null, e);
            }
        }
    }

    private String transcriptTail() {
        synchronized (transcript) {
            String value = transcript.toString();
            int start = Math.max(0, value.length() - 2000);
            return value.substring(start);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { stdout.close(); } catch (IOException ignored) { }
        try { stdin.close(); } catch (IOException ignored) { }
        readerThread.interrupt();
    }
}
