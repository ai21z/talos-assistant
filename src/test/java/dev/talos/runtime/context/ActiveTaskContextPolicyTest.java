package dev.talos.runtime.context;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ActiveTaskContextPolicyTest {

    @Test void makeThoseChangesConsumesProposalContext() {
        ActiveTaskContext saved = readmeProposal();
        String userRequest = "make those changes";
        TaskContract rawContract = TaskContractResolver.fromUserRequest(userRequest);
        ArtifactGoal savedGoal = ArtifactGoal.fromActiveContext(saved);

        ActiveTaskContextPolicy.Decision decision = ActiveTaskContextPolicy.evaluate(
                userRequest,
                rawContract,
                saved,
                savedGoal,
                3);

        assertTrue(decision.consumed());
        assertEquals(ActiveTaskContext.State.ACTIVE, decision.planContext().state());
        assertEquals(TaskType.FILE_EDIT, decision.taskContract().type());
        assertTrue(decision.taskContract().mutationAllowed());
        assertTrue(decision.taskContract().verificationRequired());
        assertEquals(Set.of("README.md"), decision.taskContract().expectedTargets());
        assertEquals(savedGoal, decision.artifactGoal());
        assertEquals(ArtifactGoal.Source.ACTIVE_CONTEXT, decision.artifactGoal().source());
        assertEquals(ArtifactGoal.ArtifactKind.README, decision.artifactGoal().artifactKind());
        assertEquals(saved, decision.memoryContext());
        assertTrue(decision.taskContract().originalUserRequest().contains("Add title and usage."));
        assertTrue(decision.taskContract().originalUserRequest().contains("make those changes"));
    }

    @Test void nullSavedContextReturnsBaselineDecisionWithoutMemory() {
        String userRequest = "Read README.md.";
        TaskContract rawContract = TaskContractResolver.fromUserRequest(userRequest);

        ActiveTaskContextPolicy.Decision decision = ActiveTaskContextPolicy.evaluate(
                userRequest,
                rawContract,
                null,
                ArtifactGoal.fromActiveContext(readmeProposal()),
                3);

        assertFalse(decision.consumed());
        assertEquals(rawContract, decision.taskContract());
        assertEquals(ActiveTaskContext.State.NONE, decision.planContext().state());
        assertEquals(ArtifactGoal.none(), decision.artifactGoal());
        assertEquals(ArtifactGoal.Source.NONE, decision.artifactGoal().source());
        assertEquals(ActiveTaskContext.none(), decision.memoryContext());
    }

    @Test void nonActiveSavedContextReturnsBaselineDecisionWithoutMemory() {
        String userRequest = "make those changes";
        TaskContract rawContract = TaskContractResolver.fromUserRequest(userRequest);
        ActiveTaskContext saved = readmeProposal();

        assertNonActiveBaseline(rawContract, saved.suppressed("answer only"));
        assertNonActiveBaseline(rawContract, saved.cleared("new target"));
        assertNonActiveBaseline(rawContract, saved.expired("too old"));
    }

    @Test void noWorkspaceChatSuppressesWithoutClearingMemory() {
        ActiveTaskContext saved = readmeProposal();
        String userRequest = "I am only chatting, please don't inspect my files.";
        TaskContract rawContract = TaskContractResolver.fromUserRequest(userRequest);
        ArtifactGoal savedGoal = ArtifactGoal.fromActiveContext(saved);

        ActiveTaskContextPolicy.Decision decision = ActiveTaskContextPolicy.evaluate(
                userRequest,
                rawContract,
                saved,
                savedGoal,
                3);

        assertFalse(decision.consumed());
        assertEquals(ActiveTaskContext.State.SUPPRESSED, decision.planContext().state());
        assertEquals(ArtifactGoal.none(), decision.artifactGoal());
        assertEquals(ArtifactGoal.Source.NONE, decision.artifactGoal().source());
        assertEquals(ArtifactGoal.ArtifactKind.UNKNOWN, decision.artifactGoal().artifactKind());
        assertEquals(saved, decision.memoryContext());
    }

    @Test void unrelatedExplicitTargetClearsContextForMemory() {
        ActiveTaskContext saved = readmeProposal();
        String userRequest = "Read config.json.";
        TaskContract rawContract = TaskContractResolver.fromUserRequest(userRequest);

        ActiveTaskContextPolicy.Decision decision = ActiveTaskContextPolicy.evaluate(
                userRequest,
                rawContract,
                saved,
                ArtifactGoal.fromActiveContext(saved),
                3);

        assertFalse(decision.consumed());
        assertEquals(ActiveTaskContext.State.CLEARED, decision.planContext().state());
        assertEquals(ActiveTaskContext.none(), decision.memoryContext());
        assertEquals(Set.of("config.json"), decision.taskContract().expectedTargets());
    }

    @Test void partialExplicitTargetOverlapClearsContextForMemory() {
        ActiveTaskContext saved = readmeProposal();
        String userRequest = "Read README.md and config.json.";
        TaskContract rawContract = TaskContractResolver.fromUserRequest(userRequest);

        ActiveTaskContextPolicy.Decision decision = ActiveTaskContextPolicy.evaluate(
                userRequest,
                rawContract,
                saved,
                ArtifactGoal.fromActiveContext(saved),
                3);

        assertFalse(decision.consumed());
        assertEquals(ActiveTaskContext.State.CLEARED, decision.planContext().state());
        assertEquals(ActiveTaskContext.none(), decision.memoryContext());
        assertEquals(Set.of("README.md", "config.json"), decision.taskContract().expectedTargets());
    }

    @Test void expiredContextIsMarkedExpiredAndCleared() {
        ActiveTaskContext saved = readmeProposal();
        String userRequest = "make those changes";
        TaskContract rawContract = TaskContractResolver.fromUserRequest(userRequest);

        ActiveTaskContextPolicy.Decision decision = ActiveTaskContextPolicy.evaluate(
                userRequest,
                rawContract,
                saved,
                ArtifactGoal.fromActiveContext(saved),
                6);

        assertFalse(decision.consumed());
        assertEquals(ActiveTaskContext.State.EXPIRED, decision.planContext().state());
        assertEquals(ActiveTaskContext.none(), decision.memoryContext());
        assertFalse(decision.taskContract().mutationAllowed());
    }

    @Test void expiredContextDoesNotAttachToSmallTalkBoundaryTurn() {
        ActiveTaskContext saved = readmeProposal();
        String userRequest = "Hello friend, how are you?";
        TaskContract rawContract = TaskContractResolver.fromUserRequest(userRequest);

        ActiveTaskContextPolicy.Decision decision = ActiveTaskContextPolicy.evaluate(
                userRequest,
                rawContract,
                saved,
                ArtifactGoal.fromActiveContext(saved),
                6);

        assertFalse(decision.consumed());
        assertEquals(TaskType.SMALL_TALK, decision.taskContract().type());
        assertEquals(ActiveTaskContext.State.NONE, decision.planContext().state());
        assertEquals(ArtifactGoal.none(), decision.artifactGoal());
        assertEquals(ActiveTaskContext.none(), decision.memoryContext());
    }

    @Test void bareYesDoesNotConsumeProposalContext() {
        ActiveTaskContext saved = readmeProposal();
        String userRequest = "yes";
        TaskContract rawContract = TaskContractResolver.fromUserRequest(userRequest);

        ActiveTaskContextPolicy.Decision decision = ActiveTaskContextPolicy.evaluate(
                userRequest,
                rawContract,
                saved,
                ArtifactGoal.fromActiveContext(saved),
                3);

        assertFalse(decision.consumed());
        assertFalse(decision.taskContract().mutationAllowed());
    }

    private static ActiveTaskContext readmeProposal() {
        return ActiveTaskContext.proposedChanges(
                2,
                "trace-propose",
                List.of("README.md"),
                "Add title and usage.");
    }

    private static void assertNonActiveBaseline(TaskContract rawContract, ActiveTaskContext savedContext) {
        ActiveTaskContextPolicy.Decision decision = ActiveTaskContextPolicy.evaluate(
                rawContract.originalUserRequest(),
                rawContract,
                savedContext,
                ArtifactGoal.fromActiveContext(readmeProposal()),
                3);

        assertFalse(decision.consumed());
        assertEquals(rawContract, decision.taskContract());
        assertEquals(ActiveTaskContext.State.NONE, decision.planContext().state());
        assertEquals(ArtifactGoal.none(), decision.artifactGoal());
        assertEquals(ActiveTaskContext.none(), decision.memoryContext());
    }
}
