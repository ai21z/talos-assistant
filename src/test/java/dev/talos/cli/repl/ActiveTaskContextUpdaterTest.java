package dev.talos.cli.repl;

import dev.talos.runtime.Result;

import dev.talos.runtime.TurnAudit;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.TurnResult;
import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.context.ArtifactGoal;
import dev.talos.runtime.task.StaticWebRequirements;
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
    void failedStaticWebInteractionVerificationStoresRequiredClaimForRepair() {
        String request = "Create a synthwave website with a button with id teaser-button "
                + "that updates visible text in #teaser-status when clicked.";
        TurnResult result = turn(
                9,
                new Result.Ok("Static verification failed."),
                policy("FILE_CREATE", true, true, List.of("index.html", "styles.css", "scripts.js")),
                trace(9, "trace-failed-interaction", true, true,
                        List.of("index.html", "styles.css", "scripts.js"),
                        "FAILED",
                        "scripts.js: JavaScript syntax check failed at line 4",
                        "GRANTED_OR_NOT_REQUIRED",
                        "SUCCEEDED",
                        "FAILED",
                        1,
                        1,
                        List.of("STATIC_INTERACTION_GUARD"),
                        List.of("Browser behavior verifier observed JavaScript error.")),
                List.of(new TurnRecord.ToolCallSummary("talos.write_file", "scripts.js", true, "")),
                0);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result,
                request,
                ActiveTaskContext.none(),
                ArtifactGoal.none());

        ActiveTaskContext context = update.activeTaskContext();
        assertEquals(ActiveTaskContext.Kind.VERIFIER_FINDINGS, context.kind());
        assertEquals(1, context.requiredVerificationClaims().size());
        ActiveTaskContext.RequiredVerificationClaim claim = context.requiredVerificationClaims().getFirst();
        assertEquals("#teaser-button", claim.triggerSelector());
        assertEquals("#teaser-status", claim.outputSelector());
        assertEquals("click", claim.eventType());
        assertTrue(context.renderForPlan().contains("#teaser-button"), context.renderForPlan());
        assertTrue(context.renderForPlan().contains("#teaser-status"), context.renderForPlan());
    }

    @Test
    void repairPromptConsumesVerifierContextAndCarriesRequiredClaimIntoContract() {
        ActiveTaskContext previous = ActiveTaskContext.verifierFindings(
                9,
                "trace-failed-interaction",
                List.of("index.html", "styles.css", "scripts.js"),
                List.of("scripts.js: JavaScript syntax check failed at line 4"),
                "FAILED",
                List.of(new ActiveTaskContext.RequiredVerificationClaim(
                        "static-web-interaction:#teaser-button->#teaser-status",
                        "Static interaction #teaser-button -> #teaser-status.",
                        "STATIC_INTERACTION_GUARD",
                        "#teaser-button",
                        "#teaser-status",
                        "click")));

        var rawContract = dev.talos.runtime.task.TaskContractResolver.fromUserRequest(
                "Fix the remaining static verification problems and make the existing Neon Voltage site verified. "
                        + "Keep exactly index.html, styles.css, and scripts.js; do not create any other files.");

        var decision = dev.talos.runtime.context.ActiveTaskContextPolicy.evaluate(
                rawContract.originalUserRequest(),
                rawContract,
                previous,
                ArtifactGoal.fromActiveContext(previous),
                10);

        assertTrue(decision.consumed());
        assertTrue(decision.taskContract().originalUserRequest().contains("#teaser-button"),
                decision.taskContract().originalUserRequest());
        assertTrue(decision.taskContract().originalUserRequest().contains("#teaser-status"),
                decision.taskContract().originalUserRequest());
    }

    @Test
    void successfulMutationWithPassingVerificationClearsExistingContextAndGoal() {
        ActiveTaskContext previous = ActiveTaskContext.proposedChanges(
                6, "trace-old", List.of("README.md"), "Change the title.");
        ArtifactGoal previousGoal = ArtifactGoal.fromActiveContext(previous);
        TurnResult result = turn(
                10,
                new Result.Ok("Done."),
                policy("FILE_EDIT", true, true, List.of("README.md")),
                trace(10, "trace-success", true, true, List.of("README.md"),
                        "PASSED", "All checks passed", "GRANTED_OR_NOT_REQUIRED", "SUCCEEDED", "COMPLETED_VERIFIED"),
                List.of(new TurnRecord.ToolCallSummary("talos.edit_file", "README.md", true, "")),
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
    void successfulStaticWebMutationWithPassingVerificationKeepsDurableSurfaceContext() {
        TurnResult result = turn(
                10,
                new Result.Ok("Done."),
                policy("FILE_EDIT", true, true, List.of("index.html", "style.css", "script.js")),
                trace(10, "trace-static-success", true, true, List.of("index.html", "style.css", "script.js"),
                        "PASSED", "All checks passed", "GRANTED_OR_NOT_REQUIRED", "SUCCEEDED", "COMPLETED_VERIFIED"),
                List.of(
                        new TurnRecord.ToolCallSummary("talos.write_file", "index.html", true, ""),
                        new TurnRecord.ToolCallSummary("talos.write_file", "style.css", true, ""),
                        new TurnRecord.ToolCallSummary("talos.write_file", "script.js", true, "")),
                0);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result,
                "Create a synthwave band website.",
                ActiveTaskContext.none(),
                ArtifactGoal.none());

        assertEquals(ActiveTaskContext.Kind.VERIFIED_MUTATION, update.activeTaskContext().kind());
        assertEquals(List.of("index.html", "style.css", "script.js"), update.activeTaskContext().targets());
        assertEquals(ArtifactGoal.ArtifactKind.STATIC_WEB, update.artifactGoal().artifactKind());
    }

    @Test
    void successfulStaticWebMutationWithReadbackOnlyVerificationKeepsDurableSurfaceContext() {
        TurnResult result = turn(
                12,
                new Result.Ok("Done."),
                policy("FILE_EDIT", true, true, List.of("index.html", "style.css", "script.js")),
                trace(12, "trace-static-unverified", true, true, List.of("index.html", "style.css", "script.js"),
                        "READBACK_ONLY", "", "GRANTED_OR_NOT_REQUIRED", "SUCCEEDED", "COMPLETED_UNVERIFIED"),
                List.of(
                        new TurnRecord.ToolCallSummary("talos.write_file", "index.html", true, ""),
                        new TurnRecord.ToolCallSummary("talos.write_file", "style.css", true, ""),
                        new TurnRecord.ToolCallSummary("talos.write_file", "script.js", true, "")),
                0);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result,
                "ok just edit the site to look better",
                ActiveTaskContext.none(),
                ArtifactGoal.none());

        assertEquals(ActiveTaskContext.Kind.PARTIAL_MUTATION, update.activeTaskContext().kind());
        assertEquals(List.of("index.html", "style.css", "script.js"), update.activeTaskContext().targets());
        assertEquals(ArtifactGoal.ArtifactKind.STATIC_WEB, update.artifactGoal().artifactKind());
    }

    @Test
    void failedNoMutationStaticWebCreationCreatesPendingContextWithRequirements() {
        String request = "Create a complete Retrocats website. Use exactly index.html, style.css, and script.js. "
                + "Do not create a local tailwind.min.css file. "
                + "The site must preserve these required visible facts: Retrocats, Costanza, Berlin 22 July 2026.";
        TurnResult result = turn(
                13,
                new Result.Ok("[Action obligation failed: no file writes completed.]"),
                policy("FILE_CREATE", true, true,
                        List.of("index.html", "style.css", "script.js"),
                        List.of("tailwind.min.css")),
                trace(13, "trace-pending-static", true, true,
                        List.of("index.html", "style.css", "script.js"),
                        "NOT_RUN", "", "GRANTED_OR_NOT_REQUIRED", "NOT_REQUESTED", "BLOCKED_BY_POLICY"),
                List.of(),
                0);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result,
                request,
                ActiveTaskContext.none(),
                ArtifactGoal.none());

        ActiveTaskContext context = update.activeTaskContext();
        assertEquals(ActiveTaskContext.Kind.PENDING_MUTATION, context.kind());
        assertEquals(ActiveTaskContext.Operation.CREATE, context.operation());
        assertEquals(List.of("index.html", "style.css", "script.js"), context.targets());
        StaticWebRequirements requirements = context.staticWebRequirements();
        assertTrue(requirements.requiredVisibleFacts().contains("Costanza"), requirements.toString());
        assertEquals(java.util.Set.of("tailwind.min.css"), requirements.forbiddenArtifacts());
        assertEquals(ArtifactGoal.ArtifactKind.STATIC_WEB, update.artifactGoal().artifactKind());
    }

    @Test
    void successfulMutationWithNotRunVerificationPreservesExistingContextAndGoal() {
        assertSuccessfulUnverifiedMutationPreservesContext(
                "NOT_RUN",
                "SUCCEEDED",
                "COMPLETED_UNVERIFIED");
    }

    @Test
    void successfulMutationWithBlankVerificationPreservesExistingContextAndGoal() {
        assertSuccessfulUnverifiedMutationPreservesContext(
                "",
                "SUCCEEDED",
                "COMPLETED_UNVERIFIED");
    }

    @Test
    void successfulMutationWithReadbackOnlyVerificationPreservesExistingContextAndGoal() {
        assertSuccessfulUnverifiedMutationPreservesContext(
                "READBACK_ONLY",
                "SUCCEEDED",
                "COMPLETED_UNVERIFIED");
    }

    @Test
    void mixedSuccessfulAndFailedMutationPreservesExistingContextAndGoal() {
        ActiveTaskContext previous = ActiveTaskContext.proposedChanges(
                6, "trace-old", List.of("index.html", "style.css"), "Update page and styles.");
        ArtifactGoal previousGoal = ArtifactGoal.fromActiveContext(previous);
        TurnResult result = turn(
                12,
                new Result.Ok("Partially done."),
                policy("FILE_EDIT", true, true, List.of("index.html", "style.css")),
                trace(12, "trace-partial", true, true, List.of("index.html", "style.css"),
                        "PASSED", "Readback passed for index.html", "GRANTED_OR_NOT_REQUIRED", "PARTIAL", "PARTIAL"),
                List.of(
                        new TurnRecord.ToolCallSummary("talos.edit_file", "index.html", true, ""),
                        new TurnRecord.ToolCallSummary("talos.edit_file", "style.css", false, "old_string not found")),
                0);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result,
                "Apply those changes.",
                previous,
                previousGoal);

        assertSame(previous, update.activeTaskContext());
        assertSame(previousGoal, update.artifactGoal());
    }

    @Test
    void recoveredFailedThenSuccessfulMutationClearsWhenTraceOutcomeIsVerifiedSucceeded() {
        ActiveTaskContext previous = ActiveTaskContext.proposedChanges(
                6, "trace-old", List.of("README.md"), "Change the title.");
        ArtifactGoal previousGoal = ArtifactGoal.fromActiveContext(previous);
        TurnResult result = turn(
                12,
                new Result.Ok("Done after retry."),
                policy("FILE_EDIT", true, true, List.of("README.md")),
                trace(12, "trace-recovered", true, true, List.of("README.md"),
                        "PASSED", "All checks passed", "GRANTED_OR_NOT_REQUIRED", "SUCCEEDED", "COMPLETED_VERIFIED"),
                List.of(
                        new TurnRecord.ToolCallSummary("talos.edit_file", "README.md", false, "old_string not found"),
                        new TurnRecord.ToolCallSummary("talos.edit_file", "README.md", true, "")),
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

    private void assertSuccessfulUnverifiedMutationPreservesContext(
            String verificationStatus,
            String mutationStatus,
            String classification) {
        ActiveTaskContext previous = ActiveTaskContext.proposedChanges(
                6, "trace-old", List.of("index.html"), "Change the hero.");
        ArtifactGoal previousGoal = ArtifactGoal.fromActiveContext(previous);
        TurnResult result = turn(
                12,
                new Result.Ok("Done."),
                policy("FILE_EDIT", true, true, List.of("index.html")),
                trace(12, "trace-unverified", true, true, List.of("index.html"),
                        verificationStatus, "", "GRANTED_OR_NOT_REQUIRED", mutationStatus, classification),
                List.of(new TurnRecord.ToolCallSummary("talos.edit_file", "index.html", true, "")),
                0);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result,
                "Apply those changes.",
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
        return policy(taskType, mutationAllowed, verificationRequired, expectedTargets, List.of());
    }

    private static TurnPolicyTrace policy(
            String taskType,
            boolean mutationAllowed,
            boolean verificationRequired,
            List<String> expectedTargets,
            List<String> forbiddenTargets) {
        return new TurnPolicyTrace(
                taskType,
                mutationAllowed,
                verificationRequired,
                expectedTargets,
                forbiddenTargets,
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
        return trace(
                turnNumber,
                traceId,
                mutationAllowed,
                verificationRequired,
                expectedTargets,
                verificationStatus,
                verificationProblem,
                approvalStatus,
                mutationStatus,
                classification,
                0,
                0,
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
            String classification,
            int requiredClaimCount,
            int unsatisfiedRequiredClaimCount,
            List<String> authoritativeProofKinds,
            List<String> limitations) {
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
                .verification(
                        verificationStatus,
                        verificationProblem,
                        problems,
                        requiredClaimCount,
                        unsatisfiedRequiredClaimCount,
                        authoritativeProofKinds,
                        limitations)
                .outcome(classification, verificationStatus, approvalStatus, mutationStatus, classification)
                .build();
    }
}
