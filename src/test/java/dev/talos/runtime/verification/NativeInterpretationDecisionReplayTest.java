package dev.talos.runtime.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.runtime.task.AnswerResultRequirements.Role;
import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.talos.runtime.verification.ReadOnlyAnswerInterpretation.Decision;
import static org.junit.jupiter.api.Assertions.*;

class NativeInterpretationDecisionReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode SAVED = resource("native-v5-c2-saved-responses.json");
    private static final JsonNode SOURCE_FIXTURES = resource(SAVED.path("contextFixture").asText());

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void exactHistoricalVersionFiveResponsesAreRejectedWithoutReinterpretation(int index) {
        var fixture = fixture(index);
        var answer = NativeReadOnlyAnswerProposal.assess(fixture.request(), fixture.raw(), fixture.sources());

        assertEquals(5, fixture.proposal().path("version").intValue());
        assertTrue(answer.assessment().limited());
        assertFalse(answer.components().complete());
        assertEquals(1, answer.components().components().size());
        assertFalse(answer.components().components().getFirst().met());
        assertFalse(answer.answer().contains("`22.2`"));
    }

    @Test
    void explicitlyMappedEmptyHistoricalSelectionsRemainMissingNotUnsupported() {
        var fixture = fixture(1);
        var proposal = envelope(fixture);
        assertTrue(fixture.proposal().path("interpretations").isEmpty());
        proposal.putObject("interpretationDecisions");

        var answer = NativeReadOnlyAnswerProposal.assess(fixture.request(), proposal.toString(), fixture.sources());

        assertEquals(fixture.proposal().path("results"), proposal.path("results"));
        assertEquals(4, unknown(answer).size());
        assertTrue(unknown(answer).stream().allMatch(item -> item.interpretationDecision() == Decision.MISSING));
        assertTrue(unknown(answer).stream().allMatch(item -> item.reason().equals("NATIVE_INTERPRETATION_DECISION_MISSING")));
        assertTrue(observations(answer).isEmpty());
        assertTrue(answer.answer().contains("`22.2`"));
        incomplete(answer);
    }

    @Test
    void losslessGroupingOfEightHistoricalSelectionsIsRejectedRatherThanTruncated() {
        var fixture = fixture(0);
        var original = fixture.proposal().path("interpretations");
        assertEquals(8, original.size());
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        original.forEach(item -> grouped.computeIfAbsent(item.path("regionId").asText(), ignored -> new ArrayList<>())
                .add(item.path("relationId").asText()));
        assertEquals(List.of(3, 3, 2), grouped.values().stream().map(List::size).toList());
        assertFalse(grouped.containsKey("result-5-209-254"));
        var proposal = envelope(fixture);
        var decisions = proposal.putObject("interpretationDecisions");
        var catalog = NativeReadOnlyAnswerProposal.catalog(fixture.request(), fixture.sources());
        for (var region : grouped.entrySet()) {
            ArrayNode selections = decisions.putArray(region.getKey());
            for (String id : region.getValue()) {
                var relation = catalog.relations().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
                assertEquals(region.getKey(), relation.regionId());
                assertEquals("t0", relation.targetId());
                selections.add(id);
            }
        }
        assertEquals(8, java.util.stream.StreamSupport.stream(decisions.spliterator(), false).mapToInt(JsonNode::size).sum());
        assertEquals(fixture.proposal().path("results"), proposal.path("results"));

        var answer = NativeReadOnlyAnswerProposal.assess(fixture.request(), proposal.toString(), fixture.sources());

        assertTrue(answer.assessment().limited());
        assertEquals(1, answer.components().components().size());
        assertFalse(answer.components().components().getFirst().met());
        assertTrue(observations(answer).isEmpty());
        assertEquals(8, fixture.proposal().path("interpretations").size());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void independentlyAuthoredSemanticSelectionsDoNotConvertUnknownMeaningIntoCompleteCoverage(int index) {
        var fixture = fixture(index);
        var proposal = envelope(fixture);
        var catalog = NativeReadOnlyAnswerProposal.catalog(fixture.request(), fixture.sources());
        var decisions = proposal.putObject("interpretationDecisions");
        decisions.put("result-1-0-69", "unsupported");
        select(decisions, catalog, "result-2-71-121", NativeReadOnlyAnswerProposal.RelationKind.DIAGNOSIS);
        select(decisions, catalog, "result-3-123-164", NativeReadOnlyAnswerProposal.RelationKind.SELECTOR);
        select(decisions, catalog, "result-5-209-254", NativeReadOnlyAnswerProposal.RelationKind.SCRIPT_DISTINCTION);

        var answer = NativeReadOnlyAnswerProposal.assess(fixture.request(), proposal.toString(), fixture.sources());

        assertEquals(fixture.proposal().path("results"), proposal.path("results"));
        assertEquals(4, unknown(answer).size());
        assertEquals(1, unknown(answer).stream().filter(item -> item.interpretationDecision() == Decision.UNSUPPORTED).count());
        assertEquals(3, unknown(answer).stream().filter(item -> item.interpretationDecision() == Decision.SELECTED).count());
        assertEquals(3, observations(answer).size());
        assertTrue(observations(answer).stream().allMatch(ReadOnlyAnswerComponents.Component::met));
        assertTrue(answer.answer().contains("lookup would be null"));
        assertTrue(answer.answer().contains("`#calc` should be replaced by `#calculate`"));
        assertTrue(answer.answer().contains("`index.html` links `script.js`"));
        assertTrue(answer.answer().contains("`scripts.js`"));
        assertTrue(answer.answer().contains("`22.2`"));
        incomplete(answer);
    }

    @Test
    void independentlyAuthoredUnsupportedDecisionsAreAccountedForButDoNotPassUsefulness() {
        var fixture = fixture(1);
        var proposal = envelope(fixture);
        var catalog = NativeReadOnlyAnswerProposal.catalog(fixture.request(), fixture.sources());
        var decisions = proposal.putObject("interpretationDecisions");
        catalog.requirements().slots().stream().filter(slot -> slot.role() == Role.UNKNOWN)
                .forEach(slot -> decisions.put(slot.id(), "unsupported"));

        var answer = NativeReadOnlyAnswerProposal.assess(fixture.request(), proposal.toString(), fixture.sources());

        assertEquals(4, unknown(answer).size());
        assertTrue(unknown(answer).stream().allMatch(item -> item.interpretationDecision() == Decision.UNSUPPORTED));
        assertTrue(unknown(answer).stream().allMatch(item -> item.reason().equals("NATIVE_INTERPRETATION_UNSUPPORTED")));
        assertTrue(observations(answer).isEmpty());
        assertTrue(answer.answer().contains("`22.2`"));
        assertFalse(answer.answer().contains("lookup would be null"));
        assertFalse(answer.answer().contains("should be replaced by"));
        assertTrue(fixture.proposal().path("interpretations").isEmpty());
        incomplete(answer);
    }

    private static void select(ObjectNode decisions, NativeReadOnlyAnswerProposal.Catalog catalog, String region,
                               NativeReadOnlyAnswerProposal.RelationKind kind) {
        var matches = catalog.relations().stream().filter(item -> item.regionId().equals(region)
                && item.kind() == kind && item.targetId().equals("t0")).toList();
        assertEquals(1, matches.size());
        decisions.put(region, matches.getFirst().id());
    }

    private static ObjectNode envelope(Fixture fixture) {
        var catalog = NativeReadOnlyAnswerProposal.catalog(fixture.request(), fixture.sources());
        assertEquals(5, catalog.requirements().slots().size());
        assertEquals(4, catalog.requirements().slots().stream().filter(slot -> slot.role() == Role.UNKNOWN).count());
        assertEquals("calculate", catalog.targets().getFirst().buttonId());
        var formula = catalog.formulas().stream().filter(item -> item.id().equals("f0")).findFirst().orElseThrow();
        assertEquals(ReadOnlyAnswerProposal.FormulaOrigin.SOURCE, formula.origin());
        assertEquals("s0", formula.sourceId());
        assertEquals("weight divided by height squared", formula.expression());
        assertEquals(formula.expression(), fixture.sources().getFirst().text().substring(formula.span().start(), formula.span().end()));
        for (String id : List.of("b0", "b1")) {
            var binding = catalog.bindings().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
            assertEquals("result-4-166-203", binding.slotId());
            assertEquals("f0", binding.formulaId());
            assertEquals(id.equals("b0") ? "height" : "weight", binding.variable());
            assertEquals(binding.variable(), binding.inputId());
            assertEquals(id.equals("b0") ? "1.8 m" : "72 kg", fixture.request().substring(binding.span().start(), binding.span().end()));
        }
        var mapped = fixture.proposal().deepCopy();
        mapped.put("version", 6).put("catalogId", catalog.id());
        mapped.remove("interpretations");
        return mapped;
    }

    private static void incomplete(ReadOnlyWebDiagnosticResult answer) {
        assertFalse(answer.components().requestCovered());
        assertFalse(answer.components().complete());
        assertTrue(unknown(answer).stream().noneMatch(ReadOnlyAnswerComponents.Component::met));
        assertTrue(answer.answer().contains("A complete interpretation of the request was not established."));
        assertTrue(answer.answer().endsWith(ReadOnlyAnswerComponents.FOOTER));
    }

    private static List<ReadOnlyAnswerComponents.Component> unknown(ReadOnlyWebDiagnosticResult answer) {
        return answer.components().components().stream().filter(item -> item.kind().equals("UNKNOWN")).toList();
    }

    private static List<ReadOnlyAnswerComponents.Component> observations(ReadOnlyWebDiagnosticResult answer) {
        return answer.components().components().stream().filter(item -> item.kind().equals("OBSERVATION")).toList();
    }

    private record Fixture(String request, String raw, ObjectNode proposal, List<Source> sources) {}

    private static Fixture fixture(int index) {
        try {
            assertEquals(2, SAVED.path("captures").size());
            JsonNode capture = SAVED.path("captures").get(index);
            String raw = capture.path("response").asText();
            assertEquals(capture.path("bytes").intValue(), raw.getBytes(StandardCharsets.UTF_8).length);
            assertEquals(capture.path("sha256").asText(), ReadOnlyWebDiagnosticResult.textHash(raw));
            JsonNode context = SOURCE_FIXTURES.path("contexts").path(SAVED.path("context").asText());
            String request = context.path("request").asText();
            assertEquals(SAVED.path("requestSha256").asText(), ReadOnlyWebDiagnosticResult.textHash(request));
            List<Source> sources = new ArrayList<>();
            for (int i = 0; i < context.path("paths").size(); i++) {
                String path = context.path("paths").get(i).asText();
                JsonNode retained = SOURCE_FIXTURES.path("sources").path(context.path("sources").get(i).asText());
                String text = retained.path("text").asText();
                assertEquals(retained.path("sha256").asText(), ReadOnlyWebDiagnosticResult.textHash(text));
                sources.add(new Source(path, text, UUID.nameUUIDFromBytes(("decision-replay\n" + capture.path("id").asText()
                        + "\n" + path).getBytes(StandardCharsets.UTF_8)).toString(), 1,
                        ReadOnlyWebDiagnosticResult.textHash(text), "numbered-read-lf-v1"));
            }
            return new Fixture(request, raw, (ObjectNode) JSON.readTree(raw), List.copyOf(sources));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static JsonNode resource(String name) {
        try (var stream = NativeInterpretationDecisionReplayTest.class.getResourceAsStream(name)) {
            assertNotNull(stream);
            return JSON.readTree(stream);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
