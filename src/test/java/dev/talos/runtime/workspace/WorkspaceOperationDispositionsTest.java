package dev.talos.runtime.workspace;

import dev.talos.runtime.intent.TargetSource;
import dev.talos.runtime.intent.TaskIntentProjector;
import dev.talos.runtime.intent.WorkspaceOperationObligation;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.CapabilityPosture;
import dev.talos.runtime.policy.CapabilityPosturePolicy;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskResolution;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.tools.WorkspacePathIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Dispositions of requested workspace operations, derived through the real resolver (P14, RC2-I). */
class WorkspaceOperationDispositionsTest {
    private static final List<String> BLOCKED_REASONS = List.of(
            "workspace-operation-primary-content-authority-ambiguous",
            "workspace-operation-before-content-order-unsupported",
            "workspace-operation-count-not-one",
            "workspace-operation-cross-plan-overlap",
            "workspace-operation-owner-mismatch",
            "workspace-operation-not-fully-typed",
            "workspace-operation-mutation-not-allowed",
            "workspace-operation-operand-forbidden",
            "workspace-operation-unresolved-optional-mutation-target",
            "workspace-operation-replay-requires-typed-lifecycle",
            "workspace-operation-projection-removed-parser-anchor",
            "workspace-operation-projection-removed-content-predecessor");
    private static final String HEADER = "No file was changed in this turn. Only the workspace operations below were "
            + "assessed, and no other part of the request was answered.";

    @TempDir
    Path temp;

    @Test
    void aDeleteAfterAnUntypedContentEditIsWithheldWithTheAmbiguityReason() {
        WorkspaceOperationDispositions dispositions =
                dispositions("add a footer to index.html and then delete notes.md");

        assertEquals(List.of("WITHHELD DELETE_PATH delete notes.md"), rows(dispositions));
        assertEquals("Not performed: delete notes.md. Its order relative to the requested content change could not "
                + "be established.", dispositions.withheld().getFirst().notPerformedStatement());
        assertFalse(dispositions.answeredByRuntime());
    }

    @Test
    void twoTypedDeletesAreWithheldAndAnsweredByTheRuntime() {
        WorkspaceOperationDispositions dispositions = dispositions("delete a.md and delete b.md");

        assertEquals(List.of("WITHHELD DELETE_PATH delete a.md", "WITHHELD DELETE_PATH delete b.md"),
                rows(dispositions));
        assertTrue(dispositions.answeredByRuntime());
        assertEquals(HEADER + """

                Not performed: delete a.md. Only one workspace operation can run per request.
                Not performed: delete b.md. Only one workspace operation can run per request.""",
                dispositions.runtimeAnswer());
    }

    @Test
    void aRuntimeAnswerBesideUntypedTextSaysThatTheRestWasNotAnswered() {
        WorkspaceOperationDispositions question =
                dispositions("What is a markdown file? Then delete a.md and delete b.md.");
        WorkspaceOperationDispositions summary =
                dispositions("Summarize notes.md, then delete a.md, then delete b.md.");

        assertEquals(List.of("WITHHELD DELETE_PATH delete a.md", "WITHHELD DELETE_PATH delete b.md"),
                rows(question));
        assertTrue(question.answeredByRuntime());
        assertTrue(question.runtimeAnswer().startsWith(HEADER + "\n"), question.runtimeAnswer());
        assertEquals(List.of("WITHHELD DELETE_PATH delete a.md", "WITHHELD DELETE_PATH delete b.md"),
                rows(summary));
        // The parsers give the summary no read target, and the blocked surface holds no read tool, so a model could
        // not summarize notes.md truthfully. The runtime answer says that the summary was not answered.
        assertTrue(summary.answeredByRuntime());
        assertTrue(summary.runtimeAnswer().startsWith(HEADER + "\n"), summary.runtimeAnswer());
    }

    @Test
    void aWorkspaceDecisionWithoutATypedObligationIsNotStated() {
        // The parser bound only a.md here, and records the same kind of decision for a pasted command in a question.
        assertTrue(dispositions("delete a.md and b.md").isEmpty());
        assertTrue(dispositions("rm notes.txt gives permission denied. Why?").isEmpty());
    }

