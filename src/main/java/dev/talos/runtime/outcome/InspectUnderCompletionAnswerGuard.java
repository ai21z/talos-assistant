package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.spi.types.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure final-answer guard for turns that answered after too little requested
 * workspace inspection.
 */
public final class InspectUnderCompletionAnswerGuard {
    private static final Logger LOG = LoggerFactory.getLogger(InspectUnderCompletionAnswerGuard.class);

    private InspectUnderCompletionAnswerGuard() {}

    /**
     * Minimum answer length at which the inspect under-completion gate becomes
     * eligible.
     */
    public static final int INSPECT_MIN_CHARS = 500;

    /**
     * Annotation prepended when the user requested multi-file inspection but
     * the tool evidence shows at most one read-only tool invocation.
     */
    public static final String UNDER_INSPECTION_ANNOTATION =
            "[Inspect check: the user asked for multiple files to be read "
            + "before answering, but only one read-only tool call was made "
            + "this turn. The response below may not reflect the full "
            + "workspace contents.]\n\n";

    private static final Set<String> INSPECT_REQUEST_MARKERS = Set.of(
            "entry file",
            "entry files",
            "read the relevant",
            "read the main",
            "read the files",
            "read all the",
            "read all ",
            "read each",
            "read them all",
            "read both",
            "read these",
            "all three",
            "look at each",
            "look at all",
            "inspect each",
            "inspect all",
            "open each",
            "start by reading",
            "first read",
            "first, read"
    );

    /**
     * True iff the latest user request contains an inspect-first marker
     * indicating plural-file inspection.
     */
    public static boolean looksLikeInspectFirstRequest(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT);
        for (String marker : INSPECT_REQUEST_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    /**
     * Counts successful-or-attempted read-only tool invocations in
     * {@code loopResult.toolNames()}.
     */
    public static int readOnlyToolCount(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null || loopResult.toolNames() == null) return 0;
        int count = 0;
        for (String toolName : loopResult.toolNames()) {
            if (toolName == null) continue;
            String name = toolName.toLowerCase(Locale.ROOT);
            if (name.startsWith("talos.")) name = name.substring("talos.".length());
            if (name.equals("read_file") || name.equals("list_dir") || name.equals("grep")) {
                count++;
            }
        }
        return count;
    }

    /**
     * Annotates a substantive answer when the turn completed after the user
     * requested multi-file inspection but the loop evidence shows at most one
     * read-only tool invocation.
     */
    public static String annotateIfInspectUnderCompletion(
            String answer,
            List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult) {
        if (answer == null || answer.isBlank()) return answer;
        if (loopResult == null) return answer;
        if (loopResult.toolsInvoked() == 0) return answer;
        if (loopResult.mutatingToolSuccesses() > 0) return answer;
        if (answer.length() < INSPECT_MIN_CHARS) return answer;
        int readOnlyToolCount = readOnlyToolCount(loopResult);
        if (readOnlyToolCount > 1) return answer;
        if (!looksLikeInspectFirstRequest(latestUserRequest(messages))) return answer;

        LOG.warn("Inspect under-completion detected: answer={} chars, "
                + "read-only tool calls={}, tools invoked={}, "
                + "user asked for multi-file inspection. Annotating.",
                answer.length(), readOnlyToolCount, loopResult.toolsInvoked());
        return UNDER_INSPECTION_ANNOTATION + answer;
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
