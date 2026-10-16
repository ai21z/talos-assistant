package dev.talos.runtime.policy;

import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.WorkStatusEvidence;
import dev.talos.runtime.intent.CommandExecutionRequest;
import dev.talos.runtime.intent.WorkStatusRequest;
import dev.talos.runtime.task.TaskContract;

import java.util.List;
import java.util.Optional;

/** Retained status is assessed before generation, without granting fresh authority. */
public final class WorkStatusAnswerPolicy {
    public static final int MAX_OPERATION_DETAIL_ROWS = 12;
    public static final int MAX_OPERATION_DETAIL_CHARS = 4096;
    private WorkStatusAnswerPolicy() {}

    public static Optional<WorkStatusRequest> requestForMessages(List<dev.talos.spi.types.ChatMessage> messages) {
        return requestFor(dev.talos.runtime.task.TaskContractResolver.fromMessages(messages));
    }

    public static Optional<WorkStatusRequest> requestFor(TaskContract contract) {
        if (contract != null && !contract.mutationRequested() && !contract.mutationAllowed()
                && !contract.verificationRequired() && contract.expectedTargets().isEmpty()
                && contract.sourceEvidenceTargets().isEmpty() && contract.forbiddenTargets().isEmpty()
                && contract.type() == dev.talos.runtime.task.TaskType.READ_ONLY_QA) {
            var attribution = dev.talos.runtime.intent.HistoricalAttributionRequest.parse(contract.originalUserRequest());
            if (attribution.isPresent()) return Optional.of(attribution.orElseThrow().asWorkStatusRequest());
        }
        if (!permitsRetainedOnly(contract)) return Optional.empty();
        var request = WorkStatusRequest.parse(contract.originalUserRequest());
        return request.selected() ? Optional.of(request) : Optional.empty();
    }

    public static boolean permitsRetainedOnly(TaskContract contract) {
        return contract != null && !contract.mutationRequested() && !contract.mutationAllowed()
                && contract.originalUserRequest().length() <= 4096
                && !WorkStatusRequest.hasFreshAction(contract.originalUserRequest())
                && dev.talos.runtime.MutationIntent.classifyShape(contract.originalUserRequest()).decision()
                    == dev.talos.runtime.MutationIntent.Decision.READ_ONLY
                && contract.expectedTargets().isEmpty() && contract.sourceEvidenceTargets().isEmpty()
                && CommandExecutionRequest.parse(contract.originalUserRequest()).commandClauses().isEmpty()
                && !dev.talos.runtime.task.HistoricalFollowUpRequest.isVerificationRequest(contract.originalUserRequest());
    }

    /** Captured operational results are rendered without historical prose generation or fresh access. */
    public static String renderCaptured(dev.talos.runtime.intent.RequestedResultInventory inventory,
            SessionMemory memory, java.nio.file.Path workspace) {
        var frame = dev.talos.runtime.observation.TurnRequestFrame.current();
        if (!dev.talos.runtime.observation.TurnRequestFrame.admits(inventory) || !inventory.retainedWork()
                || frame == null || workspace == null || !frame.workspace().equals(workspace.toAbsolutePath().normalize()))
            throw new SecurityException("The retained-work projection is not current");
        String unresolved = inventory.unresolvedRegions().isEmpty() ? "" : "Additional requested meaning remains unresolved.\n";
        return renderRetained(inventory.results(), unresolved, memory, workspace,
                frame.meaning().publication(), frame.meaning().fields());
    }

    /**
     * A partially parsed request with an explicit handoff frame is answered from retained runtime records only.
     * The frame itself asks for saved effects, verification and open checks, so no unsupported wording is
     * interpreted. Target names and counts are withheld when an unsupported clause carries a restriction cue.
     */
    public static String renderPartialHandoff(WorkStatusRequest request, SessionMemory memory,
            java.nio.file.Path workspace, List<String> uninterpretedLiterals) {
        if (request == null || workspace == null || !request.handoffFrame()
                || request.coverage() != WorkStatusRequest.Coverage.PARTIAL)
            throw new IllegalArgumentException("A partially parsed handoff frame is required");
        boolean restricted = request.unsupportedText().stream()
                .anyMatch(dev.talos.runtime.intent.OutputRestrictionCue::present);
        var frame = request.components().stream().filter(component -> component.roles().contains(
                WorkStatusRequest.Role.VERIFICATION)).findFirst().orElse(request.components().getFirst());
        var region = new dev.talos.runtime.intent.RequestedResultInventory.SourceRegion(frame.start(), frame.end());
        var results = java.util.stream.Stream.of(
                dev.talos.runtime.intent.RequestedResultInventory.Kind.RETAINED_SAVES,
                dev.talos.runtime.intent.RequestedResultInventory.Kind.RETAINED_VERIFICATION,
                dev.talos.runtime.intent.RequestedResultInventory.Kind.RETAINED_UNCHECKED)
                .map(kind -> new dev.talos.runtime.intent.RequestedResultInventory.RequestedResult(
                        "handoff-" + kind.name().toLowerCase(java.util.Locale.ROOT), region, kind, "active workspace",
                        "retained conversation",
                        dev.talos.runtime.intent.RequestedResultInventory.EvidenceBasis.RETAINED_RUNTIME_RECORDS))
                .toList();
        // Literals arrive already projected and redaction-checked by the caller, so an unanswered question stays visible.
        var literals = uninterpretedLiterals == null ? List.<String>of() : uninterpretedLiterals;
        String unresolved = (literals.isEmpty() ? "Request coverage remains incomplete.\n"
                    : "Request coverage remains incomplete. Not interpreted, so project meaning remains unresolved: "
                        + String.join(", ", literals) + "\n")
                + (request.roles().contains(WorkStatusRequest.Role.PROJECT_HANDOFF)
                    ? "Earlier project discussion was not summarized for this request.\n" : "");
        return renderRetained(results, unresolved, memory, workspace,
                restricted ? dev.talos.runtime.observation.TurnRequestFrame.Publication.NO_NAMES
                        : dev.talos.runtime.observation.TurnRequestFrame.Publication.ALL_NAMES,
                restricted ? new dev.talos.spi.WorkspaceObservationBoundary.Fields(false, true, true, true)
                        : dev.talos.spi.WorkspaceObservationBoundary.Fields.all());
    }

