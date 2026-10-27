package dev.talos.qualification;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.core.llm.ProviderCanonicalAnswerLineageFixture;
import dev.talos.runtime.trace.FinalAnswerStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationFinalStatusCompatibilityTest {
    @TempDir
    Path temp;

    @Test
    void validStatusAndLegacyAbsencePreserveTheProviderOrigin() throws Exception {
        var captured = ProviderCanonicalAnswerLineageFixture.run(
                temp.resolve("workspace"), temp.resolve("store"));
        assertTrue(FinalAnswerStatus.decode(captured.trace()).available());
        ObjectNode trace = (ObjectNode) QualificationJson.tree(captured.trace());
        byte[] answer = answer(captured);
        assertEquals(QualificationRecords.AnswerOrigin.MODEL_PROVIDER, origin(answer, trace));

        events(trace).remove(index(trace, FinalAnswerStatus.TYPE));
        assertEquals(QualificationRecords.AnswerOrigin.MODEL_PROVIDER, origin(answer, trace));
    }

    @Test
    void malformedOrMisplacedStatusCannotBypassTheProvenanceChain() throws Exception {
        var captured = ProviderCanonicalAnswerLineageFixture.run(
                temp.resolve("workspace"), temp.resolve("store"));
        ObjectNode original = (ObjectNode) QualificationJson.tree(captured.trace());
        byte[] answer = answer(captured);
        assertEquals(QualificationRecords.AnswerOrigin.MODEL_PROVIDER, origin(answer, original));

        Map<String, Consumer<ObjectNode>> cases = new LinkedHashMap<>();
        cases.put("duplicate", trace -> events(trace).insert(
                index(trace, "OUTCOME_RENDERED"), event(trace, FinalAnswerStatus.TYPE).deepCopy()));
        cases.put("before route", trace -> {
            var marker = events(trace).remove(index(trace, FinalAnswerStatus.TYPE));
            events(trace).insert(index(trace, "ROUTED_ANSWER_CAPTURED"), marker);
        });
        cases.put("after outcome", trace -> {
            var marker = events(trace).remove(index(trace, FinalAnswerStatus.TYPE));
            events(trace).insert(index(trace, "TRACE_COMPLETED"), marker);
        });
        cases.put("trace identity", trace -> status(trace).put("traceId", "different-trace"));
        cases.put("answer identity", trace -> status(trace).put("answerHash", "0".repeat(64)));
        cases.put("version", trace -> status(trace).put("version", 2));
        cases.put("version type", trace -> status(trace).put("version", "1"));
        cases.put("turn binding", trace -> status(trace).put("turnNumber", 999));
        cases.put("unknown field", trace -> status(trace).put("unrecognized", true));
        cases.put("semantic pass", trace -> status(trace).put("coverage", "PASS"));
        cases.put("outcome mismatch", trace -> status(trace).put("combinedStatus", "FAILED"));
        cases.put("association type", trace -> status(trace).put("associationAvailable", "true"));
        cases.put("unknown cause", trace -> status(trace).putArray("causes").add("UNRECOGNIZED"));
        cases.put("nonempty phase", trace -> event(trace, FinalAnswerStatus.TYPE).put("phase", "APPLY"));
        cases.put("unknown intervening event", trace -> {
            var unknown = event(trace, FinalAnswerStatus.TYPE).deepCopy();
            unknown.put("type", "UNRECOGNIZED");
            events(trace).insert(index(trace, FinalAnswerStatus.TYPE), unknown);
        });
        cases.put("verification after final status", trace -> {
            var verification = event(trace, "VERIFICATION_COMPLETED").deepCopy();
            events(trace).insert(index(trace, "OUTCOME_RENDERED"), verification);
        });
        for (var entry : cases.entrySet()) {
            ObjectNode modified = original.deepCopy();
            entry.getValue().accept(modified);
            assertEquals(QualificationRecords.AnswerOrigin.NONE,
                    origin(answer, modified), entry.getKey());
        }
    }

    @Test
    void validStatusDoesNotMakeALengthLimitedProviderResponseEligible() throws Exception {
        var captured = ProviderCanonicalAnswerLineageFixture.run(
                temp.resolve("workspace"), temp.resolve("store"), "length");
        var status = FinalAnswerStatus.decode(captured.trace());
        assertTrue(status.available());
        assertEquals(FinalAnswerStatus.Delivery.LIMITED, status.delivery());
        assertTrue(status.causes().contains(FinalAnswerStatus.Cause.INCOMPLETE_PROVIDER_GENERATION));
        assertTrue(captured.trace().events().stream().anyMatch(event -> "GENERATION_RESPONSE_CAPTURED".equals(event.type())
                && "PROVIDER_LENGTH".equals(event.data().get("completion"))));
        assertTrue(captured.persistedAnswer().contains(dev.talos.runtime.IncompleteGenerationPresentation.NOTICE));
        assertTrue(captured.displayText().contains(dev.talos.runtime.IncompleteGenerationPresentation.NOTICE));
        assertEquals(QualificationRecords.AnswerOrigin.OPERATOR,
                origin(answer(captured), (ObjectNode) QualificationJson.tree(captured.trace())));
        assertEquals(QualificationRecords.AnswerOrigin.NONE,
                origin("4\n".getBytes(StandardCharsets.UTF_8), (ObjectNode) QualificationJson.tree(captured.trace())));
    }

    private static byte[] answer(ProviderCanonicalAnswerLineageFixture.CapturedTurn captured) {
        return (captured.persistedAnswer() + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static QualificationRecords.AnswerOrigin origin(byte[] answer, ObjectNode trace) {
        return QualificationCausalOwnership.matchedAnswerOrigin(
                answer, QualificationJson.bytes(trace), QualificationProtocol.VERSION);
    }

    private static ArrayNode events(ObjectNode trace) {
        return (ArrayNode) trace.path("events");
    }

    private static int index(ObjectNode trace, String type) {
        for (int index = 0; index < events(trace).size(); index++) {
            if (type.equals(events(trace).get(index).path("type").asText())) return index;
        }
        throw new AssertionError("Required event is absent, " + type);
    }

    private static ObjectNode event(ObjectNode trace, String type) {
        return (ObjectNode) events(trace).get(index(trace, type));
    }

    private static ObjectNode status(ObjectNode trace) {
        return (ObjectNode) event(trace, FinalAnswerStatus.TYPE).path("data");
    }
}