    @Test
    void anOperationBeforeAContentEditIsWithheldAndTheEditStillGoesToTheModel() {
        WorkspaceOperationDispositions dispositions = dispositions("delete notes.md and then update index.html");

        assertEquals(List.of("WITHHELD DELETE_PATH delete notes.md"), rows(dispositions));
        assertEquals("workspace-operation-before-content-order-unsupported",
                dispositions.withheld().getFirst().reason());
        assertFalse(dispositions.answeredByRuntime());
    }

    @Test
    void aForbiddenOperandGivesTheForbiddenReasonWhateverThePlanCounted() {
        WorkspaceOperationDispositions forbidden = dispositions("Delete notes.md. Don't touch notes.md.");
        WorkspaceOperationDispositions forbiddenCopy = dispositions("Copy a.md to b.md. Don't change b.md.");

        assertEquals(List.of("WITHHELD DELETE_PATH delete notes.md"), rows(forbidden));
        assertEquals("workspace-operation-operand-forbidden", forbidden.withheld().getFirst().reason());
        assertEquals(List.of("WITHHELD COPY_PATH copy a.md to b.md"), rows(forbiddenCopy));
        assertEquals("workspace-operation-operand-forbidden", forbiddenCopy.withheld().getFirst().reason());
    }

    @Test
    void anUnresolvedOptionalTargetWithholdsTheCopyAndKeepsTheModelTurn() {
        WorkspaceOperationDispositions dispositions =
                dispositions("Edit index.html and update style.css as needed, then copy a.md to b.md.");

        assertEquals(List.of("WITHHELD COPY_PATH copy a.md to b.md"), rows(dispositions));
        assertEquals("workspace-operation-unresolved-optional-mutation-target",
                dispositions.withheld().getFirst().reason());
        assertFalse(dispositions.answeredByRuntime());
    }

    @Test
    void anUntypedDecisionBesideATypedOneKeepsTheModelTurn() {
        WorkspaceOperationDispositions dispositions = dispositions("move a.md to old/a.md and copy b.md to c.md");

        assertEquals(List.of("WITHHELD COPY_PATH copy b.md to c.md"), rows(dispositions));
        // The move is recognized without operands, so a runtime answer would not name every operation.
        assertFalse(dispositions.answeredByRuntime());
    }

    @Test
    void aReadOnlyPostureStatesItsOperationsAsNotAllowedAndKeepsItsModelTurn() {
        WorkspaceOperationDispositions twoDeletes = planPosture("delete a.md and delete b.md");
        WorkspaceOperationDispositions editThenDelete =
                planPosture("Edit index.html to add a footer, then delete notes.md.");

        assertEquals(List.of("WITHHELD DELETE_PATH delete a.md", "WITHHELD DELETE_PATH delete b.md"),
                rows(twoDeletes));
        assertEquals(List.of("WITHHELD DELETE_PATH delete notes.md"), rows(editThenDelete));
        for (WorkspaceOperationDispositions dispositions : List.of(twoDeletes, editThenDelete)) {
            assertFalse(dispositions.answeredByRuntime());
            for (var disposition : dispositions.withheld()) {
                assertEquals("workspace-operation-mutation-not-allowed", disposition.reason());
                assertTrue(disposition.notPerformedStatement().endsWith("This turn does not allow changes."));
            }
        }
    }

    @Test
    void aConfirmedReplayStatesItsOperationsAndKeepsItsModelTurn() {
        WorkspaceOperationDispositions single = TaskResolution.confirmedReplay(
                TaskContractResolver.resolveFromUserRequest("delete notes.md")).workspaceOperationDispositions();
        WorkspaceOperationDispositions two = TaskResolution.confirmedReplay(
                TaskContractResolver.resolveFromUserRequest("delete a.md and delete b.md"))
                .workspaceOperationDispositions();

        assertEquals(List.of("WITHHELD DELETE_PATH delete notes.md"), rows(single));
        assertEquals("Not performed: delete notes.md. A confirmed replay cannot run a workspace operation.",
                single.withheld().getFirst().notPerformedStatement());
        assertFalse(single.answeredByRuntime());
        assertEquals(List.of("WITHHELD DELETE_PATH delete a.md", "WITHHELD DELETE_PATH delete b.md"), rows(two));
        assertFalse(two.answeredByRuntime());
    }

