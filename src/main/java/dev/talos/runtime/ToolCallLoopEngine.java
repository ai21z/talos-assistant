package dev.talos.runtime;

import dev.talos.runtime.toolcall.LoopState;
import dev.talos.runtime.toolcall.ToolCallExecutionStage;
import dev.talos.runtime.toolcall.ToolCallParseStage;
import dev.talos.runtime.toolcall.ToolCallRepromptStage;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatMessage.NativeToolCall;
import dev.talos.tools.ToolProgressSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ToolCallLoopEngine {
    private static final Logger LOG = LoggerFactory.getLogger(ToolCallLoopEngine.class);

    private final TurnProcessor turnProcessor;
    private final int maxIterations;
    private final ToolProgressSink progressSink;
    private final boolean strict;

    ToolCallLoopEngine(
            TurnProcessor turnProcessor,
            int maxIterations,
            ToolProgressSink progressSink,
            boolean strict
    ) {
        this.turnProcessor = Objects.requireNonNull(turnProcessor, "turnProcessor");
        this.maxIterations = maxIterations;
        this.progressSink = progressSink;
        this.strict = strict;
    }

    ToolCallLoop.LoopResult run(
            String initialAnswer,
            List<NativeToolCall> nativeToolCalls,
            List<ChatMessage> messages,
            Path workspace,
            RuntimeTurnContext ctx
    ) {
        if (initialAnswer == null) initialAnswer = "";

        boolean hasNative = nativeToolCalls != null && !nativeToolCalls.isEmpty();
        boolean hasTextCalls = ToolCallParser.containsToolCalls(initialAnswer);
        if (!hasNative && !hasTextCalls) {
            if (CodeBlockToolExtractor.containsExtractableBlocks(initialAnswer)) {
                LOG.debug("Response contains code blocks with filename hints but no tool calls. "
                        + "File writes were NOT performed. The model should use tool_call format for file operations.");
            }
            return new ToolCallLoop.LoopResult(initialAnswer, 0, 0, List.of(), messages, 0, 0, false, 0,
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
                state.contentWithheldFromModelContext,
                state.userVisiblePrivacyNotices);

        LOG.debug("Tool-call loop complete: {} iterations, {} tools invoked, {} failed",
                state.iterations, state.totalToolsInvoked, state.failedCalls);

        int cushionFiresAliasRescue =
                turnProcessor.toolRegistry().aliasRescueCount() - state.aliasRescueBaseline;

        return new ToolCallLoop.LoopResult(finalAnswer, state.iterations, state.totalToolsInvoked,
                List.copyOf(state.toolNames), messages, state.failedCalls, state.retriedCalls,
                hitIterLimit, state.mutatingToolSuccesses, List.copyOf(state.pathsReadThisTurn),
                state.cushionFiresRedundantRead,
                cushionFiresAliasRescue, state.cushionFiresB3EditShortCircuit,
                state.cushionFiresE1Suggestion, state.failureDecision, List.copyOf(state.toolOutcomes),
                Map.copyOf(state.readFileBodiesThisTurn));
    }
}
