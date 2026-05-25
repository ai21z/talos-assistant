package dev.talos.runtime.toolcall;

import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.failure.FailurePolicy;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallParser;
import dev.talos.runtime.repair.RepairInstruction;
import dev.talos.runtime.repair.RepairPolicy;
import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.policy.ConditionalReviewFixPolicy;
import dev.talos.runtime.policy.ResponseObligationVerifier;
import dev.talos.safety.SafeLogFormatter;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.workspace.WorkspaceOperationIntent;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolAliasPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

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
            state.currentText = responseOnlyAfterDeniedMutation(state);
            state.currentNativeCalls = List.of();
            LOG.debug("Stopping tool-call loop after denied mutating tool call; not re-prompting.");
            return false;
        }

        if (outcome.pathPolicyBlockedThisIteration()) {
            Optional<ExpectedTargetScopeRepairPlanner.Plan> expectedTargetRepair =
                    ExpectedTargetScopeRepairPlanner.nextPlan(
                            state,
                            currentNativeToolSpecs(state),
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
                    ? failurePolicyStopMessage(state, state.failureDecision)
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
            state.currentText = failurePolicyStopMessage(state, state.failureDecision);
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
            List<String> remainingRepairTargets = remainingFullRewriteRepairTargets(state);
            List<String> remainingExpectedTargets = remainingExpectedMutationTargets(state);
            if (remainingRepairTargets.isEmpty() && remainingExpectedTargets.isEmpty()) {
                Optional<StaticWebContinuationPlanner.Plan> staticWebPlan =
                        StaticWebContinuationPlanner.nextPlan(state, currentNativeToolSpecs(state));
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

        if (repairReadOnlyBudgetExceeded(state)) {
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
                return false;
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
            return false;
        }

        if (mutationReadOnlyBudgetExceeded(state)) {
            CompactMutationContinuationOutcome compactMutation =
                    tryCompactMutationContinuation(
                            state,
                            "read-only mutation evidence budget",
                            "read-only mutation evidence budget was exhausted after "
                                    + readOnlyInspectionAttemptCount(state)
                                    + " read-only/no-progress inspection attempt(s)");
            if (compactMutation == CompactMutationContinuationOutcome.CONTINUE_LOOP) {
                LOG.info("Continuing mutation task with compact continuation after read-only inspection budget.");
                return true;
            }
            if (compactMutation == CompactMutationContinuationOutcome.STOP_TURN) {
                return false;
            }
        }

        FailureDecision failureDecision = FailurePolicy.defaults(state.maxIterations)
                .afterIteration(state, outcome);
        if (failureDecision.shouldStop()) {
            state.failureDecision = failureDecision;
            state.currentText = failurePolicyStopMessage(state, failureDecision);
            state.currentNativeCalls = List.of();
            LOG.debug("Stopping tool-call loop by failure policy: {}", failureDecision.reason());
            return false;
        }

        if (state.iterations >= 3) {
            ToolCallSupport.compactOlderToolResultsInPlace(state.messages);
        }

        String userTask = ToolCallSupport.latestUserRequestIn(state.messages);
        Optional<SourceEvidenceExactRepairPlanner.Plan> sourceEvidenceRepair =
                SourceEvidenceExactRepairPlanner.nextPlan(state, currentNativeToolSpecs(state), userTask);
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
                        currentNativeToolSpecs(state),
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
                        currentNativeToolSpecs(state),
                        userTask);
        if (oldStringMissRepair.isPresent()) {
            TargetReadbackCompactRepairPlanner.Plan repair = oldStringMissRepair.get();
            state.setPendingActionObligation(
                    PendingActionObligation.oldStringMissTargets(List.of(repair.path())));
            state.oldStringMissRepairPromptedPaths.add(repair.promptedPathKey());
            return chatReprompt(state, repair.messages(), repair.tools(), repair.controls(), repair.retryName());
        }

        int staleRepairIndex = -1;
        Optional<RepairInstruction> staleRepair = nextStaleEditRepair(state);
        if (staleRepair.isPresent()) {
            state.messages.add(ChatMessage.system(staleRepair.get().instruction()));
            state.staleEditRepairPromptedPaths.add(staleRepair.get().path());
            staleRepairIndex = state.messages.size() - 1;
        }

        int emptyRepairIndex = -1;
        Optional<RepairInstruction> repair = nextEmptyEditRepair(state);
        if (repair.isPresent()) {
            state.messages.add(ChatMessage.system(repair.get().instruction()));
            state.emptyEditRepairPromptedPaths.add(repair.get().path());
            emptyRepairIndex = state.messages.size() - 1;
        }

        int repairProgressIndex = -1;
        List<String> remainingRepairTargets = remainingFullRewriteRepairTargets(state);
        if (!remainingRepairTargets.isEmpty()) {
            state.messages.add(ChatMessage.system(
                    "[Static repair progress] Continue the bounded repair. Remaining full-file "
                            + "replacement targets: " + String.join(", ", remainingRepairTargets)
                            + ". Use talos.write_file with complete corrected file content for each remaining target. "
                            + "Do not claim completion until static verification passes."));
            repairProgressIndex = state.messages.size() - 1;
        }

        int expectedProgressIndex = -1;
        List<String> remainingExpectedTargets = remainingExpectedMutationTargets(state);
        if (!remainingExpectedTargets.isEmpty()) {
            state.messages.add(ChatMessage.system(
                    "[Expected target progress] Continue this mutation task. Remaining expected target paths "
                            + "not successfully mutated in this turn: " + String.join(", ", remainingExpectedTargets)
                            + ". Use the visible write/edit tools to mutate these exact paths before answering. "
                            + "Similar filenames are not substitutes. For small static web files, prefer "
                            + "talos.write_file with complete file content. Do not claim completion until "
                            + "static verification passes."));
            expectedProgressIndex = state.messages.size() - 1;
        }
        boolean staticRepairObligationActive = !remainingRepairTargets.isEmpty()
                && (!state.staticWebFullRewriteRequiredTargets.isEmpty()
                || hasStaticRepairContext(state)
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
        List<ToolSpec> repromptToolSpecs = repromptToolSpecs(
                state,
                staticRepairObligationActive,
                expectedTargetObligationActive);

        int anchorIndex = -1;
        if (userTask != null && !userTask.isBlank()) {
            String pinned = userTask.length() <= 500 ? userTask : userTask.substring(0, 500) + "…";
            state.messages.add(ChatMessage.system("[Current task — stay focused on this] " + pinned));
            anchorIndex = state.messages.size() - 1;
        }
        List<ChatMessage> requestMessages = repromptMessages(
                state,
                staticRepairObligationActive,
                remainingRepairTargets,
                userTask);

        try {
            if (!chatRepromptResult(state, requestMessages, repromptToolSpecs, repromptControls(state))) {
                return false;
            }
            return true;
        } catch (EngineException.ContextBudgetExceeded budget) {
            return stopAfterContextBudgetExceeded(state, budget, "tool-call loop continuation");
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
                                repromptControls(state));
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
                    return stopAfterContextBudgetExceeded(state, budget, "transient retry continuation");
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
        } finally {
            if (anchorIndex >= 0 && anchorIndex < state.messages.size()) {
                ChatMessage m = state.messages.get(anchorIndex);
                if ("system".equals(m.role())
                        && m.content() != null
                        && m.content().startsWith("[Current task")) {
                    state.messages.remove(anchorIndex);
                }
            }
            if (expectedProgressIndex >= 0 && expectedProgressIndex < state.messages.size()) {
                ChatMessage m = state.messages.get(expectedProgressIndex);
                if ("system".equals(m.role())
                        && m.content() != null
                        && m.content().startsWith("[Expected target progress]")) {
                    state.messages.remove(expectedProgressIndex);
                }
            }
            if (repairProgressIndex >= 0 && repairProgressIndex < state.messages.size()) {
                ChatMessage m = state.messages.get(repairProgressIndex);
                if ("system".equals(m.role())
                        && m.content() != null
                        && m.content().startsWith("[Static repair progress]")) {
                    state.messages.remove(repairProgressIndex);
                }
            }
            if (emptyRepairIndex >= 0 && emptyRepairIndex < state.messages.size()) {
                ChatMessage m = state.messages.get(emptyRepairIndex);
                if ("system".equals(m.role())
                        && m.content() != null
                        && m.content().startsWith("[Edit repair required]")) {
                    state.messages.remove(emptyRepairIndex);
                }
            }
            if (staleRepairIndex >= 0 && staleRepairIndex < state.messages.size()) {
                ChatMessage m = state.messages.get(staleRepairIndex);
                if ("system".equals(m.role())
                        && m.content() != null
                        && m.content().startsWith("[Stale edit repair required]")) {
                    state.messages.remove(staleRepairIndex);
                }
            }
        }
    }

    private static boolean stopAfterContextBudgetExceeded(
            LoopState state,
            EngineException.ContextBudgetExceeded budget,
            String retryName
    ) {
        String detail = ResponseObligationVerifier.contextBudgetRetrySkippedDetail(budget);
        LocalTurnTraceCapture.warning("CONTEXT_BUDGET_RETRY_SKIPPED", detail);
        if (state != null && state.failPendingActionObligation(detail)) {
            LOG.info("Skipping {} because it exceeded the local context budget.", retryName);
            return false;
        }
        CompactMutationContinuationOutcome compactMutation =
                tryCompactMutationContinuation(
                        state,
                        retryName,
                        "exceeded context budget: "
                                + ResponseObligationVerifier.contextBudgetRetrySkippedDetail(budget));
        if (compactMutation == CompactMutationContinuationOutcome.CONTINUE_LOOP) {
            LOG.info("Continuing {} with compact mutation continuation after context budget overflow.",
                    retryName);
            return true;
        }
        if (compactMutation == CompactMutationContinuationOutcome.STOP_TURN) {
            return false;
        }
        if (CompactReadOnlyEvidenceContinuation.tryAnswer(state, retryName)) {
            LOG.info("Answered {} with compact read-only evidence continuation after context budget overflow.",
                    retryName);
            return false;
        }
        if (state != null) {
            state.failureDecision = FailureDecision.stop(
                    FailureAction.ASK_USER,
                    "Context budget prevented " + retryName + ". " + detail);
            state.currentText = ResponseObligationVerifier
                    .deterministicContextBudgetRetrySkippedAnswer(retryName, budget);
            state.currentNativeCalls = List.of();
        }
        LOG.info("Skipping {} because it exceeded the local context budget.", retryName);
        return false;
    }

    private enum CompactMutationContinuationOutcome {
        NOT_APPLICABLE,
        CONTINUE_LOOP,
        STOP_TURN
    }

    private static CompactMutationContinuationOutcome tryCompactMutationContinuation(
            LoopState state,
            String retryName,
            String reason
    ) {
        Optional<CompactMutationContinuationPlanner.Plan> continuation =
                CompactMutationContinuationPlanner.planForContextBudget(
                        state,
                        currentNativeToolSpecs(state),
                        retryName);
        if (continuation.isEmpty()) return CompactMutationContinuationOutcome.NOT_APPLICABLE;

        CompactMutationContinuationPlanner.Plan compact = continuation.get();
        try {
            LlmClient.StreamResult result = state.ctx.llm().chatFull(
                    compact.messages(),
                    compact.tools(),
                    compact.controls());
            state.currentText = result.text() == null ? "" : result.text();
            state.currentNativeCalls = result.hasToolCalls()
                    ? new ArrayList<>(result.toolCalls())
                    : List.of();
            LocalTurnTraceCapture.warning(
                    "COMPACT_MUTATION_CONTINUATION",
                    "used compact mutation continuation after " + retryName
                            + ": "
                            + (reason == null || reason.isBlank() ? "compact retry requested" : reason));
            LocalTurnTraceCapture.recordActionObligation(
                    ActionObligation.MUTATING_TOOL_REQUIRED.name(),
                    "RETRIED_COMPACT_CONTEXT",
                    "compact mutation continuation retried current request with narrowed write/edit tools");
            if (!state.currentNativeCalls.isEmpty()
                    || ToolCallParser.containsToolCalls(state.currentText)) {
                return CompactMutationContinuationOutcome.CONTINUE_LOOP;
            }
            state.failureDecision = FailureDecision.stop(
                    FailureAction.ASK_USER,
                    "COMPACT_MUTATION_CONTINUATION_NO_TOOL: compact mutation continuation returned no write/edit tool calls.");
            state.currentText = ResponseObligationVerifier
                    .deterministicNoActionAnswer(ActionObligation.MUTATING_TOOL_REQUIRED);
            state.currentNativeCalls = List.of();
            return CompactMutationContinuationOutcome.STOP_TURN;
        } catch (EngineException.ContextBudgetExceeded budget) {
            LocalTurnTraceCapture.warning(
                    "COMPACT_MUTATION_CONTINUATION_CONTEXT_BUDGET_EXCEEDED",
                    ResponseObligationVerifier.contextBudgetRetrySkippedDetail(budget));
            return CompactMutationContinuationOutcome.NOT_APPLICABLE;
        } catch (EngineException ee) {
            LocalTurnTraceCapture.warning(
                    "COMPACT_MUTATION_CONTINUATION_FAILED",
                    ee.getMessage() == null ? ee.getClass().getSimpleName() : ee.getMessage());
            return CompactMutationContinuationOutcome.NOT_APPLICABLE;
        } catch (Exception e) {
            LocalTurnTraceCapture.warning(
                    "COMPACT_MUTATION_CONTINUATION_FAILED",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return CompactMutationContinuationOutcome.NOT_APPLICABLE;
        }
    }

    private static boolean readOnlyProgressOnly(LoopState state) {
        if (state == null || state.toolOutcomes.isEmpty()) return false;
        for (ToolCallLoop.ToolOutcome outcome : state.toolOutcomes) {
            if (outcome == null || !outcome.success()) return false;
            if (!ToolCallSupport.isReadOnlyTool(outcome.toolName()) || outcome.mutating()) {
                return false;
            }
        }
        return true;
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
            return stopAfterContextBudgetExceeded(state, budget, retryName);
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

    private static List<ToolSpec> repromptToolSpecs(
            LoopState state,
            boolean staticRepairProgress,
            boolean expectedTargetProgress
    ) {
        List<ToolSpec> base = currentNativeToolSpecs(state);
        if (base == null || base.isEmpty()) return base;
        if (staticRepairProgress) {
            List<ToolSpec> narrowed = filterTools(base, List.of("talos.write_file"));
            return narrowed.isEmpty() ? base : narrowed;
        }
        if (expectedTargetProgress) {
            List<ToolSpec> narrowed = filterTools(base, List.of("talos.write_file", "talos.edit_file"));
            return narrowed.isEmpty() ? base : narrowed;
        }
        return base;
    }

    private static List<ChatMessage> repromptMessages(
            LoopState state,
            boolean staticRepairObligationActive,
            List<String> remainingRepairTargets,
            String userTask
    ) {
        if (!staticRepairObligationActive) {
            return state == null ? List.of() : state.messages;
        }
        List<ChatMessage> out = new ArrayList<>();
        out.add(ChatMessage.system("""
                You are Talos, a local-first workspace assistant.
                This is a bounded static-repair continuation. Use the available file-write tool to repair the exact remaining target paths.
                Do not answer in prose instead of calling the required tool. Do not claim completion until tool-backed changes have executed.
                """));
        lastStaticVerificationRepairContext(state.messages)
                .map(message -> enrichStaticRepairContextForReprompt(message, state))
                .ifPresent(out::add);
        out.add(ChatMessage.system(
                "[Static repair progress] Continue the bounded repair. Remaining full-file "
                        + "replacement targets: " + String.join(", ", remainingRepairTargets)
                        + ". Use talos.write_file with complete corrected file content for each remaining target. "
                        + "Do not claim completion until static verification passes."));
        String currentTask = userTask == null || userTask.isBlank()
                ? "Continue the bounded static repair."
                : userTask.strip();
        out.add(ChatMessage.user(currentTask));
        return out;
    }

    private static Optional<ChatMessage> lastStaticVerificationRepairContext(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return Optional.empty();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message != null
                    && "system".equals(message.role())
                    && message.content() != null
                    && message.content().startsWith("[Static verification repair context]")) {
                return Optional.of(message);
            }
        }
        return Optional.empty();
    }

    private static ChatMessage enrichStaticRepairContextForReprompt(ChatMessage message, LoopState state) {
        if (message == null || message.content() == null) return message;
        String enriched = RepairPolicy.enrichSelectorFactsForRepairContext(
                message.content(),
                state == null ? null : state.workspace);
        if (enriched.equals(message.content())) return message;
        return ChatMessage.system(enriched);
    }

    private static List<ToolSpec> currentNativeToolSpecs(LoopState state) {
        if (state == null || state.ctx == null) return List.of();
        if (state.ctx.nativeToolSpecs() != null) {
            return state.ctx.nativeToolSpecs();
        }
        if (state.ctx.llm() != null) {
            return state.ctx.llm().getToolSpecs();
        }
        return List.of();
    }

    private static List<ToolSpec> filterTools(List<ToolSpec> specs, List<String> allowedNames) {
        if (specs == null || specs.isEmpty() || allowedNames == null || allowedNames.isEmpty()) {
            return List.of();
        }
        return specs.stream()
                .filter(spec -> spec != null && allowedNames.contains(spec.name()))
                .toList();
    }

    public boolean hitIterationLimit(LoopState state) {
        return state.iterations >= state.maxIterations
                && (!state.currentNativeCalls.isEmpty() || ToolCallParser.containsToolCalls(state.currentText));
    }

    private static ChatRequestControls repromptControls(LoopState state) {
        return repromptControls(state, "pending-action-obligation");
    }

    private static ChatRequestControls repromptControls(LoopState state, String debugTag) {
        if (state == null
                || state.ctx == null
                || state.ctx.llm() == null
                || !state.hasPendingActionObligation()
                || !state.ctx.llm().supportsRequiredToolChoice()
                || !hasMutatingTool(state.ctx.nativeToolSpecs())) {
            return ChatRequestControls.defaults();
        }
        List<String> tags = new ArrayList<>(List.of("pending-action-obligation"));
        if (debugTag != null && !debugTag.isBlank() && !tags.contains(debugTag)) {
            tags.add(debugTag);
        }
        return new ChatRequestControls(
                ToolChoiceMode.REQUIRED,
                "",
                ResponseFormatMode.TEXT,
                "",
                tags);
    }

    private static boolean hasMutatingTool(List<ToolSpec> specs) {
        if (specs == null || specs.isEmpty()) return false;
        for (ToolSpec spec : specs) {
            String name = spec == null ? "" : spec.name();
            if ("talos.write_file".equals(name) || "talos.edit_file".equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean repairReadOnlyBudgetExceeded(LoopState state) {
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
        return readOnlyCalls + Math.max(0, state.cushionFiresRedundantRead) >= REPAIR_READ_ONLY_TOOL_BUDGET;
    }

    private static boolean mutationReadOnlyBudgetExceeded(LoopState state) {
        if (state == null || state.toolNames.isEmpty()) return false;
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        if (contract == null || !contract.mutationAllowed() || !contract.mutationRequested()) return false;
        if (WorkspaceOperationIntent.detect(contract).isPresent()) return false;
        if (state.mutationSinceStart || state.mutatingToolSuccesses > 0) return false;
        if (state.failedCalls > 0) return false;
        if (!readOnlyProgressOnly(state)) return false;
        if (!CompactMutationContinuationPlanner.hasMutationTargets(state, contract)) return false;
        return readOnlyInspectionAttemptCount(state) >= REPAIR_READ_ONLY_TOOL_BUDGET;
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

    private static String failurePolicyStopMessage(LoopState state, FailureDecision decision) {
        String reason = decision == null || decision.reason().isBlank()
                ? "repeated tool failures"
                : decision.reason();
        String message = "[Tool loop stopped by failure policy: "
                + reason
                + " Review the latest tool errors before retrying.]";
        String context = failurePolicyRuntimeContext(state, reason);
        if (context.isBlank()) return message;
        return message + "\n\n" + context;
    }

    private static String failurePolicyRuntimeContext(LoopState state, String reason) {
        if (state == null || reason == null || !reason.toLowerCase(java.util.Locale.ROOT).contains("no-progress")) {
            return "";
        }
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        if (contract == null || contract.type() == TaskType.UNKNOWN) return "";
        StringBuilder out = new StringBuilder("Runtime context:\n");
        out.append("- task contract: ").append(contract.type()).append('\n');
        out.append("- mutationAllowed=").append(contract.mutationAllowed()).append('\n');
        out.append("- successful mutations: ").append(state.mutatingToolSuccesses).append('\n');
        if (!contract.mutationAllowed()) {
            out.append("- mutating tools were not available for this turn's contract; ")
                    .append("use an explicit create/edit/fix request if you intend a workspace change.\n");
        }
        return out.toString().stripTrailing();
    }

    private static String responseOnlyAfterDeniedMutation(LoopState state) {
        if (state == null || state.ctx == null || state.ctx.llm() == null) {
            return deniedMutationStopMessage();
        }

        state.messages.add(ChatMessage.system(
                "[Tool policy stop] The latest mutating tool call was rejected by Talos policy. "
                        + "Do not call any more tools in this turn. Answer the user's request using only "
                        + "the tool results already gathered. If the gathered evidence is insufficient, "
                        + "say exactly what was inspected and what remains unknown."));
        int anchorIndex = state.messages.size() - 1;

        try {
            LlmClient.StreamResult terminal =
                    state.ctx.llm().chatFull(state.messages, state.ctx.nativeToolSpecs());
            String text = terminal.text() == null ? "" : terminal.text();
            if (terminal.hasToolCalls()) {
                return deniedMutationStopMessage();
            }
            String stripped = ToolCallParser.stripToolCalls(text).strip();
            if (stripped.isBlank() || ToolCallParser.containsToolCalls(text)) {
                return deniedMutationStopMessage();
            }
            return stripped;
        } catch (Exception e) {
            LOG.warn("Response-only synthesis after denied mutation failed: {}", SafeLogFormatter.throwableMessage(e));
            return deniedMutationStopMessage();
        } finally {
            if (anchorIndex < state.messages.size()) {
                ChatMessage m = state.messages.get(anchorIndex);
                if ("system".equals(m.role())
                        && m.content() != null
                        && m.content().startsWith("[Tool policy stop]")) {
                    state.messages.remove(anchorIndex);
                }
            }
        }
    }

    private static String deniedMutationStopMessage() {
        return "[Tool loop stopped because a mutating tool was not allowed for this turn.]";
    }

    private static String canonicalToolName(String toolName) {
        ToolAliasPolicy.Decision decision = ToolAliasPolicy.resolve(toolName);
        if (decision.accepted() && decision.canonicalToolName() != null && !decision.canonicalToolName().isBlank()) {
            return decision.canonicalToolName();
        }
        return toolName == null ? "" : toolName;
    }

    static Optional<RepairInstruction> nextStaleEditRepair(LoopState state) {
        return RepairPolicy.nextStaleEditRepair(state);
    }

    static String staleEditRepairInstruction(String path) {
        return RepairPolicy.staleEditRepairInstruction(path);
    }

    static Optional<RepairInstruction> nextEmptyEditRepair(LoopState state) {
        return RepairPolicy.nextEmptyEditRepair(state);
    }

    static String emptyEditRepairInstruction(String path) {
        return RepairPolicy.emptyEditRepairInstruction(path);
    }

    private static List<String> remainingFullRewriteRepairTargets(LoopState state) {
        if (state == null) return List.of();
        Set<String> required = new java.util.LinkedHashSet<>(
                RepairPolicy.fullRewriteTargetsFromRepairContext(state.messages));
        required.addAll(state.staticWebFullRewriteRequiredTargets);
        if (required.isEmpty()) return List.of();
        Set<String> successfullyMutated = new java.util.HashSet<>();
        for (dev.talos.runtime.ToolCallLoop.ToolOutcome outcome : state.toolOutcomes) {
            if (outcome == null || !outcome.success() || !outcome.mutating()) continue;
            String path = ToolCallSupport.normalizePath(outcome.pathHint());
            if (!path.isBlank()) successfullyMutated.add(path);
        }
        return required.stream()
                .map(ToolCallSupport::normalizePath)
                .filter(path -> !path.isBlank())
                .filter(path -> !successfullyMutated.contains(path))
                .sorted()
                .toList();
    }

    private static List<String> remainingExpectedMutationTargets(LoopState state) {
        if (state == null || state.messages == null) return List.of();
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        if (contract == null || !contract.mutationAllowed()) {
            return List.of();
        }
        if (!RepairPolicy.fullRewriteTargetsFromRepairContext(state.messages).isEmpty()
                || !state.staticWebFullRewriteRequiredTargets.isEmpty()) {
            return List.of();
        }
        String latestUserRequest = ToolCallSupport.latestUserRequestIn(state.messages);
        Set<String> expectedTargets = contract.expectedTargets().isEmpty()
                ? TaskContractResolver.extractExpectedTargets(latestUserRequest)
                : contract.expectedTargets();
        if (expectedTargets.isEmpty()) {
            return List.of();
        }
        Set<String> satisfiedTargets = new java.util.HashSet<>();
        for (dev.talos.runtime.ToolCallLoop.ToolOutcome outcome : state.toolOutcomes) {
            if (outcome == null || !outcome.success() || !outcome.mutating()) continue;
            addSatisfiedExpectedTargetKeys(satisfiedTargets, outcome);
        }
        java.util.LinkedHashMap<String, String> expectedDisplayByKey = new java.util.LinkedHashMap<>();
        for (String target : expectedTargets) {
            String display = ToolCallSupport.normalizePath(target);
            String key = normalizeExpectedTargetKey(display);
            if (!key.isBlank()) {
                expectedDisplayByKey.putIfAbsent(key, display);
            }
        }
        return expectedDisplayByKey.entrySet().stream()
                .filter(entry -> !satisfiedTargets.contains(entry.getKey()))
                .map(java.util.Map.Entry::getValue)
                .sorted()
                .toList();
    }

    private static void addSatisfiedExpectedTargetKeys(
            Set<String> satisfiedTargets,
            dev.talos.runtime.ToolCallLoop.ToolOutcome outcome
    ) {
        if (satisfiedTargets == null || outcome == null) return;
        WorkspaceOperationPlan plan = outcome.workspaceOperationPlan();
        if (plan != null && !plan.pathEffects().isEmpty()) {
            for (WorkspaceOperationPlan.PathEffect effect : plan.pathEffects()) {
                addExpectedTargetPathKeys(satisfiedTargets, effect.path());
            }
            return;
        }
        addExpectedTargetPathKeys(satisfiedTargets, outcome.pathHint());
    }

    private static void addExpectedTargetPathKeys(Set<String> satisfiedTargets, String path) {
        String normalized = normalizeExpectedTargetKey(path);
        if (normalized.isBlank()) return;
        satisfiedTargets.add(normalized);
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < normalized.length()) {
            satisfiedTargets.add(normalized.substring(slash + 1));
        }
    }

    private static String normalizeExpectedTargetKey(String path) {
        return ToolCallSupport.normalizePath(path).toLowerCase(java.util.Locale.ROOT);
    }

}
