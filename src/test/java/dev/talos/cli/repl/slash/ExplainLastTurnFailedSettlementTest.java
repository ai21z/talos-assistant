package dev.talos.cli.repl.slash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.JsonTurnLogAppender;
import dev.talos.runtime.Result;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.TurnResult;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.TurnEvidenceProjectionTraceEvent;
import dev.talos.runtime.trace.TurnTraceEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplainLastTurnFailedSettlementTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> VIEWS = List.of("", "tools", "sources", "trace");
    private static final List<String> HOSTILE_CANARIES = List.of(
            "USER_FREE_FORM_CANARY",
            "ASSISTANT_FREE_FORM_CANARY",
            "TOOL_NAME_CANARY",
            "PATH_HINT_CANARY",
            "TOOL_REASON_CANARY",
            "POLICY_FREE_FORM_CANARY",
            "RETRIEVAL_FREE_FORM_CANARY",
            "TRACE_OUTCOME_CANARY",
            "INVALID_MARKER_CANARY");

    @Test
    void validFailedPairUsesOnlyBoundedTerminalRendererForEveryView(
            @TempDir Path dir
    ) {
        String traceWorkspaceId = JsonSessionStore.sessionIdFor(dir);
        String sessionId = storageSessionId(dir);
        String traceId = "trc-valid-terminal";
        TurnRecord row = hostileRow(traceId, "RETRIEVAL_FREE_FORM_CANARY");
        LocalTurnTrace trace = terminalTrace(
                traceId, traceWorkspaceId, "CAPTURE_OBSERVED_UNBOUND", MarkerShape.VALID);
        JsonSessionStore store = persist(dir, sessionId, row, trace);

        for (String view : VIEWS) {
            String text = execute(dir, store, sessionId, view);

            assertTrue(text.contains("Trace pair: VALID"), text);
            assertTrue(text.contains("Status:    ERROR"), text);
            assertTrue(text.contains("Outcome:   TURN_FAILED"), text);
            assertTrue(text.contains("Settlement: FAILED at ASSISTANT_PREPARATION"), text);
            assertTrue(text.contains("Evidence projection: INCOMPLETE"), text);
            assertTrue(text.contains("retrieval: CAPTURE_OBSERVED_UNBOUND"), text);
            assertTrue(text.contains("Tools: unavailable in terminal projection"), text);
            assertTrue(text.contains("Sources: unavailable in terminal projection"), text);
            assertNoHostileCanary(text);
        }
    }

    @Test
    void validCancelledPairUsesOnlyBoundedTerminalRendererForEveryView(
            @TempDir Path dir
    ) {
        String traceWorkspaceId = JsonSessionStore.sessionIdFor(dir);
        String sessionId = storageSessionId(dir);
        String traceId = "trc-valid-cancelled";
        TurnRecord row = hostileRow(
                traceId, "RETRIEVAL_FREE_FORM_CANARY", "cancelled");
        LocalTurnTrace trace = terminalTrace(
                traceId,
                traceWorkspaceId,
                "CAPTURE_OBSERVED_UNBOUND",
                MarkerShape.VALID,
                "CANCELLED",
                "GENERATION_COORDINATION",
                "CANCELLATION",
                "java.util.concurrent.CancellationException");
        JsonSessionStore store = persist(dir, sessionId, row, trace);

        for (String view : VIEWS) {
            String text = execute(dir, store, sessionId, view);

            assertTrue(text.contains("Trace pair: VALID"), text);
            assertTrue(text.contains("Status:    ABORTED"), text);
            assertTrue(text.contains("Outcome:   TURN_CANCELLED"), text);
            assertTrue(text.contains(
                    "Settlement: CANCELLED at GENERATION_COORDINATION"), text);
            assertTrue(text.contains("Evidence projection: INCOMPLETE"), text);
            assertTrue(text.contains("retrieval: CAPTURE_OBSERVED_UNBOUND"), text);
            assertTrue(text.contains("Tools: unavailable in terminal projection"), text);
            assertTrue(text.contains("Sources: unavailable in terminal projection"), text);
            assertNoHostileCanary(text);
        }
    }

    @Test
    void completedResultErrorRetainsOrdinaryLastSummaryBytes(
            @TempDir Path dir
    ) {
        String sessionId = "completed-result-error";
        JsonSessionStore store = persistCompleted(
                dir,
                sessionId,
                new TurnResult(new Result.Error("ordinary error detail", 500), 7),
                "ordinary error request");

        TurnRecord row = store.loadTurns(sessionId).getFirst();
        assertEquals("error", row.status());
        assertEquals("""
                Last Turn

                  Turn:      7
                  Status:    error
                  Outcome:   ERROR
                  Duration:  0ms
                  Approvals: required=0 granted=0 denied=0

                User Request
                  ordinary error request

                Tools
                  none
                """, execute(dir, store, sessionId, ""));
        assertOrdinaryViewsHaveNoTerminalMarkers(dir, store, sessionId);
    }

    @Test
    void legacyCompletedAbortMarkerRetainsOrdinaryLastSummaryBytes(
            @TempDir Path dir
    ) {
        String sessionId = "completed-abort-marker";
        JsonSessionStore store = persistCompleted(
                dir,
                sessionId,
                new TurnResult(new Result.Streamed(
                        "[turn aborted: legacy completed marker]", ""), 8),
                "legacy abort request");

        TurnRecord row = store.loadTurns(sessionId).getFirst();
        assertEquals("aborted", row.status());
        assertEquals("""
                Last Turn

                  Turn:      8
                  Status:    aborted
                  Outcome:   ABORTED
                  Duration:  0ms
                  Approvals: required=0 granted=0 denied=0

                User Request
                  legacy abort request

                Tools
                  none
                """, execute(dir, store, sessionId, ""));
        assertOrdinaryViewsHaveNoTerminalMarkers(dir, store, sessionId);
    }

    @Test
    void matchingRowIdentityFromDifferentWorkspaceFailsClosedForEveryView(
            @TempDir Path dir
    ) throws Exception {
        String traceWorkspaceId = JsonSessionStore.sessionIdFor(dir);
        String sessionId = storageSessionId(dir);
        String traceId = "trc-different-workspace";
        TurnRecord row = hostileRow(traceId, "RETRIEVAL_FREE_FORM_CANARY");
        LocalTurnTrace trace = terminalTrace(
                traceId,
                traceWorkspaceId,
                "CAPTURE_OBSERVED_UNBOUND",
                MarkerShape.VALID);
        JsonSessionStore store = persist(dir, sessionId, row, trace);
        tamperTraceSession(
                dir,
                sessionId,
                JsonSessionStore.sessionIdFor(dir.resolve("other-workspace")));

        for (String view : VIEWS) {
            String text = execute(dir, store, sessionId, view);
            assertTrue(text.contains("Trace pair: INVALID/UNKNOWN"), text);
            assertTrue(text.contains("Settlement: INVALID/UNKNOWN"), text);
            assertTrue(text.contains("Evidence projection: INVALID/UNKNOWN"), text);
            assertNoHostileCanary(text);
        }
    }

    @Test
    void tamperedPolicyPayloadInvalidatesThePairWithoutRenderingIt(
            @TempDir Path dir
    ) throws Exception {
        String sessionId = storageSessionId(dir);
        String traceId = "trc-policy-tamper";
        JsonSessionStore store = persist(
                dir,
                sessionId,
                hostileRow(traceId, ""),
                terminalTrace(
                        traceId,
                        JsonSessionStore.sessionIdFor(dir),
                        "UNAVAILABLE",
                        MarkerShape.VALID));
        tamperTurnPolicyReason(
                dir,
                sessionId,
                "POLICY_FREE_FORM_CANARY");

        for (String view : VIEWS) {
            String text = execute(dir, store, sessionId, view);
            assertTrue(text.contains("Trace pair: INVALID/UNKNOWN"), text);
            assertTrue(text.contains("Settlement: INVALID/UNKNOWN"), text);
            assertTrue(text.contains(
                    "Evidence projection: INVALID/UNKNOWN"), text);
            assertNoHostileCanary(text);
        }
    }

    @Test
    void missingProjectionCounterpartFailsClosedWithoutRowDisclosure(
            @TempDir Path dir
    ) {
        String sessionId = storageSessionId(dir);
        String traceId = "trc-missing-marker";
        JsonSessionStore store = persist(
                dir,
                sessionId,
                hostileRow(traceId, "RETRIEVAL_FREE_FORM_CANARY"),
                terminalTrace(
                        traceId,
                        JsonSessionStore.sessionIdFor(dir),
                        "UNAVAILABLE",
                        MarkerShape.MISSING));

        for (String view : VIEWS) {
            String text = execute(dir, store, sessionId, view);

            assertTrue(text.contains("Settlement: FAILED at ASSISTANT_PREPARATION"), text);
            assertTrue(text.contains("Evidence projection: INVALID/UNKNOWN"), text);
            assertTrue(text.contains("retrieval: INVALID/UNKNOWN"), text);
            assertFalse(text.contains("Local trace:"), text);
            assertNoHostileCanary(text);
        }
    }

    @Test
    void activeRowStatusMustMatchItsTypedSettlementForEveryView(
            @TempDir Path dir
    ) {
        String sessionId = storageSessionId(dir);
        String traceId = "trc-active-status-mismatch";
        JsonSessionStore store = persist(
                dir,
                sessionId,
                hostileRow(traceId, "", "cancelled"),
                terminalTrace(
                        traceId,
                        JsonSessionStore.sessionIdFor(dir),
                        "UNAVAILABLE",
                        MarkerShape.VALID,
                        "FAILED",
                        "ASSISTANT_EXECUTION",
                        "UNEXPECTED",
                        "java.lang.IllegalStateException"));

        for (String view : VIEWS) {
            String text = execute(dir, store, sessionId, view);
            assertTrue(text.contains("Trace pair: VALID"), text);
            assertTrue(text.contains("Settlement: INVALID/UNKNOWN"), text);
            assertTrue(text.contains(
                    "Evidence projection: INVALID/UNKNOWN"), text);
            assertFalse(text.contains("Local trace:"), text);
            assertNoHostileCanary(text);
        }
    }

    @Test
    void invalidProjectionMarkerFailsClosedWithoutRenderingItsPayload(
            @TempDir Path dir
    ) throws Exception {
        String sessionId = storageSessionId(dir);
        String traceId = "trc-invalid-marker";
        JsonSessionStore store = persist(
                dir,
                sessionId,
                hostileRow(traceId, ""),
                terminalTrace(
                        traceId,
                        JsonSessionStore.sessionIdFor(dir),
                        "UNAVAILABLE",
                        MarkerShape.VALID));
        tamperProjectionMarker(dir, sessionId, "INVALID_MARKER_CANARY");

        String text = execute(dir, store, sessionId, "trace");

        assertTrue(text.contains("Trace pair: VALID"), text);
        assertTrue(text.contains("Evidence projection: INVALID/UNKNOWN"), text);
        assertFalse(text.contains("INVALID_MARKER_CANARY"), text);
        assertNoHostileCanary(text);
    }

    @Test
    void projectionMarkerAloneForcesTerminalRendererAndInvalidSettlement(
            @TempDir Path dir
    ) throws Exception {
        String sessionId = storageSessionId(dir);
        String traceId = "trc-projection-only";
        JsonSessionStore store = persist(
                dir,
                sessionId,
                hostileRow(traceId, "", "ok"),
                terminalTrace(
                        traceId,
                        JsonSessionStore.sessionIdFor(dir),
                        "UNAVAILABLE",
                        MarkerShape.VALID));
        tamperRemoveSettlement(dir, sessionId);

        for (String view : VIEWS) {
            String text = execute(dir, store, sessionId, view);
            assertTrue(text.contains("Settlement: INVALID/UNKNOWN"), text);
            assertTrue(text.contains("Evidence projection: INVALID/UNKNOWN"), text);
            assertNoHostileCanary(text);
        }
    }

    @Test
    void retrievalCaptureMarkerMustMatchRowSummaryInBothDirectionsForEveryView(
            @TempDir Path dir
    ) {
        assertRetrievalMismatch(
                dir.resolve("capture-without-row"),
                "trc-capture-without-row",
                "CAPTURE_OBSERVED_UNBOUND",
                "");
        assertRetrievalMismatch(
                dir.resolve("row-without-capture"),
                "trc-row-without-capture",
                "UNAVAILABLE",
                "RETRIEVAL_FREE_FORM_CANARY");
    }

    @Test
    void missingTraceCounterpartFailsClosedForAllViews(@TempDir Path dir) {
        String sessionId = "missing-trace-session";
        String traceId = "trc-missing-trace";
        JsonSessionStore store = new JsonSessionStore(dir.resolve("sessions"));
        try (var writer = store.acquireSessionWriter(sessionId)) {
            writer.appendTurn(
                    sessionId, hostileRow(traceId, "RETRIEVAL_FREE_FORM_CANARY"));
        }

        for (String view : VIEWS) {
            String text = execute(dir, store, sessionId, view);

            assertTrue(text.contains("Trace pair: INVALID/UNKNOWN"), text);
            assertTrue(text.contains("Settlement: INVALID/UNKNOWN"), text);
            assertTrue(text.contains("Evidence projection: INVALID/UNKNOWN"), text);
            assertFalse(text.contains("Local trace:"), text);
            assertNoHostileCanary(text);
        }
    }

    @Test
    void noResultRowWithoutTraceStillUsesTerminalFailClosedRenderer(@TempDir Path dir) {
        String sessionId = "no-result-partial";
        JsonSessionStore store = new JsonSessionStore(dir.resolve("sessions"));
        try (var writer = store.acquireSessionWriter(sessionId)) {
            writer.appendTurn(sessionId, new TurnRecord(
                    1,
                    Instant.parse("2026-07-21T00:00:02Z"),
                    12,
                    "",
                    "",
                    List.of(),
                    0,
                    0,
                    0,
                    "",
                    "no_result",
                    TurnPolicyTrace.empty(),
                    ""));
        }

        for (String view : VIEWS) {
            String text = execute(dir, store, sessionId, view);
            assertTrue(text.contains("Trace pair: INVALID/UNKNOWN"), text);
            assertTrue(text.contains("Status:    UNKNOWN"), text);
            assertTrue(text.contains("Settlement: INVALID/UNKNOWN"), text);
            assertTrue(text.contains("Tools: unavailable in terminal projection"), text);
            assertTrue(text.contains("Sources: unavailable in terminal projection"), text);
        }
    }

    @Test
    void ordinaryCompletedSummaryRenderingRemainsByteCompatible() {
        TurnRecord ordinary = new TurnRecord(
                3,
                Instant.parse("2026-07-21T00:00:00Z"),
                5,
                "ordinary request",
                "ordinary answer",
                List.of(),
                0,
                0,
                0,
                "",
                "ok",
                TurnPolicyTrace.empty(),
                "");

        assertEquals("""
                Last Turn

                  Turn:      3
                  Status:    ok
                  Outcome:   NO_TOOL_RESPONSE
                  Duration:  5ms
                  Approvals: required=0 granted=0 denied=0

                User Request
                  ordinary request

                Tools
                  none

                Assistant Preview
                  ordinary answer
                """, ExplainLastTurnCommand.render(ordinary));
    }

    private static void assertRetrievalMismatch(
            Path dir,
            String traceId,
            String markerRetrieval,
            String rowRetrieval
    ) {
        String sessionId = storageSessionId(dir);
        TurnRecord row = hostileRow(traceId, rowRetrieval);
        LocalTurnTrace trace = terminalTrace(
                traceId,
                JsonSessionStore.sessionIdFor(dir),
                markerRetrieval,
                MarkerShape.VALID);
        JsonSessionStore store = persist(dir, sessionId, row, trace);
        for (String view : VIEWS) {
            String text = execute(dir, store, sessionId, view);
            assertTrue(text.contains("Evidence projection: INVALID/UNKNOWN"), text);
            assertTrue(text.contains("retrieval: INVALID/UNKNOWN"), text);
            assertTrue(text.contains("Tools: unavailable in terminal projection"), text);
            assertTrue(text.contains("Sources: unavailable in terminal projection"), text);
            assertNoHostileCanary(text);
        }
    }

    private static JsonSessionStore persist(
            Path dir,
            String sessionId,
            TurnRecord row,
            LocalTurnTrace trace
    ) {
        JsonSessionStore store = new JsonSessionStore(dir.resolve("sessions"));
        try (var writer = store.acquireSessionWriter(sessionId)) {
            writer.persistTurnEnvelope(sessionId, row, trace);
        }
        return new JsonSessionStore(dir.resolve("sessions"));
    }

    private static JsonSessionStore persistCompleted(
            Path dir,
            String sessionId,
            TurnResult result,
            String userInput
    ) {
        JsonSessionStore store = new JsonSessionStore(dir.resolve("sessions"));
        try (var writer = store.acquireSessionWriter(sessionId)) {
            new JsonTurnLogAppender(writer, sessionId).onTurnComplete(result, userInput);
        }
        return new JsonSessionStore(dir.resolve("sessions"));
    }

    private static String execute(
            Path workspace,
            JsonSessionStore store,
            String sessionId,
            String view
    ) {
        ExplainLastTurnCommand command = new ExplainLastTurnCommand(
                workspace, store, null, sessionId);
        Result.TrustedInfo result = assertInstanceOf(
                Result.TrustedInfo.class,
                command.execute(view, Context.builder(new Config()).build()));
        return result.text;
    }

    private static TurnRecord hostileRow(String traceId, String retrievalSummary) {
        return hostileRow(traceId, retrievalSummary, "failed");
    }

    private static TurnRecord hostileRow(
            String traceId,
            String retrievalSummary,
            String status
    ) {
        return new TurnRecord(
                1,
                Instant.parse("2026-07-21T00:00:02Z"),
                12,
                "USER_FREE_FORM_CANARY",
                "ASSISTANT_FREE_FORM_CANARY",
                List.of(new TurnRecord.ToolCallSummary(
                        "TOOL_NAME_CANARY",
                        "PATH_HINT_CANARY",
                        false,
                        "TOOL_REASON_CANARY")),
                4,
                2,
                2,
                retrievalSummary,
                status,
                TurnPolicyTrace.empty(),
                traceId);
    }

    private static String storageSessionId(Path workspace) {
        return JsonSessionStore.newSessionInstanceId(
                workspace,
                Instant.parse("2026-07-21T00:00:00Z"));
    }

    private static void tamperTraceSession(
            Path root,
            String sessionId,
            String replacementSessionId
    ) throws IOException {
        ObjectNode trace = readTraceNode(root, sessionId);
        trace.put("sessionId", replacementSessionId);
        writeTraceNode(root, sessionId, trace);
    }

    private static void tamperTurnPolicyReason(
            Path root,
            String sessionId,
            String reason
    ) throws IOException {
        Path turnLog = root.resolve("sessions")
                .resolve(sessionId + ".turns.jsonl");
        ObjectNode row = (ObjectNode) MAPPER.readTree(
                Files.readString(turnLog));
        ((ObjectNode) row.path("policyTrace"))
                .put("classificationReason", reason);
        Files.writeString(
                turnLog,
                MAPPER.writeValueAsString(row) + "\n");
    }

    private static void tamperProjectionMarker(
            Path root,
            String sessionId,
            String canary
    ) throws IOException {
        ObjectNode trace = readTraceNode(root, sessionId);
        boolean changed = false;
        for (JsonNode event : trace.withArray("events")) {
            if (TurnEvidenceProjectionTraceEvent.TYPE.equals(
                    event.path("type").asText())) {
                ((ObjectNode) event.path("data")).put("raw", canary);
                changed = true;
            }
        }
        assertTrue(changed, "projection marker fixture must exist before tampering");
        writeTraceNode(root, sessionId, trace);
    }

    private static void tamperRemoveSettlement(
            Path root,
            String sessionId
    ) throws IOException {
        ObjectNode trace = readTraceNode(root, sessionId);
        var events = trace.withArray("events");
        boolean changed = false;
        for (int index = events.size() - 1; index >= 0; index--) {
            if ("TURN_SETTLED".equals(events.get(index).path("type").asText())) {
                events.remove(index);
                changed = true;
            }
        }
        assertTrue(changed, "settlement fixture must exist before tampering");
        writeTraceNode(root, sessionId, trace);
    }

    private static ObjectNode readTraceNode(
            Path root,
            String sessionId
    ) throws IOException {
        return (ObjectNode) MAPPER.readTree(Files.readString(
                traceFile(root, sessionId)));
    }

    private static void writeTraceNode(
            Path root,
            String sessionId,
            ObjectNode trace
    ) throws IOException {
        Files.writeString(
                traceFile(root, sessionId),
                MAPPER.writeValueAsString(trace));
    }

    private static Path traceFile(Path root, String sessionId)
            throws IOException {
        Path traceDir = root.resolve("sessions").resolve("traces")
                .resolve(sessionId);
        try (var paths = Files.list(traceDir)) {
            return paths.findFirst().orElseThrow();
        }
    }

    private static LocalTurnTrace terminalTrace(
            String traceId,
            String sessionId,
            String retrieval,
            MarkerShape markerShape
    ) {
        return terminalTrace(
                traceId,
                sessionId,
                retrieval,
                markerShape,
                "FAILED",
                "ASSISTANT_PREPARATION",
                "UNEXPECTED",
                "java.lang.IllegalStateException");
    }

    private static LocalTurnTrace terminalTrace(
            String traceId,
            String sessionId,
            String retrieval,
            MarkerShape markerShape,
            String settlementKind,
            String stage,
            String errorKind,
            String exceptionClass
    ) {
        LocalTurnTrace.Builder builder = LocalTurnTrace.builder(
                        traceId, sessionId, 1, "2026-07-21T00:00:00Z")
                .mode("ask")
                .outcome(
                        "TRACE_OUTCOME_CANARY",
                        "TRACE_OUTCOME_CANARY",
                        "TRACE_OUTCOME_CANARY",
                        "TRACE_OUTCOME_CANARY",
                        "TRACE_OUTCOME_CANARY")
                .event(TurnTraceEvent.simple(
                        "TRACE_STARTED",
                        "2026-07-21T00:00:00Z",
                        Map.of("turnNumber", 1, "redactionMode", "DEFAULT")));
        if (markerShape != MarkerShape.MISSING) {
            Map<String, Object> marker = markerData(retrieval);
            if (markerShape == MarkerShape.INVALID) {
                marker.put("raw", "INVALID_MARKER_CANARY");
            }
            builder.event(TurnTraceEvent.simple(
                    TurnEvidenceProjectionTraceEvent.TYPE,
                    "2026-07-21T00:00:00Z",
                    marker));
        }
        return builder
                .event(TurnTraceEvent.simple(
                        "TURN_SETTLED",
                        "2026-07-21T00:00:01Z",
                        Map.of(
                                "schemaVersion", 1,
                                "settlementKind", settlementKind,
                                "stage", stage,
                                "errorKind", errorKind,
                                "errorCode", 500,
                                "exceptionClass", exceptionClass,
                                "causalOwner", "UNKNOWN")))
                .event(TurnTraceEvent.simple(
                        "TRACE_COMPLETED", "2026-07-21T00:00:02Z", Map.of()))
                .build();
    }

    private static Map<String, Object> markerData(String retrieval) {
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("schemaVersion", 1);
        marker.put("completeness", "INCOMPLETE");
        marker.put("reason", "NON_CONVERSATIONAL_SAFE_PROJECTION");
        marker.put("providerLifecycle", "NOT_OBSERVED");
        marker.put("retrieval", retrieval);
        marker.put("captureFinalizers", "OBSERVED_COMPLETE");
        marker.put("omittedCategories", List.of(
                "CHECKPOINT_VERIFICATION", "POLICY_CONTEXT", "TOOL_LIFECYCLE"));
        return marker;
    }

    private static void assertNoHostileCanary(String text) {
        for (String canary : HOSTILE_CANARIES) {
            assertFalse(text.contains(canary), () -> canary + " leaked through:\n" + text);
        }
    }

    private static void assertOrdinaryViewsHaveNoTerminalMarkers(
            Path dir,
            JsonSessionStore store,
            String sessionId
    ) {
        for (String view : VIEWS) {
            String text = execute(dir, store, sessionId, view);
            assertFalse(text.contains("Trace pair:"), text);
            assertFalse(text.contains("Settlement:"), text);
            assertFalse(text.contains("Evidence projection:"), text);
            assertFalse(text.contains("terminal projection"), text);
        }
    }

    private enum MarkerShape { VALID, MISSING, INVALID }
}
