package dev.talos.runtime.context;

import java.util.List;

/** Immutable snapshot of the current turn context ledger. */
public record ContextLedgerSnapshot(
        String traceId,
        int turnNumber,
        List<ContextLedger.Entry> entries,
        ContextLedgerSummary summary) {

    public ContextLedgerSnapshot {
        traceId = traceId == null ? "" : traceId;
        entries = entries == null ? List.of() : List.copyOf(entries);
        summary = summary == null ? ContextLedgerSummary.empty() : summary;
    }

    public static ContextLedgerSnapshot empty() {
        return new ContextLedgerSnapshot("", 0, List.of(), ContextLedgerSummary.empty());
    }
}
