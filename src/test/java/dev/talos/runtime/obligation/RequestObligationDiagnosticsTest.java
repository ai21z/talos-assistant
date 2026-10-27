package dev.talos.runtime.obligation;

import dev.talos.cli.prompt.PromptDebugInspector;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.TurnTraceEvent;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.PromptDebugSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestObligationDiagnosticsTest {
    private static final String F21_REQUEST =
            "Append Decision 004 to docs/decisions.md saying health checks remain read-only. "
                    + "Preserve Decisions 001 through 003 and change no code.";

    @AfterEach
    void clearTrace() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void traceCarriesOneExactHashAndRedactedTypedProjection() {
        RequestObligation obligation = RequestObligationCompiler.compile(
                TaskContractResolver.fromUserRequest(F21_REQUEST));
        LocalTurnTraceCapture.begin(
                "trace-request-obligation",
                "session-request-obligation",
                1,
                "2026-07-28T00:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                F21_REQUEST);

        LocalTurnTraceCapture.recordRequestObligation(obligation);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        List<TurnTraceEvent> events = trace.events().stream()
                .filter(event -> event.type().equals("REQUEST_OBLIGATION_COMPILED"))
                .toList();
        assertEquals(1, events.size());
        Map<String, Object> data = events.getFirst().data();
        assertEquals(obligation.canonicalSha256(), data.get("canonicalSha256"));
        assertEquals("COMPLETE", data.get("derivationStatus"));
        assertEquals("APPEND_STATEMENT", data.get("actionKind"));
        assertEquals(
                QualificationFreeJson.parse(obligation.redactedProjectionJson()),
                data);
        assertFalse(data.toString().contains("health checks remain read-only"));
        assertFalse(data.toString().contains("Decision 004"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void redactedProjectionAndRecordedTraceCannotBeMutatedThroughNestedMaps() {
        RequestObligation obligation = RequestObligationCompiler.compile(
                TaskContractResolver.fromUserRequest(F21_REQUEST));
        Map<String, Object> projection = obligation.redactedProjection();
        Map<String, Object> projectedOutput =
                (Map<String, Object>) projection.get("outputShape");
        assertThrows(
                UnsupportedOperationException.class,
                () -> projectedOutput.put("target", "forged.md"));

        LocalTurnTraceCapture.begin(
                "trace-request-obligation-immutability",
                "session-request-obligation-immutability",
                1,
                "2026-07-28T00:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                F21_REQUEST);
        LocalTurnTraceCapture.recordRequestObligation(obligation);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        Map<String, Object> data = trace.events().stream()
                .filter(event -> event.type().equals("REQUEST_OBLIGATION_COMPILED"))
                .findFirst()
                .orElseThrow()
                .data();
        Map<String, Object> recordedOutput =
                (Map<String, Object>) data.get("outputShape");
        assertThrows(
                UnsupportedOperationException.class,
                () -> recordedOutput.put("target", "forged.md"));
        assertEquals(
                obligation.redactedProjection(),
                trace.events().stream()
                        .filter(event ->
                                event.type().equals("REQUEST_OBLIGATION_COMPILED"))
                        .findFirst()
                        .orElseThrow()
                        .data());
    }

    @Test
    void promptDebugRendersTheWholeRedactedCanonicalObligationAndHash() {
        RequestObligation obligation = RequestObligationCompiler.compile(
                TaskContractResolver.fromUserRequest(F21_REQUEST));
        String redacted = obligation.redactedProjectionJson();
        PromptDebugSnapshot snapshot = new PromptDebugSnapshot(
                "CHAT_REQUEST",
                "test",
                "model",
                false,
                Instant.parse("2026-07-28T00:00:00Z"),
                List.of(),
                List.of(),
                ChatRequestControls.defaults(),
                "",
                Map.of(
                        "requestObligation.redactedProjectionJson", redacted,
                        "requestObligation.fullCanonicalSha256",
                        obligation.canonicalSha256()));

        String rendered = PromptDebugInspector.format(snapshot);

        assertTrue(redacted.length() > 240);
        assertTrue(rendered.contains(redacted), rendered);
        assertTrue(rendered.contains(obligation.canonicalSha256()), rendered);
        assertFalse(rendered.contains("health checks remain read-only"));
        assertFalse(rendered.contains("\"label\":\"Decision 004\""));
        assertTrue(rendered.contains("redacted projection"));
        assertTrue(rendered.contains("Full canonical obligation SHA-256"));
    }

    @Test
    void defaultDiagnosticsRedactProtectedObligationPathsButRetainTheCanonicalBinding() {
        TaskContract protectedTarget = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                false,
                true,
                Set.of(".env"),
                Set.of(),
                Set.of(),
                "Do not act.",
                "test");
        RequestObligation obligation = RequestObligationCompiler.notMigrated(protectedTarget);

        assertFalse(obligation.redactedProjectionJson().contains(".env"));
        assertTrue(obligation.redactedProjectionJson().contains("<protected-path>"));
        assertFalse(obligation.canonicalSha256().isBlank());

        LocalTurnTraceCapture.begin(
                "trace-protected-obligation",
                "session-protected-obligation",
                1,
                "2026-07-28T00:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "protected request");
        LocalTurnTraceCapture.recordRequestObligation(obligation);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        Map<String, Object> data = trace.events().stream()
                .filter(event -> event.type().equals("REQUEST_OBLIGATION_COMPILED"))
                .findFirst()
                .orElseThrow()
                .data();
        assertFalse(data.toString().contains(".env"));
        assertTrue(data.toString().contains("<protected-path>"));
        assertEquals(obligation.canonicalSha256(), data.get("canonicalSha256"));
    }

    private static final class QualificationFreeJson {
        private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
                new com.fasterxml.jackson.databind.ObjectMapper();

        private QualificationFreeJson() {}

        static Map<String, Object> parse(String value) {
            try {
                return MAPPER.readValue(
                        value,
                        new com.fasterxml.jackson.core.type.TypeReference<>() {});
            } catch (Exception invalid) {
                throw new AssertionError(invalid);
            }
        }
    }
}
