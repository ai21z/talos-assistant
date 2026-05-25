package dev.talos.runtime.toolcall;

import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.expectation.AppendLineExpectation;
import dev.talos.runtime.expectation.ReplacementExpectation;
import dev.talos.runtime.expectation.TaskExpectationResolver;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.failure.FailurePolicy;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallParser;
import dev.talos.runtime.capability.StaticWebCapabilityProfile;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@SuppressWarnings("resource") // LoopState.ctx owns the shared LlmClient for the active REPL session.
public final class ToolCallRepromptStage {
    private static final Logger LOG = LoggerFactory.getLogger(ToolCallRepromptStage.class);
    private static final int REPAIR_READ_ONLY_TOOL_BUDGET = 6;
    private static final int COMPACT_READBACK_REPAIR_MAX_CHARS = 12_000;

    private record OldStringMissRepair(String path, String reason, String readback) {}
    private record AppendLineRepair(String path, String expectedLine, String reason, String readback) {}
    private record ExpectedTargetRepair(
            List<String> expectedTargets,
            String failedTarget,
            String reason,
            String readbackFrame,
            String replacementOldText,
            String replacementNewText
    ) {}
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
            Optional<ExpectedTargetRepair> expectedTargetRepair = nextExpectedTargetScopeRepair(state);
            if (expectedTargetRepair.isPresent()) {
                ExpectedTargetRepair repair = expectedTargetRepair.get();
                state.failureDecision = FailureDecision.continueLoop();
                state.setPendingActionObligation(
                        PendingActionObligation.expectedTargetScopeTargets(repair.expectedTargets()));
                state.expectedTargetScopeRepairPromptedKeys.add(expectedTargetRepairKey(repair));
                ChatMessage.NativeToolCall exactReplacementRepair =
                        exactExpectedTargetReplacementRepairCall(repair);
                if (exactReplacementRepair != null) {
                    LocalTurnTraceCapture.recordRepair(
                            "PLANNED",
                            "expected-target-scope exact replacement target="
                                    + repair.expectedTargets().getFirst()
                                    + " after wrong-target block=" + repair.failedTarget());
                    state.currentText = "";
                    state.currentNativeCalls = List.of(exactReplacementRepair);
                    return true;
                }
                List<ToolSpec> repairToolSpecs = oldStringMissRepairToolSpecs(state);
                List<ChatMessage> requestMessages = expectedTargetRepairMessages(
                        repair,
                        ToolCallSupport.latestUserRequestIn(state.messages));
                return chatReprompt(state, requestMessages, repairToolSpecs,
                        repromptControls(state, "expected-target-scope-compact-repair"),
                        "expected-target scope compact repair");
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

        Optional<AppendLineRepair> appendLineRepair = nextAppendLineCompactRepair(state);
        if (appendLineRepair.isPresent()) {
            AppendLineRepair repair = appendLineRepair.get();
            state.setPendingActionObligation(
                    PendingActionObligation.appendLineTargets(List.of(repair.path())));
            state.appendLineRepairPromptedPaths.add(normalizeExpectedTargetKey(repair.path()));
            List<ToolSpec> repairToolSpecs = oldStringMissRepairToolSpecs(state);
            List<ChatMessage> requestMessages = appendLineRepairMessages(repair, userTask);
            return chatReprompt(state, requestMessages, repairToolSpecs,
                    repromptControls(state, "append-line-compact-repair"),
                    "append-line compact repair");
        }

        Optional<OldStringMissRepair> oldStringMissRepair = nextOldStringMissCompactRepair(state);
        if (oldStringMissRepair.isPresent()) {
            OldStringMissRepair repair = oldStringMissRepair.get();
            state.setPendingActionObligation(
                    PendingActionObligation.oldStringMissTargets(List.of(repair.path())));
            state.oldStringMissRepairPromptedPaths.add(normalizeExpectedTargetKey(repair.path()));
            List<ToolSpec> repairToolSpecs = oldStringMissRepairToolSpecs(state);
            List<ChatMessage> requestMessages = oldStringMissRepairMessages(repair, userTask);
            return chatReprompt(state, requestMessages, repairToolSpecs,
                    repromptControls(state, "old-string-miss-compact-repair"),
                    "old-string miss compact repair");
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

    private static boolean isSensitiveReadbackPath(String path) {
        if (path == null || path.isBlank()) return true;
        String normalized = ToolCallSupport.normalizePath(path).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return true;
        for (String segment : normalized.split("/")) {
            if (segment.equals(".env") || segment.startsWith(".env.")) return true;
            if (segment.equals(".git") || segment.equals(".ssh") || segment.equals(".gnupg")) return true;
        }
        return normalized.contains("id_rsa")
                || normalized.contains("credentials")
                || normalized.contains("secret");
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

    private static Optional<ExpectedTargetRepair> nextExpectedTargetScopeRepair(LoopState state) {
        if (state == null || state.toolOutcomes == null || state.toolOutcomes.isEmpty()) {
            return Optional.empty();
        }
        String failureReason = state.failureDecision == null ? "" : state.failureDecision.reason();
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        List<String> remainingExpectedTargets = expectedMutationTargetsForScopeRepair(state);
        if (remainingExpectedTargets.isEmpty() && looksLikeExpectedTargetScopeFailure(failureReason)) {
            remainingExpectedTargets = expectedTargetsFromScopeFailureReason(failureReason);
        }
        if (remainingExpectedTargets.isEmpty()) return Optional.empty();
        for (int i = state.toolOutcomes.size() - 1; i >= 0; i--) {
            ToolCallLoop.ToolOutcome outcome = state.toolOutcomes.get(i);
            if (outcome == null || !outcome.expectedTargetScopeFailure()) continue;
            String failedTarget = ToolCallSupport.normalizePath(outcome.pathHint());
            if (failedTarget.isBlank()) failedTarget = "(unknown)";
            ExpectedTargetRepair repair = expectedTargetRepair(
                    remainingExpectedTargets,
                    failedTarget,
                    outcome.errorMessage(),
                    contract,
                    state);
            if (repair == null) continue;
            if (state.expectedTargetScopeRepairPromptedKeys.contains(expectedTargetRepairKey(repair))) {
                continue;
            }
            return Optional.of(repair);
        }
        if (looksLikeExpectedTargetScopeFailure(failureReason)) {
            String failedTarget = firstBacktickValue(failureReason);
            if (failedTarget.isBlank()) failedTarget = "(unknown)";
            ExpectedTargetRepair repair = expectedTargetRepair(
                    remainingExpectedTargets,
                    failedTarget,
                    failureReason,
                    contract,
                    state);
            if (repair != null
                    && !state.expectedTargetScopeRepairPromptedKeys.contains(expectedTargetRepairKey(repair))) {
                return Optional.of(repair);
            }
        }
        return Optional.empty();
    }

    private static List<String> expectedTargetsFromScopeFailureReason(String reason) {
        if (reason == null || reason.isBlank()) return List.of();
        String marker = "current expected target set:";
        String lower = reason.toLowerCase(Locale.ROOT);
        int start = lower.indexOf(marker);
        if (start < 0) return List.of();
        String tail = reason.substring(start + marker.length()).strip();
        int end = tail.indexOf(". Similar filenames");
        if (end >= 0) {
            tail = tail.substring(0, end);
        } else {
            int period = tail.indexOf('.');
            if (period >= 0) tail = tail.substring(0, period);
        }
        if (tail.isBlank()) return List.of();
        return java.util.Arrays.stream(tail.split(","))
                .map(ToolCallSupport::normalizePath)
                .filter(path -> !path.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private static boolean looksLikeExpectedTargetScopeFailure(String reason) {
        return reason != null
                && reason.toLowerCase(Locale.ROOT)
                .contains("target outside expected targets before approval");
    }

    private static String firstBacktickValue(String value) {
        if (value == null || value.isBlank()) return "";
        int start = value.indexOf('`');
        if (start < 0) return "";
        int end = value.indexOf('`', start + 1);
        if (end <= start) return "";
        return ToolCallSupport.normalizePath(value.substring(start + 1, end));
    }

    private static List<String> expectedMutationTargetsForScopeRepair(LoopState state) {
        if (state == null || state.messages == null) return List.of();
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        if (contract == null || !contract.mutationAllowed()) return List.of();
        Set<String> expectedTargets = contract.expectedTargets().isEmpty()
                ? TaskContractResolver.extractExpectedTargets(ToolCallSupport.latestUserRequestIn(state.messages))
                : contract.expectedTargets();
        if (expectedTargets == null || expectedTargets.isEmpty()) return List.of();
        Set<String> successfullyMutated = new java.util.HashSet<>();
        for (ToolCallLoop.ToolOutcome outcome : state.toolOutcomes) {
            if (outcome == null || !outcome.success() || !outcome.mutating()) continue;
            addSatisfiedExpectedTargetKeys(successfullyMutated, outcome);
        }
        return expectedTargets.stream()
                .map(ToolCallSupport::normalizePath)
                .filter(path -> !path.isBlank())
                .distinct()
                .filter(path -> !successfullyMutated.contains(normalizeExpectedTargetKey(path)))
                .sorted()
                .toList();
    }

    private static ExpectedTargetRepair expectedTargetRepair(
            List<String> expectedTargets,
            String failedTarget,
            String reason,
            TaskContract contract,
            LoopState state
    ) {
        if (expectedTargets == null || expectedTargets.isEmpty() || state == null) return null;
        StringBuilder readbacks = new StringBuilder();
        for (String target : expectedTargets) {
            String path = ToolCallSupport.normalizePath(target);
            if (path.isBlank() || isSensitiveReadbackPath(path)) continue;
            if (!successfulReadbackForPath(state, path)) continue;
            String readback = latestSuccessfulReadbackForPath(state, path);
            if (readback == null || readback.isBlank()) continue;
            readbacks.append("Current readback for ")
                    .append(path)
                    .append(":\n")
                    .append(truncateForCompactRepair(readback))
                    .append("\n---\n");
        }
        appendSuccessfulStaticWebMutationReadbacks(state, readbacks);
        if (readbacks.isEmpty()) {
            if (expectedTargets.stream().noneMatch(StaticWebCapabilityProfile::isSmallWebFile)) {
                return null;
            }
            if (state.mutatingToolSuccesses <= 0 && !looksDirectoryLikeFailedTarget(failedTarget)) {
                return null;
            }
            readbacks.append("No current expected-target readback exists yet. ")
                    .append("Create the missing expected target file(s) from the current user request; ")
                    .append("do not create or mutate the failed attempted target unless it is explicitly listed as expected.");
        }
        List<String> normalizedTargets = expectedTargets.stream()
                        .map(ToolCallSupport::normalizePath)
                        .filter(path -> !path.isBlank())
                        .distinct()
                        .sorted()
                        .toList();
        ReplacementExpectation replacement = replacementExpectationForTargets(contract, normalizedTargets);
        return new ExpectedTargetRepair(
                normalizedTargets,
                failedTarget,
                reason,
                readbacks.toString().strip(),
                replacement == null ? "" : replacement.oldText(),
                replacement == null ? "" : replacement.newText());
    }

    private static void appendSuccessfulStaticWebMutationReadbacks(
            LoopState state,
            StringBuilder readbacks
    ) {
        if (state == null || state.workspace == null || state.toolOutcomes == null || readbacks == null) return;
        Path root = state.workspace.toAbsolutePath().normalize();
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (ToolCallLoop.ToolOutcome outcome : state.toolOutcomes) {
            if (!StaticWebContinuationPlanner.mutatedSmallWebFile(outcome)) continue;
            addSmallWebReadbackPath(paths, outcome.pathHint());
            WorkspaceOperationPlan plan = outcome.workspaceOperationPlan();
            if (plan == null) continue;
            for (WorkspaceOperationPlan.PathEffect effect : plan.pathEffects()) {
                if (effect != null) {
                    addSmallWebReadbackPath(paths, effect.path());
                }
            }
        }
        for (String path : paths) {
            if (isSensitiveReadbackPath(path)) continue;
            try {
                Path resolved = root.resolve(path).toAbsolutePath().normalize();
                if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) continue;
                String content = Files.readString(resolved);
                if (content.isBlank()) continue;
                readbacks.append("Current generated static web file ")
                        .append(path)
                        .append(":\n")
                        .append(truncateForCompactRepair(content))
                        .append("\n---\n");
            } catch (Exception ignored) {
                // The compact repair can still proceed from the expected target frame.
            }
        }
    }

    private static void addSmallWebReadbackPath(Set<String> paths, String path) {
        if (paths == null || path == null || path.isBlank()) return;
        String normalized = ToolCallSupport.normalizePath(path);
        if (normalized.isBlank() || !StaticWebCapabilityProfile.isSmallWebFile(normalized)) return;
        paths.add(normalized);
    }

    private static ReplacementExpectation replacementExpectationForTargets(
            TaskContract contract,
            List<String> targets
    ) {
        if (contract == null || targets == null || targets.size() != 1) return null;
        String target = targets.getFirst();
        for (var expectation : TaskExpectationResolver.resolve(contract)) {
            if (expectation instanceof ReplacementExpectation replacement
                    && ToolCallSupport.normalizePath(replacement.targetPath()).equals(target)) {
                return replacement;
            }
        }
        return null;
    }

    private static boolean looksDirectoryLikeFailedTarget(String failedTarget) {
        if (failedTarget == null || failedTarget.isBlank()) return false;
        String normalized = ToolCallSupport.normalizePath(failedTarget).toLowerCase(Locale.ROOT);
        if (normalized.endsWith("/")) return true;
        int slash = normalized.lastIndexOf('/');
        String last = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return !last.contains(".");
    }

    private static String expectedTargetRepairKey(ExpectedTargetRepair repair) {
        if (repair == null) return "";
        return ToolCallSupport.normalizePath(repair.failedTarget())
                + "->"
                + String.join(",", repair.expectedTargets());
    }

    private static ChatMessage.NativeToolCall exactExpectedTargetReplacementRepairCall(
            ExpectedTargetRepair repair
    ) {
        if (repair == null || repair.expectedTargets().size() != 1) return null;
        if (repair.replacementOldText().isBlank() || repair.replacementNewText().isBlank()) {
            return null;
        }
        return new ChatMessage.NativeToolCall(
                "runtime_expected_target_repair",
                "talos.edit_file",
                Map.of(
                        "path", repair.expectedTargets().getFirst(),
                        "old_string", repair.replacementOldText(),
                        "new_string", repair.replacementNewText()));
    }

    private static Optional<AppendLineRepair> nextAppendLineCompactRepair(LoopState state) {
        if (state == null || state.toolOutcomes == null || state.toolOutcomes.isEmpty()) {
            return Optional.empty();
        }
        List<String> remainingExpectedTargets = remainingExpectedMutationTargets(state);
        if (remainingExpectedTargets.isEmpty()) return Optional.empty();
        Set<String> remaining = remainingExpectedTargets.stream()
                .map(ToolCallRepromptStage::normalizeExpectedTargetKey)
                .collect(java.util.stream.Collectors.toSet());
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        for (int i = state.toolOutcomes.size() - 1; i >= 0; i--) {
            ToolCallLoop.ToolOutcome outcome = state.toolOutcomes.get(i);
            if (outcome == null || !outcome.appendLinePreservationFailure()) continue;
            String pathKey = normalizeExpectedTargetKey(outcome.pathHint());
            if (pathKey.isBlank() || !remaining.contains(pathKey)) continue;
            if (state.appendLineRepairPromptedPaths.contains(pathKey)) continue;
            String path = displayExpectedTargetForKey(remainingExpectedTargets, pathKey);
            if (path.isBlank()) {
                path = ToolCallSupport.normalizePath(outcome.pathHint());
            }
            if (isSensitiveReadbackPath(path) || !successfulReadbackForPath(state, path)) continue;
            AppendLineExpectation expectation = appendLineExpectationForPath(contract, path);
            if (expectation == null || expectation.expectedLine().isBlank()) continue;
            String readback = latestSuccessfulReadbackForPath(state, path);
            if (readback == null || readback.isBlank()) continue;
            return Optional.of(new AppendLineRepair(
                    path,
                    expectation.expectedLine(),
                    outcome.errorMessage(),
                    truncateForCompactRepair(readback)));
        }
        return Optional.empty();
    }

    private static AppendLineExpectation appendLineExpectationForPath(TaskContract contract, String path) {
        if (contract == null || path == null || path.isBlank()) return null;
        String target = ToolCallSupport.normalizePath(path).toLowerCase(Locale.ROOT);
        for (var expectation : TaskExpectationResolver.resolve(contract)) {
            if (expectation instanceof AppendLineExpectation appendLine
                    && ToolCallSupport.normalizePath(appendLine.targetPath())
                    .toLowerCase(Locale.ROOT)
                    .equals(target)) {
                return appendLine;
            }
        }
        return null;
    }

    private static Optional<OldStringMissRepair> nextOldStringMissCompactRepair(LoopState state) {
        if (state == null || state.toolOutcomes == null || state.toolOutcomes.isEmpty()) {
            return Optional.empty();
        }
        List<String> remainingExpectedTargets = remainingExpectedMutationTargets(state);
        if (remainingExpectedTargets.isEmpty()) return Optional.empty();
        Set<String> remaining = remainingExpectedTargets.stream()
                .map(ToolCallRepromptStage::normalizeExpectedTargetKey)
                .collect(java.util.stream.Collectors.toSet());
        for (int i = state.toolOutcomes.size() - 1; i >= 0; i--) {
            ToolCallLoop.ToolOutcome outcome = state.toolOutcomes.get(i);
            if (outcome == null || !outcome.oldStringNotFoundEditFailure()) continue;
            String pathKey = normalizeExpectedTargetKey(outcome.pathHint());
            if (pathKey.isBlank() || !remaining.contains(pathKey)) continue;
            if (state.oldStringMissRepairPromptedPaths.contains(pathKey)) continue;
            String path = displayExpectedTargetForKey(remainingExpectedTargets, pathKey);
            if (path.isBlank()) {
                path = ToolCallSupport.normalizePath(outcome.pathHint());
            }
            if (!successfulReadbackForPath(state, path)) continue;
            String readback = latestSuccessfulReadbackForPath(state, path);
            if (readback == null || readback.isBlank()) continue;
            return Optional.of(new OldStringMissRepair(
                    path,
                    outcome.errorMessage(),
                    truncateForCompactRepair(readback)));
        }
        return Optional.empty();
    }

    private static boolean successfulReadbackForPath(LoopState state, String normalizedPath) {
        if (state == null || normalizedPath == null || normalizedPath.isBlank()) return false;
        String targetKey = normalizeExpectedTargetKey(normalizedPath);
        if (targetKey.isBlank()) return false;
        for (ToolCallLoop.ToolOutcome outcome : state.toolOutcomes) {
            if (outcome == null || !outcome.success()) continue;
            if (!"talos.read_file".equals(canonicalToolName(outcome.toolName()))) continue;
            if (targetKey.equals(normalizeExpectedTargetKey(outcome.pathHint()))) {
                return true;
            }
        }
        return false;
    }

    private static String latestSuccessfulReadbackForPath(LoopState state, String normalizedPath) {
        if (state == null || normalizedPath == null || normalizedPath.isBlank()) {
            return null;
        }
        String target = ToolCallSupport.canonicalizeReadPath(normalizedPath)
                .toLowerCase(Locale.ROOT);
        String fullBody = latestSuccessfulReadbackForPath(state.successfulReadCallBodies, target);
        if (fullBody != null) return fullBody;
        return latestSuccessfulReadbackForPath(state.successfulReadCalls, target);
    }

    private static String latestSuccessfulReadbackForPath(Map<String, String> readbacksBySignature, String target) {
        if (readbacksBySignature == null || readbacksBySignature.isEmpty()
                || target == null || target.isBlank()) {
            return null;
        }
        for (var entry : readbacksBySignature.entrySet()) {
            String signature = entry.getKey() == null
                    ? ""
                    : entry.getKey().replace('\\', '/').toLowerCase(Locale.ROOT);
            if (signature.startsWith("talos.read_file:")
                    && signature.contains("path=" + target + ";")) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String displayExpectedTargetForKey(List<String> targets, String key) {
        if (targets == null || targets.isEmpty() || key == null || key.isBlank()) return "";
        for (String target : targets) {
            String display = ToolCallSupport.normalizePath(target);
            if (!display.isBlank() && key.equals(normalizeExpectedTargetKey(display))) {
                return display;
            }
        }
        return "";
    }

    private static String truncateForCompactRepair(String readback) {
        if (readback == null || readback.length() <= COMPACT_READBACK_REPAIR_MAX_CHARS) {
            return readback;
        }
        return readback.substring(0, COMPACT_READBACK_REPAIR_MAX_CHARS)
                + "\n... [readback truncated for compact old-string repair]";
    }

    private static List<ToolSpec> oldStringMissRepairToolSpecs(LoopState state) {
        List<ToolSpec> base = currentNativeToolSpecs(state);
        List<ToolSpec> narrowed = filterTools(base, List.of("talos.edit_file", "talos.write_file"));
        return narrowed.isEmpty() ? base : narrowed;
    }

    private static List<ChatMessage> oldStringMissRepairMessages(
            OldStringMissRepair repair,
            String userTask
    ) {
        String currentTask = userTask == null || userTask.isBlank()
                ? "Apply the requested file change."
                : userTask.strip();
        return List.of(
                ChatMessage.system("""
                        You are Talos, a local-first workspace assistant.
                        This is a compact target-only repair after talos.edit_file failed because old_string was not found.
                        Use the provided current file readback as the only file-content source.
                        Use talos.write_file with complete target content for small Markdown/prose files unless a precise talos.edit_file replacement is obvious from the readback.
                        Do not answer in prose instead of calling a write/edit tool.
                        """),
                ChatMessage.system(
                        "[OldStringMissRepair] Target: " + repair.path() + "\n"
                                + "Failed reason: " + safeRepairReason(repair.reason()) + "\n"
                                + "Only mutate this target. Ignore stale prior history outside this compact repair frame."),
                ChatMessage.user(
                        "Current user request:\n"
                                + currentTask
                                + "\n\nCurrent readback for " + repair.path() + ":\n"
                                + repair.readback()
                                + "\n\nApply the current request to " + repair.path()
                                + " using talos.write_file or talos.edit_file now."));
    }

    private static List<ChatMessage> appendLineRepairMessages(
            AppendLineRepair repair,
            String userTask
    ) {
        String currentTask = userTask == null || userTask.isBlank()
                ? "Append the requested line to the target file."
                : userTask.strip();
        return List.of(
                ChatMessage.system("""
                        You are Talos, a local-first workspace assistant.
                        This is a compact target-only repair after talos.write_file was blocked before approval because it did not preserve the same-turn readback for an append-line task.
                        Use the provided current file readback as the only file-content source.
                        Prefer talos.write_file with complete target content equal to the readback plus exactly the required appended line as the final logical line.
                        Do not answer in prose instead of calling a write/edit tool.
                        """),
                ChatMessage.system(
                        "[AppendLineRepair] Target: " + repair.path() + "\n"
                                + "Required appended line: " + repair.expectedLine() + "\n"
                                + "Failed reason: " + safeAppendLineRepairReason(repair.reason()) + "\n"
                                + "Only mutate this target. Ignore stale prior history outside this compact repair frame."),
                ChatMessage.user(
                        "Current user request:\n"
                                + currentTask
                                + "\n\nCurrent readback for " + repair.path() + ":\n"
                                + repair.readback()
                                + "\n\nAppend exactly this line as the final logical line:\n"
                                + repair.expectedLine()
                                + "\n\nCall talos.write_file or talos.edit_file now."));
    }

    private static List<ChatMessage> expectedTargetRepairMessages(
            ExpectedTargetRepair repair,
            String userTask
    ) {
        String currentTask = userTask == null || userTask.isBlank()
                ? "Apply the requested file change to the expected target."
                : userTask.strip();
        return List.of(
                ChatMessage.system("""
                        You are Talos, a local-first workspace assistant.
                        This is a compact target-only repair after a mutation was blocked before approval because it targeted a file outside the expected target set.
                        Use the provided expected-target frame as the only file-content source.
                        If the frame says no current readback exists, create the missing expected file(s) from the current user request.
                        Only mutate the expected target path(s). Do not mutate the failed attempted target unless it is also explicitly listed as expected.
                        Do not put required root files inside css/, js/, assets/, site/, or other subdirectories unless the expected target path explicitly includes that directory.
                        Do not answer in prose instead of calling a write/edit tool.
                        """),
                ChatMessage.system(
                        "[ExpectedTargetRepair]\n"
                                + "Expected target(s): " + String.join(", ", repair.expectedTargets()) + "\n"
                                + "Failed attempted target: " + repair.failedTarget() + "\n"
                                + expectedTargetRepairReplacementFrame(repair)
                                + "Failed reason: " + safeExpectedTargetRepairReason(repair.reason()) + "\n"
                                + "Only mutate the expected target path(s). Ignore stale prior history outside this compact repair frame."),
                ChatMessage.user(
                        "Current user request:\n"
                                + currentTask
                                + "\n\n"
                                + repair.readbackFrame()
                                + "\n\nCall talos.write_file or talos.edit_file for the expected target now."));
    }

    private static String expectedTargetRepairReplacementFrame(ExpectedTargetRepair repair) {
        if (repair == null || repair.replacementOldText().isBlank() || repair.replacementNewText().isBlank()) {
            return "";
        }
        return "Exact replacement: old_string=`" + repair.replacementOldText()
                + "` new_string=`" + repair.replacementNewText() + "`\n";
    }

    private static String safeRepairReason(String reason) {
        if (reason == null || reason.isBlank()) return "old_string not found";
        return reason.strip();
    }

    private static String safeAppendLineRepairReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "append-line write_file did not preserve same-turn readback";
        }
        return reason.strip();
    }

    private static String safeExpectedTargetRepairReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "mutation targeted a file outside the expected target set";
        }
        return reason.strip();
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
