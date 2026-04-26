package dev.talos.runtime.toolcall;

import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.failure.FailurePolicy;
import dev.talos.runtime.ToolCallParser;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class ToolCallRepromptStage {
    private static final Logger LOG = LoggerFactory.getLogger(ToolCallRepromptStage.class);

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
            state.currentText = "[Tool loop stopped because a mutating path was blocked by workspace policy before approval.]";
            state.currentNativeCalls = List.of();
            LOG.debug("Stopping tool-call loop after pre-approval path policy block; not re-prompting.");
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
            state.currentText = String.join("\n", outcome.mutationSummaries());
            state.currentNativeCalls = List.of();
            LOG.debug("P0: skipping re-prompt after {} successful mutation(s) this iteration",
                    outcome.mutationsThisIteration());
            return false;
        }

        if (outcome.mutationsThisIteration() > 0 && outcome.failuresThisIteration() > 0) {
            LOG.debug("CCR-020: re-prompting after partial success ({} mutation(s), {} failure(s) "
                    + "this iteration) so the model can retry the failed call(s)",
                    outcome.mutationsThisIteration(), outcome.failuresThisIteration());
            // fall through to the re-prompt path below
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

        int repairIndex = -1;
        Optional<EmptyEditRepair> repair = nextEmptyEditRepair(state);
        if (repair.isPresent()) {
            state.messages.add(ChatMessage.system(repair.get().instruction()));
            state.emptyEditRepairPromptedPaths.add(repair.get().path());
            repairIndex = state.messages.size() - 1;
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
                    state.ctx.llm().chatFull(state.messages, state.ctx.nativeToolSpecs());
            state.currentText = repromptResult.text();
            state.currentNativeCalls = repromptResult.hasToolCalls()
                    ? new ArrayList<>(repromptResult.toolCalls()) : List.of();
            if (state.currentText == null) state.currentText = "";
            if (state.currentText.isEmpty() && state.currentNativeCalls.isEmpty()) {
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
                        state.ctx.llm().chatFull(state.messages, state.ctx.nativeToolSpecs());
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
            if (repairIndex >= 0 && repairIndex < state.messages.size()) {
                ChatMessage m = state.messages.get(repairIndex);
                if ("system".equals(m.role())
                        && m.content() != null
                        && m.content().startsWith("[Edit repair required]")) {
                    state.messages.remove(repairIndex);
                }
            }
        }
    }

    public boolean hitIterationLimit(LoopState state) {
        return state.iterations >= state.maxIterations
                && (!state.currentNativeCalls.isEmpty() || ToolCallParser.containsToolCalls(state.currentText));
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

        int anchorIndex = -1;
        state.messages.add(ChatMessage.system(
                "[Tool policy stop] The latest mutating tool call was rejected by Talos policy. "
                        + "Do not call any more tools in this turn. Answer the user's request using only "
                        + "the tool results already gathered. If the gathered evidence is insufficient, "
                        + "say exactly what was inspected and what remains unknown."));
        anchorIndex = state.messages.size() - 1;

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
            if (anchorIndex >= 0 && anchorIndex < state.messages.size()) {
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

    record EmptyEditRepair(String path, String instruction) {}

    static Optional<EmptyEditRepair> nextEmptyEditRepair(LoopState state) {
        if (state == null
                || state.emptyEditArgumentFailuresByPath == null
                || state.emptyEditArgumentFailuresByPath.isEmpty()
                || state.pathsReadThisTurn == null
                || state.pathsReadThisTurn.isEmpty()) {
            return Optional.empty();
        }

        return state.emptyEditArgumentFailuresByPath.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() >= 1)
                .filter(entry -> state.pathsReadThisTurn.contains(entry.getKey()))
                .filter(entry -> !state.emptyEditRepairPromptedPaths.contains(entry.getKey()))
                .max(Comparator
                        .<java.util.Map.Entry<String, Integer>>comparingInt(java.util.Map.Entry::getValue)
                        .thenComparing(java.util.Map.Entry::getKey))
                .map(entry -> new EmptyEditRepair(entry.getKey(), emptyEditRepairInstruction(entry.getKey())));
    }

    static String emptyEditRepairInstruction(String path) {
        String target = path == null || path.isBlank() ? "the target file" : "`" + path + "`";
        return "[Edit repair required] You previously called talos.edit_file for "
                + target
                + " with empty old_string/new_string, and the file has now been read. "
                + "Your next talos.edit_file call for this file must include a non-empty "
                + "old_string copied exactly from the latest talos.read_file result, without "
                + "line-number prefixes, and a new_string parameter containing the intended "
                + "replacement. new_string may be empty only for an explicit deletion task. "
                + "Do not call talos.edit_file with empty old_string again. If you "
                + "cannot form the exact edit, stop and say no edit was applied.";
    }
}
