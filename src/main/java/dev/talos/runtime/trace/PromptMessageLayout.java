package dev.talos.runtime.trace;

import dev.talos.spi.types.ChatMessage;

import java.util.List;

/** Compact, redaction-safe summary of the prompt message layout. */
public record PromptMessageLayout(
        int systemMessageCount,
        int historyMessageCount,
        int userMessageCount,
        int totalMessageCount,
        String historyPolicy,
        boolean currentTurnFrameInjected,
        String currentTurnFramePlacement,
        String currentTurnFrameHash,
        String currentTurnFramePreviewRedacted,
        String promptHash
) {
    public PromptMessageLayout {
        historyPolicy = safe(historyPolicy);
        currentTurnFramePlacement = safe(currentTurnFramePlacement);
        currentTurnFrameHash = safe(currentTurnFrameHash);
        currentTurnFramePreviewRedacted = safe(currentTurnFramePreviewRedacted);
        promptHash = safe(promptHash);
    }

    static PromptMessageLayout fromMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return new PromptMessageLayout(
                    0, 0, 0, 0,
                    "NOT_DERIVED",
                    false,
                    "UNKNOWN",
                    "",
                    "",
                    PromptAuditRedactor.hash(""));
        }

        int systemCount = 0;
        int userCount = 0;
        int currentUserIndex = -1;
        int frameIndex = -1;
        String frame = "";
        StringBuilder promptDigest = new StringBuilder();

        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            String role = message == null ? "" : safe(message.role());
            String content = message == null ? "" : safe(message.content());
            promptDigest.append(role).append(':').append(content).append('\n');
            if ("system".equals(role)) {
                systemCount++;
                if (frameIndex < 0 && isCurrentTurnFrame(content)) {
                    frameIndex = i;
                    frame = content;
                }
            }
            if ("user".equals(role)) {
                userCount++;
                currentUserIndex = i;
            }
        }

        int historyCount = 0;
        boolean compactedHistoryIncluded = false;
        if (currentUserIndex > 0) {
            for (int i = 0; i < currentUserIndex; i++) {
                ChatMessage message = messages.get(i);
                String role = message == null ? "" : safe(message.role());
                if ("user".equals(role) || "assistant".equals(role)) {
                    historyCount++;
                    if ("assistant".equals(role) && isConversationContext(message.content())) {
                        compactedHistoryIncluded = true;
                    }
                }
            }
        }

        boolean injected = frameIndex >= 0;
        String placement = placement(frameIndex, currentUserIndex, historyCount, messages);
        String historyPolicy = historyPolicy(historyCount, compactedHistoryIncluded);
        return new PromptMessageLayout(
                systemCount,
                historyCount,
                userCount,
                messages.size(),
                historyPolicy,
                injected,
                placement,
                injected ? PromptAuditRedactor.hash(frame) : "",
                injected ? PromptAuditRedactor.preview(frame) : "",
                PromptAuditRedactor.hash(promptDigest.toString()));
    }

    private static String placement(
            int frameIndex,
            int currentUserIndex,
            int historyCount,
            List<ChatMessage> messages
    ) {
        if (frameIndex < 0 || currentUserIndex < 0) return "UNKNOWN";
        if (frameIndex > currentUserIndex) return "AFTER_USER";
        if (historyCount == 0 && frameIndex < currentUserIndex) {
            return "AFTER_HISTORY_BEFORE_USER";
        }

        int lastHistoryIndex = -1;
        for (int i = 0; i < currentUserIndex; i++) {
            ChatMessage message = messages.get(i);
            String role = message == null ? "" : safe(message.role());
            if ("user".equals(role) || "assistant".equals(role)) {
                lastHistoryIndex = i;
            }
        }
        if (frameIndex > lastHistoryIndex && frameIndex < currentUserIndex) {
            return "AFTER_HISTORY_BEFORE_USER";
        }
        if (frameIndex < lastHistoryIndex) return "BEFORE_HISTORY";
        return "UNKNOWN";
    }

    private static boolean isCurrentTurnFrame(String content) {
        return content != null
                && (content.startsWith("[CurrentTurnCapability]")
                || content.startsWith("[TaskContract]"));
    }

    private static boolean isConversationContext(String content) {
        return content != null && content.startsWith("[Conversation context]");
    }

    private static String historyPolicy(int historyCount, boolean compactedHistoryIncluded) {
        if (historyCount <= 0) return "SUPPRESSED";
        return compactedHistoryIncluded ? "INCLUDED_COMPACTED" : "INCLUDED";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
