package dev.talos.runtime.toolcall;

import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.failure.FailurePolicy;
import dev.talos.runtime.ToolCallParser;
import dev.talos.safety.SafeLogFormatter;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@SuppressWarnings("resource") // LoopState.ctx owns the shared LlmClient for the active REPL session.
public final class ToolCallRepromptStage {
    private static final Logger LOG = LoggerFactory.getLogger(ToolCallRepromptStage.class);
    private static final int REPAIR_READ_ONLY_TOOL_BUDGET = 6;

    public boolean reprompt(LoopState state, ToolCallExecutionStage.IterationOutcome outcome) {
        if (outcome.approvalDeniedThisIteration()) {
            state.currentText = "[Tool loop stopped because the requested mutation was not approved.]";
            state.currentNativeCalls = List.of();
            LOG.debug("Stopping tool-call loop after denied mutating tool call; not re-prompting.");
            return false;
        }

        if (outcome.mutatingDeniedThisIteration()) {
            state.currentText = DeniedMutationResponseOnlySynthesizer.synthesize(state);
            state.currentNativeCalls = List.of();
            LOG.debug("Stopping tool-call loop after denied mutating tool call; not re-prompting.");
            return false;
        }

        if (outcome.pathPolicyBlockedThisIteration()) {
            Optional<ExpectedTargetScopeRepairPlanner.Plan> expectedTargetRepair =
                    ExpectedTargetScopeRepairPlanner.nextPlan(
                            state,
                            ToolRepromptRequestBuilder.currentNativeToolSpecs(state),
                            ToolCallSupport.latestUserRequestIn(state.messages));
            if (expectedTargetRepair.isPresent()) {
                ExpectedTargetScopeRepairPlanner.Plan repair = expectedTargetRepair.get();
                state.failureDecision = FailureDecision.continueLoop();
                state.setPendingActionObligation(
                        PendingActionObligation.expectedTargetScopeTargets(repair.expectedTargets()));
                state.expectedTargetScopeRepairPromptedKeys.add(repair.key());
                if (repair.exactReplacementRepair() != null) {
                    LocalTurnTraceCapture.recordRepair("PLANNED", repair.traceDetail());
                    state.currentText = "";
                    state.currentNativeCalls = List.of(repair.exactReplacementRepair());
                    return true;
                }
                return chatReprompt(state, repair.messages(), repair.tools(), repair.controls(), repair.retryName());
            }
            state.currentText = state.failureDecision.shouldStop()
                    ? ToolFailurePolicyStopAnswer.render(state, state.failureDecision)
                    : "[Tool loop stopped because a mutating path was blocked by workspace policy before approval.]";
            state.currentNativeCalls = List.of();
            LOG.debug("Stopping tool-call loop after pre-approval path policy block; not re-prompting.");
            return false;
        }

        if (state.staleEditRereadIgnoredPath != null && !state.staleEditRereadIgnoredPath.isBlank()) {
            state.failureDecision = FailureDecision.stop(
                    FailureAction.ASK_USER,
                    "failure policy stopped the tool loop because talos.edit_file was retried for path `"
                            + state.staleEditRereadIgnoredPath
                            + "` before rereading the file after a same-turn mutation changed it. "
                            + "No approval was requested for the stale retry and no additional file change was made.");
            state.currentText = ToolFailurePolicyStopAnswer.render(state, state.failureDecision);
            state.currentNativeCalls = List.of();
            LOG.debug("Stopping tool-call loop after stale edit retry ignored reread requirement for {}",
                    SafeLogFormatter.value(state.staleEditRereadIgnoredPath));
            return false;
        }

        TerminalReadOnlyStopAnswer.Answer terminalReadOnlyAnswer =
                TerminalReadOnlyStopAnswer.select(state, outcome);
        if (terminalReadOnlyAnswer != null) {
            state.currentText = terminalReadOnlyAnswer.text();
            state.currentNativeCalls = List.of();
            LOG.debug(terminalReadOnlyAnswer.logMessage());
            return false;
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
        // for all-success iterations — that path still avoids the 5-15
        // minute post-mutation bloviation observed on local 31B Q4 models.
        if (outcome.mutationsThisIteration() > 0 && outcome.failuresThisIteration() == 0) {
            if (StaticWebContinuationPlanner.staticWebVerificationAlreadyPasses(state)) {
                state.currentText = String.join("\n", outcome.mutationSummaries());
                state.currentNativeCalls = List.of();
                state.clearPendingActionObligation();
                LOG.debug("Stopping static web repair after verifier-passed mutation before expected-target progress.");
                return false;
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
                    return chatReprompt(state, plan.messages(), plan.tools(), plan.controls(), plan.retryName());
                }
            }
            if (remainingRepairTargets.isEmpty() && remainingExpectedTargets.isEmpty()) {
                state.currentText = String.join("\n", outcome.mutationSummaries());
                state.currentNativeCalls = List.of();
                LOG.debug("P0: skipping re-prompt after {} successful mutation(s) this iteration",
                        outcome.mutationsThisIteration());
                return false;
            }
            if (!remainingRepairTargets.isEmpty()) {
                LOG.debug("Continuing static repair after {} successful mutation(s); remaining full-write targets: {}",
                        outcome.mutationsThisIteration(), remainingRepairTargets);
            }
            if (!remainingExpectedTargets.isEmpty()) {
                LOG.debug("Continuing mutation task after {} successful mutation(s); remaining expected targets: {}",
                        outcome.mutationsThisIteration(), remainingExpectedTargets);
            }
        }

        if (outcome.mutationsThisIteration() > 0 && outcome.failuresThisIteration() > 0) {
            LOG.debug("CCR-020: re-prompting after partial success ({} mutation(s), {} failure(s) "
                    + "this iteration) so the model can retry the failed call(s)",
                    outcome.mutationsThisIteration(), outcome.failuresThisIteration());
            // fall through to the re-prompt path below
        }

        Optional<Boolean> repairBudgetStop =
                ToolRepairInspectionBudgetGate.tryStop(state, REPAIR_READ_ONLY_TOOL_BUDGET);
        if (repairBudgetStop.isPresent()) {
            return repairBudgetStop.get();
        }

        Optional<Boolean> mutationEvidenceBudget =
                ToolMutationEvidenceBudgetGate.tryContinueOrStop(state, REPAIR_READ_ONLY_TOOL_BUDGET);
        if (mutationEvidenceBudget.isPresent()) {
            return mutationEvidenceBudget.get();
        }

        FailureDecision failureDecision = FailurePolicy.defaults(state.maxIterations)
                .afterIteration(state, outcome);
        if (failureDecision.shouldStop()) {
            state.failureDecision = failureDecision;
            state.currentText = ToolFailurePolicyStopAnswer.render(state, failureDecision);
            state.currentNativeCalls = List.of();
            LOG.debug("Stopping tool-call loop by failure policy: {}", failureDecision.reason());
            return false;
        }

        if (state.iterations >= 3) {
            ToolCallSupport.compactOlderToolResultsInPlace(state.messages);
        }

        String userTask = ToolCallSupport.latestUserRequestIn(state.messages);
        Optional<SourceEvidenceExactRepairPlanner.Plan> sourceEvidenceRepair =
                SourceEvidenceExactRepairPlanner.nextPlan(
                        state,
                        ToolRepromptRequestBuilder.currentNativeToolSpecs(state),
                        userTask);
        if (sourceEvidenceRepair.isPresent()) {
            SourceEvidenceExactRepairPlanner.Plan repair = sourceEvidenceRepair.get();
            state.setPendingActionObligation(PendingActionObligation.expectedTargets(List.of(repair.path())));
            state.sourceEvidenceExactRepairPromptedKeys.add(repair.key());
            return chatReprompt(state, repair.messages(), repair.tools(), repair.controls(),
                    "source-evidence exact compact repair");
        }

        Optional<TargetReadbackCompactRepairPlanner.Plan> appendLineRepair =
                TargetReadbackCompactRepairPlanner.nextAppendLinePlan(
                        state,
                        ToolRepromptRequestBuilder.currentNativeToolSpecs(state),
                        userTask);
        if (appendLineRepair.isPresent()) {
            TargetReadbackCompactRepairPlanner.Plan repair = appendLineRepair.get();
            state.setPendingActionObligation(
                    PendingActionObligation.appendLineTargets(List.of(repair.path())));
            state.appendLineRepairPromptedPaths.add(repair.promptedPathKey());
            return chatReprompt(state, repair.messages(), repair.tools(), repair.controls(), repair.retryName());
        }

        Optional<TargetReadbackCompactRepairPlanner.Plan> oldStringMissRepair =
                TargetReadbackCompactRepairPlanner.nextOldStringMissPlan(
                        state,
                        ToolRepromptRequestBuilder.currentNativeToolSpecs(state),
                        userTask);
        if (oldStringMissRepair.isPresent()) {
            TargetReadbackCompactRepairPlanner.Plan repair = oldStringMissRepair.get();
            state.setPendingActionObligation(
                    PendingActionObligation.oldStringMissTargets(List.of(repair.path())));
            state.oldStringMissRepairPromptedPaths.add(repair.promptedPathKey());
            return chatReprompt(state, repair.messages(), repair.tools(), repair.controls(), repair.retryName());
        }

        List<String> remainingRepairTargets =
                StaticRepairTargetProgressAccounting.remainingFullRewriteRepairTargets(state);
        List<String> remainingExpectedTargets =
                ExpectedTargetProgressAccounting.remainingExpectedMutationTargets(state);
        boolean staticRepairObligationActive = !remainingRepairTargets.isEmpty()
                && (!state.staticWebFullRewriteRequiredTargets.isEmpty()
                || StaticRepairTargetProgressAccounting.hasStaticRepairContext(state)
                || state.hasPendingActionObligation());
        boolean expectedTargetObligationActive = !remainingExpectedTargets.isEmpty()
                && (outcome.mutationsThisIteration() > 0 || state.hasPendingActionObligation());
        if (staticRepairObligationActive) {
            state.setPendingActionObligation(
                    PendingActionObligation.staticRepairTargets(remainingRepairTargets));
        } else if (expectedTargetObligationActive) {
            state.setPendingActionObligation(
                    PendingActionObligation.expectedTargets(remainingExpectedTargets));
        } else {
            state.clearPendingActionObligation();
        }
        List<ToolSpec> repromptToolSpecs = ToolRepromptRequestBuilder.toolSpecs(
                state,
                staticRepairObligationActive,
                expectedTargetObligationActive);

        List<ChatMessage> requestMessages = List.of();
        try (ToolRepromptMessageOverlay ignored = ToolRepromptMessageOverlay.apply(
                state,
                remainingRepairTargets,
                remainingExpectedTargets,
                userTask)) {
            requestMessages = new ArrayList<>(ToolRepromptRequestBuilder.messages(
                    state,
                    staticRepairObligationActive,
                    remainingRepairTargets,
                    userTask));
            if (!chatRepromptResult(state, requestMessages, repromptToolSpecs,
                    ToolRepromptRequestBuilder.controls(state))) {
                return false;
            }
            return true;
        } catch (EngineException.ContextBudgetExceeded budget) {
            return ToolRepromptContextBudgetHandler.handle(state, budget, "tool-call loop continuation");
        } catch (EngineException.ConnectionFailed cf) {
            LOG.warn("Ollama not reachable during tool-call loop iteration {}: {}",
                    state.iterations, SafeLogFormatter.throwableMessage(cf));
            state.currentText = "[Ollama not reachable — tool loop aborted. " + cf.guidance() + "]";
            state.currentNativeCalls = List.of();
            return false;
        } catch (EngineException.ModelNotFound mnf) {
            LOG.warn("Model not found during tool-call loop iteration {}: {}",
                    state.iterations, SafeLogFormatter.value(mnf.model()));
            state.currentText = "[Model '" + mnf.model() + "' not found — tool loop aborted. " + mnf.guidance() + "]";
            state.currentNativeCalls = List.of();
            return false;
        } catch (EngineException.Transient tr) {
            LOG.warn("Transient error during tool-call loop iteration {}: {}",
                    state.iterations, SafeLogFormatter.throwableMessage(tr));
            try {
                Thread.sleep(400);
                LlmClient.StreamResult retryResult =
                        state.ctx.llm().chatFull(
                                requestMessages,
                                repromptToolSpecs,
                                ToolRepromptRequestBuilder.controls(state));
                state.currentText = retryResult.text();
                state.currentNativeCalls = retryResult.hasToolCalls()
                        ? new ArrayList<>(retryResult.toolCalls()) : List.of();
                if (state.currentText == null) state.currentText = "";
                if (state.currentText.isEmpty() && state.currentNativeCalls.isEmpty()) {
                    if (!state.pendingMutationSummaries.isEmpty()) {
                        state.currentText = String.join("\n", state.pendingMutationSummaries);
                    } else {
                        state.currentText = "(no answer from model after retry)";
                    }
                    return false;
                }
                return true;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                state.currentText = "[Interrupted during tool-call loop]";
                state.currentNativeCalls = List.of();
                return false;
            } catch (Exception retryEx) {
                if (retryEx instanceof EngineException.ContextBudgetExceeded budget) {
                    return ToolRepromptContextBudgetHandler.handle(state, budget, "transient retry continuation");
                }
                state.currentText = "[" + tr.guidance() + "]";
                state.currentNativeCalls = List.of();
                return false;
            }
        } catch (EngineException ee) {
            LOG.warn("Engine error during tool-call loop iteration {}: {}",
                    state.iterations, SafeLogFormatter.throwableMessage(ee));
            state.currentText = "[Engine error during tool loop: " + ee.getMessage() + "]";
            state.currentNativeCalls = List.of();
            return false;
        } catch (Exception e) {
            LOG.warn("LLM call failed during tool-call loop iteration {}: {}",
                    state.iterations, SafeLogFormatter.throwableMessage(e));
            state.currentText = "(error during follow-up LLM call: " + e.getMessage() + ")";
            state.currentNativeCalls = List.of();
            return false;
        }
    }

