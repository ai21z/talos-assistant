package dev.talos.runtime;

import dev.talos.core.util.Sanitize;
import dev.talos.runtime.policy.ProtectedContentPolicy;

final class ToolLoopFinalAnswerFinalizer {
    private static final String UNRESOLVED_CONTINUATION =
            "[Tool-call continuation could not be completed. No further tool calls were executed.]";
    private static final String ITERATION_LIMIT =
            "[Tool-call limit reached. Some tool calls were not executed.]";

    private ToolLoopFinalAnswerFinalizer() {}

    static String withIterationLimitNotice(String currentText) {
        return ToolCallParser.stripToolCalls(currentText) + "\n\n" + ITERATION_LIMIT;
    }

    static String finalizeAnswer(String currentText, int toolsInvoked, boolean contentWithheldFromModelContext) {
        if (shouldSuppressUnfinishedToolContinuation(currentText, toolsInvoked)) {
            return unresolvedContinuationFallback();
        }
        String answer = Sanitize.stripSuspiciousHtml(ToolCallParser.stripToolCalls(currentText));
        return contentWithheldFromModelContext
                ? ProtectedContentPolicy.sanitizeText(answer)
                : answer;
    }

    static boolean shouldSuppressUnfinishedToolContinuation(String text, int toolsInvoked) {
        return toolsInvoked > 0 && ToolCallParser.looksLikeUnfinishedToolPayload(text);
    }

    static String unresolvedContinuationFallback() {
        return UNRESOLVED_CONTINUATION;
    }
}
