package dev.talos.core.context;

import java.util.concurrent.atomic.AtomicReference;

/** Thread-local capture for the current turn context ledger. */
public final class ContextLedgerCapture {
    private ContextLedgerCapture() {}

    private static final ThreadLocal<ContextLedger> CURRENT = new ThreadLocal<>();
    private static final AtomicReference<ContextLedgerSnapshot> LATEST =
            new AtomicReference<>(ContextLedgerSnapshot.empty());

    public static void begin(String traceId, int turnNumber) {
        CURRENT.set(new ContextLedger(traceId, turnNumber));
    }

    public static void record(ContextItem item, ContextDecision decision) {
        ContextLedger ledger = CURRENT.get();
        if (ledger == null) return;
        ledger.record(item, decision);
    }

    public static ContextLedgerSnapshot snapshot() {
        ContextLedger current = CURRENT.get();
        if (current != null) return current.snapshot();
        ContextLedgerSnapshot latest = LATEST.get();
        return latest == null ? ContextLedgerSnapshot.empty() : latest;
    }

    public static ContextLedgerSnapshot complete() {
        ContextLedger current = CURRENT.get();
        CURRENT.remove();
        ContextLedgerSnapshot snapshot = current == null ? ContextLedgerSnapshot.empty() : current.snapshot();
        LATEST.set(snapshot);
        return snapshot;
    }

    public static void clear() {
        CURRENT.remove();
        LATEST.set(ContextLedgerSnapshot.empty());
    }
}
