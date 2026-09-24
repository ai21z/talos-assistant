package dev.talos.engine.llamacpp;

import dev.talos.spi.types.ProviderTransportHandoff;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LlamaCppCaptureRetentionTest {
    @Test
    void queuedBytesAreDroppedAfterTheBarrierWhileTheStreamStillDrains() throws Exception {
        var before = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var drained = new AtomicInteger();
        var sink = new ByteArrayOutputStream();
        InputStream source = new InputStream() {
            @Override public int read() { return -1; }
            @Override public long transferTo(OutputStream target) throws IOException {
                target.write("before\n".getBytes(StandardCharsets.UTF_8));
                before.countDown();
                await(release);
                byte[] block = new byte[8192];
                java.util.Arrays.fill(block, (byte) 'x');
                for (int index = 0; index < 512; index++) {
                    target.write(block);
                    drained.addAndGet(block.length);
                }
                return 7L + drained.get();
            }
        };
        var capture = capture(source, sink);
        try {
            assertTrue(before.await(3, TimeUnit.SECONDS));
            assertTrue(capture.suppressOutputRetention());
            assertTrue(capture.suppressOutputRetention());
        } finally { release.countDown(); }
        capture.settle(Duration.ofSeconds(5));
        assertEquals(4 * 1024 * 1024, drained.get());
        assertEquals("before\n", sink.toString(StandardCharsets.UTF_8));
        assertTrue(capture.healthy());
        assertTrue(capture.captureComplete());
        assertTrue(capture.sourceClosed());
        assertTrue(capture.sinkClosed());
    }

    @Test
    void anActiveBlockedWriterCannotBeMistakenForSuppressedCapture() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var allowEof = new CountDownLatch(1);
        var sink = new OutputStream() {
            @Override public void write(int value) throws IOException {
                entered.countDown();
                await(release);
            }
        };
        InputStream source = new InputStream() {
            @Override public int read() { return -1; }
            @Override public long transferTo(OutputStream target) throws IOException {
                target.write(1);
                await(allowEof);
                return 1;
            }
        };
        var capture = capture(source, sink);
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            long started = System.nanoTime();
            assertFalse(capture.suppressOutputRetention());
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(4));
        } finally {
            release.countDown();
            allowEof.countDown();
        }
        capture.settle(Duration.ofSeconds(5));
        assertTrue(capture.suppressOutputRetention());
    }

    @Test
    void interruptedSuppressionDoesNotClaimSuccessOrEraseInterruption() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        InputStream source = new InputStream() {
            @Override public int read() throws IOException {
                started.countDown();
                await(release);
                return -1;
            }
        };
        var capture = capture(source, new ByteArrayOutputStream());
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS));
            Thread.currentThread().interrupt();
            assertFalse(capture.suppressOutputRetention());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            release.countDown();
        }
        capture.settle(Duration.ofSeconds(5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"--log-file", "--log-file=out", "--log_file", "--log_file=out",
            "--log-prompts-dir", "--log-prompts-dir=out", "--log_prompts_dir", "--logdir", "--log-dir"})
    void nativeFileSinksCannotBorrowTheStdoutBarrier(String flag) {
        assertTrue(ProcessBuilderLlamaCppProcessLauncher.nativeFileLogging(
                List.of("server", flag, "out"), Map.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"LLAMA_ARG_LOG_FILE", "LLAMA_LOG_FILE", "llama_arg_log_file"})
    void theEffectiveEnvironmentCannotIntroduceANativeFileSink(String key) {
        assertTrue(ProcessBuilderLlamaCppProcessLauncher.nativeFileLogging(
                List.of("server", "--log-disable"), Map.of(key, "out")));
        assertFalse(ProcessBuilderLlamaCppProcessLauncher.nativeFileLogging(List.of("server"), Map.of(key, "")));
    }

    @Test
    void stdoutVerbosityIsNotChangedOrConfusedWithANativeFileSink() {
        List<String> command = List.of("server", "-lv", "5", "--log-jsonl", "--log-colors", "off");
        var environment = Map.of("LLAMA_ARG_LOG_VERBOSITY", "5");
        assertFalse(ProcessBuilderLlamaCppProcessLauncher.nativeFileLogging(command, environment));
        assertEquals("5", command.get(2));
        assertEquals("5", environment.get("LLAMA_ARG_LOG_VERBOSITY"));
    }

    @Test
    void nativeFileLoggingIsRejectedBeforeAnInitiallyNonRetainingChildCanStart() {
        var launcher = new ProcessBuilderLlamaCppProcessLauncher();
        assertThrows(ProviderTransportHandoff.ArtifactRetentionRejected.class,
                () -> launcher.start(List.of("not-an-executable", "--log-prompts-dir", "out"), null));
        assertThrows(ProviderTransportHandoff.ArtifactRetentionRejected.class,
                () -> launcher.start(List.of("not-an-executable"), null, Map.of("LLAMA_ARG_LOG_FILE", "out")));
    }

    @Test
    void realMergedStdoutAndStderrAreDrainedWithoutRetention() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var command = List.of(java, "-cp", System.getProperty("java.class.path"), MixedOutput.class.getName());
        var child = new ProcessBuilderLlamaCppProcessLauncher().start(command, null);
        try {
            assertTrue(child.suppressOutputRetention());
            assertTrue(child.waitFor(Duration.ofSeconds(10)));
            assertTrue(child.outputCaptureHealthy());
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    public static class MixedOutput {
        public static void main(String[] args) throws IOException {
            byte[] block = new byte[8192];
            for (int index = 0; index < 256; index++) {
                System.out.write(block);
                System.err.write(block);
            }
        }
    }

    private static ProcessBuilderLlamaCppProcessLauncher.LaunchOutputCapture capture(
            InputStream source, OutputStream sink) throws IOException {
        var capture = new ProcessBuilderLlamaCppProcessLauncher.LaunchOutputCapture(() -> sink);
        capture.start(new InertProcess(source));
        return capture;
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("Fixture deadline");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Fixture interrupted", interrupted);
        }
    }

    private static final class InertProcess extends Process {
        private final InputStream source;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private InertProcess(InputStream source) { this.source = source; }
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return source; }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { alive.set(false); return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { alive.set(false); }
        @Override public boolean isAlive() { return alive.get(); }
        @Override public long pid() { return 1; }
    }
}
