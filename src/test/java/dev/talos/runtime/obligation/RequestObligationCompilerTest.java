package dev.talos.runtime.obligation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.runtime.expectation.TaskExpectation;
import dev.talos.runtime.expectation.TaskExpectationResolver;
import dev.talos.runtime.intent.TaskContractCompiler;
import dev.talos.runtime.intent.TaskIntentResolver;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.turn.CurrentTurnPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static dev.talos.runtime.obligation.RequestObligation.ActionKind.APPEND_STATEMENT;
import static dev.talos.runtime.obligation.RequestObligation.DerivationStatus.COMPLETE;
import static dev.talos.runtime.obligation.RequestObligation.DerivationStatus.NOT_MIGRATED;
import static dev.talos.runtime.obligation.RequestObligation.MutationScope.ONLY_REQUIRED_TARGETS;
import static dev.talos.runtime.obligation.RequestObligation.OutputKind.APPEND_LABELED_STATEMENT;
import static dev.talos.runtime.obligation.RequestObligation.PreservationKind.NORMALIZED_PRIOR_CONTENT;
import static dev.talos.runtime.obligation.RequestObligation.ResponseOwner.DETERMINISTIC_RUNTIME_ALLOWED;
import static dev.talos.runtime.obligation.RequestObligation.VerificationKind.MATCHING_STATEMENT_FINAL_LOGICAL_LINE;
import static dev.talos.runtime.obligation.RequestObligation.VerificationKind.MATCHING_STATEMENT_PRESENT_ONCE;
import static dev.talos.runtime.obligation.RequestObligation.VerificationKind.STRUCTURED_MUTATION_EVIDENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestObligationCompilerTest {

    private static final String F21_REQUEST =
            "Append Decision 004 to docs/decisions.md saying health checks remain read-only. "
                    + "Preserve Decisions 001 through 003 and change no code.";
    private static final String F16_REQUEST =
            "Append a Contributing section to README.md with one sentence saying "
                    + "Run tests before review. Preserve all current text.";
    private static final String F17_REQUEST =
            "In notes.md, change draft to final. If an exact edit fails, "
                    + "recover without deleting any other line.";

    @Test
    void f21CompilesTheCompleteDeterministicAppendContract() {
        TaskContract contract = TaskContractResolver.fromUserRequest(F21_REQUEST);
        List<TaskExpectation> expectations = TaskExpectationResolver.resolve(contract);

        RequestObligation obligation = RequestObligationCompiler.compile(contract);

        assertEquals(RequestObligation.SCHEMA_VERSION, obligation.schemaVersion());
        assertEquals(COMPLETE, obligation.derivationStatus());
        assertEquals(DETERMINISTIC_RUNTIME_ALLOWED, obligation.responseOwner());
        assertEquals(APPEND_STATEMENT, obligation.actionKind());
        assertEquals(ONLY_REQUIRED_TARGETS, obligation.mutationScope());
        assertEquals(List.of("docs/decisions.md"), obligation.requiredMutationTargets());
        assertEquals(List.of(), obligation.requiredSourceTargets());
        assertEquals(List.of(), obligation.forbiddenTargets());
        assertEquals(
                List.of(new RequestObligation.PreservationConstraint(
                        NORMALIZED_PRIOR_CONTENT,
                        "docs/decisions.md")),
                obligation.preservationConstraints());
        assertEquals(
                new RequestObligation.OutputShape(
                        APPEND_LABELED_STATEMENT,
                        "docs/decisions.md",
                        "Decision 004",
                        "health checks remain read-only"),
                obligation.outputShape());
        assertEquals(
                List.of(
                        new RequestObligation.VerificationPostcondition(
                                MATCHING_STATEMENT_FINAL_LOGICAL_LINE,
                                "docs/decisions.md"),
                        new RequestObligation.VerificationPostcondition(
                                MATCHING_STATEMENT_PRESENT_ONCE,
                                "docs/decisions.md"),
                        new RequestObligation.VerificationPostcondition(
                                STRUCTURED_MUTATION_EVIDENCE,
                                "docs/decisions.md")),
                obligation.verificationPostconditions());
    }

    @Test
    void f21CanonicalJsonRemainsByteIdenticalAfterTheSchemaV1EnumExtension() {
        RequestObligation obligation = RequestObligationCompiler.compile(
                TaskContractResolver.fromUserRequest(F21_REQUEST));

        assertEquals(
                "{\"actionKind\":\"APPEND_STATEMENT\","
                        + "\"derivationStatus\":\"COMPLETE\","
                        + "\"forbiddenTargets\":[],"
                        + "\"mutationScope\":\"ONLY_REQUIRED_TARGETS\","
                        + "\"outputShape\":{\"kind\":\"APPEND_LABELED_STATEMENT\","
                        + "\"label\":\"Decision 004\","
                        + "\"statement\":\"health checks remain read-only\","
                        + "\"target\":\"docs/decisions.md\"},"
                        + "\"preservationConstraints\":[{\"kind\":\"NORMALIZED_PRIOR_CONTENT\","
                        + "\"target\":\"docs/decisions.md\"}],"
                        + "\"requiredMutationTargets\":[\"docs/decisions.md\"],"
                        + "\"requiredSourceTargets\":[],"
                        + "\"responseOwner\":\"DETERMINISTIC_RUNTIME_ALLOWED\","
                        + "\"schemaVersion\":1,"
                        + "\"verificationPostconditions\":["
                        + "{\"kind\":\"MATCHING_STATEMENT_FINAL_LOGICAL_LINE\","
                        + "\"target\":\"docs/decisions.md\"},"
                        + "{\"kind\":\"MATCHING_STATEMENT_PRESENT_ONCE\","
                        + "\"target\":\"docs/decisions.md\"},"
                        + "{\"kind\":\"STRUCTURED_MUTATION_EVIDENCE\","
                        + "\"target\":\"docs/decisions.md\"}]}",
                obligation.canonicalJson());
    }

    @Test
    void f16CompilesOneExactAppendSectionObligation() {
        RequestObligation obligation = RequestObligationCompiler.compile(
                TaskContractResolver.fromUserRequest(F16_REQUEST));

        assertEquals(COMPLETE, obligation.derivationStatus());
        assertEquals(RequestObligation.ActionKind.APPEND_SECTION, obligation.actionKind());
        assertEquals(List.of("README.md"), obligation.requiredMutationTargets());
        assertTrue(obligation.requiredSourceTargets().isEmpty());
        assertEquals(
                new RequestObligation.OutputShape(
                        RequestObligation.OutputKind.APPEND_MARKDOWN_SECTION,
                        "README.md",
                        "Contributing",
                        "Run tests before review."),
                obligation.outputShape());
        assertEquals(
                List.of(new RequestObligation.PreservationConstraint(
                        NORMALIZED_PRIOR_CONTENT,
                        "README.md")),
                obligation.preservationConstraints());
        assertEquals(
                List.of(
                        new RequestObligation.VerificationPostcondition(
                                RequestObligation.VerificationKind
                                        .MATCHING_SECTION_FINAL_DOCUMENT_SECTION,
                                "README.md"),
                        new RequestObligation.VerificationPostcondition(
                                RequestObligation.VerificationKind
                                        .MATCHING_SECTION_PRESENT_ONCE,
                                "README.md"),
                        new RequestObligation.VerificationPostcondition(
                                STRUCTURED_MUTATION_EVIDENCE,
                                "README.md")),
                obligation.verificationPostconditions());
    }

    @Test
    void f17CompilesOneExactPreserveRestReplacementObligation() {
        RequestObligation obligation = RequestObligationCompiler.compile(
                TaskContractResolver.fromUserRequest(F17_REQUEST));

        assertEquals(COMPLETE, obligation.derivationStatus());
        assertEquals(
                RequestObligation.ActionKind.REPLACE_TEXT_PRESERVE_REST,
                obligation.actionKind());
        assertEquals(List.of("notes.md"), obligation.requiredMutationTargets());
        assertTrue(obligation.requiredSourceTargets().isEmpty());
        assertEquals(
                new RequestObligation.OutputShape(
                        RequestObligation.OutputKind.REPLACE_TEXT,
                        "notes.md",
                        "draft",
                        "final"),
                obligation.outputShape());
        assertEquals(
                List.of(new RequestObligation.PreservationConstraint(
                        RequestObligation.PreservationKind
                                .UNCHANGED_CONTENT_AROUND_REPLACEMENT,
                        "notes.md")),
                obligation.preservationConstraints());
        assertEquals(
                List.of(
                        new RequestObligation.VerificationPostcondition(
                                RequestObligation.VerificationKind.NEW_TEXT_PRESENT,
                                "notes.md"),
                        new RequestObligation.VerificationPostcondition(
                                RequestObligation.VerificationKind.OLD_TEXT_ABSENT,
                                "notes.md"),
                        new RequestObligation.VerificationPostcondition(
                                RequestObligation.VerificationKind
                                        .REQUESTED_REPLACEMENT_EXACTLY_ONCE,
                                "notes.md"),
                        new RequestObligation.VerificationPostcondition(
                                STRUCTURED_MUTATION_EVIDENCE,
                                "notes.md")),
                obligation.verificationPostconditions());
    }

    @Test
    void replacementWithoutExplicitPreservationRemainsNotMigrated() {
        RequestObligation obligation = RequestObligationCompiler.compile(
                TaskContractResolver.fromUserRequest(
                        "In notes.md, replace draft with final."));

        assertEquals(NOT_MIGRATED, obligation.derivationStatus());
    }

    @Test
    void newCompleteShapesRejectMultipleMutationTargetsAndSourceTargets() {
        TaskContract multipleTargets = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("README.md", "other.md"),
                Set.of(),
                Set.of(),
                F16_REQUEST,
                "test");
        TaskContract sourceBearing = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("notes.md"),
                Set.of("source.md"),
                Set.of(),
                F17_REQUEST,
                "test");

        assertEquals(
                NOT_MIGRATED,
                RequestObligationCompiler.compile(multipleTargets).derivationStatus());
        assertEquals(
                NOT_MIGRATED,
                RequestObligationCompiler.compile(sourceBearing).derivationStatus());
    }

    @Test
    void unsupportedExpectationIsExplicitlyNotMigratedWithoutCollapsingTargetRoles() {
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("shared.txt", "target.txt"),
                Set.of("shared.txt", "source.txt"),
                Set.of("forbidden.txt"),
                "Replace beta with BETA in target.txt.",
                "test");

        RequestObligation obligation = RequestObligationCompiler.compile(contract);

        assertEquals(NOT_MIGRATED, obligation.derivationStatus());
        assertEquals(List.of("shared.txt", "target.txt"), obligation.requiredMutationTargets());
        assertEquals(List.of("shared.txt", "source.txt"), obligation.requiredSourceTargets());
        assertEquals(List.of("forbidden.txt"), obligation.forbiddenTargets());
        assertTrue(obligation.requiredMutationTargets().contains("shared.txt"));
        assertTrue(obligation.requiredSourceTargets().contains("shared.txt"));
    }

    @Test
    void overlappingLegacyContractRetainsCompositeDimensionsThroughSharedObligation() {
        TaskContract legacy = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("styles.css"),
                Set.of("styles.css"),
                Set.of(),
                "Read styles.css, then update styles.css.",
                "overlap-round-trip");

        TaskContract projected = TaskContractCompiler.compile(
                TaskIntentResolver.fromLegacyContract(legacy));
        RequestObligation obligation = RequestObligationCompiler.compile(projected);

        assertEquals(NOT_MIGRATED, obligation.derivationStatus());
        assertEquals(List.of("styles.css"), obligation.requiredMutationTargets());
        assertEquals(List.of("styles.css"), obligation.requiredSourceTargets());
        assertTrue(obligation.canonicalJson().contains(
                "\"requiredMutationTargets\":[\"styles.css\"]"));
        assertTrue(obligation.canonicalJson().contains(
                "\"requiredSourceTargets\":[\"styles.css\"]"));
    }

    @Test
    void canonicalFormSortsAndDeduplicatesTheSupportedAppendCollections() throws Exception {
        RequestObligation first = new RequestObligation(
                RequestObligation.SCHEMA_VERSION,
                COMPLETE,
                DETERMINISTIC_RUNTIME_ALLOWED,
                APPEND_STATEMENT,
                ONLY_REQUIRED_TARGETS,
                List.of("z.md", "z.md"),
                List.of(),
                List.of("forbidden-b.txt", "forbidden-a.txt", "forbidden-b.txt"),
                List.of(
                        new RequestObligation.PreservationConstraint(
                                NORMALIZED_PRIOR_CONTENT,
                                "z.md"),
                        new RequestObligation.PreservationConstraint(
                                NORMALIZED_PRIOR_CONTENT,
                                "z.md")),
                new RequestObligation.OutputShape(
                        APPEND_LABELED_STATEMENT,
                        "z.md",
                        "Decision 004",
                        "health checks remain read-only"),
                List.of(
                        new RequestObligation.VerificationPostcondition(
                                STRUCTURED_MUTATION_EVIDENCE,
                                "z.md"),
                        new RequestObligation.VerificationPostcondition(
                                MATCHING_STATEMENT_PRESENT_ONCE,
                                "z.md"),
                        new RequestObligation.VerificationPostcondition(
                                MATCHING_STATEMENT_FINAL_LOGICAL_LINE,
                                "z.md"),
                        new RequestObligation.VerificationPostcondition(
                                MATCHING_STATEMENT_PRESENT_ONCE,
                                "z.md")));
        RequestObligation second = new RequestObligation(
                RequestObligation.SCHEMA_VERSION,
                COMPLETE,
                DETERMINISTIC_RUNTIME_ALLOWED,
                APPEND_STATEMENT,
                ONLY_REQUIRED_TARGETS,
                List.of("z.md"),
                List.of(),
                List.of("forbidden-a.txt", "forbidden-b.txt"),
                List.of(new RequestObligation.PreservationConstraint(
                        NORMALIZED_PRIOR_CONTENT,
                        "z.md")),
                new RequestObligation.OutputShape(
                        APPEND_LABELED_STATEMENT,
                        "z.md",
                        "Decision 004",
                        "health checks remain read-only"),
                List.of(
                        new RequestObligation.VerificationPostcondition(
                                MATCHING_STATEMENT_FINAL_LOGICAL_LINE,
                                "z.md"),
                        new RequestObligation.VerificationPostcondition(
                                MATCHING_STATEMENT_PRESENT_ONCE,
                                "z.md"),
                        new RequestObligation.VerificationPostcondition(
                                STRUCTURED_MUTATION_EVIDENCE,
                                "z.md")));

        assertEquals(second, first);
        assertEquals(second.canonicalJson(), first.canonicalJson());
        assertEquals(second.canonicalSha256(), first.canonicalSha256());
        assertEquals(second, new ObjectMapper().readValue(
                first.canonicalJson(),
                RequestObligation.class));
        assertThrows(UnsupportedOperationException.class,
                () -> first.requiredMutationTargets().add("later.md"));
        assertThrows(UnsupportedOperationException.class,
                () -> first.preservationConstraints().clear());
    }

    @Test
    void rejectsContradictoryAndCrossTargetConstraints() {
        assertThrows(IllegalArgumentException.class, () -> new RequestObligation(
                RequestObligation.SCHEMA_VERSION,
                COMPLETE,
                DETERMINISTIC_RUNTIME_ALLOWED,
                APPEND_STATEMENT,
                ONLY_REQUIRED_TARGETS,
                List.of("same.md"),
                List.of(),
                List.of("same.md"),
                List.of(new RequestObligation.PreservationConstraint(
                        NORMALIZED_PRIOR_CONTENT,
                        "same.md")),
                new RequestObligation.OutputShape(
                        APPEND_LABELED_STATEMENT,
                        "same.md",
                        "Decision 004",
                        "health checks remain read-only"),
                List.of(new RequestObligation.VerificationPostcondition(
                        STRUCTURED_MUTATION_EVIDENCE,
                        "same.md"))));

        assertThrows(IllegalArgumentException.class, () -> new RequestObligation(
                RequestObligation.SCHEMA_VERSION,
                COMPLETE,
                DETERMINISTIC_RUNTIME_ALLOWED,
                APPEND_STATEMENT,
                ONLY_REQUIRED_TARGETS,
                List.of("target.md"),
                List.of(),
                List.of(),
                List.of(new RequestObligation.PreservationConstraint(
                        NORMALIZED_PRIOR_CONTENT,
                        "other.md")),
                new RequestObligation.OutputShape(
                        APPEND_LABELED_STATEMENT,
                        "target.md",
                        "Decision 004",
                        "health checks remain read-only"),
                List.of(new RequestObligation.VerificationPostcondition(
                        STRUCTURED_MUTATION_EVIDENCE,
                        "target.md"))));
    }

    @Test
    void completeAppendSchemaRejectsMultipleTargetsAndUnverifiedSourceCoverage() {
        assertThrows(IllegalArgumentException.class, () -> new RequestObligation(
                RequestObligation.SCHEMA_VERSION,
                COMPLETE,
                DETERMINISTIC_RUNTIME_ALLOWED,
                APPEND_STATEMENT,
                ONLY_REQUIRED_TARGETS,
                List.of("a.md", "b.md"),
                List.of(),
                List.of(),
                List.of(new RequestObligation.PreservationConstraint(
                        NORMALIZED_PRIOR_CONTENT,
                        "a.md")),
                new RequestObligation.OutputShape(
                        APPEND_LABELED_STATEMENT,
                        "a.md",
                        "Decision 004",
                        "health checks remain read-only"),
                List.of(
                        new RequestObligation.VerificationPostcondition(
                                MATCHING_STATEMENT_PRESENT_ONCE,
                                "a.md"),
                        new RequestObligation.VerificationPostcondition(
                                MATCHING_STATEMENT_FINAL_LOGICAL_LINE,
                                "a.md"),
                        new RequestObligation.VerificationPostcondition(
                                STRUCTURED_MUTATION_EVIDENCE,
                                "a.md"))));

        TaskContract sourceBearing = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("docs/decisions.md"),
                Set.of("docs/source.md"),
                Set.of(),
                F21_REQUEST,
                "test");
        RequestObligation obligation = RequestObligationCompiler.compile(sourceBearing);
        assertEquals(NOT_MIGRATED, obligation.derivationStatus());
        assertEquals(List.of("docs/source.md"), obligation.requiredSourceTargets());
    }

    @Test
    void rejectsUnsafeNonRelativeTargetForms() {
        List<String> unsafeTargets = List.of(
                "",
                ".",
                "..",
                "./target.md",
                "docs/../target.md",
                "docs/./target.md",
                "docs//target.md",
                "docs/",
                "/workspace/target.md",
                "\\workspace\\target.md",
                "C:\\workspace\\target.md",
                "C:target.md",
                "\\\\server\\share\\target.md");

        for (String unsafeTarget : unsafeTargets) {
            assertThrows(IllegalArgumentException.class, () -> notMigratedWithMutationTarget(
                    unsafeTarget), unsafeTarget);
        }
    }

    @Test
    void currentTurnPlanCapturesOneObligationFromTheSameExpectationList() {
        TaskContract contract = TaskContractResolver.fromUserRequest(F21_REQUEST);

        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.APPLY,
                List.of("talos.edit_file"),
                List.of("talos.edit_file"),
                List.of());

        assertEquals(COMPLETE, plan.requestObligation().derivationStatus());
        assertEquals(APPEND_STATEMENT, plan.requestObligation().actionKind());
        assertEquals(1, plan.taskExpectations().size());
        assertEquals(
                plan.taskExpectations().getFirst().targetPath(),
                plan.requestObligation().outputShape().target());
        assertEquals(
                plan.taskExpectations().getFirst().kind(),
                plan.requestObligation().actionKind().name());
    }

    @Test
    void oldCurrentTurnPlanConstructorCompilesRatherThanDroppingTheObligation() {
        TaskContract contract = TaskContractResolver.fromUserRequest(F21_REQUEST);
        List<TaskExpectation> expectations = TaskExpectationResolver.resolve(contract);

        CurrentTurnPlan plan = new CurrentTurnPlan(
                contract,
                contract.originalUserRequest(),
                ExecutionPhase.APPLY,
                ExecutionPhase.APPLY,
                null,
                expectations,
                List.of("talos.edit_file"),
                List.of("talos.edit_file"),
                List.of(),
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED);

        assertEquals(COMPLETE, plan.requestObligation().derivationStatus());
        assertFalse(plan.canonicalRequestObligationHash().isBlank());
        assertSame(plan.requestObligation(), plan.requestObligation());
    }

    @Test
    void currentTurnPlanRejectsASecondConflictingObligationAuthority() {
        TaskContract contract = TaskContractResolver.fromUserRequest(F21_REQUEST);
        List<TaskExpectation> expectations = TaskExpectationResolver.resolve(contract);

        assertThrows(IllegalArgumentException.class, () -> new CurrentTurnPlan(
                contract,
                contract.originalUserRequest(),
                ExecutionPhase.APPLY,
                ExecutionPhase.APPLY,
                null,
                expectations,
                List.of("talos.edit_file"),
                List.of("talos.edit_file"),
                List.of(),
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                RequestObligationCompiler.notMigrated(contract)));
    }

    @Test
    void currentTurnPlanRejectsCallerObligationThatOmitsRequiredSourceDimension() {
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("styles.css"),
                Set.of("styles.css"),
                Set.of(),
                "Read styles.css, then update styles.css.",
                "required-post-read-mutation");

        RequestObligation sourceFreeForgery =
                notMigratedWithMutationTarget("styles.css");

        assertThrows(IllegalArgumentException.class, () -> new CurrentTurnPlan(
                contract,
                contract.originalUserRequest(),
                ExecutionPhase.APPLY,
                ExecutionPhase.APPLY,
                null,
                List.of(),
                List.of("talos.read_file", "talos.edit_file"),
                List.of("talos.read_file", "talos.edit_file"),
                List.of(),
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                sourceFreeForgery));
    }

    @Test
    void currentTurnPlanRejectsCallerForgedTaskExpectations() {
        TaskContract contract = TaskContractResolver.fromUserRequest(F21_REQUEST);
        TaskExpectation forged = new dev.talos.runtime.expectation.AppendStatementExpectation(
                "docs/decisions.md",
                "Decision 999",
                "mutations remain unrestricted",
                "forged");

        assertThrows(IllegalArgumentException.class, () -> new CurrentTurnPlan(
                contract,
                contract.originalUserRequest(),
                ExecutionPhase.APPLY,
                ExecutionPhase.APPLY,
                null,
                List.of(forged),
                List.of("talos.edit_file"),
                List.of("talos.edit_file"),
                List.of(),
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                null));
    }

    @Test
    void unsafeContractTargetProducesNonAuthoritativeEmptyObligationInsteadOfCrashingPlan() {
        TaskContract unsafe = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("../outside.txt"),
                Set.of(),
                Set.of(),
                "Write ../outside.txt.",
                "test");

        RequestObligation obligation = RequestObligationCompiler.compile(unsafe);

        assertEquals(NOT_MIGRATED, obligation.derivationStatus());
        assertTrue(obligation.requiredMutationTargets().isEmpty());
    }

    @Test
    void currentTurnPlanStoresItsOwnCompiledInstanceWhenSuppliedValueMatches() {
        TaskContract contract = TaskContractResolver.fromUserRequest(F21_REQUEST);
        List<TaskExpectation> expectations = TaskExpectationResolver.resolve(contract);
        RequestObligation supplied = RequestObligationCompiler.compile(contract);

        CurrentTurnPlan plan = new CurrentTurnPlan(
                contract,
                contract.originalUserRequest(),
                ExecutionPhase.APPLY,
                ExecutionPhase.APPLY,
                null,
                expectations,
                List.of("talos.edit_file"),
                List.of("talos.edit_file"),
                List.of(),
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                supplied);

        assertEquals(supplied, plan.requestObligation());
        assertNotSame(supplied, plan.requestObligation());
    }

    private static RequestObligation notMigratedWithMutationTarget(String target) {
        return new RequestObligation(
                RequestObligation.SCHEMA_VERSION,
                NOT_MIGRATED,
                RequestObligation.ResponseOwner.UNKNOWN,
                RequestObligation.ActionKind.NONE,
                RequestObligation.MutationScope.NONE,
                List.of(target),
                List.of(),
                List.of(),
                List.of(),
                RequestObligation.OutputShape.none(),
                List.of());
    }
}
