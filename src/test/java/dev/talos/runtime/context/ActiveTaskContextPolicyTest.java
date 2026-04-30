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
        assertTrue(decision.taskContract().originalUserRequest().contains("Add title and usage."));
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
}
