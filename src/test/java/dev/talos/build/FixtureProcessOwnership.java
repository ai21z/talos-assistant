package dev.talos.build;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Test-fixture ownership discovery; never signals a process. */
public final class FixtureProcessOwnership {
    private static final int MAX_ANCESTORS = 128;

    private FixtureProcessOwnership() {}

    public static void retainDescendants(ProcessHandle root, Set<ProcessHandle> observed) {
        List<ProcessHandle> anchors = new ArrayList<>();
        anchors.add(root);
        anchors.addAll(observed);
        for (ProcessHandle anchor : anchors) {
            Optional<Instant> started = liveStart(anchor);
            if (started.isEmpty()) continue;
            List<ProcessHandle> owned;
            try (var descendants = anchor.descendants()) {
                owned = descendants.filter(candidate -> isDescendantOf(candidate, anchor, started.get())).toList();
            }
            // descendants() uses a fresh numeric-PID snapshot. If the retained
            // anchor exited or changed during discovery, that snapshot owns nothing.
            if (liveStart(anchor).equals(started)) observed.addAll(owned);
        }
        // Previously authenticated descendants remain retained after their parent
        // exits. Existing wait/failure/termination paths still account for them.
    }

    private static boolean isDescendantOf(ProcessHandle candidate, ProcessHandle anchor, Instant anchorStart) {
        if (candidate.pid() == anchor.pid()) return false;
        Set<Long> visited = new HashSet<>();
        ProcessHandle current = candidate;
        Instant childStart = null;
        for (int depth = 0; depth < MAX_ANCESTORS; depth++) {
            if (!visited.add(current.pid())) return false;
            Optional<Instant> currentStart = liveStart(current);
            if (currentStart.isEmpty() || currentStart.get().isBefore(anchorStart)
                    || (childStart != null && currentStart.get().isAfter(childStart))) return false;
            if (current.pid() == anchor.pid()) return currentStart.get().equals(anchorStart);
            Optional<ProcessHandle> parent = current.parent();
            if (parent.isEmpty()) return false;
            childStart = currentStart.get();
            current = parent.get();
        }
        return false;
    }

    private static Optional<Instant> liveStart(ProcessHandle handle) {
        if (!handle.isAlive()) return Optional.empty();
        Optional<Instant> started = handle.info().startInstant();
        return handle.isAlive() ? started : Optional.empty();
    }
}
