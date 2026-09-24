package dev.talos.runtime.context;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProposalProvenanceTest {
    @Test
    void acceptedProposalCanBeConsumedWithoutGrantingApproval() {
        ActiveTaskContext context = accepted();
        var decision = evaluate(context, "apply those changes");
        assertTrue(context.hasBoundProposal());
        assertTrue(decision.consumed());
        assertEquals(List.of("README.md"), decision.taskContract().expectedTargets().stream().toList());
        assertTrue(decision.taskContract().verificationRequired());
        assertTrue(context.suppressed("chat").hasBoundProposal());
    }

    @Test
    void legacyProposalIsPreservedButCannotBeConsumed() {
        ActiveTaskContext old = ActiveTaskContext.proposedChanges(2, "trace",
                List.of("README.md"), "Replace the title.");
        var decision = evaluate(old, "apply those changes");
        assertFalse(old.hasBoundProposal());
        assertFalse(decision.consumed());
        assertFalse(decision.taskContract().mutationAllowed());
        assertEquals(old, decision.memoryContext());
        assertEquals(ActiveTaskContext.State.SUPPRESSED, decision.planContext().state());
        assertEquals(ArtifactGoal.none(), decision.artifactGoal());
    }

    @Test
    void provenanceCannotBeMovedAcrossTraceTurnSummaryOrTargetScope() {
        ActiveTaskContext source = accepted();
        for (ActiveTaskContext other : List.of(
                ActiveTaskContext.proposedChanges(3, "trace", List.of("README.md"), "Replace the title."),
                ActiveTaskContext.proposedChanges(2, "other", List.of("README.md"), "Replace the title."),
                ActiveTaskContext.proposedChanges(2, "trace", List.of("notes.md"), "Replace the title."),
                ActiveTaskContext.proposedChanges(2, "trace", List.of("README.md"), "Delete the file."))) {
            ActiveTaskContext copied = other.withProposalProvenance(source.proposalProvenance());
            assertFalse(copied.hasBoundProposal());
            assertFalse(evaluate(copied, "apply those changes").consumed());
        }
    }

    @Test
    void malformedOrFutureProvenanceRemainsUnusable() {
        ActiveTaskContext source = accepted();
        var pin = source.proposalProvenance();
        for (ProposalProvenance invalid : List.of(
                new ProposalProvenance(2, 2, "trace", pin.requestHash(), pin.answerHash(), pin.summaryHash(), pin.targets()),
                new ProposalProvenance(1, 2, "trace", "", pin.answerHash(), pin.summaryHash(), pin.targets()),
                new ProposalProvenance(1, 2, "trace", pin.requestHash(), "bad", pin.summaryHash(), pin.targets()),
                new ProposalProvenance(1, 2, "trace", pin.requestHash(), pin.answerHash(), pin.summaryHash(),
                        List.of("README.md", "a", "b", "c", "d", "e")))) {
            assertFalse(evaluate(source.withProposalProvenance(invalid), "apply those changes").consumed());
        }
    }

    @Test
    void assistantProseCannotBypassMissingProvenance() {
        ActiveTaskContext old = ActiveTaskContext.proposedChanges(2, "trace",
                List.of("README.md"), "Replace the title.");
        for (String confirmation : List.of("yes", "proceed", "Yes proceed please!", "do it")) {
            TaskContract raw = TaskContractResolver.fromMessages(List.of(
                    ChatMessage.user("Read README.md. Do not edit files."),
                    ChatMessage.assistant("README.md can be overwritten. Would you like me to proceed?"),
                    ChatMessage.user(confirmation)));
            var decision = ActiveTaskContextPolicy.evaluate(confirmation, raw, old,
                    ArtifactGoal.fromActiveContext(old), 3);
            assertFalse(raw.mutationAllowed(), confirmation);
            assertFalse(decision.taskContract().mutationAllowed(), confirmation);
            assertFalse(decision.consumed(), confirmation);
        }
    }

    @Test
    void explicitCurrentScopeStillWorksWithoutOldProvenance() {
        ActiveTaskContext old = accepted().withProposalProvenance(ProposalProvenance.none());
        String request = "Edit notes.md and replace old with new.";
        TaskContract direct = TaskContractResolver.fromUserRequest(request);
        assertTrue(direct.mutationAllowed());
        var decision = ActiveTaskContextPolicy.evaluate(request, direct, old,
                ArtifactGoal.fromActiveContext(old), 3);
        assertEquals(direct, decision.taskContract());
        assertFalse(decision.consumed());
    }

    private static ActiveTaskContext accepted() {
        return ProposalContextTestSupport.accepted(2, "trace", List.of("README.md"), "Replace the title.");
    }

    private static ActiveTaskContextPolicy.Decision evaluate(ActiveTaskContext context, String request) {
        return ActiveTaskContextPolicy.evaluate(request, TaskContract.unknown(request),
                context, ArtifactGoal.fromActiveContext(context), 3);
    }
}
