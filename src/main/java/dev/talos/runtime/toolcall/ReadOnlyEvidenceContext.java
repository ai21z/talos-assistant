package dev.talos.runtime.toolcall;

import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.intent.TargetRole;
import dev.talos.runtime.intent.TaskIntentResolver;
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

    static boolean requiresGeneralEvidenceContext(LoopState state) {
        return state.inheritedGeneralEvidenceContext || state.toolOutcomes.stream().anyMatch(outcome ->
                !"talos.read_file".equals(dev.talos.tools.ToolAliasPolicy.resolve(
                        outcome.toolName()).canonicalToolName()));
    }

    record Carrier(int index, ChatMessage message, ChatMessage.NativeToolCall call,
                   ReadEvidence evidence, String target) {}

    /** Delivery says which observations can reach a request, never that an answer is complete. */
    record Delivery(List<Carrier> carriers, boolean available, boolean retentionLimited,
                    int excludedSourceCount) {
        Delivery { carriers = List.copyOf(carriers); }
        static Delivery unavailable() { return new Delivery(List.of(), false, false, 0); }
        static Delivery retentionLimit() { return new Delivery(List.of(), false, true, 0); }
    }

    static Delivery delivery(LoopState state) {
        if (state == null || state.workspace == null || state.mutationSinceStart
                || state.mutatingToolSuccesses != 0 || state.contentWithheldFromModelContext
                || state.hasPendingActionObligation() || state.failureDecision.shouldStop()
                || state.toolOutcomes.stream().anyMatch(outcome -> outcome.mutating()
                    || outcome.mutationEffects().hasEffects())) return Delivery.unavailable();
        var contract = TurnTaskContractCapture.currentOrResolve(state.messages);
        if (contract.mutationAllowed() || contract.mutationRequested()) return Delivery.unavailable();
        return deliverObservations(state);
    }

    /** Same-turn preimages are data delivery, not a new read or mutation authorization. */
    static Delivery mutationContinuationDelivery(LoopState state) {
        if (state == null || state.workspace == null || state.ctx == null || state.mutationSinceStart
                || state.mutatingToolSuccesses != 0 || state.contentWithheldFromModelContext
                || state.failureDecision.shouldStop()
                || state.toolOutcomes.stream().anyMatch(outcome -> outcome.mutating() || outcome.denied()
                    || outcome.mutationEffects().hasEffects()
                    || outcome.failureReason() == dev.talos.tools.ToolFailureReason.USER_APPROVAL_DENIED
                    || outcome.failureReason() == dev.talos.tools.ToolFailureReason.PERMISSION_POLICY_DENIED
                    || outcome.failureReason() == dev.talos.tools.ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID))
            return Delivery.unavailable();
        var resolution = TurnTaskContractCapture.getResolution();
        if (resolution == null || !resolution.contract().mutationRequested()) return Delivery.unavailable();
        var phase = state.ctx.executionPhaseState() == null
                ? dev.talos.runtime.turn.CurrentTurnPlan.defaultPhaseFor(resolution.contract())
                : state.ctx.executionPhaseState().phase();
        if (dev.talos.runtime.policy.ActionObligationPolicy.derive(resolution, phase)
                != dev.talos.runtime.policy.ActionObligation.MUTATING_TOOL_REQUIRED)
            return Delivery.unavailable();
        return deliverObservations(state);
    }

    private static Delivery deliverObservations(LoopState state) {
        var observations = state.readEvidence.latestReadFileObservations();
        if (observations.size() > MAX_TARGETS) return Delivery.retentionLimit();

        List<Carrier> carriers = new ArrayList<>();
        var resultIndexes = new java.util.HashSet<Integer>();
        var callIds = new java.util.HashSet<String>();
        int characters = 0;
        int excludedSources = 0;
        for (ReadEvidence evidence : observations) {
            String target = evidence.effectivePath();
            var policy = ProtectedPathPolicy.classify(state.workspace, target);
            if (!policy.hasPath() || !policy.insideWorkspace() || policy.workspaceEscape()
                    || policy.protectedPath()
                    || state.readEvidence.latestReadFileForPath(target, true) != evidence
                    || evidence.ranged() || evidence.truncated() || evidence.redacted()
                    || evidence.body().isBlank()) {
                excludedSources++;
                continue;
            }
            var bound = state.readEvidence.toolResult(evidence);
            if (bound == null || bound.index() < 0 || bound.index() >= state.messages.size()
                    || failedRefreshAfter(state, target, bound.index())) {
                excludedSources++;
                continue;
            }
            ChatMessage current = state.messages.get(bound.index());
            ChatMessage original = bound.message();
            if (current == null || !"tool".equals(current.role()) || current.hasNativeToolCalls()
                    || !current.contentRestrictions().modelAllowed()
                    || !original.contentRestrictions().modelAllowed()
                    || !Objects.equals(current.toolCallId(), original.toolCallId())
                    || !(Objects.equals(current.content(), original.content())
                        || Objects.equals(current.content(), ToolCallSupport.summarizeToolResult(original.content())))
                    || bound.body() == null
                    || !bound.body().observedBody().equals(evidence.body())
                    || !bound.body().displayedBody().equals(evidence.body())
                    || original.content().contains("... (output truncated")) {
                excludedSources++;
                continue;
            }
            ChatMessage.NativeToolCall call = ReadOnlyEvidenceHandoff.boundReadCall(state, bound.index(), evidence);
            if (call == null || !resultIndexes.add(bound.index()) || !callIds.add(original.toolCallId())) {
                excludedSources++;
                continue;
            }
            characters += original.content().length();
            if (characters > MAX_EVIDENCE_CHARS) return Delivery.retentionLimit();
            // A restriction acquired after binding must survive temporary restoration.
            carriers.add(new Carrier(bound.index(), original.withContentRestrictions(current.contentRestrictions()),
                    call, evidence, target));
        }
        return new Delivery(carriers, true, false, excludedSources);
    }

    private static boolean failedRefreshAfter(LoopState state, String target, int observedIndex) {
        for (var outcome : state.toolOutcomes) {
            if (outcome.success() || !"read_file".equals(
                    dev.talos.tools.ToolAliasPolicy.localCanonicalName(outcome.toolName()))
                    || !state.readEvidence.sameEffectivePath(target, outcome.pathHint())) continue;
            // Only an execution-bound carrier can establish that a failed read was superseded.
            int resultIndex = state.toolOutcomeResults.resultIndex(outcome, state.toolOutcomes, state.messages);
            if (resultIndex < 0 || resultIndex >= observedIndex) return true;
        }
        return false;
    }

    record Selection(List<Carrier> carriers, boolean available, List<String> remainingTargets,
                     boolean retentionLimited, boolean requestLimited) {
        Selection {
            carriers = List.copyOf(carriers);
            remainingTargets = List.copyOf(remainingTargets);
        }
        boolean complete() { return available && remainingTargets.isEmpty(); }
        static Selection unavailable() { return new Selection(List.of(), false, List.of(), false, false); }
        static Selection retentionLimit() { return new Selection(List.of(), false, List.of(), true, false); }
        static Selection requestLimit() { return new Selection(List.of(), false, List.of(), false, true); }
    }

    static Selection select(LoopState state) {
        if (!safeReadOnlyState(state)) return Selection.unavailable();
        var contract = TurnTaskContractCapture.currentOrResolve(state.messages);
        if (contract.type() != TaskType.DIAGNOSE_ONLY && contract.type() != TaskType.READ_ONLY_QA)
            return Selection.unavailable();
        var targets = new LinkedHashSet<>(contract.expectedTargets());
        targets.addAll(contract.sourceEvidenceTargets());
        return selectSources(state, targets);
    }

    static Selection selectRequiredAcquisition(LoopState state) {
        if (!safeReadOnlyState(state)) return Selection.unavailable();
        var resolution = TurnTaskContractCapture.getResolution();
        if (resolution == null || resolution.contract().verificationRequired()
                || resolution.contract().type() == TaskType.UNKNOWN) return Selection.unavailable();
        var contract = resolution.contract();
        var targets = new LinkedHashSet<>(resolution.intent().targets().pathsByRole(TargetRole.MUST_READ));
        targets.addAll(resolution.intent().targets().pathsByRole(TargetRole.SOURCE_EVIDENCE));
        var legacy = resolution.intent().targets().pathsByRole(TargetRole.MUST_MUTATE);
        if (!legacy.isEmpty()) {
            if (contract.type() != TaskType.DIAGNOSE_ONLY
                    && contract.originalUserRequest().length() > ReadOnlyEvidenceSynthesizer.MAX_REQUEST_CHARS)
                return Selection.requestLimit();
            // Legacy placeholders are not read authority outside the diagnosis compatibility path.
            var directReads = contract.type() == TaskType.DIAGNOSE_ONLY
                    ? contract.expectedTargets()
                    : TaskIntentResolver.requiredReadTargetsForAcquisition(contract.originalUserRequest());
            for (String target : legacy) {
                if (contract.expectedTargets().contains(target) && directReads.contains(target)) targets.add(target);
            }
        }
        targets.removeAll(contract.forbiddenTargets());
        return selectSources(state, targets);
    }

    private static boolean safeReadOnlyState(LoopState state) {
        if (state == null || state.workspace == null || state.mutationSinceStart
                || state.mutatingToolSuccesses != 0 || state.contentWithheldFromModelContext
                || state.hasPendingActionObligation() || state.failedCalls != 0
                || state.failureDecision.shouldStop()
                || state.toolOutcomes.stream().anyMatch(outcome -> outcome.mutating()
                    || outcome.denied() || !outcome.success()
                    || outcome.mutationEffects().hasEffects())) return false;
        var contract = TurnTaskContractCapture.currentOrResolve(state.messages);
        return !contract.mutationAllowed() && !contract.mutationRequested();
    }

    private static Selection selectSources(LoopState state, java.util.Set<String> targets) {
        if (targets.isEmpty()) return Selection.unavailable();
        var sourcePaths = new ArrayList<>(targets.stream().sorted().toList());
        for (var observed : state.readEvidence.latestReadFileObservations()) {
            if (sourcePaths.stream().noneMatch(path -> state.readEvidence.sameEffectivePath(
                    path, observed.effectivePath()))) sourcePaths.add(observed.effectivePath());
        }
        if (sourcePaths.size() > MAX_TARGETS) return Selection.retentionLimit();

        List<Carrier> carriers = new ArrayList<>();
        List<String> remainingTargets = new ArrayList<>();
        var resultIndexes = new java.util.HashSet<Integer>();
        var callIds = new java.util.HashSet<String>();
        int characters = 0;
        for (String target : sourcePaths) {
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
                    || !current.contentRestrictions().modelAllowed()
                    || !original.contentRestrictions().modelAllowed()
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
            if (characters > MAX_EVIDENCE_CHARS) return Selection.retentionLimit();
            carriers.add(new Carrier(bound.index(), original.withContentRestrictions(current.contentRestrictions()),
                    call, evidence, target));
        }
        return new Selection(carriers, true, remainingTargets, false, false);
    }
}
