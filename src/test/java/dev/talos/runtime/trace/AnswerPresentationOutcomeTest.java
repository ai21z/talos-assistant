package dev.talos.runtime.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.talos.runtime.trace.LocalTurnTraceCapture.AnswerPresentationLimitation.OUTPUT_TRUNCATED;
import static dev.talos.runtime.trace.LocalTurnTraceCapture.AnswerPresentationLimitation.RUNTIME_IDENTITY_INCOMPLETE;
import static org.junit.jupiter.api.Assertions.*;

/** Constructed trace outcomes isolate presentation reconciliation from executor integration tests. */
class AnswerPresentationOutcomeTest {
    private record Outcome(String status, String classification, String verification, String approval, String mutation) {}

    private static final Outcome READ_COMPLETE = new Outcome(
            "COMPLETE", "READ_ONLY_ANSWERED", "NOT_RUN", "NOT_REQUIRED", "NOT_REQUESTED");
    private static final Outcome READ_LIMITED = new Outcome(
            "ADVISORY_ONLY", "ADVISORY_ONLY", "NOT_RUN", "NOT_REQUIRED", "NOT_REQUESTED");

    @AfterEach void clearTrace() {
        LocalTurnTraceCapture.clear();
    }

    @Test void eitherTypedLimitationDowngradesReadCompletionBeforeOrAfterEvaluation() {
        for (var reason : List.of(RUNTIME_IDENTITY_INCOMPLETE, OUTPUT_TRUNCATED)) {
            for (boolean beforeEvaluation : List.of(false, true)) {
                beginTrace();
                if (beforeEvaluation) LocalTurnTraceCapture.recordAnswerPresentationLimitation(reason);
                record(READ_COMPLETE);
                if (!beforeEvaluation) LocalTurnTraceCapture.recordAnswerPresentationLimitation(reason);
                assertOutcome(routeAndComplete("An incomplete answer."), READ_LIMITED);
            }
        }
    }

    @Test void pendingLimitationsSurviveRepeatedCompleteEvaluations() {
        beginTrace();
        record(READ_COMPLETE);
        LocalTurnTraceCapture.recordAnswerPresentationLimitation(RUNTIME_IDENTITY_INCOMPLETE);
        record(READ_COMPLETE);
        LocalTurnTraceCapture.recordAnswerPresentationLimitation(OUTPUT_TRUNCATED);
        record(READ_COMPLETE);
        assertOutcome(routeAndComplete("An incomplete composed answer."), READ_LIMITED);
    }

    @Test void successfulMutationBecomesPartialWithoutErasingItsExecutionOrVerificationEvidence() {
        for (var reason : List.of(RUNTIME_IDENTITY_INCOMPLETE, OUTPUT_TRUNCATED)) {
            for (String classification : List.of("COMPLETED_VERIFIED", "COMPLETED_UNVERIFIED")) {
                for (boolean beforeEvaluation : List.of(false, true)) {
                    beginTrace();
                    String verification = "COMPLETED_VERIFIED".equals(classification) ? "PASSED" : "NOT_RUN";
                    var complete = new Outcome("COMPLETE", classification, verification, "GRANTED", "SUCCEEDED");
                    if (beforeEvaluation) LocalTurnTraceCapture.recordAnswerPresentationLimitation(reason);
                    record(complete);
                    if (!beforeEvaluation) LocalTurnTraceCapture.recordAnswerPresentationLimitation(reason);
                    record(complete);
                    assertOutcome(routeAndComplete("The mutation summary was cut short."),
                            new Outcome("PARTIAL", "PARTIAL", verification, "GRANTED", "SUCCEEDED"));
                }
            }
        }
    }

