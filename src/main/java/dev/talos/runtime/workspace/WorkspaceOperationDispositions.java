package dev.talos.runtime.workspace;

import dev.talos.runtime.intent.CoordinatedMutationTailPlan;
import dev.talos.runtime.intent.SequentialMutationClausePlan;
import dev.talos.runtime.intent.TargetRef;
import dev.talos.runtime.intent.WorkspaceOperationObligation;
import dev.talos.tools.WorkspacePathIdentity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The typed state of every workspace operation the request parsers bound with exact operands (MICA operation
 * preservation). An operation is admitted only when it is the one operation the authority plan authorized, and every
 * typed operation of a blocked plan is withheld with a typed reason, so it is stated as not performed instead of being
 * dropped. A workspace decision without a typed obligation is not stated, because its operands are not bound and the
 * parser also records such decisions for text that requests nothing, such as a pasted command in a question. It is
 * derived from the resolution and never persisted.
 */
public record WorkspaceOperationDispositions(List<Disposition> dispositions, boolean answeredByRuntime) {

    public enum State {
        ADMITTED,
        WITHHELD
    }

    public record Operand(WorkspaceOperationObligation.Role role, String path) {
        public Operand {
            Objects.requireNonNull(role, "operand role");
            path = TargetRef.normalizePath(path);
            if (path.isBlank()) throw new IllegalArgumentException("disposition operand must name a path");
        }
    }

    /** One typed requested operation, bound to its exact source span. */
    public record Disposition(
            State state,
            WorkspaceOperationObligation.Kind kind,
            List<Operand> operands,
            int sourceStartOffset,
            int sourceEndOffset,
            String sourceText,
            String operationId,
            String reason
    ) {
        public Disposition {
            Objects.requireNonNull(state, "disposition state");
            Objects.requireNonNull(kind, "disposition kind");
            operands = List.copyOf(operands == null ? List.of() : operands);
            sourceText = Objects.toString(sourceText, "");
            operationId = Objects.toString(operationId, "").strip();
            reason = Objects.toString(reason, "").strip();
            if (operands.isEmpty() || operationId.isEmpty() || sourceStartOffset < 0
                    || sourceEndOffset <= sourceStartOffset || sourceText.isEmpty()) {
                throw new IllegalArgumentException("disposition must bind one typed operation and its source");
            }
            if (state == State.ADMITTED != reason.isEmpty()) {
                throw new IllegalArgumentException("only a withheld disposition carries a reason");
            }
        }

        public boolean withheld() {
            return state == State.WITHHELD;
        }

        /** The operation in runtime words, built from its kind and operands. */
        public String description() {
            return switch (kind) {
                case DELETE_PATH -> "delete " + path(WorkspaceOperationObligation.Role.TARGET);
                case MKDIR -> "create folder " + path(WorkspaceOperationObligation.Role.TARGET);
                case COPY_PATH -> "copy " + path(WorkspaceOperationObligation.Role.SOURCE)
                        + " to " + path(WorkspaceOperationObligation.Role.DESTINATION);
                case MOVE_PATH -> "move " + path(WorkspaceOperationObligation.Role.SOURCE)
                        + " to " + path(WorkspaceOperationObligation.Role.DESTINATION);
                case RENAME_PATH -> "rename " + path(WorkspaceOperationObligation.Role.SOURCE)
                        + " to " + path(WorkspaceOperationObligation.Role.DESTINATION);
            };
        }

        /** The runtime statement for a withheld operation. It never says failed, because nothing was attempted. */
        public String notPerformedStatement() {
            return "Not performed: " + description() + ". " + reasonSentence(reason);
        }

        private String path(WorkspaceOperationObligation.Role role) {
            return operands.stream().filter(operand -> operand.role() == role).map(Operand::path)
                    .findFirst().orElse(operands.getFirst().path());
        }
    }

    public WorkspaceOperationDispositions {
        dispositions = List.copyOf(dispositions == null ? List.of() : dispositions);
        if (answeredByRuntime && (withheldIn(dispositions).isEmpty()
                || dispositions.stream().anyMatch(disposition -> !disposition.withheld()))) {
            throw new IllegalArgumentException("only withheld operations can be answered by the runtime");
        }
    }

