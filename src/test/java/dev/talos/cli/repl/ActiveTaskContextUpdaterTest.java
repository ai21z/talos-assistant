package dev.talos.cli.repl;

import dev.talos.runtime.Result;

import dev.talos.runtime.TurnAudit;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.intent.WorkspaceOperationObligation;
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
    void aDeniedWorkspaceOperationCreatesNoCarriedContext() {
        // A removal is resolved from the current message alone (MICA invariant I2), so its outcome is not carried.
        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                operationTurn(true, operation(
                        "talos.delete_path", "index.html", false, "approval denied by user for talos.delete_path",
                                asTarget("index.html"))),
                "ok can you delete this page now please?",
                ActiveTaskContext.none(),
                ArtifactGoal.none());

        assertEquals(ActiveTaskContext.State.NONE, update.activeTaskContext().state());
        assertEquals(ArtifactGoal.none(), update.artifactGoal());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void aWorkspaceOperationOnACarriedTargetRetiresTheContext(boolean approved) {
        ActiveTaskContext previous = ActiveTaskContext.deniedMutation(
                1, "trace-denied", List.of("index.html"), "No files changed; approval denied by user.");
        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                operationTurn(!approved, operation(
                        "talos.delete_path", "index.html", approved, approved ? "" : "approval denied by user",
                                asTarget("index.html"))),
                "delete index.html",
                previous,
                ArtifactGoal.fromActiveContext(previous));

        assertEquals(ActiveTaskContext.State.NONE, update.activeTaskContext().state());
        assertEquals(ArtifactGoal.none(), update.artifactGoal());
    }

    @Test
    void aMoveWhoseDestinationIsACarriedTargetRetiresTheContext() {
        ActiveTaskContext previous = ActiveTaskContext.deniedMutation(
                1, "trace-denied", List.of("index.html"), "No files changed; approval denied by user.");
        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                operationTurn(false, operation(
                        "talos.move_path", "./index.html", true, "",
                                asSource("draft.html"), asDestination("./index.html"))),
                "move draft.html to index.html",
                previous,
                ArtifactGoal.fromActiveContext(previous));

        assertEquals(ActiveTaskContext.State.NONE, update.activeTaskContext().state());
    }

    @Test
    void anApprovedRenameOfACarriedTargetRetiresTheContext() {
        // The request names both files, and the audit of the applied rename names its source as an operand.
        ActiveTaskContext previous = ActiveTaskContext.deniedMutation(
                1, "trace-denied", List.of("index.html"), "No files changed; approval denied by user.");
        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                operationTurn(false, operation("talos.rename_path", "home.html", true, "",
                        asSource("index.html"), asDestination("home.html")),
                        "index.html", "home.html"),
                "rename index.html to home.html",
                previous,
                ArtifactGoal.fromActiveContext(previous));

        assertEquals(ActiveTaskContext.State.NONE, update.activeTaskContext().state());
    }

    @Test
    void aDeletedFolderRetiresTheContextOfTheFilesInsideIt() {
        ActiveTaskContext previous = ActiveTaskContext.deniedMutation(
                1, "trace-denied", List.of("site/index.html", "site/style.css"),
                "No files changed; approval denied by user.");
        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                operationTurn(false, operation("talos.delete_path", "site", true, "", asTarget("site"))),
                "delete the site folder recursively",
                previous,
                ArtifactGoal.fromActiveContext(previous));

        assertEquals(ActiveTaskContext.State.NONE, update.activeTaskContext().state());
    }

    @Test
    void aFolderWhoseNameStartsLikeACarriedFolderDoesNotRetireIt() {
        ActiveTaskContext previous = ActiveTaskContext.deniedMutation(
                1, "trace-denied", List.of("site-old/index.html"), "No files changed; approval denied by user.");
        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                operationTurn(false, operation("talos.delete_path", "site", true, "", asTarget("site"))),
                "delete the site folder recursively",
                previous,
                ArtifactGoal.fromActiveContext(previous));

        assertSame(previous, update.activeTaskContext());
    }

    @Test
    void aWorkspaceOperationOnAnotherPathKeepsTheCarriedContext() {
        ActiveTaskContext previous = ActiveTaskContext.deniedMutation(
                1, "trace-denied", List.of("index.html"), "No files changed; approval denied by user.");
        ArtifactGoal previousGoal = ArtifactGoal.fromActiveContext(previous);
        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                operationTurn(true, operation(
                        "talos.delete_path", "notes.txt", false, "approval denied by user", asTarget("notes.txt"))),
                "delete notes.txt",
                previous,
                previousGoal);

        assertSame(previous, update.activeTaskContext());
        assertSame(previousGoal, update.artifactGoal());
    }

    @Test
    void aDeniedEditBesideAWorkspaceOperationStillCreatesItsDeniedContext() {
        TurnResult result = turn(
                4,
                new Result.Ok("No files were changed because approval was denied."),
                policy("FILE_EDIT", true, true, List.of("index.html")),
                trace(4, "trace-mixed", true, true, List.of("index.html"),
                        "", "", "DENIED", "DENIED", "BLOCKED_BY_APPROVAL"),
                List.of(new TurnRecord.ToolCallSummary(
                                "talos.edit_file", "index.html", false, "approval denied by user"),
                        operation(
                                "talos.delete_path", "style.css", false, "approval denied by user",
                                        asTarget("style.css"))),
                2);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result, "Edit index.html so it works, then delete style.css.",
                ActiveTaskContext.none(), ArtifactGoal.none());

        assertEquals(ActiveTaskContext.Kind.DENIED_MUTATION, update.activeTaskContext().kind());
        assertEquals(List.of("index.html"), update.activeTaskContext().targets());
    }

    @Test
    void aDeniedOperationBesideADeniedEditIsNotCarriedAsAnEditTarget() {
        // The request names neither file, so the model chose both. Neither denial is carried.
        TurnResult result = turn(
                4,
                new Result.Ok("No files were changed because approval was denied."),
                policy("FILE_EDIT", true, true, List.of()),
                trace(4, "trace-mixed", true, true, List.of(),
                        "", "", "DENIED", "DENIED", "BLOCKED_BY_APPROVAL"),
                List.of(new TurnRecord.ToolCallSummary(
                                "talos.edit_file", "index.html", false, "approval denied by user"),
                        operation(
                                "talos.delete_path", "style.css", false, "approval denied by user",
                                        asTarget("style.css"))),
                2);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result, "fix the page and delete the old stylesheet", ActiveTaskContext.none(), ArtifactGoal.none());

        assertEquals(ActiveTaskContext.State.NONE, update.activeTaskContext().state(),
                update.activeTaskContext().toString());
    }

    @Test
    void aDeniedEditOfAFileTheRequestNamedOnlyAsARoleIsCarried() {
        // Without expected targets, a call path that the request named still counts as requested.
        LocalTurnTrace localTrace = LocalTurnTrace.builder("trace-named", "session", 4, "2026-05-01T00:00:00Z")
                .taskContract(new LocalTurnTrace.TaskContractSummary("FILE_EDIT", true, true, true, List.of(),
                        List.of(), "explicit-request-pattern", List.of(new LocalTurnTrace.RolefulTargetSummary(
                                "index.html", "MUST_MUTATE", "USER_REQUEST", "named", "index.html", 1.0))))
                .verification("", "", List.of(), 0, 0, List.of(), List.of())
                .outcome("BLOCKED_BY_APPROVAL", "", "DENIED", "DENIED", "BLOCKED_BY_APPROVAL")
                .build();
        TurnResult result = turn(4, new Result.Ok("No files were changed because approval was denied."),
                policy("FILE_EDIT", true, true, List.of()), localTrace,
                List.of(new TurnRecord.ToolCallSummary(
                        "talos.edit_file", "index.html", false, "approval denied by user")), 1);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result, "change the heading in index.html", ActiveTaskContext.none(), ArtifactGoal.none());

        assertEquals(ActiveTaskContext.Kind.DENIED_MUTATION, update.activeTaskContext().kind());
        assertEquals(List.of("index.html"), update.activeTaskContext().targets());
    }

    @Test
    void aFailedWriteThatChangedAFileTheModelChoseKeepsItsRepairContext() {
        // The write reached the disk before it failed, so the file was changed and its failed check stays repairable.
        TurnResult result = turn(4, new Result.Ok("index.html still links style.css."),
                policy("FILE_EDIT", true, true, List.of()),
                trace(4, "trace-applied-failed", true, true, List.of(),
                        "FAILED", "index.html still links style.css", "GRANTED_OR_NOT_REQUIRED", "FAILED", "FAILED"),
                List.of(new TurnRecord.ToolCallSummary("talos.write_file", "index.html", List.of("index.html"),
                        false, "read-back integrity check failed",
                        dev.talos.tools.ToolMutationEffects.applied("index.html"), null, null, null, List.of())),
                0);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result, "fix the page", ActiveTaskContext.none(), ArtifactGoal.none());

        assertEquals(ActiveTaskContext.Kind.VERIFIER_FINDINGS, update.activeTaskContext().kind(),
                update.activeTaskContext().toString());
        assertEquals(List.of("index.html"), update.activeTaskContext().targets());
    }

    @Test
    void aFailedWriteThatChangedOnlyAnotherPathDoesNotCarryItsFile() {
        // The folder was created, but the file the model chose was never written.
        TurnResult result = turn(4, new Result.Ok("The write of pages/new/index.html failed."),
                policy("FILE_EDIT", true, true, List.of()),
                trace(4, "trace-folder-only", true, true, List.of(),
                        "FAILED", "pages/new/index.html is missing", "GRANTED_OR_NOT_REQUIRED", "FAILED", "FAILED"),
                List.of(new TurnRecord.ToolCallSummary("talos.write_file", "pages/new/index.html",
                        List.of("pages/new/index.html"), false, "write failed",
                        dev.talos.tools.ToolMutationEffects.applied("pages/new"), null, null, null, List.of())),
                0);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result, "fix the page", ActiveTaskContext.none(), ArtifactGoal.none());

        assertEquals(ActiveTaskContext.State.NONE, update.activeTaskContext().state(),
                update.activeTaskContext().toString());
    }

    @Test
    void aFailedCheckKeepsOnlyTheFilesTheModelChoseThatChanged() {
        // style.css was changed and failed its check, script.js was only proposed and changed nothing.
        TurnResult result = turn(4, new Result.Ok("style.css failed its check."),
                policy("FILE_EDIT", true, true, List.of()),
                trace(4, "trace-failed-model", true, true, List.of(),
                        "FAILED", "style.css is not linked", "GRANTED_OR_NOT_REQUIRED", "PARTIAL", "FAILED"),
                List.of(new TurnRecord.ToolCallSummary("talos.edit_file", "style.css", true, ""),
                        new TurnRecord.ToolCallSummary("talos.edit_file", "script.js", false, "old_string not found")),
                0);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result, "make the page look better", ActiveTaskContext.none(), ArtifactGoal.none());

        assertEquals(ActiveTaskContext.Kind.VERIFIER_FINDINGS, update.activeTaskContext().kind(),
                update.activeTaskContext().toString());
        assertEquals(List.of("style.css"), update.activeTaskContext().targets());
    }

    @Test
    void anAppliedEditBesideADeniedEditOfFilesTheModelChoseCarriesNoDenial() {
        // The applied file was approved, the denied one was only proposed. Neither is a denied request of the user.
        TurnResult result = turn(4, new Result.Ok("Updated style.css. The edit of index.html was denied."),
                policy("FILE_EDIT", true, true, List.of()),
                trace(4, "trace-mixed-model", true, true, List.of(),
                        "", "", "DENIED", "PARTIAL", "PARTIAL"),
                List.of(new TurnRecord.ToolCallSummary("talos.edit_file", "style.css", true, ""),
                        new TurnRecord.ToolCallSummary(
                                "talos.edit_file", "index.html", false, "approval denied by user")),
                1);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result, "make the page look better", ActiveTaskContext.none(), ArtifactGoal.none());

        assertEquals(ActiveTaskContext.State.NONE, update.activeTaskContext().state(),
                update.activeTaskContext().toString());
    }

    @Test
    void aPolicyDeniedOperationBesideAnApprovedEditIsNotADeniedEdit() {
        TurnResult result = turn(
                5,
                new Result.Ok("Updated index.html."),
                policy("FILE_EDIT", true, true, List.of("index.html")),
                trace(5, "trace-policy", true, true, List.of("index.html"),
                        "READBACK_ONLY", "", "GRANTED_OR_NOT_REQUIRED", "PARTIAL", "PARTIAL"),
                List.of(new TurnRecord.ToolCallSummary("talos.edit_file", "index.html", true, ""),
                        new TurnRecord.ToolCallSummary("talos.delete_path", ".env", List.of(), false,
                                "permission policy denied talos.delete_path (PROTECTED_PATH_DENY)",
                                dev.talos.tools.ToolMutationEffects.none(),
                                TurnRecord.ToolCallDisposition.BLOCKED,
                                dev.talos.tools.ToolFailureReason.PERMISSION_POLICY_DENIED,
                                null, List.of(asTarget(".env")))),
                1);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result, "edit index.html and delete .env", ActiveTaskContext.none(), ArtifactGoal.none());

        assertFalse(update.activeTaskContext().kind() == ActiveTaskContext.Kind.DENIED_MUTATION,
                update.activeTaskContext().toString());
    }

    @Test
    void anAppliedOperationBesideAnEditRetiresTheCarriedContextOfItsPath() {
        ActiveTaskContext previous = ActiveTaskContext.deniedMutation(
                1, "trace-denied", List.of("style.css"), "No files changed; approval denied by user.");
        TurnResult result = turn(
                3,
                new Result.Ok("Updated index.html and deleted style.css."),
                policy("FILE_EDIT", true, true, List.of("index.html")),
                trace(3, "trace-mixed", true, true, List.of("index.html"),
                        "READBACK_ONLY", "", "GRANTED_OR_NOT_REQUIRED", "SUCCEEDED", "COMPLETED_UNVERIFIED"),
                List.of(new TurnRecord.ToolCallSummary("talos.edit_file", "index.html", true, ""),
                        operation("talos.delete_path", "style.css", true, "", asTarget("style.css"))),
                0);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result, "Edit index.html so it works, then delete style.css.", previous,
                ArtifactGoal.fromActiveContext(previous));

        assertEquals(ActiveTaskContext.State.NONE, update.activeTaskContext().state(),
                update.activeTaskContext().toString());
    }

    @Test
    void aCreatedFolderKeepsTheDeniedContextOfAWriteInsideIt() {
        TurnResult result = turn(
                2,
                new Result.Ok("No files were changed because approval was denied."),
                policy("FILE_CREATE", true, true, List.of("site/index.html")),
                trace(2, "trace-mkdir", true, true, List.of("site/index.html"),
                        "", "", "DENIED", "DENIED", "BLOCKED_BY_APPROVAL"),
                List.of(operation("talos.mkdir", "site", true, "", asTarget("site")),
                        new TurnRecord.ToolCallSummary(
                                "talos.write_file", "site/index.html", false, "approval denied by user")),
                1);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result, "create a folder site and put an index.html in it", ActiveTaskContext.none(),
                ArtifactGoal.none());

        assertEquals(ActiveTaskContext.Kind.DENIED_MUTATION, update.activeTaskContext().kind());
        assertEquals(List.of("site/index.html"), update.activeTaskContext().targets());
    }

    @Test
    void aFileTheOperationRequestExcludedKeepsItsCarriedContext() {
        ActiveTaskContext previous = ActiveTaskContext.deniedMutation(
                1, "trace-denied", List.of("index.html"), "No files changed; approval denied by user.");
        TurnResult result = operationTurnWithRoles(
                operation("talos.delete_path", "notes.txt", true, "", asTarget("notes.txt")),
                List.of(new LocalTurnTrace.RolefulTargetSummary(
                                "notes.txt", "MENTIONED_ONLY", "USER_REQUEST", "named", "notes.txt", 1.0),
                        new LocalTurnTrace.RolefulTargetSummary(
                                "index.html", "FORBIDDEN", "USER_REQUEST", "excluded", "index.html", 1.0)));

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result, "delete notes.txt but do not touch index.html", previous,
                ArtifactGoal.fromActiveContext(previous));

        assertSame(previous, update.activeTaskContext());
    }

    @Test
    void aFailedCheckBesideAnAppliedDeleteDoesNotCarryTheDeletedPath() {
        TurnResult result = turn(
                3,
                new Result.Ok("Updated index.html and deleted style.css."),
                policy("FILE_EDIT", true, true, List.of()),
                trace(3, "trace-mixed", true, true, List.of(),
                        "FAILED", "index.html still links style.css", "GRANTED_OR_NOT_REQUIRED", "SUCCEEDED",
                        "FAILED"),
                List.of(new TurnRecord.ToolCallSummary("talos.edit_file", "index.html", true, ""),
                        operation("talos.delete_path", "style.css", true, "", asTarget("style.css"))),
                0);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result, "fix the page and delete the old stylesheet", ActiveTaskContext.none(), ArtifactGoal.none());

        assertEquals(ActiveTaskContext.Kind.VERIFIER_FINDINGS, update.activeTaskContext().kind());
        assertEquals(List.of("index.html"), update.activeTaskContext().targets());
    }

    @Test
    void aRetiredStaticWebContextDoesNotWidenTheTargetsOfTheTurn() {
        ActiveTaskContext previous = ActiveTaskContext.partialMutation(
                1, "trace-site", List.of("index.html", "style.css", "script.js"), "PARTIAL",
                StaticWebRequirements.none());
        TurnResult result = turn(
                2,
                new Result.Ok("Updated the page and deleted style.css."),
                policy("FILE_EDIT", true, true, List.of("index.html", "script.js")),
                trace(2, "trace-mixed", true, true, List.of("index.html", "script.js"),
                        "PASSED", "", "GRANTED_OR_NOT_REQUIRED", "SUCCEEDED", "COMPLETED_VERIFIED"),
                List.of(new TurnRecord.ToolCallSummary("talos.edit_file", "index.html", true, ""),
                        operation("talos.delete_path", "style.css", true, "", asTarget("style.css"))),
                0);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result, "inline the styles and delete style.css", previous, ArtifactGoal.fromActiveContext(previous));

        assertFalse(update.activeTaskContext().targets().contains("style.css"),
                update.activeTaskContext().toString());
    }

    @Test
    void aRenameBesideAnEditRetiresTheCarriedContextOfItsSource() {
        // The audit of the applied rename names only the destination. The request mentioned the source.
        ActiveTaskContext previous = ActiveTaskContext.deniedMutation(
                1, "trace-denied", List.of("notes.md"), "No files changed; approval denied by user.");
        LocalTurnTrace localTrace = LocalTurnTrace.builder("trace-mixed", "session", 2, "2026-05-01T00:00:00Z")
                .taskContract(new LocalTurnTrace.TaskContractSummary("FILE_EDIT", true, true, true,
                        List.of("README.md"), List.of(), "explicit-request-pattern",
                        List.of(new LocalTurnTrace.RolefulTargetSummary(
                                        "notes.md", "MENTIONED_ONLY", "USER_REQUEST", "named", "notes.md", 1.0),
                                new LocalTurnTrace.RolefulTargetSummary(
                                        "README.md", "MUST_MUTATE", "USER_REQUEST", "named", "README.md", 1.0))))
                .verification("READBACK_ONLY", "", List.of(), 0, 0, List.of(), List.of())
                .outcome("COMPLETED_UNVERIFIED", "READBACK_ONLY", "GRANTED_OR_NOT_REQUIRED", "SUCCEEDED",
                        "COMPLETED_UNVERIFIED")
                .build();
        TurnResult result = turn(
                2,
                new Result.Ok("Renamed notes.md and fixed README.md."),
                policy("FILE_EDIT", true, true, List.of("README.md")),
                localTrace,
                List.of(operation("talos.rename_path", "archive.md", true, "",
                        asSource("notes.md"), asDestination("archive.md")),
                        new TurnRecord.ToolCallSummary("talos.edit_file", "README.md", true, "")),
                0);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result, "rename notes.md to archive.md and fix the typo in README.md", previous,
                ArtifactGoal.fromActiveContext(previous));

        assertEquals(ActiveTaskContext.State.NONE, update.activeTaskContext().state(),
                update.activeTaskContext().toString());
    }

    @Test
    void aFailedCheckOfAnOperationWhoseContractListsItsTargetCreatesNoContext() {
        // Only the operation-only rule keeps this outcome from becoming a repair context on the deleted file.
        TurnResult result = turn(
                2,
                new Result.Ok("Deleted index.html."),
                policy("FILE_EDIT", true, true, List.of("index.html")),
                trace(2, "trace-operation", true, true, List.of("index.html"),
                        "FAILED", "index.html still exists", "GRANTED_OR_NOT_REQUIRED", "SUCCEEDED", "FAILED"),
                List.of(operation("talos.delete_path", "index.html", true, "", asTarget("index.html"))),
                0);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result, "delete index.html", ActiveTaskContext.none(), ArtifactGoal.none());

        assertEquals(ActiveTaskContext.State.NONE, update.activeTaskContext().state(),
                update.activeTaskContext().toString());
    }

    @Test
    void anOperationPathTheContractListsIsNotCarriedAsADeniedEditTarget() {
        TurnResult result = turn(
                4,
                new Result.Ok("No files were changed because approval was denied."),
                policy("FILE_EDIT", true, true, List.of("index.html", "style.css")),
                trace(4, "trace-mixed", true, true, List.of("index.html", "style.css"),
                        "", "", "DENIED", "DENIED", "BLOCKED_BY_APPROVAL"),
                List.of(new TurnRecord.ToolCallSummary(
                                "talos.edit_file", "index.html", false, "approval denied by user"),
                        operation(
                                "talos.delete_path", "style.css", false, "approval denied by user",
                                        asTarget("style.css"))),
                2);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result, "edit index.html and delete style.css", ActiveTaskContext.none(), ArtifactGoal.none());

        assertEquals(ActiveTaskContext.Kind.DENIED_MUTATION, update.activeTaskContext().kind());
        assertEquals(List.of("index.html"), update.activeTaskContext().targets());
    }

    @Test
    void aDeletedPathTheContractListsIsNotARepairTarget() {
        TurnResult result = turn(
                3,
                new Result.Ok("Updated index.html and deleted style.css."),
                policy("FILE_EDIT", true, true, List.of("index.html", "style.css")),
                trace(3, "trace-mixed", true, true, List.of("index.html", "style.css"),
                        "FAILED", "index.html still links style.css", "GRANTED_OR_NOT_REQUIRED", "SUCCEEDED",
                        "FAILED"),
                List.of(new TurnRecord.ToolCallSummary("talos.edit_file", "index.html", true, ""),
                        operation("talos.delete_path", "style.css", true, "", asTarget("style.css"))),
                0);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result, "fix index.html and delete style.css", ActiveTaskContext.none(), ArtifactGoal.none());

        assertEquals(ActiveTaskContext.Kind.VERIFIER_FINDINGS, update.activeTaskContext().kind());
        assertEquals(List.of("index.html"), update.activeTaskContext().targets());
    }

    @Test
    void aDeletedPathTheContractListsIsNotAVerifiedTarget() {
        TurnResult result = turn(
                3,
                new Result.Ok("Updated the page and deleted style.css."),
                policy("FILE_EDIT", true, true, List.of("index.html", "style.css", "script.js")),
                trace(3, "trace-mixed", true, true, List.of("index.html", "style.css", "script.js"),
                        "PASSED", "", "GRANTED_OR_NOT_REQUIRED", "SUCCEEDED", "COMPLETED_VERIFIED"),
                List.of(new TurnRecord.ToolCallSummary("talos.edit_file", "index.html", true, ""),
                        new TurnRecord.ToolCallSummary("talos.edit_file", "script.js", true, ""),
                        operation("talos.delete_path", "style.css", true, "", asTarget("style.css"))),
                0);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result, "inline the styles, update script.js and delete style.css", ActiveTaskContext.none(),
                ArtifactGoal.none());

        assertEquals(ActiveTaskContext.Kind.VERIFIED_MUTATION, update.activeTaskContext().kind());
        assertEquals(List.of("index.html", "script.js"), update.activeTaskContext().targets());
    }

    @Test
    void aCopiedFileThatIsThenEditedStaysARepairTarget() {
        // The copy destination is also changed by a content call, so it remains a content target.
        TurnResult result = turn(
                3,
                new Result.Ok("Copied a.html to b.html and edited b.html."),
                policy("FILE_EDIT", true, true, List.of("b.html")),
                trace(3, "trace-mixed", true, true, List.of("b.html"),
                        "FAILED", "b.html has no title", "GRANTED_OR_NOT_REQUIRED", "SUCCEEDED", "FAILED"),
                List.of(operation("talos.copy_path", "b.html", true, "", asSource("a.html"), asDestination("b.html")),
                        new TurnRecord.ToolCallSummary("talos.edit_file", "b.html", true, "")),
                0);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result, "copy a.html to b.html and give b.html a title", ActiveTaskContext.none(),
                ArtifactGoal.none());

        assertEquals(ActiveTaskContext.Kind.VERIFIER_FINDINGS, update.activeTaskContext().kind());
        assertEquals(List.of("b.html"), update.activeTaskContext().targets());
    }

    @Test
    void aCreatedFolderKeepsTheCarriedContextOfAFileInsideIt() {
        // Creating a folder moves nothing, so a carried task on a file inside it stays.
        ActiveTaskContext previous = ActiveTaskContext.deniedMutation(
                1, "trace-denied", List.of("site/index.html"), "No files changed; approval denied by user.");
        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                operationTurn(false, operation("talos.mkdir", "site", true, "", asTarget("site"))),
                "create the folder site",
                previous,
                ArtifactGoal.fromActiveContext(previous));

        assertSame(previous, update.activeTaskContext());
    }

    @Test
    void aFailedCheckOfAnOperationOnlyTurnCarriesNoOtherFileItsContractListed() {
        // No repair offer is rendered for an operation-only turn, so no repair context may be carried for it either.
        TurnResult result = turn(
                2,
                new Result.Ok("Deleted style.css."),
                policy("FILE_EDIT", true, true, List.of("index.html")),
                trace(2, "trace-operation", true, true, List.of("index.html"),
                        "FAILED", "index.html still links style.css", "GRANTED_OR_NOT_REQUIRED", "SUCCEEDED",
                        "FAILED"),
                List.of(operation("talos.delete_path", "style.css", true, "", asTarget("style.css"))),
                0);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result, "delete style.css and keep index.html working", ActiveTaskContext.none(), ArtifactGoal.none());

        assertEquals(ActiveTaskContext.State.NONE, update.activeTaskContext().state(),
                update.activeTaskContext().toString());
    }

    @Test
    void aDeniedEditOfAFileTheTurnDeletedIsNotCarried() {
        TurnResult result = turn(
                2,
                new Result.Ok("Deleted style.css. The edit was not applied because approval was denied."),
                policy("FILE_EDIT", true, true, List.of()),
                trace(2, "trace-mixed", true, true, List.of(),
                        "", "", "DENIED", "SUCCEEDED", "COMPLETED_UNVERIFIED"),
                List.of(new TurnRecord.ToolCallSummary(
                                "talos.edit_file", "style.css", false, "approval denied by user"),
                        operation("talos.delete_path", "style.css", true, "", asTarget("style.css"))),
                1);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result, "delete style.css", ActiveTaskContext.none(), ArtifactGoal.none());

        assertEquals(ActiveTaskContext.State.NONE, update.activeTaskContext().state(),
                update.activeTaskContext().toString());
    }

    @Test
    void aDeniedEditOfAFileInsideADeletedFolderIsNotCarried() {
        TurnResult result = turn(
                2,
                new Result.Ok("Deleted docs. The edit was not applied because approval was denied."),
                policy("FILE_EDIT", true, true, List.of()),
                trace(2, "trace-mixed", true, true, List.of(),
                        "", "", "DENIED", "SUCCEEDED", "COMPLETED_UNVERIFIED"),
                List.of(new TurnRecord.ToolCallSummary(
                                "talos.edit_file", "docs/a.md", false, "approval denied by user"),
                        operation("talos.delete_path", "docs", true, "", asTarget("docs"))),
                1);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result, "update docs/a.md, then delete docs", ActiveTaskContext.none(), ArtifactGoal.none());

        assertEquals(ActiveTaskContext.State.NONE, update.activeTaskContext().state(),
                update.activeTaskContext().toString());
    }

    @Test
    void anEditedFileThatTheTurnThenDeletedIsNotARepairTarget() {
        TurnResult result = turn(
                3,
                new Result.Ok("Updated index.html and style.css, then deleted style.css."),
                policy("FILE_EDIT", true, true, List.of("index.html", "style.css")),
                trace(3, "trace-mixed", true, true, List.of("index.html", "style.css"),
                        "FAILED", "index.html still links style.css", "GRANTED_OR_NOT_REQUIRED", "SUCCEEDED",
                        "FAILED"),
                List.of(new TurnRecord.ToolCallSummary("talos.edit_file", "index.html", true, ""),
                        new TurnRecord.ToolCallSummary("talos.edit_file", "style.css", true, ""),
                        operation("talos.delete_path", "style.css", true, "", asTarget("style.css"))),
                0);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result, "fix index.html and style.css, then delete style.css", ActiveTaskContext.none(),
                ArtifactGoal.none());

        assertEquals(ActiveTaskContext.Kind.VERIFIER_FINDINGS, update.activeTaskContext().kind());
        assertEquals(List.of("index.html"), update.activeTaskContext().targets());
    }

    @Test
    void aDeniedWriteOfACopiedFileKeepsItsRetryContext() {
        // The copy leaves the file in place, so the denied write to it is a denied content edit (I2). The resolver
        // names b.html as the expected target of this request.
        TurnResult result = turn(
                2,
                new Result.Ok("Copied a.html to b.html. The write was not applied because approval was denied."),
                policy("FILE_EDIT", true, true, List.of("b.html")),
                trace(2, "trace-mixed", true, true, List.of("b.html"),
                        "", "", "DENIED", "SUCCEEDED", "COMPLETED_UNVERIFIED"),
                List.of(operation("talos.copy_path", "b.html", true, "", asSource("a.html"), asDestination("b.html")),
                        new TurnRecord.ToolCallSummary(
                                "talos.write_file", "b.html", false, "approval denied by user")),
                1);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result, "copy a.html to b.html and write a new heading into b.html", ActiveTaskContext.none(),
                ArtifactGoal.none());

        assertEquals(ActiveTaskContext.Kind.DENIED_MUTATION, update.activeTaskContext().kind());
        assertEquals(List.of("b.html"), update.activeTaskContext().targets());
    }

    @Test
    void aCopyDestinationTheContractListsIsNotARepairTarget() {
        TurnResult result = turn(
                3,
                new Result.Ok("Updated index.html and copied app.js to app.bak.js."),
                policy("FILE_EDIT", true, true, List.of("index.html", "app.bak.js")),
                trace(3, "trace-mixed", true, true, List.of("index.html", "app.bak.js"),
                        "FAILED", "index.html has no title", "GRANTED_OR_NOT_REQUIRED", "SUCCEEDED", "FAILED"),
                List.of(new TurnRecord.ToolCallSummary("talos.edit_file", "index.html", true, ""),
                        operation("talos.copy_path", "app.bak.js", true, "",
                                asSource("app.js"), asDestination("app.bak.js"))),
                0);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result, "fix index.html and copy app.js to app.bak.js", ActiveTaskContext.none(), ArtifactGoal.none());

        assertEquals(ActiveTaskContext.Kind.VERIFIER_FINDINGS, update.activeTaskContext().kind());
        assertEquals(List.of("index.html"), update.activeTaskContext().targets());
    }

    @Test
    void theDestinationOfADeniedMoveIsNotCarriedBesideADeniedEdit() {
        assertEquals(List.of("c.md"), deniedBesideADeniedEdit(
                deniedOperation("talos.move_path", "a.md", asSource("a.md"), asDestination("archive/a.md")),
                List.of("a.md", "archive/a.md", "c.md"), "c.md",
                "move a.md to archive/a.md and add a heading to c.md"));
    }

    @Test
    void theDestinationOfADeniedCopyIsNotCarriedBesideADeniedEdit() {
        assertEquals(List.of("c.html"), deniedBesideADeniedEdit(
                deniedOperation("talos.copy_path", "a.html", asSource("a.html"), asDestination("b.html")),
                List.of("b.html", "c.html"), "c.html",
                "copy a.html to b.html and add a title to c.html"));
    }

    @Test
    void theDestinationOfADeniedRenameIsNotCarriedBesideADeniedEdit() {
        assertEquals(List.of("README.md"), deniedBesideADeniedEdit(
                deniedOperation("talos.rename_path", "notes.md", asSource("notes.md"), asDestination("archive.md")),
                List.of("notes.md", "archive.md", "README.md"), "README.md",
                "rename notes.md to archive.md and fix the typo in README.md"));
    }

    @Test
    void theSourceOfAnAppliedMoveIsNotARepairTarget() {
        // The applied move records its destination as the changed path, and its audit names the moved source.
        TurnResult result = turn(
                3,
                new Result.Ok("Moved a.md to archive/a.md and updated c.md."),
                policy("FILE_EDIT", true, true, List.of("a.md", "archive/a.md", "c.md")),
                trace(3, "trace-mixed", true, true, List.of("a.md", "archive/a.md", "c.md"),
                        "FAILED", "c.md has no heading", "GRANTED_OR_NOT_REQUIRED", "SUCCEEDED", "FAILED"),
                List.of(operation("talos.move_path", "archive/a.md", true, "",
                                asSource("a.md"), asDestination("archive/a.md")),
                        new TurnRecord.ToolCallSummary("talos.edit_file", "c.md", true, "")),
                0);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result, "move a.md to archive/a.md and add a heading to c.md", ActiveTaskContext.none(),
                ArtifactGoal.none());

        assertEquals(ActiveTaskContext.Kind.VERIFIER_FINDINGS, update.activeTaskContext().kind());
        assertEquals(List.of("c.md"), update.activeTaskContext().targets());
    }

    @Test
    void aMoveDestinationIsMatchedExactlyAndKeepsTheCarriedContextOfAFileInsideIt() {
        // A destination is created, so only the destination itself ends a carried task, not a file inside it.
        ActiveTaskContext previous = ActiveTaskContext.deniedMutation(
                1, "trace-denied", List.of("out/index.html"), "No files changed; approval denied by user.");
        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                operationTurn(false, operation("talos.move_path", "out", true, "",
                        asSource("a.md"), asDestination("out"))),
                "move a.md to out",
                previous,
                ArtifactGoal.fromActiveContext(previous));

        assertEquals(ActiveTaskContext.Kind.DENIED_MUTATION, update.activeTaskContext().kind());
        assertEquals(List.of("out/index.html"), update.activeTaskContext().targets());
    }

    @Test
    void aCopyEndsTheCarriedContextOfItsSourceAtAnyOutcome() {
        // Every operand of an operation is one of its paths, whatever the outcome, so the audit alone ends a carried
        // task on the copied source although the request named no file.
        ActiveTaskContext previous = ActiveTaskContext.deniedMutation(
                1, "trace-denied", List.of("a.html"), "No files changed; approval denied by user.");
        for (boolean denied : List.of(true, false)) {
            ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                    operationTurn(denied, denied
                            ? deniedOperation("talos.copy_path", "a.html", asSource("a.html"), asDestination("b.html"))
                            : operation("talos.copy_path", "b.html", true, "",
                                    asSource("a.html"), asDestination("b.html"))),
                    "copy a.html to b.html",
                    previous,
                    ArtifactGoal.fromActiveContext(previous));

            assertEquals(ActiveTaskContext.State.NONE, update.activeTaskContext().state(), "denied " + denied);
        }
    }

    @Test
    void aCopiedFolderIsMatchedExactlyAndKeepsTheCarriedContextOfAFileInsideIt() {
        // A copy leaves its source in place, so only the copied folder itself ends a carried task, not a file inside.
        ActiveTaskContext previous = ActiveTaskContext.deniedMutation(
                1, "trace-denied", List.of("site/index.html"), "No files changed; approval denied by user.");
        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                operationTurn(false, operation("talos.copy_path", "backup", true, "",
                        asSource("site"), asDestination("backup"))),
                "copy site to backup",
                previous,
                ArtifactGoal.fromActiveContext(previous));

        assertEquals(ActiveTaskContext.Kind.DENIED_MUTATION, update.activeTaskContext().kind());
        assertEquals(List.of("site/index.html"), update.activeTaskContext().targets());
    }

    @Test
    void anAppliedRenameOfAFolderEndsTheCarriedContextOfAFileInsideItsSource() {
        // The request names no file, so only the audited source of the rename relocates the carried file.
        ActiveTaskContext previous = ActiveTaskContext.deniedMutation(
                1, "trace-denied", List.of("site/index.html"), "No files changed; approval denied by user.");
        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                operationTurn(false, operation("talos.rename_path", "web", true, "",
                        asSource("site"), asDestination("web"))),
                "rename site to web",
                previous,
                ArtifactGoal.fromActiveContext(previous));

        assertEquals(ActiveTaskContext.State.NONE, update.activeTaskContext().state());
    }

    @Test
    void aDeniedRenameEndsTheCarriedContextOfItsDestination() {
        // The audit names the destination of the denied rename, so a carried task on it ends without request names.
        ActiveTaskContext previous = ActiveTaskContext.deniedMutation(
                1, "trace-denied", List.of("home.html"), "No files changed; approval denied by user.");
        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                operationTurn(true, deniedOperation("talos.rename_path", "index.html",
                        asSource("index.html"), asDestination("home.html"))),
                "rename index.html to home.html",
                previous,
                ArtifactGoal.fromActiveContext(previous));

        assertEquals(ActiveTaskContext.State.NONE, update.activeTaskContext().state());
    }

    /** The targets carried after a denied operation and a denied content edit in one turn. */
    private List<String> deniedBesideADeniedEdit(
            TurnRecord.ToolCallSummary operation, List<String> listed, String edited, String request) {
        TurnResult result = turn(
                2,
                new Result.Ok("No file changes were applied because approval was denied."),
                policy("FILE_EDIT", true, true, listed),
                trace(2, "trace-mixed", true, true, listed, "", "", "DENIED", "DENIED", "BLOCKED_BY_APPROVAL"),
                List.of(operation, new TurnRecord.ToolCallSummary(
                        "talos.edit_file", edited, false, "approval denied by user")),
                2);
        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result, request, ActiveTaskContext.none(), ArtifactGoal.none());
        assertEquals(ActiveTaskContext.Kind.DENIED_MUTATION, update.activeTaskContext().kind(),
                update.activeTaskContext().toString());
        return update.activeTaskContext().targets();
    }

    /** An operation call the user denied, as the audit records it. */
    private static TurnRecord.ToolCallSummary deniedOperation(
            String tool, String pathHint, TurnRecord.OperationOperand... operands) {
        return new TurnRecord.ToolCallSummary(tool, pathHint, List.of(), false, "approval denied by user",
                dev.talos.tools.ToolMutationEffects.none(), TurnRecord.ToolCallDisposition.BLOCKED,
                dev.talos.tools.ToolFailureReason.USER_APPROVAL_DENIED, null, List.of(operands));
    }

    /** An operation call as the audit records it, naming each operand with its role. */
    private static TurnRecord.ToolCallSummary operation(
            String tool, String pathHint, boolean success, String reason, TurnRecord.OperationOperand... operands) {
        return new TurnRecord.ToolCallSummary(tool, pathHint, List.of(), success, reason, null, null, null, null,
                List.of(operands));
    }

    private static TurnRecord.OperationOperand asSource(String path) {
        return new TurnRecord.OperationOperand(path, WorkspaceOperationObligation.Role.SOURCE);
    }

    private static TurnRecord.OperationOperand asDestination(String path) {
        return new TurnRecord.OperationOperand(path, WorkspaceOperationObligation.Role.DESTINATION);
    }

    private static TurnRecord.OperationOperand asTarget(String path) {
        return new TurnRecord.OperationOperand(path, WorkspaceOperationObligation.Role.TARGET);
    }

    private static TurnResult operationTurnWithRoles(
            TurnRecord.ToolCallSummary call, List<LocalTurnTrace.RolefulTargetSummary> roles) {
        LocalTurnTrace localTrace = LocalTurnTrace.builder("trace-operation", "session", 2, "2026-05-01T00:00:00Z")
                .taskContract(new LocalTurnTrace.TaskContractSummary("FILE_EDIT", true, true, true, List.of(),
                        List.of(), "sequential-workspace-operation-awaits-typed-owner", roles))
                .verification("READBACK_ONLY", "", List.of(), 0, 0, List.of(), List.of())
                .outcome("COMPLETED_UNVERIFIED", "READBACK_ONLY", "GRANTED_OR_NOT_REQUIRED", "SUCCEEDED",
                        "COMPLETED_UNVERIFIED")
                .build();
        return turn(2, new Result.Ok("Done."), policy("FILE_EDIT", true, true, List.of()), localTrace,
                List.of(call), 0);
    }

    /** A delete turn as the delete rerun recorded it, a FILE_EDIT contract without expected targets. */
    private static TurnResult operationTurn(boolean denied, TurnRecord.ToolCallSummary call, String... named) {
        LocalTurnTrace base = trace(2, "trace-operation", true, true, List.of(),
                denied ? "" : "READBACK_ONLY", "", denied ? "DENIED" : "GRANTED_OR_NOT_REQUIRED",
                denied ? "DENIED" : "SUCCEEDED", denied ? "BLOCKED_BY_APPROVAL" : "COMPLETED_UNVERIFIED");
        LocalTurnTrace localTrace = LocalTurnTrace.builder("trace-operation", "session", 2, "2026-05-01T00:00:00Z")
                .taskContract(new LocalTurnTrace.TaskContractSummary("FILE_EDIT", true, true, true, List.of(),
                        List.of(), "sequential-workspace-operation-awaits-typed-owner",
                        java.util.Arrays.stream(named).map(path -> new LocalTurnTrace.RolefulTargetSummary(
                                path, "MENTIONED_ONLY", "USER_REQUEST", "named", path, 1.0)).toList()))
                .verification(base.verification().status(), "", List.of(), 0, 0, List.of(), List.of())
                .outcome(base.outcome().status(), base.outcome().verificationStatus(), base.outcome().approvalStatus(),
                        base.outcome().mutationStatus(), base.outcome().classification())
                .build();
        return turn(
                2,
                new Result.Ok(denied ? "No file changes were applied because approval was denied." : "Done."),
                policy("FILE_EDIT", true, true, List.of()),
                localTrace,
                List.of(call),
                denied ? 1 : 0);
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
    void aChecksPassCompletedUnverifiedKeepsTheSameTaskContextKind() {
        TurnResult result = turn(
                10,
                new Result.Ok("Done."),
                policy("FILE_EDIT", true, true, List.of("index.html", "style.css", "script.js")),
                trace(10, "trace-static-checks", true, true, List.of("index.html", "style.css", "script.js"),
                        "PASSED", "Static web coherence checks passed", "GRANTED_OR_NOT_REQUIRED", "SUCCEEDED",
                        "COMPLETED_UNVERIFIED"),
                List.of(
                        new TurnRecord.ToolCallSummary("talos.write_file", "index.html", true, ""),
                        new TurnRecord.ToolCallSummary("talos.write_file", "style.css", true, ""),
                        new TurnRecord.ToolCallSummary("talos.write_file", "script.js", true, "")),
                0);

        ActiveTaskContextUpdater.Update update = updater.updateAfterTurn(
                result,
                "Make it a modern landing page.",
                ActiveTaskContext.none(),
                ArtifactGoal.none());

        assertEquals(ActiveTaskContext.Kind.VERIFIED_MUTATION, update.activeTaskContext().kind());
        assertEquals(List.of("index.html", "style.css", "script.js"), update.activeTaskContext().targets());
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

    @Test
    void failedStaticVerificationWithTheRenderedOfferBindsTheRepairOffer() {
        ActiveTaskContext context = failedTurnContext(
                "[Task incomplete: Static verification failed - Missing #app root]\n\n"
                        + "The requested task is not verified complete.\n\n"
                        + dev.talos.runtime.context.RepairOffer.LINE);

        assertEquals(ActiveTaskContext.Kind.VERIFIER_FINDINGS, context.kind());
        assertTrue(context.hasBoundRepairOffer());
        assertFalse(context.hasBoundProposal());
    }

    @Test
    void anOfferLineWithoutTheRuntimeFailureHeaderIsNotBound() {
        ActiveTaskContext context = failedTurnContext(
                "Static verification failed.\n\n" + dev.talos.runtime.context.RepairOffer.LINE);

        assertEquals(ActiveTaskContext.Kind.VERIFIER_FINDINGS, context.kind());
        assertFalse(context.hasBoundRepairOffer());
    }

    @Test
    void aBoundRepairOfferExpiresWithItsContext() {
        ActiveTaskContext offered = failedTurnContext(
                "[Task incomplete: Static verification failed - Missing #app root]\n\n"
                        + dev.talos.runtime.context.RepairOffer.LINE);
        var raw = dev.talos.runtime.task.TaskContractResolver.fromUserRequest("yes");

        var live = dev.talos.runtime.context.ActiveTaskContextPolicy.evaluate(
                "yes", raw, offered, ArtifactGoal.fromActiveContext(offered), 10);
        var expired = dev.talos.runtime.context.ActiveTaskContextPolicy.evaluate(
                "yes", raw, offered, ArtifactGoal.fromActiveContext(offered), 13);

        assertTrue(live.consumed());
        assertTrue(live.taskContract().mutationAllowed());
        assertFalse(expired.consumed());
        assertFalse(expired.taskContract().mutationAllowed());
    }

    private ActiveTaskContext failedTurnContext(String answer) {
        TurnResult result = turn(
                9,
                new Result.Ok(answer),
                policy("FILE_EDIT", true, true, List.of("index.html")),
                trace(9, "trace-failed-offer", true, true, List.of("index.html"),
                        "FAILED", "Missing #app root", "GRANTED_OR_NOT_REQUIRED", "SUCCEEDED", "FAILED"),
                List.of(new TurnRecord.ToolCallSummary("talos.edit_file", "index.html", true, "")),
                0);
        return updater.updateAfterTurn(result, "Update index.html.", ActiveTaskContext.none(), ArtifactGoal.none())
                .activeTaskContext();
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
