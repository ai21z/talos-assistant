package dev.talos.runtime;

import dev.talos.core.util.Sanitize;
import dev.talos.core.util.UiChrome;
import dev.talos.runtime.policy.ProtectedContentPolicy;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ToolLoopFinalAnswerFinalizer {
    private static final String UNRESOLVED_CONTINUATION =
            "[Tool-call continuation could not be completed. No further tool calls were executed.]";
    private static final String ITERATION_LIMIT =
            UiChrome.TOOL_CALL_LIMIT_PREFIX + ". Some tool calls were not executed.]";

    private ToolLoopFinalAnswerFinalizer() {}

    static String withIterationLimitNotice(String currentText) {
        return ToolCallParser.stripToolCalls(currentText) + "\n\n" + ITERATION_LIMIT;
    }

    static String finalizeAnswer(String currentText, int toolsInvoked, boolean contentWithheldFromModelContext) {
        return finalizeAnswer(currentText, toolsInvoked, contentWithheldFromModelContext, List.of());
    }

    static String finalizeAnswer(
            String currentText,
            int toolsInvoked,
            boolean contentWithheldFromModelContext,
            List<String> userVisiblePrivacyNotices
    ) {
        String answer = shouldSuppressUnfinishedToolContinuation(currentText, toolsInvoked)
                ? unresolvedContinuationFallback()
                : Sanitize.stripSuspiciousHtml(ToolCallParser.stripToolCalls(currentText));
        String safeAnswer = contentWithheldFromModelContext
                ? ProtectedContentPolicy.sanitizeText(answer)
                : answer;
        return withRuntimePrivacyNotices(safeAnswer, userVisiblePrivacyNotices);
    }

    private static String withRuntimePrivacyNotices(String answer, List<String> notices) {
        String current = answer == null ? "" : answer;
        Set<String> uniqueNotices = sanitizedUniqueNotices(notices);
        if (uniqueNotices.isEmpty()) {
            return current;
        }
        StringBuilder prefix = new StringBuilder();
        for (String notice : uniqueNotices) {
            if (current.contains(notice)) {
                continue;
            }
            if (!prefix.isEmpty()) {
                prefix.append('\n');
            }
            prefix.append(notice);
        }
        if (prefix.isEmpty()) {
            return current;
        }
        if (current.isBlank()) {
            return prefix.toString();
        }
        return prefix + "\n\n" + current;
    }

    private static Set<String> sanitizedUniqueNotices(List<String> notices) {
        if (notices == null || notices.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String notice : notices) {
            String safe = ProtectedContentPolicy.sanitizeText(notice == null ? "" : notice).strip();
            if (!safe.isBlank()) {
                out.add(safe);
            }
        }
        return out;
    }

    static boolean shouldSuppressUnfinishedToolContinuation(String text, int toolsInvoked) {
        return toolsInvoked > 0 && ToolCallParser.looksLikeUnfinishedToolPayload(text);
    }

    static String unresolvedContinuationFallback() {
        return UNRESOLVED_CONTINUATION;
    }
}
