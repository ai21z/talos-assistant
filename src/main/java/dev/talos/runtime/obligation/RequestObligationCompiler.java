package dev.talos.runtime.obligation;

import dev.talos.runtime.expectation.AppendStatementExpectation;
import dev.talos.runtime.expectation.TaskExpectation;
import dev.talos.runtime.expectation.TaskExpectationResolver;
import dev.talos.runtime.task.TaskContract;

import java.util.List;

import static dev.talos.runtime.obligation.RequestObligation.ActionKind.APPEND_STATEMENT;
import static dev.talos.runtime.obligation.RequestObligation.ActionKind.NONE;
import static dev.talos.runtime.obligation.RequestObligation.DerivationStatus.COMPLETE;
import static dev.talos.runtime.obligation.RequestObligation.DerivationStatus.NOT_MIGRATED;
import static dev.talos.runtime.obligation.RequestObligation.MutationScope.ONLY_REQUIRED_TARGETS;
import static dev.talos.runtime.obligation.RequestObligation.OutputKind.APPEND_LABELED_STATEMENT;
import static dev.talos.runtime.obligation.RequestObligation.PreservationKind.NORMALIZED_PRIOR_CONTENT;
import static dev.talos.runtime.obligation.RequestObligation.ResponseOwner.DETERMINISTIC_RUNTIME_ALLOWED;
import static dev.talos.runtime.obligation.RequestObligation.ResponseOwner.UNKNOWN;
import static dev.talos.runtime.obligation.RequestObligation.VerificationKind.MATCHING_STATEMENT_FINAL_LOGICAL_LINE;
import static dev.talos.runtime.obligation.RequestObligation.VerificationKind.MATCHING_STATEMENT_PRESENT_ONCE;
import static dev.talos.runtime.obligation.RequestObligation.VerificationKind.STRUCTURED_MUTATION_EVIDENCE;

/** Compiles only request semantics that the deterministic runtime can prove. */
public final class RequestObligationCompiler {

    private RequestObligationCompiler() {}

    public static RequestObligation compile(TaskContract contract) {
        TaskContract safeContract = contract == null ? TaskContract.unknown("") : contract;
        return compileResolved(safeContract, TaskExpectationResolver.resolve(safeContract));
    }

    private static RequestObligation compileResolved(
            TaskContract contract,
            List<TaskExpectation> expectations
    ) {
        TaskContract safeContract = contract == null ? TaskContract.unknown("") : contract;
        List<TaskExpectation> safeExpectations =
                expectations == null ? List.of() : List.copyOf(expectations);

        try {
            AppendStatementExpectation append = completeAppendStatement(
                    safeContract,
                    safeExpectations);
            if (append == null) {
                return notMigrated(safeContract);
            }

            String target = append.targetPath();
            return new RequestObligation(
                    RequestObligation.SCHEMA_VERSION,
                    COMPLETE,
                    DETERMINISTIC_RUNTIME_ALLOWED,
                    APPEND_STATEMENT,
                    ONLY_REQUIRED_TARGETS,
                    List.copyOf(safeContract.expectedTargets()),
                    List.copyOf(safeContract.sourceEvidenceTargets()),
                    List.copyOf(safeContract.forbiddenTargets()),
                    List.of(new RequestObligation.PreservationConstraint(
                            NORMALIZED_PRIOR_CONTENT,
                            target)),
                    new RequestObligation.OutputShape(
                            APPEND_LABELED_STATEMENT,
                            target,
                            append.label(),
                            append.statement()),
                    List.of(
                            new RequestObligation.VerificationPostcondition(
                                    MATCHING_STATEMENT_PRESENT_ONCE,
                                    target),
                            new RequestObligation.VerificationPostcondition(
                                    MATCHING_STATEMENT_FINAL_LOGICAL_LINE,
                                    target),
                            new RequestObligation.VerificationPostcondition(
                                    STRUCTURED_MUTATION_EVIDENCE,
                                    target)));
        } catch (IllegalArgumentException unsafeOrUnsupportedContract) {
            return notMigratedWithoutTargets();
        }
    }

    public static RequestObligation notMigrated(TaskContract contract) {
        TaskContract safeContract = contract == null ? TaskContract.unknown("") : contract;
        try {
            return new RequestObligation(
                    RequestObligation.SCHEMA_VERSION,
                    NOT_MIGRATED,
                    UNKNOWN,
                    NONE,
                    RequestObligation.MutationScope.NONE,
                    List.copyOf(safeContract.expectedTargets()),
                    List.copyOf(safeContract.sourceEvidenceTargets()),
                    List.copyOf(safeContract.forbiddenTargets()),
                    List.of(),
                    RequestObligation.OutputShape.none(),
                    List.of());
        } catch (IllegalArgumentException unsafeContract) {
            return notMigratedWithoutTargets();
        }
    }

    private static AppendStatementExpectation completeAppendStatement(
            TaskContract contract,
            List<TaskExpectation> expectations
    ) {
        if (!contract.mutationRequested()
                || !contract.mutationAllowed()
                || !contract.verificationRequired()
                || contract.expectedTargets().size() != 1
                || !contract.sourceEvidenceTargets().isEmpty()
                || expectations.size() != 1
                || !(expectations.getFirst() instanceof AppendStatementExpectation append)
                || !contract.expectedTargets().contains(append.targetPath())) {
            return null;
        }
        return append;
    }

    private static RequestObligation notMigratedWithoutTargets() {
        return new RequestObligation(
                RequestObligation.SCHEMA_VERSION,
                NOT_MIGRATED,
                UNKNOWN,
                NONE,
                RequestObligation.MutationScope.NONE,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                RequestObligation.OutputShape.none(),
                List.of());
    }
}
