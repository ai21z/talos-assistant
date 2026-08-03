package dev.talos.runtime.workspace;

import dev.talos.runtime.intent.WorkspaceOperationObligation;
import dev.talos.runtime.intent.CoordinatedMutationTailPlan;
import dev.talos.runtime.intent.SequentialMutationClausePlan;
import dev.talos.runtime.intent.TaskIntent;
import dev.talos.runtime.intent.TargetRole;
import dev.talos.tools.ToolCall;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One fail-closed runtime authority plan structurally merged from the exact
 * T1196/T1204 parser outputs.
 */
public final class WorkspaceOperationAuthorityPlan {
    private static final String IDENTITY_VERSION =
            "talos-workspace-operation-authority-plan-v1";

    public enum State {
        NONE,
        AUTHORIZED_SINGLE,
        BLOCKED
    }

    private final String planId;
    private final String canonicalRequestSha256;
    private final int canonicalRequestUtf8Bytes;
    private final State state;
    private final String reason;
    private final List<WorkspaceOperationObligation> operations;

    private WorkspaceOperationAuthorityPlan(
            String planId,
            String canonicalRequestSha256,
            int canonicalRequestUtf8Bytes,
            State state,
            String reason,
            List<WorkspaceOperationObligation> operations
    ) {
        this.canonicalRequestSha256 = requireSha256(canonicalRequestSha256);
        if (canonicalRequestUtf8Bytes < 0) {
            throw new IllegalArgumentException(
                    "workspace authority request byte count must not be negative");
        }
        this.canonicalRequestUtf8Bytes = canonicalRequestUtf8Bytes;
        this.state = Objects.requireNonNull(state, "workspace authority state");
        this.reason = Objects.toString(reason, "").strip();
        this.operations = List.copyOf(operations == null ? List.of() : operations);
        validateState();
        String expected = computePlanId();
        String supplied = Objects.toString(planId, "").strip().toLowerCase();
        if (!supplied.isEmpty() && !expected.equals(supplied)) {
            throw new IllegalArgumentException(
                    "workspace authority plan identity mismatch");
        }
        this.planId = expected;
    }

    public static WorkspaceOperationAuthorityPlan none(String canonicalRequest) {
        String request = Objects.toString(canonicalRequest, "");
        return new WorkspaceOperationAuthorityPlan(
                "",
                sha256(request),
                request.getBytes(StandardCharsets.UTF_8).length,
                State.NONE,
                "no-workspace-operation-evidence",
                List.of());
    }

    public static WorkspaceOperationAuthorityPlan blocked(
            String canonicalRequest,
            String reason
    ) {
        String request = Objects.toString(canonicalRequest, "");
        return new WorkspaceOperationAuthorityPlan(
                "",
                sha256(request),
                request.getBytes(StandardCharsets.UTF_8).length,
                State.BLOCKED,
                reason == null || reason.isBlank()
                        ? "workspace-operation-not-fully-typed"
                        : reason,
                List.of());
    }

    public static WorkspaceOperationAuthorityPlan authorized(
            String canonicalRequest,
            WorkspaceOperationObligation operation
    ) {
        String request = Objects.toString(canonicalRequest, "");
        WorkspaceOperationObligation required = Objects.requireNonNull(
                operation,
                "workspace operation obligation");
        if (!required.canonicalRequestSha256().equals(sha256(request))
                || required.canonicalRequestUtf8Bytes()
                        != request.getBytes(StandardCharsets.UTF_8).length
                || required.sourceEndOffset() > request.length()
                || !request.substring(
                                required.sourceStartOffset(),
                                required.sourceEndOffset())
                        .equals(required.sourceText())) {
            throw new IllegalArgumentException(
                    "workspace operation does not bind the canonical request");
        }
        return new WorkspaceOperationAuthorityPlan(
                "",
                required.canonicalRequestSha256(),
                required.canonicalRequestUtf8Bytes(),
                State.AUTHORIZED_SINGLE,
                "exact-single-parser-owned-operation",
                List.of(required));
    }

