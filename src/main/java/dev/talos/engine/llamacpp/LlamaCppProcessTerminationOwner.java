package dev.talos.engine.llamacpp;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns a launched llama.cpp process until root and captured-descendant exit is
 * mechanically observed.
 *
 * <p>The system owner keeps a strong reference to every active or unsettled
 * lease. A failed termination attempt therefore cannot silently turn a live
 * child into an unowned process.
 */
final class LlamaCppProcessTerminationOwner {
    enum State {
        TERMINATED,
        RETAINED_LIVE,
        RETAINED_UNVERIFIED
    }

    enum Cause {
        INTERRUPTED,
        TREE_ENUMERATION_FAILED,
        TREE_CAPTURE_UNSTABLE,
        PROCESS_IDENTITY_AMBIGUOUS,
        SIGNAL_FAILED,
        FORCED_TIMEOUT,
        /** The exit code is set, but the operating system has not yet signalled that the process exited. */
        EXIT_SIGNAL_PENDING,
        EXIT_OBSERVATION_FAILED,
        RESOURCE_SETTLEMENT_FAILED
    }

    enum IdentityAuthority {
        DIRECT_PROCESS_CAPABILITY,
        EXACT_PID_START,
        UNAVAILABLE
    }

    enum TerminationMode {
        GRACEFUL_THEN_FORCED,
        FORCED_ONLY
    }

    record ProcessIdentity(
            long processId,
            long processStartEpochMilli,
            IdentityAuthority authority) {
        ProcessIdentity {
            authority = Objects.requireNonNull(authority, "identity authority");
            if (processId < 0L
                    || (processId == 0L
                        && authority
                                != IdentityAuthority.DIRECT_PROCESS_CAPABILITY)) {
                throw new IllegalArgumentException(
                        "owned process ID must be positive unless a direct "
                                + "process capability has no observable PID");
            }
            if (processStartEpochMilli < 0L) {
                throw new IllegalArgumentException(
                        "owned process start must not be negative");
            }
            if (authority == IdentityAuthority.EXACT_PID_START
                    && (processId < 1L || processStartEpochMilli < 1L)) {
                throw new IllegalArgumentException(
                        "exact process identity requires a start time");
            }
        }

        boolean signalAuthorized() {
            return authority != IdentityAuthority.UNAVAILABLE;
        }
    }

    record Policy(
            Duration gracefulTimeout,
            Duration forcedTimeout,
            Duration resourceTimeout,
            TerminationMode mode) {
        Policy {
            gracefulTimeout = requireTimeout(gracefulTimeout, "graceful timeout");
            forcedTimeout = requireTimeout(forcedTimeout, "forced timeout");
            resourceTimeout = requireTimeout(resourceTimeout, "resource timeout");
            mode = Objects.requireNonNull(mode, "termination mode");
            if (mode == TerminationMode.FORCED_ONLY
                    && !gracefulTimeout.isZero()) {
                throw new IllegalArgumentException(
                        "forced-only termination requires a zero graceful timeout");
            }
        }

        Policy(
                Duration gracefulTimeout,
                Duration forcedTimeout,
                Duration resourceTimeout) {
            this(
                    gracefulTimeout,
                    forcedTimeout,
                    resourceTimeout,
                    TerminationMode.GRACEFUL_THEN_FORCED);
        }

        Policy(Duration gracefulTimeout, Duration forcedTimeout) {
            this(gracefulTimeout, forcedTimeout, Duration.ofSeconds(2));
        }

        static Policy forcedOnly(
                Duration forcedTimeout,
                Duration resourceTimeout) {
            return new Policy(
                    Duration.ZERO,
                    forcedTimeout,
                    resourceTimeout,
                    TerminationMode.FORCED_ONLY);
        }

        private static Duration requireTimeout(Duration value, String label) {
            Duration exact = Objects.requireNonNull(value, label);
            if (exact.isNegative()) {
                throw new IllegalArgumentException(label + " must not be negative");
            }
            return exact;
        }
    }

    record Outcome(
            State state,
            Set<Cause> causes,
            int trackedProcessCount,
            boolean interruptRestored) {
        Outcome {
            state = Objects.requireNonNull(state, "termination state");
            if (causes == null || causes.isEmpty()) {
                causes = Set.of();
            } else {
                causes = Collections.unmodifiableSet(EnumSet.copyOf(causes));
            }
            if (trackedProcessCount < 1) {
                throw new IllegalArgumentException(
                        "termination outcome must track at least the root process");
            }
        }

        boolean terminated() {
            return state == State.TERMINATED;
        }

        String safeSummary() {
            return state.name() + (causes.isEmpty() ? "" : ":" + causes);
        }
    }

    static final class Unsettled extends IllegalStateException {
        private final Outcome outcome;
        private final Lease lease;

        Unsettled(
                String message,
                Throwable cause,
                Outcome outcome,
                Lease lease) {
            super(message, cause);
            this.outcome = Objects.requireNonNull(outcome, "termination outcome");
            this.lease = Objects.requireNonNull(lease, "retained process lease");
            if (outcome.terminated()) {
                throw new IllegalArgumentException(
                        "an unsettled failure requires an unsettled outcome");
            }
        }

