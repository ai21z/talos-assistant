package dev.talos.runtime.toolcall;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

final class ToolRepromptSuccessfulMutationDecision {
    private static final Logger LOG = LoggerFactory.getLogger(ToolRepromptSuccessfulMutationDecision.class);

    private ToolRepromptSuccessfulMutationDecision() {
    }

    static Optional<Boolean> tryHandle(
            LoopState state,
            ToolCallExecutionStage.IterationOutcome outcome
    ) {
        if (outcome.mutationsThisIteration() <= 0 || outcome.failuresThisIteration() != 0) {
            return Optional.empty();
        }

        // CCR-020: skip the post-mutation re-prompt only when every call in
        // this iteration succeeded. A partial-success iteration (at least
        // one mutation succeeded AND at least one call failed) MUST re-prompt
        // so the model can see the failure messages that were appended to
        // state.messages and retry the failed edits (or switch to write_file
        // as the error suggestion recommends). Skipping on partial success
        // is a workspace-integrity bug: one file gets edited while another
        // silently stays stale, and the loop terminates without retrying.
        //
        // The original P0 skip (see ToolCallLoopP0Test) is preserved intact
        // for all-success iterations; that path still avoids the 5-15
        // minute post-mutation bloviation observed on local 31B Q4 models.
        if (StaticWebContinuationPlanner.staticWebVerificationAlreadyPasses(state)) {
            state.finishWithAnswer(String.join("\n", outcome.mutationSummaries()));
            state.clearPendingActionObligation();
            LOG.debug("Stopping static web repair after verifier-passed mutation before expected-target progress.");
            return Optional.of(false);
        }
        List<String> remainingRepairTargets =
                StaticRepairTargetProgressAccounting.remainingFullRewriteRepairTargets(state);
        List<String> remainingExpectedTargets =
                ExpectedTargetProgressAccounting.remainingExpectedMutationTargets(state);
        if (remainingRepairTargets.isEmpty() && remainingExpectedTargets.isEmpty()) {
            Optional<StaticWebContinuationPlanner.Plan> staticWebPlan =
                    StaticWebContinuationPlanner.nextPlan(
                            state,
                            ToolRepromptRequestBuilder.currentNativeToolSpecs(state));
            if (staticWebPlan.isPresent()) {
                StaticWebContinuationPlanner.Plan plan = staticWebPlan.get();
                plan.pendingActionObligation().ifPresent(state::setPendingActionObligation);
                if (plan.missingTargets().isEmpty()) {
                    LOG.debug("Continuing static web creation after directory-only mutation.");
                } else {
                    LOG.debug("Continuing static web creation after verification found missing target(s): {}",
                            plan.missingTargets());
                }
                return Optional.of(ToolRepromptChatExecutor.execute(
                        state, plan.messages(), plan.tools(), plan.controls(), plan.retryName()));
            }
        }
        if (remainingRepairTargets.isEmpty() && remainingExpectedTargets.isEmpty()) {
            state.finishWithAnswer(String.join("\n", outcome.mutationSummaries()));
            LOG.debug("P0: skipping re-prompt after {} successful mutation(s) this iteration",
                    outcome.mutationsThisIteration());
            return Optional.of(false);
        }
        if (!remainingRepairTargets.isEmpty()) {
            LOG.debug("Continuing static repair after {} successful mutation(s); remaining full-write targets: {}",
                    outcome.mutationsThisIteration(), remainingRepairTargets);
        }
        if (!remainingExpectedTargets.isEmpty()) {
            LOG.debug("Continuing mutation task after {} successful mutation(s); remaining expected targets: {}",
                    outcome.mutationsThisIteration(), remainingExpectedTargets);
        }
        return Optional.empty();
    }
}