    private static String renderRetained(List<dev.talos.runtime.intent.RequestedResultInventory.RequestedResult> requestedResults,
            String unresolved, SessionMemory memory, java.nio.file.Path workspace,
            dev.talos.runtime.observation.TurnRequestFrame.Publication publication,
            dev.talos.spi.WorkspaceObservationBoundary.Fields fields) {
        var evidence = memory == null ? new WorkStatusEvidence(List.of(), 0, false) : memory.workStatusEvidence();
        String scope = dev.talos.runtime.JsonSessionStore.sessionIdFor(workspace);
        var turns = evidence.turns().stream().filter(turn -> scope.equals(turn.workspaceHash())).toList();
        var answer = new StringBuilder("Retained handoff, historical observations only.\n");
        boolean omitted = evidence.evictedTurns() > 0 || turns.size() != evidence.turns().size()
                || turns.size() > MAX_OPERATION_DETAIL_ROWS || turns.stream().anyMatch(turn -> turn.omittedCalls() > 0);
        String tail = "No fresh inspection, tools or verification were performed.";
        String omission = "Retained or presented records are incomplete, absence of earlier work is not established.\n";
        int contentLimit = MAX_OPERATION_DETAIL_CHARS - tail.length() - omission.length() - unresolved.length();
        for (var requested : requestedResults) {
            String part;
            if (requested.disposition() != dev.talos.runtime.intent.RequestedResultInventory.Disposition.ACTIVE) {
                part = switch (requested.disposition()) {
                    case CANCELLED -> "A handoff result was cancelled.";
                    case REPLACED -> "A handoff result was replaced.";
                    case WITHHELD -> "A handoff result was withheld.";
                    default -> "A requested result remains unresolved.";
                };
            } else if (!fields.existencePublic() || !fields.hiddenFactsPublic()) {
                part = "Retained-work details were withheld by the current request.";
            } else {
                part = switch (requested.kind()) {
                    case RETAINED_SAVES -> capturedEffects(turns, publication, fields);
                    case RETAINED_VERIFICATION -> "Recorded verification: " + (turns.isEmpty() ? "unavailable"
                            : turns.stream().sorted(java.util.Comparator.comparingInt(WorkStatusAnswerPolicy::verificationPriority)
                                    .thenComparingInt(WorkStatusEvidence.Turn::turnNumber)).limit(MAX_OPERATION_DETAIL_ROWS)
                                .map(turn -> "Turn " + turn.turnNumber() + " " + turn.verification())
                                .collect(java.util.stream.Collectors.joining(", ")))
                            + ". These labels are turn-level, not per-file proof.";
                    case RETAINED_UNCHECKED -> "Current contents, file-specific correctness, complete tests and release acceptance "
                            + "are not established by retained status records. Missing records prevent an exhaustive unchecked-work inventory.";
                    default -> "A requested result remains unresolved.";
                };
            }
            if (answer.length() + part.length() + 1 > contentLimit) omitted = true;
            else answer.append(part).append('\n');
        }
        answer.append(unresolved);
        if (omitted) answer.append(omission);
        answer.append(tail);
        return answer.toString();
    }

    private static int verificationPriority(WorkStatusEvidence.Turn turn) {
        return switch (turn.verification()) {
            case FAILED, UNKNOWN, UNAVAILABLE -> 0;
            default -> 1;
        };
    }

