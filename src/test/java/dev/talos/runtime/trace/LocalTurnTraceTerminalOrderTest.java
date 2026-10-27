package dev.talos.runtime.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalTurnTraceTerminalOrderTest {

    @AfterEach
    void clearTrace() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void evaluatedOutcomeIsRenderedOnlyAfterTerminalAnswerAndRoute() {
        beginTrace("trace-outcome-before-route");
        LocalTurnTraceCapture.recordOutcome(
                "COMPLETE", "PASSED", "NONE", "NOT_REQUESTED", "READ_ONLY_ANSWERED");
        LocalTurnTraceCapture.recordDeterministicAnswerSelected("Ready");
        LocalTurnTraceCapture.recordRoutedAnswerCaptured("Ready");

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertEquals(List.of(
                "OUTCOME_EVALUATED",
                "TERMINAL_ANSWER_SELECTED",
                "ROUTED_ANSWER_CAPTURED",
                "OUTCOME_RENDERED",
                "TRACE_COMPLETED"), lifecycle(trace));
    }

    @Test
    void fallbackOutcomeAfterRouteStillPrecedesTraceCompletion() {
        beginTrace("trace-route-before-outcome");
        LocalTurnTraceCapture.recordDeterministicAnswerSelected("Ready");
        LocalTurnTraceCapture.recordRoutedAnswerCaptured("Ready");
        LocalTurnTraceCapture.recordOutcomeIfAbsent(
                "COMPLETE", "NOT_RUN", "NONE", "NOT_REQUESTED", "READ_ONLY_ANSWERED");

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertEquals(List.of(
                "TERMINAL_ANSWER_SELECTED",
                "ROUTED_ANSWER_CAPTURED",
                "OUTCOME_RENDERED",
                "TRACE_COMPLETED"), lifecycle(trace));
    }

    @Test
    void terminalRouteWithoutEvaluationGetsBoundedUnknownOutcomeAtCompletion() {
        beginTrace("trace-route-without-outcome");
        LocalTurnTraceCapture.recordDeterministicAnswerSelected("Ready");
        LocalTurnTraceCapture.recordRoutedAnswerCaptured("Ready");

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertEquals(List.of(
                "TERMINAL_ANSWER_SELECTED",
                "ROUTED_ANSWER_CAPTURED",
                "OUTCOME_RENDERED",
                "TRACE_COMPLETED"), lifecycle(trace));
        assertEquals("UNKNOWN", trace.outcome().status());
        assertEquals("OUTCOME_NOT_EVALUATED", trace.outcome().classification());
    }

    @Test
    void duplicateRouteCannotCreateASecondTerminalChainOrChangeTheAnswer() {
        beginTrace("trace-duplicate-route");
        LocalTurnTraceCapture.recordDeterministicAnswerSelected("first");
        LocalTurnTraceCapture.recordRoutedAnswerCaptured("first");
        LocalTurnTraceCapture.recordDeterministicAnswerSelected("second");
        LocalTurnTraceCapture.recordRoutedAnswerCaptured("second");
        LocalTurnTraceCapture.recordOutcome(
                "COMPLETE", "NOT_RUN", "NONE", "NOT_REQUESTED", "READ_ONLY_ANSWERED");

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertEquals(1, count(trace, "TERMINAL_ANSWER_SELECTED"));
        assertEquals(1, count(trace, "ROUTED_ANSWER_CAPTURED"));
        assertEquals(1, count(trace, "OUTCOME_RENDERED"));
        TurnTraceEvent terminal = trace.events().stream()
                .filter(event -> "TERMINAL_ANSWER_SELECTED".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertEquals(TraceRedactor.hash("first"), terminal.data().get("answerHash"));
    }

    @Test
    void unmatchedRouteHasUnknownOriginInsteadOfInventingRuntimeAuthorship() {
        beginTrace("trace-unmatched-route");
        LocalTurnTraceCapture.recordRoutedAnswerCaptured("unbound answer");

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        TurnTraceEvent terminal = trace.events().stream()
                .filter(event -> "TERMINAL_ANSWER_SELECTED".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("NONE", terminal.data().get("origin"));
    }

    private static List<String> lifecycle(LocalTurnTrace trace) {
        return trace.events().stream()
                .map(TurnTraceEvent::type)
                .filter(type -> type.equals("OUTCOME_EVALUATED")
                        || type.equals("TERMINAL_ANSWER_SELECTED")
                        || type.equals("ROUTED_ANSWER_CAPTURED")
                        || type.equals("OUTCOME_RENDERED")
                        || type.equals("TRACE_COMPLETED"))
                .toList();
    }

    private static long count(LocalTurnTrace trace, String type) {
        return trace.events().stream().filter(event -> type.equals(event.type())).count();
    }

    private static void beginTrace(String traceId) {
        LocalTurnTraceCapture.begin(
                traceId,
                "sid",
                1,
                "2026-07-16T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                "test-model",
                "test terminal route ordering");
    }
}
