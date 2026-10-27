package dev.talos.runtime.command;

import dev.talos.spi.types.ControlFlowFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ProcessCommandRunnerTest {

    @Test
    void deadRootCannotAdoptMisleadingDescendantsOnDrainFailure(@TempDir Path workspace) {
        OwnershipProcess process = new OwnershipProcess();
        process.alive.set(false);
        process.failOutput = true;
        // A stale/reused numeric PID may still yield a live handle. The actual
        // owned Process, not that handle, determines whether discovery is allowed.
        CommandResult result = new ProcessCommandRunner(builder -> process, 100).run(plan(
                javaExecutable(), List.of(), workspace, 100, CommandOutputLimits.defaults()));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("quiescence is unconfirmed"));
        assertEquals(0, process.discoveries.get());
        assertEquals(0, process.child.kills.get());
        assertEquals(0, process.kills.get());
    }

    @Test
    void rootExitDuringDiscoveryDiscardsAllNewDescendants(@TempDir Path workspace) {
        OwnershipProcess process = new OwnershipProcess();
        process.duringDiscovery = () -> process.alive.set(false);
        CommandResult result = runOwnershipTimeout(process, workspace);

        assertTrue(result.timedOut());
        assertEquals(1, process.discoveries.get());
        assertEquals(0, process.child.kills.get());
        assertEquals(0, process.kills.get());
    }

    @Test
    void liveRootTimeoutStillTerminatesStartBoundDescendant(@TempDir Path workspace) {
        OwnershipProcess process = new OwnershipProcess();
        CommandResult result = runOwnershipTimeout(process, workspace);

        assertFalse(result.success());
        assertTrue(result.timedOut());
        assertTrue(result.killed());
        assertEquals(1, process.discoveries.get());
        assertEquals(1, process.child.kills.get());
        assertEquals(1, process.kills.get());
    }

    @Test
    void missingOrPredatingStartDoesNotAuthorizeDescendantTermination(@TempDir Path workspace) {
        for (int defect = 0; defect < 3; defect++) {
            OwnershipProcess process = new OwnershipProcess();
            if (defect == 0) process.root.started.set(null);
            if (defect == 1) process.child.started.set(null);
            if (defect == 2) process.child.started.set(process.root.started.get().minusSeconds(1));

            assertFalse(runOwnershipTimeout(process, workspace).success());
            assertEquals(0, process.child.kills.get(), "identity defect " + defect);
            assertEquals(1, process.kills.get(), "the owned live root remains terminable");
        }
    }

    @Test
    void changedRootStartDuringDiscoveryDiscardsNewDescendants(@TempDir Path workspace) {
        OwnershipProcess process = new OwnershipProcess();
        process.duringDiscovery = () -> process.root.started.updateAndGet(started -> started.plusMillis(1));

        assertFalse(runOwnershipTimeout(process, workspace).success());
        assertEquals(0, process.child.kills.get());
        assertEquals(1, process.kills.get());
    }

    @Test
    void changedDescendantStartBeforeTerminationIsNotTerminated(@TempDir Path workspace) {
        OwnershipProcess process = new OwnershipProcess();
        process.child.changeStartAfterFirstRead = true;

        assertFalse(runOwnershipTimeout(process, workspace).success());
        assertEquals(0, process.child.kills.get());
        assertEquals(1, process.kills.get());
    }

    private static CommandResult runOwnershipTimeout(OwnershipProcess process, Path workspace) {
        return new ProcessCommandRunner(builder -> process, 100).run(plan(
                javaExecutable(), List.of(), workspace, 100, CommandOutputLimits.defaults()));
    }

    /** Synthetic process records only: no native process creation or termination. */
    private static final class OwnershipProcess extends Process {
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger discoveries = new AtomicInteger();
        private final AtomicInteger kills = new AtomicInteger();
        private final OwnershipHandle root = new OwnershipHandle(700, Instant.parse("2026-01-01T00:00:00Z"));
        private final OwnershipHandle child = new OwnershipHandle(701, Instant.parse("2026-01-01T00:00:01Z"));
        private Runnable duringDiscovery = () -> {};
        private boolean failOutput;
        @Override public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
        @Override public java.io.InputStream getInputStream() {
            if (!failOutput) return java.io.InputStream.nullInputStream();
            return new java.io.InputStream() {
                @Override public int read() throws java.io.IOException { throw new java.io.IOException("fixture drain failure"); }
            };
        }
        @Override public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) { return !alive.get(); }
        @Override public int exitValue() {
            if (alive.get()) throw new IllegalThreadStateException();
            return 0;
        }
        @Override public void destroy() { destroyForcibly(); }
        @Override public Process destroyForcibly() { kills.incrementAndGet(); alive.set(false); root.alive.set(false); return this; }
        @Override public boolean isAlive() { return alive.get(); }
        @Override public ProcessHandle toHandle() { return root; }
        @Override public Stream<ProcessHandle> descendants() {
            discoveries.incrementAndGet();
            return Stream.<ProcessHandle>of(child).peek(ignored -> duringDiscovery.run());
        }
    }

    private static final class OwnershipHandle implements ProcessHandle {
        private final long pid;
        private final AtomicReference<Instant> started;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger kills = new AtomicInteger();
        private final AtomicInteger infoReads = new AtomicInteger();
        private boolean changeStartAfterFirstRead;
        OwnershipHandle(long pid, Instant started) { this.pid = pid; this.started = new AtomicReference<>(started); }
        @Override public long pid() { return pid; }
        @Override public Optional<ProcessHandle> parent() { return Optional.empty(); }
        @Override public Stream<ProcessHandle> children() { return Stream.empty(); }
        @Override public Stream<ProcessHandle> descendants() { return Stream.empty(); }
        @Override public Info info() {
            Instant observed = started.get();
            if (changeStartAfterFirstRead && infoReads.getAndIncrement() > 0) observed = observed.plusMillis(1);
            Instant snapshot = observed;
            return new Info() {
                @Override public Optional<String> command() { return Optional.empty(); }
                @Override public Optional<String> commandLine() { return Optional.empty(); }
                @Override public Optional<String[]> arguments() { return Optional.empty(); }
                @Override public Optional<Instant> startInstant() { return Optional.ofNullable(snapshot); }
                @Override public Optional<java.time.Duration> totalCpuDuration() { return Optional.empty(); }
                @Override public Optional<String> user() { return Optional.empty(); }
            };
        }
        @Override public CompletableFuture<ProcessHandle> onExit() { throw new UnsupportedOperationException(); }
        @Override public boolean supportsNormalTermination() { return true; }
        @Override public boolean destroy() { return destroyForcibly(); }
        @Override public boolean destroyForcibly() { kills.incrementAndGet(); alive.set(false); return true; }
        @Override public boolean isAlive() { return alive.get(); }
        @Override public int compareTo(ProcessHandle other) { return Long.compare(pid, other.pid()); }
    }

    @Test
    void uninterruptiblePipeReadIsClosedWithoutUnboundedExecutorClose(@TempDir Path workspace) {
        BlockingPipe pipe = new BlockingPipe(false);
        try {
            CommandResult result = assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),
                    () -> new ProcessCommandRunner(builder -> new ExitedProcess(pipe), 100).run(plan(
                            javaExecutable(), List.of(), workspace, 100, CommandOutputLimits.defaults())));
            assertFalse(result.success());
            assertTrue(result.errorMessage().contains("quiescence is unconfirmed"));
            assertTrue(pipe.closed.get(), "owned stream must be closed even after capture timeout");
            assertEquals(0, pipe.readerFinished.getCount(), "close must settle the blocked reader");
        } finally {
            pipe.release.countDown();
        }
    }

    @Test
    void uninterruptibleStreamCloseIsBoundedAndNeverReportedAsSuccess(@TempDir Path workspace) {
        BlockingPipe pipe = new BlockingPipe(true);
        try {
            CommandResult result = assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),
                    () -> new ProcessCommandRunner(builder -> new ExitedProcess(pipe), 100).run(plan(
                            javaExecutable(), List.of(), workspace, 100, CommandOutputLimits.defaults())));
            assertFalse(result.success());
            assertTrue(result.errorMessage().contains("cleanup did not settle"));
        } finally {
            pipe.release.countDown();
        }
    }

    private static final class BlockingPipe extends java.io.InputStream {
        private final boolean blockClose;
        private final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch readerFinished = new java.util.concurrent.CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();
        BlockingPipe(boolean blockClose) { this.blockClose = blockClose; }
        @Override public int read() {
            awaitRelease();
            readerFinished.countDown();
            return -1;
        }
        @Override public void close() {
            if (blockClose) awaitRelease();
            closed.set(true);
            release.countDown();
        }
        private void awaitRelease() {
            boolean interrupted = false;
            while (true) {
                try { release.await(); break; }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    @Test
    void exitedParentWithRealChildHoldingInheritedPipesCannotHangRunner(@TempDir Path workspace) throws Exception {
        Path childPid = workspace.resolve("inherited-child.pid");
        long pid = -1;
        try {
            CommandResult result = assertTimeoutPreemptively(java.time.Duration.ofSeconds(12),
                    () -> new ProcessCommandRunner().run(plan(javaExecutable(),
                            List.of("-cp", classPath(), ParentWithInheritedPipes.class.getName(), childPid.toString()),
                            workspace, 5_000, CommandOutputLimits.defaults())));
            pid = awaitPid(childPid);
            // Some JDK/OS pipe implementations deliver EOF on parent exit; others
            // retain the child's writer. Both must return within the same drain bound.
            if (!result.success()) assertFalse(result.errorMessage().isBlank());
            assertTrue(result.durationMs() < 12_000);
        } finally {
            if (pid < 0 && java.nio.file.Files.isRegularFile(childPid)) pid = awaitPid(childPid);
            if (pid > 0) {
                ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
                assertFalse(awaitNotAlive(pid), "fixture-owned child must be quiescent after test cleanup");
            }
        }
    }

    private static final class ExitedProcess extends Process {
        private final java.io.InputStream output;
        ExitedProcess(java.io.InputStream output) { this.output = output; }
        @Override public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
        @Override public java.io.InputStream getInputStream() { return output; }
        @Override public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) { return true; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { }
        @Override public boolean isAlive() { return false; }
        @Override public java.util.stream.Stream<ProcessHandle> descendants() { return java.util.stream.Stream.empty(); }
    }

    @Test
    void capturesSuccessfulJavaVersionWithoutShell(@TempDir Path workspace) {
        CommandResult result = new ProcessCommandRunner().run(plan(
                javaExecutable(),
                List.of("-version"),
                workspace,
                20_000,
                CommandOutputLimits.defaults()));

        assertTrue(result.success(), result.stderr());
        assertEquals(0, result.exitCode());
        assertFalse(result.timedOut());
        assertTrue(result.stderr().toLowerCase(java.util.Locale.ROOT).contains("version"),
                result.stderr());
    }

    @Test
    void capturesNonZeroExitCode(@TempDir Path workspace) {
        CommandResult result = new ProcessCommandRunner().run(plan(
                javaExecutable(),
                List.of("-cp", classPath(), ExitWithCode.class.getName(), "7"),
                workspace,
                20_000,
                CommandOutputLimits.defaults()));

        assertFalse(result.success());
        assertEquals(7, result.exitCode());
        assertFalse(result.timedOut());
    }

    @Test
    void timeoutKillsProcess(@TempDir Path workspace) {
        CommandResult result = new ProcessCommandRunner().run(plan(
                javaExecutable(),
                List.of("-cp", classPath(), Sleepy.class.getName()),
                workspace,
                200,
                CommandOutputLimits.defaults()));

        assertFalse(result.success());
        assertTrue(result.timedOut());
        assertTrue(result.killed());
        assertEquals(-1, result.exitCode());
    }

    @Test
    void callerInterruptionKillsChildAndPropagatesWithInterruptFlag(@TempDir Path workspace) throws Exception {
        Path pidFile = workspace.resolve("child.pid");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                new ProcessCommandRunner().run(plan(
                        javaExecutable(),
                        List.of("-cp", classPath(), BlockingChild.class.getName(), pidFile.toString()),
                        workspace,
                        20_000,
                        CommandOutputLimits.defaults()));
            } catch (Throwable throwable) {
                failure.set(throwable);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        }, "process-command-interruption-test");

        long pid = -1;
        try {
            caller.start();
            pid = awaitPid(pidFile);
            caller.interrupt();
            caller.join(10_000);

            assertFalse(caller.isAlive(), "interrupted runner must terminate promptly");
            assertNotNull(failure.get(), "interruption must propagate instead of becoming CommandResult");
            assertEquals(ControlFlowFailure.Kind.INTERRUPTION,
                    ControlFlowFailure.classify(failure.get()));
            assertTrue(interrupted.get(), "caller interrupt status must be restored before propagation");
            assertFalse(awaitNotAlive(pid), "child process must be forcibly terminated");
        } finally {
            if (pid > 0) ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
            if (caller.isAlive()) {
                caller.interrupt();
                caller.join(5_000);
            }
        }
    }

    @Test
    void directCancellationFromProcessStartupPropagates(@TempDir Path workspace) {
        CancellationException cancellation = new CancellationException("stop command");
        ProcessCommandRunner runner = new ProcessCommandRunner(builder -> { throw cancellation; });

        CancellationException thrown = assertThrows(
                CancellationException.class,
                () -> runner.run(plan(
                        javaExecutable(),
                        List.of("-version"),
                        workspace,
                        20_000,
                        CommandOutputLimits.defaults())));

        assertSame(cancellation, thrown);
    }

    @Test
    void wrappedInterruptionFromProcessStartupPropagatesAndRestoresFlag(@TempDir Path workspace) {
        RuntimeException wrapper = new RuntimeException(new InterruptedException("stop command"));
        ProcessCommandRunner runner = new ProcessCommandRunner(builder -> { throw wrapper; });

        try {
            RuntimeException thrown = assertThrows(
                    RuntimeException.class,
                    () -> runner.run(plan(
                            javaExecutable(),
                            List.of("-version"),
                            workspace,
                            20_000,
                            CommandOutputLimits.defaults())));

            assertSame(wrapper, thrown);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void directInterruptionFromProcessStartupPropagatesAndRestoresFlag(@TempDir Path workspace) {
        InterruptedException interruption = new InterruptedException("stop command");
        ProcessCommandRunner runner = new ProcessCommandRunner(builder -> { throw interruption; });

        try {
            RuntimeException thrown = assertThrows(
                    RuntimeException.class,
                    () -> runner.run(plan(
                            javaExecutable(),
                            List.of("-version"),
                            workspace,
                            20_000,
                            CommandOutputLimits.defaults())));

            assertSame(interruption, thrown.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void ordinaryProcessStartupFailureRemainsInternalResult(@TempDir Path workspace) {
        ProcessCommandRunner runner = new ProcessCommandRunner(
                builder -> { throw new java.io.IOException("ordinary startup failure"); });

        CommandResult result = runner.run(plan(
                javaExecutable(),
                List.of("-version"),
                workspace,
                20_000,
                CommandOutputLimits.defaults()));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("IOException"), result.errorMessage());
    }

    @Test
    void capsLargeOutput(@TempDir Path workspace) {
        CommandResult result = new ProcessCommandRunner().run(plan(
                javaExecutable(),
                List.of("-cp", classPath(), SpamStdout.class.getName()),
                workspace,
                20_000,
                new CommandOutputLimits(64, 64, 64)));

        assertTrue(result.success(), result.stderr());
        assertTrue(result.stdoutTruncated());
        assertTrue(result.stdout().length() <= 64, result.stdout().length() + " chars");
    }

    @Test
    void redactsSecretLikeOutput(@TempDir Path workspace) {
        CommandResult result = new ProcessCommandRunner().run(plan(
                javaExecutable(),
                List.of("-cp", classPath(), SecretPrinter.class.getName()),
                workspace,
                20_000,
                CommandOutputLimits.defaults()));

        assertTrue(result.success(), result.stderr());
        assertTrue(result.redactionApplied());
        assertTrue(result.stdout().contains("API_TOKEN=[redacted]"), result.stdout());
        assertFalse(result.stdout().contains("abc123"), result.stdout());
    }

    @Test
    void redactsBareSecretShapesFromCommandOutput(@TempDir Path workspace) {
        CommandResult result = new ProcessCommandRunner().run(plan(
                javaExecutable(),
                List.of("-cp", classPath(), SecretShapePrinter.class.getName()),
                workspace,
                20_000,
                CommandOutputLimits.defaults()));

        assertTrue(result.success(), result.stderr());
        assertTrue(result.redactionApplied());
        assertFalse(result.stdout().contains("ghp_AbCdEfGhIjKlMnOpQrStUvWxYz1234567890"), result.stdout());
        assertFalse(result.stdout().contains("sk-proj-abcdefghijklmnopqrstuvwxyz1234567890"), result.stdout());
        assertFalse(result.stdout().contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"), result.stdout());
        assertFalse(result.stdout().contains("PRIVATE KEY"), result.stdout());
        assertFalse(result.stdout().contains("supersecret"), result.stdout());
        assertTrue(result.stdout().contains("[redacted]"), result.stdout());
    }

    @Test
    void internalFailureRedactsProtectedExecutablePath(@TempDir Path workspace) {
        Path protectedExecutable = workspace.resolve("protected").resolve("FILE_DISCOVERED_CANARY_CMD_EXEC.exe");

        CommandResult result = new ProcessCommandRunner().run(plan(
                protectedExecutable.toString(),
                List.of(),
                workspace,
                20_000,
                CommandOutputLimits.defaults()));

        assertFalse(result.success());
        assertFalse(result.errorMessage().contains("FILE_DISCOVERED_CANARY_CMD_EXEC"), result.errorMessage());
        assertFalse(result.errorMessage().contains("\\protected\\"), result.errorMessage());
        assertFalse(result.errorMessage().contains("/protected/"), result.errorMessage());
        assertTrue(result.errorMessage().contains("<protected-path>"), result.errorMessage());
    }

    @Test
    void posixEnvironmentAllowlistKeepsGradleBasicsWithoutBroadLeak() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("HOME", "/home/talos");
        source.put("LANG", "C.UTF-8");
        source.put("LC_ALL", "C.UTF-8");
        source.put("TMPDIR", "/tmp/talos");
        source.put("JAVA_HOME", "/opt/jdk-21");
        source.put("PATH", "/usr/bin");
        source.put("AWS_SECRET_ACCESS_KEY", "do-not-forward");
        source.put("TALOS_FAKE_SECRET", "do-not-forward");

        Map<String, String> environment = ProcessCommandRunner.filteredEnvironment(
                source,
                CommandRuntimePlatform.posix());

        assertEquals("/home/talos", environment.get("HOME"));
        assertEquals("C.UTF-8", environment.get("LANG"));
        assertEquals("C.UTF-8", environment.get("LC_ALL"));
        assertEquals("/tmp/talos", environment.get("TMPDIR"));
        assertEquals("/opt/jdk-21", environment.get("JAVA_HOME"));
        assertEquals("/usr/bin", environment.get("PATH"));
        assertEquals(6, environment.size(), "POSIX command environment must stay narrowly allowlisted");
        assertFalse(environment.containsKey("AWS_SECRET_ACCESS_KEY"));
        assertFalse(environment.containsKey("TALOS_FAKE_SECRET"));
    }

    @Test
    void windowsEnvironmentAllowlistKeepsProcessBasicsWithoutBroadLeak() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("SystemRoot", "C:\\Windows");
        source.put("WINDIR", "C:\\Windows");
        source.put("ComSpec", "C:\\Windows\\System32\\cmd.exe");
        source.put("PATHEXT", ".COM;.EXE;.BAT;.CMD");
        source.put("TEMP", "C:\\Users\\talos\\AppData\\Local\\Temp");
        source.put("TMP", "C:\\Users\\talos\\AppData\\Local\\Temp");
        source.put("JAVA_HOME", "C:\\jdk-21");
        source.put("PATH", "C:\\Windows\\System32;C:\\jdk-21\\bin");
        source.put("AWS_SECRET_ACCESS_KEY", "do-not-forward");
        source.put("TALOS_FAKE_SECRET", "do-not-forward");

        Map<String, String> environment = ProcessCommandRunner.filteredEnvironment(
                source,
                CommandRuntimePlatform.windows());

        assertEquals("C:\\Windows", environment.get("SystemRoot"));
        assertEquals("C:\\Windows", environment.get("WINDIR"));
        assertEquals("C:\\Windows\\System32\\cmd.exe", environment.get("ComSpec"));
        assertEquals(".COM;.EXE;.BAT;.CMD", environment.get("PATHEXT"));
        assertEquals("C:\\Users\\talos\\AppData\\Local\\Temp", environment.get("TEMP"));
        assertEquals("C:\\Users\\talos\\AppData\\Local\\Temp", environment.get("TMP"));
        assertEquals("C:\\jdk-21", environment.get("JAVA_HOME"));
        assertEquals("C:\\Windows\\System32;C:\\jdk-21\\bin", environment.get("PATH"));
        assertEquals(8, environment.size(), "Windows command environment must stay narrowly allowlisted");
        assertFalse(environment.containsKey("AWS_SECRET_ACCESS_KEY"));
        assertFalse(environment.containsKey("TALOS_FAKE_SECRET"));
    }

    private static CommandPlan plan(
            String executable,
            List<String> argv,
            Path workspace,
            long timeoutMs,
            CommandOutputLimits limits
    ) {
        return new CommandPlan(
                "test_profile",
                "Test profile",
                executable,
                argv,
                workspace,
                CommandRisk.READ_ONLY_DIAGNOSTIC,
                false,
                false,
                List.of(),
                true,
                false,
                timeoutMs,
                100,
                limits);
    }

    private static String javaExecutable() {
        String exe = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", exe).toString();
    }

    private static String classPath() {
        return System.getProperty("java.class.path");
    }

    public static final class ExitWithCode {
        public static void main(String[] args) {
            int code = args.length == 0 ? 1 : Integer.parseInt(args[0]);
            System.exit(code);
        }
    }

    public static final class Sleepy {
        public static void main(String[] args) throws Exception {
            Thread.sleep(30_000);
        }
    }

    public static final class ParentWithInheritedPipes {
        public static void main(String[] args) throws Exception {
            Process child = new ProcessBuilder(javaExecutable(), "-cp", classPath(), Sleepy.class.getName())
                    .inheritIO().start();
            java.nio.file.Files.writeString(Path.of(args[0]), Long.toString(child.pid()));
        }
    }

    public static final class SpamStdout {
        public static void main(String[] args) {
            System.out.print("x".repeat(10_000));
        }
    }

    public static final class SecretPrinter {
        public static void main(String[] args) {
            System.out.println("API_TOKEN=abc123");
        }
    }

    public static final class SecretShapePrinter {
        public static void main(String[] args) {
            System.out.println("bare github token ghp_AbCdEfGhIjKlMnOpQrStUvWxYz1234567890");
            System.out.println("openai token sk-proj-abcdefghijklmnopqrstuvwxyz1234567890");
            System.out.println("jwt eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
                    + "eyJzdWIiOiJ0YWxvcyIsIm5hbWUiOiJUZXN0In0."
                    + "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c");
            System.out.println(String.join(System.lineSeparator(),
                    "-----BEGIN " + "PRIVATE KEY-----",
                    "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCfakefixtureonly",
                    "-----END " + "PRIVATE KEY-----"));
            System.out.println("jdbc:postgresql://user:supersecret@localhost/db");
        }
    }

    public static final class BlockingChild {
        public static void main(String[] args) throws Exception {
            java.nio.file.Files.writeString(Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
            Thread.sleep(30_000);
        }
    }

    private static long awaitPid(Path pidFile) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (java.nio.file.Files.isRegularFile(pidFile)) {
                String value = java.nio.file.Files.readString(pidFile).strip();
                if (!value.isBlank()) return Long.parseLong(value);
            }
            Thread.sleep(20);
        }
        fail("child process did not publish its pid");
        return -1;
    }

    private static boolean awaitNotAlive(long pid) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            if (!alive) return false;
            Thread.sleep(20);
        }
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