    @Test
    void anAdmittedOperationIsTheOnlyDisposition() {
        assertEquals(List.of("ADMITTED DELETE_PATH delete notes.md"), rows(dispositions("delete notes.md")));
        assertEquals(List.of("ADMITTED DELETE_PATH delete notes.md"),
                rows(dispositions("Edit index.html to add a footer, then delete notes.md.")));
    }

    @Test
    void requestsWithoutRequestedOperationsHaveNone() {
        assertTrue(dispositions("List the files.").isEmpty());
        // A non-authoritative mention is not a requested operation until RC2a separates mentions (plan, Precision).
        assertTrue(dispositions(
                "Add a footer that says Thanks for visiting to index.html and then delete notes.md.").isEmpty());
    }

    @Test
    void everyBlockedReasonIsStatedFromWhatTheMergeDecidedAndNeverAsFailed() {
        String request = "delete a.md and delete b.md";
        TaskResolution resolution = TaskContractResolver.resolveFromUserRequest(request);
        Set<String> sentences = new java.util.HashSet<>();
        for (String reason : BLOCKED_REASONS) {
            WorkspaceOperationDispositions dispositions = WorkspaceOperationDispositions.derive(
                    WorkspaceOperationAuthorityPlan.blocked(request, reason),
                    resolution.mutationTailPlan(),
                    resolution.sequentialMutationPlan(),
                    Set.of(),
                    true,
                    false);

            assertEquals(2, dispositions.withheld().size(), reason);
            for (var disposition : dispositions.withheld()) {
                assertEquals(reason, disposition.reason());
                String statement = disposition.notPerformedStatement();
                assertTrue(statement.startsWith("Not performed: delete "), statement);
                assertFalse(statement.toLowerCase(Locale.ROOT).contains("fail"), statement);
                assertFalse(statement.endsWith("It could not be authorized in this turn."), reason);
                // Typed operations are bound exactly, so no reason may claim that their operands were not.
                assertFalse(statement.contains("could not be bound"), statement);
            }
            sentences.add(WorkspaceOperationDispositions.reasonSentence(reason));
        }
        // Only the two projection reasons share their sentence.
        assertEquals(BLOCKED_REASONS.size() - 1, sentences.size(), sentences.toString());
        assertEquals("Both request parsers recognized workspace operations in this request, so none can run.",
                WorkspaceOperationDispositions.reasonSentence("workspace-operation-cross-plan-overlap"));
        assertEquals("The typed operation and its recognized clause come from different request parsers.",
                WorkspaceOperationDispositions.reasonSentence("workspace-operation-owner-mismatch"));
        assertEquals("The recognized operation clause could not be matched to exactly one typed operation.",
                WorkspaceOperationDispositions.reasonSentence("workspace-operation-not-fully-typed"));
    }

    @Test
    void aWithheldFolderIsSettledByAnyEffectOnItOrInsideIt() throws Exception {
        var mkdir = dispositions("create a folder named archive and then write archive/notes.md")
                .withheld().getFirst();
        Path workspace = Files.createDirectories(temp.resolve("workspace"));

        assertEquals(WorkspaceOperationObligation.Kind.MKDIR, mkdir.kind());
        assertTrue(WorkspaceOperationDispositions.settledByEffects(mkdir, List.of("archive"), null));
        assertTrue(WorkspaceOperationDispositions.settledByEffects(mkdir, List.of("archive/notes.md"), null));
        assertTrue(WorkspaceOperationDispositions.settledByEffects(mkdir, List.of("archive/deep/notes.md"), workspace));
        assertFalse(WorkspaceOperationDispositions.settledByEffects(mkdir, List.of("archive-old/notes.md"), workspace));
        assertFalse(WorkspaceOperationDispositions.settledByEffects(mkdir, List.of("notes.md"), workspace));
        assertFalse(WorkspaceOperationDispositions.settledByEffects(mkdir, List.of(), workspace));
        // A case variant settles it exactly when workspace identity may match the two spellings on this platform.
        Files.createDirectories(workspace.resolve("Archive"));
        assertEquals(WorkspacePathIdentity.mayMatch(workspace, "archive", "Archive"),
                WorkspaceOperationDispositions.settledByEffects(mkdir, List.of("Archive/notes.md"), workspace));
        assertFalse(WorkspaceOperationDispositions.settledByEffects(mkdir, List.of("Archive/notes.md"), null));
        var delete = dispositions("add a footer to index.html and then delete notes.md").withheld().getFirst();
        assertFalse(WorkspaceOperationDispositions.settledByEffects(delete, List.of("notes.md"), workspace));
    }

