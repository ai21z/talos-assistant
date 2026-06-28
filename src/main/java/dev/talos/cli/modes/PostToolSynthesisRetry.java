package dev.talos.cli.modes;

import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.toolcall.ToolCallSupport;
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
        if (toolsInvoked <= 0) return answer;
        if (!isDeflection(answer)) return answer;

        LOG.info("Post-tool deflection detected ({} tools used). Attempting synthesis retry.", toolsInvoked);

        String originalRequest = latestUserRequest(messages);
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

        messages.add(ChatMessage.assistant(answer));
        messages.add(ChatMessage.user(retryPrompt));

        try {
            LlmClient.StreamResult retry = chatFull.chat(messages);
            String retryText = retry.text();
            if (retryText != null && !retryText.isBlank() && !isDeflection(retryText)) {
                LOG.info("Synthesis retry produced substantive answer ({} chars)", retryText.length());
                return retryText;
            }
            LOG.warn("Synthesis retry still deflected. Returning original answer.");
        } catch (Exception e) {
            LOG.warn("Synthesis retry failed: {}", SafeLogFormatter.throwableMessage(e));
        }
        return answer;
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

    private static String latestUserRequest(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if ("user".equals(message.role())) {
                String content = message.content();
                if (ToolCallSupport.isSyntheticToolResultContent(content)) continue;
                return content == null || content.isBlank() ? null : content;
            }
        }
        return null;
    }
}
