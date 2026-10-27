package dev.talos.runtime.policy;

import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.expectation.LiteralContentExpectation;
import dev.talos.runtime.expectation.TaskExpectation;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.PromptAuditRedactor;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.runtime.workspace.WorkspaceOperationAuthorityPlan;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Renders a short current-turn-local capability frame from runtime state. */
public final class CurrentTurnCapabilityFrame {
    private static final int MAX_INLINE_EXACT_CONTENT_CHARS = 4_000;

    private CurrentTurnCapabilityFrame() {}

    public static String render(CurrentTurnPlan plan) {
        if (plan == null) {
            return render(null, ExecutionPhase.INSPECT, List.of());
        }
        String frame = render(
                plan.taskContract(),
                plan.phaseInitial(),
                plan.visibleTools(),
                EvidenceObligationPolicy.parse(plan.evidenceObligation()),
                plan.activeTaskContext(),
                plan.artifactGoal(),
                plan.taskExpectations(),
                plan.actionObligation(),
                plan.workspaceOperationPlan(),
                plan.contentMutationPredecessorPaths());
        var binding = plan.contentSaveRequest().clarificationBinding();
        if (binding != null) {
            frame += "\n[OperandClarification]\nOriginal task turn: " + binding.originTurn()
                    + "\nFilename reply turn: " + binding.replyTurn()
                    + "\nThe original task remains authoritative. Only its missing destination was clarified. "
                    + "All requested results and dependencies must be retained. "
                    + "Whole request coverage remains unassessed. A verified file operation does not prove complete fulfillment. "
                    + "Normal proposal validation and approval remain required.\n[/OperandClarification]";
        }
        return frame;
    }

    public static String render(TaskContract contract, ExecutionPhase phase, List<String> visibleTools) {
        return render(
                contract,
                phase,
                visibleTools,
                EvidenceObligationPolicy.derive(
                        contract,
                        phase,
                        null),
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                List.of(),
                null,
                null,
                List.of());
    }

