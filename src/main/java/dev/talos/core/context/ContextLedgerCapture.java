package dev.talos.core.context;

import java.util.concurrent.atomic.AtomicReference;

/** Thread-local capture for the current turn context ledger. */
public final class ContextLedgerCapture {
    private ContextLedgerCapture() {}

    private static final ThreadLocal<ContextLedger> CURRENT = new ThreadLocal<>();
    private static final AtomicReference<ContextLedgerSnapshot> LATEST =
            new AtomicReference<>(ContextLedgerSnapshot.empty());

    public static void begin(String traceId, int turnNumber) {
        ContextLedger previous = CURRENT.get();
        if (previous != null) previous.close();
        CURRENT.set(new ContextLedger(traceId, turnNumber));
    }

    public static void bindPromptMessage(String role, String content, java.util.List<ContextItem> items) {
        ContextLedger current = CURRENT.get();
        if (current != null) current.bind(role, content, items);
    }

    /** The owner is captured before work is submitted to a provider worker. */
    public static ContextDeliveryCapture deliveryCapture() {
        ContextLedger current = CURRENT.get();
        return current == null ? ContextDeliveryCapture.empty() : current.deliveryCapture();
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
        ContextLedgerSnapshot snapshot = current == null ? ContextLedgerSnapshot.empty() : current.close();
        LATEST.set(snapshot);
        return snapshot;
    }

    public static void clear() {
        ContextLedger current = CURRENT.get();
        if (current != null) current.close();
        CURRENT.remove();
        LATEST.set(ContextLedgerSnapshot.empty());
    }
}
