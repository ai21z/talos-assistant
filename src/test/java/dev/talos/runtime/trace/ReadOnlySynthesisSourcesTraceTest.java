package dev.talos.runtime.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.runtime.task.ReadOnlyRequestMeaning;
import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence;
import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.Source;
import dev.talos.runtime.verification.ReadOnlyWebAnswerAssessment;
import dev.talos.runtime.verification.ReadOnlyWebDiagnosticResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadOnlySynthesisSourcesTraceTest {
    private static final String TYPE = "READ_ONLY_SYNTHESIS_SOURCES";
    private static final String ORIGIN = "00000000-0000-0000-0000-000000000001";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ReadOnlyRequestMeaning MEANING = ReadOnlyRequestMeaning.compile(
            "Read index.html and script.js. Explain why the Calculate button fails. PRIVATE_REQUEST");

    @AfterEach
    void captureIsCleared() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void orderedSourceIdentitiesAreRetainedWithoutBodiesOrRequestText() throws Exception {
        beginTrace();
        var first = source("script.js", "PRIVATE_FIRST_BODY\n", 7);
        var second = source("index.html", "PRIVATE_SECOND_BODY\n", 2);
        LocalTurnTraceCapture.recordReadOnlySynthesisSources(MEANING, List.of(first, second));

        var trace = LocalTurnTraceCapture.complete();
        var event = events(trace).getFirst();
        assertEquals(Set.of("scope", "requestHash", "meaningId", "sourceCount", "sources"), event.data().keySet());
        assertEquals("DIAGNOSTIC_ONLY", event.data().get("scope"));
        assertEquals(MEANING.requestSha256(), event.data().get("requestHash"));
        assertEquals(MEANING.identity(), event.data().get("meaningId"));
        assertEquals(2, event.data().get("sourceCount"));
        assertEquals(List.of(metadata(first), metadata(second)), event.data().get("sources"));
        String serialized = MAPPER.writeValueAsString(trace);
        for (String privateText : List.of("PRIVATE_REQUEST", "PRIVATE_FIRST_BODY", "PRIVATE_SECOND_BODY"))
            assertFalse(serialized.contains(privateText), privateText);
    }

    @Test
    void protectedPathsUseExistingTraceRedactionWhileExactHashesRemain() {
        beginTrace();
        var source = source("private/secrets.env", "PRIVATE_CONTENT\n", 1);
        LocalTurnTraceCapture.recordReadOnlySynthesisSources(MEANING, List.of(source));

        var recorded = MAPPER.valueToTree(events(LocalTurnTraceCapture.complete()).getFirst().data());
        assertEquals("<protected-path>", recorded.at("/sources/0/pathHint").asText());
        assertEquals(TraceRedactor.hash(source.path()), recorded.at("/sources/0/pathHash").asText());
        assertFalse(recorded.toString().contains(source.path()));
        assertFalse(recorded.toString().contains("PRIVATE_CONTENT"));
    }

    @Test
    void eightSourcesAreRetainedButAnOversizedSetIsNotPartiallyRecorded() {
        beginTrace();
        List<Source> eight = IntStream.rangeClosed(1, 8).mapToObj(i -> source("source-" + i + ".js", "body\n", i)).toList();
        LocalTurnTraceCapture.recordReadOnlySynthesisSources(MEANING, eight);
        List<Source> nine = new ArrayList<>(eight);
        nine.add(source("source-9.js", "body\n", 9));
        LocalTurnTraceCapture.recordReadOnlySynthesisSources(MEANING, nine);

        var events = events(LocalTurnTraceCapture.complete());
        assertEquals(1, events.size());
        assertEquals(8, events.getFirst().data().get("sourceCount"));
        assertEquals(eight.stream().map(ReadOnlySynthesisSourcesTraceTest::metadata).toList(), events.getFirst().data().get("sources"));
    }

    @Test
    void malformedMetadataIsIgnoredWithoutRecordingAValidPrefix() throws Exception {
        beginTrace();
        Source good = source("index.html", "body\n", 1);
        List<Source> malformed = List.of(
                new Source("script.js", "body\n", "PRIVATE_ORIGIN", 2, good.bodyHash(), good.projectionVersion()),
                new Source("script.js", "body\n", ORIGIN, 0, good.bodyHash(), good.projectionVersion()),
                new Source("script.js", "body\n", ORIGIN, 2, "PRIVATE_HASH", good.projectionVersion()),
                new Source("script.js", "body\n", ORIGIN, 2, good.bodyHash(), "PRIVATE_PROJECTION"),
                new Source("", "body\n", ORIGIN, 2, good.bodyHash(), good.projectionVersion()),
                new Source("x".repeat(4097), "body\n", ORIGIN, 2, good.bodyHash(), good.projectionVersion()),
                new Source("script.js", "x".repeat(16_001), ORIGIN, 2, good.bodyHash(), good.projectionVersion()),
                good);
        for (Source invalid : malformed)
            LocalTurnTraceCapture.recordReadOnlySynthesisSources(MEANING, List.of(good, invalid));
        List<Source> withNull = new ArrayList<>(List.of(good));
        withNull.add(null);
        LocalTurnTraceCapture.recordReadOnlySynthesisSources(MEANING, withNull);
        LocalTurnTraceCapture.recordReadOnlySynthesisSources(null, List.of(good));
        LocalTurnTraceCapture.recordReadOnlySynthesisSources(MEANING, null);
        LocalTurnTraceCapture.recordReadOnlySynthesisSources(MEANING, List.of());

        var trace = LocalTurnTraceCapture.complete();
        assertTrue(events(trace).isEmpty());
        assertFalse(MAPPER.writeValueAsString(trace).contains("PRIVATE_"));
    }

    @Test
    void failedSynthesisKeepsSourceMetadataWithoutCreatingAuthority() {
        beginTrace();
        assertNull(LocalTurnTraceCapture.checkpointExactAnswerCandidate("answer"));
        LocalTurnTraceCapture.recordReadOnlySynthesisSources(MEANING, List.of(source("index.html", "body\n", 1)));
        assertNull(LocalTurnTraceCapture.checkpointExactAnswerCandidate("answer"));
        LocalTurnTraceCapture.recordReadOnlyWebAnswerAssessment(
                ReadOnlyWebAnswerAssessment.limited(ReadOnlyWebAnswerAssessment.Reason.SYNTHESIS_UNAVAILABLE), List.of());
        LocalTurnTraceCapture.recordOutcome("ADVISORY_ONLY", "NOT_RUN", "NONE", "NOT_REQUESTED", "ADVISORY_ONLY");

        var trace = LocalTurnTraceCapture.complete();
        assertEquals(1, events(trace).size());
        assertEquals("ADVISORY_ONLY", trace.outcome().status());
        assertEquals("NOT_RUN", trace.outcome().verificationStatus());
        assertEquals("NOT_REQUESTED", trace.outcome().mutationStatus());
        assertTrue(trace.verification().authoritativeProofKinds().isEmpty());
        assertTrue(trace.events().stream().noneMatch(e -> e.type().equals("TERMINAL_ANSWER_SELECTED")
                || e.type().equals("GENERATION_RESPONSE_CAPTURED")));
    }

    @Test
    void inactiveAndCompletedTracesAreNotStartedOrChanged() {
        LocalTurnTraceCapture.clear();
        LocalTurnTraceCapture.recordReadOnlySynthesisSources(MEANING, List.of(source("index.html", "body\n", 1)));
        assertFalse(LocalTurnTraceCapture.isActive());
        assertNull(LocalTurnTraceCapture.complete());

        beginTrace();
        var completed = LocalTurnTraceCapture.complete();
        LocalTurnTraceCapture.recordReadOnlySynthesisSources(MEANING, List.of(source("index.html", "body\n", 1)));
        assertFalse(LocalTurnTraceCapture.isActive());
        assertTrue(events(completed).isEmpty());
        assertNull(LocalTurnTraceCapture.complete());
    }

    @Test
    void noSourceMetadataIsAppendedAfterAnswerRouting() {
        beginTrace();
        LocalTurnTraceCapture.recordDeterministicAnswerSelected("Existing answer.");
        LocalTurnTraceCapture.recordRoutedAnswerCaptured("Existing answer.");
        LocalTurnTraceCapture.recordReadOnlySynthesisSources(MEANING, List.of(source("index.html", "body\n", 1)));
        assertTrue(events(LocalTurnTraceCapture.complete()).isEmpty());
    }

    private static Source source(String path, String text, long ordinal) {
        return new Source(path, text, ORIGIN, ordinal, ReadOnlyWebDiagnosticResult.textHash("numbered:" + text),
                ReadOnlyFinalizationEvidence.SOURCE_PROJECTION_VERSION);
    }

    private static Map<String, Object> metadata(Source source) {
        return Map.of("observationId", source.observationId(), "pathHint", source.path(),
                "pathHash", TraceRedactor.hash(source.path()), "bodyHash", source.bodyHash(),
                "textHash", ReadOnlyWebDiagnosticResult.textHash(source.text()), "projectionVersion", source.projectionVersion());
    }

    private static List<TurnTraceEvent> events(LocalTurnTrace trace) {
        return trace.events().stream().filter(event -> event.type().equals(TYPE)).toList();
    }

    private static void beginTrace() {
        LocalTurnTraceCapture.begin("trc-synthesis-sources", "sid-synthesis-sources", 1, "2026-09-23T00:00:00Z",
                "workspace-hash", "ask", "test", "test-model", "A source identity trace is recorded.");
    }
}
