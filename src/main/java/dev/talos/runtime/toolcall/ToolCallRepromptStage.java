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
import dev.talos.runtime.policy.SafeLogFormatter;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.verification.StaticTaskVerifier;
import dev.talos.runtime.verification.TaskVerificationResult;
import dev.talos.runtime.verification.TaskVerificationStatus;
import dev.talos.runtime.verification.WebDiagnosticIntent;
import dev.talos.runtime.workspace.WorkspaceOperationIntent;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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
    private static final int COMPACT_MUTATION_READBACK_MAX_CHARS = 4_000;

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
    private record StaticWebContinuation(TaskVerificationResult verification, List<String> missingTargets) {}

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

        String webDiagnostics = readOnlyWebDiagnosticStopAnswer(state, outcome);
        if (webDiagnostics != null) {
            state.currentText = webDiagnostics;
            state.currentNativeCalls = List.of();
            LOG.debug("Stopping read-only web diagnostic loop with deterministic static diagnostics.");
            return false;
        }

        String unsupportedDocument = unsupportedDocumentStopAnswer(state, outcome);
        if (unsupportedDocument != null) {
            state.currentText = unsupportedDocument;
            state.currentNativeCalls = List.of();
            LOG.debug("Stopping tool-call loop after unsupported binary document read.");
            return false;
        }

        String directoryListing = directoryListingStopAnswer(state, outcome);
        if (directoryListing != null) {
            state.currentText = directoryListing;
            state.currentNativeCalls = List.of();
            LOG.debug("Stopping directory-listing loop after successful list_dir evidence.");
            return false;
        }

        String readTargetAnswer = readTargetStopAnswer(state, outcome);
        if (readTargetAnswer != null) {
            state.currentText = readTargetAnswer;
            state.currentNativeCalls = List.of();
            LOG.debug("Stopping read-target loop after required read_file evidence.");
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
            if (staticWebVerificationAlreadyPasses(state)) {
                state.currentText = String.join("\n", outcome.mutationSummaries());
                state.currentNativeCalls = List.of();
                state.clearPendingActionObligation();
                LOG.debug("Stopping static web repair after verifier-passed mutation before expected-target progress.");
                return false;
            }
            List<String> remainingRepairTargets = remainingFullRewriteRepairTargets(state);
            List<String> remainingExpectedTargets = remainingExpectedMutationTargets(state);
            if (remainingRepairTargets.isEmpty() && remainingExpectedTargets.isEmpty()) {
                if (shouldContinueStaticWebCreationAfterDirectoryOnlyMutation(state)) {
                    LOG.debug("Continuing static web creation after directory-only mutation.");
                    return continueStaticWebCreationAfterDirectoryOnlyMutation(state);
                }
                Optional<StaticWebContinuation> staticWebContinuation = staticWebVerificationContinuation(state);
                if (staticWebContinuation.isPresent()) {
                    LOG.debug("Continuing static web creation after verification found missing target(s): {}",
                            staticWebContinuation.get().missingTargets());
                    return continueStaticWebCreationAfterVerificationFailure(state, staticWebContinuation.get());
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
            LOG.warn("Model not found during tool-call loop iteration {}: {}", state.iterations, mnf.model());
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
        if (tryCompactReadOnlyEvidenceContinuation(state, retryName)) {
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
        Optional<CompactMutationContinuation> continuation =
                compactMutationContinuationForContextBudget(state, retryName);
        if (continuation.isEmpty()) return CompactMutationContinuationOutcome.NOT_APPLICABLE;

        CompactMutationContinuation compact = continuation.get();
        try {
            LlmClient.StreamResult result = state.ctx.llm().chatFull(
                    compact.messages(),
                    compact.toolSpecs(),
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

    private record CompactMutationContinuation(
            List<ChatMessage> messages,
            List<ToolSpec> toolSpecs,
            ChatRequestControls controls
    ) {}

    private static Optional<CompactMutationContinuation> compactMutationContinuationForContextBudget(
            LoopState state,
            String retryName
    ) {
        if (state == null || state.ctx == null || state.ctx.llm() == null) return Optional.empty();
        if (state.hasPendingActionObligation()) return Optional.empty();
        if (state.mutationSinceStart || state.mutatingToolSuccesses > 0) return Optional.empty();
        if (!readOnlyProgressOnly(state)) return Optional.empty();

        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        if (contract == null || !contract.mutationAllowed() || !contract.mutationRequested()) {
            return Optional.empty();
        }
        if (WorkspaceOperationIntent.detect(contract).isPresent()) {
            return Optional.empty();
        }
        if (compactMutationTargets(state, contract).isEmpty()) {
            return Optional.empty();
        }

        List<ToolSpec> tools = compactMutationContinuationToolSpecs(state);
        if (tools.isEmpty()) return Optional.empty();

        List<ChatMessage> messages = compactMutationContinuationMessages(state, contract, retryName);
        ChatRequestControls controls = compactMutationContinuationControls(state, tools);
        return Optional.of(new CompactMutationContinuation(messages, tools, controls));
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

    private static List<ToolSpec> compactMutationContinuationToolSpecs(LoopState state) {
        List<String> allowed = hasStaticRepairContext(state)
                ? List.of("talos.write_file")
                : List.of("talos.write_file", "talos.edit_file");
        List<ToolSpec> narrowed = filterTools(currentNativeToolSpecs(state), allowed);
        if (narrowed.isEmpty()) return List.of();
        return narrowed.stream()
                .map(ToolCallRepromptStage::compactMutationToolSpec)
                .toList();
    }

    private static ToolSpec compactMutationToolSpec(ToolSpec spec) {
        if (spec == null) return null;
        return switch (spec.name()) {
            case "talos.write_file" -> new ToolSpec(
                    "talos.write_file",
                    "Write complete file content.",
                    "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"]}");
            case "talos.edit_file" -> new ToolSpec(
                    "talos.edit_file",
                    "Replace exact text in a file.",
                    "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"old_string\":{\"type\":\"string\"},\"new_string\":{\"type\":\"string\"}},\"required\":[\"path\",\"old_string\",\"new_string\"]}");
            default -> spec;
        };
    }

    private static ChatRequestControls compactMutationContinuationControls(
            LoopState state,
            List<ToolSpec> tools
    ) {
        boolean required = state != null
                && state.ctx != null
                && state.ctx.llm() != null
                && state.ctx.llm().supportsRequiredToolChoice()
                && hasMutatingTool(tools);
        return new ChatRequestControls(
                required ? ToolChoiceMode.REQUIRED : ToolChoiceMode.AUTO,
                "",
                ResponseFormatMode.TEXT,
                "",
                List.of("compact-mutation-continuation"));
    }

    private static List<ChatMessage> compactMutationContinuationMessages(
            LoopState state,
            TaskContract contract,
            String retryName
    ) {
        String userTask = ToolCallSupport.latestUserRequestIn(state.messages);
        if (userTask == null || userTask.isBlank()) {
            userTask = contract == null ? "" : contract.originalUserRequest();
        }
        StringBuilder frame = new StringBuilder();
        frame.append("[CompactMutationContinuation]\n")
                .append("Normal tool-loop continuation exceeded the local context budget during ")
                .append(retryName == null || retryName.isBlank() ? "tool-call loop continuation" : retryName)
                .append(".\n")
                .append("Continue only the current mutation request. Older conversation history is intentionally omitted.\n")
                .append("Prose/manual snippets do not change files; call the provided write/edit tools now.\n");
        appendCompactMutationContract(frame, state, contract);
        appendCompactMutationReadbacks(frame, state, contract);

        String currentRequest = userTask == null ? "" : userTask.strip();
        return List.of(
                ChatMessage.system("""
                        You are Talos, a local-first workspace assistant.
                        This is a compact mutation continuation after the full-history continuation exceeded the local context budget.
                        Use only the current request, expected targets, and readback evidence in this compact frame.
                        Do not answer in prose instead of calling a file mutation tool.
                        Do not claim completion until tool-backed changes have executed and runtime verification has run.
                        """),
                ChatMessage.system(frame.toString()),
                ChatMessage.user("Current mutation request:\n" + currentRequest
                        + "\n\nCall talos.write_file or talos.edit_file now."));
    }

    private static void appendCompactMutationContract(StringBuilder frame, LoopState state, TaskContract contract) {
        if (frame == null || contract == null) return;
        frame.append("\n[TaskContract]\n")
                .append("type: ").append(contract.type().name()).append('\n')
                .append("mutationAllowed: ").append(contract.mutationAllowed()).append('\n')
                .append("verificationRequired: ").append(contract.verificationRequired()).append('\n');
        List<String> targets = compactMutationTargets(state, contract);
        if (!targets.isEmpty()) {
            frame.append("[ExpectedTargets]\n")
                    .append("requiredTargets: ").append(String.join(", ", targets)).append('\n')
                    .append("You must write or edit these exact target paths for this turn.\n")
                    .append("Similar filenames are not substitutes for required target paths.\n")
                    .append("script.js and scripts.js are different target paths; preserve the exact requested spelling.\n");
            String staticWebGuidance = StaticWebCapabilityProfile.repairCoherenceGuidance(targets);
            if (!staticWebGuidance.isBlank()) {
                frame.append('\n').append(staticWebGuidance).append('\n');
            }
        }
    }

    private static void appendCompactMutationReadbacks(
            StringBuilder frame,
            LoopState state,
            TaskContract contract
    ) {
        if (frame == null || state == null) return;
        List<String> targets = compactMutationReadbackTargets(state, contract);
        if (targets.isEmpty()) return;
        boolean wroteHeader = false;
        for (String target : targets) {
            if (target == null || target.isBlank() || isSensitiveReadbackPath(target)) continue;
            String readback = latestSuccessfulReadbackForPath(state, target);
            if (readback == null || readback.isBlank()) continue;
            if (!wroteHeader) {
                frame.append("\n[CurrentReadbackEvidence]\n");
                wroteHeader = true;
            }
            frame.append("Path: ").append(target).append('\n')
                    .append(truncateForCompactMutation(readback))
                    .append("\n---\n");
        }
    }

    private static List<String> compactMutationReadbackTargets(LoopState state, TaskContract contract) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        List<String> expected = compactMutationTargets(state, contract);
        out.addAll(expected);
        for (ToolCallLoop.ToolOutcome outcome : state.toolOutcomes) {
            if (outcome == null || !outcome.success()) continue;
            if (!"talos.read_file".equals(canonicalToolName(outcome.toolName()))) continue;
            String path = ToolCallSupport.normalizePath(outcome.pathHint());
            if (path.isBlank() || isSensitiveReadbackPath(path)) continue;
            if (expected.contains(path) || isSimilarSiblingTarget(path, expected)) {
                out.add(path);
            }
        }
        return new ArrayList<>(out);
    }

    private static List<String> compactMutationTargets(LoopState state, TaskContract contract) {
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        Set<String> repairTargets = state == null
                ? Set.of()
                : RepairPolicy.fullRewriteTargetsFromRepairContext(state.messages);
        if (repairTargets != null && !repairTargets.isEmpty()) {
            repairTargets.stream()
                    .map(ToolCallSupport::normalizePath)
                    .filter(path -> !path.isBlank())
                    .sorted(Comparator.naturalOrder())
                    .forEach(targets::add);
            return new ArrayList<>(targets);
        }
        if (contract != null && contract.expectedTargets() != null) {
            contract.expectedTargets().stream()
                    .map(ToolCallSupport::normalizePath)
                    .filter(path -> !path.isBlank())
                    .sorted(Comparator.naturalOrder())
                    .forEach(targets::add);
        }
        return new ArrayList<>(targets);
    }

    private static boolean isSimilarSiblingTarget(String readPath, List<String> expectedTargets) {
        if (readPath == null || readPath.isBlank() || expectedTargets == null || expectedTargets.isEmpty()) {
            return false;
        }
        String normalizedRead = ToolCallSupport.normalizePath(readPath).toLowerCase(Locale.ROOT);
        for (String expected : expectedTargets) {
            String normalizedExpected = ToolCallSupport.normalizePath(expected).toLowerCase(Locale.ROOT);
            if (sameParent(normalizedRead, normalizedExpected)
                    && sameExtension(normalizedRead, normalizedExpected)
                    && singularPluralStemMatch(fileStem(normalizedRead), fileStem(normalizedExpected))) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameParent(String left, String right) {
        return parentPath(left).equals(parentPath(right));
    }

    private static String parentPath(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static boolean sameExtension(String left, String right) {
        return extension(left).equals(extension(right));
    }

    private static String extension(String path) {
        if (path == null) return "";
        String file = fileName(path);
        int dot = file.lastIndexOf('.');
        return dot < 0 ? "" : file.substring(dot);
    }

    private static String fileStem(String path) {
        String file = fileName(path);
        int dot = file.lastIndexOf('.');
        return dot < 0 ? file : file.substring(0, dot);
    }

    private static String fileName(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static boolean singularPluralStemMatch(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) return false;
        if (left.equals(right)) return false;
        return (left + "s").equals(right) || (right + "s").equals(left);
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

    private static String truncateForCompactMutation(String readback) {
        if (readback == null || readback.length() <= COMPACT_MUTATION_READBACK_MAX_CHARS) {
            return readback;
        }
        return readback.substring(0, COMPACT_MUTATION_READBACK_MAX_CHARS)
                + "\n... [readback truncated for compact mutation continuation]";
    }

    private static boolean tryCompactReadOnlyEvidenceContinuation(LoopState state, String retryName) {
        Optional<ReadOnlyEvidenceAnswer> evidence = readOnlyEvidenceAnswerForCompactFallback(state);
        if (evidence.isEmpty()) return false;
        ReadOnlyEvidenceAnswer answer = evidence.get();
        List<ChatMessage> messages = readOnlyEvidenceAnswerMessages(answer);
        try {
            LlmClient.StreamResult result = state.ctx.llm().chatFull(
                    messages,
                    List.of(),
                    ChatRequestControls.defaults());
            String text = result.text() == null ? "" : result.text().strip();
            if (result.hasToolCalls() || ToolCallParser.containsToolCalls(text)) {
                LocalTurnTraceCapture.warning(
                        "READ_ONLY_EVIDENCE_COMPACT_REJECTED",
                        "compact read-only evidence continuation emitted tool calls after " + retryName);
                return false;
            }
            String stripped = ToolCallParser.stripToolCalls(text).strip();
            if (stripped.isBlank()) {
                LocalTurnTraceCapture.warning(
                        "READ_ONLY_EVIDENCE_COMPACT_REJECTED",
                        "compact read-only evidence continuation returned empty text after " + retryName);
                return false;
            }
            state.currentText = stripped;
            state.currentNativeCalls = List.of();
            state.failureDecision = FailureDecision.continueLoop();
            state.clearPendingActionObligation();
            LocalTurnTraceCapture.warning(
                    "READ_ONLY_EVIDENCE_COMPACT_CONTINUATION",
                    "used compact evidence-only answer for " + answer.target() + " after " + retryName);
            return true;
        } catch (EngineException.ContextBudgetExceeded budget) {
            LocalTurnTraceCapture.warning(
                    "READ_ONLY_EVIDENCE_COMPACT_CONTEXT_BUDGET_EXCEEDED",
                    ResponseObligationVerifier.contextBudgetRetrySkippedDetail(budget));
            return false;
        } catch (EngineException ee) {
            LocalTurnTraceCapture.warning(
                    "READ_ONLY_EVIDENCE_COMPACT_FAILED",
                    ee.getMessage() == null ? ee.getClass().getSimpleName() : ee.getMessage());
            return false;
        } catch (Exception e) {
            LocalTurnTraceCapture.warning(
                    "READ_ONLY_EVIDENCE_COMPACT_FAILED",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return false;
        }
    }

    private record ReadOnlyEvidenceAnswer(String target, String userTask, String readback) {}

    private static Optional<ReadOnlyEvidenceAnswer> readOnlyEvidenceAnswerForCompactFallback(LoopState state) {
        if (state == null || state.ctx == null || state.ctx.llm() == null) return Optional.empty();
        if (state.hasPendingActionObligation()) return Optional.empty();
        if (state.mutationSinceStart || state.mutatingToolSuccesses > 0) return Optional.empty();
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        if (contract.type() != TaskType.READ_ONLY_QA || contract.expectedTargets().size() != 1) {
            return Optional.empty();
        }
        String userTask = ToolCallSupport.latestUserRequestIn(state.messages);
        if (!looksLikeReadOnlyReviewProposal(userTask)) return Optional.empty();
        String target = contract.expectedTargets().iterator().next();
        String normalizedTarget = ToolCallSupport.normalizePath(target);
        if (!successfulReadbackForPath(state, normalizedTarget)) return Optional.empty();
        String body = latestSuccessfulReadbackForPath(state, normalizedTarget);
        if (body == null || body.isBlank()) return Optional.empty();
        return Optional.of(new ReadOnlyEvidenceAnswer(normalizedTarget, userTask.strip(), body));
    }

    private static boolean looksLikeReadOnlyReviewProposal(String userTask) {
        if (userTask == null || userTask.isBlank()) return false;
        String lower = userTask.toLowerCase(Locale.ROOT);
        boolean reviewProposal = lower.contains("review")
                || lower.contains("propose")
                || lower.contains("proposal")
                || lower.contains("improvement")
                || lower.contains("suggest");
        boolean markdownTarget = lower.contains("readme") || lower.contains(".md");
        boolean explicitlyReadOnly = lower.contains("do not edit")
                || lower.contains("don't edit")
                || lower.contains("dont edit")
                || lower.contains("do not change")
                || lower.contains("without editing")
                || lower.contains("no file changes");
        return reviewProposal && markdownTarget && explicitlyReadOnly;
    }

    private static List<ChatMessage> readOnlyEvidenceAnswerMessages(ReadOnlyEvidenceAnswer answer) {
        return List.of(
                ChatMessage.system("""
                        You are Talos, a local-first workspace assistant.
                        [ReadOnlyEvidenceAnswer]
                        This is a compact evidence-only continuation after the full-history continuation exceeded the local context budget.
                        Answer the current user request using only the read_file evidence below.
                        Do not claim any file was changed, edited, updated, saved, completed, or ready to use.
                        For review/proposal output, separate observed evidence from suggestions.
                        Do not state commands, dependencies, package managers, frameworks, scripts, licenses, or file meanings as facts unless they appear in the read_file evidence.
                        """),
                ChatMessage.system("[ReadOnlyEvidenceAnswer] Target: " + answer.target()
                        + "\nOlder conversation history is intentionally omitted from this compact frame."),
                ChatMessage.user(
                        "Current user request:\n"
                                + answer.userTask()
                                + "\n\nCurrent read_file evidence for " + answer.target() + ":\n"
                                + answer.readback()
                                + "\n\nAnswer now without tools."));
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
            LOG.warn("Model not found during {}: {}", retryName, mnf.model());
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
        if (readbacks.isEmpty()) return null;
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

    private static boolean continueStaticWebCreationAfterDirectoryOnlyMutation(LoopState state) {
        List<ToolSpec> base = currentNativeToolSpecs(state);
        List<ToolSpec> narrowed = filterTools(base, List.of("talos.write_file"));
        if (narrowed.isEmpty()) {
            narrowed = filterTools(base, List.of("talos.write_file", "talos.edit_file"));
        }
        List<ToolSpec> tools = narrowed.isEmpty() ? base : narrowed;
        if (tools == null) tools = List.of();
        List<ChatMessage> messages = staticWebCreationContinuationMessages(state);
        ChatRequestControls controls = staticWebCreationContinuationControls(state, tools);
        return chatReprompt(
                state,
                messages,
                tools,
                controls,
                "static-web-directory-only-continuation");
    }

    private static boolean continueStaticWebCreationAfterVerificationFailure(
            LoopState state,
            StaticWebContinuation continuation
    ) {
        List<ToolSpec> base = currentNativeToolSpecs(state);
        List<ToolSpec> narrowed = filterTools(base, List.of("talos.write_file", "talos.edit_file"));
        List<ToolSpec> tools = narrowed.isEmpty() ? base : narrowed;
        if (tools == null) tools = List.of();
        if (continuation != null && !continuation.missingTargets().isEmpty()) {
            state.setPendingActionObligation(PendingActionObligation.expectedTargets(
                    continuation.missingTargets(),
                    staticWebVerificationFailureContext(continuation.verification())));
        }
        List<ChatMessage> messages = staticWebVerificationContinuationMessages(state, continuation);
        ChatRequestControls controls = staticWebCreationContinuationControls(state, tools);
        return chatReprompt(
                state,
                messages,
                tools,
                controls,
                "static-web-verification-continuation");
    }

    private static List<ChatMessage> staticWebCreationContinuationMessages(LoopState state) {
        String userTask = ToolCallSupport.latestUserRequestIn(state.messages);
        if (userTask == null || userTask.isBlank()) {
            TaskContract contract = TaskContractResolver.fromMessages(state.messages);
            userTask = contract == null ? "Create the requested static web artifact." : contract.originalUserRequest();
        }
        String directorySummary = successfulDirectoryMutationSummary(state);
        StringBuilder frame = new StringBuilder();
        frame.append("[StaticWebCreationContinuation]\n")
                .append("A directory mutation succeeded, but a website/app creation request is not complete ")
                .append("until actual static web files are written.\n")
                .append("Do not answer in prose instead of calling a file mutation tool.\n")
                .append("Write the HTML/CSS/JavaScript surface now. Prefer index.html, styles.css, and script.js ")
                .append("unless the user requested different names.\n")
                .append("Do not claim completion until tool-backed file writes have executed and static verification can run.");
        if (!directorySummary.isBlank()) {
            frame.append("\nSuccessful directory mutation: ").append(directorySummary);
        }
        return List.of(
                ChatMessage.system("""
                        You are Talos, a local-first workspace assistant.
                        This is a bounded static-web creation continuation after a directory-only mutation.
                        Directory creation alone does not satisfy a website/app creation request.
                        Use the visible write-file tool now to create the actual web files.
                        """),
                ChatMessage.system(frame.toString()),
                ChatMessage.user("Current user request:\n"
                        + (userTask == null ? "" : userTask.strip())
                        + "\n\nCall talos.write_file now for the actual static web files."));
    }

    private static List<ChatMessage> staticWebVerificationContinuationMessages(
            LoopState state,
            StaticWebContinuation continuation
    ) {
        String userTask = ToolCallSupport.latestUserRequestIn(state.messages);
        if (userTask == null || userTask.isBlank()) {
            TaskContract contract = TaskContractResolver.fromMessages(state.messages);
            userTask = contract == null ? "Create the requested static web artifact." : contract.originalUserRequest();
        }
        TaskVerificationResult verification = continuation == null ? null : continuation.verification();
        List<String> problems = verification == null ? List.of() : verification.problems();
        List<String> targets = continuation == null ? List.of() : continuation.missingTargets();
        StringBuilder frame = new StringBuilder();
        frame.append("[StaticWebVerificationContinuation]\n")
                .append("Static verification found the current web artifact incomplete after a successful mutation.\n")
                .append("Continue the same user request with file mutation tools. Do not answer in prose.\n");
        if (!targets.isEmpty()) {
            frame.append("Missing or unmutated target files: ")
                    .append(String.join(", ", targets))
                    .append('\n');
        }
        if (!problems.isEmpty()) {
            frame.append("Verification problems:\n");
            for (String problem : problems) {
                if (problem == null || problem.isBlank()) continue;
                frame.append("- ").append(problem.strip()).append('\n');
            }
        }
        frame.append("Write or repair the missing static web assets now. ")
                .append("For linked CSS/JavaScript files, create the exact linked filenames.");
        return List.of(
                ChatMessage.system("""
                        You are Talos, a local-first workspace assistant.
                        This is a bounded static-web verification continuation.
                        The prior mutation wrote part of the requested web artifact, but static verification found missing linked assets or structural web files.
                        Use the visible write/edit tools now. Do not claim completion until tool-backed changes have executed.
                        """),
                ChatMessage.system(frame.toString().stripTrailing()),
                ChatMessage.user("Current user request:\n"
                        + (userTask == null ? "" : userTask.strip())
                        + "\n\nCall talos.write_file or talos.edit_file now for the missing static web target files."));
    }

    private static String staticWebVerificationFailureContext(TaskVerificationResult verification) {
        if (verification == null || verification.status() != TaskVerificationStatus.FAILED) return "";
        String summary = verification.summary() == null || verification.summary().isBlank()
                ? "Static verification failed."
                : verification.summary().strip();
        StringBuilder out = new StringBuilder();
        out.append("[Task incomplete: Static verification failed - ")
                .append(summary)
                .append("]");
        List<String> problems = verification.problems();
        if (problems != null && !problems.isEmpty()) {
            out.append("\n\nUnresolved static verification problems:");
            for (String problem : problems) {
                if (problem == null || problem.isBlank()) continue;
                out.append("\n- ").append(problem.strip());
            }
        }
        out.append("\n\nThe requested task is not verified complete.");
        return out.toString();
    }

    private static ChatRequestControls staticWebCreationContinuationControls(
            LoopState state,
            List<ToolSpec> tools
    ) {
        boolean required = state != null
                && state.ctx != null
                && state.ctx.llm() != null
                && state.ctx.llm().supportsRequiredToolChoice()
                && hasMutatingTool(tools);
        return new ChatRequestControls(
                required ? ToolChoiceMode.REQUIRED : ToolChoiceMode.AUTO,
                "",
                ResponseFormatMode.TEXT,
                "",
                List.of("static-web-directory-only-continuation"));
    }

    private static String successfulDirectoryMutationSummary(LoopState state) {
        if (state == null || state.toolOutcomes == null || state.toolOutcomes.isEmpty()) return "";
        for (int i = state.toolOutcomes.size() - 1; i >= 0; i--) {
            ToolCallLoop.ToolOutcome outcome = state.toolOutcomes.get(i);
            if (!successfulDirectoryMutation(outcome)) continue;
            String summary = outcome.summary() == null ? "" : outcome.summary().strip();
            if (!summary.isBlank()) return summary;
            return outcome.pathHint() == null ? "" : outcome.pathHint().strip();
        }
        return "";
    }

    private static Optional<StaticWebContinuation> staticWebVerificationContinuation(LoopState state) {
        if (state == null || state.workspace == null) return Optional.empty();
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        if (contract == null || !contract.mutationAllowed() || !contract.mutationRequested()) {
            return Optional.empty();
        }
        if (!StaticWebCapabilityProfile.looksFunctionalWebTask(contract)) return Optional.empty();
        if (!hasSuccessfulSmallWebFileMutation(state)) return Optional.empty();
        TaskVerificationResult verification = staticWebVerification(state);
        if (verification.status() != TaskVerificationStatus.FAILED) return Optional.empty();
        List<String> missingTargets = missingStaticWebTargets(verification, state);
        if (missingTargets.isEmpty()) return Optional.empty();
        return Optional.of(new StaticWebContinuation(verification, missingTargets));
    }

    private static List<String> missingStaticWebTargets(TaskVerificationResult verification, LoopState state) {
        if (verification == null || verification.problems().isEmpty()) return List.of();
        Set<String> satisfied = successfulSmallWebMutationKeys(state);
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (String problem : verification.problems()) {
            if (problem == null || problem.isBlank()) continue;
            String lower = problem.toLowerCase(Locale.ROOT);
            addBacktickStaticWebTargets(problem, targets);
            if (lower.contains("css file") || lower.contains("css target")) {
                targets.add("styles.css");
            }
            if (lower.contains("javascript file") || lower.contains("js file")
                    || lower.contains("javascript target") || lower.contains("js target")) {
                targets.add("script.js");
            }
            if (lower.contains("html file") || lower.contains("html target")) {
                targets.add("index.html");
            }
        }
        addLinkedMissingStaticWebAssetsFromMutatedHtml(state, targets);
        return targets.stream()
                .map(ToolCallSupport::normalizePath)
                .filter(target -> !target.isBlank())
                .filter(StaticWebCapabilityProfile::isSmallWebFile)
                .filter(target -> !satisfied.contains(normalizeExpectedTargetKey(target)))
                .sorted()
                .toList();
    }

    private static void addLinkedMissingStaticWebAssetsFromMutatedHtml(LoopState state, Set<String> targets) {
        if (state == null || state.workspace == null || state.toolOutcomes == null || targets == null) return;
        Path root = state.workspace.toAbsolutePath().normalize();
        for (ToolCallLoop.ToolOutcome outcome : state.toolOutcomes) {
            if (!mutatedSmallWebFile(outcome)) continue;
            String htmlPath = ToolCallSupport.normalizePath(outcome.pathHint());
            if (!(htmlPath.endsWith(".html") || htmlPath.endsWith(".htm"))) continue;
            try {
                Path resolved = root.resolve(htmlPath).toAbsolutePath().normalize();
                if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) continue;
                String html = Files.readString(resolved);
                for (String linked : linkedStaticWebAssets(html)) {
                    String target = resolveLinkedAssetAgainstHtmlPath(htmlPath, linked);
                    if (target.isBlank()) continue;
                    Path linkedPath = root.resolve(target).toAbsolutePath().normalize();
                    if (!linkedPath.startsWith(root) || Files.isRegularFile(linkedPath)) continue;
                    targets.add(target);
                }
            } catch (Exception ignored) {
                // Verification already reports the failure; missing target inference is best effort.
            }
        }
    }

    private static List<String> linkedStaticWebAssets(String html) {
        if (html == null || html.isBlank()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String href : htmlAttributeValues(html, "href")) {
            String normalized = normalLinkedAssetCandidate(href);
            if (normalized.endsWith(".css")) out.add(normalized);
        }
        for (String src : htmlAttributeValues(html, "src")) {
            String normalized = normalLinkedAssetCandidate(src);
            if (normalized.endsWith(".js")) out.add(normalized);
        }
        return out.stream().toList();
    }

    private static List<String> htmlAttributeValues(String html, String attribute) {
        if (html == null || html.isBlank() || attribute == null || attribute.isBlank()) return List.of();
        String lower = html.toLowerCase(Locale.ROOT);
        String needle = attribute.toLowerCase(Locale.ROOT) + "=";
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < lower.length()) {
            int index = lower.indexOf(needle, start);
            if (index < 0) break;
            int valueStart = index + needle.length();
            while (valueStart < html.length() && Character.isWhitespace(html.charAt(valueStart))) {
                valueStart++;
            }
            if (valueStart >= html.length()) break;
            char quote = html.charAt(valueStart);
            if (quote == '"' || quote == '\'') {
                int valueEnd = html.indexOf(quote, valueStart + 1);
                if (valueEnd < 0) break;
                out.add(html.substring(valueStart + 1, valueEnd));
                start = valueEnd + 1;
            } else {
                int valueEnd = valueStart;
                while (valueEnd < html.length()
                        && !Character.isWhitespace(html.charAt(valueEnd))
                        && html.charAt(valueEnd) != '>') {
                    valueEnd++;
                }
                if (valueEnd > valueStart) {
                    out.add(html.substring(valueStart, valueEnd));
                }
                start = Math.max(valueEnd, valueStart + 1);
            }
        }
        return out;
    }

    private static String normalLinkedAssetCandidate(String value) {
        if (value == null || value.isBlank()) return "";
        String stripped = value.strip();
        int query = stripped.indexOf('?');
        if (query >= 0) stripped = stripped.substring(0, query);
        int fragment = stripped.indexOf('#');
        if (fragment >= 0) stripped = stripped.substring(0, fragment);
        String lower = stripped.toLowerCase(Locale.ROOT);
        if (lower.isBlank()
                || lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("//")
                || lower.startsWith("data:")
                || lower.startsWith("#")
                || lower.startsWith("/")) {
            return "";
        }
        return ToolCallSupport.normalizePath(stripped);
    }

    private static String resolveLinkedAssetAgainstHtmlPath(String htmlPath, String linked) {
        String normalizedHtml = ToolCallSupport.normalizePath(htmlPath);
        String normalizedLinked = ToolCallSupport.normalizePath(linked);
        if (normalizedHtml.isBlank() || normalizedLinked.isBlank()) return "";
        int slash = normalizedHtml.lastIndexOf('/');
        if (slash < 0) return normalizedLinked;
        return ToolCallSupport.normalizePath(normalizedHtml.substring(0, slash + 1) + normalizedLinked);
    }

    private static void addBacktickStaticWebTargets(String text, Set<String> targets) {
        if (text == null || text.isBlank() || targets == null) return;
        int start = 0;
        while (start < text.length()) {
            int open = text.indexOf('`', start);
            if (open < 0) return;
            int close = text.indexOf('`', open + 1);
            if (close < 0) return;
            String candidate = ToolCallSupport.normalizePath(text.substring(open + 1, close).strip());
            if (StaticWebCapabilityProfile.isSmallWebFile(candidate)) {
                targets.add(candidate);
            }
            start = close + 1;
        }
    }

    private static boolean hasSuccessfulSmallWebFileMutation(LoopState state) {
        if (state == null || state.toolOutcomes == null) return false;
        for (ToolCallLoop.ToolOutcome outcome : state.toolOutcomes) {
            if (mutatedSmallWebFile(outcome)) return true;
        }
        return false;
    }

    private static Set<String> successfulSmallWebMutationKeys(LoopState state) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (state == null || state.toolOutcomes == null) return out;
        for (ToolCallLoop.ToolOutcome outcome : state.toolOutcomes) {
            if (!mutatedSmallWebFile(outcome)) continue;
            addSmallWebMutationKey(out, outcome.pathHint());
            WorkspaceOperationPlan plan = outcome.workspaceOperationPlan();
            if (plan == null) continue;
            for (WorkspaceOperationPlan.PathEffect effect : plan.pathEffects()) {
                if (effect != null) {
                    addSmallWebMutationKey(out, effect.path());
                }
            }
        }
        return out;
    }

    private static void addSmallWebMutationKey(Set<String> out, String path) {
        if (out == null || path == null || path.isBlank()) return;
        if (!StaticWebCapabilityProfile.isSmallWebFile(path)) return;
        out.add(normalizeExpectedTargetKey(path));
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
                        Use the provided current expected-target readback as the only file-content source.
                        Only mutate the expected target path(s). Do not mutate the failed attempted target unless it is also explicitly listed as expected.
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
        if (compactMutationTargets(state, contract).isEmpty()) return false;
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

    private static boolean shouldContinueStaticWebCreationAfterDirectoryOnlyMutation(LoopState state) {
        if (state == null || state.toolOutcomes == null || state.toolOutcomes.isEmpty()) return false;
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        if (contract == null || !contract.mutationAllowed() || !contract.mutationRequested()) return false;
        if (!StaticWebCapabilityProfile.looksFunctionalWebTask(contract)) return false;
        if (staticWebVerificationAlreadyPasses(state)) return false;
        boolean hasDirectoryMutation = false;
        for (ToolCallLoop.ToolOutcome outcome : state.toolOutcomes) {
            if (outcome == null || !outcome.success() || !outcome.mutating()) continue;
            if (mutatedSmallWebFile(outcome)) {
                return false;
            }
            if (successfulDirectoryMutation(outcome)) {
                hasDirectoryMutation = true;
            }
        }
        return hasDirectoryMutation;
    }

    private static boolean successfulDirectoryMutation(ToolCallLoop.ToolOutcome outcome) {
        if (outcome == null || !outcome.success() || !outcome.mutating()) return false;
        String toolName = canonicalToolName(outcome.toolName());
        if ("talos.mkdir".equals(toolName)) return true;
        WorkspaceOperationPlan plan = outcome.workspaceOperationPlan();
        if (plan == null) return false;
        if (plan.operationKind() == WorkspaceOperationPlan.OperationKind.CREATE_DIRECTORY) return true;
        for (WorkspaceOperationPlan.PathEffect effect : plan.pathEffects()) {
            if (effect != null
                    && effect.operationKind() == WorkspaceOperationPlan.OperationKind.CREATE_DIRECTORY) {
                return true;
            }
        }
        return false;
    }

    private static boolean mutatedSmallWebFile(ToolCallLoop.ToolOutcome outcome) {
        if (outcome == null || !outcome.success() || !outcome.mutating()) return false;
        String toolName = canonicalToolName(outcome.toolName());
        if (("talos.write_file".equals(toolName) || "talos.edit_file".equals(toolName))
                && StaticWebCapabilityProfile.isSmallWebFile(outcome.pathHint())) {
            return true;
        }
        WorkspaceOperationPlan plan = outcome.workspaceOperationPlan();
        if (plan == null || plan.pathEffects().isEmpty()) return false;
        for (WorkspaceOperationPlan.PathEffect effect : plan.pathEffects()) {
            if (effect != null && StaticWebCapabilityProfile.isSmallWebFile(effect.path())) {
                return true;
            }
        }
        return false;
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

    private static String readTargetStopAnswer(
            LoopState state,
            ToolCallExecutionStage.IterationOutcome outcome
    ) {
        if (state == null || outcome == null) return null;
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        if (contract.type() != TaskType.READ_ONLY_QA || contract.expectedTargets().size() != 1) return null;
        String target = contract.expectedTargets().iterator().next();
        String normalizedTarget = ToolCallSupport.normalizePath(target);
        boolean targetRead = state.toolOutcomes.stream()
                .anyMatch(toolOutcome -> "talos.read_file".equals(canonicalToolName(toolOutcome.toolName()))
                        && toolOutcome.success()
                        && normalizedTarget.equals(ToolCallSupport.normalizePath(toolOutcome.pathHint())));
        if (!targetRead) return null;
        if (outcome.successesThisIteration() > 0 && outcome.failuresThisIteration() == 0) return null;
        String body = latestSuccessfulToolResultBodyByCanonical(state.messages, "talos.read_file");
        if (body == null || body.isBlank()) return null;
        return "Read " + target + ":\n" + body;
    }

    private static String directoryListingStopAnswer(
            LoopState state,
            ToolCallExecutionStage.IterationOutcome outcome
    ) {
        if (state == null || outcome == null || outcome.successesThisIteration() <= 0) return null;
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        if (contract.type() != TaskType.DIRECTORY_LISTING) return null;
        String body = DirectoryListingEvidence.selectedBody(
                state.messages,
                state.toolOutcomes,
                contract.originalUserRequest());
        if (body == null || body.isBlank()) return null;
        return renderDirectoryEntries(body);
    }

    private static String renderDirectoryEntries(String toolBody) {
        if (toolBody == null || toolBody.isBlank()) return null;
        String[] lines = toolBody.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        StringBuilder out = new StringBuilder("Directory entries:");
        boolean added = false;
        for (String line : lines) {
            String entry = line == null ? "" : line.strip();
            if (entry.isBlank()) continue;
            out.append("\n- ").append(entry);
            added = true;
        }
        return added ? out.toString() : null;
    }

    private static String latestSuccessfulToolResultBodyByCanonical(List<ChatMessage> messages, String canonicalToolName) {
        if (messages == null || messages.isEmpty() || canonicalToolName == null || canonicalToolName.isBlank()) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message == null || message.content() == null) continue;
            String content = message.content().strip();
            int prefixStart = content.indexOf("[tool_result:");
            if (prefixStart < 0) continue;
            int prefixEnd = content.indexOf(']', prefixStart);
            if (prefixEnd < 0) continue;
            String rawToolName = content.substring(prefixStart + "[tool_result:".length(), prefixEnd).strip();
            if (!canonicalToolName.equals(canonicalToolName(rawToolName))) continue;
            String body = content.substring(prefixEnd + 1).strip();
            int end = body.indexOf("[/tool_result]");
            if (end >= 0) {
                body = body.substring(0, end).strip();
            }
            if (body.startsWith("[error]")) continue;
            if (body.contains("You already gathered this information")) continue;
            return body;
        }
        return null;
    }

    private static String canonicalToolName(String toolName) {
        ToolAliasPolicy.Decision decision = ToolAliasPolicy.resolve(toolName);
        if (decision.accepted() && decision.canonicalToolName() != null && !decision.canonicalToolName().isBlank()) {
            return decision.canonicalToolName();
        }
        return toolName == null ? "" : toolName;
    }

    private static String unsupportedDocumentStopAnswer(
            LoopState state,
            ToolCallExecutionStage.IterationOutcome outcome
    ) {
        if (outcome == null) return null;
        if (outcome.successesThisIteration() > 0 || outcome.mutationsThisIteration() > 0) return null;
        List<String> unsupportedPaths = outcome.unsupportedReadPathsThisIteration();
        if (unsupportedPaths == null || unsupportedPaths.isEmpty()) return null;
        if (userNamedConvertedFallback(state, unsupportedPaths)) return null;
        return "[Document capability note: Talos could not inspect unsupported binary document contents with "
                + "the current local text-tool surface: "
                + String.join(", ", unsupportedPaths)
                + ". It cannot confirm whether those files are empty or what they contain.]";
    }

    private static boolean userNamedConvertedFallback(LoopState state, List<String> unsupportedPaths) {
        if (state == null || unsupportedPaths == null || unsupportedPaths.isEmpty()) return false;
        String userTask = ToolCallSupport.latestUserRequestIn(state.messages);
        if (userTask == null || userTask.isBlank()) return false;
        String lower = userTask.toLowerCase(java.util.Locale.ROOT);
        for (String path : unsupportedPaths) {
            String stem = filenameStem(path);
            if (stem.isBlank()) continue;
            if (lower.contains(stem + ".txt") || lower.contains("extracted_" + stem + ".txt")) {
                return true;
            }
        }
        return false;
    }

    private static String filenameStem(String path) {
        if (path == null || path.isBlank()) return "";
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).toLowerCase(java.util.Locale.ROOT);
    }

    private static String readOnlyWebDiagnosticStopAnswer(
            LoopState state,
            ToolCallExecutionStage.IterationOutcome outcome
    ) {
        if (state == null || outcome == null) return null;
        if (state.workspace == null) return null;
        if (state.totalToolsInvoked <= 0) return null;
        if (state.mutatingToolSuccesses > 0 || outcome.mutationsThisIteration() > 0) return null;

        String userTask = ToolCallSupport.latestUserRequestIn(state.messages);
        String retryTaskType = ToolCallSupport.embeddedRetryTaskType(userTask);
        if ("WORKSPACE_EXPLAIN".equals(retryTaskType)) return null;
        if (declaresTaskType(state.messages, "WORKSPACE_EXPLAIN")) return null;
        String intentUserTask = ToolCallSupport.effectiveUserRequestForRetryWrappedPrompt(userTask);
        if (!WebDiagnosticIntent.matchesReadOnlyRequest(intentUserTask)) return null;
        if (!readStaticWebDiagnosticSurface(state)) return null;

        String diagnostics = StaticTaskVerifier.renderWebDiagnostics(state.workspace);
        return diagnostics == null || diagnostics.isBlank() ? null : diagnostics;
    }

    private static boolean readStaticWebDiagnosticSurface(LoopState state) {
        if (state == null || state.pathsReadThisTurn == null || state.pathsReadThisTurn.isEmpty()) return false;
        boolean readHtml = false;
        boolean readScript = false;
        for (String path : state.pathsReadThisTurn) {
            String lower = ToolCallSupport.normalizePath(path).toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".html") || lower.endsWith(".htm")) {
                readHtml = true;
            }
            if (lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".ts") || lower.endsWith(".tsx")) {
                readScript = true;
            }
        }
        return readHtml && readScript;
    }

    private static boolean declaresTaskType(List<ChatMessage> messages, String taskType) {
        if (messages == null || taskType == null || taskType.isBlank()) return false;
        String marker = "Task type: " + taskType;
        for (ChatMessage message : messages) {
            if (message == null || message.content() == null) continue;
            if (message.content().contains(marker)) return true;
        }
        return false;
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

    private static boolean staticWebVerificationAlreadyPasses(LoopState state) {
        TaskVerificationResult verification = staticWebVerification(state);
        if (verification.status() != TaskVerificationStatus.PASSED) return false;
        String summary = verification.summary() == null ? "" : verification.summary();
        return summary.contains("Static web coherence checks passed");
    }

    private static TaskVerificationResult staticWebVerification(LoopState state) {
        if (state == null || state.workspace == null) return TaskVerificationResult.notRun("");
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        if (contract == null || !contract.mutationAllowed() || !contract.verificationRequired()) {
            return TaskVerificationResult.notRun("");
        }
        if (state.mutatingToolSuccesses <= 0) return TaskVerificationResult.notRun("");
        ToolCallLoop.LoopResult snapshot = new ToolCallLoop.LoopResult(
                state.currentText,
                state.iterations,
                state.totalToolsInvoked,
                List.copyOf(state.toolNames),
                state.messages,
                state.failedCalls,
                state.retriedCalls,
                false,
                state.mutatingToolSuccesses,
                List.copyOf(state.pathsReadThisTurn),
                state.cushionFiresRedundantRead,
                0,
                state.cushionFiresB3EditShortCircuit,
                state.cushionFiresE1Suggestion,
                state.failureDecision,
                List.copyOf(state.toolOutcomes));
        return StaticTaskVerifier.verifyWithoutTraceEvents(
                state.workspace,
                contract,
                snapshot,
                0);
    }
}
