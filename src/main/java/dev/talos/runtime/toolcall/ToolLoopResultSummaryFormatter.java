package dev.talos.runtime.toolcall;

import dev.talos.core.util.UiChrome;
import dev.talos.runtime.ToolCallLoop;

import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Formats the public tool-loop telemetry summary exposed by {@code LoopResult.summary()}. */
public final class ToolLoopResultSummaryFormatter {
    private static final int MAX_READ_PATHS = 3;

    private ToolLoopResultSummaryFormatter() {}

    public record GroundingDisclosure(String workspaceCandidateNote) {
        public GroundingDisclosure {
            workspaceCandidateNote = workspaceCandidateNote == null ? "" : workspaceCandidateNote.strip();
        }

        public static GroundingDisclosure none() {
            return new GroundingDisclosure("");
        }
    }

    public static String format(ToolCallLoop.LoopResult result) {
        return format(result, GroundingDisclosure.none());
    }

    public static String format(ToolCallLoop.LoopResult result, GroundingDisclosure disclosure) {
        if (result == null || result.toolsInvoked() <= 0) return null;
        var unique = new LinkedHashSet<>(result.toolNames() != null ? result.toolNames() : List.of());
        String names = unique.isEmpty() ? "" : ": " + String.join(", ", unique);
        String base = UiChrome.TOOL_SUMMARY_OPEN + result.toolsInvoked() + " " + UiChrome.TOOL_SUMMARY_MARKER
                + names + " | " + result.iterations() + " iteration(s)"
                + readSetSuffix(result)
                + workspaceCandidateSuffix(disclosure)
                + "]";
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

    private static String readSetSuffix(ToolCallLoop.LoopResult result) {
        if (result == null || hasMutatingSuccess(result)) return "";
        List<String> paths = successfulReadFilePaths(result.toolOutcomes());
        if (paths.isEmpty()) return "";
        return UiChrome.TOOL_SUMMARY_READ_PREFIX + boundedPathList(paths);
    }

    private static String workspaceCandidateSuffix(GroundingDisclosure disclosure) {
        if (disclosure == null || disclosure.workspaceCandidateNote().isBlank()) return "";
        return " | " + disclosure.workspaceCandidateNote();
    }

    private static boolean hasMutatingSuccess(ToolCallLoop.LoopResult result) {
        if (result.mutatingToolSuccesses() > 0) return true;
        if (result.toolOutcomes() == null) return false;
        return result.toolOutcomes().stream()
                .anyMatch(outcome -> outcome != null && outcome.success() && outcome.mutating());
    }

    private static List<String> successfulReadFilePaths(List<ToolCallLoop.ToolOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) return List.of();
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (ToolCallLoop.ToolOutcome outcome : outcomes) {
            if (outcome == null || !outcome.success()) continue;
            if (!"talos.read_file".equals(outcome.canonicalToolName())) continue;
            String path = normalizeDisplayPath(outcome.pathHint());
            if (!path.isBlank()) paths.add(path);
        }
        List<String> sorted = new ArrayList<>(paths);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(sorted);
    }

    private static String boundedPathList(List<String> paths) {
        if (paths == null || paths.isEmpty()) return "";
        int shown = Math.min(MAX_READ_PATHS, paths.size());
        List<String> visible = paths.subList(0, shown);
        String joined = String.join(", ", visible);
        int remaining = paths.size() - shown;
        if (remaining > 0) {
            joined += ", and " + remaining + " more";
        }
        return joined;
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

    private static String normalizeDisplayPath(String path) {
        if (path == null || path.isBlank()) return "";
        String normalized = path.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static String normalizeSummaryPath(String path) {
        if (path == null || path.isBlank()) return "";
        return path.replace('\\', '/').replaceFirst("^\\./+", "").toLowerCase(Locale.ROOT);
    }
}