        Outcome outcome() {
            return outcome;
        }

        Lease lease() {
            return lease;
        }
    }

    static final class LaunchBlocked extends IllegalStateException {
        LaunchBlocked(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    interface ResourceSettlement {
        ResourceSettlement NONE = timeout -> false;

        /**
         * Settles every parent-owned resource associated with the child.
         *
         * @return whether interruption was observed and consumed while the
         *         bounded settlement continued
         */
        boolean settle(Duration timeout);

        default boolean ownsProcessStdout() {
            return false;
        }
    }

    @FunctionalInterface
    interface ProcessTargetFactory {
        Target create(Process process, ResourceSettlement resources);
    }

    @FunctionalInterface
    interface ResourceAttemptCompletion {
        ResourceAttemptCompletion NONE = () -> {};

        void beforeComplete();
    }

    @FunctionalInterface
    interface ResourceAttemptJoinObserver {
        ResourceAttemptJoinObserver NONE = sequence -> {};

        void joined(long sequence);

        default void interrupted(long sequence) {}

        default void followOnSelected(
                long joinedSequence,
                long followOnSequence) {}
    }

    interface Target {
        ProcessIdentity identity();

        boolean identityStillMatches();

        boolean isAlive();

        List<Target> descendants();

        void destroy();

        void destroyForcibly();

        boolean waitFor(Duration timeout) throws InterruptedException;

        /** True once the operating system has signalled exit. Targets without that signal rely on isAlive. */
        default boolean awaitExitProof(Duration timeout) throws InterruptedException {
            return true;
        }

        default boolean owns(
                Process process,
                ResourceSettlement resources) {
            return false;
        }

        default boolean closeResources(Duration timeout) {
            return false;
        }
    }

    static final class Lease {
        private final LlamaCppProcessTerminationOwner owner;
        private Target root;
        private final Map<ProcessIdentity, Target> tracked =
                new LinkedHashMap<>();
        private boolean treeCapturedWhileRootAlive;
        private boolean permanentlyUnverified;
        private final EnumSet<Cause> permanentCauses =
                EnumSet.noneOf(Cause.class);
        private Outcome lastOutcome;

        private Lease(LlamaCppProcessTerminationOwner owner, Target root) {
            this.owner = owner;
            this.root = root;
            this.tracked.put(root.identity(), root);
        }

        synchronized Outcome terminate(Policy policy) {
            if (lastOutcome != null && lastOutcome.terminated()) {
                return lastOutcome;
            }
            lastOutcome = owner.terminate(this, Objects.requireNonNull(
                    policy, "termination policy"));
            return lastOutcome;
        }

        private void detachTerminatedTargets() {
            tracked.clear();
            root = null;
        }

        synchronized Outcome lastOutcome() {
            return lastOutcome;
        }

        synchronized boolean owns(
                Process process,
                ResourceSettlement resources) {
            return root != null && root.owns(process, resources);
        }

        synchronized boolean allTrackedExitedWithoutSignalling() {
            if (root == null || tracked.isEmpty()) return true;
            for (Target target : List.copyOf(tracked.values())) {
                try {
                    if (target.isAlive()) return false;
                } catch (RuntimeException unavailable) {
                    return false;
                }
            }
            return true;
        }
    }

    static final class LaunchPermit implements AutoCloseable {
        private final LlamaCppProcessTerminationOwner owner;
        private boolean open = true;
        private volatile Lease adoptedLease;

        private LaunchPermit(LlamaCppProcessTerminationOwner owner) {
            this.owner = owner;
        }

        Lease adopt(Process process, ResourceSettlement resources) {
            return owner.adopt(this, process, resources);
        }

        Lease adoptedLease() {
            return adoptedLease;
        }

        @Override
        public void close() {
            owner.cancel(this);
        }
    }

    private static final LlamaCppProcessTerminationOwner SYSTEM =
            new LlamaCppProcessTerminationOwner();

    private final Set<Lease> owned =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final ProcessTargetFactory processTargetFactory;
    private LaunchPermit reservedLaunch;

    LlamaCppProcessTerminationOwner() {
        this(
                ResourceAttemptCompletion.NONE,
                ResourceAttemptJoinObserver.NONE);
    }

    LlamaCppProcessTerminationOwner(
            ResourceAttemptCompletion resourceAttemptCompletion) {
        this(resourceAttemptCompletion, ResourceAttemptJoinObserver.NONE);
    }

    LlamaCppProcessTerminationOwner(
            ResourceAttemptCompletion resourceAttemptCompletion,
            ResourceAttemptJoinObserver resourceAttemptJoinObserver) {
        ResourceAttemptCompletion exact = Objects.requireNonNull(
                resourceAttemptCompletion,
                "resource attempt completion");
        ResourceAttemptJoinObserver exactJoinObserver = Objects.requireNonNull(
                resourceAttemptJoinObserver,
                "resource attempt join observer");
        this.processTargetFactory = (process, resources) ->
                new ProcessTarget(
                        process,
                        resources,
                        exact,
                        exactJoinObserver);
    }

    LlamaCppProcessTerminationOwner(ProcessTargetFactory processTargetFactory) {
        this.processTargetFactory = Objects.requireNonNull(
                processTargetFactory, "process target factory");
    }

    static LlamaCppProcessTerminationOwner system() {
        return SYSTEM;
    }

    Lease adopt(Process process) {
        Objects.requireNonNull(process, "process");
        if (this == SYSTEM) {
            throw new IllegalStateException(
                    "the system process owner requires an atomic launch permit");
        }
        Target target = Objects.requireNonNull(
                processTargetFactory.create(process, ResourceSettlement.NONE),
                "process target");
        if (!target.owns(process, ResourceSettlement.NONE)) {
            throw new IllegalStateException(
                    "process target does not own the exact process resources");
        }
        return adopt(target);
    }

    synchronized Lease adopt(Target root) {
        if (this == SYSTEM) {
            throw new IllegalStateException(
                    "the system process owner requires an atomic launch permit");
        }
        if (reservedLaunch != null) {
            throw new LaunchBlocked(
                    "managed llama.cpp launch reservation is already active");
        }
        Target exact = Objects.requireNonNull(root, "root process");
        Objects.requireNonNull(exact.identity(), "root process identity");
        Lease lease = new Lease(this, exact);
        owned.add(lease);
        captureTree(lease, EnumSet.noneOf(Cause.class));
        return lease;
    }

    synchronized int ownedLeaseCount() {
        return owned.size();
    }

    LaunchPermit retryRetainedAndAcquire(Policy policy) {
        Objects.requireNonNull(policy, "termination policy");
        final LaunchPermit permit;
        final List<Lease> snapshot;
        synchronized (this) {
            if (reservedLaunch != null) {
                throw new LaunchBlocked(
                        "another managed llama.cpp launch is being established");
            }
            permit = new LaunchPermit(this);
            reservedLaunch = permit;
            snapshot = List.copyOf(owned);
        }

        boolean activeLeaseObserved = false;
        List<Outcome> outcomes = new ArrayList<>();
        try {
            for (Lease lease : snapshot) {
                Outcome previous = lease.lastOutcome();
                if (previous == null) {
                    if (lease.allTrackedExitedWithoutSignalling()) {
                        outcomes.add(lease.terminate(policy));
                    } else {
                        activeLeaseObserved = true;
                    }
                    continue;
                }
                if (!previous.terminated()) {
                    outcomes.add(lease.terminate(policy));
                }
            }

            synchronized (this) {
                if (reservedLaunch != permit) {
                    throw new LaunchBlocked(
                            "managed llama.cpp launch reservation changed unexpectedly");
                }
                if (activeLeaseObserved || !owned.isEmpty()) {
                    String detail = outcomes.isEmpty()
                            ? "active process ownership exists"
                            : outcomes.stream()
                                    .map(Outcome::safeSummary)
                                    .distinct()
                                    .sorted()
                                    .reduce((left, right) -> left + "," + right)
                                    .orElse("retained process ownership exists");
                    throw new LaunchBlocked(
                            "managed llama.cpp replacement launch is blocked: " + detail);
                }
                return permit;
            }
        } catch (RuntimeException | Error failure) {
            permit.close();
            throw failure;
        }
    }

    private Lease adopt(
            LaunchPermit permit,
            Process process,
            ResourceSettlement resources) {
        Process exactProcess = Objects.requireNonNull(process, "process");
        ResourceSettlement exactResources = Objects.requireNonNull(
                resources, "process resources");
        Target target = Objects.requireNonNull(
                processTargetFactory.create(exactProcess, exactResources),
                "process target");
        if (!target.owns(exactProcess, exactResources)) {
            throw new IllegalStateException(
                    "process target does not own the exact process resources");
        }
        final Lease lease;
        synchronized (this) {
            if (permit == null
                    || permit.owner != this
                    || !permit.open
                    || reservedLaunch != permit) {
                throw new IllegalStateException(
                        "managed llama.cpp launch permit is stale or unavailable");
            }
            permit.open = false;
            reservedLaunch = null;
            lease = new Lease(this, target);
            permit.adoptedLease = lease;
            owned.add(lease);
        }
        captureTree(lease, EnumSet.noneOf(Cause.class));
        return lease;
    }

    private synchronized void cancel(LaunchPermit permit) {
        if (permit == null || permit.owner != this || !permit.open) return;
        permit.open = false;
        if (reservedLaunch == permit) reservedLaunch = null;
    }

    private Outcome terminate(Lease lease, Policy policy) {
        EnumSet<Cause> causes = EnumSet.noneOf(Cause.class);
        causes.addAll(lease.permanentCauses);
        boolean restoreInterrupt = Thread.interrupted();
        if (restoreInterrupt) causes.add(Cause.INTERRUPTED);
        try {
            captureTree(lease, causes);
            if (policy.mode() == TerminationMode.GRACEFUL_THEN_FORCED) {
                signalGracefully(lease, causes);
                restoreInterrupt |= awaitTracked(
                        lease, policy.gracefulTimeout(), causes);

                if (safeAlive(lease.root, causes)) {
                    captureTree(lease, causes);
                }
            }
            signalForcibly(lease, causes);
            restoreInterrupt |= awaitTracked(
                    lease, policy.forcedTimeout(), causes);

            boolean live = anyTrackedAlive(lease, causes);
            if (live) causes.add(Cause.FORCED_TIMEOUT);
            if (!live && !awaitRootExitProof(lease, policy, causes)) {
                // The exit code is set but the operating system has not signalled exit yet.
                // The lease stays retained and a later attempt can settle it.
                causes.add(Cause.EXIT_SIGNAL_PENDING);
                live = true;
                restoreInterrupt |= Thread.interrupted();
            }
            if (!live) {
                restoreInterrupt |= closeTrackedResources(
                        lease,
                        policy.resourceTimeout(),
                        causes);
            }
            if (!lease.treeCapturedWhileRootAlive) {
                lease.permanentlyUnverified = true;
                causes.add(Cause.TREE_ENUMERATION_FAILED);
            }
            if (causes.contains(Cause.TREE_ENUMERATION_FAILED)
                    || causes.contains(Cause.TREE_CAPTURE_UNSTABLE)
                    || causes.contains(Cause.PROCESS_IDENTITY_AMBIGUOUS)) {
                lease.permanentlyUnverified = true;
                if (causes.contains(Cause.TREE_ENUMERATION_FAILED)) {
                    lease.permanentCauses.add(Cause.TREE_ENUMERATION_FAILED);
                }
                if (causes.contains(Cause.TREE_CAPTURE_UNSTABLE)) {
                    lease.permanentCauses.add(Cause.TREE_CAPTURE_UNSTABLE);
                }
                if (causes.contains(Cause.PROCESS_IDENTITY_AMBIGUOUS)) {
                    lease.permanentCauses.add(Cause.PROCESS_IDENTITY_AMBIGUOUS);
                }
            }

            State state;
            int trackedProcessCount = lease.tracked.size();
            if (live) {
                state = State.RETAINED_LIVE;
            } else if (lease.permanentlyUnverified
                    || causes.contains(Cause.EXIT_OBSERVATION_FAILED)
                    || causes.contains(Cause.RESOURCE_SETTLEMENT_FAILED)) {
                state = State.RETAINED_UNVERIFIED;
            } else {
                state = State.TERMINATED;
                release(lease);
                lease.detachTerminatedTargets();
            }
            return new Outcome(
                    state,
                    causes,
                    trackedProcessCount,
                    restoreInterrupt);
        } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt();
        }
    }

