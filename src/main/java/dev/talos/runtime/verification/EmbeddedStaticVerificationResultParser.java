package dev.talos.runtime.verification;

import java.util.ArrayList;
import java.util.List;

/** Parses already-rendered static verification failures back into verification state. */
public final class EmbeddedStaticVerificationResultParser {
    private static final String NOT_APPLICABLE_SUMMARY = "Post-apply verification was not applicable.";
    private static final String FAILURE_MARKER = "[Task incomplete: Static verification failed - ";
    private static final String PROBLEMS_MARKER = "Unresolved static verification problems:";

    private EmbeddedStaticVerificationResultParser() {}

    public static TaskVerificationResult parse(String answer) {
        if (answer == null || answer.isBlank()) {
            return TaskVerificationResult.notRun(NOT_APPLICABLE_SUMMARY);
        }
        int markerStart = answer.indexOf(FAILURE_MARKER);
        if (markerStart < 0) {
            return TaskVerificationResult.notRun(NOT_APPLICABLE_SUMMARY);
        }
        int summaryStart = markerStart + FAILURE_MARKER.length();
        int summaryEnd = answer.indexOf(']', summaryStart);
        if (summaryEnd < 0) {
            int lineEnd = answer.indexOf('\n', summaryStart);
            summaryEnd = lineEnd < 0 ? answer.length() : lineEnd;
        }
        String summary = answer.substring(summaryStart, Math.max(summaryStart, summaryEnd)).strip();
        if (summary.isBlank()) summary = "Static verification failed.";

        List<String> problems = problems(answer);
        if (problems.isEmpty()) {
            problems = List.of(summary);
        }
        return TaskVerificationResult.failed(summary, List.of(), problems);
    }

    private static List<String> problems(String answer) {
        int start = answer.indexOf(PROBLEMS_MARKER);
        if (start < 0) return List.of();
        String tail = answer.substring(start + PROBLEMS_MARKER.length());
        List<String> problems = new ArrayList<>();
        boolean started = false;
        for (String line : tail.split("\\R")) {
            String trimmed = line == null ? "" : line.strip();
            if (trimmed.startsWith("- ")) {
                started = true;
                String problem = trimmed.substring(2).strip();
                if (!problem.isBlank()) problems.add(problem);
            } else if (started && !trimmed.isBlank()) {
                break;
            }
        }
        return List.copyOf(problems);
    }
}
