package dev.talos.runtime.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.runtime.task.AnswerResultRequirements;
import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NativeInterpretationContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String READ = "Read guide.md, index.html, engine.js and spare.js. ";
    private static final String REQUEST = READ + "Please explain exactly why the Compute button is broken. "
            + "Give the correct selector. Give the flow according to guide.md for volume 24 L and elapsed 6 s.";
    private static final String HTML = """
            <!doctype html><html><body>
            <label>Volume (L)<input id="volume" value="24"></label>
            <label>Elapsed (s)<input id="elapsed" value="6"></label>
            <button id="compute">Compute</button><p id="result"></p><script src="engine.js"></script>
            </body></html>
            """;
    private static final String SCRIPT = """
            const button = document.querySelector('#missing');
            button.addEventListener('click', () => {
              const volume = Number(document.querySelector('#volume').value);
              const elapsed = Number(document.querySelector('#elapsed').value);
              document.querySelector('#result').textContent = (volume + elapsed).toFixed(2);
            });
            """;

    @Test
    void wireSchemaIsClosedVersionSixWithOneRequiredDecisionPerUnknownRegion() throws Exception {
        var schema = JSON.readTree(NativeReadOnlyAnswerProposal.responseSchema(REQUEST, sources()));
        assertEquals(6, schema.path("properties").path("version").path("enum").get(0).intValue());
        var extra = schema.path("properties").path("interpretationDecisions");
        var unknown = AnswerResultRequirements.fromRequest(REQUEST).slots().stream()
                .filter(slot -> slot.role() == AnswerResultRequirements.Role.UNKNOWN).toList();
        assertEquals("object", extra.path("type").asText());
        assertFalse(extra.path("additionalProperties").booleanValue());
        assertEquals(unknown.size(), extra.path("properties").size());
        assertEquals(unknown.size(), extra.path("required").size());
        for (var slot : unknown) {
            assertTrue(java.util.stream.StreamSupport.stream(extra.path("required").spliterator(), false)
                    .anyMatch(item -> item.asText().equals(slot.id())));
            assertEquals("unsupported", extra.path("properties").path(slot.id()).path("enum").get(0).asText());
        }
        assertFalse(schema.toString().contains("proposedRole"));
        assertFalse(schema.toString().contains("requestQuote"));
        assertTrue(NativeReadOnlyAnswerProposal.instructions(REQUEST, sources()).contains("Unknown regions remain unresolved"));
    }

    @Test
    void anInterpretationAddsAnObservationWithoutChangingAnyOriginalSlot() throws Exception {
        var original = AnswerResultRequirements.fromRequest(REQUEST).slots();
        var candidate = selected(REQUEST);
        var answer = assess(REQUEST, candidate);
        assertEquals(original, AnswerResultRequirements.fromRequest(REQUEST).slots());
        assertEquals(original.size() + 1, answer.components().components().size());
        assertEquals("OBSERVATION", answer.components().components().getLast().kind());
        assertTrue(answer.components().components().getLast().met());
        assertTrue(answer.answer().contains("`4.00`"));
        assertTrue(answer.answer().contains("lookup would be null"));
        assertFalse(answer.components().requestCovered());
        assertFalse(answer.components().complete());
    }

    @Test
    void explicitUnsupportedDecisionsPreserveTheExistingLimitedAnswer() {
        var answer = assess(REQUEST, NativeAnswerProposalTestSupport.proposal(REQUEST, sources()));
        assertFalse(answer.components().components().stream().anyMatch(item -> item.kind().equals("OBSERVATION")));
        assertTrue(answer.components().components().stream().filter(item -> item.kind().equals("UNKNOWN"))
                .allMatch(item -> item.interpretationDecision() == ReadOnlyAnswerInterpretation.Decision.UNSUPPORTED));
        assertFalse(answer.components().complete());
        assertTrue(answer.answer().contains("`4.00`"));
    }

    @Test
    void missingDecisionIsNotReclassifiedAsExplicitUnsupported() {
        var candidate = NativeAnswerProposalTestSupport.proposal(REQUEST, sources());
        decisions(candidate).remove(unknownRegion(REQUEST));
        var answer = assess(REQUEST, candidate);

        assertEquals(ReadOnlyAnswerInterpretation.Decision.MISSING, answer.components().components().stream()
                .filter(item -> item.requirementId().equals(unknownRegion(REQUEST))).findFirst().orElseThrow().interpretationDecision());
        assertFalse(answer.components().complete());
        assertTrue(answer.answer().contains("`4.00`"));
    }

    @Test
    void aSupplementCannotCoverAnOmittedKnownCalculation() throws Exception {
        var candidate = selected(REQUEST);
        var slot = AnswerResultRequirements.fromRequest(REQUEST).slots().stream().filter(AnswerResultRequirements.Slot::calculation).findFirst().orElseThrow();
        var entries = candidate.putArray("results");
        NativeAnswerProposalTestSupport.proposal(REQUEST, sources()).path("results").forEach(item -> {
            if (!item.path("slotId").asText().equals(slot.id())) entries.add(item);
        });
        var answer = assess(REQUEST, candidate);
        assertFalse(answer.components().complete());
        assertTrue(answer.components().components().stream().anyMatch(item -> item.kind().equals("CALCULATION") && !item.met()));
        assertTrue(answer.components().components().getLast().met());
    }

    @ParameterizedTest
    @ValueSource(strings = {"role", "formulaId", "bindingIds", "answer", "permission", "complete", "ignored", "tools"})
    void supplementalAuthorityAndResultFieldsAreRejected(String field) throws Exception {
        var candidate = selected(REQUEST);
        decisions(candidate).putObject(unknownRegion(REQUEST)).put(field, "forbidden");
        rejected(assess(REQUEST, candidate));
    }

    @Test
    void knownRegionReplacementStaleRelationsAndDuplicateSelectionsAreRejected() throws Exception {
        var catalog = NativeReadOnlyAnswerProposal.catalog(REQUEST, sources());
        var known = catalog.requirements().slots().stream().filter(slot -> slot.role() != AnswerResultRequirements.Role.UNKNOWN).findFirst().orElseThrow();
        for (String region : List.of(known.id(), "missing-region")) {
            var candidate = selected(REQUEST);
            decisions(candidate).put(region, "unsupported");
            rejected(assess(REQUEST, candidate));
        }
        var candidate = selected(REQUEST);
        decisions(candidate).put(unknownRegion(REQUEST), "missing-relation");
        rejected(assess(REQUEST, candidate));
        candidate = selected(REQUEST);
        String duplicate = candidate.toString().replace("\"" + unknownRegion(REQUEST) + "\":",
                "\"" + unknownRegion(REQUEST) + "\":\"unsupported\",\"" + unknownRegion(REQUEST) + "\":");
        rejected(NativeReadOnlyAnswerProposal.assess(REQUEST, duplicate, sources()));
        candidate = selected(REQUEST);
        rejected(assess(REQUEST + " ", candidate));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Do not diagnose the Compute button.", "If the Compute button fails, explain why.",
            "\"Explain why the Compute button fails\".", "Suppose the Compute button is broken."})
    void excludedRegionsHaveNoSelectableDiagnosticRelation(String region) throws Exception {
        String request = READ + region;
        var catalog = NativeReadOnlyAnswerProposal.catalog(request, sources());
        assertTrue(catalog.relations().isEmpty());
        var candidate = NativeAnswerProposalTestSupport.proposal(request, sources());
        decisions(candidate).put(catalog.requirements().slots().getFirst().id(), "d0");
        rejected(assess(request, candidate));
        var choices = JSON.readTree(NativeReadOnlyAnswerProposal.responseSchema(request, sources()))
                .path("properties").path("interpretationDecisions").path("properties");
        choices.forEach(choice -> {
            assertEquals(1, choice.path("enum").size());
            assertEquals("unsupported", choice.path("enum").get(0).asText());
        });
    }

    @Test
    void anAppearanceQuestionCannotBecomeAnAcceptedFailureExplanation() throws Exception {
        String request = READ + "Explain why the Compute button is red.";
        var answer = assess(request, selected(request));
        assertFalse(answer.components().complete());
        assertFalse(answer.components().requestCovered());
        assertTrue(answer.components().components().stream().anyMatch(item -> item.kind().equals("UNKNOWN") && !item.met()));
        assertFalse(answer.components().components().stream().anyMatch(item -> item.kind().equals("DIAGNOSIS")));
        assertTrue(answer.answer().contains("Its relevance to that region was not established."));
    }

    @Test
    void noSupplementCanDisplaceAFullLedger() throws Exception {
        String request = READ + "Explain exactly why the Compute button is broken. ".repeat(32);
        assertEquals(32, AnswerResultRequirements.fromRequest(request).slots().size());
        assertEquals("NATIVE_INTERPRETATION_BOUND", assertThrows(ReadOnlyAnswerProposal.Rejected.class,
                () -> NativeReadOnlyAnswerProposal.catalog(request, sources())).reason);
        assertEquals("NATIVE_INTERPRETATION_BOUND", assertThrows(ReadOnlyAnswerProposal.Rejected.class,
                () -> NativeReadOnlyAnswerProposal.responseSchema(request, sources())).reason);
    }

    @Test
    void versionThreeAndMalformedDecisionContainersAreRejected() throws Exception {
        var candidate = selected(REQUEST);
        candidate.put("version", 3);
        rejected(assess(REQUEST, candidate));
        candidate = selected(REQUEST);
        candidate.remove("interpretationDecisions");
        rejected(assess(REQUEST, candidate));
        candidate = selected(REQUEST);
        candidate.putNull("interpretationDecisions");
        rejected(assess(REQUEST, candidate));
        candidate = selected(REQUEST);
        String valid = candidate.toString();
        String region = unknownRegion(REQUEST);
        rejected(NativeReadOnlyAnswerProposal.assess(REQUEST,
                valid.replace("\"" + region + "\":", "\"" + region + "\":\"unsupported\",\"" + region + "\":"), sources()));
    }

    private static ObjectNode decisions(ObjectNode candidate) {
        return (ObjectNode) candidate.path("interpretationDecisions");
    }

    private static String unknownRegion(String request) {
        return AnswerResultRequirements.fromRequest(request).slots().stream()
                .filter(slot -> slot.role() == AnswerResultRequirements.Role.UNKNOWN).findFirst().orElseThrow().id();
    }

    private static ObjectNode selected(String request) throws Exception {
        return (ObjectNode) JSON.readTree(NativeAnswerProposalTestSupport.interpretedCandidate(request, sources()));
    }

    private static ReadOnlyWebDiagnosticResult assess(String request, ObjectNode candidate) {
        return NativeReadOnlyAnswerProposal.assess(request, candidate.toString(), sources());
    }

    private static void rejected(ReadOnlyWebDiagnosticResult result) {
        assertTrue(result.assessment().limited(), result.answer());
        assertFalse(result.components().complete());
        assertFalse(result.components().components().stream().anyMatch(item -> item.kind().equals("OBSERVATION")));
    }

    private static List<Source> sources() {
        return List.of(source("guide.md", "Flow is volume divided by elapsed.\n"), source("index.html", HTML),
                source("engine.js", SCRIPT), source("spare.js", "const retained = true;\n"));
    }

    private static Source source(String path, String text) {
        return new Source(path, text, UUID.nameUUIDFromBytes((path + "\n" + text).getBytes(StandardCharsets.UTF_8)).toString(),
                1, ReadOnlyWebDiagnosticResult.textHash(text), "numbered-read-lf-v1");
    }
}