    private static void captureTree(Lease lease, EnumSet<Cause> causes) {
        if (!stableLiveRoot(lease, causes)) return;
        final List<Target> descendants;
        try {
            descendants = List.copyOf(Objects.requireNonNull(
                    lease.root.descendants(), "process descendants"));
        } catch (RuntimeException invalid) {
            lease.permanentlyUnverified = true;
            causes.add(Cause.TREE_ENUMERATION_FAILED);
            lease.permanentCauses.add(Cause.TREE_ENUMERATION_FAILED);
            return;
        }
        for (Target descendant : descendants) {
            if (descendant == null) {
                lease.permanentlyUnverified = true;
                causes.add(Cause.PROCESS_IDENTITY_AMBIGUOUS);
                lease.permanentCauses.add(Cause.PROCESS_IDENTITY_AMBIGUOUS);
                continue;
            }
            final ProcessIdentity identity;
            try {
                identity = Objects.requireNonNull(
                        descendant.identity(), "descendant process identity");
            } catch (RuntimeException invalid) {
                lease.permanentlyUnverified = true;
                causes.add(Cause.PROCESS_IDENTITY_AMBIGUOUS);
                lease.permanentCauses.add(Cause.PROCESS_IDENTITY_AMBIGUOUS);
                continue;
            }
            if (!identity.signalAuthorized()) {
                lease.permanentlyUnverified = true;
                causes.add(Cause.PROCESS_IDENTITY_AMBIGUOUS);
                lease.permanentCauses.add(Cause.PROCESS_IDENTITY_AMBIGUOUS);
            }
            boolean conflictingPid = lease.tracked.keySet().stream()
                    .anyMatch(existing ->
                            existing.processId() == identity.processId()
                                    && !existing.equals(identity));
            if (conflictingPid) {
                lease.permanentlyUnverified = true;
                causes.add(Cause.PROCESS_IDENTITY_AMBIGUOUS);
                lease.permanentCauses.add(Cause.PROCESS_IDENTITY_AMBIGUOUS);
            }
            lease.tracked.putIfAbsent(identity, descendant);
        }
        if (stableLiveRoot(lease, causes)) {
            lease.treeCapturedWhileRootAlive = true;
        } else {
            lease.permanentlyUnverified = true;
            causes.add(Cause.TREE_CAPTURE_UNSTABLE);
            lease.permanentCauses.add(Cause.TREE_CAPTURE_UNSTABLE);
            if (causes.contains(Cause.PROCESS_IDENTITY_AMBIGUOUS)) {
                lease.permanentCauses.add(Cause.PROCESS_IDENTITY_AMBIGUOUS);
            }
        }
    }

