package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.policy.ProtectedPathPolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Opaque, bounded evidence continuation for the same read-only diagnosis turn.
 * This carries observed model-visible read results, not tool execution authority
 * or a claim that the filesystem has remained byte-identical since the reads.
 */
public final class ReadOnlyEvidenceHandoff {
    private static final ReadOnlyEvidenceHandoff EMPTY =
            new ReadOnlyEvidenceHandoff(null, null, "", 0, List.of());

    private final Path workspace;
    private final TaskContract contract;
    private final String traceId;
    private final int turn;
    private final List<Entry> entries;

    private ReadOnlyEvidenceHandoff(Path workspace, TaskContract contract, String traceId,
                                    int turn, List<Entry> entries) {
        this.workspace = workspace;
        this.contract = contract;
        this.traceId = traceId;
        this.turn = turn;
        this.entries = List.copyOf(entries);
    }

    public static ReadOnlyEvidenceHandoff empty() { return EMPTY; }

    /** Reuse the bounded JSON snapshot and exact proposal-to-executed-read binding for continuations. */
    static ChatMessage.NativeToolCall boundReadCall(LoopState state, int resultIndex, ReadEvidence evidence) {
        if (state == null || evidence == null || resultIndex < 0 || resultIndex >= state.messages.size())
            return null;
        try {
            ChatMessage result = state.messages.get(resultIndex);
            if (result == null) return null;
            LocatedCall original = locateCall(state.messages, resultIndex, result.toolCallId());
            if (original == null) return null;
            var converted = ToolCallSupport.convertNativeToolCalls(List.of(original.call()));
            return converted.size() == 1
                    && state.readEvidence.latestIdenticalCall(converted.getFirst(), true) == evidence
                    ? original.call() : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /** Sole factory: selection is derived from the actual executed loop ledger. */
    public static ReadOnlyEvidenceHandoff capture(LoopState state) {
        if (!eligible(state) || state.failureDecision.shouldStop()
                || state.readOnlySynthesisStatus != ToolCallLoop.ReadOnlySynthesisStatus.NOT_ATTEMPTED
                || !LocalTurnTraceCapture.isActive()
                || LocalTurnTraceCapture.currentTraceId().isBlank()
                || TurnTaskContractCapture.get() == null) return EMPTY;
        try {
            var selected = ReadOnlyEvidenceContext.select(state);
            if (selected.carriers().isEmpty()) return EMPTY;
            List<Entry> captured = new ArrayList<>();
            for (var carrier : selected.carriers()) {
                LocatedCall originalCall = locateCall(state.messages, carrier.index(),
                        carrier.message().toolCallId());
                if (originalCall == null) return EMPTY;
                // Only inert snapshotted JSON values reach the existing native converter.
                // Bind the original proposal to the exact typed call actually recorded,
                // rather than accepting a mutable assistant proposal changed before export.
                var converted = ToolCallSupport.convertNativeToolCalls(List.of(originalCall.call()));
                if (converted.size() != 1
                        || state.readEvidence.latestIdenticalCall(converted.getFirst(), true)
                            != carrier.evidence()) return EMPTY;
                captured.add(new Entry(carrier.target(), carrier.evidence(), carrier.index(),
                        carrier.message(), originalCall.index(), originalCall.call()));
            }
            var result = new ReadOnlyEvidenceHandoff(normalize(state.workspace),
                    TurnTaskContractCapture.get(), LocalTurnTraceCapture.currentTraceId(),
                    LocalTurnTraceCapture.currentTurnNumber(), captured);
            return result.matches(state.messages, state.workspace) ? result : EMPTY;
        } catch (RuntimeException invalid) {
            return EMPTY;
        }
    }

    /** Temporarily restore original tool bodies before the initial completeness retry dispatch. */
    public RequestOverlay restoreForRequest(List<ChatMessage> messages, Path currentWorkspace) {
        if (!matches(messages, currentWorkspace)) return RequestOverlay.empty();
        List<Replacement> changes = new ArrayList<>();
        for (Entry entry : entries) {
            ChatMessage current = messages.get(entry.resultIndex());
            if (!current.equals(entry.result()))
                changes.add(new Replacement(entry.resultIndex(), current, entry.result()));
        }
        RequestOverlay overlay = new RequestOverlay(messages, changes);
        try {
            for (Replacement change : changes) messages.set(change.index(), change.restored());
            return overlay;
        } catch (RuntimeException invalid) {
            overlay.close();
            return RequestOverlay.empty();
        }
    }

    /** Called only while constructing a fresh nested loop, before it processes any calls. */
    void seed(LoopState state) {
        if (!eligible(state) || state.iterations != 0 || state.totalToolsInvoked != 0
                || !state.toolOutcomes.isEmpty() || !state.readEvidence.isEmpty()
                || state.failureDecision.shouldStop()
                || state.readOnlySynthesisStatus != ToolCallLoop.ReadOnlySynthesisStatus.NOT_ATTEMPTED
                || !matches(state.messages, state.workspace)) return;
        for (Entry entry : entries) {
            state.readEvidence.importReusable(entry.evidence(),
                    new ReadEvidenceLedger.BoundToolResult(entry.resultIndex(), entry.result()));
            state.pathsReadThisTurn.add(ToolCallSupport.normalizePath(entry.evidence().effectivePath()));
        }
    }

    private boolean matches(List<ChatMessage> messages, Path currentWorkspace) {
        if (entries.isEmpty() || messages == null || workspace == null
                || !workspace.equals(normalize(currentWorkspace))
                || !LocalTurnTraceCapture.isActive()
                || !traceId.equals(LocalTurnTraceCapture.currentTraceId())
                || turn != LocalTurnTraceCapture.currentTurnNumber()
                || !contract.equals(TurnTaskContractCapture.get())) return false;
        try {
            var targets = new LinkedHashSet<>(contract.expectedTargets());
            targets.addAll(contract.sourceEvidenceTargets());
            if (targets.isEmpty() || targets.size() > ReadOnlyEvidenceContext.MAX_TARGETS
                    || entries.size() > targets.size()) return false;
            for (String target : targets) if (!safePath(target)) return false;
            var ids = new java.util.HashSet<String>();
            var indexes = new java.util.HashSet<Integer>();
            var pathIdentity = new ReadEvidenceLedger(workspace);
            int characters = 0;
            for (Entry entry : entries) {
                if (!targets.contains(entry.target()) || !safePath(entry.evidence().effectivePath())
                        || !pathIdentity.sameEffectivePath(entry.evidence().effectivePath(), entry.target())
                        || entry.resultIndex() < 0 || entry.resultIndex() >= messages.size()
                        || !ids.add(entry.result().toolCallId()) || !indexes.add(entry.resultIndex())) return false;
                ChatMessage current = messages.get(entry.resultIndex());
                if (current == null || !"tool".equals(current.role()) || current.hasNativeToolCalls()
                        || !Objects.equals(current.toolCallId(), entry.result().toolCallId())
                        || !(Objects.equals(current.content(), entry.result().content())
                            || Objects.equals(current.content(),
                                ToolCallSupport.summarizeToolResult(entry.result().content())))) return false;
                LocatedCall originalCall = locateCall(messages, entry.resultIndex(), entry.result().toolCallId());
                if (originalCall == null || originalCall.index() != entry.callIndex()
                        || !originalCall.call().equals(entry.call())) return false;
                characters += entry.result().content().length();
                if (characters > ReadOnlyEvidenceContext.MAX_EVIDENCE_CHARS) return false;
            }
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private boolean safePath(String target) {
        var policy = ProtectedPathPolicy.classify(workspace, target);
        return policy.hasPath() && policy.insideWorkspace() && !policy.workspaceEscape()
                && !policy.protectedPath();
    }

    private static boolean eligible(LoopState state) {
        return state != null && state.workspace != null && !state.mutationSinceStart
                && state.mutatingToolSuccesses == 0 && !state.contentWithheldFromModelContext
                && !state.hasPendingActionObligation() && state.failedCalls == 0
                && state.toolOutcomes.stream().noneMatch(outcome -> outcome.mutating()
                    || outcome.denied() || !outcome.success() || outcome.mutationEffects().hasEffects());
    }

    private static Path normalize(Path value) {
        if (value == null) return null;
        try { return value.toAbsolutePath().normalize(); }
        catch (RuntimeException invalid) { return null; }
    }

    private static LocatedCall locateCall(List<ChatMessage> messages, int resultIndex, String id) {
        if (id == null || id.isBlank()) return null;
        LocatedCall found = null;
        int resultCount = 0;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (message == null) return null;
            if ("tool".equals(message.role()) && id.equals(message.toolCallId())) resultCount++;
            if (!"assistant".equals(message.role()) || !message.hasNativeToolCalls()) continue;
            for (var call : message.toolCalls()) {
                if (call == null) return null;
                if (!id.equals(call.id())) continue;
                if (found != null || i >= resultIndex) return null;
                found = new LocatedCall(i, snapshotCall(call));
            }
        }
        return resultCount == 1 ? found : null;
    }

    private static ChatMessage.NativeToolCall snapshotCall(ChatMessage.NativeToolCall call) {
        if (call.name() == null || call.name().isBlank() || call.arguments() == null)
            throw new IllegalArgumentException("Missing native read identity");
        Budget budget = new Budget();
        Map<String, Object> arguments = snapshotMap(call.arguments(), 0, budget);
        return new ChatMessage.NativeToolCall(call.id(), call.name(), arguments);
    }

    private static Map<String, Object> snapshotMap(Map<?, ?> values, int depth, Budget budget) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (var entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Non-JSON key");
            budget.consume(key.length());
            copy.put(key, snapshotValue(entry.getValue(), depth + 1, budget));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object snapshotValue(Object value, int depth, Budget budget) {
        if (depth > 8) throw new IllegalArgumentException("Native identity nesting exceeds bound");
        budget.consume(value instanceof String text ? text.length() : 0);
        if (value == null || value instanceof String || value instanceof Boolean) return value;
        Class<?> type = value.getClass();
        if (type == Byte.class || type == Short.class || type == Integer.class || type == Long.class
                || type == java.math.BigInteger.class || type == java.math.BigDecimal.class) return value;
        if (type == Float.class && Float.isFinite((Float) value)
                || type == Double.class && Double.isFinite((Double) value)) return value;
        if (value instanceof Map<?, ?> map) return snapshotMap(map, depth, budget);
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) copy.add(snapshotValue(item, depth + 1, budget));
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("Non-JSON native identity");
    }

    private static final class Budget {
        private int nodes;
        private int characters;
        void consume(int length) {
            if (++nodes > 256 || (characters += length) > ReadOnlyEvidenceContext.MAX_EVIDENCE_CHARS)
                throw new IllegalArgumentException("Native identity exceeds bound");
        }
    }

    private record Entry(String target, ReadEvidence evidence, int resultIndex, ChatMessage result,
                         int callIndex, ChatMessage.NativeToolCall call) {}
    private record LocatedCall(int index, ChatMessage.NativeToolCall call) {}
    private record Replacement(int index, ChatMessage previous, ChatMessage restored) {}

    /** The overlay owns only exact replacements and never changes tool roles or IDs. */
    public static final class RequestOverlay implements AutoCloseable {
        private final List<ChatMessage> messages;
        private final List<Replacement> replacements;
        private boolean closed;

        private RequestOverlay(List<ChatMessage> messages, List<Replacement> replacements) {
            this.messages = messages;
            this.replacements = List.copyOf(replacements);
        }

        private static RequestOverlay empty() { return new RequestOverlay(null, List.of()); }

        @Override public void close() {
            if (closed) return;
            closed = true;
            for (Replacement replacement : replacements) {
                if (replacement.index() >= messages.size()) continue;
                if (replacement.restored().equals(messages.get(replacement.index())))
                    messages.set(replacement.index(), replacement.previous());
            }
        }
    }
}