    /** Structurally merges exact parser outputs without reading prose. */
    public static WorkspaceOperationAuthorityPlan merge(
            String canonicalRequest,
            CoordinatedMutationTailPlan tailPlan,
            SequentialMutationClausePlan sequentialPlan
    ) {
        String request = Objects.toString(canonicalRequest, "");
        CoordinatedMutationTailPlan tail = tailPlan == null
                ? CoordinatedMutationTailPlan.empty(request)
                : tailPlan;
        SequentialMutationClausePlan sequential = sequentialPlan == null
                ? SequentialMutationClausePlan.empty(request)
                : sequentialPlan;
        if (!tail.canonicalRequest().equals(request)
                || !sequential.canonicalRequest().equals(request)) {
            throw new IllegalArgumentException(
                    "workspace authority merge requires one canonical request");
        }
        int tailClauses = tail.workspaceOperationClauseObservations().size();
        long sequentialClauses = sequential.ownedRegions().stream()
                .filter(region ->
                        region.kind()
                                == SequentialMutationClausePlan.RegionKind
                                        .WORKSPACE_OPERATION)
                .count();
        long observedClauses = tailClauses + sequentialClauses;
        List<WorkspaceOperationObligation> typed = java.util.stream.Stream
                .concat(
                        tail.workspaceOperationObligations().stream(),
                        sequential.workspaceOperationObligations().stream())
                .toList();
        if (observedClauses == 0) {
            if (!typed.isEmpty()) {
                throw new IllegalArgumentException(
                        "typed workspace authority lacks parser-owned evidence");
            }
            return none(request);
        }
        if (tailClauses > 0 && sequentialClauses > 0) {
            return blocked(request, "workspace-operation-cross-plan-overlap");
        }
        if (observedClauses != 1) {
            return blocked(request, "workspace-operation-count-not-one");
        }
        if (typed.size() != 1) {
            return blocked(request, "workspace-operation-not-fully-typed");
        }
        WorkspaceOperationObligation operation = typed.getFirst();
        boolean exactTail = operation.owner()
                        == WorkspaceOperationObligation.Owner.COORDINATED_TAIL
                && tailClauses == 1
                && sequentialClauses == 0;
        boolean exactSequential = operation.owner()
                        == WorkspaceOperationObligation.Owner.SEQUENTIAL_CLAUSE
                && sequentialClauses == 1
                && tailClauses == 0;
        if (!exactTail && !exactSequential) {
            return blocked(request, "workspace-operation-owner-mismatch");
        }
        return authorized(request, operation);
    }

    /**
     * Applies mutation ceilings and the currently supported content-before-
     * operation ordering to the structurally merged parser plan.
     */
    public static WorkspaceOperationAuthorityPlan merge(
            TaskIntent intent,
            CoordinatedMutationTailPlan tailPlan,
            SequentialMutationClausePlan sequentialPlan
    ) {
        TaskIntent safeIntent = intent == null
                ? new TaskIntent(
                        null,
                        false,
                        false,
                        false,
                        dev.talos.runtime.intent.ArtifactTargetSet.empty(),
                        "",
                        "")
                : intent;
        WorkspaceOperationAuthorityPlan structural = merge(
                safeIntent.originalUserRequest(),
                tailPlan,
                sequentialPlan);
        if (structural.state != State.AUTHORIZED_SINGLE) return structural;
        if (!safeIntent.mutationAllowed()) {
            return blocked(
                    safeIntent.originalUserRequest(),
                    "workspace-operation-mutation-not-allowed");
        }
        WorkspaceOperationObligation operation = structural.operations
                .getFirst();
        Set<String> forbiddenPaths = safeIntent.targets()
                .pathsByRole(TargetRole.FORBIDDEN);
        if (operation.operands().stream()
                .map(WorkspaceOperationObligation.Operand::effectivePath)
                .anyMatch(forbiddenPaths::contains)) {
            return blocked(
                    safeIntent.originalUserRequest(),
                    "workspace-operation-operand-forbidden");
        }
        if (!safeIntent.targets()
                .pathsByRole(TargetRole.MAY_MUTATE)
                .isEmpty()) {
            return blocked(
                    safeIntent.originalUserRequest(),
                    "workspace-operation-unresolved-optional-mutation-target");
        }
        List<dev.talos.runtime.intent.TargetRef> contentTargets =
                safeIntent.targets().targets().stream()
                        .filter(target -> target.role() == TargetRole.MUST_MUTATE
                                || target.role()
                                        == TargetRole
                                                .MUST_MUTATE_AFTER_SOURCE_EVIDENCE
                                || target.role()
                                        == TargetRole.OUTPUT_DESTINATION)
                        .toList();
        for (dev.talos.runtime.intent.TargetRef target : contentTargets) {
            int start = target.derivation().startOffset();
            int end = target.derivation().endOffset();
            if (start < 0 || end < 0) {
                return blocked(
                        safeIntent.originalUserRequest(),
                        "workspace-operation-primary-content-authority-ambiguous");
            }
            boolean precedesOperation = end <= operation.sourceStartOffset();
            if (!precedesOperation) {
                return blocked(
                        safeIntent.originalUserRequest(),
                        "workspace-operation-before-content-order-unsupported");
            }
        }
        return structural;
    }