    private static boolean stableLiveRoot(
            Lease lease,
            EnumSet<Cause> causes) {
        Target root = lease.root;
        if (root == null) {
            causes.add(Cause.TREE_CAPTURE_UNSTABLE);
            return false;
        }
        try {
            if (!root.isAlive()) return false;
            if (!root.identityStillMatches()) {
                causes.add(Cause.PROCESS_IDENTITY_AMBIGUOUS);
                return false;
            }
            return true;
        } catch (RuntimeException unavailable) {
            causes.add(Cause.EXIT_OBSERVATION_FAILED);
            return false;
        }
    }

    private static void signalGracefully(
            Lease lease,
            EnumSet<Cause> causes) {
        for (Target target : descendantsFirst(lease)) {
            signal(target, false, causes);
        }
        signal(lease.root, false, causes);
    }

    private static void signalForcibly(
            Lease lease,
            EnumSet<Cause> causes) {
        for (Target target : descendantsFirst(lease)) {
            signal(target, true, causes);
        }
        signal(lease.root, true, causes);
    }

    private static List<Target> descendantsFirst(Lease lease) {
        List<Target> descendants = new ArrayList<>(lease.tracked.values());
        descendants.remove(lease.root);
        Collections.reverse(descendants);
        return descendants;
    }

    private static void signal(
            Target target,
            boolean force,
            EnumSet<Cause> causes) {
        if (!safeAlive(target, causes)) return;
        if (!signalAuthorized(target, causes)) return;
        try {
            if (force) {
                target.destroyForcibly();
            } else {
                target.destroy();
            }
        } catch (RuntimeException failed) {
            causes.add(Cause.SIGNAL_FAILED);
        }
    }

