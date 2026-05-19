package dev.talos.runtime.context;

import java.util.List;
import java.util.Map;

/** JSON-friendly aggregate view of context decisions for trace and prompt-debug. */
public record ContextLedgerSummary(
        int totalItems,
        Map<String, Integer> bySource,
        Map<String, Integer> byBoundary,
        Map<String, Integer> byPrivacyClass,
        Map<String, Integer> byDecision,
        Map<String, Integer> byReason) {

    public ContextLedgerSummary {
        totalItems = Math.max(0, totalItems);
        bySource = copy(bySource);
        byBoundary = copy(byBoundary);
        byPrivacyClass = copy(byPrivacyClass);
        byDecision = copy(byDecision);
        byReason = copy(byReason);
    }

    public static ContextLedgerSummary empty() {
        return new ContextLedgerSummary(0, Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    static ContextLedgerSummary from(List<ContextLedger.Entry> entries) {
        if (entries == null || entries.isEmpty()) return empty();
        Map<String, Integer> bySource = new java.util.TreeMap<>();
        Map<String, Integer> byBoundary = new java.util.TreeMap<>();
        Map<String, Integer> byPrivacy = new java.util.TreeMap<>();
        Map<String, Integer> byDecision = new java.util.TreeMap<>();
        Map<String, Integer> byReason = new java.util.TreeMap<>();
        for (ContextLedger.Entry entry : entries) {
            if (entry == null) continue;
            ContextItem item = entry.item();
            ContextDecision decision = entry.decision();
            if (item != null) {
                increment(bySource, item.source().name());
                increment(byBoundary, item.executionBoundary().name());
                increment(byPrivacy, item.privacyClass().name());
            }
            if (decision != null) {
                increment(byDecision, decision.action().name());
                increment(byReason, decision.reasonCode());
            }
        }
        return new ContextLedgerSummary(entries.size(), bySource, byBoundary, byPrivacy, byDecision, byReason);
    }

    private static void increment(Map<String, Integer> counts, String key) {
        if (key == null || key.isBlank()) return;
        counts.merge(key, 1, Integer::sum);
    }

    private static Map<String, Integer> copy(Map<String, Integer> map) {
        if (map == null || map.isEmpty()) return Map.of();
        return Map.copyOf(map);
    }
}
