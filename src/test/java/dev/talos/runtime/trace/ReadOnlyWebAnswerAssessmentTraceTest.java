package dev.talos.runtime.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.runtime.verification.ReadOnlyWebAnswerAssessment;
import dev.talos.runtime.verification.ReadOnlyWebDiagnosticResult;
import dev.talos.runtime.verification.StaticWebDiagnostic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReadOnlyWebAnswerAssessmentTraceTest {
    private static final String TYPE = "READ_ONLY_WEB_ANSWER_ASSESSED";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void captureIsCleared() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void onlyClosedAssessmentMetadataIsRecorded() throws Exception {
        beginTrace("trc-answer-assessment-metadata");
        var id = diagnostic(StaticWebDiagnostic.Kind.JAVASCRIPT_MISSING_ID);
        var cssClass = diagnostic(StaticWebDiagnostic.Kind.JAVASCRIPT_MISSING_CLASS);

        LocalTurnTraceCapture.recordReadOnlyWebAnswerAssessment(
                ReadOnlyWebAnswerAssessment.limited(
                        ReadOnlyWebAnswerAssessment.Reason.SELECTOR_RELATION_NOT_ESTABLISHED),
                List.of(id, cssClass, id));
        LocalTurnTraceCapture.recordReadOnlyWebAnswerAssessment(
                ReadOnlyWebAnswerAssessment.preservedAnswer(), List.of(id));

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        List<TurnTraceEvent> assessments = assessments(trace);
        assertEquals(2, assessments.size());
        assertEquals(Map.of(
                "disposition", "LIMITED",
                "reasonCode", "SELECTOR_RELATION_NOT_ESTABLISHED",
                "typedDiagnosticCount", 3,
                "typedDiagnosticKinds", List.of("JAVASCRIPT_MISSING_CLASS", "JAVASCRIPT_MISSING_ID")),
                assessments.getFirst().data());
        assertEquals(Map.of(
                "disposition", "PRESERVED",
                "reasonCode", "PRESERVED",
                "typedDiagnosticCount", 1,
                "typedDiagnosticKinds", List.of("JAVASCRIPT_MISSING_ID")), assessments.getLast().data());
        for (TurnTraceEvent event : assessments) {
            assertEquals(Set.of("disposition", "reasonCode", "typedDiagnosticCount", "typedDiagnosticKinds"),
                    event.data().keySet());
        }
        String serialized = MAPPER.writeValueAsString(trace);
        for (String canary : List.of("PRIVATE_SOURCE", "PRIVATE_TARGET", "PRIVATE_OPERAND", "PRIVATE_MESSAGE")) {
            assertFalse(serialized.contains(canary), canary);
        }
    }

    @Test
    void absentAndInapplicableAssessmentsAreIgnored() {
        beginTrace("trc-answer-assessment-ignored");
        LocalTurnTraceCapture.recordReadOnlyWebAnswerAssessment(null, List.of(diagnostic(StaticWebDiagnostic.Kind.OTHER)));
        LocalTurnTraceCapture.recordReadOnlyWebAnswerAssessment(ReadOnlyWebAnswerAssessment.notApplicable(), null);
        LocalTurnTraceCapture.recordReadOnlyWebAnswerAssessment(ReadOnlyWebAnswerAssessment.notApplicable(),
                List.of(diagnostic(StaticWebDiagnostic.Kind.JAVASCRIPT_MISSING_ID)));

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertTrue(assessments(trace).isEmpty());
        assertEquals(LocalTurnTrace.VerificationSummary.empty(), trace.verification());
        assertEquals(LocalTurnTrace.OutcomeSummary.empty(), trace.outcome());
    }

    @Test
    void recordingIsIgnoredAfterTheAnswerHasBeenRouted() {
        beginTrace("trc-answer-assessment-routed");
        LocalTurnTraceCapture.recordDeterministicAnswerSelected("The existing answer was retained.");
        LocalTurnTraceCapture.recordRoutedAnswerCaptured("The existing answer was retained.");
        LocalTurnTraceCapture.recordReadOnlyWebAnswerAssessment(
                ReadOnlyWebAnswerAssessment.preservedAnswer(),
                List.of(diagnostic(StaticWebDiagnostic.Kind.JAVASCRIPT_MISSING_ID)));
        LocalTurnTraceCapture.recordReadOnlyWebAnswerAssessment(
                ReadOnlyWebAnswerAssessment.limited(ReadOnlyWebAnswerAssessment.Reason.UNREAD_PATH),
                List.of(diagnostic(StaticWebDiagnostic.Kind.JAVASCRIPT_MISSING_ID)));

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertTrue(assessments(trace).isEmpty());
        assertEquals(1, events(trace, "TERMINAL_ANSWER_SELECTED").size());
        assertEquals(TraceRedactor.hash("The existing answer was retained."),
                events(trace, "TERMINAL_ANSWER_SELECTED").getFirst().data().get("answerHash"));
        assertEquals("UNKNOWN", trace.outcome().status());
        assertEquals("NOT_RUN", trace.outcome().verificationStatus());
        assertTrue(trace.verification().authoritativeProofKinds().isEmpty());
    }

    @Test
    void noAnswerCandidateOrEvidenceAuthorityIsCreated() {
        beginTrace("trc-answer-assessment-no-authority");
        assertNull(LocalTurnTraceCapture.checkpointExactAnswerCandidate("PRIVATE_MESSAGE"));
        LocalTurnTraceCapture.recordReadOnlyWebAnswerAssessment(
                ReadOnlyWebAnswerAssessment.preservedAnswer(),
                List.of(diagnostic(StaticWebDiagnostic.Kind.JAVASCRIPT_MISSING_ID)));
        assertNull(LocalTurnTraceCapture.checkpointExactAnswerCandidate("PRIVATE_MESSAGE"));

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertEquals(1, assessments(trace).size());
        assertEquals(List.of("TRACE_STARTED", TYPE, "TRACE_COMPLETED"),
                trace.events().stream().map(TurnTraceEvent::type).toList());
        assertEquals(LocalTurnTrace.VerificationSummary.empty(), trace.verification());
        assertEquals(LocalTurnTrace.OutcomeSummary.empty(), trace.outcome());
        assertEquals(LocalTurnTrace.ToolSurface.empty(), trace.toolSurface());
    }

    @Test
    void anExistingAnswerCandidateIsLeftUnchanged() {
        beginTrace("trc-answer-assessment-existing-candidate");
        String answer = "The existing answer was retained.";
        LocalTurnTraceCapture.recordDeterministicAnswerSelected(answer);
        LocalTurnTraceCapture.recordReadOnlyWebAnswerAssessment(
                ReadOnlyWebAnswerAssessment.limited(ReadOnlyWebAnswerAssessment.Reason.UNREAD_PATH),
                List.of(diagnostic(StaticWebDiagnostic.Kind.JAVASCRIPT_MISSING_ID)));
        assertNotNull(LocalTurnTraceCapture.checkpointExactAnswerCandidate(answer));
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(answer);

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        TurnTraceEvent terminal = events(trace, "TERMINAL_ANSWER_SELECTED").getFirst();
        assertEquals(TraceRedactor.hash(answer), terminal.data().get("answerHash"));
        assertEquals("RUNTIME_DETERMINISTIC", terminal.data().get("origin"));
        assertTrue(events(trace, "ANSWER_TRANSFORMED").isEmpty());
        assertEquals("NOT_RUN", trace.outcome().verificationStatus());
        assertTrue(trace.verification().authoritativeProofKinds().isEmpty());
    }

    @Test
    void anExistingFailureIsNotUpgradedByPreservationMetadata() {
        beginTrace("trc-answer-assessment-existing-failure");
        LocalTurnTraceCapture.recordVerification("FAILED", "An execution failure was observed.",
                List.of("The required result was not produced."));
        LocalTurnTraceCapture.recordOutcome("INCOMPLETE", "FAILED", "NONE", "NOT_REQUESTED", "ADVISORY_ONLY");
        LocalTurnTraceCapture.recordReadOnlyWebAnswerAssessment(
                ReadOnlyWebAnswerAssessment.preservedAnswer(),
                List.of(diagnostic(StaticWebDiagnostic.Kind.JAVASCRIPT_MISSING_ID)));

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertEquals("FAILED", trace.verification().status());
        assertEquals(List.of("The required result was not produced."), trace.verification().problems());
        assertEquals("INCOMPLETE", trace.outcome().status());
        assertEquals("FAILED", trace.outcome().verificationStatus());
        assertEquals("ADVISORY_ONLY", trace.outcome().classification());
        assertTrue(trace.verification().authoritativeProofKinds().isEmpty());
        assertEquals(1, assessments(trace).size());
        assertTrue(events(trace, "TERMINAL_ANSWER_SELECTED").isEmpty());
    }

    @Test
    void absentDiagnosticsAreRecordedAsEmptyMetadata() {
        beginTrace("trc-answer-assessment-empty-diagnostics");
        LocalTurnTraceCapture.recordReadOnlyWebAnswerAssessment(
                ReadOnlyWebAnswerAssessment.limited(ReadOnlyWebAnswerAssessment.Reason.INVALID_INPUT), null);

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertEquals(Map.of(
                "disposition", "LIMITED",
                "reasonCode", "INVALID_INPUT",
                "typedDiagnosticCount", 0,
                "typedDiagnosticKinds", List.of()), assessments(trace).getFirst().data());
    }

    @Test
    void noTraceIsStartedByAssessmentRecording() {
        LocalTurnTraceCapture.clear();
        LocalTurnTraceCapture.recordReadOnlyWebAnswerAssessment(
                ReadOnlyWebAnswerAssessment.preservedAnswer(),
                List.of(diagnostic(StaticWebDiagnostic.Kind.JAVASCRIPT_MISSING_ID)));
        assertFalse(LocalTurnTraceCapture.isActive());
        assertNull(LocalTurnTraceCapture.complete());
    }

    @Test
    void selectedAssociationIsRecordedWithoutSourceOrCandidateText() throws Exception {
        beginTrace("trc-observed-association");
        String selection = "00000000-0000-0000-0000-000000000001";
        String observation = "00000000-0000-0000-0000-000000000002:1";
        var result = new ReadOnlyWebDiagnosticResult("PRIVATE_CANDIDATE",
                ReadOnlyWebAnswerAssessment.preservedAnswer(),
                List.of(diagnostic(StaticWebDiagnostic.Kind.JAVASCRIPT_MISSING_ID)))
                .withAssociation(selection, "generation-1", "PRIVATE_INPUT", List.of(observation));
        LocalTurnTraceCapture.recordReadOnlyWebAnswerAssessment(result);
        LocalTurnTraceCapture.recordOutcome("INCOMPLETE", "FAILED", "NONE", "NOT_REQUESTED", "ADVISORY_ONLY");

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        var metadata = assessments(trace).getFirst().data();
        assertEquals(selection, metadata.get("selectionId"));
        assertEquals("generation-1", metadata.get("generationId"));
        assertEquals(ReadOnlyWebDiagnosticResult.textHash("PRIVATE_INPUT"), metadata.get("inputHash"));
        assertEquals(ReadOnlyWebDiagnosticResult.textHash("PRIVATE_CANDIDATE"), metadata.get("outputHash"));
        assertEquals(List.of(observation), metadata.get("observationIds"));
        assertEquals("observed-static-web-v1", metadata.get("analysisVersion"));
        assertEquals("INTERMEDIATE_ASSESSMENT", metadata.get("scope"));
        assertEquals("INCOMPLETE", trace.outcome().status());
        String serialized = MAPPER.writeValueAsString(trace);
        for (String canary : List.of("PRIVATE_CANDIDATE", "PRIVATE_INPUT", "PRIVATE_SOURCE", "PRIVATE_MESSAGE"))
            assertFalse(serialized.contains(canary), canary);
        assertTrue(events(trace, "TERMINAL_ANSWER_SELECTED").isEmpty());
    }

    @Test
    void privatePathsAndFreeTextCannotBeUsedAsAssociationFields() {
        assertThrows(IllegalArgumentException.class, () -> new ReadOnlyWebDiagnosticResult.Association(
                "C:/private/source.html", "generation-1", "", "", List.of(), ""));
        assertThrows(IllegalArgumentException.class, () -> new ReadOnlyWebDiagnosticResult.Association(
                "", "generation-1", "PRIVATE_INPUT", "", List.of(), ""));
        assertThrows(IllegalArgumentException.class, () -> new ReadOnlyWebDiagnosticResult.Association(
                "", "generation-1", "", "", List.of("PRIVATE_SOURCE"), ""));
    }

    private static StaticWebDiagnostic diagnostic(StaticWebDiagnostic.Kind kind) {
        return new StaticWebDiagnostic(kind, "PRIVATE_SOURCE", "PRIVATE_TARGET",
                List.of("PRIVATE_OPERAND"), "PRIVATE_MESSAGE");
    }

    private static List<TurnTraceEvent> assessments(LocalTurnTrace trace) {
        return events(trace, TYPE);
    }

    private static List<TurnTraceEvent> events(LocalTurnTrace trace, String type) {
        return trace.events().stream().filter(event -> type.equals(event.type())).toList();
    }

    private static void beginTrace(String traceId) {
        LocalTurnTraceCapture.begin(traceId, "sid-answer-assessment", 1, "2026-09-12T00:00:00Z",
                "workspace-hash", "ask", "test", "test-model", "An answer assessment is recorded.");
    }
}