    private void validateState() {
        if (state == State.AUTHORIZED_SINGLE) {
            if (operations.size() != 1 || reason.isBlank()) {
                throw new IllegalArgumentException(
                        "authorized workspace plan requires exactly one operation");
            }
            WorkspaceOperationObligation operation = operations.getFirst();
            if (!operation.canonicalRequestSha256()
                            .equals(canonicalRequestSha256)
                    || operation.canonicalRequestUtf8Bytes()
                            != canonicalRequestUtf8Bytes) {
                throw new IllegalArgumentException(
                        "workspace plan and operation request identities differ");
            }
            return;
        }
        if (!operations.isEmpty()) {
            throw new IllegalArgumentException(
                    "non-authorizing workspace plan cannot retain executable operations");
        }
        if (reason.isBlank()) {
            throw new IllegalArgumentException(
                    "non-authorizing workspace plan requires a bounded reason");
        }
    }

    private String computePlanId() {
        StringBuilder canonical = new StringBuilder(IDENTITY_VERSION);
        append(canonical, canonicalRequestSha256);
        append(canonical, Integer.toString(canonicalRequestUtf8Bytes));
        append(canonical, state.name());
        append(canonical, reason);
        append(canonical, Integer.toString(operations.size()));
        for (WorkspaceOperationObligation operation : operations) {
            append(canonical, operation.stableId());
        }
        return sha256(canonical.toString());
    }

    public boolean bindsRequest(String canonicalRequest) {
        String request = Objects.toString(canonicalRequest, "");
        return canonicalRequestSha256.equals(sha256(request))
                && canonicalRequestUtf8Bytes
                        == request.getBytes(StandardCharsets.UTF_8).length;
    }

    public boolean authorizes(ToolCall call) {
        return state == State.AUTHORIZED_SINGLE
                && matches(operations.getFirst(), call);
    }

    public boolean blocksWorkspaceOperations() {
        return state == State.BLOCKED;
    }

    public boolean observedWorkspaceOperation() {
        return state != State.NONE;
    }

    public List<WorkspaceOperationObligation> authorizedOperations() {
        return state == State.AUTHORIZED_SINGLE ? operations : List.of();
    }

    public List<String> toolNames() {
        if (state != State.AUTHORIZED_SINGLE) return List.of();
        return operations.stream()
                .map(WorkspaceOperationObligation::toolName)
                .toList();
    }

    private static boolean matches(
            WorkspaceOperationObligation operation,
            ToolCall call
    ) {
        if (operation == null
                || call == null
                || !operation.toolName().equals(call.toolName())) {
            return false;
        }
        Map<String, String> parameters = call.parameters();
        return switch (operation.kind()) {
            case COPY_PATH -> matchesTwoPath(
                    operation,
                    parameters,
                    true);
            case MOVE_PATH -> matchesTwoPath(
                    operation,
                    parameters,
                    false);
            case RENAME_PATH -> matchesRename(operation, parameters);
            case MKDIR -> parameters.keySet().equals(Set.of("path"))
                    && samePath(parameters.get("path"),
                            operation.targetPath());
            case DELETE_PATH -> matchesDelete(operation, parameters);
        };
    }