    private static boolean awaitTracked(
            Lease lease,
            Duration timeout,
            EnumSet<Cause> causes) {
        boolean interrupted = false;
        long deadline = deadlineAfter(timeout);
        for (Target target : List.copyOf(lease.tracked.values())) {
            Duration remaining = remaining(deadline);
            if (remaining.isZero()) continue;
            try {
                target.waitFor(remaining);
            } catch (InterruptedException stop) {
                interrupted = true;
                causes.add(Cause.INTERRUPTED);
                Thread.interrupted();
            } catch (RuntimeException unavailable) {
                causes.add(Cause.EXIT_OBSERVATION_FAILED);
            }
        }
        return interrupted;
    }

    private static boolean anyTrackedAlive(
            Lease lease,
            EnumSet<Cause> causes) {
        boolean live = false;
        for (Target target : List.copyOf(lease.tracked.values())) {
            live |= safeAlive(target, causes);
        }
        return live;
    }

    private static boolean closeTrackedResources(
            Lease lease,
            Duration timeout,
            EnumSet<Cause> causes) {
        boolean interrupted = false;
        for (Target target : List.copyOf(lease.tracked.values())) {
            try {
                interrupted |= target.closeResources(timeout);
            } catch (RuntimeException unavailable) {
                causes.add(Cause.RESOURCE_SETTLEMENT_FAILED);
                if (Thread.interrupted()) {
                    interrupted = true;
                    causes.add(Cause.INTERRUPTED);
                }
            }
        }
        if (interrupted) causes.add(Cause.INTERRUPTED);
        return interrupted;
    }

    private static boolean safeAlive(
            Target target,
            EnumSet<Cause> causes) {
        try {
            boolean alive = target.isAlive();
            if (alive && !target.identityStillMatches()) {
                causes.add(Cause.PROCESS_IDENTITY_AMBIGUOUS);
                return true;
            }
            return alive;
        } catch (RuntimeException unavailable) {
            causes.add(Cause.EXIT_OBSERVATION_FAILED);
            return true;
        }
    }

    private static boolean signalAuthorized(
            Target target,
            EnumSet<Cause> causes) {
        try {
            if (target.identity().signalAuthorized()
                    && target.identityStillMatches()) {
                return true;
            }
        } catch (RuntimeException unavailable) {
            causes.add(Cause.EXIT_OBSERVATION_FAILED);
            return false;
        }
        causes.add(Cause.PROCESS_IDENTITY_AMBIGUOUS);
        return false;
    }

    private synchronized void release(Lease lease) {
        owned.remove(lease);
    }

    private static long deadlineAfter(Duration timeout) {
        long now = System.nanoTime();
        long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
        if (nanos <= 0L) return now;
        long deadline = now + nanos;
        return deadline < 0L || deadline < now ? Long.MAX_VALUE : deadline;
    }

    private static Duration remaining(long deadline) {
        long remaining = deadline - System.nanoTime();
        return remaining <= 0L ? Duration.ZERO : Duration.ofNanos(remaining);
    }

    private static final class ProcessTarget implements Target {
        private final Process process;
        private final java.util.concurrent.CompletableFuture<?> exit;
        private final ProcessIdentity identity;
        private final ResourceSettlement resources;
        private final ProcessResources processResources;