    public static WorkspaceOperationDispositions none() {
        return new WorkspaceOperationDispositions(List.of(), false);
    }

    /**
     * Derives the dispositions from the typed obligations of both parser plans and the authority plan. The runtime
     * answers alone only when the caller found nothing else requested and every workspace decision of the parsers lies
     * inside a typed obligation, so its answer names every recognized operation. In a turn that allows no change, that
     * is the reason stated for every withheld operation, because no operation could run there whatever the plan
     * counted.
     */
    public static WorkspaceOperationDispositions derive(
            WorkspaceOperationAuthorityPlan plan,
            CoordinatedMutationTailPlan tailPlan,
            SequentialMutationClausePlan sequentialPlan,
            Set<String> forbiddenPaths,
            boolean mutationAllowed,
            boolean nothingElseRequested
    ) {
        if (plan == null || plan.state() == WorkspaceOperationAuthorityPlan.State.NONE) return none();
        if (plan.state() == WorkspaceOperationAuthorityPlan.State.AUTHORIZED_SINGLE) {
            // The structural merge admits exactly one observed operation, so nothing else is requested here.
            WorkspaceOperationObligation operation = plan.authorizedOperations().getFirst();
            return new WorkspaceOperationDispositions(List.of(new Disposition(State.ADMITTED, operation.kind(),
                    operands(operation), operation.sourceStartOffset(), operation.sourceEndOffset(),
                    operation.sourceText(), operation.operationId(), "")), false);
        }
        List<WorkspaceOperationObligation> obligations = new ArrayList<>();
        List<int[]> decisionSpans = new ArrayList<>();
        if (tailPlan != null) {
            obligations.addAll(tailPlan.workspaceOperationObligations());
            for (CoordinatedMutationTailPlan.TargetDecision decision : tailPlan.decisions()) {
                if (decision.authority() == CoordinatedMutationTailPlan.Authority.WORKSPACE_OPERATION_BLOCKED) {
                    decisionSpans.add(new int[] {decision.startOffset(), decision.endOffset()});
                }
            }
        }
        if (sequentialPlan != null) {
            obligations.addAll(sequentialPlan.workspaceOperationObligations());
            for (SequentialMutationClausePlan.ClauseDecision decision : sequentialPlan.decisions()) {
                if (decision.authority() == SequentialMutationClausePlan.Authority.WORKSPACE_OPERATION_BLOCKED) {
                    decisionSpans.add(new int[] {decision.startOffset(), decision.endOffset()});
                }
            }
        }
        Set<String> forbidden = forbiddenPaths == null ? Set.of() : forbiddenPaths;
        List<Disposition> out = new ArrayList<>();
        for (WorkspaceOperationObligation obligation : obligations) {
            List<Operand> operands = operands(obligation);
            out.add(new Disposition(State.WITHHELD, obligation.kind(), operands, obligation.sourceStartOffset(),
                    obligation.sourceEndOffset(), obligation.sourceText(), obligation.operationId(),
                    reason(plan, operands, forbidden, mutationAllowed)));
        }
        // Only a decision inside an obligation's span is that obligation's own clause. A wider decision may hold an
        // operation the obligation does not name.
        boolean everyDecisionTyped = decisionSpans.stream().allMatch(span -> obligations.stream()
                .anyMatch(obligation -> obligation.sourceStartOffset() <= span[0]
                        && span[1] <= obligation.sourceEndOffset()));
        out.sort(Comparator.comparingInt(Disposition::sourceStartOffset)
                .thenComparingInt(Disposition::sourceEndOffset));
        return new WorkspaceOperationDispositions(out, nothingElseRequested && everyDecisionTyped && !out.isEmpty());
    }

    private static List<Operand> operands(WorkspaceOperationObligation operation) {
        return operation.operands().stream()
                .map(operand -> new Operand(operand.role(), operand.effectivePath()))
                .toList();
    }

    public List<Disposition> withheld() {
        return withheldIn(dispositions);
    }

    public List<Disposition> admitted() {
        return dispositions.stream().filter(disposition -> !disposition.withheld()).toList();
    }

    public boolean isEmpty() {
        return dispositions.isEmpty();
    }

