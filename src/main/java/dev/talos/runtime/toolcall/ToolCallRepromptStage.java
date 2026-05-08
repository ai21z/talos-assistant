package dev.talos.runtime.toolcall;

import dev.talos.core.llm.LlmClient;
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
            state.currentText = state.failureDecision.shouldStop()
                    ? failurePolicyStopMessage(state.failureDecision)
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
            state.currentText = failurePolicyStopMessage(state.failureDecision);
            state.currentNativeCalls = List.of();
            LOG.debug("Stopping tool-call loop after stale edit retry ignored reread requirement for {}",
                    state.staleEditRereadIgnoredPath);
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

        FailureDecision failureDecision = FailurePolicy.defaults(state.maxIterations)
                .afterIteration(state, outcome);
        if (failureDecision.shouldStop()) {
            state.failureDecision = failureDecision;
            state.currentText = failurePolicyStopMessage(failureDecision);
            state.currentNativeCalls = List.of();
            LOG.debug("Stopping tool-call loop by failure policy: {}", failureDecision.reason());
            return false;
        }

        if (state.iterations >= 3) {
            ToolCallSupport.compactOlderToolResultsInPlace(state.messages);
        }

        String userTask = ToolCallSupport.latestUserRequestIn(state.messages);
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
            LOG.warn("Ollama not reachable during tool-call loop iteration {}: {}", state.iterations, cf.getMessage());
            state.currentText = "[Ollama not reachable — tool loop aborted. " + cf.guidance() + "]";
            state.currentNativeCalls = List.of();
            return false;
        } catch (EngineException.ModelNotFound mnf) {
            LOG.warn("Model not found during tool-call loop iteration {}: {}", state.iterations, mnf.model());
            state.currentText = "[Model '" + mnf.model() + "' not found — tool loop aborted. " + mnf.guidance() + "]";
            state.currentNativeCalls = List.of();
            return false;
        } catch (EngineException.Transient tr) {
            LOG.warn("Transient error during tool-call loop iteration {}: {}", state.iterations, tr.getMessage());
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
            LOG.warn("Engine error during tool-call loop iteration {}: {}", state.iterations, ee.getMessage());
            state.currentText = "[Engine error during tool loop: " + ee.getMessage() + "]";
            state.currentNativeCalls = List.of();
            return false;
        } catch (Exception e) {
            LOG.warn("LLM call failed during tool-call loop iteration {}: {}", state.iterations, e.getMessage());
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
                tryCompactMutationContinuation(state, retryName, budget);
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
            EngineException.ContextBudgetExceeded originalBudget
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
                            + " exceeded context budget: "
                            + ResponseObligationVerifier.contextBudgetRetrySkippedDetail(originalBudget));
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
            LOG.warn("Ollama not reachable during {}: {}", retryName, cf.getMessage());
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
            LOG.warn("Engine error during {}: {}", retryName, ee.getMessage());
            state.currentText = "[Engine error during tool loop: " + ee.getMessage() + "]";
            state.currentNativeCalls = List.of();
            return false;
        } catch (Exception e) {
            LOG.warn("LLM call failed during {}: {}", retryName, e.getMessage());
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

    private static String safeRepairReason(String reason) {
        if (reason == null || reason.isBlank()) return "old_string not found";
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

    private static String failurePolicyStopMessage(FailureDecision decision) {
        String reason = decision == null || decision.reason().isBlank()
                ? "repeated tool failures"
                : decision.reason();
        return "[Tool loop stopped by failure policy: "
                + reason
                + " Review the latest tool errors before retrying.]";
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
            LOG.warn("Response-only synthesis after denied mutation failed: {}", e.getMessage());
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
        if (state == null || state.workspace == null) return false;
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        if (contract == null || !contract.mutationAllowed() || !contract.verificationRequired()) {
            return false;
        }
        if (state.mutatingToolSuccesses <= 0) return false;
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
        TaskVerificationResult verification = StaticTaskVerifier.verify(
                state.workspace,
                contract,
                snapshot,
                0);
        if (verification.status() != TaskVerificationStatus.PASSED) return false;
        String summary = verification.summary() == null ? "" : verification.summary();
        return summary.contains("Static web coherence checks passed");
    }
}