    private static String render(
            TaskContract contract,
            ExecutionPhase phase,
            List<String> visibleTools,
            EvidenceObligation evidenceObligation,
            String activeTaskContext,
            String artifactGoal,
            List<TaskExpectation> taskExpectations,
            ActionObligation suppliedActionObligation,
            WorkspaceOperationAuthorityPlan workspaceOperationPlan,
            List<String> contentMutationPredecessorPaths
    ) {
        TaskType type = contract == null || contract.type() == null ? TaskType.UNKNOWN : contract.type();
        ExecutionPhase safePhase = phase == null ? ExecutionPhase.INSPECT : phase;
        ActionObligation obligation = suppliedActionObligation == null
                ? ActionObligationPolicy.derive(contract, safePhase)
                : suppliedActionObligation;
        EvidenceObligation evidence = evidenceObligation == null
                ? EvidenceObligation.NONE
                : evidenceObligation;
        boolean mutationAllowed = contract != null
                && contract.mutationAllowed()
                && carriesMutationAuthority(obligation);
        boolean verificationRequired = contract != null && contract.verificationRequired();
        String tools = visibleTools == null || visibleTools.isEmpty()
                ? "(none)"
                : String.join(", ", visibleTools);

        StringBuilder frame = new StringBuilder();
        frame.append("[CurrentTurnCapability]\n")
                .append("[TaskContract]\n")
                .append("type: ").append(type.name()).append('\n')
                .append("mutationAllowed: ").append(mutationAllowed).append('\n')
                .append("verificationRequired: ").append(verificationRequired).append('\n')
                .append("phase: ").append(safePhase.name()).append('\n')
                .append("visibleTools: ").append(tools).append('\n')
                .append("obligation: ").append(obligation.name()).append('\n')
                .append("evidenceObligation: ").append(evidence.name()).append('\n');
        appendExpectedTargets(frame, contract, mutationAllowed, obligation);
        appendWorkspaceOperationGuidance(
                frame,
                workspaceOperationPlan,
                contentMutationPredecessorPaths,
                mutationAllowed);
        appendTextFormatChangeGuidance(frame, contract, mutationAllowed, visibleTools);
        appendSourceEvidenceTargets(frame, contract, mutationAllowed);
        appendStaticWebRequirements(frame, contract, mutationAllowed);
        appendStaticWebRewriteGroundingGuidance(frame, contract, mutationAllowed, visibleTools);
        appendActiveTaskContext(frame, activeTaskContext, artifactGoal);
        appendProposalApplyGuidance(frame, activeTaskContext, artifactGoal, mutationAllowed);
        if (mutationAllowed) {
            appendTaskExpectations(frame, taskExpectations);
        }

        switch (obligation) {
            case MUTATING_TOOL_REQUIRED -> frame
                    .append("Available mutating tools: ")
                    .append(availableFileMutationTools(visibleTools))
                    .append(".\n")
                    .append("""
                    Use file tools to apply the requested workspace change in this turn.
                    Runtime handles approval, permissions, checkpointing, and verification.
                    Do not say you lack filesystem or workspace access.
                    Do not provide manual snippets instead of acting unless a narrow clarification is genuinely required.""");
            case WORKSPACE_OPERATION_REQUIRED -> frame.append("""
                    Use only the exact workspace operation tool named in the typed operation frame for that operation.
                    Do not emulate move, copy, rename, or mkdir by manually writing or editing file contents.
                    Runtime handles approval, permissions, checkpointing, and verification.
                    Do not say you lack filesystem or workspace access.
                    Do not provide manual instructions instead of acting unless a narrow clarification is genuinely required.""");
            case CONDITIONAL_REVIEW_FIX -> frame.append("""
                    This is a conditional review-and-fix turn.
                    Inspect the relevant files first using read-only tools.
                    Only call talos.write_file or talos.edit_file after evidence shows an obvious issue, or when you are applying a concrete repair.
                    If inspection finds no current browser-blocking issue, say: No file change is required.
                    Do not make a harmless or no-op edit just to satisfy mutation.""");
            case LIST_DIR_ONLY -> frame.append("""
                    This turn asks only for directory entries.
                    Use only talos.list_dir.
                    Do not read, grep, retrieve, summarize, write, or edit file contents.""");
            case INSPECT_REQUIRED -> frame.append("""
                    This turn is read-only workspace inspection.
                    Use read-only tools to inspect evidence before answering.
                    Do not call mutating tools.
                    If you identify a possible fix, describe it and wait for an explicit change request before editing.""");
            case VERIFY_FROM_EVIDENCE -> frame.append("""
                    This turn is verify/status-oriented.
                    Use read-only evidence or prior verified outcomes.
                    Do not call mutating tools.
                    If you identify a possible fix, describe it and wait for an explicit change request before editing.""");
            case DIRECT_ANSWER_ONLY -> {
                frame.append("""
                        This turn requires a direct answer without workspace inspection.
                        No workspace tools are visible.
                        Do not call tools.
                        Do not claim to have inspected files or performed workspace actions.
                        """);
                if (type == TaskType.SMALL_TALK
                        && (CapabilityAnswerPolicy.looksLikeIdentityTurn(contract.originalUserRequest())
                        || CapabilityAnswerPolicy.looksLikeCapabilityTurn(contract.originalUserRequest()))) {
                    frame.append("Answer directly from Talos product identity/capability only.");
                } else {
                    frame.append("Answer the user's requested topic directly using general knowledge "
                            + "and information already provided in the conversation.");
                }
            }
            case REPAIR_FROM_VERIFIER_FINDINGS -> frame.append("""
                    Repair must be based on previous verifier findings and remain bounded.
                    Use the visible file tools only if mutation is allowed.""");
            case NONE, UNKNOWN -> frame.append("""
                    Follow the visible tool surface and task contract.
                    Do not claim unavailable workspace capabilities that the runtime has exposed.""");
                }
        appendFileGroundedAnswerGuidance(frame, contract, mutationAllowed);
        appendReadOnlyProposalGroundingGuidance(frame, contract, mutationAllowed);
        appendDirectoryAwareVerificationGuidance(frame, contract, visibleTools);
        frame.append('\n').append(evidenceGuidance(evidence));
        var comparison = dev.talos.runtime.task.DocumentComparisonRequest.from(contract);
        if (comparison.active()) {
            frame.append("\nEach requested ordered pair must be inspected with talos.compare_documents before finalization. A raw diff does not establish behavioral equivalence. Separately requested reads and results remain required.");
            for (var requirement : comparison.requirements()) frame.append("\nComparison left ")
                    .append(requirement.pair().left()).append(", right ").append(requirement.pair().right()).append('.');
        }
        return frame.toString();
    }

