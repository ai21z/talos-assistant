package dev.talos.runtime.toolcall;

import dev.talos.core.util.UiChrome;
import dev.talos.runtime.ToolCallLoop;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Formats the public tool-loop telemetry summary exposed by {@code LoopResult.summary()}. */
public final class ToolLoopResultSummaryFormatter {
    private ToolLoopResultSummaryFormatter() {}

    public static String format(ToolCallLoop.LoopResult result) {
        if (result == null || result.toolsInvoked() <= 0) return null;
        var unique = new LinkedHashSet<>(result.toolNames() != null ? result.toolNames() : List.of());
        String names = unique.isEmpty() ? "" : ": " + String.join(", ", unique);
        String base = UiChrome.TOOL_SUMMARY_OPEN + result.toolsInvoked() + " " + UiChrome.TOOL_SUMMARY_MARKER
                + names + " | " + result.iterations() + " iteration(s)]";
        int displayFailedCalls = displayFailedCalls(result.failedCalls(), result.toolOutcomes());
        if (displayFailedCalls > 0) {
            base += " [" + displayFailedCalls + " failed]";
        }
        if (result.hitIterLimit()) {
            base += " " + UiChrome.ITERATION_LIMIT_PREFIX + " reached]";
        }
        if (result.failureDecision() != null && result.failureDecision().shouldStop()) {
            base += " [failure policy stopped]";
        }
        return base;
    }

    private static int displayFailedCalls(int failedCalls, List<ToolCallLoop.ToolOutcome> toolOutcomes) {
        if (failedCalls <= 0 || toolOutcomes == null || toolOutcomes.isEmpty()) {
            return Math.max(0, failedCalls);
        }
        int recovered = 0;
        for (int i = 0; i < toolOutcomes.size(); i++) {
            ToolCallLoop.ToolOutcome failure = toolOutcomes.get(i);
            if (!isRecoveredEditFailureShape(failure)) continue;
            String failedPath = normalizeSummaryPath(failure.pathHint());
            if (failedPath.isBlank()) continue;
            for (int j = i + 1; j < toolOutcomes.size(); j++) {
                ToolCallLoop.ToolOutcome later = toolOutcomes.get(j);
                if (later != null
                        && later.mutating()
                        && later.success()
                        && failedPath.equals(normalizeSummaryPath(later.pathHint()))) {
                    recovered++;
                    break;
                }
            }
        }
        return Math.max(0, failedCalls - recovered);
    }

    private static boolean isRecoveredEditFailureShape(ToolCallLoop.ToolOutcome outcome) {
        return outcome != null
                && (outcome.invalidEmptyEditArguments()
                || outcome.fullRewriteRepairRedirect()
                || outcome.oldStringNotFoundEditFailure());
    }

    private static String normalizeSummaryPath(String path) {
        if (path == null || path.isBlank()) return "";
        return path.replace('\\', '/').replaceFirst("^\\./+", "").toLowerCase(Locale.ROOT);
    }
}
