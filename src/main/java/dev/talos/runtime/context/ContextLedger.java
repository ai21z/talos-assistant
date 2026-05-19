package dev.talos.runtime.context;

import java.util.ArrayList;
import java.util.List;

/** Append-only per-turn context decision ledger. */
public final class ContextLedger {
    public record Entry(ContextItem item, ContextDecision decision) {
        public Entry {
            decision = decision == null
                    ? ContextDecision.excludedByPrivacyOrTrustPolicy("UNSPECIFIED")
                    : decision;
        }
    }

    private final String traceId;
    private final int turnNumber;
    private final List<Entry> entries = new ArrayList<>();

    public ContextLedger(String traceId, int turnNumber) {
        this.traceId = traceId == null ? "" : traceId;
        this.turnNumber = Math.max(0, turnNumber);
    }

    public void record(ContextItem item, ContextDecision decision) {
        if (item == null) return;
        entries.add(new Entry(item, decision));
    }

    public ContextLedgerSnapshot snapshot() {
        List<Entry> copy = List.copyOf(entries);
        return new ContextLedgerSnapshot(traceId, turnNumber, copy, ContextLedgerSummary.from(copy));
    }
}
