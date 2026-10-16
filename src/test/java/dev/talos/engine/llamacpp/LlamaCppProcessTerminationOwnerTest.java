package dev.talos.engine.llamacpp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlamaCppProcessTerminationOwnerTest {
    private static final LlamaCppProcessTerminationOwner.Policy IMMEDIATE =
            new LlamaCppProcessTerminationOwner.Policy(
                    Duration.ZERO,
                    Duration.ZERO);

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void forcedTimeoutRetainsLeaseUntilALaterExitIsProven() {
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        FakeTarget root = new FakeTarget(10L);
        root.gracefulLeavesAlive = true;
        root.forceLeavesAlive = true;
        LlamaCppProcessTerminationOwner.Lease lease = owner.adopt(root);

        LlamaCppProcessTerminationOwner.Outcome first =
                lease.terminate(IMMEDIATE);

        assertEquals(
                LlamaCppProcessTerminationOwner.State.RETAINED_LIVE,
                first.state());
        assertTrue(first.causes().contains(
                LlamaCppProcessTerminationOwner.Cause.FORCED_TIMEOUT));
        assertEquals(1, owner.ownedLeaseCount());

        root.alive = false;
        LlamaCppProcessTerminationOwner.Outcome second =
                lease.terminate(IMMEDIATE);

        assertEquals(
                LlamaCppProcessTerminationOwner.State.TERMINATED,
                second.state());
        assertEquals(0, owner.ownedLeaseCount());
    }

    @Test
    void interruptionDoesNotSkipForceAndIsRestoredOnReturn() {
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        FakeTarget root = new FakeTarget(20L);
        root.gracefulLeavesAlive = true;
        root.forceLeavesAlive = true;
        root.interruptNextWait = true;
        LlamaCppProcessTerminationOwner.Lease lease = owner.adopt(root);

        LlamaCppProcessTerminationOwner.Outcome outcome =
                lease.terminate(new LlamaCppProcessTerminationOwner.Policy(
                        Duration.ofMillis(1),
                        Duration.ofMillis(1)));

        assertEquals(
                LlamaCppProcessTerminationOwner.State.RETAINED_LIVE,
                outcome.state());
        assertTrue(root.forceSignals > 0,
                "interruption must not skip the forced termination phase");
        assertTrue(outcome.interruptRestored());
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(1, owner.ownedLeaseCount());
    }

    @Test
    void aSurvivingCapturedDescendantPreventsReleaseAfterRootExit() {
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        FakeTarget root = new FakeTarget(30L);
        FakeTarget child = new FakeTarget(31L);
        child.gracefulLeavesAlive = true;
        child.forceLeavesAlive = true;
        root.children.add(child);
        LlamaCppProcessTerminationOwner.Lease lease = owner.adopt(root);

        LlamaCppProcessTerminationOwner.Outcome outcome =
                lease.terminate(IMMEDIATE);

        assertFalse(root.alive);
        assertTrue(child.alive);
        assertEquals(
                LlamaCppProcessTerminationOwner.State.RETAINED_LIVE,
                outcome.state());
        assertEquals(2, outcome.trackedProcessCount());
        assertEquals(1, owner.ownedLeaseCount());
    }

    @Test
    void descendantEnumerationFailureCannotBeRewrittenAsRootTermination() {
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        FakeTarget root = new FakeTarget(40L);
        root.failDescendantEnumeration = true;
        LlamaCppProcessTerminationOwner.Lease lease = owner.adopt(root);

        LlamaCppProcessTerminationOwner.Outcome outcome =
                lease.terminate(IMMEDIATE);

        assertFalse(root.alive);
        assertEquals(
                LlamaCppProcessTerminationOwner.State.RETAINED_UNVERIFIED,
                outcome.state());
        assertTrue(outcome.causes().contains(
                LlamaCppProcessTerminationOwner.Cause
                        .TREE_ENUMERATION_FAILED));
        assertEquals(1, owner.ownedLeaseCount());
    }

    @Test
    void descendantSignalsPrecedeTheRootSignal() {
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        List<String> signals = new ArrayList<>();
        FakeTarget root = new FakeTarget(50L, signals);
        FakeTarget child = new FakeTarget(51L, signals);
        root.children.add(child);
        owner.adopt(root).terminate(IMMEDIATE);

        assertEquals(List.of("graceful-51", "graceful-50"), signals);
    }

    @Test
    void forcedOnlySignalsDescendantsBeforeRootWithoutAGracefulPhase() {
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        List<String> signals = new ArrayList<>();
        FakeTarget root = new FakeTarget(52L, signals);
        FakeTarget child = new FakeTarget(53L, signals);
        root.children.add(child);

        LlamaCppProcessTerminationOwner.Outcome outcome = owner.adopt(root)
                .terminate(LlamaCppProcessTerminationOwner.Policy.forcedOnly(
                        Duration.ZERO,
                        Duration.ZERO));

        assertEquals(List.of("force-53", "force-52"), signals);
        assertEquals(
                LlamaCppProcessTerminationOwner.State.TERMINATED,
                outcome.state());
        assertEquals(0, owner.ownedLeaseCount());
    }

    @Test
    void forcedOnlyRejectsANonzeroGracefulTimeout() {
        assertThrows(IllegalArgumentException.class, () ->
                new LlamaCppProcessTerminationOwner.Policy(
                        Duration.ofMillis(1),
                        Duration.ZERO,
                        Duration.ZERO,
                        LlamaCppProcessTerminationOwner.TerminationMode
                                .FORCED_ONLY));
    }

    @Test
    void forcedOnlyRetainsATreeThatWasUnstableBeforeAnySignal() {
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        FakeTarget root = new FakeTarget(54L);
        FakeTarget child = new FakeTarget(55L);
        root.children.add(child);
        root.exitDuringDescendantEnumeration = true;

        LlamaCppProcessTerminationOwner.Outcome outcome = owner.adopt(root)
                .terminate(LlamaCppProcessTerminationOwner.Policy.forcedOnly(
                        Duration.ZERO,
                        Duration.ZERO));

        assertEquals(
                LlamaCppProcessTerminationOwner.State.RETAINED_UNVERIFIED,
                outcome.state());
        assertTrue(outcome.causes().contains(
                LlamaCppProcessTerminationOwner.Cause.TREE_CAPTURE_UNSTABLE));
        assertEquals(1, owner.ownedLeaseCount());
    }

    @Test
    void changedDescendantIdentityIsRetainedAndNeverSignalled() {
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        FakeTarget root = new FakeTarget(60L);
        FakeTarget child = new FakeTarget(61L);
        child.identityMatches = false;
        root.children.add(child);

        LlamaCppProcessTerminationOwner.Lease lease = owner.adopt(root);
        LlamaCppProcessTerminationOwner.Outcome outcome =
                lease.terminate(IMMEDIATE);

        assertEquals(
                LlamaCppProcessTerminationOwner.State.RETAINED_LIVE,
                outcome.state());
        assertTrue(outcome.causes().contains(
                LlamaCppProcessTerminationOwner.Cause
                        .PROCESS_IDENTITY_AMBIGUOUS));
        assertEquals(0, child.gracefulSignals);
        assertEquals(0, child.forceSignals);

        child.alive = false;
        LlamaCppProcessTerminationOwner.Outcome retry =
                lease.terminate(IMMEDIATE);

        assertEquals(
                LlamaCppProcessTerminationOwner.State.RETAINED_UNVERIFIED,
                retry.state());
        assertTrue(retry.causes().contains(
                LlamaCppProcessTerminationOwner.Cause
                        .PROCESS_IDENTITY_AMBIGUOUS));
        assertEquals(1, owner.ownedLeaseCount());
    }

    @Test
    void rootExitDuringDescendantEnumerationIsPermanentlyUnverified() {
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        FakeTarget root = new FakeTarget(65L);
        FakeTarget child = new FakeTarget(66L);
        root.children.add(child);
        root.exitDuringDescendantEnumeration = true;

        LlamaCppProcessTerminationOwner.Lease lease = owner.adopt(root);
        LlamaCppProcessTerminationOwner.Outcome outcome =
                lease.terminate(IMMEDIATE);

        assertFalse(child.alive,
                "a child observed before the unstable post-check remains owned");
        assertEquals(2, outcome.trackedProcessCount());
        assertEquals(
                LlamaCppProcessTerminationOwner.State.RETAINED_UNVERIFIED,
                outcome.state());
        assertTrue(outcome.causes().contains(
                LlamaCppProcessTerminationOwner.Cause.TREE_CAPTURE_UNSTABLE));
    }

    @Test
    void rootIdentityChangeDuringChildMaterializationIsUnverified() {
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        FakeTarget root = new FakeTarget(67L);
        FakeTarget child = new FakeTarget(68L);
        child.identityReadHook = () -> root.identityMatches = false;
        root.children.add(child);

        LlamaCppProcessTerminationOwner.Lease lease = owner.adopt(root);
        root.alive = false;
        LlamaCppProcessTerminationOwner.Outcome outcome =
                lease.terminate(IMMEDIATE);

        assertEquals(
                LlamaCppProcessTerminationOwner.State.RETAINED_UNVERIFIED,
                outcome.state());
        assertTrue(outcome.causes().contains(
                LlamaCppProcessTerminationOwner.Cause.TREE_CAPTURE_UNSTABLE));
        assertTrue(outcome.causes().contains(
                LlamaCppProcessTerminationOwner.Cause
                        .PROCESS_IDENTITY_AMBIGUOUS));
    }

    @Test
    void retainedLeaseBlocksLaunchUntilRetryProvesExit() {
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        FakeTarget root = new FakeTarget(69L);
        root.gracefulLeavesAlive = true;
        root.forceLeavesAlive = true;
        LlamaCppProcessTerminationOwner.Lease lease = owner.adopt(root);
        assertEquals(
                LlamaCppProcessTerminationOwner.State.RETAINED_LIVE,
                lease.terminate(IMMEDIATE).state());

        assertThrows(
                LlamaCppProcessTerminationOwner.LaunchBlocked.class,
                () -> owner.retryRetainedAndAcquire(IMMEDIATE));
        assertEquals(1, owner.ownedLeaseCount());

        root.alive = false;
        try (LlamaCppProcessTerminationOwner.LaunchPermit permit =
                     owner.retryRetainedAndAcquire(IMMEDIATE)) {
            assertEquals(0, owner.ownedLeaseCount());
        }
    }

    @Test
    void activeLeaseIsBlockedWithoutBeingKilledByReplacementRecovery() {
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        FakeTarget root = new FakeTarget(69_001L);
        owner.adopt(root);

        assertThrows(
                LlamaCppProcessTerminationOwner.LaunchBlocked.class,
                () -> owner.retryRetainedAndAcquire(IMMEDIATE));

        assertTrue(root.alive);
        assertEquals(0, root.gracefulSignals);
        assertEquals(0, root.forceSignals);
    }

    @Test
    void concurrentLaunchReservationAllowsExactlyOnePermit() throws Exception {
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        Thread first = Thread.ofPlatform().start(() -> {
            try (LlamaCppProcessTerminationOwner.LaunchPermit permit =
                         owner.retryRetainedAndAcquire(IMMEDIATE)) {
                acquired.countDown();
                release.await();
            } catch (Throwable failure) {
                firstFailure.set(failure);
            }
        });
        assertTrue(acquired.await(2L, TimeUnit.SECONDS));

        assertThrows(
                LlamaCppProcessTerminationOwner.LaunchBlocked.class,
                () -> owner.retryRetainedAndAcquire(IMMEDIATE));

        release.countDown();
        first.join(2_000L);
        assertFalse(first.isAlive());
        assertEquals(null, firstFailure.get());
        try (LlamaCppProcessTerminationOwner.LaunchPermit permit =
                     owner.retryRetainedAndAcquire(IMMEDIATE)) {
            assertEquals(0, owner.ownedLeaseCount());
        }
    }

    @Test
    void transientExitObservationFailureCanBeRetriedAndReleased() {
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        FakeTarget root = new FakeTarget(70L);
        LlamaCppProcessTerminationOwner.Lease lease = owner.adopt(root);
        root.alive = false;
        root.failNextAliveObservation = true;

        LlamaCppProcessTerminationOwner.Outcome first =
                lease.terminate(IMMEDIATE);

        assertEquals(
                LlamaCppProcessTerminationOwner.State.RETAINED_UNVERIFIED,
                first.state());
        assertTrue(first.causes().contains(
                LlamaCppProcessTerminationOwner.Cause
                        .EXIT_OBSERVATION_FAILED));
        assertEquals(1, owner.ownedLeaseCount());

        LlamaCppProcessTerminationOwner.Outcome second =
                lease.terminate(IMMEDIATE);

        assertEquals(
                LlamaCppProcessTerminationOwner.State.TERMINATED,
                second.state());
        assertFalse(second.causes().contains(
                LlamaCppProcessTerminationOwner.Cause
                        .EXIT_OBSERVATION_FAILED));
        assertEquals(0, owner.ownedLeaseCount());
    }

    @Test
    void transientResourceCloseFailureCanBeRetriedAndReleased() {
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        FakeTarget root = new FakeTarget(80L);
        root.failNextResourceClose = true;
        LlamaCppProcessTerminationOwner.Lease lease = owner.adopt(root);

        LlamaCppProcessTerminationOwner.Outcome first =
                lease.terminate(IMMEDIATE);

        assertEquals(
                LlamaCppProcessTerminationOwner.State.RETAINED_UNVERIFIED,
                first.state());
        assertTrue(first.causes().contains(
                LlamaCppProcessTerminationOwner.Cause
                        .RESOURCE_SETTLEMENT_FAILED));
        assertEquals(1, root.resourceCloseAttempts);
        assertEquals(1, owner.ownedLeaseCount());

        LlamaCppProcessTerminationOwner.Outcome second =
                lease.terminate(IMMEDIATE);

        assertEquals(
                LlamaCppProcessTerminationOwner.State.TERMINATED,
                second.state());
        assertEquals(2, root.resourceCloseAttempts);
        assertEquals(0, owner.ownedLeaseCount());
    }

    @Test
    void directProcessWithUnavailablePidIsStillAdoptedAndRetained() {
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        ThrowingPidProcess process = new ThrowingPidProcess();

        LlamaCppProcessTerminationOwner.Lease lease = owner.adopt(process);
        LlamaCppProcessTerminationOwner.Outcome outcome =
                lease.terminate(IMMEDIATE);

        assertTrue(process.destroyed);
        assertEquals(
                LlamaCppProcessTerminationOwner.State.RETAINED_UNVERIFIED,
                outcome.state());
        assertTrue(outcome.causes().contains(
                LlamaCppProcessTerminationOwner.Cause
                        .TREE_ENUMERATION_FAILED));
        assertEquals(1, owner.ownedLeaseCount());
    }

    private static final class FakeTarget
            implements LlamaCppProcessTerminationOwner.Target {
        private final LlamaCppProcessTerminationOwner.ProcessIdentity identity;
        private final List<LlamaCppProcessTerminationOwner.Target> children =
                new ArrayList<>();
        private final List<String> signals;
        private boolean alive = true;
        private boolean identityMatches = true;
        private boolean gracefulLeavesAlive;
        private boolean forceLeavesAlive;
        private boolean failDescendantEnumeration;
        private boolean exitDuringDescendantEnumeration;
        private boolean failNextAliveObservation;
        private boolean failNextResourceClose;
        private boolean interruptNextWait;
        private int gracefulSignals;
        private int forceSignals;
        private int resourceCloseAttempts;
        private Runnable identityReadHook;

        private FakeTarget(long processId) {
            this(processId, new ArrayList<>());
        }

        private FakeTarget(long processId, List<String> signals) {
            this.identity =
                    new LlamaCppProcessTerminationOwner.ProcessIdentity(
                            processId,
                            1_000L + processId,
                            LlamaCppProcessTerminationOwner.IdentityAuthority
                                    .EXACT_PID_START);
            this.signals = signals;
        }

        @Override
        public LlamaCppProcessTerminationOwner.ProcessIdentity identity() {
            if (identityReadHook != null) {
                Runnable hook = identityReadHook;
                identityReadHook = null;
                hook.run();
            }
            return identity;
        }

        @Override
        public boolean identityStillMatches() {
            return identityMatches;
        }

        @Override
        public boolean isAlive() {
            if (failNextAliveObservation) {
                failNextAliveObservation = false;
                throw new IllegalStateException(
                        "injected exit observation failure");
            }
            return alive;
        }

        @Override
        public List<LlamaCppProcessTerminationOwner.Target> descendants() {
            if (failDescendantEnumeration) {
                throw new IllegalStateException(
                        "injected descendant enumeration failure");
            }
            if (exitDuringDescendantEnumeration) alive = false;
            return List.copyOf(children);
        }

        @Override
        public void destroy() {
            gracefulSignals++;
            signals.add("graceful-" + identity.processId());
            if (!gracefulLeavesAlive) alive = false;
        }

        @Override
        public void destroyForcibly() {
            forceSignals++;
            signals.add("force-" + identity.processId());
            if (!forceLeavesAlive) alive = false;
        }

        @Override
        public boolean waitFor(Duration timeout) throws InterruptedException {
            if (interruptNextWait) {
                interruptNextWait = false;
                throw new InterruptedException(
                        "injected termination interruption");
            }
            return !alive;
        }

        @Override
        public boolean closeResources(Duration timeout) {
            resourceCloseAttempts++;
            if (failNextResourceClose) {
                failNextResourceClose = false;
                throw new IllegalStateException(
                        "injected resource close failure");
            }
            return false;
        }
    }

    @Test
    void exitCodeWithoutSignalledExitIsNotReportedAsTerminated() {
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        LingeringExitProcess process = new LingeringExitProcess();
        LlamaCppProcessTerminationOwner.Lease lease = owner.adopt(process);

        // Shaped like a CUDA server on Windows: the exit code is set when termination starts,
        // while the operating system has not yet signalled that the process exited. The fake's
        // completion models the root reaper created at adoption, which waits for that signal.
        LlamaCppProcessTerminationOwner.Outcome lingering = lease.terminate(IMMEDIATE);
        assertTrue(process.destroyed);
        assertFalse(lingering.terminated(), lingering.safeSummary());
        assertEquals(LlamaCppProcessTerminationOwner.State.RETAINED_LIVE, lingering.state());
        assertTrue(lingering.causes().contains(
                LlamaCppProcessTerminationOwner.Cause.EXIT_SIGNAL_PENDING), lingering.safeSummary());
        assertFalse(lingering.causes().contains(
                LlamaCppProcessTerminationOwner.Cause.FORCED_TIMEOUT), lingering.safeSummary());
        assertEquals(1, owner.ownedLeaseCount());

        // A lingering result stays retriable, it does not poison later launches.
        process.signalExit();
        LlamaCppProcessTerminationOwner.Outcome settled = lease.terminate(IMMEDIATE);
        assertTrue(settled.terminated(), settled.safeSummary());
        assertEquals(0, owner.ownedLeaseCount());
    }

    @Test
    void exitSignalledDuringTheProofWaitSettlesWithoutAnUnstableTree() throws Exception {
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        LingeringExitProcess process = new LingeringExitProcess();
        LlamaCppProcessTerminationOwner.Lease lease = owner.adopt(process);
        Thread signaller = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException stop) {
                return;
            }
            process.signalExit();
        });
        signaller.start();

        LlamaCppProcessTerminationOwner.Outcome outcome = lease.terminate(IMMEDIATE);
        signaller.join(5_000);

        assertTrue(outcome.terminated(), outcome.safeSummary());
        assertFalse(outcome.causes().contains(
                LlamaCppProcessTerminationOwner.Cause.TREE_CAPTURE_UNSTABLE), outcome.safeSummary());
        assertEquals(0, owner.ownedLeaseCount());
    }

    @Test
    void interruptedProofWaitIsNotATerminationAndKeepsTheInterrupt() throws Exception {
        LlamaCppProcessTerminationOwner owner =
                new LlamaCppProcessTerminationOwner();
        LingeringExitProcess process = new LingeringExitProcess();
        LlamaCppProcessTerminationOwner.Lease lease = owner.adopt(process);
        AtomicReference<LlamaCppProcessTerminationOwner.Outcome> outcome = new AtomicReference<>();
        AtomicReference<Boolean> interruptKept = new AtomicReference<>();
        CountDownLatch waiting = new CountDownLatch(1);
        Thread terminator = new Thread(() -> {
            waiting.countDown();
            outcome.set(lease.terminate(IMMEDIATE));
            interruptKept.set(Thread.currentThread().isInterrupted());
        });
        terminator.start();
        assertTrue(waiting.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);
        terminator.interrupt();
        terminator.join(5_000);

        assertFalse(outcome.get().terminated(), outcome.get().safeSummary());
        assertTrue(outcome.get().causes().contains(
                LlamaCppProcessTerminationOwner.Cause.INTERRUPTED), outcome.get().safeSummary());
        assertEquals(Boolean.TRUE, interruptKept.get());
        assertEquals(1, owner.ownedLeaseCount());
    }

    /** The exit code is set by destroy, while exit completion stays pending until signalled. */
    private static final class LingeringExitProcess extends Process {
        private final CompletableFuture<Process> exit = new CompletableFuture<>();
        private final ProcessHandle handle = new LingeringHandle(exit);
        private boolean exitCodeSet;
        private boolean destroyed;

        void signalExit() {
            exit.complete(this);
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 1;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            // The JDK Windows implementation returns at once when the exit code is set.
            return exitCodeSet;
        }

        @Override
        public int exitValue() {
            if (!exitCodeSet) throw new IllegalThreadStateException();
            return 1;
        }

        @Override
        public void destroy() {
            destroyed = true;
            exitCodeSet = true;
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return !exitCodeSet;
        }

        @Override
        public long pid() {
            return 999_999_937L;
        }

        @Override
        public CompletableFuture<Process> onExit() {
            return exit;
        }

        @Override
        public ProcessHandle toHandle() {
            return handle;
        }
    }

    private record LingeringHandle(CompletableFuture<Process> exit) implements ProcessHandle {
        @Override public long pid() { return 999_999_937L; }
        @Override public Optional<ProcessHandle> parent() { return Optional.empty(); }
        @Override public java.util.stream.Stream<ProcessHandle> children() { return java.util.stream.Stream.empty(); }
        @Override public java.util.stream.Stream<ProcessHandle> descendants() { return java.util.stream.Stream.empty(); }
        @Override public Info info() {
            return new Info() {
                @Override public Optional<String> command() { return Optional.empty(); }
                @Override public Optional<String> commandLine() { return Optional.empty(); }
                @Override public Optional<String[]> arguments() { return Optional.empty(); }
                @Override public Optional<java.time.Instant> startInstant() { return Optional.empty(); }
                @Override public Optional<Duration> totalCpuDuration() { return Optional.empty(); }
                @Override public Optional<String> user() { return Optional.empty(); }
            };
        }
        @Override public CompletableFuture<ProcessHandle> onExit() { return exit.thenApply(done -> this); }
        @Override public boolean supportsNormalTermination() { return false; }
        @Override public boolean destroy() { return true; }
        @Override public boolean destroyForcibly() { return true; }
        @Override public boolean isAlive() { return !exit.isDone(); }
        @Override public int compareTo(ProcessHandle other) { return Long.compare(pid(), other.pid()); }
    }

    private static final class ThrowingPidProcess extends Process {
        private boolean alive = true;
        private boolean destroyed;

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            alive = false;
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive;
        }

        @Override
        public int exitValue() {
            if (alive) throw new IllegalThreadStateException();
            return 0;
        }

        @Override
        public void destroy() {
            destroyed = true;
            alive = false;
        }

        @Override
        public Process destroyForcibly() {
            destroyed = true;
            alive = false;
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public long pid() {
            throw new UnsupportedOperationException(
                    "injected PID observation failure");
        }
    }
}
