package dev.talos.runtime.expectation;

import java.util.List;

/** Redaction-safe verification result for a resolved task expectation. */
public record ExpectationVerificationResult(
        TaskExpectation expectation,
        ExpectationVerificationStatus status,
        String summary,
        List<String> facts,
        List<String> problems
) {
    public ExpectationVerificationResult {
        status = status == null ? ExpectationVerificationStatus.FAILED : status;
        summary = summary == null ? "" : summary.strip();
        facts = facts == null ? List.of() : List.copyOf(facts);
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    public static ExpectationVerificationResult passed(TaskExpectation expectation, String summary, List<String> facts) {
        return new ExpectationVerificationResult(
                expectation,
                ExpectationVerificationStatus.PASSED,
                summary,
                facts,
                List.of());
    }

    public static ExpectationVerificationResult failed(
            TaskExpectation expectation,
            String summary,
            List<String> problems
    ) {
        return new ExpectationVerificationResult(
                expectation,
                ExpectationVerificationStatus.FAILED,
                summary,
                List.of(),
                problems);
    }
}
