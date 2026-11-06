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
        // The P0 skip (see ToolCallLoopP0Test) avoids the 5-15 minute
        // post-mutation reply observed on local 31B Q4 models. Since P20 it
        // ends an all-success round with nothing left only when request-bound
        // proof holds or no further round can run (MutationContinuationDecision).
        List<String> remainingRepairTargets =
                StaticRepairTargetProgressAccounting.remainingFullRewriteRepairTargets(state);
        List<String> remainingExpectedTargets =
                ExpectedTargetProgressAccounting.remainingExpectedMutationTargets(state);
        boolean nothingRemains = remainingRepairTargets.isEmpty() && remainingExpectedTargets.isEmpty();
        if (StaticWebContinuationPlanner.staticWebVerificationAlreadyPasses(state)) {
            // P20 (D16 option A): a passed verification still stops before target progress while a target remains.
            // With nothing left it takes the D14 decision, because a pass that is not bound to the request does not
            // show that every requested change was made.
            if (nothingRemains) {
                MutationContinuationDecision.Decision continuation = MutationContinuationDecision.decide(state);
                if (continuation.continues()) {
                    LOG.debug("Continuing after a verifier-passed mutation without request-bound proof");
                    return Optional.empty();
                }
            }
            state.finishWithToolAnswer(String.join("\n", summaries(state, outcome)));
            state.clearPendingActionObligation();
            LOG.debug("Stopping static web repair after verifier-passed mutation before expected-target progress.");
            return Optional.of(false);
        }
        if (nothingRemains) {
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
                            dev.talos.safety.SafeLogFormatter.value(plan.missingTargets()));
                }
                return Optional.of(ToolRepromptChatExecutor.execute(
                        state, plan.messages(), plan.tools(), plan.controls(), plan.retryName()));
            }
        }
        if (nothingRemains) {
            // P20 (D14 option A): target progress alone does not end the turn. The summaries are the answer only
            // when request-bound proof holds or no further round can run.
            MutationContinuationDecision.Decision continuation = MutationContinuationDecision.decide(state);
            if (!continuation.continues()) {
                state.finishWithToolAnswer(String.join("\n", summaries(state, outcome)));
                LOG.debug("P0: skipping re-prompt after {} successful mutation(s) this iteration ({})",
                        outcome.mutationsThisIteration(), continuation.kind());
                return Optional.of(false);
            }
            LOG.debug("Continuing after {} successful mutation(s) without request-bound proof",
                    outcome.mutationsThisIteration());
            return Optional.empty();
        }
        if (!remainingRepairTargets.isEmpty()) {
            LOG.debug("Continuing static repair after {} successful mutation(s); remaining full-write targets: {}",
                    outcome.mutationsThisIteration(), dev.talos.safety.SafeLogFormatter.value(remainingRepairTargets));
        }
        if (!remainingExpectedTargets.isEmpty()) {
            LOG.debug("Continuing mutation task after {} successful mutation(s); remaining expected targets: {}",
                    outcome.mutationsThisIteration(), dev.talos.safety.SafeLogFormatter.value(remainingExpectedTargets));
        }
        return Optional.empty();
    }

    /** After a continuation the answer lists every change of the turn, not only the last round's (P20). */
    private static List<String> summaries(LoopState state, ToolCallExecutionStage.IterationOutcome outcome) {
        return state.mutationContinuations > 0 ? state.pendingMutationSummaries : outcome.mutationSummaries();
    }
}