    @Test void limitationsPreserveFailuresDenialsPartialUnknownAndUnfamiliarOutcomes() {
        var outcomes = List.of(
                new Outcome("FAILED", "FAILED", "FAILED", "NOT_REQUIRED", "FAILED"),
                new Outcome("BLOCKED", "BLOCKED_BY_APPROVAL", "NOT_RUN", "DENIED", "DENIED"),
                new Outcome("BLOCKED", "BLOCKED_BY_POLICY", "NOT_RUN", "NOT_REQUIRED", "DENIED"),
                new Outcome("PARTIAL", "PARTIAL", "FAILED", "GRANTED", "PARTIAL"),
                new Outcome("UNKNOWN", "OUTCOME_NOT_EVALUATED", "NOT_RUN", "UNKNOWN", "UNKNOWN"),
                new Outcome("ADVISORY_ONLY", "ADVISORY_ONLY", "NOT_RUN", "NOT_REQUIRED", "NOT_REQUESTED"),
                new Outcome("COMPLETE", "FUTURE_CLASSIFICATION", "NOT_RUN", "UNKNOWN", "UNKNOWN"));
        for (var expected : outcomes) {
            for (boolean beforeEvaluation : List.of(false, true)) {
                beginTrace();
                if (beforeEvaluation) {
                    LocalTurnTraceCapture.recordAnswerPresentationLimitation(RUNTIME_IDENTITY_INCOMPLETE);
                    LocalTurnTraceCapture.recordAnswerPresentationLimitation(OUTPUT_TRUNCATED);
                }
                record(expected);
                if (!beforeEvaluation) {
                    LocalTurnTraceCapture.recordAnswerPresentationLimitation(RUNTIME_IDENTITY_INCOMPLETE);
                    LocalTurnTraceCapture.recordAnswerPresentationLimitation(OUTPUT_TRUNCATED);
                }
                assertOutcome(routeAndComplete("The existing outcome remains authoritative."), expected);
            }
        }
    }

    @Test void limitationWithoutAnEvaluatedOutcomeRemainsUnknown() {
        beginTrace();
        LocalTurnTraceCapture.recordAnswerPresentationLimitation(OUTPUT_TRUNCATED);
        assertOutcome(routeAndComplete("A limited answer without an evaluation."), new Outcome(
                "UNKNOWN", "OUTCOME_NOT_EVALUATED", "NOT_RUN", "UNKNOWN", "UNKNOWN"));
    }

    @Test void finalRoutingPreventsPresentationOrEvaluationFromChangingTheSettledOutcome() {
        beginTrace();
        record(READ_COMPLETE);
        LocalTurnTraceCapture.recordRoutedAnswerCaptured("The complete answer was already delivered.");
        LocalTurnTraceCapture.recordAnswerPresentationLimitation(RUNTIME_IDENTITY_INCOMPLETE);
        LocalTurnTraceCapture.recordAnswerPresentationLimitation(OUTPUT_TRUNCATED);
        record(READ_LIMITED);
        assertOutcome(LocalTurnTraceCapture.complete(), READ_COMPLETE);
    }

    @Test void completedTraceDoesNotLeakPresentationLimitationsIntoTheNextTrace() {
        beginTrace();
        record(READ_COMPLETE);
        LocalTurnTraceCapture.recordAnswerPresentationLimitation(RUNTIME_IDENTITY_INCOMPLETE);
        LocalTurnTraceCapture.recordAnswerPresentationLimitation(OUTPUT_TRUNCATED);
        assertOutcome(routeAndComplete("An incomplete answer."), READ_LIMITED);

        beginTrace();
        record(READ_COMPLETE);
        assertOutcome(routeAndComplete("A complete answer in the next trace."), READ_COMPLETE);
    }

    @Test void warningLikeProviderProseAloneDoesNotChangeCompletion() {
        beginTrace();
        record(READ_COMPLETE);
        assertOutcome(routeAndComplete("The log literally contains [output truncated], "
                + "RUNTIME_IDENTITY_OUTPUT_LIMIT and RUNTIME_IDENTITY_PRESENTATION_INCOMPLETE. "
                + "Those strings are the complete requested quotation."), READ_COMPLETE);
    }

    private static void record(Outcome outcome) {
        LocalTurnTraceCapture.recordOutcome(outcome.status(), outcome.verification(), outcome.approval(),
                outcome.mutation(), outcome.classification());
    }

    private static LocalTurnTrace routeAndComplete(String answer) {
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(answer);
        return LocalTurnTraceCapture.complete();
    }

    private static void assertOutcome(LocalTurnTrace trace, Outcome expected) {
        assertNotNull(trace);
        var outcome = trace.outcome();
        assertEquals(expected.status(), outcome.status());
        assertEquals(expected.classification(), outcome.classification());
        assertEquals(expected.verification(), outcome.verificationStatus());
        assertEquals(expected.approval(), outcome.approvalStatus());
        assertEquals(expected.mutation(), outcome.mutationStatus());
        assertEquals(expected.verification(), trace.verification().status());
        var rendered = trace.events().stream().filter(event -> "OUTCOME_RENDERED".equals(event.type())).toList();
        assertEquals(1, rendered.size());
        assertEquals(expected.status(), rendered.getFirst().data().get("status"));
        assertEquals(expected.classification(), rendered.getFirst().data().get("classification"));
    }

    private static void beginTrace() {
        LocalTurnTraceCapture.begin("presentation-outcome", "presentation-tests", 1,
                "2026-09-30T00:00:00Z", "workspace-hash", "test", "llama_cpp", "fixture", "Answer the request.");
    }
}
