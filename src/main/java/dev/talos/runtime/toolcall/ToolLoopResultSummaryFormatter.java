package dev.talos.runtime.toolcall;

import dev.talos.core.util.UiChrome;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.tools.ToolFailureReason;

import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;

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
        // P21 (D18 option A): a call refused for its executable authority never ran, so it is shown as not run.
        // LA-F7: so is every call whose producer witnessed that it never reached the tool registry, such as a
        // refusal before approval or a denial.
        int notRunCalls = refusedBeforeExecution(result.toolOutcomes());
        int displayFailedCalls = Math.max(0, result.failedCalls() - notRunCalls);
        if (displayFailedCalls > 0) {
            base += " [" + displayFailedCalls + " failed]";
        }
        if (notRunCalls > 0) {
            base += " [" + notRunCalls + " not run]";
        }
        if (result.hitIterLimit()) {
            base += " " + UiChrome.ITERATION_LIMIT_PREFIX + " reached]";
        }
        if (result.failureDecision() != null && result.failureDecision().shouldStop()) {
            base += " [failure policy stopped]";
        }
        return base;
    }

    private static int refusedBeforeExecution(List<ToolCallLoop.ToolOutcome> outcomes) {
        if (outcomes == null) return 0;
        return (int) outcomes.stream()
                .filter(outcome -> outcome != null
                        && (outcome.dispatch().neverRan()
                        || outcome.failureReason() == ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID))
                .count();
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

    private static String normalizeDisplayPath(String path) {
        if (path == null || path.isBlank()) return "";
        String normalized = path.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

}
