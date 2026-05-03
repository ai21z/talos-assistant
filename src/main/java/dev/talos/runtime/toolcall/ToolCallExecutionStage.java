package dev.talos.runtime.toolcall;

import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.repair.RepairPolicy;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolProgressSink;
import dev.talos.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
                                   boolean pathPolicyBlockedThisIteration,
                                   int successesThisIteration,
                                   List<String> unsupportedReadPathsThisIteration) {
        public IterationOutcome {
            unsupportedReadPathsThisIteration = unsupportedReadPathsThisIteration == null
                    ? List.of()
                    : List.copyOf(unsupportedReadPathsThisIteration);
        }

        public IterationOutcome(int mutationsThisIteration,
                                List<String> mutationSummaries,
                                int failuresThisIteration,
                                boolean approvalDeniedThisIteration,
                                boolean mutatingDeniedThisIteration,
                                boolean pathPolicyBlockedThisIteration,
                                int successesThisIteration) {
            this(
                    mutationsThisIteration,
                    mutationSummaries,
                    failuresThisIteration,
                    approvalDeniedThisIteration,
                    mutatingDeniedThisIteration,
                    pathPolicyBlockedThisIteration,
                    successesThisIteration,
                    List.of());
        }
    }

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
        boolean pathPolicyBlockedThisIter = false;
        List<String> mutationSummariesThisIter = new ArrayList<>();
        List<String> unsupportedReadPathsThisIter = new ArrayList<>();
        Set<String> staleRereadRequiredAtStart = staleRereadRequiredPaths(state);
        Set<String> fullRewriteRepairTargets = strict
                ? Set.of()
                : RepairPolicy.fullRewriteTargetsFromRepairContext(state.messages);

        for (int i = 0; i < parsed.calls().size(); i++) {
            ToolCall call = parsed.calls().get(i);
            ToolCall effective = ToolCallSupport.repairMissingPath(call);

            String pathHint = ToolCallSupport.resolvePathHint(effective);
            emitProgress(effective.toolName(), "executing", pathHint);
            LOG.debug("  Executing tool: {} (params: {})", effective.toolName(), effective.parameters());

            boolean isEditFile = "talos.edit_file".equals(effective.toolName());
            if (isEditFile
                    && !strict
                    && fullRewriteRepairTargets.contains(normalizePath(pathHint))) {
                state.failedCalls++;
                failuresThisIter++;
                recordFailure(state, effective.toolName(), pathHint);
                String diagnosticError = fullRewriteRepairRequiredDiagnostic(pathHint);
                String diagnostic = "[tool_result: " + effective.toolName() + "]\n"
                        + "[error] " + diagnosticError
                        + "\n[/tool_result]";
                state.toolOutcomes.add(new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                        effective.toolName(), pathHint, false, true, false, "", diagnosticError,
                        null, ToolError.INVALID_PARAMS));
                appendResultMessage(state, parsed.useNativePath(), i, diagnostic);
                LOG.debug("Blocked edit_file for full-rewrite repair target {}", pathHint);
                continue;
            }

            if (isEditFile && !strict && staleRereadRequiredAtStart.contains(normalizePath(pathHint))) {
                state.failedCalls++;
                failuresThisIter++;
                recordFailure(state, effective.toolName(), pathHint);
                state.staleEditRereadIgnoredPath = normalizePath(pathHint);
                String diagnosticError = staleEditRereadRequiredDiagnostic(pathHint);
                String diagnostic = "[tool_result: " + effective.toolName() + "]\n"
                        + "[error] " + diagnosticError
                        + "\n[/tool_result]";
                state.toolOutcomes.add(new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                        effective.toolName(), pathHint, false, true, false, "", diagnosticError,
                        null, ToolError.INVALID_PARAMS));
                appendResultMessage(state, parsed.useNativePath(), i, diagnostic);
                LOG.debug("Blocked stale edit retry for path {} until read_file runs in a later iteration", pathHint);
                continue;
            }

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
                recordSuccessfulRead(state, pathHint);
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
                recordMutationSuccess(state, pathHint);
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
            if (!result.success()
                    && result.error() != null
                    && ToolError.UNSUPPORTED_FORMAT.equals(result.error().code())
                    && "talos.read_file".equals(effective.toolName())
                    && pathHint != null
                    && !pathHint.isBlank()) {
                unsupportedReadPathsThisIter.add(ToolCallSupport.normalizePath(pathHint));
            }
            if (isPreApprovalPathPolicyBlock(result) && ToolCallSupport.isMutatingTool(effective.toolName())) {
                pathPolicyBlockedThisIter = true;
                if (isExpectedTargetScopeBlock(result)) {
                    state.failureDecision = dev.talos.runtime.failure.FailureDecision.stop(
                            dev.talos.runtime.failure.FailureAction.ASK_USER,
                            result.errorMessage());
                }
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
                    if (isOldStringNotFound(result) && wasMutatedSinceRead(state, pathHint)) {
                        recordStaleEditFailure(state, pathHint);
                    }
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
                pathPolicyBlockedThisIter,
                successesThisIter,
                unsupportedReadPathsThisIter);
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

    private static Set<String> staleRereadRequiredPaths(LoopState state) {
        if (state == null || state.staleEditFailuresByPath.isEmpty()) {
            return Set.of();
        }
        Set<String> paths = new HashSet<>();
        for (String path : state.staleEditFailuresByPath.keySet()) {
            String normalized = ToolCallSupport.normalizePath(path);
            if (!normalized.isBlank() && state.pathsMutatedSinceRead.contains(normalized)) {
                paths.add(normalized);
            }
        }
        return paths;
    }

    private static void recordSuccessfulRead(LoopState state, String pathHint) {
        if (state == null || pathHint == null || pathHint.isBlank()) return;
        String path = normalizePath(pathHint);
        state.pathsReadThisTurn.add(path);
        state.pathsMutatedSinceRead.remove(path);
        state.staleEditFailuresByPath.remove(path);
        state.staleEditRepairPromptedPaths.remove(path);
        if (path.equals(state.staleEditRereadIgnoredPath)) {
            state.staleEditRereadIgnoredPath = null;
        }
    }

    private static void recordMutationSuccess(LoopState state, String pathHint) {
        if (state == null || pathHint == null || pathHint.isBlank()) return;
        String path = normalizePath(pathHint);
        state.pathsMutatedSinceRead.add(path);
    }

    private static void recordEmptyEditArgumentFailure(LoopState state, String pathHint) {
        if (state == null || pathHint == null || pathHint.isBlank()) return;
        state.emptyEditArgumentFailuresByPath.merge(
                normalizePath(pathHint), 1, Integer::sum);
    }

    private static void recordStaleEditFailure(LoopState state, String pathHint) {
        if (state == null || pathHint == null || pathHint.isBlank()) return;
        state.staleEditFailuresByPath.merge(normalizePath(pathHint), 1, Integer::sum);
    }

    private static boolean wasPathReadThisTurn(LoopState state, String pathHint) {
        return state != null
                && pathHint != null
                && state.pathsReadThisTurn.contains(normalizePath(pathHint));
    }

    private static boolean wasMutatedSinceRead(LoopState state, String pathHint) {
        return state != null
                && pathHint != null
                && state.pathsMutatedSinceRead.contains(normalizePath(pathHint));
    }

    private static boolean isOldStringNotFound(ToolResult result) {
        if (result == null || result.success() || result.error() == null) return false;
        if (!ToolError.INVALID_PARAMS.equals(result.error().code())) return false;
        String message = result.errorMessage();
        return message != null && message.contains("old_string not found");
    }

    private static String normalizePath(String pathHint) {
        return ToolCallSupport.normalizePath(pathHint == null ? "" : pathHint);
    }

    private static String emptyEditArgumentDiagnostic(String pathHint, boolean pathWasRead) {
        String target = pathHint == null || pathHint.isBlank()
                ? "the target file"
                : "`" + pathHint + "`";
        String prefix = pathWasRead
                ? "Repeated empty or missing talos.edit_file arguments for " + target + " after the file was read. "
                : "Repeated empty or missing talos.edit_file arguments for " + target + ". ";
        return prefix
                + "`old_string` was empty or `new_string` was missing, so no approval was requested "
                + "and no file was changed. Copy the exact `old_string` from the latest "
                + "talos.read_file result and provide the intended `new_string`, or stop "
                + "and explain why the edit cannot be formed.";
    }

    private static String staleEditRereadRequiredDiagnostic(String pathHint) {
        String target = pathHint == null || pathHint.isBlank()
                ? "the target file"
                : "`" + pathHint + "`";
        return "A previous edit changed " + target
                + ", then another edit for the same file failed because old_string was not found. "
                + "Call talos.read_file for " + target
                + " in a separate follow-up step before attempting another talos.edit_file. "
                + "No approval was requested and no additional file change was made.";
    }

    private static String fullRewriteRepairRequiredDiagnostic(String pathHint) {
        String target = pathHint == null || pathHint.isBlank()
                ? "the target file"
                : "`" + pathHint + "`";
        return "Static verification repair requires a complete talos.write_file replacement for "
                + target + ". This talos.edit_file call was not executed, no approval was requested, "
                + "and no file was changed. Use talos.write_file with the full corrected file content "
                + "for this small web file.";
    }

    private static boolean isUserApprovalDenial(ToolResult result) {
        if (result == null || result.success() || result.error() == null) return false;
        if (!ToolError.DENIED.equals(result.error().code())) return false;
        String message = result.errorMessage();
        return message != null && message.startsWith("User did not approve ");
    }

    private static boolean isPreApprovalPathPolicyBlock(ToolResult result) {
        if (result == null || result.success() || result.error() == null) return false;
        if (!ToolError.INVALID_PARAMS.equals(result.error().code())) return false;
        String message = result.errorMessage();
        return message != null
                && (message.startsWith("Path not allowed before approval")
                || message.startsWith("Invalid path before approval")
                || message.startsWith("Target outside expected targets before approval"));
    }

    private static boolean isExpectedTargetScopeBlock(ToolResult result) {
        if (result == null || result.success() || result.error() == null) return false;
        if (!ToolError.INVALID_PARAMS.equals(result.error().code())) return false;
        String message = result.errorMessage();
        return message != null && message.startsWith("Target outside expected targets before approval");
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
