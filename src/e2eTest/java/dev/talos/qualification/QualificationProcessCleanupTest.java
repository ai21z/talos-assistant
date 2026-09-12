package dev.talos.qualification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationProcessCleanupTest {

    private static final Duration NO_WAIT = Duration.ZERO;

    @Test
    void nativeDeadRootCannotAdoptMisleadingLiveDescendants() {
        NativeCleanupProcess root = new NativeCleanupProcess();
        root.alive = false;
        // The numeric handle deliberately looks live: it is not the owned Process.
        assertTrue(root.rootHandle.isAlive());

        QualificationProcessCleanup.Result result = nativeCleanup(root);

        assertEquals(0, root.descendantCalls);
        assertEquals(QualificationProcessCleanup.Outcome.ALREADY_EXITED, result.outcome());
        assertRejectedNativeDescendant(root, result);
        assertEquals("", root.inputText());
    }

    @Test
    void nativeRootExitDuringDiscoveryDiscardsNewDescendants() {
        NativeCleanupProcess root = new NativeCleanupProcess();
        root.afterDiscovery = () -> root.alive = false;

        QualificationProcessCleanup.Result result = nativeCleanup(root);

        assertEquals(1, root.descendantCalls);
        assertEquals(QualificationProcessCleanup.Outcome.ALREADY_EXITED, result.outcome());
        assertRejectedNativeDescendant(root, result);
        assertEquals("", root.inputText());
    }

    @Test
    void nativeRootStartChangeDuringDiscoveryDiscardsNewDescendants() {
        NativeCleanupProcess root = new NativeCleanupProcess();
        root.afterDiscovery = () -> root.rootHandle.started = root.rootHandle.started.plusSeconds(1);

        QualificationProcessCleanup.Result result = nativeCleanup(root);

        assertEquals(1, root.descendantCalls);
        assertEquals(QualificationProcessCleanup.Outcome.GRACEFUL, result.outcome());
        assertRejectedNativeDescendant(root, result);
    }

    @Test
    void nativeMissingRootStartDoesNotEnumerateDescendants() {
        NativeCleanupProcess root = new NativeCleanupProcess();
        root.rootHandle.started = null;

        QualificationProcessCleanup.Result result = nativeCleanup(root);

        assertEquals(0, root.descendantCalls);
        assertEquals(QualificationProcessCleanup.Outcome.GRACEFUL, result.outcome());
        assertRejectedNativeDescendant(root, result);
    }

    @Test
    void nativeMissingOrPredatingChildStartDoesNotAuthorizeTermination() {
        for (boolean missing : List.of(true, false)) {
            NativeCleanupProcess root = new NativeCleanupProcess();
            root.child.started = missing ? null : root.rootHandle.started.minusSeconds(1);

            QualificationProcessCleanup.Result result = nativeCleanup(root);

            assertEquals(1, root.descendantCalls);
            assertEquals(QualificationProcessCleanup.Outcome.GRACEFUL, result.outcome());
            assertRejectedNativeDescendant(root, result);
        }
    }

    @Test
    void nativeLiveRootCapturesChildBeforeGracefulExitAndStopsIt() {
        NativeCleanupProcess root = new NativeCleanupProcess();

        QualificationProcessCleanup.Result result = nativeCleanup(root);

        assertEquals(1, root.descendantCalls);
        assertEquals("/exit\r", root.inputText());
        assertEquals(QualificationProcessCleanup.Outcome.TERMINATED, result.outcome());
        assertEquals(List.of(root.child.pid()), result.capturedDescendantPids());
        assertEquals(List.of(root.child.pid()), result.normalStopPids());
        assertTrue(result.forcedStopPids().isEmpty());
        assertTrue(result.survivingPids().isEmpty());
        assertTrue(result.errors().isEmpty());
        assertEquals(1, root.child.destroyCalls);
        assertEquals(0, root.child.forceCalls);
        assertEquals(0, root.destroyCalls + root.forceCalls);
        assertFalse(root.child.isAlive());
        assertFalse(root.isAlive());
    }

    @Test
    void nativeCapturedChildIdentityChangeBeforeStopFailsClosed() {
        NativeCleanupProcess root = new NativeCleanupProcess();
        root.afterExitCommand = () -> root.child.started = root.child.started.plusSeconds(1);

        QualificationProcessCleanup.Result result = nativeCleanup(root);

        assertEquals(QualificationProcessCleanup.Outcome.FAILED, result.outcome());
        assertEquals(List.of(root.child.pid()), result.capturedDescendantPids());
        assertEquals(List.of(root.child.pid()), result.survivingPids());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("identity changed")));
        assertTrue(result.normalStopPids().isEmpty());
        assertTrue(result.forcedStopPids().isEmpty());
        assertEquals(0, root.child.destroyCalls + root.child.forceCalls);
        assertTrue(root.child.isAlive());
    }

    private static QualificationProcessCleanup.Result nativeCleanup(NativeCleanupProcess root) {
        // Null notBefore disables campaign discovery before any platform query.
        return QualificationProcessCleanup.stop(root, null, null, 0, null);
    }

    private static void assertRejectedNativeDescendant(
            NativeCleanupProcess root, QualificationProcessCleanup.Result result) {
        assertTrue(result.capturedDescendantPids().isEmpty());
        assertTrue(result.normalStopPids().isEmpty());
        assertTrue(result.forcedStopPids().isEmpty());
        assertEquals(0, root.child.destroyCalls + root.child.forceCalls);
        assertTrue(root.child.isAlive());
    }

    @Test
    void absentCampaignListenerIsVerifiedCleanState() throws Exception {
        int unusedPort;
        try (ServerSocket reservation = new ServerSocket(0)) {
            unusedPort = reservation.getLocalPort();
        }
        String executableName = System.getProperty("os.name", "").startsWith("Windows")
                ? "java.exe"
                : "java";
        Path existingExecutable = Path.of(
                System.getProperty("java.home"), "bin", executableName);
        Path discoveryExecutable = isWindows()
                ? findOnPath("pwsh.exe")
                : existingExecutable;

        QualificationProcessCleanup.Result result = QualificationProcessCleanup.stop(
                null,
                discoveryExecutable,
                existingExecutable,
                unusedPort,
                Instant.now());

        assertEquals(QualificationProcessCleanup.Outcome.ALREADY_EXITED, result.outcome());
        assertTrue(result.campaignServerDiscoveryVerified());
        assertTrue(result.campaignServerPids().isEmpty());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void linkedDiscoveryExecutableFailsBeforeQueryProcessStart(@TempDir Path temp)
            throws Exception {
        Path external = Files.writeString(
                temp.resolve("external-discovery.exe"),
                "T1170_EXTERNAL_CLEANUP_DISCOVERY_CANARY",
                StandardCharsets.UTF_8);
        Path link = temp.resolve("linked-discovery.exe");
        createSymbolicLinkOrAbort(link, external);
        AtomicInteger starts = new AtomicInteger();

        QualificationProcessCleanup.FinderResult result =
                QualificationProcessCleanup.findWindowsCampaignServers(
                        link,
                        external,
                        31000,
                        Instant.now(),
                        builder -> {
                            starts.incrementAndGet();
                            throw new IOException("query start must not be reached");
                        });

        assertFalse(result.verified());
        assertEquals(0, starts.get());
        String errors = String.join("\n", result.errors());
        assertTrue(errors.contains("LINKED_COMPONENT"), errors);
        assertFalse(errors.contains(external.toString()), errors);
        assertFalse(errors.contains("T1170_EXTERNAL_CLEANUP_DISCOVERY_CANARY"), errors);
    }

    @Test
    void ordinaryDiscoveryUsesTheExactValidatedAbsoluteExecutable(@TempDir Path temp)
            throws Exception {
        Path discovery = Files.writeString(
                temp.resolve("exact-discovery.exe"),
                "ordinary discovery executable",
                StandardCharsets.UTF_8);
        Path expectedServer = Files.writeString(
                temp.resolve("server.exe"),
                "ordinary server executable",
                StandardCharsets.UTF_8);
        AtomicInteger starts = new AtomicInteger();
        AtomicReference<List<String>> command = new AtomicReference<>();

        QualificationProcessCleanup.FinderResult result =
                QualificationProcessCleanup.findWindowsCampaignServers(
                        discovery,
                        expectedServer,
                        31001,
                        Instant.now(),
                        builder -> {
                            starts.incrementAndGet();
                            command.set(List.copyOf(builder.command()));
                            throw new IOException("intentional test stop");
                        });

        assertFalse(result.verified());
        assertEquals(1, starts.get());
        assertEquals(
                discovery.toAbsolutePath().normalize().toString(),
                command.get().getFirst());
    }

    @Test
    void gracefullyExitsRootAndCapturedDescendantsWithoutTermination() {
        FakeTarget child = new FakeTarget(102L);
        FakeTarget root = new FakeTarget(101L).withDescendants(child);
        root.onExitCommand(() -> {
            root.alive = false;
            child.alive = false;
        });

        QualificationProcessCleanup.Result result = cleanup(root);

        assertEquals("/exit\r", root.inputText());
        assertEquals(QualificationProcessCleanup.Outcome.GRACEFUL, result.outcome());
        assertEquals(List.of(102L), result.capturedDescendantPids());
        assertTrue(result.normalStopPids().isEmpty());
        assertTrue(result.forcedStopPids().isEmpty());
        assertTrue(result.survivingPids().isEmpty());
        assertEquals(0, root.destroyCalls + child.destroyCalls);
        assertEquals(0, root.forceCalls + child.forceCalls);
    }

    @Test
    void terminatesCapturedDescendantEvenWhenRootExitsGracefully() {
        FakeTarget child = new FakeTarget(202L).exitOnDestroy();
        FakeTarget root = new FakeTarget(201L).withDescendants(child);
        root.onExitCommand(() -> root.alive = false);

        QualificationProcessCleanup.Result result = cleanup(root);

        assertEquals("/exit\r", root.inputText());
        assertEquals(QualificationProcessCleanup.Outcome.TERMINATED, result.outcome());
        assertEquals(List.of(202L), result.normalStopPids());
        assertTrue(result.forcedStopPids().isEmpty());
        assertFalse(child.alive);
        assertEquals(1, child.destroyCalls);
        assertEquals(0, root.destroyCalls);
    }

    @Test
    void usesForcedFallbackForEveryCapturedSurvivor() {
        FakeTarget child = new FakeTarget(302L).exitOnForce();
        FakeTarget root = new FakeTarget(301L).withDescendants(child).exitOnForce();

        QualificationProcessCleanup.Result result = cleanup(root);

        assertEquals(QualificationProcessCleanup.Outcome.FORCED, result.outcome());
        assertEquals(List.of(302L, 301L), result.normalStopPids());
        assertEquals(List.of(302L, 301L), result.forcedStopPids());
        assertTrue(result.survivingPids().isEmpty());
        assertFalse(root.alive);
        assertFalse(child.alive);
    }

    @Test
    void neverTouchesProcessOutsideCapturedTree() {
        FakeTarget owned = new FakeTarget(402L).exitOnDestroy();
        FakeTarget unrelated = new FakeTarget(499L);
        FakeTarget root = new FakeTarget(401L).withDescendants(owned);
        root.onExitCommand(() -> root.alive = false);

        QualificationProcessCleanup.Result result = cleanup(root);

        assertEquals(QualificationProcessCleanup.Outcome.TERMINATED, result.outcome());
        assertTrue(unrelated.alive);
        assertEquals(0, unrelated.destroyCalls);
        assertEquals(0, unrelated.forceCalls);
        assertFalse(result.capturedDescendantPids().contains(unrelated.pid()));
    }

    @Test
    void terminatesExactCampaignServerAfterRootHasAlreadyExited() {
        FakeTarget campaignServer = new FakeTarget(502L).exitOnDestroy();
        FakeTarget unrelated = new FakeTarget(599L);
        FakeTarget root = new FakeTarget(501L);
        root.alive = false;

        QualificationProcessCleanup.Result result = QualificationProcessCleanup.stop(
                root, List.of(campaignServer), NO_WAIT, NO_WAIT, NO_WAIT);

        assertEquals(QualificationProcessCleanup.Outcome.TERMINATED, result.outcome());
        assertFalse(campaignServer.alive);
        assertEquals(List.of(502L), result.campaignServerPids());
        assertEquals(List.of(502L), result.normalStopPids());
        assertTrue(unrelated.alive);
        assertEquals(0, unrelated.destroyCalls);
        assertEquals(0, unrelated.forceCalls);
    }

    @Test
    void exactCampaignIdentityWinsOverDuplicateGenericDescendant() {
        FakeTarget generic = new FakeTarget(602L).withIdentityMismatch();
        FakeTarget exactCampaignServer = new FakeTarget(602L).exitOnDestroy();
        FakeTarget root = new FakeTarget(601L).withDescendants(generic);
        root.alive = false;

        QualificationProcessCleanup.Result result = QualificationProcessCleanup.stop(
                root, List.of(exactCampaignServer), NO_WAIT, NO_WAIT, NO_WAIT);

        assertEquals(QualificationProcessCleanup.Outcome.TERMINATED, result.outcome());
        assertFalse(exactCampaignServer.alive);
        assertTrue(generic.alive);
        assertEquals(0, generic.destroyCalls);
        assertEquals(List.of(602L), result.normalStopPids());
    }

    @Test
    void identityLossFailsClosedAndIsVisibleInEvidence() {
        FakeTarget child = new FakeTarget(702L).withIdentityMismatch();
        FakeTarget root = new FakeTarget(701L).withDescendants(child);
        root.onExitCommand(() -> root.alive = false);

        QualificationProcessCleanup.Result result = cleanup(root);

        assertEquals(QualificationProcessCleanup.Outcome.FAILED, result.outcome());
        assertEquals(List.of(702L), result.survivingPids());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("identity changed")));
        assertEquals("FAILED", result.evidence().get("outcome"));
        assertEquals(List.of(702L), result.evidence().get("survivingPids"));
    }

    @Test
    void approvalPromptFailureCleanupDoesNotWriteExitIntoThePrompt() {
        FakeTarget root = new FakeTarget(801L).exitOnDestroy();

        QualificationProcessCleanup.Result result = QualificationProcessCleanup.stop(
                root, List.of(), NO_WAIT, NO_WAIT, NO_WAIT, false);

        assertEquals("", root.inputText());
        assertFalse(result.gracefulExitRequested());
        assertEquals(QualificationProcessCleanup.Outcome.TERMINATED, result.outcome());
        assertEquals(1, root.destroyCalls);
    }

    private static QualificationProcessCleanup.Result cleanup(FakeTarget root) {
        return QualificationProcessCleanup.stop(root, List.of(), NO_WAIT, NO_WAIT, NO_WAIT);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }

    private static Path findOnPath(String executable) {
        String path = System.getenv("PATH");
        if (path != null) {
            for (String token : path.split(java.util.regex.Pattern.quote(
                    java.io.File.pathSeparator))) {
                if (token == null || token.isBlank()) continue;
                Path candidate = Path.of(token.strip()).resolve(executable)
                        .toAbsolutePath().normalize();
                if (Files.isRegularFile(candidate)) return candidate;
            }
        }
        throw new IllegalStateException("required test executable is unavailable");
    }

    private static void createSymbolicLinkOrAbort(Path link, Path target)
            throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            assumeTrue(false, "symbolic-link fixture unavailable: "
                    + unavailable.getClass().getSimpleName());
        }
    }

    private static final class NativeCleanupProcess extends Process {
        private final FakeNativeHandle rootHandle = new FakeNativeHandle(
                900L, Instant.parse("2026-09-05T00:00:00Z"));
        private final FakeNativeHandle child = new FakeNativeHandle(
                901L, rootHandle.started.plusSeconds(1));
        private final ByteArrayOutputStream input = new ByteArrayOutputStream();
        private boolean alive = true;
        private int descendantCalls;
        private int destroyCalls;
        private int forceCalls;
        private Runnable afterDiscovery = () -> {};
        private Runnable afterExitCommand = () -> {};

        @Override public long pid() { return rootHandle.pid(); }
        @Override public boolean isAlive() { return alive; }
        @Override public ProcessHandle toHandle() { return rootHandle; }
        @Override public Stream<ProcessHandle> descendants() {
            descendantCalls++;
            return Stream.<ProcessHandle>of(child).onClose(afterDiscovery);
        }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int value) { input.write(value); }
                @Override public void flush() {
                    alive = false;
                    rootHandle.alive = false;
                    afterExitCommand.run();
                }
            };
        }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return !alive; }
        @Override public int exitValue() {
            if (alive) throw new IllegalThreadStateException("synthetic process is alive");
            return 0;
        }
        @Override public void destroy() {
            destroyCalls++;
            alive = false;
            rootHandle.alive = false;
        }
        @Override public Process destroyForcibly() {
            forceCalls++;
            alive = false;
            rootHandle.alive = false;
            return this;
        }
        private String inputText() { return input.toString(StandardCharsets.UTF_8); }
    }

    private static final class FakeNativeHandle implements ProcessHandle {
        private final long pid;
        private Instant started;
        private boolean alive = true;
        private int destroyCalls;
        private int forceCalls;

        private FakeNativeHandle(long pid, Instant started) {
            this.pid = pid;
            this.started = started;
        }

        @Override public long pid() { return pid; }
        @Override public boolean isAlive() { return alive; }
        @Override public Optional<ProcessHandle> parent() { return Optional.empty(); }
        @Override public Stream<ProcessHandle> children() { return Stream.empty(); }
        @Override public Stream<ProcessHandle> descendants() { return Stream.empty(); }
        @Override public Info info() {
            Instant capturedStart = started;
            return new Info() {
                @Override public Optional<String> command() { return Optional.empty(); }
                @Override public Optional<String> commandLine() { return Optional.empty(); }
                @Override public Optional<String[]> arguments() { return Optional.empty(); }
                @Override public Optional<Instant> startInstant() { return Optional.ofNullable(capturedStart); }
                @Override public Optional<Duration> totalCpuDuration() { return Optional.empty(); }
                @Override public Optional<String> user() { return Optional.empty(); }
            };
        }
        @Override public CompletableFuture<ProcessHandle> onExit() {
            // Deterministic non-blocking wait model; the caller separately checks alive.
            return CompletableFuture.completedFuture(this);
        }
        @Override public boolean supportsNormalTermination() { return true; }
        @Override public boolean destroy() {
            destroyCalls++;
            alive = false;
            return true;
        }
        @Override public boolean destroyForcibly() {
            forceCalls++;
            alive = false;
            return true;
        }
        @Override public int compareTo(ProcessHandle other) { return Long.compare(pid, other.pid()); }
    }

    private static final class FakeTarget implements QualificationProcessCleanup.Target {
        private final long pid;
        private final ByteArrayOutputStream input = new ByteArrayOutputStream();
        private final List<QualificationProcessCleanup.Target> descendants = new ArrayList<>();
        private Runnable exitCommand = () -> {};
        private boolean alive = true;
        private boolean destroyStops;
        private boolean forceStops;
        private boolean identityMatches = true;
        private int destroyCalls;
        private int forceCalls;

        private FakeTarget(long pid) {
            this.pid = pid;
        }

        private FakeTarget withDescendants(FakeTarget... targets) {
            descendants.addAll(List.of(targets));
            return this;
        }

        private FakeTarget onExitCommand(Runnable action) {
            exitCommand = action;
            return this;
        }

        private FakeTarget exitOnDestroy() {
            destroyStops = true;
            return this;
        }

        private FakeTarget exitOnForce() {
            forceStops = true;
            return this;
        }

        private FakeTarget withIdentityMismatch() {
            identityMatches = false;
            return this;
        }

        private String inputText() {
            return input.toString(StandardCharsets.UTF_8);
        }

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public boolean identityMatches() {
            return identityMatches;
        }

        @Override
        public List<QualificationProcessCleanup.Target> descendants() {
            return List.copyOf(descendants);
        }

        @Override
        public OutputStream input() {
            return new OutputStream() {
                @Override
                public void write(int value) {
                    input.write(value);
                }

                @Override
                public void flush() {
                    exitCommand.run();
                }
            };
        }

        @Override
        public boolean waitFor(Duration timeout) {
            return !alive;
        }

        @Override
        public void destroy() {
            destroyCalls++;
            if (destroyStops) alive = false;
        }

        @Override
        public void destroyForcibly() {
            forceCalls++;
            if (forceStops) alive = false;
        }
    }
}
