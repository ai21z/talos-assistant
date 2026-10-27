package dev.talos.core.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import dev.talos.spi.types.ProviderContextMembership;

/** Per-turn context decisions and explicitly owned request observations. */
public final class ContextLedger {
    public record Entry(ContextItem item, ContextDecision decision, String requestId, String providerBodyHash) {
        public Entry {
            decision = decision == null
                    ? ContextDecision.excludedByPrivacyOrTrustPolicy("UNSPECIFIED")
                    : decision;
            requestId = requestId == null ? "" : requestId;
            providerBodyHash = providerBodyHash == null ? "" : providerBodyHash;
        }

        public Entry(ContextItem item, ContextDecision decision) {
            this(item, decision, "", "");
        }
    }

    private final String traceId;
    private final int turnNumber;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<ProviderContextMembership.MessageKey, List<ContextItem>> bindings = new LinkedHashMap<>();
    private final java.util.Set<ContextItem> unboundSources = new java.util.LinkedHashSet<>();
    private final Map<String, List<ContextItem>> pending = new LinkedHashMap<>();
    private final Map<String, List<Entry>> observedRequests = new LinkedHashMap<>();
    private long requestSequence;
    private boolean closed;

    public ContextLedger(String traceId, int turnNumber) {
        this.traceId = traceId == null ? "" : traceId;
        this.turnNumber = Math.max(0, turnNumber);
    }

    public synchronized void record(ContextItem item, ContextDecision decision) {
        if (item == null || closed) return;
        entries.add(new Entry(item, decision));
    }

    synchronized void bind(String role, String content, List<ContextItem> items) {
        if (!closed && items != null && !items.isEmpty()) {
            try {
                bindings.merge(ProviderContextMembership.key(role, content),
                        List.copyOf(new java.util.LinkedHashSet<>(items)), (previous, added) -> {
                    var combined = new java.util.LinkedHashSet<>(previous);
                    combined.addAll(added);
                    return List.copyOf(combined);
                });
            } catch (IllegalArgumentException | NullPointerException unsupported) {
                // Diagnostic representation limits do not reject otherwise valid prompt assembly.
                items.stream().filter(java.util.Objects::nonNull).forEach(unboundSources::add);
            }
        }
    }

    synchronized ContextDeliveryCapture deliveryCapture() {
        return new ContextDeliveryCapture(this, closed ? Map.of() : Map.copyOf(bindings),
                closed ? List.of() : List.copyOf(unboundSources));
    }

    synchronized String beginRequest(List<ContextItem> items) {
        if (closed || items.isEmpty()) return "";
        String id = "context-request-" + (++requestSequence);
        pending.put(id, List.copyOf(items));
        return id;
    }

    synchronized void completeRequest(String id, List<Entry> dispositions) {
        if (!closed && pending.remove(id) != null) {
            observedRequests.remove(id);
            entries.addAll(dispositions);
        }
    }

    synchronized void observeRequest(String id, List<Entry> dispositions) {
        if (!closed && pending.containsKey(id)) observedRequests.put(id, List.copyOf(dispositions));
    }

    synchronized ContextLedgerSnapshot close() {
        if (!closed) {
            pending.forEach((id, items) -> {
                if (observedRequests.containsKey(id)) entries.addAll(observedRequests.get(id));
                else items.forEach(item -> entries.add(new Entry(item,
                        new ContextDecision(ContextDecision.Action.DELIVERY_UNOBSERVED, "REQUEST_NOT_SETTLED"), id, "")));
            });
            pending.clear();
            observedRequests.clear();
            closed = true;
        }
        return snapshot();
    }

    public synchronized ContextLedgerSnapshot snapshot() {
        List<Entry> values = new ArrayList<>(entries);
        observedRequests.values().forEach(values::addAll);
        List<Entry> copy = List.copyOf(values);
        return new ContextLedgerSnapshot(traceId, turnNumber, copy, ContextLedgerSummary.from(copy));
    }
}