        private ProcessTarget(
                Process process,
                ResourceSettlement resources,
                ResourceAttemptCompletion resourceAttemptCompletion,
                ResourceAttemptJoinObserver resourceAttemptJoinObserver) {
            this.process = process;
            // Created at adoption, while the process is running, so the JDK reaper waits on the
            // operating system exit signal. A completion created after the exit code is set would
            // complete at once and prove nothing. Process subclasses without a handle keep the
            // exit-code observation.
            this.exit = exitCompletion(() -> process.toHandle().onExit());
            this.identity = directProcessIdentity(process);
            this.resources = Objects.requireNonNull(
                    resources, "process resources");
            this.processResources = new ProcessResources(
                    process,
                    resources,
                    resourceAttemptCompletion,
                    resourceAttemptJoinObserver);
        }

        @Override
        public ProcessIdentity identity() {
            return identity;
        }

        @Override
        public boolean identityStillMatches() {
            return directIdentityMatches(process, identity);
        }

        @Override
        public boolean isAlive() {
            return process.isAlive();
        }

        @Override
        public boolean awaitExitProof(Duration timeout) throws InterruptedException {
            if (exit == null || exit.isDone()) return true;
            long millis = boundedMillis(timeout);
            if (millis <= 0L) return false;
            try {
                exit.get(millis, TimeUnit.MILLISECONDS);
                return true;
            } catch (TimeoutException pending) {
                return false;
            } catch (ExecutionException unavailable) {
                throw new IllegalStateException(
                        "process exit observation failed", unavailable);
            }
        }

        @Override
        public List<Target> descendants() {
            try (java.util.stream.Stream<ProcessHandle> descendants =
                         process.toHandle().descendants()) {
                return descendants
                        .map(ProcessHandleTarget::new)
                        .map(Target.class::cast)
                        .toList();
            }
        }

        @Override
        public void destroy() {
            process.destroy();
        }

        @Override
        public void destroyForcibly() {
            process.destroyForcibly();
        }

        @Override
        public boolean waitFor(Duration timeout) throws InterruptedException {
            long millis = boundedMillis(timeout);
            return millis > 0L
                    ? process.waitFor(millis, TimeUnit.MILLISECONDS)
                    : !process.isAlive();
        }

        @Override
        public boolean owns(
                Process candidate,
                ResourceSettlement candidateResources) {
            return process == candidate && resources == candidateResources;
        }

        @Override
        public boolean closeResources(Duration timeout) {
            return processResources.settle(timeout);
        }
    }

    private static final class ProcessResources {
        private final Process process;
        private final ResourceSettlement delegated;
        private final boolean delegatedOwnsStdout;
        private final ResourceAttemptCompletion attemptCompletion;
        private final ResourceAttemptJoinObserver attemptJoinObserver;
        private final AtomicReference<Throwable> failure =
                new AtomicReference<>();
        private final AtomicBoolean delegatedInterruptObserved =
                new AtomicBoolean();
        private volatile boolean stdinClosed;
        private volatile boolean delegatedSettled;
        private volatile boolean stdoutClosed;
        private volatile boolean stderrClosed;
        private CloseAttempt closeAttempt;
        private long closeAttemptSequence;

        private ProcessResources(
                Process process,
                ResourceSettlement delegated,
                ResourceAttemptCompletion attemptCompletion,
                ResourceAttemptJoinObserver attemptJoinObserver) {
            this.process = Objects.requireNonNull(process, "process");
            this.delegated = Objects.requireNonNull(
                    delegated, "process resources");
            this.attemptCompletion = Objects.requireNonNull(
                    attemptCompletion,
                    "resource attempt completion");
            this.attemptJoinObserver = Objects.requireNonNull(
                    attemptJoinObserver,
                    "resource attempt join observer");
            this.delegatedOwnsStdout = delegated.ownsProcessStdout();
            this.stdoutClosed = delegatedOwnsStdout;
        }

