package dev.talos.runtime.obligation;

import dev.talos.runtime.expectation.AppendSectionExpectation;
import dev.talos.runtime.expectation.AppendStatementExpectation;
import dev.talos.runtime.expectation.ReplacementExpectation;
import dev.talos.runtime.expectation.TaskExpectation;
import dev.talos.runtime.expectation.TaskExpectationResolver;
import dev.talos.runtime.task.TaskContract;

import java.util.List;

import static dev.talos.runtime.obligation.RequestObligation.ActionKind.APPEND_SECTION;
import static dev.talos.runtime.obligation.RequestObligation.ActionKind.APPEND_STATEMENT;
import static dev.talos.runtime.obligation.RequestObligation.ActionKind.NONE;
import static dev.talos.runtime.obligation.RequestObligation.ActionKind.REPLACE_TEXT_PRESERVE_REST;
import static dev.talos.runtime.obligation.RequestObligation.DerivationStatus.COMPLETE;
import static dev.talos.runtime.obligation.RequestObligation.DerivationStatus.NOT_MIGRATED;
import static dev.talos.runtime.obligation.RequestObligation.MutationScope.ONLY_REQUIRED_TARGETS;
import static dev.talos.runtime.obligation.RequestObligation.OutputKind.APPEND_LABELED_STATEMENT;
import static dev.talos.runtime.obligation.RequestObligation.OutputKind.APPEND_MARKDOWN_SECTION;
import static dev.talos.runtime.obligation.RequestObligation.OutputKind.REPLACE_TEXT;
import static dev.talos.runtime.obligation.RequestObligation.PreservationKind.NORMALIZED_PRIOR_CONTENT;
import static dev.talos.runtime.obligation.RequestObligation.PreservationKind.UNCHANGED_CONTENT_AROUND_REPLACEMENT;
import static dev.talos.runtime.obligation.RequestObligation.ResponseOwner.DETERMINISTIC_RUNTIME_ALLOWED;
import static dev.talos.runtime.obligation.RequestObligation.ResponseOwner.UNKNOWN;
import static dev.talos.runtime.obligation.RequestObligation.VerificationKind.MATCHING_STATEMENT_FINAL_LOGICAL_LINE;
import static dev.talos.runtime.obligation.RequestObligation.VerificationKind.MATCHING_STATEMENT_PRESENT_ONCE;
import static dev.talos.runtime.obligation.RequestObligation.VerificationKind.MATCHING_SECTION_FINAL_DOCUMENT_SECTION;
import static dev.talos.runtime.obligation.RequestObligation.VerificationKind.MATCHING_SECTION_PRESENT_ONCE;
import static dev.talos.runtime.obligation.RequestObligation.VerificationKind.NEW_TEXT_PRESENT;
import static dev.talos.runtime.obligation.RequestObligation.VerificationKind.OLD_TEXT_ABSENT;
import static dev.talos.runtime.obligation.RequestObligation.VerificationKind.REQUESTED_REPLACEMENT_EXACTLY_ONCE;
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
            TaskExpectation expectation = completeExpectation(safeContract, safeExpectations);
            if (expectation == null) {
                return notMigrated(safeContract);
            }
            if (expectation instanceof AppendStatementExpectation append) {
                return compileAppendStatement(safeContract, append);
            }
            if (expectation instanceof AppendSectionExpectation appendSection) {
                return compileAppendSection(safeContract, appendSection);
            }
            if (expectation instanceof ReplacementExpectation replacement
                    && replacement.preserveRest()) {
                return compilePreserveRestReplacement(safeContract, replacement);
            }
            return notMigrated(safeContract);
        } catch (IllegalArgumentException unsafeOrUnsupportedContract) {
            return notMigratedWithoutTargets();
        }
    }

    private static RequestObligation compileAppendStatement(
            TaskContract contract,
            AppendStatementExpectation append
    ) {
        String target = append.targetPath();
        return complete(
                contract,
                APPEND_STATEMENT,
                NORMALIZED_PRIOR_CONTENT,
                new RequestObligation.OutputShape(
                        APPEND_LABELED_STATEMENT,
                        target,
                        append.label(),
                        append.statement()),
                List.of(
                        MATCHING_STATEMENT_PRESENT_ONCE,
                        MATCHING_STATEMENT_FINAL_LOGICAL_LINE,
                        STRUCTURED_MUTATION_EVIDENCE));
    }

    private static RequestObligation compileAppendSection(
            TaskContract contract,
            AppendSectionExpectation append
    ) {
        String target = append.targetPath();
        return complete(
                contract,
                APPEND_SECTION,
                NORMALIZED_PRIOR_CONTENT,
                new RequestObligation.OutputShape(
                        APPEND_MARKDOWN_SECTION,
                        target,
                        append.sectionName(),
                        append.expectedSentence()),
                List.of(
                        MATCHING_SECTION_PRESENT_ONCE,
                        MATCHING_SECTION_FINAL_DOCUMENT_SECTION,
                        STRUCTURED_MUTATION_EVIDENCE));
    }

    private static RequestObligation compilePreserveRestReplacement(
            TaskContract contract,
            ReplacementExpectation replacement
    ) {
        String target = replacement.targetPath();
        return complete(
                contract,
                REPLACE_TEXT_PRESERVE_REST,
                UNCHANGED_CONTENT_AROUND_REPLACEMENT,
                new RequestObligation.OutputShape(
                        REPLACE_TEXT,
                        target,
                        replacement.oldText(),
                        replacement.newText()),
                List.of(
                        REQUESTED_REPLACEMENT_EXACTLY_ONCE,
                        OLD_TEXT_ABSENT,
                        NEW_TEXT_PRESENT,
                        STRUCTURED_MUTATION_EVIDENCE));
    }

    private static RequestObligation complete(
            TaskContract contract,
            RequestObligation.ActionKind actionKind,
            RequestObligation.PreservationKind preservationKind,
            RequestObligation.OutputShape outputShape,
            List<RequestObligation.VerificationKind> verificationKinds
    ) {
        String target = outputShape.target();
        return new RequestObligation(
                RequestObligation.SCHEMA_VERSION,
                COMPLETE,
                DETERMINISTIC_RUNTIME_ALLOWED,
                actionKind,
                ONLY_REQUIRED_TARGETS,
                List.copyOf(contract.expectedTargets()),
                List.copyOf(contract.sourceEvidenceTargets()),
                List.copyOf(contract.forbiddenTargets()),
                List.of(new RequestObligation.PreservationConstraint(
                        preservationKind,
                        target)),
                outputShape,
                verificationKinds.stream()
                        .map(kind -> new RequestObligation.VerificationPostcondition(
                                kind,
                                target))
                        .toList());
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

    private static TaskExpectation completeExpectation(
            TaskContract contract,
            List<TaskExpectation> expectations
    ) {
        if (!contract.mutationRequested()
                || !contract.mutationAllowed()
                || !contract.verificationRequired()
                || contract.expectedTargets().size() != 1
                || !contract.sourceEvidenceTargets().isEmpty()
                || expectations.size() != 1) {
            return null;
        }
        TaskExpectation expectation = expectations.getFirst();
        if (!contract.expectedTargets().contains(expectation.targetPath())) return null;
        if (expectation instanceof AppendStatementExpectation
                || expectation instanceof AppendSectionExpectation
                || expectation instanceof ReplacementExpectation replacement
                && replacement.preserveRest()) {
            return expectation;
        }
        return null;
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
