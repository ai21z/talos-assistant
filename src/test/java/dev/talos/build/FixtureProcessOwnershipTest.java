package dev.talos.build;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class FixtureProcessOwnershipTest {
    @Test void exitedRootCannotAdoptMisleadingNumericPidDescendants() {
        FakeHandle root = handle(100, 20, null);
        FakeHandle unrelated = handle(200, 10, root);
        root.descendants = List.of(unrelated);
        root.alive = false;
        Set<ProcessHandle> observed = new LinkedHashSet<>();
        FixtureProcessOwnership.retainDescendants(root, observed);
        assertTrue(observed.isEmpty());
        assertEquals(0, root.discoveryCalls);
    }

    @Test void rootExitDuringSnapshotDiscardsAllNewOwnership() {
        FakeHandle root = handle(100, 20, null);
        FakeHandle child = handle(200, 21, root);
        root.descendants = List.of(child);
        root.afterDiscovery = () -> root.alive = false;
        Set<ProcessHandle> observed = new LinkedHashSet<>();
        FixtureProcessOwnership.retainDescendants(root, observed);
        assertEquals(1, root.discoveryCalls);
        assertTrue(observed.isEmpty());
    }

    @Test void replacedRootDuringSnapshotCannotAdoptReplacementChildren() {
        FakeHandle root = handle(100, 20, null);
        FakeHandle child = handle(200, 31, root);
        root.descendants = List.of(child);
        root.afterDiscovery = () -> root.started = Optional.of(at(30));
        Set<ProcessHandle> observed = new LinkedHashSet<>();
        FixtureProcessOwnership.retainDescendants(root, observed);
        assertTrue(observed.isEmpty());
    }

    @Test void retainedChildRemainsAccountedForAfterRootExitAndCanOwnLaterChildren() {
        FakeHandle root = handle(100, 20, null);
        FakeHandle child = handle(200, 21, root);
        root.descendants = List.of(child);
        Set<ProcessHandle> observed = new LinkedHashSet<>();
        FixtureProcessOwnership.retainDescendants(root, observed);
        assertEquals(Set.of(child), observed);
        root.alive = false;
        FakeHandle grandchild = handle(300, 22, child);
        child.descendants = List.of(grandchild);
        FixtureProcessOwnership.retainDescendants(root, observed);
        assertEquals(Set.of(child, grandchild), observed);
        assertTrue(observed.stream().anyMatch(ProcessHandle::isAlive),
                "The existing survivor check must still fail and clean up genuinely owned live children");
        assertEquals(1, root.discoveryCalls);
    }

    @Test void rejectsOlderUnknownUnrelatedAndBrokenParentIdentity() {
        FakeHandle root = handle(100, 20, null);
        FakeHandle older = handle(200, 10, root);
        FakeHandle unknown = handle(300, 21, root);
        unknown.started = Optional.empty();
        FakeHandle unrelatedParent = handle(400, 21, null);
        FakeHandle unrelated = handle(500, 22, unrelatedParent);
        FakeHandle wrongGeneration = handle(100, 19, null);
        FakeHandle wrongParent = handle(600, 22, wrongGeneration);
        FakeHandle impossibleParent = handle(700, 25, root);
        FakeHandle impossibleChild = handle(800, 24, impossibleParent);
        root.descendants = List.of(older, unknown, unrelated, wrongParent, impossibleChild);
        Set<ProcessHandle> observed = new LinkedHashSet<>();
        FixtureProcessOwnership.retainDescendants(root, observed);
        assertTrue(observed.isEmpty());
    }

    @Test void acceptsSameTimestampChildButRejectsParentCyclesAndSelf() {
        FakeHandle root = handle(100, 20, null);
        FakeHandle child = handle(200, 20, root);
        FakeHandle cycleA = handle(300, 21, null);
        FakeHandle cycleB = handle(400, 21, cycleA);
        cycleA.parent = cycleB;
        root.descendants = List.of(root, child, cycleA);
        Set<ProcessHandle> observed = new LinkedHashSet<>();
        FixtureProcessOwnership.retainDescendants(root, observed);
        assertEquals(Set.of(child), observed);
    }

    @Test void unknownLiveRootIsNotAnOwnershipAnchor() {
        FakeHandle root = handle(100, 20, null);
        root.started = Optional.empty();
        root.descendants = List.of(handle(200, 21, root));
        Set<ProcessHandle> observed = new LinkedHashSet<>();
        FixtureProcessOwnership.retainDescendants(root, observed);
        assertTrue(observed.isEmpty());
        assertEquals(0, root.discoveryCalls);
    }

    private static Instant at(int seconds) { return Instant.parse("2026-09-05T00:00:00Z").plusSeconds(seconds); }

    private static FakeHandle handle(long pid, int seconds, FakeHandle parent) {
        return new FakeHandle(pid, at(seconds), parent);
    }

    private static final class FakeHandle implements ProcessHandle {
        private final long pid;
        private Optional<Instant> started;
        private FakeHandle parent;
        private boolean alive = true;
        private List<ProcessHandle> descendants = List.of();
        private int discoveryCalls;
        private Runnable afterDiscovery = () -> {};

        private FakeHandle(long pid, Instant started, FakeHandle parent) {
            this.pid = pid;
            this.started = Optional.of(started);
            this.parent = parent;
        }

        @Override public long pid() { return pid; }
        @Override public Optional<ProcessHandle> parent() { return Optional.ofNullable(parent); }
        @Override public Stream<ProcessHandle> children() { throw new AssertionError("Unexpected enumeration"); }
        @Override public Stream<ProcessHandle> descendants() {
            discoveryCalls++;
            return descendants.stream().onClose(afterDiscovery);
        }
        @Override public boolean isAlive() { return alive; }
        @Override public Info info() {
            Optional<Instant> snapshot = started;
            return new Info() {
                @Override public Optional<String> command() { return Optional.empty(); }
                @Override public Optional<String> commandLine() { return Optional.empty(); }
                @Override public Optional<String[]> arguments() { return Optional.empty(); }
                @Override public Optional<Instant> startInstant() { return snapshot; }
                @Override public Optional<Duration> totalCpuDuration() { return Optional.empty(); }
                @Override public Optional<String> user() { return Optional.empty(); }
            };
        }
        @Override public CompletableFuture<ProcessHandle> onExit() { throw new AssertionError("No process waits"); }
        @Override public boolean supportsNormalTermination() { return false; }
        @Override public boolean destroy() { throw new AssertionError("No process signaling in ownership tests"); }
        @Override public boolean destroyForcibly() { throw new AssertionError("No process signaling in ownership tests"); }
        @Override public int compareTo(ProcessHandle other) { return Long.compare(pid, other.pid()); }
    }
}
