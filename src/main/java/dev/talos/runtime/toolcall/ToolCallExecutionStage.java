package dev.talos.runtime.toolcall;

import dev.talos.runtime.TurnProcessor;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolProgressSink;
import dev.talos.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class ToolCallExecutionStage {
    private static final Logger LOG = LoggerFactory.getLogger(ToolCallExecutionStage.class);

    /**
     * Outcome of one tool-call iteration.
     *
     * @param mutationsThisIteration count of successful mutating tool calls
     * @param mutationSummaries      short human-readable summaries of the
     *                               successful mutations
     * @param failuresThisIteration  count of failed tool calls in this
     *                               iteration, including short-circuited
     *                               duplicate-edit rejections. Gated by
     *                               {@link ToolCallRepromptStage} to decide
     *                               whether to skip the post-mutation
     *                               re-prompt (CCR-020 — skip only when
     *                               every call in the iteration succeeded).
     */
    public record IterationOutcome(int mutationsThisIteration,
                                   List<String> mutationSummaries,
                                   int failuresThisIteration,
                                   boolean approvalDeniedThisIteration,
                                   boolean mutatingDeniedThisIteration,
                                   int successesThisIteration) {}

    private final TurnProcessor turnProcessor;
    private final ToolProgressSink progressSink;
    private final boolean strict;

    public ToolCallExecutionStage(TurnProcessor turnProcessor, ToolProgressSink progressSink, boolean strict) {
        this.turnProcessor = turnProcessor;
        this.progressSink = progressSink;
        this.strict = strict;
    }

    public IterationOutcome execute(LoopState state, ToolCallParseStage.ParsedCalls parsed) {
        if (parsed.useNativePath()) {
            state.messages.add(ChatMessage.assistantWithToolCalls(state.currentText, state.currentNativeCalls));
        } else {
            state.messages.add(ChatMessage.assistant(state.currentText));
        }

        int mutationsThisIter = 0;
        int failuresThisIter = 0;
        int successesThisIter = 0;
        boolean approvalDeniedThisIter = false;
        boolean mutatingDeniedThisIter = false;
        List<String> mutationSummariesThisIter = new ArrayList<>();

        for (int i = 0; i < parsed.calls().size(); i++) {
            ToolCall call = parsed.calls().get(i);
            ToolCall effective = ToolCallSupport.repairMissingPath(call);

            String pathHint = ToolCallSupport.resolvePathHint(effective);
            emitProgress(effective.toolName(), "executing", pathHint);
            LOG.debug("  Executing tool: {} (params: {})", effective.toolName(), effective.parameters());

            boolean isEditFile = "talos.edit_file".equals(effective.toolName());
            if (isEditFile && !strict) {
                String callSig = ToolCallSupport.buildCallSignature(effective);
                if (state.failedCallSignatures.contains(callSig)) {
                    state.retriedCalls++;
                    state.failedCalls++;
                    state.cushionFiresB3EditShortCircuit++;
                    failuresThisIter++;
                    recordFailure(state, effective.toolName(), pathHint);
                    boolean emptyEditArguments = ToolCallSupport.hasEmptyEditArguments(effective);
                    if (emptyEditArguments) {
                        recordEmptyEditArgumentFailure(state, pathHint);
                    }
                    String diagnosticError = emptyEditArguments
                            ? emptyEditArgumentDiagnostic(pathHint, wasPathReadThisTurn(state, pathHint))
                            : "This exact edit was already attempted and failed. "
                                    + "Call talos.read_file to see the file's current state, "
                                    + "then provide the exact raw content (without line-number prefixes) in old_string. "
                                    + "Alternatively, use talos.write_file to replace the entire file content.";
                    String diagnostic = "[tool_result: " + effective.toolName() + "]\n"
                            + "[error] " + diagnosticError
                            + "\n[/tool_result]";
                    state.toolOutcomes.add(new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                            effective.toolName(), pathHint, false, true, false, "", diagnosticError,
                            null, ToolError.INVALID_PARAMS));
                    appendResultMessage(state, parsed.useNativePath(), i, diagnostic);
                    LOG.debug("  Skipped duplicate failing edit_file call for path: {}", pathHint);
                    continue;
                }
            }

            if (!strict && !state.mutationSinceStart && ToolCallSupport.isReadOnlyTool(effective.toolName())) {
                String readSig = ToolCallSupport.buildReadCallSignature(effective);
                String priorResult = state.successfulReadCalls.get(readSig);
                if (priorResult != null) {
                    state.cushionFiresRedundantRead++;
                    String diagnostic = "[tool_result: " + effective.toolName() + "]\n"
                            + "You already gathered this information and the workspace has not changed since then. "
                            + "Answer the user's question now using the evidence you already have."
                            + "\n[/tool_result]";
                    appendResultMessage(state, parsed.useNativePath(), i, diagnostic);
                    LOG.debug("  Suppressed redundant {} call (sig: {})", effective.toolName(), readSig);
                    continue;
                }
            }

            state.totalToolsInvoked++;
            state.toolNames.add(effective.toolName());

            String readBeforeWriteNudge = null;
            if (!strict && "talos.edit_file".equals(effective.toolName()) && pathHint != null) {
                if (!state.pathsReadThisTurn.contains(ToolCallSupport.normalizePath(pathHint))) {
                    readBeforeWriteNudge = "\nHint: You did not read this file before editing. "
                            + "Call talos.read_file first to see the current content, "
                            + "then retry the edit with the exact text.";
                }
            }

            ToolResult result = turnProcessor.executeTool(state.toolSession, effective, state.ctx);
            emitToolResult(effective.toolName(), result);
            if (result.success()) {
                successesThisIter++;
            }

            if ("talos.read_file".equals(effective.toolName()) && pathHint != null && result.success()) {
                state.pathsReadThisTurn.add(ToolCallSupport.normalizePath(pathHint));
            }
            if (result.success() && ToolCallSupport.isReadOnlyTool(effective.toolName())) {
                state.successfulReadCalls.put(
                        ToolCallSupport.buildReadCallSignature(effective),
                        ToolCallSupport.truncateForLog(result.output()));
            }
            if (ToolCallSupport.isMutatingTool(effective.toolName()) && result.success()) {
                state.mutationSinceStart = true;
                state.mutatingToolSuccesses++;
                mutationsThisIter++;
                String summary = ToolCallSupport.firstSentenceSummary(result.output());
                if (!summary.isBlank()) {
                    mutationSummariesThisIter.add("✓ " + summary);
                    state.pendingMutationSummaries.add("✓ " + summary);
                }
                state.successfulReadCalls.clear();
            }

            boolean denied = !result.success()
                    && result.error() != null
                    && ToolError.DENIED.equals(result.error().code());
            if (denied && ToolCallSupport.isMutatingTool(effective.toolName())) {
                mutatingDeniedThisIter = true;
            }
            if (isUserApprovalDenial(result) && ToolCallSupport.isMutatingTool(effective.toolName())) {
                approvalDeniedThisIter = true;
            }
            state.toolOutcomes.add(new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                    effective.toolName(),
                    pathHint,
                    result.success(),
                    ToolCallSupport.isMutatingTool(effective.toolName()),
                    denied,
                    result.success() ? ToolCallSupport.firstSentenceSummary(result.output()) : "",
                    result.success() ? "" : result.errorMessage(),
                    result.verification(),
                    result.error() == null ? "" : result.error().code()));

            if (!result.success()) {
                state.failedCalls++;
                failuresThisIter++;
                recordFailure(state, effective.toolName(), pathHint);
                if (ToolCallSupport.isMutatingTool(effective.toolName())) {
                    state.successfulReadCalls.clear();
                }
                if (isEditFile) {
                    String callSig = ToolCallSupport.buildCallSignature(effective);
                    state.failedCallSignatures.add(callSig);
                    if (ToolCallSupport.hasEmptyEditArguments(effective)) {
                        recordEmptyEditArgumentFailure(state, pathHint);
                    }
                    if (!strict && pathHint != null) {
                        int failCount = state.editFailuresByPath.merge(
                                ToolCallSupport.normalizePath(pathHint), 1, Integer::sum);
                        if (failCount >= 2) {
                            state.cushionFiresE1Suggestion++;
                            result = ToolResult.fail(dev.talos.tools.ToolError.invalidParams(
                                    result.errorMessage()
                                            + "\nSuggestion: edit_file has failed on this file multiple times. "
                                            + "Consider using talos.write_file with the complete updated file content instead."));
                        }
                    }
                }
            }

            String resultText = ToolCallSupport.formatToolResult(effective, result);
            if (readBeforeWriteNudge != null) {
                resultText = resultText + readBeforeWriteNudge;
            }
            appendResultMessage(state, parsed.useNativePath(), i, resultText);

            LOG.debug("  Tool {} → {}", effective.toolName(),
                    result.success() ? "success (" + ToolCallSupport.truncateForLog(result.output()) + ")"
                            : "error: " + result.errorMessage());
        }

        return new IterationOutcome(
                mutationsThisIter,
                mutationSummariesThisIter,
                failuresThisIter,
                approvalDeniedThisIter,
                mutatingDeniedThisIter,
                successesThisIter);
    }

    private static void recordFailure(LoopState state, String toolName, String pathHint) {
        if (state == null) return;
        if (toolName != null && !toolName.isBlank()) {
            state.failureCountsByTool.merge(toolName, 1, Integer::sum);
        }
        if (pathHint != null && !pathHint.isBlank()) {
            state.failureCountsByPath.merge(ToolCallSupport.normalizePath(pathHint), 1, Integer::sum);
        }
    }

    private static void recordEmptyEditArgumentFailure(LoopState state, String pathHint) {
        if (state == null || pathHint == null || pathHint.isBlank()) return;
        state.emptyEditArgumentFailuresByPath.merge(
                ToolCallSupport.normalizePath(pathHint), 1, Integer::sum);
    }

    private static boolean wasPathReadThisTurn(LoopState state, String pathHint) {
        return state != null
                && pathHint != null
                && state.pathsReadThisTurn.contains(ToolCallSupport.normalizePath(pathHint));
    }

    private static String emptyEditArgumentDiagnostic(String pathHint, boolean pathWasRead) {
        String target = pathHint == null || pathHint.isBlank()
                ? "the target file"
                : "`" + pathHint + "`";
        String prefix = pathWasRead
                ? "Repeated empty talos.edit_file arguments for " + target + " after the file was read. "
                : "Repeated empty talos.edit_file arguments for " + target + ". ";
        return prefix
                + "`old_string` and `new_string` were empty, so no approval was requested "
                + "and no file was changed. Copy the exact `old_string` from the latest "
                + "talos.read_file result and provide the intended `new_string`, or stop "
                + "and explain why the edit cannot be formed.";
    }

    private static boolean isUserApprovalDenial(ToolResult result) {
        if (result == null || result.success() || result.error() == null) return false;
        if (!ToolError.DENIED.equals(result.error().code())) return false;
        String message = result.errorMessage();
        return message != null && message.startsWith("User did not approve ");
    }

    private void appendResultMessage(LoopState state, boolean nativePath, int callIndex, String content) {
        if (nativePath && callIndex < state.currentNativeCalls.size()) {
            String callId = state.currentNativeCalls.get(callIndex).id();
            state.messages.add(ChatMessage.toolResult(callId, content));
        } else {
            state.messages.add(ChatMessage.user(content));
        }
    }

    private void emitProgress(String toolName, String action, String detail) {
        if (progressSink != null) {
            try {
                progressSink.onToolProgress(toolName, action, detail);
            } catch (Exception e) {
                LOG.debug("Progress sink error (ignored): {}", e.getMessage());
            }
        }
    }

    private void emitToolResult(String toolName, ToolResult result) {
        if (progressSink == null) return;
        if (!result.success()) {
            emitProgress(toolName, "error", result.errorMessage());
            return;
        }
        if (result.verification() != null && !result.verification().acceptable()) {
            String detail = ToolCallSupport.extractVerificationSummary(result.output());
            emitProgress(toolName, "warning", detail);
        }
    }
}
