package dev.talos.runtime.workspace;

import dev.talos.runtime.intent.WorkspaceOperationObligation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Hash-only durable projection of a workspace-operation authority plan. */
public record WorkspaceOperationPlanEvidence(
        int schemaVersion,
        String planId,
        String state,
        String reason,
        String canonicalRequestSha256,
        int canonicalRequestUtf8Bytes,
        List<OperationEvidence> operations
) {
    public static final int SCHEMA_VERSION = 1;
    private static final String PLAN_IDENTITY_VERSION =
            "talos-workspace-operation-authority-plan-v1";
    private static final String OPERATION_IDENTITY_VERSION =
            "talos-workspace-operation-obligation-v1";
    private static final int MAX_REASON_CHARS = 160;

    public record OperationEvidence(
            int ordinal,
            String operationId,
            String owner,
            String kind,
            String toolName,
            String overwritePolicy,
            String recursivePolicy,
            String parentPolicy,
            int actionStartOffset,
            int actionEndOffset,
            int sourceStartOffset,
            int sourceEndOffset,
            String sourceSpanSha256,
            List<OperandEvidence> operands
    ) {
        public OperationEvidence {
            if (ordinal < 0
                    || operationId == null
                    || !operationId.matches("[0-9a-f]{64}")
                    || owner == null || owner.isBlank()
                    || kind == null || kind.isBlank()
                    || toolName == null || toolName.isBlank()
                    || overwritePolicy == null || overwritePolicy.isBlank()
                    || recursivePolicy == null || recursivePolicy.isBlank()
                    || parentPolicy == null || parentPolicy.isBlank()
                    || actionStartOffset < 0
                    || actionEndOffset <= actionStartOffset
                    || sourceStartOffset < 0
                    || sourceEndOffset <= sourceStartOffset
                    || actionStartOffset < sourceStartOffset
                    || actionEndOffset > sourceEndOffset
                    || sourceSpanSha256 == null
                    || !sourceSpanSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "invalid workspace-operation durable evidence");
            }
            operands = List.copyOf(operands == null ? List.of() : operands);
            WorkspaceOperationObligation.Owner.valueOf(owner);
            WorkspaceOperationObligation.Kind parsedKind =
                    WorkspaceOperationObligation.Kind.valueOf(kind);
            WorkspaceOperationObligation.OverwritePolicy.valueOf(
                    overwritePolicy);
            WorkspaceOperationObligation.RecursivePolicy.valueOf(
                    recursivePolicy);
            WorkspaceOperationObligation.ParentPolicy.valueOf(parentPolicy);
            if (!parsedKind.toolName().equals(toolName)
                    || operands.size() > 2) {
                throw new IllegalArgumentException(
                        "workspace-operation evidence kind/tool shape mismatch");
            }
            WorkspaceOperationObligation.OverwritePolicy parsedOverwrite =
                    WorkspaceOperationObligation.OverwritePolicy.valueOf(
                            overwritePolicy);
            WorkspaceOperationObligation.RecursivePolicy parsedRecursive =
                    WorkspaceOperationObligation.RecursivePolicy.valueOf(
                            recursivePolicy);
            WorkspaceOperationObligation.ParentPolicy parsedParent =
                    WorkspaceOperationObligation.ParentPolicy.valueOf(
                            parentPolicy);
            boolean validPolicies = switch (parsedKind) {
                case COPY_PATH -> parsedOverwrite
                                != WorkspaceOperationObligation.OverwritePolicy
                                        .NOT_APPLICABLE
                        && parsedRecursive
                                != WorkspaceOperationObligation.RecursivePolicy
                                        .NOT_APPLICABLE
                        && parsedParent
                                == WorkspaceOperationObligation.ParentPolicy
                                        .REQUIRE_EXISTING_PARENT;
                case MOVE_PATH, RENAME_PATH -> parsedOverwrite
                                != WorkspaceOperationObligation.OverwritePolicy
                                        .NOT_APPLICABLE
                        && parsedRecursive
                                == WorkspaceOperationObligation.RecursivePolicy
                                        .NOT_APPLICABLE
                        && parsedParent
                                == WorkspaceOperationObligation.ParentPolicy
                                        .REQUIRE_EXISTING_PARENT;
                case MKDIR -> parsedOverwrite
                                == WorkspaceOperationObligation.OverwritePolicy
                                        .NOT_APPLICABLE
                        && parsedRecursive
                                == WorkspaceOperationObligation.RecursivePolicy
                                        .NOT_APPLICABLE
                        && parsedParent
                                == WorkspaceOperationObligation.ParentPolicy
                                        .REQUIRE_EXISTING_PARENT;
                case DELETE_PATH -> parsedOverwrite
                                == WorkspaceOperationObligation.OverwritePolicy
                                        .NOT_APPLICABLE
                        && parsedRecursive
                                != WorkspaceOperationObligation.RecursivePolicy
                                        .NOT_APPLICABLE
                        && parsedParent
                                == WorkspaceOperationObligation.ParentPolicy
                                        .NOT_APPLICABLE;
            };
            if (!validPolicies) {
                throw new IllegalArgumentException(
                        "workspace-operation evidence policy mismatch");
            }
            List<WorkspaceOperationObligation.Role> expectedRoles =
                    switch (parsedKind) {
                        case COPY_PATH, MOVE_PATH, RENAME_PATH -> List.of(
                                WorkspaceOperationObligation.Role.SOURCE,
                                WorkspaceOperationObligation.Role.DESTINATION);
                        case MKDIR, DELETE_PATH -> List.of(
                                WorkspaceOperationObligation.Role.TARGET);
                    };
            if (operands.size() != expectedRoles.size()) {
                throw new IllegalArgumentException(
                        "workspace-operation evidence operand count mismatch");
            }
            int priorEnd = actionEndOffset;
            for (int index = 0; index < operands.size(); index++) {
                OperandEvidence operand = operands.get(index);
                if (operand.ordinal() != index
                        || WorkspaceOperationObligation.Role.valueOf(
                                operand.role()) != expectedRoles.get(index)
                        || operand.valueStartOffset() < priorEnd
                        || operand.valueEndOffset() > sourceEndOffset) {
                    throw new IllegalArgumentException(
                            "workspace-operation evidence operand ordering mismatch");
                }
                priorEnd = operand.valueEndOffset();
            }
        }
    }

    public record OperandEvidence(
            int ordinal,
            String role,
            String lexicalValueSha256,
            String effectivePathSha256,
            int valueStartOffset,
            int valueEndOffset
    ) {
        public OperandEvidence {
            if (ordinal < 0
                    || role == null || role.isBlank()
                    || lexicalValueSha256 == null
                    || !lexicalValueSha256.matches("[0-9a-f]{64}")
                    || effectivePathSha256 == null
                    || !effectivePathSha256.matches("[0-9a-f]{64}")
                    || valueStartOffset < 0
                    || valueEndOffset <= valueStartOffset) {
                throw new IllegalArgumentException(
                        "invalid workspace-operation operand evidence");
            }
        }
    }

    public WorkspaceOperationPlanEvidence {
        if (schemaVersion != SCHEMA_VERSION
                || planId == null || !planId.matches("[0-9a-f]{64}")
                || state == null || state.isBlank()
                || reason == null || reason.isBlank()
                || reason.length() > MAX_REASON_CHARS
                || canonicalRequestSha256 == null
                || !canonicalRequestSha256.matches("[0-9a-f]{64}")
                || canonicalRequestUtf8Bytes < 0) {
            throw new IllegalArgumentException(
                    "invalid workspace-operation plan evidence");
        }
        operations = List.copyOf(operations == null ? List.of() : operations);
        WorkspaceOperationAuthorityPlan.State parsedState =
                WorkspaceOperationAuthorityPlan.State.valueOf(state);
        if (operations.size() > 1
                || (parsedState == WorkspaceOperationAuthorityPlan.State
                                .AUTHORIZED_SINGLE)
                        != (operations.size() == 1)
                || (!operations.isEmpty()
                        && operations.getFirst().ordinal() != 0)) {
            throw new IllegalArgumentException(
                    "workspace plan evidence state/operation mismatch");
        }
        for (OperationEvidence operation : operations) {
            if (!operation.operationId().equals(computeOperationId(
                    canonicalRequestSha256,
                    canonicalRequestUtf8Bytes,
                    operation))) {
                throw new IllegalArgumentException(
                        "workspace-operation evidence identity mismatch");
            }
        }
        String expectedPlanId = computePlanId(
                canonicalRequestSha256,
                canonicalRequestUtf8Bytes,
                parsedState,
                reason,
                operations);
        if (!expectedPlanId.equals(planId)) {
            throw new IllegalArgumentException(
                    "workspace plan evidence identity mismatch");
        }
    }

    public static WorkspaceOperationPlanEvidence from(
            WorkspaceOperationAuthorityPlan plan
    ) {
        WorkspaceOperationAuthorityPlan required = Objects.requireNonNull(
                plan,
                "workspace authority plan");
        List<OperationEvidence> operationEvidence = new java.util.ArrayList<>();
        for (int operationOrdinal = 0;
                operationOrdinal < required.operations().size();
                operationOrdinal++) {
            WorkspaceOperationObligation operation =
                    required.operations().get(operationOrdinal);
            List<OperandEvidence> operandEvidence = new java.util.ArrayList<>();
            for (int operandOrdinal = 0;
                    operandOrdinal < operation.operands().size();
                    operandOrdinal++) {
                WorkspaceOperationObligation.Operand operand =
                        operation.operands().get(operandOrdinal);
                operandEvidence.add(new OperandEvidence(
                        operandOrdinal,
                        operand.role().name(),
                        sha256(operand.lexicalValue()),
                        sha256(operand.effectivePath()),
                        operand.valueStartOffset(),
                        operand.valueEndOffset()));
            }
            operationEvidence.add(new OperationEvidence(
                    operationOrdinal,
                    operation.operationId(),
                    operation.owner().name(),
                    operation.kind().name(),
                    operation.toolName(),
                    operation.overwritePolicy().name(),
                    operation.recursivePolicy().name(),
                    operation.parentPolicy().name(),
                    operation.actionStartOffset(),
                    operation.actionEndOffset(),
                    operation.sourceStartOffset(),
                    operation.sourceEndOffset(),
                    operation.sourceSpanSha256(),
                    operandEvidence));
        }
        return new WorkspaceOperationPlanEvidence(
                SCHEMA_VERSION,
                required.planId(),
                required.state().name(),
                required.reason(),
                required.canonicalRequestSha256(),
                required.canonicalRequestUtf8Bytes(),
                operationEvidence);
    }

    /**
     * Conservative compatibility projection when only the durable lexical
     * request identity remains. It can represent absence of authority, never
     * mint an executable operation.
     */
    public static WorkspaceOperationPlanEvidence noneForRequestIdentity(
            String canonicalRequestSha256,
            int canonicalRequestUtf8Bytes
    ) {
        String hash = Objects.toString(
                canonicalRequestSha256,
                "").strip().toLowerCase();
        String reason = "no-workspace-operation-evidence";
        List<OperationEvidence> operations = List.of();
        return new WorkspaceOperationPlanEvidence(
                SCHEMA_VERSION,
                computePlanId(
                        hash,
                        canonicalRequestUtf8Bytes,
                        WorkspaceOperationAuthorityPlan.State.NONE,
                        reason,
                        operations),
                WorkspaceOperationAuthorityPlan.State.NONE.name(),
                reason,
                hash,
                canonicalRequestUtf8Bytes,
                operations);
    }

    public boolean matches(WorkspaceOperationAuthorityPlan plan) {
        return plan != null && equals(from(plan));
    }

    /**
     * Validates this sealed, hash-only projection against the exact retained
     * request without invoking a natural-language parser. Durable evidence is
     * descriptive only; this check never reconstructs executable authority.
     */
    public void requireBindsCanonicalRequest(String canonicalRequest) {
        String request = Objects.toString(canonicalRequest, "");
        if (!canonicalRequestSha256.equals(sha256(request))
                || canonicalRequestUtf8Bytes
                        != request.getBytes(StandardCharsets.UTF_8).length) {
            throw new IllegalArgumentException(
                    "workspace-operation evidence request identity mismatch");
        }
        for (OperationEvidence operation : operations) {
            if (operation.sourceEndOffset() > request.length()) {
                throw new IllegalArgumentException(
                        "workspace-operation evidence source span exceeds request");
            }
            String source = request.substring(
                    operation.sourceStartOffset(),
                    operation.sourceEndOffset());
            if (!operation.sourceSpanSha256().equals(sha256(source))) {
                throw new IllegalArgumentException(
                        "workspace-operation evidence source span mismatch");
            }
            for (OperandEvidence operand : operation.operands()) {
                if (operand.valueEndOffset() > request.length()) {
                    throw new IllegalArgumentException(
                            "workspace-operation evidence operand span exceeds request");
                }
                String lexicalValue = request.substring(
                        operand.valueStartOffset(),
                        operand.valueEndOffset());
                if (!operand.lexicalValueSha256().equals(
                        sha256(lexicalValue))) {
                    throw new IllegalArgumentException(
                            "workspace-operation evidence operand span mismatch");
                }
            }
        }
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

    private static String computePlanId(
            String requestSha256,
            int requestUtf8Bytes,
            WorkspaceOperationAuthorityPlan.State state,
            String reason,
            List<OperationEvidence> operations
    ) {
        StringBuilder canonical = new StringBuilder(PLAN_IDENTITY_VERSION);
        append(canonical, requestSha256);
        append(canonical, Integer.toString(requestUtf8Bytes));
        append(canonical, state.name());
        append(canonical, reason);
        append(canonical, Integer.toString(operations.size()));
        for (OperationEvidence operation : operations) {
            append(canonical, operation.operationId());
        }
        return sha256(canonical.toString());
    }

    private static String computeOperationId(
            String requestSha256,
            int requestUtf8Bytes,
            OperationEvidence operation
    ) {
        StringBuilder canonical = new StringBuilder(
                OPERATION_IDENTITY_VERSION);
        append(canonical, operation.owner());
        append(canonical, operation.kind());
        append(canonical, requestSha256);
        append(canonical, Integer.toString(requestUtf8Bytes));
        append(canonical, Integer.toString(operation.actionStartOffset()));
        append(canonical, Integer.toString(operation.actionEndOffset()));
        append(canonical, Integer.toString(operation.sourceStartOffset()));
        append(canonical, Integer.toString(operation.sourceEndOffset()));
        append(canonical, operation.sourceSpanSha256());
        append(canonical, operation.overwritePolicy());
        append(canonical, operation.recursivePolicy());
        append(canonical, operation.parentPolicy());
        append(canonical, Integer.toString(operation.operands().size()));
        for (OperandEvidence operand : operation.operands()) {
            append(canonical, operand.role());
            append(canonical, operand.lexicalValueSha256());
            append(canonical, operand.effectivePathSha256());
            append(canonical, Integer.toString(operand.valueStartOffset()));
            append(canonical, Integer.toString(operand.valueEndOffset()));
        }
        return sha256(canonical.toString());
    }

    private static void append(StringBuilder target, String value) {
        String safe = Objects.toString(value, "");
        target.append(safe.length()).append(':').append(safe);
    }
}
