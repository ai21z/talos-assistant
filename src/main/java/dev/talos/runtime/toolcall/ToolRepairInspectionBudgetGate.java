package dev.talos.runtime.toolcall;

import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.policy.ConditionalReviewFixPolicy;
import dev.talos.runtime.policy.ResponseObligationVerifier;
import dev.talos.runtime.repair.RepairPolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

final class ToolRepairInspectionBudgetGate {
    private static final Logger LOG = LoggerFactory.getLogger(ToolRepairInspectionBudgetGate.class);

    private ToolRepairInspectionBudgetGate() {
    }

    static Optional<Boolean> tryStop(LoopState state, int readOnlyToolBudget) {
        if (!repairReadOnlyBudgetExceeded(state, readOnlyToolBudget)) {
            return Optional.empty();
        }

        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        Optional<String> conditionalNoChange = ConditionalReviewFixPolicy
                .noChangeAnswerIfCurrentWorkspacePasses(
                        contract,
                        state.pathsReadThisTurn,
                        state.toolNames,
                        state.mutatingToolSuccesses,
                        state.workspace);
        if (conditionalNoChange.isPresent()) {
            state.currentText = conditionalNoChange.get();
            state.currentNativeCalls = List.of();
            state.clearPendingActionObligation();
            LOG.debug("Stopping conditional review/fix loop after inspection found no current static blocker.");
            return Optional.of(false);
        }

        String reason = "REPAIR_INSPECTION_ONLY: repair/fix turn inspected files with "
                + readOnlyInspectionAttemptCount(state)
                + " read-only/no-progress inspection attempt(s) but did not call write/edit before "
                + "the read-only repair budget was exhausted.";
        state.failureDecision = FailureDecision.stop(FailureAction.ASK_USER, reason);
        state.currentText = ResponseObligationVerifier.deterministicRepairInspectionOnlyAnswer();
        state.currentNativeCalls = List.of();
        LocalTurnTraceCapture.recordActionObligation(
                conditionalRepairObligationName(contract),
                "FAILED",
                reason,
                "REPAIR_INSPECTION_ONLY");
        LOG.debug("Stopping repair/fix loop after read-only inspection budget without mutation.");
        return Optional.of(false);
    }

    private static boolean repairReadOnlyBudgetExceeded(LoopState state, int readOnlyToolBudget) {
        if (state == null || state.toolNames.isEmpty()) return false;
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        boolean staticRepairMutation = hasStaticRepairContext(state)
                && contract != null
                && contract.mutationAllowed()
                && contract.mutationRequested();
        if (!isRepairOrFixMutationContract(contract) && !staticRepairMutation) return false;
        if (state.mutationSinceStart || state.mutatingToolSuccesses > 0) return false;
        if (state.failedCalls > 0) return false;
        for (dev.talos.runtime.ToolCallLoop.ToolOutcome outcome : state.toolOutcomes) {
            if (outcome == null || !outcome.success() || outcome.mutating()) return false;
        }
        int readOnlyCalls = 0;
        for (String toolName : state.toolNames) {
            if (!ToolCallSupport.isReadOnlyTool(toolName)) return false;
            readOnlyCalls++;
        }
        return readOnlyCalls + Math.max(0, state.cushionFiresRedundantRead) >= readOnlyToolBudget;
    }

    private static int readOnlyInspectionAttemptCount(LoopState state) {
        if (state == null) return 0;
        return Math.max(0, state.toolNames.size()) + Math.max(0, state.cushionFiresRedundantRead);
    }

    private static boolean isRepairOrFixMutationContract(TaskContract contract) {
        if (contract == null || !contract.mutationAllowed() || !contract.mutationRequested()) return false;
        String reason = contract.classificationReason();
        return "explicit-review-and-fix-request".equals(reason)
                || "repair-follow-up-inherits-previous-mutation-contract".equals(reason);
    }

    private static String conditionalRepairObligationName(TaskContract contract) {
        return ConditionalReviewFixPolicy.isConditionalReviewAndFix(contract)
                ? ActionObligation.CONDITIONAL_REVIEW_FIX.name()
                : ActionObligation.MUTATING_TOOL_REQUIRED.name();
    }

    private static boolean hasStaticRepairContext(LoopState state) {
        return state != null && !RepairPolicy.fullRewriteTargetsFromRepairContext(state.messages).isEmpty();
    }
}
