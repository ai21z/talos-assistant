package dev.talos.runtime;

import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.toolcall.LoopState;
import dev.talos.runtime.toolcall.ToolCallExecutionStage;
import dev.talos.runtime.toolcall.ToolCallParseStage;
import dev.talos.runtime.toolcall.ToolCallRepromptStage;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.runtime.toolcall.ToolLoopResultSummaryFormatter;
import dev.talos.runtime.toolcall.ToolMutationEvidence;
import dev.talos.runtime.toolcall.ToolOutcomeFailureShape;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatMessage.NativeToolCall;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolProgressSink;
import dev.talos.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
            List<ToolOutcome> toolOutcomes,
            Map<String, String> readFileBodies
    ) {
        public LoopResult {
            toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
            messages = messages == null ? List.of() : messages;
            readPaths = readPaths == null ? List.of() : List.copyOf(readPaths);
            failureDecision = failureDecision == null
                    ? FailureDecision.continueLoop()
                    : failureDecision;
            toolOutcomes = toolOutcomes == null ? List.of() : List.copyOf(toolOutcomes);
            readFileBodies = readFileBodies == null ? Map.of() : Map.copyOf(readFileBodies);
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
                FailureDecision failureDecision,
                List<ToolOutcome> toolOutcomes
        ) {
            this(finalAnswer, iterations, toolsInvoked, toolNames, messages, failedCalls,
                    retriedCalls, hitIterLimit, mutatingToolSuccesses, readPaths,
                    cushionFiresRedundantRead, cushionFiresAliasRescue,
                    cushionFiresB3EditShortCircuit, cushionFiresE1Suggestion,
                    failureDecision, toolOutcomes, Map.of());
        }

        public String summary() {
            return ToolLoopResultSummaryFormatter.format(this);
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
            WorkspaceOperationPlan workspaceOperationPlan,
            ToolMutationEvidence mutationEvidence
    ) {
        public ToolOutcome {
            toolName = toolName == null ? "" : toolName;
            pathHint = pathHint == null ? "" : pathHint;
            summary = summary == null ? "" : summary;
            errorMessage = errorMessage == null ? "" : errorMessage;
            errorCode = errorCode == null ? "" : errorCode;
            mutationEvidence = mutationEvidence == null ? ToolMutationEvidence.none() : mutationEvidence;
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
                String errorCode,
                WorkspaceOperationPlan workspaceOperationPlan
        ) {
            this(toolName, pathHint, success, mutating, denied, summary, errorMessage,
                    fileVerificationStatus, errorCode, workspaceOperationPlan, ToolMutationEvidence.none());
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
            return ToolOutcomeFailureShape.invalidEmptyEditArguments(this);
        }

        public boolean fullRewriteRepairRedirect() {
            return ToolOutcomeFailureShape.fullRewriteRepairRedirect(this);
        }

        public boolean oldStringNotFoundEditFailure() {
            return ToolOutcomeFailureShape.oldStringNotFoundEditFailure(this);
        }

        public boolean appendLinePreservationFailure() {
            return ToolOutcomeFailureShape.appendLinePreservationFailure(this);
        }

        public boolean expectedTargetScopeFailure() {
            return ToolOutcomeFailureShape.expectedTargetScopeFailure(this);
        }
    }

    public LoopResult run(String initialAnswer, List<ChatMessage> messages, Path workspace, RuntimeTurnContext ctx) {
        return run(initialAnswer, List.of(), messages, workspace, ctx);
    }

    public LoopResult run(String initialAnswer, List<NativeToolCall> nativeToolCalls,
                          List<ChatMessage> messages, Path workspace, RuntimeTurnContext ctx) {
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
                if (ToolLoopFinalAnswerFinalizer.shouldSuppressUnfinishedToolContinuation(
                        state.currentText,
                        state.totalToolsInvoked)) {
                    LOG.warn("Suppressing unfinished tool-call continuation after {} executed tool(s)",
                            state.totalToolsInvoked);
                    state.currentText = ToolLoopFinalAnswerFinalizer.unresolvedContinuationFallback();
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
            state.currentText = ToolLoopFinalAnswerFinalizer.withIterationLimitNotice(state.currentText);
        }

        String finalAnswer = ToolLoopFinalAnswerFinalizer.finalizeAnswer(
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
                state.cushionFiresE1Suggestion, state.failureDecision, List.copyOf(state.toolOutcomes),
                Map.copyOf(state.readFileBodiesThisTurn));
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
