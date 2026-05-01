package dev.talos.runtime.context;

import dev.talos.cli.repl.Result;
import dev.talos.runtime.TurnAudit;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.TurnResult;
import dev.talos.runtime.trace.LocalTurnTrace;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ActiveTaskContextUpdaterTest {

    private final ActiveTaskContextUpdater updater = new ActiveTaskContextUpdater();

    @Test
    void proposalOnlyTurnCreatesProposedChangesContextFromExpectedTargets() {
        TurnResult result = turn(
                7,
                new Result.Ok("I would update the README title and usage section."),
                policy("READ_ONLY_QA", false, false, List.of("README.md")),
                trace(7, "trace-proposal", false, false, List.of("README.md"),
                        "", "", "", "NOT_REQUESTED", ""),
                List.of(),
                0);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result,
                "Do not edit README.md yet. Propose the changes first.",
                ActiveTaskContext.none(),
                ArtifactGoal.none());

        ActiveTaskContext context = update.activeTaskContext();
        assertEquals(ActiveTaskContext.State.ACTIVE, context.state());
        assertEquals(ActiveTaskContext.Kind.PROPOSED_CHANGES, context.kind());
        assertEquals(ActiveTaskContext.Operation.APPLY_EDIT, context.operation());
        assertEquals(7, context.sourceTurnNumber());
        assertEquals("trace-proposal", context.sourceTraceId());
        assertEquals(List.of("README.md"), context.targets());
        assertTrue(context.proposalSummary().contains("README title"));
        assertEquals(ArtifactGoal.Source.ACTIVE_CONTEXT, update.artifactGoal().source());
        assertEquals(ArtifactGoal.ArtifactKind.README, update.artifactGoal().artifactKind());
    }

    @Test
    void approvalDeniedMutationCreatesDeniedMutationContext() {
        TurnResult result = turn(
                8,
                new Result.Ok("No files were changed because approval was denied."),
                policy("FILE_EDIT", true, true, List.of("index.html")),
                trace(8, "trace-denied", true, true, List.of("index.html"),
                        "", "", "DENIED", "DENIED", "BLOCKED_BY_APPROVAL"),
                List.of(new TurnRecord.ToolCallSummary(
                        "talos.edit_file",
                        "index.html",
                        false,
                        "approval denied by user for talos.edit_file")),
                1);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result,
                "Update index.html.",
                ActiveTaskContext.none(),
                ArtifactGoal.none());

        ActiveTaskContext context = update.activeTaskContext();
        assertEquals(ActiveTaskContext.State.ACTIVE, context.state());
        assertEquals(ActiveTaskContext.Kind.DENIED_MUTATION, context.kind());
        assertEquals(ActiveTaskContext.Operation.APPLY_EDIT, context.operation());
        assertEquals("NO_FILES_CHANGED", context.previousOutcomeStatus());
        assertTrue(context.blockedReason().contains("approval denied"));
        assertEquals(List.of("index.html"), context.targets());
        assertEquals(ArtifactGoal.Source.ACTIVE_CONTEXT, update.artifactGoal().source());
    }

    @Test
    void failedVerificationCreatesRepairContextWithFindings() {
        TurnResult result = turn(
                9,
                new Result.Ok("Static verification failed."),
                policy("FILE_EDIT", true, true, List.of("index.html")),
                trace(9, "trace-failed-verification", true, true, List.of("index.html"),
                        "FAILED", "Missing #app root", "GRANTED_OR_NOT_REQUIRED", "SUCCEEDED", "FAILED"),
                List.of(new TurnRecord.ToolCallSummary("talos.edit_file", "index.html", true, "")),
                0);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result,
                "Update index.html.",
                ActiveTaskContext.none(),
                ArtifactGoal.none());

        ActiveTaskContext context = update.activeTaskContext();
        assertEquals(ActiveTaskContext.State.ACTIVE, context.state());
        assertEquals(ActiveTaskContext.Kind.VERIFIER_FINDINGS, context.kind());
        assertEquals(ActiveTaskContext.Operation.REPAIR, context.operation());
        assertEquals(List.of("index.html"), context.targets());
        assertEquals(List.of("Missing #app root"), context.verifierFindings());
        assertEquals("FAILED", context.previousOutcomeStatus());
        assertEquals(ArtifactGoal.Source.ACTIVE_CONTEXT, update.artifactGoal().source());
    }

    @Test
    void successfulMutationWithPassingVerificationClearsExistingContextAndGoal() {
        ActiveTaskContext previous = ActiveTaskContext.proposedChanges(
                6, "trace-old", List.of("index.html"), "Change the hero.");
        ArtifactGoal previousGoal = ArtifactGoal.fromActiveContext(previous);
        TurnResult result = turn(
                10,
                new Result.Ok("Done."),
                policy("FILE_EDIT", true, true, List.of("index.html")),
                trace(10, "trace-success", true, true, List.of("index.html"),
                        "PASSED", "All checks passed", "GRANTED_OR_NOT_REQUIRED", "SUCCEEDED", "COMPLETED_VERIFIED"),
                List.of(new TurnRecord.ToolCallSummary("talos.edit_file", "index.html", true, "")),
                0);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result,
                "Apply those changes.",
                previous,
                previousGoal);

        assertEquals(ActiveTaskContext.none(), update.activeTaskContext());
        assertEquals(ArtifactGoal.none(), update.artifactGoal());
    }

    @Test
    void unrelatedTurnPreservesExistingContextAndGoal() {
        ActiveTaskContext previous = ActiveTaskContext.proposedChanges(
                6, "trace-old", List.of("README.md"), "Improve README.");
        ArtifactGoal previousGoal = ArtifactGoal.fromActiveContext(previous);
        TurnResult result = turn(
                11,
                new Result.Ok("Hello."),
                policy("SMALL_TALK", false, false, List.of()),
                trace(11, "trace-chat", false, false, List.of(),
                        "", "", "", "NOT_REQUESTED", "READ_ONLY_ANSWERED"),
                List.of(),
                0);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result,
                "hi",
                previous,
                previousGoal);

        assertSame(previous, update.activeTaskContext());
        assertSame(previousGoal, update.artifactGoal());
    }

    private static TurnResult turn(
            int turnNumber,
            Result result,
            TurnPolicyTrace policyTrace,
            LocalTurnTrace localTrace,
            List<TurnRecord.ToolCallSummary> calls,
            int approvalsDenied) {
        return new TurnResult(
                result,
                null,
                turnNumber,
                Duration.ofMillis(25),
                new TurnAudit(calls, approvalsDenied, 0, approvalsDenied, policyTrace, localTrace));
    }

    private static TurnPolicyTrace policy(
            String taskType,
            boolean mutationAllowed,
            boolean verificationRequired,
            List<String> expectedTargets) {
        return new TurnPolicyTrace(
                taskType,
                mutationAllowed,
                verificationRequired,
                expectedTargets,
                List.of(),
                mutationAllowed ? "APPLY" : "INSPECT",
                mutationAllowed ? "APPLY" : "INSPECT",
                List.of(),
                List.of(),
                List.of());
    }

    private static LocalTurnTrace trace(
            int turnNumber,
            String traceId,
            boolean mutationAllowed,
            boolean verificationRequired,
            List<String> expectedTargets,
            String verificationStatus,
            String verificationProblem,
            String approvalStatus,
            String mutationStatus,
            String classification) {
        List<String> problems = verificationProblem == null || verificationProblem.isBlank()
                ? List.of()
                : List.of(verificationProblem);
        return LocalTurnTrace.builder(traceId, "session", turnNumber, "2026-05-01T00:00:00Z")
                .taskContract(new LocalTurnTrace.TaskContractSummary(
                        mutationAllowed ? "FILE_EDIT" : "READ_ONLY_QA",
                        mutationAllowed,
                        verificationRequired,
                        mutationAllowed,
                        expectedTargets,
                        List.of()))
                .verification(verificationStatus, verificationProblem, problems)
                .outcome(classification, verificationStatus, approvalStatus, mutationStatus, classification)
                .build();
    }
}
