package dev.talos.cli.repl;

import dev.talos.runtime.TurnAudit;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.TurnResult;
import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.context.ArtifactGoal;
import dev.talos.runtime.policy.EvidenceObligationVerifier;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.PromptAuditRedactor;
import dev.talos.runtime.toolcall.ToolCallSupport;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Derives the next active task context from deterministic post-turn facts.
 */
public final class ActiveTaskContextUpdater {

    public record Update(ActiveTaskContext activeTaskContext, ArtifactGoal artifactGoal) {
        public Update {
            activeTaskContext = activeTaskContext == null ? ActiveTaskContext.none() : activeTaskContext;
            artifactGoal = artifactGoal == null ? ArtifactGoal.none() : artifactGoal;
        }
    }

    public Update updateAfterTurn(
            TurnResult result,
            String userInput,
            ActiveTaskContext previousContext,
            ArtifactGoal previousGoal) {
        ActiveTaskContext preservedContext = previousContext == null ? ActiveTaskContext.none() : previousContext;
        ArtifactGoal preservedGoal = previousGoal == null ? ArtifactGoal.none() : previousGoal;
        if (result == null) {
            return new Update(preservedContext, preservedGoal);
        }

        TurnFacts facts = TurnFacts.from(result);
        List<String> targets = facts.targets();

        if (facts.approvalDeniedMutationAttempt()) {
            ActiveTaskContext context = ActiveTaskContext.deniedMutation(
                    result.turnNumber(),
                    facts.traceId(),
                    targets,
                    "No files changed; approval denied by user.");
            return active(context);
        }

        if (!targets.isEmpty() && facts.verificationFailed()) {
            ActiveTaskContext context = ActiveTaskContext.verifierFindings(
                    result.turnNumber(),
                    facts.traceId(),
                    targets,
                    facts.verifierFindings(),
                    facts.verificationStatus());
            return active(context);
        }

        if (!targets.isEmpty() && facts.fullyVerifiedMutation()) {
            return new Update(ActiveTaskContext.none(), ArtifactGoal.none());
        }

        if (!targets.isEmpty()
                && looksLikeProposalIntent(userInput)
                && evidenceIncomplete(result.result())) {
            return new Update(ActiveTaskContext.none(), ArtifactGoal.none());
        }

        if (!targets.isEmpty()
                && !facts.mutationAllowed()
                && !facts.successfulMutation()
                && !facts.approvalDeniedMutationAttempt()
                && looksLikeProposalIntent(userInput)) {
            ActiveTaskContext context = ActiveTaskContext.proposedChanges(
                    result.turnNumber(),
                    facts.traceId(),
                    targets,
                    proposalSummary(result.result()));
            return active(context);
        }

        return new Update(preservedContext, preservedGoal);
    }

    private static Update active(ActiveTaskContext context) {
        return new Update(context, ArtifactGoal.fromActiveContext(context));
    }

    private static String proposalSummary(Result result) {
        return PromptAuditRedactor.preview(extractText(result), ActiveTaskContext.MAX_PROPOSAL_CHARS);
    }

    private static boolean evidenceIncomplete(Result result) {
        return extractText(result).stripLeading()
                .startsWith(EvidenceObligationVerifier.MISSING_EVIDENCE_PREFIX);
    }

    private static String extractText(Result result) {
        if (result == null) return "";
        return switch (result) {
            case Result.Ok ok -> ok.text;
            case Result.Streamed streamed -> streamed.fullText;
            case Result.Info ignored -> "";
            case Result.TrustedInfo ignored -> "";
            case Result.Error ignored -> "";
            case Result.Table ignored -> "";
            case Result.StreamStart ignored -> "";
            case Result.StreamChunk ignored -> "";
            case Result.StreamEnd ignored -> "";
            case Result.ToolProgress ignored -> "";
        };
    }

    private static boolean looksLikeProposalIntent(String userInput) {
        if (userInput == null || userInput.isBlank()) return false;
        String lower = userInput.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        boolean explicitProposal = lower.contains("propose")
                || lower.contains("proposal")
                || lower.contains("suggest changes")
                || lower.contains("suggest the changes")
                || lower.contains("what would you change")
                || lower.contains("would change");
        boolean noMutationYet = lower.contains("before editing")
                || lower.contains("before applying")
                || lower.contains("do not edit")
                || lower.contains("don't edit")
                || lower.contains("without editing")
                || lower.contains("without changing");
        boolean changeIntent = lower.contains("change")
                || lower.contains("edit")
                || lower.contains("update")
                || lower.contains("fix")
                || lower.contains("apply");
        return explicitProposal || (noMutationYet && changeIntent);
    }

