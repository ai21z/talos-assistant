package dev.talos.runtime;

import dev.talos.cli.repl.Context;
import dev.talos.core.util.Sanitize;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.runtime.toolcall.LoopState;
import dev.talos.runtime.toolcall.ToolCallExecutionStage;
import dev.talos.runtime.toolcall.ToolCallParseStage;
import dev.talos.runtime.toolcall.ToolCallRepromptStage;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatMessage.NativeToolCall;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolProgressSink;
import dev.talos.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Agentic tool-call loop: receives tool calls (native or text-parsed),
 * executes them via {@link TurnProcessor#executeTool}, feeds results back
 * as messages, and re-prompts the LLM until the response contains no more
 * tool calls (or the iteration limit is reached).
 */
public final class ToolCallLoop {

    private static final Logger LOG = LoggerFactory.getLogger(ToolCallLoop.class);

    /** Default maximum tool-call iterations per turn. */
    public static final int DEFAULT_MAX_ITERATIONS = 10;

    private final TurnProcessor turnProcessor;
    private final int maxIterations;
    private final ToolProgressSink progressSink;
    private final boolean strict;

    public ToolCallLoop(TurnProcessor turnProcessor, int maxIterations, ToolProgressSink progressSink) {
        this(turnProcessor, maxIterations, progressSink, false);
    }

    public ToolCallLoop(TurnProcessor turnProcessor, int maxIterations,
                        ToolProgressSink progressSink, boolean strict) {
        this.turnProcessor = Objects.requireNonNull(turnProcessor, "turnProcessor");
        this.maxIterations = Math.max(1, maxIterations);
        this.progressSink = progressSink;
        this.strict = strict;
    }

    public boolean isStrict() {
        return strict;
    }

    public ToolCallLoop(TurnProcessor turnProcessor, int maxIterations) {
        this(turnProcessor, maxIterations, null);
    }

    public ToolCallLoop(TurnProcessor turnProcessor) {
        this(turnProcessor, DEFAULT_MAX_ITERATIONS, null);
    }

    public record LoopResult(
            String finalAnswer,
            int iterations,
            int toolsInvoked,
            List<String> toolNames,
            List<ChatMessage> messages,
            int failedCalls,
            int retriedCalls,
            boolean hitIterLimit,
            int mutatingToolSuccesses,
            List<String> readPaths,
            int cushionFiresRedundantRead,
            int cushionFiresAliasRescue,
            int cushionFiresB3EditShortCircuit,
            int cushionFiresE1Suggestion,
            FailureDecision failureDecision,
            List<ToolOutcome> toolOutcomes
    ) {
        public LoopResult {
            toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
            messages = messages == null ? List.of() : messages;
            readPaths = readPaths == null ? List.of() : List.copyOf(readPaths);
            failureDecision = failureDecision == null
                    ? FailureDecision.continueLoop()
                    : failureDecision;
            toolOutcomes = toolOutcomes == null ? List.of() : List.copyOf(toolOutcomes);
        }

        public LoopResult(
                String finalAnswer,
                int iterations,
                int toolsInvoked,
                List<String> toolNames,
                List<ChatMessage> messages,
                int failedCalls,
                int retriedCalls,
                boolean hitIterLimit,
                int mutatingToolSuccesses,
                List<String> readPaths,
                int cushionFiresRedundantRead,
                int cushionFiresAliasRescue,
                int cushionFiresB3EditShortCircuit,
                int cushionFiresE1Suggestion
        ) {
            this(finalAnswer, iterations, toolsInvoked, toolNames, messages, failedCalls,
                    retriedCalls, hitIterLimit, mutatingToolSuccesses, readPaths,
                    cushionFiresRedundantRead, cushionFiresAliasRescue,
                    cushionFiresB3EditShortCircuit, cushionFiresE1Suggestion,
                    FailureDecision.continueLoop(), List.of());
        }

        public LoopResult(
                String finalAnswer,
                int iterations,
                int toolsInvoked,
                List<String> toolNames,
                List<ChatMessage> messages,
                int failedCalls,
                int retriedCalls,
                boolean hitIterLimit,
                int mutatingToolSuccesses,
                List<String> readPaths,
                int cushionFiresRedundantRead,
                int cushionFiresAliasRescue,
                int cushionFiresB3EditShortCircuit,
                int cushionFiresE1Suggestion,
                List<ToolOutcome> toolOutcomes
        ) {
            this(finalAnswer, iterations, toolsInvoked, toolNames, messages, failedCalls,
                    retriedCalls, hitIterLimit, mutatingToolSuccesses, readPaths,
                    cushionFiresRedundantRead, cushionFiresAliasRescue,
                    cushionFiresB3EditShortCircuit, cushionFiresE1Suggestion,
                    FailureDecision.continueLoop(), toolOutcomes);
        }

        public String summary() {
            if (toolsInvoked <= 0) return null;
            var unique = new java.util.LinkedHashSet<>(toolNames != null ? toolNames : List.of());
            String names = unique.isEmpty() ? "" : ": " + String.join(", ", unique);
            String base = "[Used " + toolsInvoked + " tool(s)" + names + " | " + iterations + " iteration(s)]";
            int displayFailedCalls = displayFailedCalls();
            if (displayFailedCalls > 0) {
                base += " [" + displayFailedCalls + " failed]";
            }
            if (hitIterLimit) {
                base += " [iteration limit reached]";
            }
            if (failureDecision.shouldStop()) {
                base += " [failure policy stopped]";
            }
            return base;
        }

        private int displayFailedCalls() {
            if (failedCalls <= 0 || toolOutcomes.isEmpty()) return Math.max(0, failedCalls);
            int recovered = 0;
            for (int i = 0; i < toolOutcomes.size(); i++) {
                ToolOutcome failure = toolOutcomes.get(i);
                if (!isRecoveredEditFailureShape(failure)) continue;
                String failedPath = normalizeSummaryPath(failure.pathHint());
                if (failedPath.isBlank()) continue;
                for (int j = i + 1; j < toolOutcomes.size(); j++) {
                    ToolOutcome later = toolOutcomes.get(j);
                    if (later != null
                            && later.mutating()
                            && later.success()
                            && failedPath.equals(normalizeSummaryPath(later.pathHint()))) {
                        recovered++;
                        break;
                    }
                }
            }
            return Math.max(0, failedCalls - recovered);
        }

        private static boolean isRecoveredEditFailureShape(ToolOutcome outcome) {
            return outcome != null
                    && (outcome.invalidEmptyEditArguments()
                    || outcome.fullRewriteRepairRedirect()
                    || outcome.oldStringNotFoundEditFailure());
        }

        private static String normalizeSummaryPath(String path) {
            if (path == null || path.isBlank()) return "";
            return path.replace('\\', '/').replaceFirst("^\\./+", "").toLowerCase(java.util.Locale.ROOT);
        }
    }

    public record ToolOutcome(
            String toolName,
            String pathHint,
            boolean success,
            boolean mutating,
            boolean denied,
            String summary,
            String errorMessage,
            dev.talos.tools.VerificationStatus fileVerificationStatus,
            String errorCode,
            WorkspaceOperationPlan workspaceOperationPlan
    ) {
        public ToolOutcome {
            toolName = toolName == null ? "" : toolName;
            pathHint = pathHint == null ? "" : pathHint;
            summary = summary == null ? "" : summary;
            errorMessage = errorMessage == null ? "" : errorMessage;
            errorCode = errorCode == null ? "" : errorCode;
        }

        public ToolOutcome(
                String toolName,
                String pathHint,
                boolean success,
                boolean mutating,
                boolean denied,
                String summary,
                String errorMessage,
                dev.talos.tools.VerificationStatus fileVerificationStatus,
                String errorCode
        ) {
            this(toolName, pathHint, success, mutating, denied, summary, errorMessage,
                    fileVerificationStatus, errorCode, null);
        }

        public ToolOutcome(
                String toolName,
                String pathHint,
                boolean success,
                boolean mutating,
                boolean denied,
                String summary,
                String errorMessage,
                dev.talos.tools.VerificationStatus fileVerificationStatus
        ) {
            this(toolName, pathHint, success, mutating, denied, summary, errorMessage, fileVerificationStatus, "");
        }

        public ToolOutcome(
                String toolName,
                String pathHint,
                boolean success,
                boolean mutating,
                boolean denied,
                String summary,
                String errorMessage
        ) {
            this(toolName, pathHint, success, mutating, denied, summary, errorMessage, null);
        }

        public ToolOutcome(
                String toolName,
                String pathHint,
                boolean success,
                boolean mutating,
                String summary,
                String errorMessage
        ) {
            this(toolName, pathHint, success, mutating, false, summary, errorMessage);
        }

        public boolean invalidEmptyEditArguments() {
            if (!"talos.edit_file".equals(toolName)) return false;
            if (!mutating || success || denied) return false;
            if (!ToolError.INVALID_PARAMS.equals(errorCode)) return false;
            String lower = errorMessage.toLowerCase(java.util.Locale.ROOT);
            boolean oldStringProblem = lower.contains("old_string")
                    && (lower.contains("empty")
                    || lower.contains("non-empty")
                    || lower.contains("present"));
            boolean newStringProblem = lower.contains("new_string")
                    && lower.contains("missing required parameter");
            return oldStringProblem || newStringProblem;
        }

        public boolean fullRewriteRepairRedirect() {
            if (!"talos.edit_file".equals(toolName)) return false;
            if (!mutating || success || denied) return false;
            if (!ToolError.INVALID_PARAMS.equals(errorCode)) return false;
            String lower = errorMessage.toLowerCase(java.util.Locale.ROOT);
            return lower.contains("static verification repair requires a complete talos.write_file replacement");
        }

        public boolean oldStringNotFoundEditFailure() {
            if (!"talos.edit_file".equals(toolName)) return false;
            if (!mutating || success || denied) return false;
            if (!ToolError.INVALID_PARAMS.equals(errorCode)) return false;
            String lower = errorMessage.toLowerCase(java.util.Locale.ROOT);
            return lower.contains("old_string not found");
        }
    }

    public LoopResult run(String initialAnswer, List<ChatMessage> messages, Path workspace, Context ctx) {
        return run(initialAnswer, List.of(), messages, workspace, ctx);
    }

    public LoopResult run(String initialAnswer, List<NativeToolCall> nativeToolCalls,
                          List<ChatMessage> messages, Path workspace, Context ctx) {
        if (initialAnswer == null) initialAnswer = "";

        boolean hasNative = nativeToolCalls != null && !nativeToolCalls.isEmpty();
        boolean hasTextCalls = ToolCallParser.containsToolCalls(initialAnswer);
        if (!hasNative && !hasTextCalls) {
            if (CodeBlockToolExtractor.containsExtractableBlocks(initialAnswer)) {
                LOG.debug("Response contains code blocks with filename hints but no tool calls. "
                        + "File writes were NOT performed. The model should use tool_call format for file operations.");
            }
            return new LoopResult(initialAnswer, 0, 0, List.of(), messages, 0, 0, false, 0,
                    List.of(), 0, 0, 0, 0, List.of());
        }

        Session toolSession = new Session(workspace, ctx.cfg());
        LoopState state = new LoopState(
                initialAnswer,
                hasNative ? new ArrayList<>(nativeToolCalls) : List.of(),
                messages,
                workspace,
                ctx,
                toolSession,
                maxIterations,
                turnProcessor.toolRegistry().aliasRescueCount());

        ToolCallParseStage parseStage = new ToolCallParseStage();
        ToolCallExecutionStage executionStage = new ToolCallExecutionStage(turnProcessor, progressSink, strict);
        ToolCallRepromptStage repromptStage = new ToolCallRepromptStage();

        while (state.iterations < maxIterations) {
            ToolCallParseStage.ParsedCalls parsed =
                    parseStage.parse(state.currentText, state.currentNativeCalls, state.iterations + 1);
            if (!parsed.useNativePath() && !parsed.useTextPath()) {
                if (state.failPendingActionObligationAfterNoExecutableToolCalls()) {
                    break;
                }
                break;
            }
            state.iterations++;
            if (parsed.calls().isEmpty()) {
                if (state.failPendingActionObligationAfterNoExecutableToolCalls()) {
                    break;
                }
                if (shouldSuppressUnfinishedToolContinuation(state.currentText, state.totalToolsInvoked)) {
                    LOG.warn("Suppressing unfinished tool-call continuation after {} executed tool(s)",
                            state.totalToolsInvoked);
                    state.currentText = unresolvedContinuationFallback();
                }
                break;
            }
            if (state.failPendingActionObligationAfterInvalidToolCalls(parsed.calls())) {
                break;
            }
            if (state.failStaticRepairAfterInvalidWriteContent(parsed.calls())) {
                break;
            }
            if (state.failStaticSelectorRepairAfterInvalidWriteContent(parsed.calls())) {
                break;
            }

            ToolCallExecutionStage.IterationOutcome outcome = executionStage.execute(state, parsed);
            if (!repromptStage.reprompt(state, outcome)) {
                break;
            }
        }

        boolean hitIterLimit = repromptStage.hitIterationLimit(state);
        if (hitIterLimit) {
            LOG.warn("Tool-call loop reached max iterations ({}). Stopping.", maxIterations);
            state.currentText = ToolCallParser.stripToolCalls(state.currentText)
                    + "\n\n[Tool-call limit reached. Some tool calls were not executed.]";
        }

        String finalAnswer = finalizeAnswer(
                state.currentText,
                state.totalToolsInvoked,
                state.contentWithheldFromModelContext);

        LOG.debug("Tool-call loop complete: {} iterations, {} tools invoked, {} failed",
                state.iterations, state.totalToolsInvoked, state.failedCalls);

        int cushionFiresAliasRescue =
                turnProcessor.toolRegistry().aliasRescueCount() - state.aliasRescueBaseline;

        return new LoopResult(finalAnswer, state.iterations, state.totalToolsInvoked,
                List.copyOf(state.toolNames), messages, state.failedCalls, state.retriedCalls,
                hitIterLimit, state.mutatingToolSuccesses, List.copyOf(state.pathsReadThisTurn),
                state.cushionFiresRedundantRead,
                cushionFiresAliasRescue, state.cushionFiresB3EditShortCircuit,
                state.cushionFiresE1Suggestion, state.failureDecision, List.copyOf(state.toolOutcomes));
    }

    private static String finalizeAnswer(String currentText, int toolsInvoked, boolean contentWithheldFromModelContext) {
        if (shouldSuppressUnfinishedToolContinuation(currentText, toolsInvoked)) {
            return unresolvedContinuationFallback();
        }
        String answer = Sanitize.stripSuspiciousHtml(ToolCallParser.stripToolCalls(currentText));
        return contentWithheldFromModelContext
                ? ProtectedContentPolicy.sanitizeText(answer)
                : answer;
    }

    private static boolean shouldSuppressUnfinishedToolContinuation(String text, int toolsInvoked) {
        return toolsInvoked > 0 && ToolCallParser.looksLikeUnfinishedToolPayload(text);
    }

    private static String unresolvedContinuationFallback() {
        return "[Tool-call continuation could not be completed. No further tool calls were executed.]";
    }

    static List<ToolCall> convertNativeToolCalls(List<NativeToolCall> nativeCalls) {
        return ToolCallSupport.convertNativeToolCalls(nativeCalls);
    }

    static String formatToolResult(ToolCall call, ToolResult result) {
        return ToolCallSupport.formatToolResult(call, result);
    }

    static String extractVerificationSummary(String output) {
        return ToolCallSupport.extractVerificationSummary(output);
    }

    static String latestUserRequestIn(List<ChatMessage> messages) {
        return ToolCallSupport.latestUserRequestIn(messages);
    }

    static final int KEEP_RECENT_TOOL_RESULTS = ToolCallSupport.KEEP_RECENT_TOOL_RESULTS;

    static void compactOlderToolResultsInPlace(List<ChatMessage> messages) {
        ToolCallSupport.compactOlderToolResultsInPlace(messages);
    }

    static String summarizeToolResult(String body) {
        return ToolCallSupport.summarizeToolResult(body);
    }

    static String firstSentenceSummary(String output) {
        return ToolCallSupport.firstSentenceSummary(output);
    }

    static String buildCallSignature(ToolCall call) {
        return ToolCallSupport.buildCallSignature(call);
    }

    static String canonicalizeReadPath(String path) {
        return ToolCallSupport.canonicalizeReadPath(path);
    }

    static boolean isReadOnlyTool(String toolName) {
        return ToolCallSupport.isReadOnlyTool(toolName);
    }

    static boolean isMutatingTool(String toolName) {
        return ToolCallSupport.isMutatingTool(toolName);
    }

    static String buildReadCallSignature(ToolCall call) {
        return ToolCallSupport.buildReadCallSignature(call);
    }

    static ToolCall repairMissingPath(ToolCall call) {
        return ToolCallSupport.repairMissingPath(call);
    }
}