    private static String capturedEffects(List<WorkStatusEvidence.Turn> turns,
            dev.talos.runtime.observation.TurnRequestFrame.Publication publication,
            dev.talos.spi.WorkspaceObservationBoundary.Fields fields) {
        var answer = new StringBuilder("Saved effects:\n");
        int rows = 0;
        boolean omitted = false;
        var effects = turns.stream().flatMap(turn -> turn.calls().stream()
                .filter(call -> call.category() == WorkStatusEvidence.Category.MUTATION)
                .map(call -> java.util.Map.entry(turn, call))).sorted(java.util.Comparator.comparingInt(entry ->
                    entry.getValue().failureReason() != dev.talos.tools.ToolFailureReason.NONE
                        || !entry.getValue().reportedSuccess() || entry.getValue().uncertainEntries() > 0 ? 0 : 1)).toList();
        for (var effect : effects) {
                var turn = effect.getKey();
                var call = effect.getValue();
                String target = publication == dev.talos.runtime.observation.TurnRequestFrame.Publication.ALL_NAMES
                        && !call.targetMetadataWithheld() ? literalTargets(call.appliedTargets()) : "withheld targets";
                String row = "Turn " + turn.turnNumber() + ": ";
                if (call.failureReason() == dev.talos.tools.ToolFailureReason.USER_APPROVAL_DENIED)
                    row += "approval was denied";
                else if (call.disposition() == dev.talos.runtime.TurnRecord.ToolCallDisposition.BLOCKED)
                    row += "the operation was blocked, " + call.failureReason();
                else row += call.canonicalToolName() + ", " + (fields.countPublic() ? call.appliedEntries() + " " : "")
                        + "applied effect entries for " + target;
                if (call.uncertainEntries() > 0) row += ", effects remain uncertain";
                if (!call.reportedSuccess() && call.appliedEntries() > 0) row += ", the operation reported failure despite applied effects";
                row += ".\n";
                if (rows >= MAX_OPERATION_DETAIL_ROWS || answer.length() + row.length() > MAX_OPERATION_DETAIL_CHARS / 2) {
                    omitted = true;
                    continue;
                }
                answer.append(row);
                rows++;
        }
        if (rows == 0) answer.append("No applicable saved-effect detail is available, earlier absence is not established.\n");
        if (omitted) answer.append("Additional retained effects were omitted by the presentation bound.\n");
        answer.append("Applied effects do not establish exact saved contents or every requested change.");
        return answer.toString();
    }

    public static String render(WorkStatusRequest request, SessionMemory memory) {
        return render(request, memory, null);
    }

