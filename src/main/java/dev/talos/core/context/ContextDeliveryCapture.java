package dev.talos.core.context;

import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ProviderContextMembership;
import dev.talos.spi.types.ProviderTransportHandoff;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Source disposition at the local transport boundary, not model attention or understanding. */
public final class ContextDeliveryCapture {
    private final ContextLedger owner;
    private final Map<ProviderContextMembership.MessageKey, List<ContextItem>> bindings;
    private final List<ContextItem> unboundSources;

    ContextDeliveryCapture(ContextLedger owner,
                           Map<ProviderContextMembership.MessageKey, List<ContextItem>> bindings,
                           List<ContextItem> unboundSources) {
        this.owner = owner;
        this.bindings = Map.copyOf(bindings);
        this.unboundSources = List.copyOf(unboundSources);
    }

    static ContextDeliveryCapture empty() {
        return new ContextDeliveryCapture(null, Map.of(), List.of());
    }

    /** Only the exact runtime-bound message is followed through the one-to-one sanitizer. */
    public Prepared prepare(List<ChatMessage> original, List<ChatMessage> sanitized) {
        List<BoundSource> sources = new ArrayList<>();
        unboundSources.forEach(item -> sources.add(new BoundSource(item, null)));
        bindings.forEach((key, items) -> {
            ProviderContextMembership.MessageKey represented = null;
            if (original != null && sanitized != null && original.size() == sanitized.size()) {
                for (int i = 0; i < original.size(); i++) {
                    ChatMessage input = original.get(i);
                    if (key.equals(messageKey(input))) {
                        ChatMessage output = sanitized.get(i);
                        represented = messageKey(output);
                        break;
                    }
                }
            }
            for (ContextItem item : items) sources.add(new BoundSource(item, represented));
        });
        return new Prepared(owner, List.copyOf(sources));
    }

    private record BoundSource(ContextItem item, ProviderContextMembership.MessageKey represented) {}

    private static ProviderContextMembership.MessageKey messageKey(ChatMessage message) {
        if (message == null) return null;
        try {
            return ProviderContextMembership.key(message.role(), message.content());
        } catch (IllegalArgumentException | NullPointerException unsupported) {
            return null;
        }
    }

    public static final class Prepared {
        private final ContextLedger owner;
        private final List<BoundSource> sources;

        private Prepared(ContextLedger owner, List<BoundSource> sources) {
            this.owner = owner;
            this.sources = sources;
        }

        public Request beginRequest(List<ChatMessage> fitted) {
            List<ProviderContextMembership.MessageKey> prepared = (fitted == null ? List.<ChatMessage>of() : fitted).stream()
                    .map(ContextDeliveryCapture::messageKey).filter(java.util.Objects::nonNull).toList();
            String id = owner == null ? "" : owner.beginRequest(sources.stream().map(BoundSource::item).toList());
            return new Request(owner, id, sources, prepared);
        }
    }

    public static final class Request implements AutoCloseable {
        private final ContextLedger owner;
        private final String id;
        private final List<BoundSource> sources;
        private final List<ProviderContextMembership.MessageKey> prepared;
        private ProviderContextMembership membership = ProviderContextMembership.unknown();
        private String bodyHash = "";
        private boolean observedHandoff;
        private boolean ambiguousHandoff;
        private boolean closed;

        private Request(ContextLedger owner, String id, List<BoundSource> sources,
                        List<ProviderContextMembership.MessageKey> prepared) {
            this.owner = owner;
            this.id = id;
            this.sources = sources;
            this.prepared = prepared;
        }

        public synchronized void handoff(ProviderTransportHandoff.DispatchEvidence evidence) {
            if (closed) return;
            String nextHash = evidence == null ? "" : evidence.providerBodyHash();
            ProviderContextMembership next = evidence == null ? ProviderContextMembership.unknown() : evidence.contextMembership();
            if (observedHandoff && (!bodyHash.equals(nextHash) || !membership.equals(next))) ambiguousHandoff = true;
            observedHandoff = true;
            bodyHash = nextHash;
            membership = next;
            if (owner != null && !id.isEmpty()) owner.observeRequest(id, dispositions());
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            if (owner == null || id.isEmpty()) return;
            owner.completeRequest(id, dispositions());
        }

        private List<ContextLedger.Entry> dispositions() {
            List<ContextLedger.Entry> dispositions = new ArrayList<>();
            for (BoundSource source : sources) {
                ContextDecision.Action action;
                String reason;
                if (ambiguousHandoff || !observedHandoff || !membership.observed() || !bodyHash.equals(membership.bodyHash())) {
                    action = ContextDecision.Action.DELIVERY_UNOBSERVED;
                    reason = ambiguousHandoff ? "CONFLICTING_HANDOFF_OBSERVATIONS"
                            : observedHandoff ? "SERIALIZED_MEMBERSHIP_UNOBSERVED" : "NO_TRANSPORT_HANDOFF_OBSERVED";
                } else if (source.represented() == null) {
                    action = ContextDecision.Action.DELIVERY_UNOBSERVED;
                    reason = "SOURCE_REPRESENTATION_UNBOUND";
                } else if (membership.contains(source.represented())) {
                    action = ContextDecision.Action.SERIALIZED_AT_TRANSPORT_HANDOFF;
                    reason = prepared.contains(source.represented()) ? "EXACT_MESSAGE_OBSERVED" : "SERIALIZED_OUTSIDE_PREPARED_MESSAGES";
                } else {
                    action = ContextDecision.Action.WITHHELD_FROM_MODEL;
                    reason = prepared.contains(source.represented()) ? "ABSENT_FROM_SERIALIZED_MESSAGES" : "OMITTED_DURING_REQUEST_PREPARATION";
                }
                dispositions.add(new ContextLedger.Entry(source.item(), new ContextDecision(action, reason), id, bodyHash));
            }
            return List.copyOf(dispositions);
        }
    }
}
