package dev.talos.runtime.toolcall;

import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.failure.FailurePolicy;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallParser;
import dev.talos.runtime.repair.RepairInstruction;
import dev.talos.runtime.repair.RepairPolicy;
import dev.talos.runtime.policy.ResponseObligationVerifier;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.verification.StaticTaskVerifier;
import dev.talos.runtime.verification.TaskVerificationResult;
import dev.talos.runtime.verification.TaskVerificationStatus;
import dev.talos.runtime.verification.WebDiagnosticIntent;
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
import java.util.List;
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
            String reason = "REPAIR_INSPECTION_ONLY: repair/fix turn inspected files with "
                    + state.toolNames.size()
                    + " read-only tool call(s) but did not call write/edit before the read-only repair budget was exhausted.";
            state.failureDecision = FailureDecision.stop(FailureAction.ASK_USER, reason);
            state.currentText = ResponseObligationVerifier.deterministicRepairInspectionOnlyAnswer();
            state.currentNativeCalls = List.of();
            LocalTurnTraceCapture.recordActionObligation(
                    "MUTATING_TOOL_REQUIRED",
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
        boolean obligationGateActive = outcome.mutationsThisIteration() > 0
                || state.hasPendingActionObligation();
        if (obligationGateActive && !remainingRepairTargets.isEmpty()) {
            state.setPendingActionObligation(
                    PendingActionObligation.staticRepairTargets(remainingRepairTargets));
        } else if (obligationGateActive && !remainingExpectedTargets.isEmpty()) {
            state.setPendingActionObligation(
                    PendingActionObligation.expectedTargets(remainingExpectedTargets));
        } else {
            state.clearPendingActionObligation();
        }

        int anchorIndex = -1;
        String userTask = ToolCallSupport.latestUserRequestIn(state.messages);
        if (userTask != null && !userTask.isBlank()) {
            String pinned = userTask.length() <= 500 ? userTask : userTask.substring(0, 500) + "…";
            state.messages.add(ChatMessage.system("[Current task — stay focused on this] " + pinned));
            anchorIndex = state.messages.size() - 1;
        }

        try {
            LlmClient.StreamResult repromptResult =
                    state.ctx.llm().chatFull(
                            state.messages,
                            state.ctx.nativeToolSpecs(),
                            repromptControls(state));
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
                                state.messages,
                                state.ctx.nativeToolSpecs(),
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

    public boolean hitIterationLimit(LoopState state) {
        return state.iterations >= state.maxIterations
                && (!state.currentNativeCalls.isEmpty() || ToolCallParser.containsToolCalls(state.currentText));
    }

    private static ChatRequestControls repromptControls(LoopState state) {
        if (state == null
                || state.ctx == null
                || state.ctx.llm() == null
                || !state.hasPendingActionObligation()
                || !state.ctx.llm().supportsRequiredToolChoice()
                || !hasMutatingTool(state.ctx.nativeToolSpecs())) {
            return ChatRequestControls.defaults();
        }
        return new ChatRequestControls(
                ToolChoiceMode.REQUIRED,
                "",
                ResponseFormatMode.TEXT,
                "",
                List.of("pending-action-obligation"));
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
        return readOnlyCalls >= REPAIR_READ_ONLY_TOOL_BUDGET;
    }

    private static boolean isRepairOrFixMutationContract(TaskContract contract) {
        if (contract == null || !contract.mutationAllowed() || !contract.mutationRequested()) return false;
        String reason = contract.classificationReason();
        return "explicit-review-and-fix-request".equals(reason)
                || "repair-follow-up-inherits-previous-mutation-contract".equals(reason);
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
        String body = latestSuccessfulToolResultBodyByCanonical(state.messages, "talos.list_dir");
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
        if (userTask != null && userTask.contains("Task type: WORKSPACE_EXPLAIN")) return null;
        if (declaresTaskType(state.messages, "WORKSPACE_EXPLAIN")) return null;
        if (!WebDiagnosticIntent.matchesReadOnlyRequest(userTask)) return null;

        String diagnostics = StaticTaskVerifier.renderWebDiagnostics(state.workspace);
        return diagnostics == null || diagnostics.isBlank() ? null : diagnostics;
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
        return expectedTargets.stream()
                .map(ToolCallRepromptStage::normalizeExpectedTargetKey)
                .filter(path -> !path.isBlank())
                .filter(path -> !satisfiedTargets.contains(path))
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
