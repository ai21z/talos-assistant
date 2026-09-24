package dev.talos.runtime.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.runtime.task.AnswerResultRequirements;
import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static dev.talos.runtime.verification.NativeReadOnlyAnswerProposal.RelationKind;
import static org.junit.jupiter.api.Assertions.*;

class NativeRoleRelationReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode FIXTURES = fixtures();

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void allSixExactSavedVersionFourResponsesAreRejectedWithoutMigration(int index) {
        Saved saved = saved(index);
        var result = NativeReadOnlyAnswerProposal.assess(saved.request(), saved.raw(), saved.sources());

        assertEquals(4, saved.parsed().path("version").intValue());
        // The unchanged root shape is rejected before its version is evaluated.
        assertEquals("NATIVE_FIELDS_INVALID", result.components().components().getFirst().reason());
        assertFalse(result.components().complete());
        assertEquals(1, result.components().components().size());
        assertFalse(result.components().components().getFirst().met());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void explicitEnvelopeRebindingPreservesSelectionsAndLeavesHistoricalMissingDecisionsAbsent(int index) {
        Saved saved = saved(index);
        var proposal = rebind(saved);
        var result = NativeReadOnlyAnswerProposal.assess(saved.request(), proposal.toString(), saved.sources());

        assertEquals(saved.parsed().path("results"), proposal.path("results"));
        assertEquals(saved.parsed().path("interpretations").size(), proposal.path("interpretationDecisions").size());
        proposal.path("interpretationDecisions").forEach(value -> assertNotEquals("unsupported", value.asText()));
        assertEquals(saved.context().equals("C3") ? 6 : saved.context().equals("C2") ? 5 : 4,
                NativeReadOnlyAnswerProposal.catalog(saved.request(), saved.sources()).requirements().slots().size());
        if (index == 0) {
            assertEquals("f0", proposal.path("results").get(0).path("formulaId").asText());
            assertEquals("NATIVE_RESULT_PAYLOAD_INVALID", result.components().components().getFirst().reason());
            assertFalse(result.components().components().getFirst().met());
            assertFalse(result.components().complete());
            assertEquals(3, result.components().components().stream().filter(ReadOnlyAnswerComponents.Component::met).count());
            assertTrue(result.answer().contains("`22.2`"));
        } else if (saved.context().equals("C2")) {
            assertFalse(result.components().complete());
            assertFalse(result.components().requestCovered());
            assertEquals(4, result.components().components().stream().filter(item -> item.kind().equals("UNKNOWN") && !item.met()).count());
            assertEquals(index == 1 ? 3 : 4, result.components().components().stream()
                    .filter(item -> item.interpretationDecision() == ReadOnlyAnswerInterpretation.Decision.MISSING).count());
            assertFalse(result.components().components().stream()
                    .anyMatch(item -> item.interpretationDecision() == ReadOnlyAnswerInterpretation.Decision.UNSUPPORTED));
            assertEquals(index == 1 ? 1 : 0, observations(result).size());
            assertTrue(result.answer().contains("`22.2`"));
            if (index == 1) {
                assertTrue(observations(result).getFirst().text().contains("diagnosis source observation"));
                assertFalse(observations(result).getFirst().text().contains("selector correction source observation"));
            }
        } else {
            assertTrue(result.components().complete(), result.answer());
            assertTrue(result.components().deliveredIntact(result.answer()));
            if (saved.context().equals("C3")) {
                assertTrue(result.answer().contains("`30.0`"));
                assertTrue(result.answer().contains("`14.5`"));
                assertTrue(result.answer().contains("disagree"));
                assertEquals(2, result.components().components().stream().filter(item -> item.kind().equals("CALCULATION") && item.met()).count());
                assertEquals(1, result.components().components().stream().filter(item -> item.kind().equals("COMPARISON") && item.met()).count());
            }
        }
        assertTrue(result.answer().endsWith(ReadOnlyAnswerComponents.FOOTER));
    }

    @Test
    void aSeparatelyAuthoredCorrectPayloadIsNotReportedAsRepairingTheSavedResponse() {
        Saved saved = saved(0);
        var original = rebind(saved);
        var synthetic = original.deepCopy();
        var diagnosis = (ObjectNode) synthetic.path("results").get(0);
        diagnosis.putNull("formulaId");
        diagnosis.putArray("bindingIds");

        assertNotEquals(original.path("results"), synthetic.path("results"));
        assertFalse(NativeReadOnlyAnswerProposal.assess(saved.request(), original.toString(), saved.sources()).components().complete());
        assertTrue(NativeReadOnlyAnswerProposal.assess(saved.request(), synthetic.toString(), saved.sources()).components().complete());
        assertEquals("f0", saved.parsed().path("results").get(0).path("formulaId").asText());
    }

    @Test
    void twoSyntheticHistoricalSelectionsForOneRegionCannotBeSilentlyDroppedDuringRebinding() {
        Saved saved = saved(1);
        var conflicting = saved.parsed().deepCopy();
        ((ArrayNode) conflicting.path("interpretations")).add(conflicting.path("interpretations").get(0).deepCopy());
        Saved synthetic = new Saved(saved.id(), saved.context(), saved.request(), saved.raw(), conflicting, saved.sources());

        assertEquals("UNREPRESENTABLE_HISTORICAL_INTERPRETATIONS",
                assertThrows(IllegalArgumentException.class, () -> rebind(synthetic)).getMessage());
        assertEquals(1, saved.parsed().path("interpretations").size());
        assertEquals(2, conflicting.path("interpretations").size());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void separatelySelectedThreeTypedObservationsImproveUsefulnessButNeverCompleteUnknownRegions(int index) {
        Saved saved = saved(index);
        var catalog = NativeReadOnlyAnswerProposal.catalog(saved.request(), saved.sources());
        var proposal = rebind(saved);
        var selections = proposal.putObject("interpretationDecisions");
        catalog.requirements().slots().stream().filter(slot -> slot.role() == AnswerResultRequirements.Role.UNKNOWN)
                .forEach(slot -> selections.put(slot.id(), "unsupported"));
        select(selections, catalog, "result-2-71-121", RelationKind.DIAGNOSIS);
        select(selections, catalog, "result-3-123-164", RelationKind.SELECTOR);
        select(selections, catalog, "result-5-209-254", RelationKind.SCRIPT_DISTINCTION);

        var result = NativeReadOnlyAnswerProposal.assess(saved.request(), proposal.toString(), saved.sources());

        assertEquals(saved.parsed().path("results"), proposal.path("results"));
        assertEquals(3, observations(result).size());
        assertTrue(observations(result).stream().allMatch(item -> item.support() == ReadOnlyAnswerComponents.Support.ATTRIBUTED_INTERPRETATION));
        assertTrue(observations(result).get(0).text().contains("lookup would be null"));
        assertTrue(observations(result).get(1).text().contains("`#calc` should be replaced by `#calculate`"));
        assertTrue(observations(result).get(2).text().contains("`index.html` links `script.js`"));
        assertTrue(observations(result).get(2).text().contains("`scripts.js`"));
        assertTrue(result.answer().contains("`22.2`"));
        assertFalse(result.components().requestCovered());
        assertFalse(result.components().complete());
        assertEquals(4, result.components().components().stream().filter(item -> item.kind().equals("UNKNOWN") && !item.met()).count());
        assertTrue(result.answer().contains("A complete interpretation of the request was not established."));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void everyOmittedResultOfACompleteMultiresultFixtureRemainsExplicitlyUnmet(int omitted) {
        Saved saved = saved(2);
        var proposal = rebind(saved);
        String missing = proposal.path("results").get(omitted).path("slotId").asText();
        ((ArrayNode) proposal.path("results")).remove(omitted);
        var result = NativeReadOnlyAnswerProposal.assess(saved.request(), proposal.toString(), saved.sources());

        assertFalse(result.components().complete());
        var component = result.components().components().stream().filter(item -> item.requirementId().equals(missing)).findFirst().orElseThrow();
        assertFalse(component.met());
        assertEquals("NATIVE_REQUIRED_RESULT_MISSING", component.reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"README.md", "index.html", "script.js", "scripts.js"})
    void savedChoicesDoNotSurviveChangedRetainedSourceBytes(String path) {
        Saved saved = saved(2);
        var proposal = rebind(saved);
        List<Source> changed = saved.sources().stream().map(source -> source.path().equals(path)
                ? source(saved.id(), path, source.text() + "\n", 1) : source).toList();
        var result = NativeReadOnlyAnswerProposal.assess(saved.request(), proposal.toString(), changed);

        assertEquals("NATIVE_CATALOG_MISMATCH", result.components().components().getFirst().reason());
        assertFalse(result.components().complete());
    }

    @Test
    void retainedBytesWithANewObservationIdentityCannotReviveASavedCatalog() {
        Saved saved = saved(2);
        var proposal = rebind(saved);
        List<Source> changed = saved.sources().stream().map(source -> source(saved.id(), source.path(), source.text(), 2)).toList();

        var result = NativeReadOnlyAnswerProposal.assess(saved.request(), proposal.toString(), changed);

        assertEquals("NATIVE_CATALOG_MISMATCH", result.components().components().getFirst().reason());
        assertFalse(result.components().complete());
    }

    @Test
    void aRelationForAnAuthenticSecondButtonCannotBindTheSelectedFirstButton() {
        Saved saved = saved(1);
        String request = "Read README.md, index.html, script.js and scripts.js. "
                + "Explain exactly why the Calculate button is broken. Explain exactly why the Reset button is broken.";
        List<Source> sources = saved.sources().stream().filter(source -> !source.path().equals("notes.md"))
                .map(source -> source.path().equals("index.html") ? source("two-targets", source.path(),
                        source.text().replace("<p id=\"result\">", "<button id=\"reset\">Reset</button><p id=\"result\">"), 1) : source).toList();
        var catalog = NativeReadOnlyAnswerProposal.catalog(request, sources);
        assertEquals(2, catalog.targets().size());
        var first = catalog.targets().stream().filter(target -> target.buttonId().equals("calculate")).findFirst().orElseThrow();
        var second = catalog.targets().stream().filter(target -> target.buttonId().equals("reset")).findFirst().orElseThrow();
        var other = catalog.relations().stream().filter(relation -> relation.targetId().equals(second.id())
                && relation.kind() == RelationKind.DIAGNOSIS).findFirst().orElseThrow();
        var proposal = NativeAnswerProposalTestSupport.proposal(request, sources);
        proposal.put("targetId", first.id());
        ((ObjectNode) proposal.path("interpretationDecisions")).put(other.regionId(), other.id());

        var result = NativeReadOnlyAnswerProposal.assess(request, proposal.toString(), sources);

        assertEquals("NATIVE_INTERPRETATION_BINDING_INVALID", result.components().components().getFirst().reason());
        assertTrue(observations(result).isEmpty());
        assertFalse(result.components().complete());
    }

    private static ObjectNode rebind(Saved saved) {
        var catalog = NativeReadOnlyAnswerProposal.catalog(saved.request(), saved.sources());
        assertSameHistoricalNumericBindings(saved, catalog);
        ObjectNode proposal = saved.parsed().deepCopy();
        proposal.put("version", 6).put("catalogId", catalog.id());
        proposal.remove("interpretations");
        var decisions = proposal.putObject("interpretationDecisions");
        assertEquals("calculate", catalog.targets().stream().filter(target -> target.id().equals(proposal.path("targetId").asText()))
                .findFirst().orElseThrow().buttonId());
        for (JsonNode interpretation : saved.parsed().path("interpretations")) {
            assertEquals("d0", interpretation.path("relationId").asText());
            assertEquals("result-2-71-121", interpretation.path("regionId").asText());
            if (decisions.has(interpretation.path("regionId").asText()))
                throw new IllegalArgumentException("UNREPRESENTABLE_HISTORICAL_INTERPRETATIONS");
            var relation = catalog.relations().stream().filter(item -> item.kind() == RelationKind.DIAGNOSIS
                    && item.regionId().equals(interpretation.path("regionId").asText())
                    && item.targetId().equals(proposal.path("targetId").asText())).toList();
            assertEquals(1, relation.size());
            decisions.put(interpretation.path("regionId").asText(), relation.getFirst().id());
        }
        assertEquals(saved.parsed().path("results"), proposal.path("results"));
        return proposal;
    }

    private static void assertSameHistoricalNumericBindings(Saved saved, NativeReadOnlyAnswerProposal.Catalog catalog) {
        boolean area = saved.context().equals("C3");
        var definition = catalog.formulas().stream().filter(formula -> formula.id().equals("f0")).findFirst().orElseThrow();
        assertEquals(ReadOnlyAnswerProposal.FormulaOrigin.SOURCE, definition.origin());
        assertEquals("s0", definition.sourceId());
        assertEquals(area ? "span multiplied by rise" : "weight divided by height squared", definition.expression());
        assertEquals(definition.expression(), saved.sources().getFirst().text().substring(definition.span().start(), definition.span().end()));
        var calculation = catalog.requirements().slots().stream().filter(slot -> slot.role() == AnswerResultRequirements.Role.SOURCE_DEFINITION).findFirst().orElseThrow();
        binding(catalog, saved.request(), "b0", calculation.id(), "f0", area ? "rise" : "height", area ? "rise 2.5 m" : "1.8 m");
        binding(catalog, saved.request(), "b1", calculation.id(), "f0", area ? "span" : "weight", area ? "span 12 m" : "72 kg");
        if (area) {
            var implementation = catalog.formulas().stream().filter(formula -> formula.id().equals("f1")).findFirst().orElseThrow();
            assertEquals(ReadOnlyAnswerProposal.FormulaOrigin.IMPLEMENTATION, implementation.origin());
            assertEquals("s2", implementation.sourceId());
            assertEquals("((span+rise))", implementation.expression());
            var slot = catalog.requirements().slots().stream().filter(item -> item.role() == AnswerResultRequirements.Role.IMPLEMENTATION).findFirst().orElseThrow();
            binding(catalog, saved.request(), "b2", slot.id(), "f1", "rise", "rise 2.5 m");
            binding(catalog, saved.request(), "b3", slot.id(), "f1", "span", "span 12 m");
        }
    }

    private static void binding(NativeReadOnlyAnswerProposal.Catalog catalog, String request, String id,
                                String slot, String formula, String variable, String text) {
        var binding = catalog.bindings().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
        assertEquals(slot, binding.slotId());
        assertEquals(formula, binding.formulaId());
        assertEquals(variable, binding.variable());
        assertEquals(variable, binding.inputId());
        assertEquals(text, request.substring(binding.span().start(), binding.span().end()));
    }

    private static void select(ObjectNode selections, NativeReadOnlyAnswerProposal.Catalog catalog, String region, RelationKind kind) {
        var choices = catalog.relations().stream().filter(item -> item.regionId().equals(region) && item.kind() == kind
                && item.targetId().equals("t0")).toList();
        assertEquals(1, choices.size());
        selections.put(region, choices.getFirst().id());
    }

    private static List<ReadOnlyAnswerComponents.Component> observations(ReadOnlyWebDiagnosticResult result) {
        return result.components().components().stream().filter(item -> item.kind().equals("OBSERVATION")).toList();
    }

    private record Saved(String id, String context, String request, String raw, ObjectNode parsed, List<Source> sources) {}

    private static Saved saved(int index) {
        try {
            assertEquals(6, FIXTURES.path("captures").size());
            JsonNode capture = FIXTURES.path("captures").get(index);
            String id = capture.path("id").asText();
            String contextId = capture.path("context").asText();
            JsonNode context = FIXTURES.path("contexts").path(contextId);
            String raw = capture.path("response").asText();
            assertEquals(capture.path("bytes").intValue(), raw.getBytes(StandardCharsets.UTF_8).length);
            assertEquals(capture.path("sha256").asText(), ReadOnlyWebDiagnosticResult.textHash(raw));
            var sources = new ArrayList<Source>();
            for (int i = 0; i < context.path("paths").size(); i++) {
                String path = context.path("paths").get(i).asText();
                JsonNode fixture = FIXTURES.path("sources").path(context.path("sources").get(i).asText());
                String text = fixture.path("text").asText();
                assertEquals(fixture.path("sha256").asText(), ReadOnlyWebDiagnosticResult.textHash(text));
                sources.add(source(id, path, text, 1));
            }
            return new Saved(id, contextId, context.path("request").asText(), raw, (ObjectNode) JSON.readTree(raw), List.copyOf(sources));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static Source source(String replay, String path, String text, long observation) {
        return new Source(path, text, UUID.nameUUIDFromBytes(("inert-replay\n" + replay + "\n" + path)
                .getBytes(StandardCharsets.UTF_8)).toString(), observation, ReadOnlyWebDiagnosticResult.textHash(text), "numbered-read-lf-v1");
    }

    private static JsonNode fixtures() {
        try (var stream = NativeRoleRelationReplayTest.class.getResourceAsStream("native-v4-saved-responses.json")) {
            assertNotNull(stream);
            return JSON.readTree(stream);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
