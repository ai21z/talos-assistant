package dev.talos.runtime.toolcall;

import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

final class ToolRepromptPathPolicyBlockedDecision {
    private static final Logger LOG = LoggerFactory.getLogger(ToolRepromptPathPolicyBlockedDecision.class);

    private ToolRepromptPathPolicyBlockedDecision() {
    }

    static Optional<Boolean> tryHandle(
            LoopState state,
            ToolCallExecutionStage.IterationOutcome outcome
    ) {
        if (outcome == null || !outcome.pathPolicyBlockedThisIteration()) {
            return Optional.empty();
        }

        Optional<ExpectedTargetScopeRepairPlanner.Plan> expectedTargetRepair =
                ExpectedTargetScopeRepairPlanner.nextPlan(
                        state,
                        ToolRepromptRequestBuilder.currentNativeToolSpecs(state),
                        TurnTaskContractCapture.originalUserRequestOrResolve(
                                state.messages));
        if (expectedTargetRepair.isPresent()) {
            ExpectedTargetScopeRepairPlanner.Plan repair = expectedTargetRepair.get();
            state.failureDecision = FailureDecision.continueLoop();
            state.setPendingActionObligation(
                    PendingActionObligation.expectedTargetScopeTargets(repair.expectedTargets()));
            state.expectedTargetScopeRepairPromptedKeys.add(repair.key());
            if (repair.exactReplacementRepair() != null) {
                LocalTurnTraceCapture.recordRepair("PLANNED", repair.traceDetail());
                state.setRuntimeNativeCalls(
                        "",
                        List.of(repair.exactReplacementRepair()),
                        LocalTurnTraceCapture.RuntimeToolAuthorityReason
                                .EXPECTED_TARGET_SCOPE_REPAIR);
                return Optional.of(true);
            }
            return Optional.of(ToolRepromptChatExecutor.execute(
                    state, repair.messages(), repair.tools(), repair.controls(), repair.retryName()));
        }
        Optional<StaticWebContinuationPlanner.Plan> blockedDirectoryContinuation =
                StaticWebContinuationPlanner.blockedUnrequestedDirectoryPlan(
                        state,
                        ToolRepromptRequestBuilder.currentNativeToolSpecs(state));
        if (blockedDirectoryContinuation.isPresent()) {
            StaticWebContinuationPlanner.Plan continuation =
                    blockedDirectoryContinuation.get();
            state.failureDecision = FailureDecision.continueLoop();
            LocalTurnTraceCapture.recordRepair(
                    "PLANNED",
                    "STATIC_WEB_BLOCKED_DIRECTORY_CONTINUATION: rejected "
                            + "unrequested directory authority; continuing with write_file only");
            return Optional.of(ToolRepromptChatExecutor.execute(
                    state,
                    continuation.messages(),
                    continuation.tools(),
                    continuation.controls(),
                    continuation.retryName()));
        }
        state.finishWithAnswer(state.failureDecision.shouldStop()
                ? ToolFailurePolicyStopAnswer.render(state, state.failureDecision)
                : "[Tool loop stopped because a mutating path was blocked by workspace policy before approval.]");
        LOG.debug("Stopping tool-call loop after pre-approval path policy block; not re-prompting.");
        return Optional.of(false);
    }
}