    private static boolean carriesMutationAuthority(
            ActionObligation obligation
    ) {
        return obligation == ActionObligation.MUTATING_TOOL_REQUIRED
                || obligation
                        == ActionObligation.WORKSPACE_OPERATION_REQUIRED
                || obligation == ActionObligation.CONDITIONAL_REVIEW_FIX
                || obligation
                        == ActionObligation.REPAIR_FROM_VERIFIER_FINDINGS;
    }

    private static String availableFileMutationTools(List<String> visibleTools) {
        if (visibleTools == null || visibleTools.isEmpty()) {
            return "(none visible)";
        }
        List<String> available = visibleTools.stream()
                .filter(tool -> "talos.write_file".equals(tool) || "talos.edit_file".equals(tool))
                .toList();
        if (available.isEmpty()) {
            return "(none visible)";
        }
        return String.join(", ", available);
    }

    private static void appendTextFormatChangeGuidance(
            StringBuilder frame,
            TaskContract contract,
            boolean mutationAllowed,
            List<String> visibleTools
    ) {
        if (!mutationAllowed
                || contract == null
                || !"explicit-text-format-request".equals(contract.classificationReason())
                || visibleTools == null
                || !visibleTools.contains("talos.read_file")
                || !visibleTools.contains("talos.write_file")) {
            return;
        }
        frame.append("[TextFormatChange]\n")
                .append("Read the exact target first with talos.read_file.\n")
                .append("Use talos.write_file with the complete text for that same path.\n")
                .append("Change only the requested text format and preserve the file's text content.\n");
    }

    private static void appendDirectoryAwareVerificationGuidance(
            StringBuilder frame,
            TaskContract contract,
            List<String> visibleTools
    ) {
        if (contract == null || contract.type() != TaskType.VERIFY_ONLY) return;
        if (visibleTools == null
                || !visibleTools.contains("talos.list_dir")
                || !visibleTools.contains("talos.read_file")) {
            return;
        }
        frame.append("""

                [DirectoryAwareVerification]
                Use talos.list_dir for directory paths.
                Use talos.read_file for file paths.
                A successful talos.list_dir result, including "(empty directory)", is directory existence evidence.
                Do not call mutating workspace operation tools for verification-only path checks.""");
    }

    private static void appendReadOnlyProposalGroundingGuidance(
            StringBuilder frame,
            TaskContract contract,
            boolean mutationAllowed
    ) {
        if (mutationAllowed || contract == null || contract.originalUserRequest() == null) return;
        String lower = contract.originalUserRequest().toLowerCase(java.util.Locale.ROOT);
        boolean reviewProposal = (lower.contains("review") || lower.contains("propose")
                || lower.contains("proposal") || lower.contains("improvement")
                || lower.contains("suggest"))
                && (lower.contains("readme") || lower.contains(".md"));
        if (!reviewProposal) return;
        frame.append("""

                [GroundedReviewProposal]
                For review/proposal output, separate observed evidence from suggestions.
                Do not state commands, dependencies, package managers, frameworks, scripts, licenses, or file meanings as facts unless they were observed in the inspected workspace evidence.
                If a command or dependency is only a possible suggestion, mark it as "if applicable" or a placeholder.
                Respect current-turn exclusions such as protected files the user says not to inspect or discuss.""");
    }

    private static void appendFileGroundedAnswerGuidance(
            StringBuilder frame,
            TaskContract contract,
            boolean mutationAllowed
    ) {
        if (mutationAllowed || contract == null || contract.type() == null) return;
        if (!isFileGroundedReadOnlyType(contract.type())) return;

        frame.append("""

                [FileGroundedAnswer]
                Workspace path/name metadata is not file evidence.
                Use workspace paths as location labels only.
                Do not present a workspace directory name as a project name, package name, product name, framework, dependency, script, or other file-grounded fact unless that fact appears in current-turn read/search/list results.
                When the user asks to only state facts from files, only state file-grounded facts that were observed in read/search/list results; otherwise say the inspected files do not state it.""");
    }

