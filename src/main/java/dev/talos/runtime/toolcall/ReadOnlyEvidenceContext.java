package dev.talos.runtime.toolcall;

import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.policy.ProtectedPathPolicy;
import dev.talos.runtime.task.TaskType;
import dev.talos.spi.types.ChatMessage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Bounded restoration of current-turn, already model-visible diagnosis evidence. */
final class ReadOnlyEvidenceContext {
    static final int MAX_TARGETS = 8;
    static final int MAX_EVIDENCE_CHARS = 16_000;

    private ReadOnlyEvidenceContext() {}

    record Carrier(int index, ChatMessage message, ChatMessage.NativeToolCall call,
                   ReadEvidence evidence, String target) {}
    record Selection(List<Carrier> carriers, boolean available, List<String> remainingTargets) {
        Selection {
            carriers = List.copyOf(carriers);
            remainingTargets = List.copyOf(remainingTargets);
        }
        boolean complete() { return available && remainingTargets.isEmpty(); }
        static Selection unavailable() { return new Selection(List.of(), false, List.of()); }
    }

    static Selection select(LoopState state) {
        if (state == null || state.workspace == null || state.mutationSinceStart
                || state.mutatingToolSuccesses != 0 || state.contentWithheldFromModelContext
                || state.hasPendingActionObligation() || state.failedCalls != 0
                || state.failureDecision.shouldStop()
                || state.toolOutcomes.stream().anyMatch(outcome -> outcome.mutating()
                    || outcome.denied() || !outcome.success()
                    || outcome.mutationEffects().hasEffects())) return Selection.unavailable();
        var contract = TurnTaskContractCapture.currentOrResolve(state.messages);
        if (contract.type() != TaskType.DIAGNOSE_ONLY
                || contract.mutationAllowed() || contract.mutationRequested()) return Selection.unavailable();
        var targets = new LinkedHashSet<>(contract.expectedTargets());
        targets.addAll(contract.sourceEvidenceTargets());
        if (targets.isEmpty() || targets.size() > MAX_TARGETS) return Selection.unavailable();

        List<Carrier> carriers = new ArrayList<>();
        List<String> remainingTargets = new ArrayList<>();
        var resultIndexes = new java.util.HashSet<Integer>();
        var callIds = new java.util.HashSet<String>();
        int characters = 0;
        for (String target : targets.stream().sorted().toList()) {
            var policy = ProtectedPathPolicy.classify(state.workspace, target);
            if (!policy.hasPath() || !policy.insideWorkspace() || policy.workspaceEscape()
                    || policy.protectedPath()) return Selection.unavailable();
            ReadEvidence evidence = state.readEvidence.latestReadFileForPath(target, true);
            if (evidence == null) {
                // Invalidated evidence is not permission to reacquire a supposedly untouched target.
                if (state.readEvidence.latestReadFileForPath(target, false) != null)
                    return Selection.unavailable();
                remainingTargets.add(target);
                continue;
            }
            // Neither a partial latest read nor an older complete read can satisfy this transition.
            if (evidence.ranged() || evidence.truncated()
                    || evidence.redacted() || evidence.body().isBlank()) return Selection.unavailable();
            var bound = state.readEvidence.toolResult(evidence);
            if (bound == null || bound.index() < 0 || bound.index() >= state.messages.size())
                return Selection.unavailable();
            ChatMessage current = state.messages.get(bound.index());
            ChatMessage original = bound.message();
            if (current == null || !"tool".equals(current.role()) || current.hasNativeToolCalls()
                    || !Objects.equals(current.toolCallId(), original.toolCallId())
                    || !(Objects.equals(current.content(), original.content())
                        || Objects.equals(current.content(), ToolCallSupport.summarizeToolResult(original.content())))
                    || bound.body() == null
                    || !bound.body().observedBody().equals(evidence.body())
                    || !bound.body().displayedBody().equals(evidence.body())
                    || original.content().contains("... (output truncated"))
                return Selection.unavailable();
            ChatMessage.NativeToolCall call = ReadOnlyEvidenceHandoff.boundReadCall(
                    state, bound.index(), evidence);
            if (call == null) return Selection.unavailable();
            // Same-file aliases must not duplicate an assistant/tool ID in a provider request.
            if (!resultIndexes.add(bound.index()) || !callIds.add(original.toolCallId()))
                return Selection.unavailable();
            characters += original.content().length();
            if (characters > MAX_EVIDENCE_CHARS) return Selection.unavailable();
            carriers.add(new Carrier(bound.index(), original, call, evidence, target));
        }
        return new Selection(carriers, true, remainingTargets);
    }
}
