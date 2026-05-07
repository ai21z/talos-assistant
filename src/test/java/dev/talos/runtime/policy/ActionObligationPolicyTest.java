package dev.talos.runtime.policy;

import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContractResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActionObligationPolicyTest {

    @Test
    void mutationAllowedApplyTurnRequiresMutatingTools() {
        var contract = TaskContractResolver.fromUserRequest(
                "I want to create a modern BMI calculator website to use! Can you make it?");

        assertEquals(
                ActionObligation.MUTATING_TOOL_REQUIRED,
                ActionObligationPolicy.derive(contract, ExecutionPhase.APPLY));
    }

    @Test
    void conditionalReviewFixApplyTurnUsesConditionalObligation() {
        var contract = TaskContractResolver.fromUserRequest(
                "Review the BMI calculator you just created and fix any obvious issue "
                        + "that would stop it from working in a browser.");

        assertEquals(
                ActionObligation.valueOf("CONDITIONAL_REVIEW_FIX"),
                ActionObligationPolicy.derive(contract, ExecutionPhase.APPLY));
    }

    @Test
    void explicitWorkspaceOperationApplyTurnRequiresWorkspaceOperationTool() {
        var contract = TaskContractResolver.fromUserRequest(
                "Move workspace-notes/readme-renamed.md to archive/readme-renamed.md.");

        assertEquals(
                ActionObligation.valueOf("WORKSPACE_OPERATION_REQUIRED"),
                ActionObligationPolicy.derive(contract, ExecutionPhase.APPLY));
    }

    @Test
    void directoryListingRequiresListDirOnly() {
        var contract = TaskContractResolver.fromUserRequest("What files are in this folder?");

        assertEquals(
                ActionObligation.LIST_DIR_ONLY,
                ActionObligationPolicy.derive(contract, ExecutionPhase.INSPECT));
    }

    @Test
    void privacyCapabilityPromptRequiresDirectAnswerOnly() {
        var contract = TaskContractResolver.fromUserRequest(
                "I am only chatting, please don't inspect my files. What can you do for me?");

        assertEquals(
                ActionObligation.DIRECT_ANSWER_ONLY,
                ActionObligationPolicy.derive(contract, ExecutionPhase.INSPECT));
    }
}