    private static boolean isFileGroundedReadOnlyType(TaskType type) {
        return type == TaskType.READ_ONLY_QA
                || type == TaskType.WORKSPACE_EXPLAIN
                || type == TaskType.DIAGNOSE_ONLY;
    }

    private static void appendExpectedTargets(
            StringBuilder frame,
            TaskContract contract,
            boolean mutationAllowed,
            ActionObligation obligation
    ) {
        if (!mutationAllowed || contract == null || contract.expectedTargets().isEmpty()) {
            return;
        }
        List<String> targets = orderedExpectedTargets(contract);
        frame.append("[ExpectedTargets]\n")
                .append("requiredTargets: ").append(String.join(", ", targets)).append('\n');
        if (obligation == ActionObligation.CONDITIONAL_REVIEW_FIX) {
            frame.append("Inspect these exact target paths when they are relevant to the review.\n")
                    .append("If evidence shows a repair is needed, write or edit these exact target paths.\n")
                    .append("Similar filenames are not substitutes for required target paths.\n")
                    .append("script.js and scripts.js are different target paths; preserve the exact requested spelling.\n")
                    .append("Do not complete a needed repair by mutating only a similar sibling filename.\n");
            return;
        }
        frame.append("You must write or edit these exact target paths for this turn.\n")
                .append("Similar filenames are not substitutes for required target paths.\n")
                .append("script.js and scripts.js are different target paths; preserve the exact requested spelling.\n")
                .append("Do not put required root files inside css/, js/, assets/, site/, or other subdirectories unless the required target path explicitly includes that directory.\n")
                .append("Do not complete this turn by mutating only a similar sibling filename.\n");
    }

    private static void appendWorkspaceOperationGuidance(
            StringBuilder frame,
            WorkspaceOperationAuthorityPlan plan,
            List<String> contentPredecessors,
            boolean mutationAllowed
    ) {
        if (!mutationAllowed
                || plan == null
                || plan.authorizedOperations().size() != 1) {
            return;
        }
        var operation = plan.authorizedOperations().getFirst();
        List<String> predecessors = contentPredecessors == null
                ? List.of()
                : contentPredecessors;
        frame.append("[TypedWorkspaceOperation]\n")
                .append("operationKind: ")
                .append(operation.kind().name())
                .append('\n')
                .append("exactTool: ")
                .append(operation.toolName())
                .append('\n');
        for (var operand : operation.operands()) {
            String label = switch (operand.role()) {
                case SOURCE -> "sourcePath";
                case DESTINATION -> "destinationPath";
                case TARGET -> "targetPath";
            };
            frame.append(label)
                    .append(": ")
                    .append(operand.effectivePath())
                    .append('\n');
        }
        frame.append("overwritePolicy: ")
                .append(operation.overwritePolicy().name())
                .append('\n')
                .append("recursivePolicy: ")
                .append(operation.recursivePolicy().name())
                .append('\n')
                .append("parentPolicy: ")
                .append(operation.parentPolicy().name())
                .append('\n');
        if (predecessors.isEmpty()) {
            frame.append("Call the exact tool once with exactly these typed operands.\n");
            return;
        }
        frame.append("contentPredecessors: ")
                .append(String.join(", ", predecessors))
                .append('\n')
                .append("First complete every content predecessor with the visible file tools. Then call the exact workspace operation tool once.\n")
                .append("Do not call the workspace operation before every predecessor succeeds.\n");
    }