    private record TurnFacts(
            TurnAudit audit,
            TurnPolicyTrace policyTrace,
            LocalTurnTrace localTrace,
            List<String> targets,
            String traceId,
            String verificationStatus,
            String mutationStatus,
            String completionStatus,
            List<String> verifierFindings,
            boolean mutationAllowed,
            boolean successfulMutation,
            boolean approvalDeniedMutationAttempt
    ) {

        static TurnFacts from(TurnResult result) {
            TurnAudit audit = result.audit() == null ? TurnAudit.empty() : result.audit();
            TurnPolicyTrace policyTrace = audit.policyTrace() == null
                    ? TurnPolicyTrace.empty()
                    : audit.policyTrace();
            LocalTurnTrace localTrace = audit.localTrace();
            List<TurnRecord.ToolCallSummary> calls = audit.toolCalls() == null
                    ? List.of()
                    : audit.toolCalls();
            List<String> targets = targets(policyTrace, localTrace, calls);
            List<TurnRecord.ToolCallSummary> mutatingCalls = calls.stream()
                    .filter(call -> isMutatingTool(call.name()))
                    .toList();
            boolean successfulMutation = !mutatingCalls.isEmpty()
                    && mutatingCalls.stream().allMatch(TurnRecord.ToolCallSummary::success);
            boolean deniedMutation = audit.approvalsDenied() > 0
                    && (mutationAllowed(policyTrace, localTrace)
                    || !mutatingCalls.isEmpty());
            String verificationStatus = verificationStatus(localTrace);
            return new TurnFacts(
                    audit,
                    policyTrace,
                    localTrace,
                    targets,
                    traceId(localTrace),
                    verificationStatus,
                    mutationStatus(localTrace),
                    completionStatus(localTrace),
                    verifierFindings(localTrace),
                    mutationAllowed(policyTrace, localTrace),
                    successfulMutation,
                    deniedMutation);
        }

        boolean verificationFailed() {
            return "FAILED".equalsIgnoreCase(verificationStatus);
        }

        boolean fullyVerifiedMutation() {
            return mutationSucceeded()
                    && "PASSED".equalsIgnoreCase(verificationStatus)
                    && "COMPLETED_VERIFIED".equalsIgnoreCase(completionStatus);
        }

        private boolean mutationSucceeded() {
            if (mutationStatus == null || mutationStatus.isBlank()) return successfulMutation;
            return "SUCCEEDED".equalsIgnoreCase(mutationStatus);
        }

        private static List<String> targets(
                TurnPolicyTrace policyTrace,
                LocalTurnTrace localTrace,
                List<TurnRecord.ToolCallSummary> calls) {
            LinkedHashSet<String> out = new LinkedHashSet<>();
            addAll(out, localTrace == null ? List.of() : localTrace.taskContract().expectedTargets());
            addAll(out, policyTrace == null ? List.of() : policyTrace.expectedTargets());
            if (out.isEmpty()) {
                for (TurnRecord.ToolCallSummary call : calls) {
                    if (call != null && isMutatingTool(call.name())) {
                        add(out, call.pathHint());
                    }
                }
            }
            return List.copyOf(out);
        }

        private static void addAll(LinkedHashSet<String> out, List<String> values) {
            if (values == null) return;
            for (String value : values) {
                add(out, value);
            }
        }

        private static void add(LinkedHashSet<String> out, String value) {
            if (value == null) return;
            String normalized = value.strip();
            if (!normalized.isBlank()) out.add(normalized);
        }

        private static String traceId(LocalTurnTrace localTrace) {
            return localTrace == null ? "" : localTrace.traceId();
        }

        private static String verificationStatus(LocalTurnTrace localTrace) {
            if (localTrace == null) return "";
            String fromVerification = localTrace.verification().status();
            if (fromVerification != null && !fromVerification.isBlank()) return fromVerification;
            return localTrace.outcome().verificationStatus();
        }

        private static String mutationStatus(LocalTurnTrace localTrace) {
            return localTrace == null ? "" : localTrace.outcome().mutationStatus();
        }

        private static String completionStatus(LocalTurnTrace localTrace) {
            if (localTrace == null) return "";
            String classification = localTrace.outcome().classification();
            if (classification != null && !classification.isBlank()) return classification;
            return localTrace.outcome().status();
        }

        private static List<String> verifierFindings(LocalTurnTrace localTrace) {
            if (localTrace == null || localTrace.verification() == null) return List.of();
            List<String> problems = localTrace.verification().problems();
            if (problems != null && !problems.isEmpty()) return List.copyOf(problems);
            String summary = localTrace.verification().summary();
            if (summary == null || summary.isBlank()) return List.of();
            List<String> out = new ArrayList<>();
            out.add(summary);
            return List.copyOf(out);
        }

        private static boolean mutationAllowed(TurnPolicyTrace policyTrace, LocalTurnTrace localTrace) {
            if (policyTrace != null && policyTrace.mutationAllowed()) return true;
            return localTrace != null && localTrace.taskContract().mutationAllowed();
        }

        private static boolean isMutatingTool(String toolName) {
            return ToolCallSupport.isMutatingTool(toolName);
        }
    }
}