        private boolean settle(Duration timeout) {
            long deadline = deadlineAfter(
                    timeout == null ? Duration.ZERO : timeout);
            CloseAttemptSelection selected = requestCloseAttempt(deadline);
            if (selected.joined()) {
                attemptJoinObserver.joined(selected.attempt().sequence());
            }
            AwaitResult awaited = await(
                    selected.attempt().completion(),
                    deadline,
                    selected.joined()
                            ? () -> attemptJoinObserver.interrupted(
                                    selected.attempt().sequence())
                            : null);
            boolean interrupted = awaited.interrupted();
            if (!awaited.completed()) {
                if (interrupted) Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "process resource closure remains unsettled",
                        failure.get());
            }
            if (selected.joined()) {
                CloseAttempt followOn = resolveFollowOn(
                        selected.attempt().sequence(),
                        deadline);
                if (followOn != null) {
                    attemptJoinObserver.followOnSelected(
                            selected.attempt().sequence(),
                            followOn.sequence());
                    AwaitResult followed = await(
                            followOn.completion(),
                            deadline,
                            () -> attemptJoinObserver.interrupted(
                                    followOn.sequence()));
                    interrupted |= followed.interrupted();
                    if (!followed.completed()) {
                        if (interrupted) Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "process resource closure remains unsettled",
                                failure.get());
                    }
                }
            }
            interrupted |= delegatedInterruptObserved.getAndSet(false);
            if (!settled()) {
                if (interrupted) Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "process resource closure remains unsettled",
                        failure.get());
            }
            return interrupted;
        }

        private synchronized CloseAttemptSelection requestCloseAttempt(
                long deadline) {
            if (closeAttempt != null
                    && closeAttempt.completion().getCount() != 0L) {
                return new CloseAttemptSelection(closeAttempt, true);
            }
            if (settled()) {
                return new CloseAttemptSelection(
                        new CloseAttempt(
                                closeAttemptSequence,
                                new CountDownLatch(0)),
                        false);
            }
            return new CloseAttemptSelection(
                    startCloseAttempt(deadline),
                    false);
        }

        private synchronized CloseAttempt resolveFollowOn(
                long joinedSequence,
                long deadline) {
            long followOnSequence;
            try {
                followOnSequence = Math.addExact(joinedSequence, 1L);
            } catch (ArithmeticException overflow) {
                throw new IllegalStateException(
                        "process resource attempt generation overflow",
                        overflow);
            }
            if (closeAttempt != null
                    && closeAttempt.sequence() == followOnSequence) {
                return closeAttempt;
            }
            if (closeAttempt == null
                    || closeAttempt.sequence() < joinedSequence
                    || closeAttempt.sequence() > followOnSequence) {
                throw new IllegalStateException(
                        "process resource attempt generation is unresolvable");
            }
            if (closeAttempt.sequence() != joinedSequence
                    || closeAttempt.completion().getCount() != 0L) {
                throw new IllegalStateException(
                        "process resource predecessor remains unresolved");
            }
            if (settled() || !beforeDeadline(deadline)) return null;
            return startCloseAttempt(deadline);
        }

        private CloseAttempt startCloseAttempt(long deadline) {
            CountDownLatch completion = new CountDownLatch(1);
            CloseAttempt exact = new CloseAttempt(
                    ++closeAttemptSequence,
                    completion);
            closeAttempt = exact;
            try {
                Thread closer = Thread.ofPlatform()
                        .daemon(true)
                        .name("talos-llama-process-resources")
                        .unstarted(() -> {
                            try {
                                closeOwnedResources(deadline);
                            } finally {
                                try {
                                    attemptCompletion.beforeComplete();
                                } finally {
                                    completion.countDown();
                                }
                            }
                        });
                closer.start();
            } catch (RuntimeException | Error startFailure) {
                record(failure, startFailure);
                completion.countDown();
            }
            return exact;
        }

        private void closeOwnedResources(long deadline) {
            if (!stdinClosed) {
                try {
                    process.getOutputStream().close();
                    stdinClosed = true;
                } catch (Throwable unavailable) {
                    record(failure, unavailable);
                }
            }
            if (!delegatedSettled) {
                Duration delegatedBudget = remaining(deadline);
                if (!delegatedBudget.isZero()) {
                    try {
                        if (delegated.settle(delegatedBudget)) {
                            delegatedInterruptObserved.set(true);
                        }
                        delegatedSettled = true;
                    } catch (Throwable unavailable) {
                        if (Thread.interrupted()) {
                            delegatedInterruptObserved.set(true);
                        }
                        record(failure, unavailable);
                    }
                }
            }
            if (!stdoutClosed && !delegatedOwnsStdout) {
                try {
                    process.getInputStream().close();
                    stdoutClosed = true;
                } catch (Throwable unavailable) {
                    record(failure, unavailable);
                }
            }
            if (!stderrClosed) {
                try {
                    process.getErrorStream().close();
                    stderrClosed = true;
                } catch (Throwable unavailable) {
                    record(failure, unavailable);
                }
            }
        }

        private boolean settled() {
            return stdinClosed
                    && delegatedSettled
                    && stdoutClosed
                    && stderrClosed;
        }

        private static boolean beforeDeadline(long deadline) {
            return deadline == Long.MAX_VALUE
                    || System.nanoTime() < deadline;
        }

        private static AwaitResult await(
                CountDownLatch latch,
                long deadline) {
            return await(latch, deadline, null);
        }

        private static AwaitResult await(
                CountDownLatch latch,
                long deadline,
                Runnable interruptObserver) {
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
                    if (interruptObserver != null) interruptObserver.run();
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
                        if (interruptObserver != null) interruptObserver.run();
                    }
                }
            }
            return new AwaitResult(completed, interrupted);
        }

        private record AwaitResult(boolean completed, boolean interrupted) {}

        private record CloseAttempt(
                long sequence,
                CountDownLatch completion) {}

        private record CloseAttemptSelection(
                CloseAttempt attempt,
                boolean joined) {}

        private static void record(
                AtomicReference<Throwable> destination,
                Throwable unavailable) {
            Throwable exact = Objects.requireNonNull(
                    unavailable, "resource closure failure");
            Throwable previous = destination.get();
            if (previous == null && destination.compareAndSet(null, exact)) return;
            previous = destination.get();
            if (previous != null && previous != exact) {
                synchronized (previous) {
                    previous.addSuppressed(exact);
                }
            }
        }
    }

    private static final class ProcessHandleTarget implements Target {
        private final ProcessHandle process;
        private final ProcessIdentity identity;

        private ProcessHandleTarget(ProcessHandle process) {
            this.process = process;
            this.identity = processIdentity(
                    process.pid(),
                    IdentityAuthority.EXACT_PID_START);
        }

        @Override
        public ProcessIdentity identity() {
            return identity;
        }

        @Override
        public boolean identityStillMatches() {
            if (!process.isAlive()) return true;
            if (identity.authority() != IdentityAuthority.EXACT_PID_START) {
                return false;
            }
            return process.info().startInstant()
                    .map(value -> value.truncatedTo(ChronoUnit.MILLIS))
                    .map(Instant::toEpochMilli)
                    .filter(value -> value == identity.processStartEpochMilli())
                    .isPresent();
        }

        @Override
        public boolean isAlive() {
            return process.isAlive();
        }

        @Override
        public List<Target> descendants() {
            return List.of();
        }

        @Override
        public void destroy() {
            process.destroy();
        }

        @Override
        public void destroyForcibly() {
            process.destroyForcibly();
        }

        @Override
        public boolean waitFor(Duration timeout) throws InterruptedException {
            if (!process.isAlive()) return true;
            long millis = boundedMillis(timeout);
            if (millis <= 0L) return !process.isAlive();
            try {
                process.onExit().get(millis, TimeUnit.MILLISECONDS);
                return true;
            } catch (TimeoutException timeoutFailure) {
                return !process.isAlive();
            } catch (ExecutionException unavailable) {
                throw new IllegalStateException(
                        "process exit observation failed", unavailable);
            }
        }
    }

    private static java.util.concurrent.CompletableFuture<?> exitCompletion(
            java.util.function.Supplier<? extends java.util.concurrent.CompletableFuture<?>> source) {
        try {
            return source.get();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /**
     * On Windows the exit code is set when termination starts, before the operating system has
     * finished the process. The root exit proof is awaited once, at the final verdict only, so tree
     * capture and signalling keep their exit-code observation. The minimum absorbs the asynchronous
     * delivery of a completion for a process that has already exited.
     */
    private static final Duration MINIMUM_EXIT_PROOF_WAIT = Duration.ofSeconds(1);

    private static boolean awaitRootExitProof(
            Lease lease,
            Policy policy,
            EnumSet<Cause> causes) {
        Duration wait = policy.forcedTimeout().compareTo(MINIMUM_EXIT_PROOF_WAIT) > 0
                ? policy.forcedTimeout() : MINIMUM_EXIT_PROOF_WAIT;
        try {
            return lease.root.awaitExitProof(wait);
        } catch (InterruptedException interrupted) {
            // An interrupted proof is not a proof. The flag is set again for the caller to record.
            Thread.currentThread().interrupt();
            causes.add(Cause.INTERRUPTED);
            return false;
        } catch (RuntimeException unavailable) {
            causes.add(Cause.EXIT_OBSERVATION_FAILED);
            return false;
        }
    }

    private static ProcessIdentity processIdentity(
            long processId,
            IdentityAuthority preferredAuthority) {
        try {
            ProcessHandle process = ProcessHandle.of(processId)
                    .filter(ProcessHandle::isAlive)
                    .orElseThrow();
            long started = process.info().startInstant()
                    .map(value -> value.truncatedTo(ChronoUnit.MILLIS))
                    .map(Instant::toEpochMilli)
                    .orElseThrow();
            return new ProcessIdentity(
                    processId,
                    started,
                    preferredAuthority);
        } catch (RuntimeException unavailable) {
            IdentityAuthority fallback =
                    preferredAuthority == IdentityAuthority.DIRECT_PROCESS_CAPABILITY
                            ? IdentityAuthority.DIRECT_PROCESS_CAPABILITY
                            : IdentityAuthority.UNAVAILABLE;
            return new ProcessIdentity(processId, 0L, fallback);
        }
    }

    private static ProcessIdentity directProcessIdentity(Process process) {
        try {
            long processId = process.pid();
            if (processId > 0L) {
                return processIdentity(
                        processId,
                        IdentityAuthority.DIRECT_PROCESS_CAPABILITY);
            }
        } catch (RuntimeException unavailable) {
            // The Process object itself remains the signal authority.
        }
        return new ProcessIdentity(
                0L,
                0L,
                IdentityAuthority.DIRECT_PROCESS_CAPABILITY);
    }

    private static boolean directIdentityMatches(
            Process process,
            ProcessIdentity identity) {
        return process != null
                && identity.authority()
                        == IdentityAuthority.DIRECT_PROCESS_CAPABILITY;
    }

    private static long boundedMillis(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return 0L;
        }
        try {
            return Math.max(1L, timeout.toMillis());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
