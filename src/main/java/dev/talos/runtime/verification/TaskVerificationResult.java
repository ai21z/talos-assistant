package dev.talos.runtime.verification;

import java.util.List;

/** Result of a bounded static verification pass over the post-apply workspace. */
public record TaskVerificationResult(
        TaskVerificationStatus status,
        String summary,
        List<String> facts,
        List<String> problems
) {
    public TaskVerificationResult {
        if (status == null) status = TaskVerificationStatus.NOT_RUN;
        summary = summary == null ? "" : summary.strip();
        facts = facts == null ? List.of() : List.copyOf(facts);
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    public static TaskVerificationResult notRun(String summary) {
        return new TaskVerificationResult(TaskVerificationStatus.NOT_RUN, summary, List.of(), List.of());
    }

    public static TaskVerificationResult passed(String summary, List<String> facts) {
        return new TaskVerificationResult(TaskVerificationStatus.PASSED, summary, facts, List.of());
    }

    public static TaskVerificationResult failed(String summary, List<String> facts, List<String> problems) {
        return new TaskVerificationResult(TaskVerificationStatus.FAILED, summary, facts, problems);
    }

    public static TaskVerificationResult unavailable(String summary, List<String> facts, List<String> problems) {
        return new TaskVerificationResult(TaskVerificationStatus.UNAVAILABLE, summary, facts, problems);
    }
}