    private static boolean matchesTwoPath(
            WorkspaceOperationObligation operation,
            Map<String, String> parameters,
            boolean recursionAllowed
    ) {
        Set<String> required = Set.of("from", "to");
        Set<String> optional = recursionAllowed
                ? Set.of("overwrite", "recursive")
                : Set.of("overwrite");
        if (!exactShape(parameters, required, optional)
                || !samePath(parameters.get("from"), operation.sourcePath())
                || !samePath(parameters.get("to"),
                        operation.destinationPath())) {
            return false;
        }
        return booleanMatches(
                        parameters.get("overwrite"),
                        operation.overwritePolicy()
                                == WorkspaceOperationObligation.OverwritePolicy
                                        .OVERWRITE_EXPLICIT)
                && (!recursionAllowed
                        || booleanMatches(
                                parameters.get("recursive"),
                                operation.recursivePolicy()
                                        == WorkspaceOperationObligation
                                                .RecursivePolicy
                                                .RECURSIVE_EXPLICIT));
    }

    private static boolean matchesRename(
            WorkspaceOperationObligation operation,
            Map<String, String> parameters
    ) {
        if (!exactShape(
                        parameters,
                        Set.of("path", "new_name"),
                        Set.of("overwrite"))
                || !samePath(parameters.get("path"), operation.sourcePath())) {
            return false;
        }
        String destination = operation.destinationPath();
        int slash = destination.lastIndexOf('/');
        String newName = slash < 0
                ? destination
                : destination.substring(slash + 1);
        return Objects.equals(parameters.get("new_name"), newName)
                && booleanMatches(
                        parameters.get("overwrite"),
                        operation.overwritePolicy()
                                == WorkspaceOperationObligation.OverwritePolicy
                                        .OVERWRITE_EXPLICIT);
    }

    private static boolean matchesDelete(
            WorkspaceOperationObligation operation,
            Map<String, String> parameters
    ) {
        return exactShape(parameters, Set.of("path"), Set.of("recursive"))
                && samePath(parameters.get("path"), operation.targetPath())
                && booleanMatches(
                        parameters.get("recursive"),
                        operation.recursivePolicy()
                                == WorkspaceOperationObligation.RecursivePolicy
                                        .RECURSIVE_EXPLICIT);
    }

    private static boolean exactShape(
            Map<String, String> parameters,
            Set<String> required,
            Set<String> optional
    ) {
        if (parameters == null || !parameters.keySet().containsAll(required)) {
            return false;
        }
        LinkedHashSet<String> extras = new LinkedHashSet<>(parameters.keySet());
        extras.removeAll(required);
        return optional.containsAll(extras);
    }

    private static boolean booleanMatches(String supplied, boolean expected) {
        if (supplied == null) return !expected;
        String value = supplied.strip().toLowerCase();
        return ("true".equals(value) || "false".equals(value))
                && Boolean.parseBoolean(value) == expected;
    }

    private static boolean samePath(String supplied, String expected) {
        return normalizePath(supplied).equals(normalizePath(expected));
    }

    private static String normalizePath(String value) {
        String normalized = Objects.toString(value, "").strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static String requireSha256(String value) {
        String normalized = Objects.toString(value, "").strip().toLowerCase();
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "workspace authority request identity must be SHA-256");
        }
        return normalized;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            Objects.toString(value, "")
                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void append(StringBuilder target, String value) {
        String safe = Objects.toString(value, "");
        target.append(safe.length()).append(':').append(safe);
    }

    public String planId() {
        return planId;
    }

    public String canonicalRequestSha256() {
        return canonicalRequestSha256;
    }

    public int canonicalRequestUtf8Bytes() {
        return canonicalRequestUtf8Bytes;
    }

    public State state() {
        return state;
    }

    public String reason() {
        return reason;
    }

    public List<WorkspaceOperationObligation> operations() {
        return operations;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WorkspaceOperationAuthorityPlan plan
                && planId.equals(plan.planId);
    }

    @Override
    public int hashCode() {
        return planId.hashCode();
    }

    @Override
    public String toString() {
        return "WorkspaceOperationAuthorityPlan[planId=" + planId
                + ", state=" + state
                + ", reason=" + reason
                + ", operations=" + operations + ']';
    }
}
