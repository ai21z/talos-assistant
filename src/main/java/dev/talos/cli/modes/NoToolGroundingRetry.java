package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.outcome.NoToolAnswerTruthfulnessGuard;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.safety.SafeLogFormatter;
import dev.talos.spi.types.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

final class NoToolGroundingRetry {
    private static final Logger LOG = LoggerFactory.getLogger(NoToolGroundingRetry.class);

    private NoToolGroundingRetry() {}

    @FunctionalInterface
    interface ChatFunction {
        LlmClient.StreamResult chat(List<ChatMessage> messages) throws Exception;
    }

    record Result(String answer, AnswerOutputLimit outputLimit) {}

    static String retryIfNeeded(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages,
            Context ctx,
            ChatFunction chat
    ) {
        return selectRetryIfNeeded(answer, plan, messages, ctx, chat).answer();
    }

    static Result selectRetryIfNeeded(String answer, CurrentTurnPlan plan, List<ChatMessage> messages,
                                     Context ctx, ChatFunction chat) {
        if (ctx == null || ctx.llm() == null || chat == null) return new Result(answer, AnswerOutputLimit.KEEP_ORIGINAL);
        if (!NoToolAnswerTruthfulnessGuard.shouldAppendStreamingGroundingAnnotation(answer, plan, messages))
            return new Result(answer, AnswerOutputLimit.KEEP_ORIGINAL);

        LOG.info("No-tool grounding retry fired: answer={} chars, zero tools, "
                + "user asked for evidence. Re-prompting once.", answer.length());

        messages.add(ChatMessage.assistant(answer));
        messages.add(ChatMessage.user(correctionPrompt()));

        LocalTurnTraceCapture.AnswerCandidateCheckpoint originalCandidate =
                LocalTurnTraceCapture.checkpointExactAnswerCandidate(answer);
        dev.talos.runtime.TurnExecutionStage priorStage =
                LocalTurnTraceCapture.currentExecutionStage();
        try {
            LlmClient.StreamResult retry = chat.chat(messages);
            String retryText = retry.text();
            if (retryText != null && !retryText.isBlank() && !retryText.equals(answer)) {
                LOG.info("Grounding retry produced a different answer ({} → {} chars)",
                        answer.length(), retryText.length());
                return new Result(retryText, AnswerOutputLimit.selected(retry.outputLimitReached()));
            }
            restoreOriginalCandidate(
                    originalCandidate,
                    answer,
                    retryText == null || retryText.isBlank()
                            ? LocalTurnTraceCapture.AnswerCandidateRestorationReason.SPECULATIVE_RETRY_EMPTY
                            : LocalTurnTraceCapture.AnswerCandidateRestorationReason.SPECULATIVE_RETRY_REJECTED);
            LOG.warn("Grounding retry did not produce a substantive new answer. "
                    + "Annotating original.");
        } catch (Exception e) {
            rethrowControlFlow(e);
            LocalTurnTraceCapture.enterExecutionStage(priorStage);
            restoreOriginalCandidate(
                    originalCandidate,
                    answer,
                    LocalTurnTraceCapture.AnswerCandidateRestorationReason.SPECULATIVE_RETRY_FAILED);
            LOG.warn("Grounding retry failed: {}. Annotating original.", SafeLogFormatter.throwableMessage(e));
        }
        String annotated = NoToolAnswerTruthfulnessGuard.UNGROUNDED_ANNOTATION + answer;
        LocalTurnTraceCapture.recordAnswerTransformedForRouting(
                annotated,
                LocalTurnTraceCapture.AnswerTransformationReason.EXECUTOR_POLICY_SHAPING);
        return new Result(annotated, AnswerOutputLimit.KEEP_ORIGINAL);
    }

    private static void rethrowControlFlow(Exception failure) {
        if (!dev.talos.runtime.TurnFailureClassifier.isCancellation(failure)
                && !dev.talos.runtime.TurnFailureClassifier.isInterruption(failure)) return;
        throw dev.talos.runtime.TurnFailureClassifier.propagateControlFlow(failure);
    }

    private static void restoreOriginalCandidate(
            LocalTurnTraceCapture.AnswerCandidateCheckpoint checkpoint,
            String answer,
            LocalTurnTraceCapture.AnswerCandidateRestorationReason reason) {
        LocalTurnTraceCapture.restoreExactAnswerCandidate(checkpoint, answer, reason);
    }

    static String correctionPrompt() {
        return "Your previous answer was produced without reading any files. "
                + "The user asked for an answer grounded in the actual workspace. "
                + "Use the available file tools to read the relevant files, then "
                + "answer concretely from what you read. Do not guess about file "
                + "contents. Do not describe files you have not read.";
    }

}