    /** True when every disposition binds an exact span of the request. */
    public boolean bindsRequest(String canonicalRequest) {
        String request = Objects.toString(canonicalRequest, "");
        return dispositions.stream().allMatch(disposition -> disposition.sourceEndOffset() <= request.length()
                && request.substring(disposition.sourceStartOffset(), disposition.sourceEndOffset())
                        .equals(disposition.sourceText()));
    }

    /**
     * The complete runtime answer for a turn whose only typed requested operations were withheld. Text the parsers
     * did not type, such as a question beside the operations, is not answered, and the answer says so.
     */
    public String runtimeAnswer() {
        if (!answeredByRuntime) return "";
        StringBuilder answer = new StringBuilder(RUNTIME_ANSWER_HEADER);
        for (Disposition disposition : withheld()) {
            answer.append('\n').append(disposition.notPerformedStatement());
        }
        return answer.toString();
    }

    static final String RUNTIME_ANSWER_HEADER = "No file was changed in this turn. Only the workspace operations below "
            + "were assessed, and no other part of the request was answered.";

    /**
     * True when a path the turn applied or may have applied, whether or not its tool call succeeded, is the withheld
     * folder or lies inside it, so the folder may exist through the turn's own effects. Each spelling is compared by
     * workspace identity where a match must be assumed, so a case variant that names the same folder also settles it.
     * Without a workspace only the same spelling does.
     */
    public static boolean settledByEffects(Disposition disposition, List<String> affectedPaths, Path workspace) {
        if (disposition == null || disposition.kind() != WorkspaceOperationObligation.Kind.MKDIR
                || affectedPaths == null) {
            return false;
        }
        String directory = disposition.operands().getFirst().path();
        for (String affected : affectedPaths) {
            String candidate = TargetRef.normalizePath(affected);
            while (!candidate.isEmpty()) {
                if (candidate.equals(directory)
                        || workspace != null && WorkspacePathIdentity.mayMatch(workspace, directory, candidate)) {
                    return true;
                }
                int parent = candidate.lastIndexOf('/');
                candidate = parent < 0 ? "" : candidate.substring(0, parent);
            }
        }
        return false;
    }

    static String reasonSentence(String reason) {
        return switch (Objects.toString(reason, "")) {
            case "workspace-operation-primary-content-authority-ambiguous" ->
                    "Its order relative to the requested content change could not be established.";
            case "workspace-operation-before-content-order-unsupported" ->
                    "An operation before a content change in the same request is not supported.";
            case "workspace-operation-count-not-one" ->
                    "Only one workspace operation can run per request.";
            case "workspace-operation-cross-plan-overlap" ->
                    "Both request parsers recognized workspace operations in this request, so none can run.";
            case "workspace-operation-owner-mismatch" ->
                    "The typed operation and its recognized clause come from different request parsers.";
            case "workspace-operation-not-fully-typed" ->
                    "The recognized operation clause could not be matched to exactly one typed operation.";
            case "workspace-operation-mutation-not-allowed" ->
                    "This turn does not allow changes.";
            case "workspace-operation-operand-forbidden" ->
                    "The request forbids changing one of its paths.";
            case "workspace-operation-unresolved-optional-mutation-target" ->
                    "Another file in the request is optional or unresolved.";
            case "workspace-operation-replay-requires-typed-lifecycle" ->
                    "A confirmed replay cannot run a workspace operation.";
            case "workspace-operation-projection-removed-parser-anchor",
                    "workspace-operation-projection-removed-content-predecessor" ->
                    "The current mode removed part of the request it depends on.";
            default -> "It could not be authorized in this turn.";
        };
    }

    private static String reason(WorkspaceOperationAuthorityPlan plan, List<Operand> operands, Set<String> forbidden,
                                 boolean mutationAllowed) {
        if (!mutationAllowed) return "workspace-operation-mutation-not-allowed";
        if (operands.stream().anyMatch(operand -> forbidden.contains(operand.path()))) {
            return "workspace-operation-operand-forbidden";
        }
        return plan.reason();
    }

    private static List<Disposition> withheldIn(List<Disposition> dispositions) {
        return dispositions.stream().filter(Disposition::withheld).toList();
    }
}
