package dev.talos.cli.repl;

import dev.talos.runtime.Result;

import dev.talos.runtime.TurnAudit;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.TurnResult;
import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.context.ArtifactGoal;
import dev.talos.runtime.context.ChangeOffer;
import dev.talos.runtime.context.ProposalProvenance;
import dev.talos.runtime.intent.IndirectChangeRequest;
import dev.talos.runtime.intent.ProposalRequestBinding;
import dev.talos.runtime.task.StaticWebRequirements;
import dev.talos.runtime.policy.EvidenceObligationVerifier;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.PromptAuditRedactor;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.runtime.verification.ProofKind;
import dev.talos.runtime.verification.StaticWebInteractionVerifier;
import dev.talos.runtime.verification.TargetBinding;
import dev.talos.runtime.intent.WorkspaceOperationObligation;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolFailureReason;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives the next active task context from deterministic post-turn facts.
 */
public final class ActiveTaskContextUpdater {
    /** Operations after which a file inside the named folder is no longer where it was. */
    private static final Set<String> RELOCATION_TOOLS = Set.of(
            WorkspaceOperationObligation.Kind.DELETE_PATH.toolName(),
            WorkspaceOperationObligation.Kind.MOVE_PATH.toolName(),
            WorkspaceOperationObligation.Kind.RENAME_PATH.toolName());
    /** Roles of a named file that an operation does not act on. */
    private static final Set<String> NOT_OPERATED_ROLES = Set.of("FORBIDDEN", "MUST_READ", "SOURCE_EVIDENCE",
            "VERIFY_ONLY");
    private static final Pattern STATIC_WEB_FILE_TARGET = Pattern.compile(
            "(?i)\\b[A-Za-z0-9_.-]+\\.(?:html?|css|js|jsx|ts|tsx)\\b");


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
        if (!result.audit().contentRestrictions().historyAllowed()) {
            return new Update(preservedContext.suppressed(
                    "Document-derived proposal content was not retained."), ArtifactGoal.none());
        }

        TurnFacts facts = TurnFacts.from(result);
        if (changeOfferDelivered(result, facts, userInput, preservedContext)) {
            ActiveTaskContext context = ActiveTaskContext.changeOffer(
                    result.turnNumber(),
                    facts.traceId(),
                    preservedContext.targets(),
                    requestedChange(userInput),
                    preservedContext.requiredVerificationClaims(),
                    staticWebRequirements(userInput, facts, preservedContext));
            return active(context.withProposalProvenance(
                    ProposalProvenance.changeOffer(context, userInput, extractText(result.result()))));
        }
        ProposalRequestBinding proposal = ProposalRequestBinding.fromRequest(userInput);
        if (proposal.requested() && !facts.mutationAllowed() && !facts.anySuccessfulMutation()) {
            if (!preservedContext.staticWebRequirements().isEmpty()
                    || !preservedContext.requiredVerificationClaims().isEmpty()) {
                return new Update(preservedContext.suppressed(
                        "Retained requirements require explicit continuation scope."), ArtifactGoal.none());
            }
            if (!eligibleProposal(result, facts) || proposal.targets().isEmpty()
                    || proposal.targets().size() > ActiveTaskContext.MAX_TARGETS
                    || !StaticWebRequirements.explicitFacts(userInput).isEmpty()
                    || !eligibleProposalScope(proposal, facts)) {
                return new Update(ActiveTaskContext.none(), ArtifactGoal.none());
            }
            ActiveTaskContext context = ActiveTaskContext.proposedChanges(
                    result.turnNumber(), facts.traceId(), proposal.targets(), proposalSummary(result.result()));
            return active(context.withProposalProvenance(
                    ProposalProvenance.accepted(context, userInput, extractText(result.result()))));
        }
        // A workspace operation is resolved from the current message alone (MICA invariant I2), so its outcome is
        // never carried. Whatever the outcome, it ends a carried task on the files the request named or the calls
        // touched, including files inside a deleted, moved or renamed folder, and leaves an unrelated one.
        if (facts.workspaceOperationOnly()) {
            List<String> scope = new ArrayList<>(facts.operationPaths());
            scope.addAll(facts.namedTargets());
            return sharesTarget(preservedContext.targets(), scope, facts.relocationPaths())
                    ? new Update(ActiveTaskContext.none(), ArtifactGoal.none())
                    : new Update(preservedContext, preservedGoal);
        }
        // Beside content changes, an operation still ends a carried task on its paths or on a file the request
        // mentioned for it, and that task then supplies neither targets nor requirements to this turn's context.
        List<String> mixedScope = new ArrayList<>(facts.operationPaths());
        mixedScope.addAll(facts.mentionedTargets());
        boolean retiresCarried = facts.workspaceOperationAttempted()
                && sharesTarget(preservedContext.targets(), mixedScope, facts.relocationPaths());
        ActiveTaskContext carried = retiresCarried ? ActiveTaskContext.none() : preservedContext;
        List<String> targets = durableStaticWebTargets(facts.targets(), carried, userInput);
        StaticWebRequirements requirements = staticWebRequirements(userInput, facts, carried);

