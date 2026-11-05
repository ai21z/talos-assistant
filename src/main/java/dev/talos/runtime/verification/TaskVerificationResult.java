package dev.talos.runtime.verification;

import java.util.List;

/**
 * Result of a bounded static verification pass over the post-apply workspace.
 *
 * <p>A pass states the scope of its proof (MICA invariant I3, decision D5). Only a pass whose checks were derived
 * from the request is request-bound, and only that pass can complete a task as verified.
 *
 * <p>{@code replacementNotAsserted} marks a result whose request named a replacement that the evidence could neither
 * prove nor refute (P17), so its rendering does not claim that no task-specific verifier applied.
 *
 * <p>{@code exactEditNotAsserted} marks a result in which an exact edit could not be confirmed in the final file after
 * a later change of the same file in the turn and was therefore not asserted (P19), with the same effect on its
 * rendering.
 */
public record TaskVerificationResult(
        TaskVerificationStatus status,
        String summary,
        List<String> facts,
        List<String> problems,
        ProofScope proofScope,
        boolean replacementNotAsserted,
        boolean exactEditNotAsserted
) {
    /** What a pass proves. */
    public enum ProofScope {
        /** Not a pass. */
        NONE,
        /** Structural or effect checks that do not bind the request, such as static web coherence. */
        CHECKS,
        /** Checks derived from the request, such as its exact content or its required claims. */
        REQUEST_BOUND
    }

    public TaskVerificationResult {
        if (status == null) status = TaskVerificationStatus.NOT_RUN;
        summary = summary == null ? "" : summary.strip();
        facts = facts == null ? List.of() : List.copyOf(facts);
        problems = problems == null ? List.of() : List.copyOf(problems);
        proofScope = status != TaskVerificationStatus.PASSED ? ProofScope.NONE
                : proofScope == ProofScope.REQUEST_BOUND ? ProofScope.REQUEST_BOUND : ProofScope.CHECKS;
    }

    public TaskVerificationResult(
            TaskVerificationStatus status, String summary, List<String> facts, List<String> problems) {
        this(status, summary, facts, problems, ProofScope.NONE);
    }

    public TaskVerificationResult(
            TaskVerificationStatus status,
            String summary,
            List<String> facts,
            List<String> problems,
            ProofScope proofScope
    ) {
        this(status, summary, facts, problems, proofScope, false);
    }

    public TaskVerificationResult(
            TaskVerificationStatus status,
            String summary,
            List<String> facts,
            List<String> problems,
            ProofScope proofScope,
            boolean replacementNotAsserted
    ) {
        this(status, summary, facts, problems, proofScope, replacementNotAsserted, false);
    }

    public static TaskVerificationResult notRun(String summary) {
        return new TaskVerificationResult(TaskVerificationStatus.NOT_RUN, summary, List.of(), List.of());
    }

    /** A pass of checks that do not bind the request. */
    public static TaskVerificationResult passed(String summary, List<String> facts) {
        return new TaskVerificationResult(TaskVerificationStatus.PASSED, summary, facts, List.of(), ProofScope.CHECKS);
    }

    /** A pass of checks derived from the request. */
    public static TaskVerificationResult passedForRequest(String summary, List<String> facts) {
        return new TaskVerificationResult(
                TaskVerificationStatus.PASSED, summary, facts, List.of(), ProofScope.REQUEST_BOUND);
    }

    public boolean requestBound() {
        return proofScope == ProofScope.REQUEST_BOUND;
    }

    public static TaskVerificationResult readbackOnly(String summary, List<String> facts) {
        return new TaskVerificationResult(TaskVerificationStatus.READBACK_ONLY, summary, facts, List.of());
    }

    public static TaskVerificationResult failed(String summary, List<String> facts, List<String> problems) {
        return new TaskVerificationResult(TaskVerificationStatus.FAILED, summary, facts, problems);
    }

    public static TaskVerificationResult unavailable(String summary, List<String> facts, List<String> problems) {
        return new TaskVerificationResult(TaskVerificationStatus.UNAVAILABLE, summary, facts, problems);
    }
}