    @Test
    void theTurnPlanCarriesTheDispositionsOfItsResolution() {
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest("add a footer to index.html and then delete notes.md");

        CurrentTurnPlan plan = CurrentTurnPlan.createWithActionObligation(resolution, ExecutionPhase.APPLY,
                List.of(), List.of(), List.of(), null, null, null, null, null);

        assertEquals(resolution.workspaceOperationDispositions(), plan.workspaceOperationDispositions());
    }

    @Test
    void theTurnPlanRejectsDispositionsThatDifferFromItsAuthorityPlan() {
        String blockedRequest = "delete a.md and delete b.md";
        TaskResolution blocked = TaskContractResolver.resolveFromUserRequest(blockedRequest);
        var withheld = blocked.workspaceOperationDispositions().withheld().getFirst();
        var admittedUnderBlockedPlan = new WorkspaceOperationDispositions(List.of(new WorkspaceOperationDispositions
                .Disposition(WorkspaceOperationDispositions.State.ADMITTED, withheld.kind(),
                withheld.operands(), withheld.sourceStartOffset(), withheld.sourceEndOffset(), withheld.sourceText(),
                withheld.operationId(), "")), false);
        assertThrows(IllegalArgumentException.class,
                () -> plan(blocked, blocked.workspaceOperationPlan(), admittedUnderBlockedPlan));

        TaskResolution admitted = TaskContractResolver.resolveFromUserRequest("delete notes.md");
        String admittedRequest = admitted.contract().originalUserRequest();
        WorkspaceOperationDispositions withheldElsewhere = WorkspaceOperationDispositions.derive(
                WorkspaceOperationAuthorityPlan.blocked(admittedRequest, "workspace-operation-count-not-one"),
                admitted.mutationTailPlan(), admitted.sequentialMutationPlan(), Set.of(), true, false);
        assertThrows(IllegalArgumentException.class,
                () -> plan(admitted, admitted.workspaceOperationPlan(), withheldElsewhere));
        assertThrows(IllegalArgumentException.class,
                () -> plan(admitted, WorkspaceOperationAuthorityPlan.none(admittedRequest),
                        admitted.workspaceOperationDispositions()));
        // Dispositions of another request do not bind this one.
        assertThrows(IllegalArgumentException.class,
                () -> plan(admitted, admitted.workspaceOperationPlan(), blocked.workspaceOperationDispositions()));
    }

    private static CurrentTurnPlan plan(TaskResolution resolution, WorkspaceOperationAuthorityPlan plan,
                                        WorkspaceOperationDispositions dispositions) {
        return new CurrentTurnPlan(resolution.contract(), resolution.contract().originalUserRequest(),
                ExecutionPhase.APPLY, null, null, null, List.of(), List.of(), List.of(), null, null, null, null, null,
                null, plan, List.of(), null, null, dispositions);
    }

    private static WorkspaceOperationDispositions dispositions(String request) {
        return TaskContractResolver.resolveFromUserRequest(request).workspaceOperationDispositions();
    }

    /** The resolution of a plan-mode turn, projected the way the prompt inspector projects a read-only posture. */
    private static WorkspaceOperationDispositions planPosture(String request) {
        TaskResolution raw = TaskContractResolver.resolveFromUserRequest(request);
        var effective = CapabilityPosturePolicy.apply(CapabilityPosture.PLAN_READ_ONLY, raw.contract());
        var intent = TaskIntentProjector.project(raw.intent(), effective.taskContract(), TargetSource.RUNTIME_DEFAULT,
                "capability-posture-projection");
        return raw.projectOnto(effective.taskContract(), intent).workspaceOperationDispositions();
    }

    private static List<String> rows(WorkspaceOperationDispositions dispositions) {
        return dispositions.dispositions().stream()
                .map(disposition -> disposition.state() + " " + disposition.kind().name() + " "
                        + disposition.description())
                .toList();
    }
}