        if (facts.approvalDeniedMutationAttempt()) {
            // Only a denial of a change the request asked for is carried (MICA invariant I1). A file the model chose
            // and the user declined is no request of the user.
            if (targets.isEmpty() || !facts.requestedTargets()) {
                return new Update(ActiveTaskContext.none(), ArtifactGoal.none());
            }
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
                    facts.verificationStatus(),
                    requiredVerificationClaims(facts, userInput),
                    requirements);
            // The runtime failure header carries a repair offer. Binding it to the delivered answer lets a plain
            // affirmation resume repair of these targets, without wording from the model supplying authority.
            return active(context.withRepairOffer(extractText(result.result())));
        }

        if (!targets.isEmpty() && facts.fullyVerifiedMutation()) {
            if (looksLikeStaticWebTargets(targets)) {
                ActiveTaskContext context = ActiveTaskContext.verifiedMutation(
                        result.turnNumber(),
                        facts.traceId(),
                        targets,
                        facts.completionStatus(),
                        requirements);
                return active(context);
            }
            return new Update(ActiveTaskContext.none(), ArtifactGoal.none());
        }

        if (!targets.isEmpty()
                && facts.successfulMutation()
                && looksLikeStaticWebTargets(targets)) {
            ActiveTaskContext context = ActiveTaskContext.partialMutation(
                    result.turnNumber(),
                    facts.traceId(),
                    targets,
                    facts.completionStatus(),
                    requirements);
            return active(context);
        }

        if (!targets.isEmpty()
                && facts.mutationAllowed()
                && !facts.anySuccessfulMutation()
                && looksLikeStaticWebTargets(targets)
                && !proposal.requested()) {
            ActiveTaskContext context = ActiveTaskContext.pendingMutation(
                    result.turnNumber(),
                    facts.traceId(),
                    targets,
                    "No required static-web mutation completed.",
                    requirements);
            return active(context);
        }

        if (proposal.requested() && !facts.anySuccessfulMutation()
                && preservedContext.kind() == ActiveTaskContext.Kind.PROPOSED_CHANGES) {
            return new Update(ActiveTaskContext.none(), ArtifactGoal.none());
        }
        if (retiresCarried) return new Update(ActiveTaskContext.none(), ArtifactGoal.none());
        return new Update(preservedContext, preservedGoal);
    }

    private static Update active(ActiveTaskContext context) {
        return new Update(context, ArtifactGoal.fromActiveContext(context));
    }

    private static StaticWebRequirements staticWebRequirements(
            String userInput,
            TurnFacts facts,
            ActiveTaskContext preservedContext) {
        StaticWebRequirements current = StaticWebRequirements.fromRequest(
                userInput,
                facts == null ? java.util.Set.of() : new LinkedHashSet<>(facts.forbiddenTargets()));
        StaticWebRequirements preserved = preservedContext == null
                ? StaticWebRequirements.none()
                : preservedContext.staticWebRequirements();
        return preserved.merge(current);
    }

    /**
     * The runtime ended this read-only turn with a change offer for the files of the active task (decision D6). The
     * trace records that the runtime rendered it, and the delivered answer ends with the same line, so model text
     * alone cannot create the offer.
     */
    private static boolean changeOfferDelivered(
            TurnResult result, TurnFacts facts, String userInput, ActiveTaskContext preservedContext) {
        if (facts.mutationAllowed() || facts.anySuccessfulMutation()) return false;
        if (preservedContext.state() != ActiveTaskContext.State.ACTIVE || preservedContext.targets().isEmpty()) {
            return false;
        }
        LocalTurnTrace trace = facts.localTrace();
        if (trace == null || trace.turnNumber() != result.turnNumber() || facts.traceId().isBlank()) return false;
        return IndirectChangeRequest.recognized(userInput)
                && LocalTurnTraceCapture.changeOfferRendered(trace, preservedContext.targets())
                && ChangeOffer.renderedAtEnd(extractText(result.result()), preservedContext.targets());
    }

    private static String requestedChange(String userInput) {
        return "Requested change: " + PromptAuditRedactor.preview(
                userInput == null ? "" : userInput.strip(), ActiveTaskContext.MAX_PROPOSAL_CHARS);
    }

    private static String proposalSummary(Result result) {
        return PromptAuditRedactor.preview(extractText(result), ActiveTaskContext.MAX_PROPOSAL_CHARS);
    }

    private static boolean evidenceIncomplete(Result result) {
        return extractText(result).stripLeading()
                .startsWith(EvidenceObligationVerifier.MISSING_EVIDENCE_PREFIX);
    }

    private static boolean eligibleProposal(TurnResult result, TurnFacts facts) {
        LocalTurnTrace trace = facts.localTrace();
        if (trace == null || trace.turnNumber() != result.turnNumber() || facts.traceId().isBlank()
                || extractText(result.result()).isBlank() || evidenceIncomplete(result.result())) return false;
        boolean answered = "READ_ONLY_ANSWERED".equals(facts.completionStatus())
                && "NOT_RUN".equals(facts.verificationStatus());
        boolean verified = "COMPLETED_VERIFIED".equals(facts.completionStatus())
                && "PASSED".equals(facts.verificationStatus());
        return (answered || verified)
                && "COMPLETE".equals(trace.outcome().status())
                && facts.verificationStatus().equals(trace.outcome().verificationStatus())
                && "NOT_REQUESTED".equals(facts.mutationStatus())
                && "NONE".equals(trace.outcome().approvalStatus())
                && facts.audit().approvalsDenied() == 0
                && facts.audit().toolCalls().stream().allMatch(TurnRecord.ToolCallSummary::success)
                && facts.unsatisfiedRequiredClaimCount() == 0
                && trace.verification().problems().isEmpty()
                && trace.warnings().isEmpty();
    }

    private static boolean eligibleProposalScope(
            ProposalRequestBinding proposal, TurnFacts facts) {
        Set<String> targets = lexicalTargetSet(proposal.targets());
        Set<String> observed = new LinkedHashSet<>(lexicalTargetSet(facts.targets()));
        Set<String> forbidden = lexicalTargetSet(facts.forbiddenTargets());
        Set<String> deferred = lexicalTargetSet(proposal.deferredTargets());
        if (deferred.isEmpty()) {
            return observed.containsAll(targets) && forbidden.isEmpty();
        }
        // Each producer must retain every restriction of the fully accounted deferred request.
        if (facts.policyTrace() == null || facts.localTrace() == null
                || !lexicalTargetSet(facts.policyTrace().forbiddenTargets()).equals(deferred)
                || !lexicalTargetSet(facts.localTrace().taskContract().forbiddenTargets()).equals(deferred)) return false;
        observed.addAll(deferred);
        return observed.containsAll(targets);
    }

    private static Set<String> lexicalTargetSet(List<String> targets) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (targets == null) return out;
        for (String target : targets) {
            String normalized = target == null ? "" : target.strip().replace('\\', '/');
            while (normalized.startsWith("./")) normalized = normalized.substring(2);
            out.add(normalized);
        }
        return out;
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

    private static List<String> durableStaticWebTargets(
            List<String> currentTargets,
            ActiveTaskContext preservedContext,
            String userInput) {
        if (currentTargets == null || currentTargets.isEmpty()) return List.of();
        if (preservedContext == null
                || preservedContext.state() != ActiveTaskContext.State.ACTIVE
                || !preservedContext.hasTargets()) {
            return currentTargets;
        }
        List<String> preservedTargets = preservedContext.targets();
        if (!looksLikeStaticWebTargets(currentTargets) || !looksLikeStaticWebTargets(preservedTargets)) {
            return currentTargets;
        }
        Set<String> current = normalizedTargetSet(currentTargets);
        Set<String> preserved = normalizedTargetSet(preservedTargets);
        if (current.isEmpty() || preserved.isEmpty() || current.equals(preserved)) {
            return currentTargets;
        }
        if (!preserved.containsAll(current)) {
            return currentTargets;
        }
        if (explicitReplacementStaticWebTargets(userInput, preserved)) {
            return currentTargets;
        }
        return preservedTargets;
    }

    /**
     * A carried target is shared when it is one of the paths, or lies inside a deleted, moved or renamed folder. A
     * created folder leaves the files inside it as they were.
     */
    private static boolean sharesTarget(List<String> carried, List<String> paths, List<String> relocated) {
        Set<String> exact = normalizedTargetSet(paths);
        exact.addAll(normalizedTargetSet(relocated));
        Set<String> folders = normalizedTargetSet(relocated);
        for (String target : normalizedTargetSet(carried)) {
            if (exact.contains(target)) return true;
            for (String folder : folders) {
                if (target.startsWith(folder.endsWith("/") ? folder : folder + "/")) return true;
            }
        }
        return false;
    }

    private static boolean explicitReplacementStaticWebTargets(String userInput, Set<String> preservedTargets) {
        if (userInput == null || userInput.isBlank()
                || preservedTargets == null || preservedTargets.isEmpty()) {
            return false;
        }
        String lower = userInput.toLowerCase(Locale.ROOT);
        if (!(lower.contains("exactly") || lower.contains("only") || lower.contains("replace")
                || lower.contains("instead"))) {
            return false;
        }
        Set<String> mentioned = new LinkedHashSet<>();
        Matcher matcher = STATIC_WEB_FILE_TARGET.matcher(userInput);
        while (matcher.find()) {
            String target = normalizeTarget(matcher.group());
            if (!target.isBlank()) mentioned.add(target);
        }
        return !mentioned.isEmpty() && !mentioned.containsAll(preservedTargets);
    }

    private static Set<String> normalizedTargetSet(List<String> targets) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (targets == null) return out;
        for (String target : targets) {
            String normalized = normalizeTarget(target);
            if (!normalized.isBlank()) out.add(normalized);
        }
        return out;
    }

    private static String normalizeTarget(String target) {
        if (target == null) return "";
        String normalized = target.strip().replace('\\', '/').toLowerCase(Locale.ROOT);
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static boolean looksLikeStaticWebTargets(List<String> targets) {
        if (targets == null || targets.isEmpty()) return false;
        boolean html = false;
        boolean css = false;
        boolean js = false;
        for (String target : targets) {
            String lower = target == null ? "" : target.toLowerCase(Locale.ROOT);
            html = html || lower.endsWith(".html") || lower.endsWith(".htm");
            css = css || lower.endsWith(".css");
            js = js || lower.endsWith(".js") || lower.endsWith(".jsx")
                    || lower.endsWith(".ts") || lower.endsWith(".tsx");
        }
        return html && (css || js);
    }

    private static List<ActiveTaskContext.RequiredVerificationClaim> requiredVerificationClaims(
            TurnFacts facts,
            String userInput) {
        if (facts == null || !facts.unsatisfiedRequiredClaim()) return List.of();
        return StaticWebInteractionVerifier.detectBinding(userInput)
                .map(ActiveTaskContextUpdater::requiredStaticWebInteractionClaim)
                .map(List::of)
                .orElse(List.of());
    }

    private static ActiveTaskContext.RequiredVerificationClaim requiredStaticWebInteractionClaim(
            TargetBinding binding) {
        String id = "static-web-interaction:"
                + binding.triggerSelector() + "->" + binding.outputSelector();
        return new ActiveTaskContext.RequiredVerificationClaim(
                id,
                "Static interaction " + binding.triggerSelector() + " -> " + binding.outputSelector() + ".",
                ProofKind.STATIC_INTERACTION_GUARD.name(),
                binding.triggerSelector(),
                binding.outputSelector(),
                binding.eventType());
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
            List<String> forbiddenTargets,
            int requiredClaimCount,
            int unsatisfiedRequiredClaimCount,
            boolean mutationAllowed,
            boolean anySuccessfulMutation,
            boolean successfulMutation,
            boolean approvalDeniedMutationAttempt,
            boolean workspaceOperationOnly,
            List<String> operationPaths,
            List<String> relocationPaths,
            List<String> namedTargets,
            List<String> mentionedTargets,
            boolean requestedTargets
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
            List<TurnRecord.ToolCallSummary> mutatingCalls = calls.stream()
                    .filter(call -> isMutatingTool(call.name()))
                    .toList();
            boolean successfulMutation = !mutatingCalls.isEmpty()
                    && mutatingCalls.stream().allMatch(TurnRecord.ToolCallSummary::success);
            boolean anySuccessfulMutation = mutatingCalls.stream().anyMatch(TurnRecord.ToolCallSummary::success);
            List<TurnRecord.ToolCallSummary> operationCalls = mutatingCalls.stream()
                    .filter(call -> isWorkspaceOperationTool(call.name()))
                    .toList();
            // Every operand the audit names is a path of its operation, whatever the outcome. The source of a move or
            // rename and a deleted path are relocated, so a file inside them is no longer where it was. A destination,
            // a created folder and the source of a copy are matched exactly.
            List<String> operationPaths = operationCalls.stream()
                    .flatMap(call -> call.operands().stream())
                    .map(TurnRecord.OperationOperand::path)
                    .toList();
            List<String> relocationPaths = operationCalls.stream()
                    .filter(call -> RELOCATION_TOOLS.contains(ToolAliasPolicy.resolve(call.name()).canonicalToolName()))
                    .flatMap(call -> call.operands().stream())
                    .filter(operand -> operand.role() != WorkspaceOperationObligation.Role.DESTINATION)
                    .map(TurnRecord.OperationOperand::path)
                    .toList();
            List<String> contentPaths = mutatingCalls.stream()
                    .filter(call -> !isWorkspaceOperationTool(call.name()))
                    .flatMap(call -> call.pathHints().stream())
                    .toList();
            // A path that only a workspace operation touched is never a content target (MICA invariant I2), even
            // when the contract lists it. A deleted, moved or renamed path, or a file inside such a folder, is never
            // one. Any other operand that a content call also addressed stays one.
            List<String> named = namedTargets(localTrace, false);
            Targets selected = targets(policyTrace, localTrace, calls, named);
            List<String> targets = selected.paths().stream()
                    .filter(target -> !sharesTarget(List.of(target), List.of(), relocationPaths))
                    .filter(target -> !sharesTarget(List.of(target), operationPaths, List.of())
                            || sharesTarget(List.of(target), contentPaths, List.of()))
                    .toList();
            // A denied operation call is not a denied content change, so only denials beyond those count.
            long deniedOperationCalls = operationCalls.stream().filter(TurnFacts::denialCounted).count();
            boolean deniedMutation = audit.approvalsDenied() > deniedOperationCalls
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
                    forbiddenTargets(policyTrace, localTrace),
                    requiredClaimCount(localTrace),
                    unsatisfiedRequiredClaimCount(localTrace),
                    mutationAllowed(policyTrace, localTrace),
                    anySuccessfulMutation,
                    successfulMutation,
                    deniedMutation,
                    !operationCalls.isEmpty() && operationCalls.size() == mutatingCalls.size(),
                    operationPaths,
                    relocationPaths,
                    named,
                    namedTargets(localTrace, true),
                    selected.requested());
        }

        boolean workspaceOperationAttempted() {
            return !operationPaths.isEmpty();
        }

        /**
         * The files the request named for a change, or with {@code mentionedOnly} only those it mentioned. They end a
         * carried task on the files the request asked to operate on even when no audited call names them, as for a
         * call refused for invalid authority. An excluded file and a file named only to be read are not part of the
         * operation.
         */
        private static List<String> namedTargets(LocalTurnTrace localTrace, boolean mentionedOnly) {
            if (localTrace == null) return List.of();
            LinkedHashSet<String> out = new LinkedHashSet<>();
            if (!mentionedOnly) addAll(out, localTrace.taskContract().expectedTargets());
            for (LocalTurnTrace.RolefulTargetSummary target : localTrace.taskContract().rolefulTargets()) {
                if (target == null) continue;
                boolean mentioned = "MENTIONED_ONLY".equals(target.role());
                if (mentionedOnly ? mentioned : !NOT_OPERATED_ROLES.contains(target.role())) add(out, target.path());
            }
            return List.copyOf(out);
        }

        boolean verificationFailed() {
            return "FAILED".equalsIgnoreCase(verificationStatus);
        }

        // The task context records which files the checked work touched. A checks pass completes unverified under
        // MICA decision D5 but still checked the same files, so the context kind does not depend on the proof scope.
        boolean fullyVerifiedMutation() {
            return mutationSucceeded()
                    && "PASSED".equalsIgnoreCase(verificationStatus)
                    && ("COMPLETED_VERIFIED".equalsIgnoreCase(completionStatus)
                    || "COMPLETED_UNVERIFIED".equalsIgnoreCase(completionStatus));
        }

        boolean unsatisfiedRequiredClaim() {
            return requiredClaimCount > 0 && unsatisfiedRequiredClaimCount > 0;
        }

        private boolean mutationSucceeded() {
            if (mutationStatus == null || mutationStatus.isBlank()) return successfulMutation;
            return "SUCCEEDED".equalsIgnoreCase(mutationStatus);
        }

        /** The files a carried context may name, and whether every one of them came from the request. */
        private record Targets(List<String> paths, boolean requested) {}

        /**
         * The expected targets of the contract. Without them, a mutating call path counts only when the request
         * named it or the call changed that file, by succeeding or by an applied or uncertain effect on its own path,
         * so a path the model chose without a change on disk never becomes carried authority (MICA invariant I1).
         */
        private static Targets targets(
                TurnPolicyTrace policyTrace,
                LocalTurnTrace localTrace,
                List<TurnRecord.ToolCallSummary> calls,
                List<String> named) {
            LinkedHashSet<String> out = new LinkedHashSet<>();
            addAll(out, localTrace == null ? List.of() : localTrace.taskContract().expectedTargets());
            addAll(out, policyTrace == null ? List.of() : policyTrace.expectedTargets());
            if (!out.isEmpty()) return new Targets(List.copyOf(out), true);
            boolean requested = true;
            for (TurnRecord.ToolCallSummary call : calls) {
                if (call == null || !isMutatingTool(call.name())
                        || call.pathHint() == null || call.pathHint().isBlank()) {
                    continue;
                }
                boolean namedPath = sharesTarget(List.of(call.pathHint()), named, List.of());
                boolean changed = call.success() || call.mutationEffects() != null
                        && sharesTarget(List.of(call.pathHint()), call.mutationEffects().affectedPaths(), List.of());
                if (namedPath || changed) {
                    add(out, call.pathHint());
                    requested &= namedPath;
                }
            }
            return new Targets(List.copyOf(out), requested);
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

        private static List<String> forbiddenTargets(
                TurnPolicyTrace policyTrace,
                LocalTurnTrace localTrace) {
            LinkedHashSet<String> out = new LinkedHashSet<>();
            addAll(out, policyTrace == null ? List.of() : policyTrace.forbiddenTargets());
            addAll(out, localTrace == null ? List.of() : localTrace.taskContract().forbiddenTargets());
            return List.copyOf(out);
        }

        private static int requiredClaimCount(LocalTurnTrace localTrace) {
            return localTrace == null || localTrace.verification() == null
                    ? 0
                    : localTrace.verification().requiredClaimCount();
        }

        private static int unsatisfiedRequiredClaimCount(LocalTurnTrace localTrace) {
            return localTrace == null || localTrace.verification() == null
                    ? 0
                    : localTrace.verification().unsatisfiedRequiredClaimCount();
        }

        private static boolean mutationAllowed(TurnPolicyTrace policyTrace, LocalTurnTrace localTrace) {
            if (policyTrace != null && policyTrace.mutationAllowed()) return true;
            return localTrace != null && localTrace.taskContract().mutationAllowed();
        }

        private static boolean isMutatingTool(String toolName) {
            return ToolCallSupport.isMutatingTool(toolName);
        }

        private static boolean isWorkspaceOperationTool(String toolName) {
            var tool = ToolAliasPolicy.resolve(toolName);
            return tool.accepted() && WorkspaceOperationObligation.Kind.isOperationTool(tool.canonicalToolName());
        }

        /** A user denial and a policy denial of a call that needed approval both count in approvalsDenied. */
        private static boolean denialCounted(TurnRecord.ToolCallSummary call) {
            return call.failureReason() == ToolFailureReason.USER_APPROVAL_DENIED
                    || call.failureReason() == ToolFailureReason.PERMISSION_POLICY_DENIED
                    || call.reason().startsWith("approval denied");
        }
    }
}