    private static List<String> orderedExpectedTargets(TaskContract contract) {
        Set<String> expected = contract.expectedTargets();
        String request = contract.originalUserRequest() == null
                ? ""
                : contract.originalUserRequest().toLowerCase(java.util.Locale.ROOT);
        return expected.stream()
                .sorted(Comparator
                        .comparingInt((String target) -> targetIndex(request, target))
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    private static void appendSourceEvidenceTargets(
            StringBuilder frame,
            TaskContract contract,
            boolean mutationAllowed
    ) {
        if (!mutationAllowed || contract == null || contract.sourceEvidenceTargets().isEmpty()) {
            return;
        }
        List<String> targets = orderedSourceEvidenceTargets(contract);
        frame.append("[SourceEvidenceTargets]\n")
                .append("sourceTargets: ").append(String.join(", ", targets)).append('\n')
                .append("Read these exact source target paths before writing or editing the requested output target(s).\n")
                .append("Use the source content only for the requested derived artifact.\n")
                .append("Do not read protected or unrelated files unless the user explicitly named them as source targets.\n");
    }

    private static void appendStaticWebRequirements(
            StringBuilder frame,
            TaskContract contract,
            boolean mutationAllowed
    ) {
        if (!mutationAllowed
                || contract == null
                || contract.staticWebRequirements().isEmpty()) {
            return;
        }
        var requirements = contract.staticWebRequirements();
        frame.append("[StaticWebRequirements]\n");
        if (!requirements.requiredVisibleFacts().isEmpty()) {
            frame.append("requiredVisibleFacts: ")
                    .append(String.join(", ", requirements.requiredVisibleFacts()))
                    .append('\n')
                    .append("Preserve these facts as visible site content; do not invent replacements.\n");
        }
        if (!requirements.forbiddenArtifacts().isEmpty()) {
            frame.append("forbiddenArtifacts: ")
                    .append(String.join(", ", requirements.forbiddenArtifacts().stream().sorted().toList()))
                    .append('\n')
                    .append("Do not create, edit, or rely on these forbidden local artifacts.\n");
        }
    }

    private static void appendStaticWebRewriteGroundingGuidance(
            StringBuilder frame,
            TaskContract contract,
            boolean mutationAllowed,
            List<String> visibleTools
    ) {
        if (!mutationAllowed || contract == null || !contract.verificationRequired()) return;
        if (contract.type() != TaskType.FILE_EDIT && contract.type() != TaskType.FILE_CREATE) return;
        if (visibleTools == null
                || !visibleTools.contains("talos.read_file")
                || !visibleTools.contains("talos.write_file")) {
            return;
        }
        List<String> targets = contract.expectedTargets().stream()
                .filter(CurrentTurnCapabilityFrame::isSmallStaticWebFile)
                .sorted()
                .toList();
        if (targets.isEmpty()) return;
        if (!looksLikeStaticWebRewriteContext(contract, targets)) return;

        frame.append("[StaticWebRewriteGrounding]\n")
                .append("Before any talos.write_file full-file rewrite of an existing required static-web target, ")
                .append("read the exact existing target first in this turn.\n")
                .append("Read first when rewriting: ")
                .append(String.join(", ", targets))
                .append('\n')
                .append("Do not call talos.write_file for an existing required static-web target until its ")
                .append("current bytes were read in this turn. After readback, write the complete corrected ")
                .append("file content for that exact path.\n");
    }

    private static boolean isSmallStaticWebFile(String target) {
        if (target == null || target.isBlank()) return false;
        String lower = target.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".html")
                || lower.endsWith(".htm")
                || lower.endsWith(".css")
                || lower.endsWith(".js")
                || lower.endsWith(".jsx")
                || lower.endsWith(".ts")
                || lower.endsWith(".tsx");
    }

    private static boolean looksLikeStaticWebRewriteContext(TaskContract contract, List<String> targets) {
        String reason = contract.classificationReason() == null
                ? ""
                : contract.classificationReason().toLowerCase(java.util.Locale.ROOT);
        String request = contract.originalUserRequest() == null
                ? ""
                : contract.originalUserRequest().toLowerCase(java.util.Locale.ROOT);
        boolean activeStaticWebContext = reason.contains("static-web")
                || reason.contains("active-static-web-context")
                || request.contains("active task context")
                || request.contains("artifactgoal{kind=static_web");
        boolean rewriteLanguage = request.contains("make it better")
                || request.contains("look better")
                || request.contains("looks better")
                || request.contains("more modern")
                || request.contains("more polished")
                || request.contains("polished and complete")
                || request.contains("repair anything unverified")
                || request.contains("rewrite")
                || request.contains("redesign")
                || request.contains("tailwind")
                || request.contains("according to my intent")
                || request.contains("still bad");
        boolean fullStaticSurface = targets.stream().anyMatch(target -> target.endsWith(".html") || target.endsWith(".htm"))
                && targets.stream().anyMatch(target -> target.endsWith(".css"))
                && targets.stream().anyMatch(target -> target.endsWith(".js"));
        return activeStaticWebContext || rewriteLanguage || fullStaticSurface;
    }

    private static List<String> orderedSourceEvidenceTargets(TaskContract contract) {
        Set<String> expected = contract.sourceEvidenceTargets();
        String request = contract.originalUserRequest() == null
                ? ""
                : contract.originalUserRequest().toLowerCase(java.util.Locale.ROOT);
        return expected.stream()
                .sorted(Comparator
                        .comparingInt((String target) -> targetIndex(request, target))
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    private static int targetIndex(String requestLower, String target) {
        if (requestLower == null || requestLower.isBlank() || target == null) {
            return Integer.MAX_VALUE;
        }
        int index = requestLower.indexOf(target.toLowerCase(java.util.Locale.ROOT));
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    private static void appendActiveTaskContext(
            StringBuilder frame,
            String activeTaskContext,
            String artifactGoal
    ) {
        boolean hasActiveTaskContext = isActiveContextForModel(activeTaskContext);
        boolean hasArtifactGoal = hasActiveTaskContext && isDerived(artifactGoal);
        if (!hasActiveTaskContext) {
            return;
        }
        frame.append("[ActiveTaskContext]\n")
                .append("activeTaskContext: ")
                .append(hasActiveTaskContext ? promptPreview(activeTaskContext) : CurrentTurnPlan.NONE_OR_NOT_DERIVED)
                .append('\n')
                .append("artifactGoal: ")
                .append(hasArtifactGoal ? promptPreview(artifactGoal) : CurrentTurnPlan.NONE_OR_NOT_DERIVED)
                .append('\n')
                .append("Active context is a current-turn hint only.\n")
                .append("Explicit current user instructions win over active context.\n")
                .append("Use active targets only for narrow deictic follow-ups.\n")
                .append("Do not broaden to unrelated workspace files because context is present.\n");
    }

    private static void appendProposalApplyGuidance(
            StringBuilder frame,
            String activeTaskContext,
            String artifactGoal,
            boolean mutationAllowed
    ) {
        if (!mutationAllowed || !isActiveContextForModel(activeTaskContext)) {
            return;
        }
        String combined = ((activeTaskContext == null ? "" : activeTaskContext) + " "
                + (artifactGoal == null ? "" : artifactGoal)).toLowerCase(java.util.Locale.ROOT);
        if (!combined.contains("proposed_changes") || !combined.contains("apply_edit")) {
            return;
        }
        boolean markdownProposal = combined.contains("kind=readme")
                || combined.contains("kind=markdown")
                || combined.contains("readme")
                || combined.contains(".md");
        if (!markdownProposal) {
            return;
        }
        frame.append("""

                [ProposalApply]
                Apply the active proposed change to the active target path(s), not an unrelated history guess.
                Read the target file first in this turn before editing or writing.
                For small Markdown/prose files, prefer talos.write_file with complete updated content after readback when an exact talos.edit_file old_string is uncertain.
                Do not retry invalid talos.edit_file old_string guesses.
                """);
    }

    private static boolean isDerived(String value) {
        return value != null
                && !value.isBlank()
                && !CurrentTurnPlan.NONE_OR_NOT_DERIVED.equals(value);
    }

    private static void appendTaskExpectations(
            StringBuilder frame,
            List<TaskExpectation> taskExpectations
    ) {
        if (taskExpectations == null || taskExpectations.isEmpty()) {
            return;
        }
        for (TaskExpectation expectation : taskExpectations) {
            if (expectation instanceof LiteralContentExpectation literal) {
                appendLiteralContentExpectation(frame, literal);
            }
        }
    }

    private static void appendLiteralContentExpectation(
            StringBuilder frame,
            LiteralContentExpectation literal
    ) {
        String delimiter = "TALOS_CURRENT_TURN_EXACT_CONTENT_"
                + literal.expectedHash().substring(0, 12);
        String expectedContent = literal.expectedContent();
        frame.append("[ExactFileWrite]\n")
                .append("target: ").append(literal.targetPath()).append('\n')
                .append("sourcePattern: ").append(literal.sourcePattern()).append('\n')
                .append("matchMode: ").append(literal.matchMode().name()).append('\n')
                .append("expectedBytes: ").append(literal.expectedBytes()).append('\n')
                .append("expectedChars: ").append(literal.expectedChars()).append('\n')
                .append("expectedLines: ").append(literal.expectedLines()).append('\n')
                .append("Use this exact current-turn content for the complete file write to ")
                .append(literal.targetPath()).append(".\n")
                .append("The complete file content for ").append(literal.targetPath())
                .append(" must equal the expectedContent payload exactly.\n")
                .append("Do not wrap it in HTML, Markdown, code fences, prose, or inferred surrounding content.\n")
                .append("For talos.write_file, the content argument must be exactly the payload below.\n")
                .append("Do not reuse exact-write literals from earlier turns or unrelated history.\n");
        if (expectedContent.length() <= MAX_INLINE_EXACT_CONTENT_CHARS) {
            frame.append("expectedContent:\n")
                    .append("<<<").append(delimiter).append('\n')
                    .append(expectedContent);
            if (!expectedContent.endsWith("\n")) {
                frame.append('\n');
            }
            frame.append(delimiter).append('\n');
        } else {
            frame.append("expectedContentPreview: ")
                    .append(PromptAuditRedactor.preview(expectedContent))
                    .append('\n')
                    .append("The complete exact payload is in the current user request; use that current-turn payload, ")
                    .append("not history.\n");
        }
    }

    private static boolean isActiveContextForModel(String value) {
        if (!isDerived(value)) return false;
        String trimmed = value.strip();
        return trimmed.startsWith("ACTIVE") || trimmed.contains("state=ACTIVE");
    }

    private static String promptPreview(String value) {
        return PromptAuditRedactor.preview(value, ActiveTaskContext.PROMPT_RENDER_CHAR_CAP);
    }

    private static String evidenceGuidance(EvidenceObligation evidence) {
        return switch (evidence) {
            case DOCUMENT_COMPARISON_REQUIRED -> "Evidence: the two requested snapshots must be compared with talos.compare_documents. A model summary is not comparison evidence.";
            case READ_TARGET_REQUIRED -> "Evidence: read the named target before answering.";
            case PATH_EXISTENCE_EVIDENCE_REQUIRED ->
                    "Evidence: verify path existence with talos.list_dir for the parent directory "
                            + "or talos.read_file for each named target before answering.";
            case PROTECTED_READ_APPROVAL_REQUIRED ->
                    "Evidence: the named target is protected. "
                            + "Call talos.read_file for the protected target; runtime will request approval. "
                            + "Do not answer from protected content unless the read succeeds.";
            case FILE_CONTENT_LOOKUP_REQUIRED ->
                    "Evidence: locate the requested workspace fact with read, grep, or retrieval results "
                            + "that identify at least one source file before answering.";
            case PROJECT_SUMMARY_EVIDENCE_REQUIRED ->
                    "Evidence: before making project-level claims, inspect the preferred top-level "
                            + "README-family file when one is present. Do not infer the whole project "
                            + "from one unrelated file.";
            case LIST_DIRECTORY_ONLY ->
                    "Evidence: list directory entries only; do not inspect file contents.";
            case WORKSPACE_INSPECTION_REQUIRED ->
                    "Evidence: inspect the workspace with read-only tools before answering.";
            case STATIC_WEB_DIAGNOSIS_REQUIRED ->
                    "Evidence: inspect static web source files before diagnosing the page. "
                            + "If index.html is present, read it before answering.";
            case VERIFY_FROM_TRACE_OR_EVIDENCE ->
                    "Evidence: answer from prior trace/status evidence or fresh read-only verification.";
            case UNSUPPORTED_CAPABILITY_CHECK_REQUIRED ->
                    "Evidence: check and report unsupported document capability before relying on file contents.";
            case NONE -> "Evidence: no additional evidence obligation is derived.";
        };
    }
}