    /** Optional ephemeral local presentation. Ordinary render remains strictly metadata-only. */
    public static Optional<String> renderLocalEditDetails(WorkStatusRequest request, SessionMemory memory,
            java.nio.file.Path workspace, dev.talos.safety.ContentRestrictions currentRestrictions) {
        if (request == null || !request.runtimeOnly() || !request.requiresOperationDetails()
                || !request.roles().contains(WorkStatusRequest.Role.ACTIONS)
                || request.coverage() != WorkStatusRequest.Coverage.COVERED || memory == null || workspace == null
                || !dev.talos.runtime.TurnAuditCapture.isActive() || !detailPermissions(currentRestrictions)
                || !detailPermissions(dev.talos.safety.ContentRestrictionScope.current())
                || requestFor(dev.talos.runtime.task.TaskContractResolver.fromUserRequest(request.originalRequest())).isEmpty())
            return Optional.empty();
        try {
            var evidence = memory.workStatusEvidence();
            String hash = dev.talos.runtime.JsonSessionStore.sessionIdFor(workspace);
            var admitted = new java.util.IdentityHashMap<WorkStatusEvidence.Call, Boolean>();
            for (var turn : evidence.turns()) {
                if (!hash.equals(turn.workspaceHash())) continue;
                for (var call : turn.calls()) {
                    var detail = call.executedEditDetail();
                    if (call.detailRetention() != WorkStatusEvidence.DetailRetention.RETAINED || !detail.available()
                            || call.disposition() != dev.talos.runtime.TurnRecord.ToolCallDisposition.EXECUTED
                            || !"talos.edit_file".equals(call.canonicalToolName()) || call.appliedEntries() != 1
                            || call.uncertainEntries() != 0 || !call.declaredTarget().equals(detail.target())
                            || !call.appliedTargets().equals(List.of(detail.target()))) continue;
                    var target = ProtectedPathPolicy.classify(workspace, detail.target());
                    if (!target.hasPath() || !target.insideWorkspace() || target.workspaceEscape() || target.protectedPath()
                            || !target.relativePath().equals(detail.target())) continue;
                    admitted.put(call, Boolean.TRUE);
                }
            }
            if (admitted.isEmpty()) return Optional.empty();
            // This restriction survives settlement. It must precede every raw response construction.
            dev.talos.safety.ContentRestrictionScope.restrict(new dev.talos.safety.ContentRestrictions(false, false, false, false));
            var answer = new StringBuilder("Retained execution handoff. Historical events, not current file contents.\n");
            int rows = 0, omittedRows = 0, missingDetails = 0, uncertain = 0, omittedCalls = 0;
            int withheldTargets = 0, omittedTargets = 0, withheldDetails = 0, omittedDetails = 0, inspections = 0;
            for (var turn : evidence.turns()) {
                omittedCalls += turn.omittedCalls();
                for (var call : turn.calls()) {
                    uncertain += call.uncertainEntries();
                    withheldTargets += call.withheldTargets();
                    omittedTargets += call.omittedTargets();
                    if (call.detailRetention() == WorkStatusEvidence.DetailRetention.WITHHELD) withheldDetails++;
                    if (call.detailRetention() == WorkStatusEvidence.DetailRetention.OMITTED) omittedDetails++;
                    if (call.appliedEntries() > 0 && !admitted.containsKey(call)) missingDetails++;
                    if (call.category() == WorkStatusEvidence.Category.INSPECTION && call.reportedSuccess()) {
                        inspections++;
                        continue;
                    }
                    String row = "Turn " + turn.turnNumber() + ": ";
                    if (admitted.containsKey(call)) {
                        var detail = call.executedEditDetail();
                        row += targetLiteral(detail.target()) + " exact replacement "
                                + ProjectHandoffPolicy.literal(detail.before()) + " -> "
                                + ProjectHandoffPolicy.literal(detail.after())
                                + ", " + readBackLabel(detail.readBack()) + ", " + formatLabel(detail.verification());
                        if (!call.reportedSuccess()) row += ", operation reported failure despite the applied replacement";
                    } else {
                        row += eventLabel(call);
                        if (hash.equals(turn.workspaceHash()) && !call.declaredTarget().isEmpty())
                            row += " for " + targetLiteral(call.declaredTarget());
                        row += ", " + call.appliedEntries() + " applied and " + call.uncertainEntries() + " uncertain effect entries";
                    }
                    row += ".\n";
                    if (rows >= MAX_OPERATION_DETAIL_ROWS || answer.length() + row.length() > MAX_OPERATION_DETAIL_CHARS - 1400) {
                        omittedRows++;
                    } else {
                        answer.append(row);
                        rows++;
                    }
                }
            }
            if (inspections > 0) answer.append(inspections).append(" successful inspection(s) retained, not repeated.\n");
            if (request.roles().contains(WorkStatusRequest.Role.VERIFICATION)
                    || request.roles().contains(WorkStatusRequest.Role.TESTING)
                    || request.roles().contains(WorkStatusRequest.Role.UNCERTAINTY)) {
                var recordedChecks = new java.util.ArrayList<String>();
                for (var status : WorkStatusEvidence.Verification.values()) {
                    long count = evidence.turns().stream().filter(turn -> turn.verification() == status).count();
                    if (count > 0) recordedChecks.add(count + " " + status.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
                }
                answer.append("Historical turn verification labels: ").append(String.join(", ", recordedChecks))
                        .append(". These do not establish tests or release acceptance.\n");
            }
            answer.append("Gaps: ").append(missingDetails).append(" applied call(s) without admitted literal details, ")
                    .append(uncertain).append(" uncertain effect entries, ").append(omittedRows)
                    .append(" output rows omitted, ").append(withheldDetails).append(" details withheld, ")
                    .append(omittedDetails).append(" details omitted, ").append(withheldTargets).append(" targets withheld, ")
                    .append(omittedTargets).append(" targets omitted, ").append(omittedCalls)
                    .append(" calls omitted, ").append(evidence.evictedTurns()).append(" turns evicted.\n")
                    .append("Missing, restricted, other-workspace and legacy details are unavailable, not evidence of no change. ")
                    .append("Applied does not mean independently verified. Execution-time read-back is not a fresh check. ")
                    .append("Operation details remain unresolved beyond these literal replacements. ")
                    .append("No fresh tools, file inspection or tests were run for this handoff. Current state, browser behavior, testing and release readiness are not established.");
            return answer.length() <= MAX_OPERATION_DETAIL_CHARS ? Optional.of(answer.toString()) : Optional.empty();
        } catch (RuntimeException unavailable) {
            // Optional presentation failure must not rewrite execution facts or relax the restriction.
            return Optional.empty();
        }
    }

    private static boolean detailPermissions(dev.talos.safety.ContentRestrictions restrictions) {
        return restrictions != null && restrictions.modelAllowed() && restrictions.historyAllowed() && restrictions.artifactAllowed();
    }

    private static String eventLabel(WorkStatusEvidence.Call call) {
        return switch (call.failureReason()) {
            case USER_APPROVAL_DENIED -> "user approval denied";
            case PERMISSION_POLICY_DENIED -> "permission policy denied execution";
            case TOOL_EXECUTION_AUTHORITY_INVALID -> "tool execution authority rejected";
            default -> switch (call.disposition()) {
                case BLOCKED -> "execution blocked, specific cause unavailable";
                case UNKNOWN -> "execution disposition unknown";
                case EXECUTED -> call.reportedSuccess() ? "operation reported success" : "operation reported failure";
            };
        };
    }

    private static String readBackLabel(dev.talos.tools.impl.ExecutedEditDetail.ReadBack value) {
        return switch (value) {
            case MATCHED_AT_EXECUTION -> "read-back matched at execution";
            case MISMATCH_AT_EXECUTION -> "read-back mismatched at execution";
            case UNAVAILABLE_AT_EXECUTION -> "read-back unavailable at execution";
            case NOT_RECORDED -> "execution-time read-back not recorded";
        };
    }

    private static String formatLabel(dev.talos.tools.VerificationStatus value) {
        return switch (value) {
            case PASS -> "format check passed at execution";
            case WARN -> "format check warned at execution";
            case FAIL -> "format check failed at execution";
            case INTEGRITY_FAIL -> "content integrity check failed at execution";
            case UNKNOWN -> "format verification unavailable";
        };
    }

    public static String render(WorkStatusRequest request, SessionMemory memory, java.nio.file.Path workspace) {
        var attribution = dev.talos.runtime.intent.HistoricalAttributionRequest.parse(request.originalRequest());
        if (attribution.isPresent()) {
            return HistoricalAttributionAnswerPolicy.render(attribution.orElseThrow(), memory, workspace);
        }
        WorkStatusEvidence evidence = memory == null
                ? new WorkStatusEvidence(List.of(), 0, false) : memory.workStatusEvidence();
        if (request.coverage() == WorkStatusRequest.Coverage.PARTIAL && ProjectHandoffPolicy.required(request))
            return renderPartialHandoff(evidence, workspace);
        if (request.runtimeOnly()) return renderRuntimeOnly(request, evidence, workspace);
        StringBuilder answer = new StringBuilder("Retained work status. This is not a complete session audit.\n");
        var calls = evidence.turns().stream().flatMap(turn -> turn.calls().stream()).toList();
        if (request.roles().contains(WorkStatusRequest.Role.ACTIONS)) {
            for (var category : WorkStatusEvidence.Category.values()) {
                var selected = calls.stream().filter(call -> call.category() == category).toList();
                long executed = selected.stream().filter(call -> call.disposition()
                        == dev.talos.runtime.TurnRecord.ToolCallDisposition.EXECUTED).count();
                long success = selected.stream().filter(call -> call.disposition()
                        == dev.talos.runtime.TurnRecord.ToolCallDisposition.EXECUTED && call.reportedSuccess()).count();
                long blocked = selected.stream().filter(call -> call.disposition()
                        == dev.talos.runtime.TurnRecord.ToolCallDisposition.BLOCKED).count();
                answer.append(switch (category) {
                    case INSPECTION -> "Inspection";
                    case MUTATION -> "Mutation";
                    case COMMAND -> "Command";
                    case OTHER -> "Other";
                }).append(" calls: ").append(executed).append(" reached execution, ")
                        .append(success).append(" reported success, ").append(blocked).append(" blocked, ")
                        .append(selected.size() - executed - blocked).append(" execution unknown.\n");
            }
            answer.append("Recorded file effect entries: ")
                    .append(calls.stream().mapToInt(WorkStatusEvidence.Call::appliedEntries).sum())
                    .append(" applied, ")
                    .append(calls.stream().mapToInt(WorkStatusEvidence.Call::uncertainEntries).sum())
                    .append(" uncertain. Applied does not mean independently verified.\n");
            answer.append("Recorded denial and rejection causes, by retained turn:\n");
            for (var turn : evidence.turns()) {
                if (turn.calls().stream().anyMatch(call -> call.category() == WorkStatusEvidence.Category.MUTATION
                        || call.appliedEntries() > 0 || call.uncertainEntries() > 0)) {
                    answer.append("Turn ").append(turn.turnNumber()).append(" file effect entries: ")
                            .append(turn.calls().stream().mapToInt(WorkStatusEvidence.Call::appliedEntries).sum())
                            .append(" applied, ")
                            .append(turn.calls().stream().mapToInt(WorkStatusEvidence.Call::uncertainEntries).sum())
                            .append(" uncertain.\n");
                }
                for (var cause : List.of(dev.talos.tools.ToolFailureReason.USER_APPROVAL_DENIED,
                        dev.talos.tools.ToolFailureReason.PERMISSION_POLICY_DENIED,
                        dev.talos.tools.ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID)) {
                    long count = turn.calls().stream().filter(call -> call.failureReason() == cause).count();
                    if (count == 0) continue;
                    answer.append("Turn ").append(turn.turnNumber()).append(": ").append(count).append(" ")
                            .append(switch (cause) {
                                case USER_APPROVAL_DENIED -> "user approval denial(s)";
                                case PERMISSION_POLICY_DENIED -> "permission policy denial(s)";
                                case TOOL_EXECUTION_AUTHORITY_INVALID -> "tool execution authority rejection(s)";
                                default -> throw new IllegalStateException("Unexpected retained denial cause");
                            }).append(".\n");
                }
                long unknown = turn.calls().stream().filter(call -> !call.reportedSuccess()
                        && call.failureReason() == dev.talos.tools.ToolFailureReason.NONE).count();
                if (unknown > 0) answer.append("Turn ").append(turn.turnNumber()).append(": ")
                        .append(unknown).append(" unsuccessful call(s) without a recorded typed cause.\n");
            }
            answer.append("Earlier causes are retained independently of the current turn's permissions.\n");
            appendOperationTargets(answer, evidence, workspace);
        }
        if (request.roles().contains(WorkStatusRequest.Role.VERIFICATION)) {
            answer.append("Recorded verification, scoped to retained turns.\n");
            for (var status : WorkStatusEvidence.Verification.values()) {
                long count = evidence.turns().stream().filter(turn -> turn.verification() == status).count();
                answer.append(status.name()).append(": ").append(count).append(" retained entries.\n");
            }
            answer.append("A recorded PASS is not proof of current source, testing or candidate acceptance.\n");
        }
        if (request.roles().contains(WorkStatusRequest.Role.UNCERTAINTY)) {
            answer.append("Retained uncertainty: ")
                    .append(evidence.turns().stream().filter(turn -> turn.verification()
                            == WorkStatusEvidence.Verification.FAILED).count())
                    .append(" failed verification entries, ")
                    .append(evidence.turns().stream().filter(turn -> turn.verification()
                            == WorkStatusEvidence.Verification.UNAVAILABLE
                            || turn.verification() == WorkStatusEvidence.Verification.UNKNOWN).count())
                    .append(" unavailable or unknown verification entries, ")
                    .append(calls.stream().mapToInt(WorkStatusEvidence.Call::uncertainEntries).sum())
                    .append(" uncertain file effect entries, ")
                    .append(calls.stream().filter(call -> !call.reportedSuccess())
                            .mapToInt(WorkStatusEvidence.Call::appliedEntries).sum())
                    .append(" applied entries from unsuccessful calls, not independently verified.\n")
                    .append("Uncertainty: current source agreement, missing records and candidate binding remain unestablished. ")
                    .append("Recorded failures and uncertain effects are not erased by later success.\n");
        }
        if (request.roles().contains(WorkStatusRequest.Role.TESTING))
            answer.append("Testing: not established. Tool success and verifier status do not establish suite completion, failures, errors or skips.\n");
        if (request.roles().contains(WorkStatusRequest.Role.RELEASE))
            answer.append("Release acceptance: not established. No candidate-bound release acceptance is provided by these records.\n");
        if (request.roles().contains(WorkStatusRequest.Role.NEXT_STEPS))
            answer.append("Proposed next steps: review the missing evidence, then separately authorize the required validation. These are proposals, not completed work.\n");
        if (request.roles().contains(WorkStatusRequest.Role.PROPOSALS))
            answer.append("Proposed work: not reconstructed from assistant prose. Performed work is limited to the retained runtime metadata above.\n");
        if (request.coverage() != WorkStatusRequest.Coverage.COVERED)
            answer.append("Request coverage: incomplete. Some requested meaning or scope is unsupported by this bounded status contract.\n");
        if (request.requiresOperationDetails()) operationGap(answer);
        if (evidence.turns().isEmpty()) answer.append("Runtime status records are unavailable in the retained window.\n");
        answer.append("Earlier status records evicted: ").append(evidence.evictedTurns()).append(".\n")
                .append("Call entries omitted by the per-turn bound: ")
                .append(evidence.turns().stream().mapToInt(WorkStatusEvidence.Turn::omittedCalls).sum()).append(".\n")
                .append("Counts are retained entries, not unique lifetime operations. A zero count does not prove no earlier action occurred. ")
                .append("Execution means a tool invocation was reached, not that a subprocess launched or files changed. ")
                .append("Restored or custom memory and assistant prose do not restore status proof. ")
                .append("No fresh workspace-content inspection or tool execution was performed for this answer.");
        return answer.toString();
    }

    /** Existing retained metadata is summarized without admitting a partially understood advisory request. */
    private static String renderPartialHandoff(WorkStatusEvidence evidence, java.nio.file.Path workspace) {
        var answer = new StringBuilder("Retained work summary, not current file contents or a complete session audit.\n");
        appendOperationTargets(answer, evidence, workspace);
        var calls = evidence.turns().stream().flatMap(turn -> turn.calls().stream()).toList();
        if (calls.isEmpty()) answer.append("No tool calls are available in the retained status window. Earlier absence is not established.\n");
        long unsuccessful = calls.stream().filter(call -> !call.reportedSuccess()).count();
        long unknown = calls.stream().filter(call -> call.disposition()
                == dev.talos.runtime.TurnRecord.ToolCallDisposition.UNKNOWN).count();
        int uncertain = calls.stream().mapToInt(WorkStatusEvidence.Call::uncertainEntries).sum();
        int applied = calls.stream().mapToInt(WorkStatusEvidence.Call::appliedEntries).sum();
        int appliedDespiteFailure = calls.stream().filter(call -> !call.reportedSuccess())
                .mapToInt(WorkStatusEvidence.Call::appliedEntries).sum();
        if (applied + uncertain > 0)
            answer.append("Recorded file effect entries across retained turns: ").append(applied)
                    .append(" applied, ").append(uncertain).append(" uncertain.\n");
        if (unsuccessful + unknown + uncertain + appliedDespiteFailure > 0) {
            answer.append("Across retained turns, ").append(unsuccessful).append(" unsuccessful calls, ")
                    .append(unknown).append(" unknown execution dispositions, ").append(uncertain)
                    .append(" uncertain effect entries, ").append(appliedDespiteFailure)
                    .append(" applied entries from unsuccessful calls. Later success does not erase these.\n");
            for (var cause : dev.talos.tools.ToolFailureReason.values()) {
                if (cause == dev.talos.tools.ToolFailureReason.NONE) continue;
                long count = calls.stream().filter(call -> call.failureReason() == cause).count();
                if (count > 0) answer.append(cause).append(": ").append(count).append(" retained calls.\n");
            }
        }
        answer.append("Historical turn-level verification, not per-file certification:\n");
        if (evidence.turns().isEmpty()) answer.append("No retained verification records are available.\n");
        for (var status : WorkStatusEvidence.Verification.values()) {
            var turns = evidence.turns().stream().filter(turn -> turn.verification() == status).toList();
            if (turns.isEmpty()) continue;
            String labels = turns.stream().limit(MAX_OPERATION_DETAIL_ROWS)
                    .map(turn -> "Turn " + turn.turnNumber()).collect(java.util.stream.Collectors.joining(", "));
            answer.append(labels).append(": ").append(status);
            if (turns.size() > MAX_OPERATION_DETAIL_ROWS)
                answer.append(", ").append(turns.size() - MAX_OPERATION_DETAIL_ROWS).append(" additional turn labels omitted");
            answer.append(".\n");
        }
        int omitted = evidence.turns().stream().mapToInt(WorkStatusEvidence.Turn::omittedCalls).sum();
        if (omitted > 0 || evidence.evictedTurns() > 0)
            answer.append("Retention gaps: ").append(omitted).append(" calls omitted, ")
                    .append(evidence.evictedTurns()).append(" turns evicted.\n");
        answer.append("Labels span retained turns and do not certify current contents, individual files, tests or release readiness. ")
                .append("Applied effects do not establish every requested change. Exact saved contents and semantic correctness are not supplied by these records.\n")
                .append("No fresh inspection, tools or verification were performed for this handoff.\n")
                .append("Request coverage remains incomplete. Unresolved meaning and scope have not been inferred from these operations.");
        return answer.toString();
    }

    private static String renderRuntimeOnly(WorkStatusRequest request, WorkStatusEvidence evidence,
                                            java.nio.file.Path workspace) {
        var answer = new StringBuilder("Retained runtime status, not a complete session audit.\n");
        var calls = evidence.turns().stream().flatMap(turn -> turn.calls().stream()).toList();
        if (request.roles().contains(WorkStatusRequest.Role.ACTIONS)) {
            for (var category : WorkStatusEvidence.Category.values()) {
                var selected = calls.stream().filter(call -> call.category() == category).toList();
                if (selected.isEmpty()) continue;
                long executed = selected.stream().filter(call -> call.disposition()
                        == dev.talos.runtime.TurnRecord.ToolCallDisposition.EXECUTED).count();
                long success = selected.stream().filter(call -> call.disposition()
                        == dev.talos.runtime.TurnRecord.ToolCallDisposition.EXECUTED && call.reportedSuccess()).count();
                long blocked = selected.stream().filter(call -> call.disposition()
                        == dev.talos.runtime.TurnRecord.ToolCallDisposition.BLOCKED).count();
                answer.append(switch (category) {
                    case INSPECTION -> "Inspection";
                    case MUTATION -> "Mutation";
                    case COMMAND -> "Command";
                    case OTHER -> "Other";
                }).append(" calls: ").append(executed).append(" reached execution, ")
                        .append(success).append(" reported success, ").append(blocked).append(" blocked, ")
                        .append(selected.size() - executed - blocked).append(" execution unknown.\n");
            }
            boolean typedCause = false;
            for (var turn : evidence.turns()) {
                if (turn.calls().stream().anyMatch(call -> call.category() == WorkStatusEvidence.Category.MUTATION
                        || call.appliedEntries() > 0 || call.uncertainEntries() > 0))
                    answer.append("Turn ").append(turn.turnNumber()).append(" file effect entries: ")
                            .append(turn.calls().stream().mapToInt(WorkStatusEvidence.Call::appliedEntries).sum())
                            .append(" applied, ")
                            .append(turn.calls().stream().mapToInt(WorkStatusEvidence.Call::uncertainEntries).sum())
                            .append(" uncertain.\n");
                for (var cause : List.of(dev.talos.tools.ToolFailureReason.USER_APPROVAL_DENIED,
                        dev.talos.tools.ToolFailureReason.PERMISSION_POLICY_DENIED,
                        dev.talos.tools.ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID)) {
                    long count = turn.calls().stream().filter(call -> call.failureReason() == cause).count();
                    if (count == 0) continue;
                    typedCause = true;
                    answer.append("Turn ").append(turn.turnNumber()).append(": ").append(count).append(" ")
                            .append(switch (cause) {
                                case USER_APPROVAL_DENIED -> "user approval denial(s)";
                                case PERMISSION_POLICY_DENIED -> "permission policy denial(s)";
                                case TOOL_EXECUTION_AUTHORITY_INVALID -> "tool execution authority rejection(s)";
                                default -> throw new IllegalStateException("Unexpected retained denial cause");
                            }).append(".\n");
                }
                long unknown = turn.calls().stream().filter(call -> !call.reportedSuccess()
                        && call.failureReason() == dev.talos.tools.ToolFailureReason.NONE).count();
                if (unknown > 0) answer.append("Turn ").append(turn.turnNumber()).append(": ")
                        .append(unknown).append(" unsuccessful call(s) without a recorded typed cause.\n");
            }
            if (!typedCause) answer.append("No typed denial or rejection was recorded in the retained window.\n");
            answer.append("Applied does not mean independently verified. Earlier causes are not changed by current permissions.\n");
            appendOperationTargets(answer, evidence, workspace);
        }
        if (request.roles().contains(WorkStatusRequest.Role.VERIFICATION)
                || request.roles().contains(WorkStatusRequest.Role.UNCERTAINTY)) {
            for (var status : WorkStatusEvidence.Verification.values()) {
                long count = evidence.turns().stream().filter(turn -> turn.verification() == status).count();
                if (count > 0) answer.append(status.name()).append(": ").append(count).append(" retained entries.\n");
            }
            answer.append("Current source agreement and missing verification remain unestablished. ")
                    .append("Recorded PASS does not establish current testing or candidate acceptance.\n");
        }
        if (request.roles().contains(WorkStatusRequest.Role.UNCERTAINTY))
            answer.append("Uncertain file effect entries: ")
                    .append(calls.stream().mapToInt(WorkStatusEvidence.Call::uncertainEntries).sum())
                    .append(". Applied entries from unsuccessful calls: ")
                    .append(calls.stream().filter(call -> !call.reportedSuccess())
                            .mapToInt(WorkStatusEvidence.Call::appliedEntries).sum())
                    .append(". Earlier failures are not erased by later success.\n");
        if (request.roles().contains(WorkStatusRequest.Role.TESTING)) answer.append("Testing: not established.\n");
        if (request.roles().contains(WorkStatusRequest.Role.RELEASE)) answer.append("Release acceptance: not established.\n");
        if (request.requiresOperationDetails()) operationGap(answer);
        if (evidence.turns().isEmpty()) answer.append("Runtime status records are unavailable in the retained window.\n");
        answer.append("Earlier status records evicted: ").append(evidence.evictedTurns())
                .append(". Call entries omitted by the per-turn bound: ")
                .append(evidence.turns().stream().mapToInt(WorkStatusEvidence.Turn::omittedCalls).sum())
                .append(". Counts are retained entries, not unique lifetime operations. Zero does not prove earlier absence.\n")
                .append("Execution means a tool invocation, not proof of a subprocess or file change. ")
                .append("Restored prose does not restore status proof. ")
                .append("No fresh workspace-content inspection or tool execution was performed for this answer.");
        return answer.toString();
    }

    private static void operationGap(StringBuilder answer) {
        answer.append("Operation details remain unresolved. Admitted targets do not establish changed contents ")
                .append("or every requested change. Counts do not fully answer what was done, denied or changed.\n");
    }

    private static void appendOperationTargets(StringBuilder answer, WorkStatusEvidence evidence,
                                                java.nio.file.Path workspace) {
        String current = workspace == null ? "" : dev.talos.runtime.JsonSessionStore.sessionIdFor(workspace);
        var details = new StringBuilder();
        long withheld = 0, omitted = 0, restrictedCalls = 0, scopeOmitted = 0, outputOmitted = 0;
        int rows = 0;
        for (var turn : evidence.turns()) {
            boolean matched = !current.isEmpty() && current.equals(turn.workspaceHash());
            for (var call : turn.calls()) {
                withheld += call.withheldTargets();
                omitted += call.omittedTargets();
                if (call.targetMetadataWithheld()) restrictedCalls++;
                if (!matched) {
                    scopeOmitted += call.retainedTargets();
                    continue;
                }
                if (call.retainedTargets() == 0) continue;
                if (rows >= MAX_OPERATION_DETAIL_ROWS) {
                    outputOmitted += call.retainedTargets();
                    continue;
                }
                var row = new StringBuilder("Turn ").append(turn.turnNumber()).append(' ')
                        .append(call.canonicalToolName()).append(" [").append(call.disposition())
                        .append(call.reportedSuccess() ? ", reported success" : ", unsuccessful")
                        .append(", ").append(call.failureReason()).append("]: ");
                if (!call.declaredTarget().isEmpty()) row.append("declared ")
                        .append(targetLiteral(call.declaredTarget())).append(' ');
                if (!call.appliedTargets().isEmpty()) row.append("applied ")
                        .append(literalTargets(call.appliedTargets())).append(' ');
                if (!call.uncertainTargets().isEmpty()) row.append("uncertain ")
                        .append(literalTargets(call.uncertainTargets())).append(' ');
                row.append('\n');
                if (details.length() + row.length() > MAX_OPERATION_DETAIL_CHARS) {
                    outputOmitted += call.retainedTargets();
                    continue;
                }
                details.append(row);
                rows++;
            }
        }
        if (!details.isEmpty()) answer.append("Recorded operation targets in the matching workspace ")
                .append("(literal escapes preserve path data):\n").append(details)
                .append("Declared targets are not applied effects. Paths do not establish changed contents or current file state.\n");
        long unbound = evidence.turns().stream().filter(turn -> turn.workspaceHash().isEmpty()).count();
        long different = evidence.turns().stream().filter(turn -> !turn.workspaceHash().isEmpty()
                && !current.isEmpty() && !current.equals(turn.workspaceHash())).count();
        if (current.isEmpty()) answer.append("Current workspace binding is unavailable, so no target attribution is supplied.\n");
        if (withheld + omitted + restrictedCalls + scopeOmitted + outputOmitted + unbound + different > 0)
            answer.append("Target limits: ").append(withheld).append(" withheld entries, ")
                    .append(omitted).append(" retention-omitted entries, ").append(restrictedCalls)
                    .append(" calls with target metadata withheld, ").append(scopeOmitted)
                    .append(" workspace-omitted entries, ").append(outputOmitted)
                    .append(" output-omitted entries, ").append(unbound).append(" unbound turns, ")
                    .append(different).append(" different-workspace turns. Counts above span retained turns, not current-workspace proof.\n");
    }

    private static String literalTargets(List<String> targets) {
        return targets.stream().map(WorkStatusAnswerPolicy::targetLiteral)
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }

    private static String targetLiteral(String target) {
        // Admission preserves identity. Only this small alphabet is safe inside a single code span.
        if (!target.isEmpty() && target.matches("[A-Za-z0-9./_ -]+")) return "`" + target + "`";
        return ProjectHandoffPolicy.literal(target);
    }
}
