package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.outcome.NoToolAnswerTruthfulnessGuard;
import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.toolcall.ToolCallSupport;
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

    static String retryIfNeeded(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages,
            Context ctx,
            ChatFunction chat
    ) {
        if (answer == null || answer.isBlank()) return answer;
        if (answer.length() < NoToolAnswerTruthfulnessGuard.UNGROUNDED_MIN_CHARS) return answer;
        if (ctx == null || ctx.llm() == null || chat == null) return answer;
        if (isDirectAnswerOnlyTurn(plan)) return answer;

        String userRequest = latestUserRequest(plan, messages);
        if (!NoToolAnswerTruthfulnessGuard.looksLikeEvidenceRequest(userRequest)) return answer;

        LOG.info("No-tool grounding retry fired: answer={} chars, zero tools, "
                + "user asked for evidence. Re-prompting once.", answer.length());

        messages.add(ChatMessage.assistant(answer));
        messages.add(ChatMessage.user(correctionPrompt()));

        try {
            LlmClient.StreamResult retry = chat.chat(messages);
            String retryText = retry.text();
            if (retryText != null && !retryText.isBlank() && !retryText.equals(answer)) {
                LOG.info("Grounding retry produced a different answer ({} → {} chars)",
                        answer.length(), retryText.length());
                return retryText;
            }
            LOG.warn("Grounding retry did not produce a substantive new answer. "
                    + "Annotating original.");
        } catch (Exception e) {
            LOG.warn("Grounding retry failed: {}. Annotating original.", SafeLogFormatter.throwableMessage(e));
        }
        return NoToolAnswerTruthfulnessGuard.UNGROUNDED_ANNOTATION + answer;
    }

    static String correctionPrompt() {
        return "Your previous answer was produced without reading any files. "
                + "The user asked for an answer grounded in the actual workspace. "
                + "Use the available file tools to read the relevant files, then "
                + "answer concretely from what you read. Do not guess about file "
                + "contents. Do not describe files you have not read.";
    }

    private static String latestUserRequest(CurrentTurnPlan plan, List<ChatMessage> messages) {
        if (plan != null
                && plan.originalUserRequest() != null
                && !plan.originalUserRequest().isBlank()) {
            return plan.originalUserRequest();
        }
        if (messages == null || messages.isEmpty()) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message == null || !"user".equals(message.role())) continue;
            String content = message.content();
            if (ToolCallSupport.isSyntheticToolResultContent(content)) continue;
            return content == null || content.isBlank() ? null : content;
        }
        return null;
    }

    private static boolean isDirectAnswerOnlyTurn(CurrentTurnPlan plan) {
        if (plan == null) return false;
        return plan.actionObligation() == ActionObligation.DIRECT_ANSWER_ONLY
                || plan.taskContract().type() == TaskType.SMALL_TALK;
    }
}
