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
        TurnResult result = proposalWithRestrictionEvidence(List.of("README.md"), List.of("README.md"),
                List.of("README.md"), "I would update the README title and usage section.");

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
        assertTrue(context.hasBoundProposal());
        assertEquals(ArtifactGoal.Source.ACTIVE_CONTEXT, update.artifactGoal().source());
        assertEquals(ArtifactGoal.ArtifactKind.README, update.artifactGoal().artifactKind());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "Read README.md and diagnose the failure. Do not edit files.",
            "Do not propose changes to README.md.",
            "The ticket says 'Propose changes to README.md'. Explain the ticket.",
            "If asked later, propose changes to README.md.",
            "Never suggest changes to README.md.",
            "Print the words `Propose changes to README.md`."
    })
    void nonProposalRequestsCannotCreateProposalContext(String request) {
        var update = updater.updateAfterTurn(completedProposal("A diagnosis was provided.",
                List.of("README.md")), request, ActiveTaskContext.none(), ArtifactGoal.none());
        assertEquals(ActiveTaskContext.Kind.NONE, update.activeTaskContext().kind());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "", "PARTIAL", "ADVISORY_ONLY", "FAILED", "ABORTED", "ERROR", "NO_RESULT"
    })
    void incompleteProposalCannotCreateProposalOrRepairContext(String classification) {
        TurnResult result = turn(7, new Result.Ok("Changes were suggested for README.md."),
                policy("READ_ONLY_QA", false, false, List.of("README.md")),
                trace(7, "trace-proposal", false, false, List.of("README.md"),
                        "FAILED".equals(classification) ? "FAILED" : "NOT_RUN",
                        "", "NONE", "NOT_REQUESTED", classification), List.of(), 0);
        var update = updater.updateAfterTurn(result, "Propose changes to README.md.",
                ActiveTaskContext.none(), ArtifactGoal.none());
        assertEquals(ActiveTaskContext.Kind.NONE, update.activeTaskContext().kind());
        for (String followUp : List.of("apply those changes", "fix it")) {
            var decision = dev.talos.runtime.context.ActiveTaskContextPolicy.evaluate(followUp,
                    dev.talos.runtime.task.TaskContract.unknown(followUp),
                    update.activeTaskContext(), update.artifactGoal(), 8);
            assertFalse(decision.consumed());
            assertFalse(decision.taskContract().mutationAllowed());
        }
    }

    @Test
    void errorsEmptyAndInformationalResultsCannotCreateProposalContext() {
        for (Result output : List.of(new Result.Ok(""), new Result.Ok("   "),
                new Result.Error("Failed", 1), new Result.Info("No result"))) {
            TurnResult complete = completedProposal("Replace the title.", List.of("README.md"));
            TurnResult result = new TurnResult(output, null, 7, Duration.ZERO, complete.audit());
            var update = updater.updateAfterTurn(result, "Propose changes to README.md.",
                    ActiveTaskContext.none(), ArtifactGoal.none());
            assertEquals(ActiveTaskContext.Kind.NONE, update.activeTaskContext().kind());
        }
    }

    @Test
    void failedReadCannotBecomeProposalEvenWithCompleteOutcome() {
        TurnResult complete = completedProposal("Replace the title.", List.of("README.md"));
        var result = turn(7, complete.result(), complete.audit().policyTrace(), complete.audit().localTrace(),
                List.of(new TurnRecord.ToolCallSummary("talos.read_file", "README.md", false, "unavailable")), 0);
        var update = updater.updateAfterTurn(result, "Propose changes to README.md.",
                ActiveTaskContext.none(), ArtifactGoal.none());
        assertEquals(ActiveTaskContext.Kind.NONE, update.activeTaskContext().kind());
    }

    @Test
    void contradictoryVerificationEvidenceCannotCreateProposalContext() {
        for (boolean mismatchedStatus : List.of(false, true)) {
            var trace = LocalTurnTrace.builder("trace-proposal", "session", 7, "2026-09-15T00:00:00Z")
                    .taskContract(new LocalTurnTrace.TaskContractSummary("READ_ONLY_QA", false, false,
                            false, List.of("README.md"), List.of()))
                    .verification("NOT_RUN", "", List.of(), 0, mismatchedStatus ? 0 : 1, List.of(), List.of())
                    .outcome("COMPLETE", mismatchedStatus ? "FAILED" : "NOT_RUN", "NONE", "NOT_REQUESTED", "READ_ONLY_ANSWERED")
                    .build();
            var result = turn(7, new Result.Ok("Replace the title."),
                    policy("READ_ONLY_QA", false, false, List.of("README.md")), trace, List.of(), 0);
            var update = updater.updateAfterTurn(result, "Propose changes to README.md.",
                    ActiveTaskContext.none(), ArtifactGoal.none());
            assertEquals(ActiveTaskContext.Kind.NONE, update.activeTaskContext().kind());
        }
    }

    @Test
    void proposalScopeExcludesReadReferencesAndPreviousDurableTargets() {
        ActiveTaskContext previous = ActiveTaskContext.partialMutation(6, "old",
                List.of("index.html", "style.css", "script.js"), "PARTIAL", StaticWebRequirements.none());
        var update = updater.updateAfterTurn(completedProposal("Replace the heading in index.html.",
                        List.of("notes.md", "index.html", "style.css", "script.js")),
                "Read notes.md. Propose changes to index.html.", previous, ArtifactGoal.fromActiveContext(previous));
        assertEquals(List.of("index.html"), update.activeTaskContext().targets());
        assertTrue(update.activeTaskContext().hasBoundProposal());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "Propose changes to README.md or notes.md.",
            "Propose README.md changes without editing.",
            "Propose changes to README.md and preserve notes.md."
    })
    void proposalWithUnresolvedScopeClearsPreviousProposal(String request) {
        ActiveTaskContext previous = dev.talos.runtime.context.ProposalContextTestSupport.accepted(
                6, "old", List.of("README.md"), "Replace title.");
        var update = updater.updateAfterTurn(completedProposal("Some changes were suggested.",
                        List.of("README.md", "notes.md")),
                request, previous, ArtifactGoal.fromActiveContext(previous));
        assertEquals(ActiveTaskContext.Kind.NONE, update.activeTaskContext().kind());
    }

    @Test
    void conflictingMutationContractCannotPreserveAnOlderProposal() {
        ActiveTaskContext previous = dev.talos.runtime.context.ProposalContextTestSupport.accepted(
                6, "old", List.of("notes.md"), "Replace title.");
        var result = turn(7, new Result.Ok("Changes were suggested for README.md."),
                policy("FILE_EDIT", true, true, List.of("README.md")),
                trace(7, "trace-proposal", true, true, List.of("README.md"),
                        "NOT_RUN", "", "NONE", "NOT_REQUESTED", "READ_ONLY_ANSWERED"), List.of(), 0);
        var update = updater.updateAfterTurn(result, "Propose changes to README.md.",
                previous, ArtifactGoal.fromActiveContext(previous));
        assertEquals(ActiveTaskContext.Kind.NONE, update.activeTaskContext().kind());
        assertEquals(ArtifactGoal.none(), update.artifactGoal());
    }

    @Test
    void proposalWordingDoesNotDiscardRecordedMutationRecovery() {
        ActiveTaskContext previous = dev.talos.runtime.context.ProposalContextTestSupport.accepted(
                6, "old", List.of("notes.md"), "Replace title.");
        var result = turn(7, new Result.Ok("An edit was recorded, verification failed."),
                policy("FILE_EDIT", true, true, List.of("README.md")),
                trace(7, "trace-edited", true, true, List.of("README.md"),
                        "FAILED", "Readback failed", "GRANTED_OR_NOT_REQUIRED", "SUCCEEDED", "FAILED"),
                List.of(new TurnRecord.ToolCallSummary("talos.edit_file", "README.md", true, "")), 0);
        var update = updater.updateAfterTurn(result, "Propose changes to README.md.",
                previous, ArtifactGoal.fromActiveContext(previous));
        assertEquals(ActiveTaskContext.Kind.VERIFIER_FINDINGS, update.activeTaskContext().kind());
        assertEquals(List.of("README.md"), update.activeTaskContext().targets());
        assertFalse(update.activeTaskContext().hasBoundProposal());
    }

    private static TurnResult completedProposal(String answer, List<String> targets) {
        return turn(7, new Result.Ok(answer), policy("READ_ONLY_QA", false, false, targets),
                trace(7, "trace-proposal", false, false, targets,
                        "NOT_RUN", "", "NONE", "NOT_REQUESTED", "READ_ONLY_ANSWERED"), List.of(), 0);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "Propose changes to README.md.",
            "Do not edit README.md yet. Propose the changes first.",
            "Do not modify README.md yet. Suggest those edits.",
            "Do not edit README.md yet. Propose changes to README.md."
    })
    void supportedProposalUsesTheActualResolvedReadOnlyContract(String request) {
        var contract = dev.talos.runtime.task.TaskContractResolver.fromUserRequest(request);
        assertFalse(contract.mutationAllowed(), contract.toString());
        assertFalse(contract.mutationRequested(), contract.toString());
        var before = contract.forbiddenTargets();
        var result = completedResolvedProposal(contract);
        var update = updater.updateAfterTurn(result, request, ActiveTaskContext.none(), ArtifactGoal.none());
        assertTrue(update.activeTaskContext().hasBoundProposal(), contract.toString());
        assertEquals(List.of("README.md"), update.activeTaskContext().targets());
        assertEquals(before, contract.forbiddenTargets());
        if (request.startsWith("Do not")) assertTrue(before.contains("README.md"), contract.toString());
        String followUp = "Apply those changes.";
        var decision = dev.talos.runtime.context.ActiveTaskContextPolicy.evaluate(followUp,
                dev.talos.runtime.task.TaskContractResolver.fromUserRequest(followUp),
                update.activeTaskContext(), update.artifactGoal(), 8);
        assertTrue(decision.consumed());
        assertEquals(java.util.Set.of("README.md"), decision.taskContract().expectedTargets());
        assertTrue(decision.taskContract().mutationAllowed());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "Do not edit README.md yet. Propose changes to README.md. Never edit README.md.",
            "Do not edit README.md yet. Propose changes to README.md. Keep README.md unchanged.",
            "Do not edit README.md yet. Propose changes to README.md. Do not edit notes.md.",
            "Do not edit README.md yet. Propose changes to README.md. Read notes.md.",
            "Do not edit README.md yet. Propose changes to README.md. Do not run tests.",
            "Do not edit README.md yet. Propose changes to notes.md."
    })
    void unaccountedDeferredRequestsCannotSupplyFollowUpScope(String request) {
        var contract = dev.talos.runtime.task.TaskContractResolver.fromUserRequest(request);
        var before = contract.forbiddenTargets();
        var update = updater.updateAfterTurn(completedResolvedProposal(contract), request,
                ActiveTaskContext.none(), ArtifactGoal.none());
        assertEquals(ActiveTaskContext.none(), update.activeTaskContext(), contract.toString());
        assertEquals(before, contract.forbiddenTargets());
        String followUp = "Apply those changes.";
        var raw = dev.talos.runtime.task.TaskContractResolver.fromUserRequest(followUp);
        var decision = dev.talos.runtime.context.ActiveTaskContextPolicy.evaluate(
                followUp, raw, update.activeTaskContext(), update.artifactGoal(), 8);
        assertFalse(decision.consumed());
        assertEquals(raw, decision.taskContract());
    }

    @Test
    void deferredScopeCannotExplainMissingOrAdditionalForbiddenEvidence() {
        String request = "Do not edit README.md yet. Propose the changes first.";
        for (List<String> defect : List.of(List.<String>of(), List.of("README.md", "notes.md"),
                List.of("readme.md"), List.of("README.md", ""))) {
            for (boolean policyDefect : List.of(false, true)) {
                var policyForbidden = policyDefect ? defect : List.of("README.md");
                var traceForbidden = policyDefect ? List.of("README.md") : defect;
                var result = proposalWithRestrictionEvidence(policyForbidden, traceForbidden,
                        List.of("README.md"), "Replace the title.");
                var update = updater.updateAfterTurn(result, request, ActiveTaskContext.none(), ArtifactGoal.none());
                assertEquals(ActiveTaskContext.none(), update.activeTaskContext(),
                        "policy " + policyForbidden + ", trace " + traceForbidden);
            }
        }
    }

    @Test
    void deferredScopeAcceptsOnlyCasePreservingLexicalNormalization() {
        String request = "Do not edit docs/README.md yet. Propose the changes first.";
        var result = proposalWithRestrictionEvidence(List.of("./docs\\README.md"),
                List.of("docs/README.md"), List.of("./docs/README.md"), "Replace the title.");
        var update = updater.updateAfterTurn(result, request, ActiveTaskContext.none(), ArtifactGoal.none());
        assertTrue(update.activeTaskContext().hasBoundProposal());
        assertEquals(List.of("docs/README.md"), update.activeTaskContext().targets());
        var caseChanged = proposalWithRestrictionEvidence(List.of("docs/readme.md"),
                List.of("docs/readme.md"), List.of("docs/README.md"), "Replace the title.");
        assertEquals(ActiveTaskContext.none(), updater.updateAfterTurn(caseChanged, request,
                ActiveTaskContext.none(), ArtifactGoal.none()).activeTaskContext());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "Propose changes to README.md.",
            "Do not edit README.md yet. Propose the changes first."
    })
    void proposalCannotDiscardRetainedRequirementsOrReactivateThemAfterRestore(
            String request, @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
        var priorContexts = List.of(
                ActiveTaskContext.pendingMutation(6, "old", List.of("README.md"), "Pending",
                        StaticWebRequirements.of(List.of(), java.util.Set.of("README.md"))),
                ActiveTaskContext.pendingMutation(6, "old", List.of("README.md"), "Pending",
                        StaticWebRequirements.of(List.of("Required release facts"), java.util.Set.of())),
                ActiveTaskContext.verifierFindings(6, "old", List.of("README.md"), List.of("Required check"),
                        "FAILED", List.of(new ActiveTaskContext.RequiredVerificationClaim(
                                "required-check", "Required check", "STATIC_INTERACTION_GUARD",
                                "#action", "#result", "click"))));
        var contract = dev.talos.runtime.task.TaskContractResolver.fromUserRequest(request);
        var store = new dev.talos.runtime.JsonSessionStore(tempDir);
        int ordinal = 0;
        for (var previous : priorContexts) {
            var update = updater.updateAfterTurn(completedResolvedProposal(contract), request,
                    previous, ArtifactGoal.fromActiveContext(previous));
            assertEquals(ActiveTaskContext.State.SUPPRESSED, update.activeTaskContext().state());
            assertEquals(previous.staticWebRequirements(), update.activeTaskContext().staticWebRequirements());
            assertEquals(previous.requiredVerificationClaims(), update.activeTaskContext().requiredVerificationClaims());
            assertEquals(ArtifactGoal.none(), update.artifactGoal());
            assertEquals(ArtifactGoal.none(), ArtifactGoal.fromActiveContext(update.activeTaskContext()));
            assertFalse(update.activeTaskContext().activeAt(8));
            String id = "suppressed-" + ordinal++;
            var data = new dev.talos.runtime.SessionData(id, tempDir.toString(), "", 7,
                    java.time.Instant.parse("2026-09-16T00:00:00Z"), List.of(), "test",
                    update.activeTaskContext(), update.artifactGoal());
            try (var writer = store.acquireSessionWriter(id)) {
                writer.save(data);
            }
            var restored = store.load(id).orElseThrow();
            assertEquals(update.activeTaskContext(), restored.activeTaskContext());
            assertEquals(ArtifactGoal.none(), restored.artifactGoal());
            for (var context : List.of(update.activeTaskContext(), restored.activeTaskContext())) {
                String followUp = "Apply those changes.";
                var raw = dev.talos.runtime.task.TaskContractResolver.fromUserRequest(followUp);
                var decision = dev.talos.runtime.context.ActiveTaskContextPolicy.evaluate(
                        followUp, raw, context, ArtifactGoal.none(), 8);
                assertFalse(decision.consumed());
                assertEquals(raw, decision.taskContract());
                assertEquals(ArtifactGoal.none(), decision.artifactGoal());
            }
        }
    }

    @Test
    void directProposalCannotDiscardCurrentNamedExclusionsOrRequiredFacts() {
        for (String request : List.of(
                "Propose changes to README.md. Do not edit notes.md.",
                "Propose changes to README.md. Preserve these facts: Required release facts.")) {
            var contract = dev.talos.runtime.task.TaskContractResolver.fromUserRequest(request);
            var update = updater.updateAfterTurn(completedResolvedProposal(contract), request,
                    ActiveTaskContext.none(), ArtifactGoal.none());
            assertEquals(ActiveTaskContext.none(), update.activeTaskContext(), request);
        }
    }

    private static TurnResult proposalWithRestrictionEvidence(
            List<String> policyForbidden, List<String> traceForbidden, List<String> targets, String answer) {
        var trace = LocalTurnTrace.builder("trace-proposal", "session", 7, "2026-09-16T00:00:00Z")
                .taskContract(new LocalTurnTrace.TaskContractSummary("READ_ONLY_QA", false, false,
                        false, targets, traceForbidden))
                .verification("NOT_RUN", "", List.of(), 0, 0, List.of(), List.of())
                .outcome("COMPLETE", "NOT_RUN", "NONE", "NOT_REQUESTED", "READ_ONLY_ANSWERED")
                .build();
        return turn(7, new Result.Ok(answer), policy("READ_ONLY_QA", false, false, targets, policyForbidden),
                trace, List.of(), 0);
    }

    private static TurnResult completedResolvedProposal(dev.talos.runtime.task.TaskContract contract) {
        var targets = List.copyOf(contract.expectedTargets());
        var trace = LocalTurnTrace.builder("trace-proposal", "session", 7, "2026-09-16T00:00:00Z")
                .taskContract(contract)
                .verification("NOT_RUN", "", List.of(), 0, 0, List.of(), List.of())
                .outcome("COMPLETE", "NOT_RUN", "NONE", "NOT_REQUESTED", "READ_ONLY_ANSWERED")
                .build();
        return turn(7, new Result.Ok("Replace the README title."),
                policy(contract.type().name(), contract.mutationAllowed(), contract.verificationRequired(),
                        targets, List.copyOf(contract.forbiddenTargets())), trace, List.of(), 0);
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
    void noMutationStaticWebContinuationDoesNotShrinkRicherSavedContext() {
        ActiveTaskContext previous = ActiveTaskContext.pendingMutation(
                2,
                "trace-rich-static",
                List.of("index.html", "style.css", "script.js"),
                "No required static-web mutation completed.",
                StaticWebRequirements.of(
                        List.of("Retrocats", "Costanza", "Life span", "Berlin 22 July 2026"),
                        java.util.Set.of("tailwind.css", "tailwind.min.css")));
        ArtifactGoal previousGoal = ArtifactGoal.fromActiveContext(previous);
        TurnResult result = turn(
                3,
                new Result.Ok("[Truth check: no file was changed.]"),
                policy("FILE_CREATE", true, true, List.of("index.html", "style.css")),
                trace(3, "trace-thin-static", true, true,
                        List.of("index.html", "style.css"),
                        "NOT_RUN", "", "GRANTED_OR_NOT_REQUIRED", "NOT_REQUESTED", "BLOCKED_BY_POLICY"),
                List.of(),
                0);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result,
                "Make this Retrocats website even more polished and complete. "
                        + "Use Tailwind correctly, preserve the required band facts, and repair anything unverified.",
                previous,
                previousGoal);

        ActiveTaskContext context = update.activeTaskContext();
        assertEquals(ActiveTaskContext.Kind.PENDING_MUTATION, context.kind());
        assertEquals(List.of("index.html", "style.css", "script.js"), context.targets());
        assertTrue(context.staticWebRequirements().requiredVisibleFacts().contains("Life span"),
                context.staticWebRequirements().toString());
        assertEquals(java.util.Set.of("tailwind.css", "tailwind.min.css"),
                context.staticWebRequirements().forbiddenArtifacts());
        assertEquals(ArtifactGoal.ArtifactKind.STATIC_WEB, update.artifactGoal().artifactKind());
        assertEquals(List.of("index.html", "style.css", "script.js"), update.artifactGoal().targets());
    }

    @Test
    void failedStaticWebContinuationDoesNotShrinkRicherSavedContext() {
        ActiveTaskContext previous = ActiveTaskContext.pendingMutation(
                2,
                "trace-rich-static",
                List.of("index.html", "style.css", "script.js"),
                "No required static-web mutation completed.",
                StaticWebRequirements.of(
                        List.of("Retrocats", "Costanza", "Life span", "Berlin 22 July 2026"),
                        java.util.Set.of("tailwind.css", "tailwind.min.css")));
        ArtifactGoal previousGoal = ArtifactGoal.fromActiveContext(previous);
        TurnResult result = turn(
                3,
                new Result.Ok("Static verification failed."),
                policy("FILE_CREATE", true, true, List.of("index.html", "style.css")),
                trace(3, "trace-thin-static-failed", true, true,
                        List.of("index.html", "style.css"),
                        "FAILED",
                        "index.html: Tailwind utility classes are used, but no accepted runtime was found.",
                        "GRANTED_OR_NOT_REQUIRED",
                        "SUCCEEDED",
                        "FAILED"),
                List.of(new TurnRecord.ToolCallSummary("talos.write_file", "index.html", true, "")),
                0);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result,
                "Make this Retrocats website even more polished and complete. "
                        + "Use Tailwind correctly, preserve the required band facts, and repair anything unverified.",
                previous,
                previousGoal);

        ActiveTaskContext context = update.activeTaskContext();
        assertEquals(ActiveTaskContext.Kind.VERIFIER_FINDINGS, context.kind());
        assertEquals(List.of("index.html", "style.css", "script.js"), context.targets());
        assertTrue(context.staticWebRequirements().requiredVisibleFacts().contains("Life span"),
                context.staticWebRequirements().toString());
        assertEquals(java.util.Set.of("tailwind.css", "tailwind.min.css"),
                context.staticWebRequirements().forbiddenArtifacts());
        assertEquals(List.of("index.html", "style.css", "script.js"), update.artifactGoal().targets());
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
                .outcome("READ_ONLY_ANSWERED".equals(classification) ? "COMPLETE" : classification,
                        verificationStatus, approvalStatus, mutationStatus, classification)
                .build();
    }
}
