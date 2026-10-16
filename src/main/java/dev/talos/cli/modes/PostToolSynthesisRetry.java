package dev.talos.cli.modes;

import dev.talos.runtime.policy.RuntimeModelIdentityAnswerPolicy;
import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.safety.SafeLogFormatter;
import dev.talos.spi.types.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/** One-shot synthesis retry for post-tool deflection answers. */
final class PostToolSynthesisRetry {
    private static final Logger LOG = LoggerFactory.getLogger(PostToolSynthesisRetry.class);

    /** Short phrases that indicate the model deflected instead of answering. */
    private static final Set<String> DEFLECTION_MARKERS = Set.of(
            "how can i help",
            "how can i assist",
            "what would you like",
            "what do you want me to",
            "let me know if you",
            "is there anything",
            "would you like me to",
            "what can i do for you",
            "feel free to ask"
    );

    /**
     * Phrases that indicate a capability-recitation non-answer instead of an
     * answer to the current question.
     */
    private static final Set<String> CAPABILITY_MARKERS = Set.of(
            "here is what i can do",
            "here's what i can do",
            "i can help you with",
            "i am able to",
            "i'm able to",
            "my capabilities include",
            "i have the following capabilities",
            "i can perform the following",
            "i can do the following"
    );

    private PostToolSynthesisRetry() {}

    @FunctionalInterface
    interface ChatFunction {
        LlmClient.StreamResult chat(List<ChatMessage> messages) throws Exception;
    }

    record Result(String answer, AnswerOutputLimit outputLimit) {}

    /**
     * If tools were used and the answer is a deflection, re-prompts the model
     * once with an instruction to synthesize from already gathered evidence.
     */
    static String synthesizeIfNeeded(
            String answer,
            int toolsInvoked,
            List<ChatMessage> messages,
            ChatFunction chatFull
    ) {
        return selectSynthesisIfNeeded(answer, toolsInvoked, messages, chatFull).answer();
    }

    static Result selectSynthesisIfNeeded(
            String answer, int toolsInvoked, List<ChatMessage> messages, ChatFunction chatFull) {
        if (toolsInvoked <= 0) return new Result(answer, AnswerOutputLimit.KEEP_ORIGINAL);
        if (!isDeflection(answer)) return new Result(answer, AnswerOutputLimit.KEEP_ORIGINAL);

        LOG.info("Post-tool deflection detected ({} tools used). Attempting synthesis retry.", toolsInvoked);

        String originalRequest =
                RuntimeModelIdentityAnswerPolicy.capturedProviderTask(
                        TurnTaskContractCapture.originalUserRequestOrResolve(messages));
        String retryPrompt;
        if (originalRequest != null && !originalRequest.isBlank()) {
            String pinned = originalRequest.length() <= 2000
                    ? originalRequest
                    : originalRequest.substring(0, 2000) + "…";
            retryPrompt = "The user's original request was:\n\n«" + pinned + "»\n\n"
                    + "You already gathered the needed evidence using tools. "
                    + "Now answer that exact request directly and concretely, "
                    + "using the tool results you received. "
                    + "Do not say the question is missing. "
                    + "Do not ask what I want - answer the question above.";
        } else {
            retryPrompt = "You already gathered the needed evidence using tools. "
                    + "Now answer the original question directly and concretely, "
                    + "using the tool results you received. "
                    + "Do not ask what I want - answer the question.";
        }

        List<ChatMessage> retryMessages =
                new java.util.ArrayList<>(messages);
        retryMessages.add(ChatMessage.assistant(answer));
        retryMessages.add(ChatMessage.user(retryPrompt));
        LocalTurnTraceCapture.AnswerCandidateCheckpoint checkpoint =
                LocalTurnTraceCapture.checkpointExactAnswerCandidate(answer);
        dev.talos.runtime.TurnExecutionStage priorStage =
                LocalTurnTraceCapture.currentExecutionStage();

        try {
            LlmClient.StreamResult retry = chatFull.chat(
                    List.copyOf(retryMessages));
            String retryText = retry.text();
            if (retryText != null && !retryText.isBlank() && !isDeflection(retryText)) {
                LOG.info("Synthesis retry produced substantive answer ({} chars)", retryText.length());
                return new Result(retryText, AnswerOutputLimit.selected(retry.outputLimitReached()));
            }
            boolean empty = retryText == null || retryText.isBlank();
            LocalTurnTraceCapture.restoreExactAnswerCandidate(
                    checkpoint,
                    answer,
                    empty
                            ? LocalTurnTraceCapture.AnswerCandidateRestorationReason
                                    .SPECULATIVE_RETRY_EMPTY
                            : LocalTurnTraceCapture.AnswerCandidateRestorationReason
                                    .SPECULATIVE_RETRY_REJECTED);
            LOG.warn(empty
                    ? "Synthesis retry was empty. Returning original answer."
                    : "Synthesis retry still deflected. Returning original answer.");
        } catch (Exception e) {
            rethrowControlFlow(e);
            LocalTurnTraceCapture.enterExecutionStage(priorStage);
            LocalTurnTraceCapture.restoreExactAnswerCandidate(
                    checkpoint,
                    answer,
                    LocalTurnTraceCapture.AnswerCandidateRestorationReason
                            .SPECULATIVE_RETRY_FAILED);
            LOG.warn("Synthesis retry failed: {}", SafeLogFormatter.throwableMessage(e));
        }
        return new Result(answer, AnswerOutputLimit.KEEP_ORIGINAL);
    }

    private static void rethrowControlFlow(Exception failure) {
        if (!dev.talos.runtime.TurnFailureClassifier.isCancellation(failure)
                && !dev.talos.runtime.TurnFailureClassifier.isInterruption(failure)) return;
        throw dev.talos.runtime.TurnFailureClassifier.propagateControlFlow(failure);
    }

    /**
     * Detects whether the model's answer is generic assistant boilerplate
     * instead of a substantive response to the user's request.
     */
    static boolean isDeflection(String answer) {
        if (answer == null || answer.isBlank()) return true;
        String lower = answer.toLowerCase();

        if (answer.length() <= 500) {
            for (String marker : DEFLECTION_MARKERS) {
                if (lower.contains(marker)) return true;
            }
            return false;
        }

        if (answer.length() <= 1500) {
            boolean hasCapability = false;
            for (String marker : CAPABILITY_MARKERS) {
                if (lower.contains(marker)) {
                    hasCapability = true;
                    break;
                }
            }
            if (hasCapability) {
                String tail = lower.substring(Math.max(0, lower.length() - 200));
                for (String marker : DEFLECTION_MARKERS) {
                    if (tail.contains(marker)) return true;
                }
            }
        }

        return false;
    }

}