    private static boolean chatReprompt(
            LoopState state,
            List<ChatMessage> requestMessages,
            List<ToolSpec> repromptToolSpecs,
            ChatRequestControls controls,
            String retryName
    ) {
        try {
            return chatRepromptResult(state, requestMessages, repromptToolSpecs, controls);
        } catch (EngineException.ContextBudgetExceeded budget) {
            return ToolRepromptContextBudgetHandler.handle(state, budget, retryName);
        } catch (EngineException.ConnectionFailed cf) {
            LOG.warn("Ollama not reachable during {}: {}",
                    SafeLogFormatter.value(retryName), SafeLogFormatter.throwableMessage(cf));
            state.currentText = "[Ollama not reachable — tool loop aborted. " + cf.guidance() + "]";
            state.currentNativeCalls = List.of();
            return false;
        } catch (EngineException.ModelNotFound mnf) {
            LOG.warn("Model not found during {}: {}",
                    SafeLogFormatter.value(retryName), SafeLogFormatter.value(mnf.model()));
            state.currentText = "[Model '" + mnf.model() + "' not found — tool loop aborted. "
                    + mnf.guidance() + "]";
            state.currentNativeCalls = List.of();
            return false;
        } catch (EngineException ee) {
            LOG.warn("Engine error during {}: {}",
                    SafeLogFormatter.value(retryName), SafeLogFormatter.throwableMessage(ee));
            state.currentText = "[Engine error during tool loop: " + ee.getMessage() + "]";
            state.currentNativeCalls = List.of();
            return false;
        } catch (Exception e) {
            LOG.warn("LLM call failed during {}: {}",
                    SafeLogFormatter.value(retryName), SafeLogFormatter.throwableMessage(e));
            state.currentText = "(error during follow-up LLM call: " + e.getMessage() + ")";
            state.currentNativeCalls = List.of();
            return false;
        }
    }

    private static boolean chatRepromptResult(
            LoopState state,
            List<ChatMessage> requestMessages,
            List<ToolSpec> repromptToolSpecs,
            ChatRequestControls controls
    ) {
        LlmClient.StreamResult repromptResult =
                state.ctx.llm().chatFull(
                        requestMessages,
                        repromptToolSpecs,
                        controls);
        state.currentText = repromptResult.text();
        state.currentNativeCalls = repromptResult.hasToolCalls()
                ? new ArrayList<>(repromptResult.toolCalls()) : List.of();
        if (state.currentText == null) state.currentText = "";
        if (state.currentText.isEmpty() && state.currentNativeCalls.isEmpty()) {
            if (state.failPendingActionObligationAfterNoExecutableToolCalls()) {
                return false;
            }
            if (!state.pendingMutationSummaries.isEmpty()) {
                state.currentText = String.join("\n", state.pendingMutationSummaries);
            } else {
                state.currentText = "(no answer from model after tool execution)";
            }
            return false;
        }
        return true;
    }

    public boolean hitIterationLimit(LoopState state) {
        return state.iterations >= state.maxIterations
                && (!state.currentNativeCalls.isEmpty() || ToolCallParser.containsToolCalls(state.currentText));
    }

}
