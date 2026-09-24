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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static dev.talos.runtime.verification.ReadOnlyAnswerInterpretation.Decision;
import static org.junit.jupiter.api.Assertions.*;

class NativeInterpretationDecisionProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String READ = "Read guide.md, index.html, engine.js and spare.js. ";
    private static final String REQUEST = READ + "Explain exactly why the Compute button is broken. "
            + "Identify the selector that should be used. Explain how engine.js differs from spare.js. "
            + "Give the flow according to guide.md for volume 24 L and elapsed 6 s.";
    private static final String HTML = """
            <!doctype html><html><body>
            <label>Volume (L)<input id="volume" value="100"></label>
            <label>Elapsed (s)<input id="elapsed" value="20"></label>
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
    void generationSchemaRequiresExactlyEveryUnknownDecisionAndOnlyItsCatalogChoices() throws Exception {
        var catalog = catalog(REQUEST);
        JsonNode schema = JSON.readTree(NativeReadOnlyAnswerProposal.responseSchema(REQUEST, sources()));
        JsonNode decisionSchema = schema.at("/properties/interpretationDecisions");
        Set<String> expected = unknownIds(catalog);

        assertEquals(6, schema.at("/properties/version/enum/0").intValue());
        assertFalse(schema.path("properties").has("interpretations"));
        assertEquals("object", decisionSchema.path("type").asText());
        assertTrue(decisionSchema.path("additionalProperties").isBoolean());
        assertFalse(decisionSchema.path("additionalProperties").booleanValue());
        assertEquals(expected, names(decisionSchema.path("properties")));
        assertEquals(expected, strings(decisionSchema.path("required")));
        for (String region : expected) {
            var allowed = new HashSet<>(Set.of("unsupported"));
            catalog.relations().stream().filter(item -> item.regionId().equals(region)).forEach(item -> allowed.add(item.id()));
            assertEquals("string", decisionSchema.path("properties").path(region).path("type").asText());
            assertEquals(allowed, strings(decisionSchema.path("properties").path(region).path("enum")));
        }
        var candidate = unsupported(REQUEST);
        assertTrue(decisionSubsetAccepts(decisionSchema, candidate.path("interpretationDecisions")));
        decisions(candidate).remove(expected.iterator().next());
        assertFalse(decisionSubsetAccepts(decisionSchema, candidate.path("interpretationDecisions")));
    }

    @Test
    void oneMissingDecisionIsRetainedSeparatelyFromTwoExplicitUnsupportedDecisions() {
        var candidate = unsupported(REQUEST);
        String missing = unknownIds(catalog(REQUEST)).stream().sorted().findFirst().orElseThrow();
        decisions(candidate).remove(missing);

        var result = assess(REQUEST, candidate);

        assertEquals(3, unknown(result).size());
        assertEquals(1, unknown(result).stream().filter(item -> item.interpretationDecision() == Decision.MISSING).count());
        assertEquals(2, unknown(result).stream().filter(item -> item.interpretationDecision() == Decision.UNSUPPORTED).count());
        var absent = unknown(result).stream().filter(item -> item.requirementId().equals(missing)).findFirst().orElseThrow();
        assertEquals("NATIVE_INTERPRETATION_DECISION_MISSING", absent.reason());
        assertTrue(result.answer().contains("`4.00`"));
        assertFalse(result.components().complete());
        assertFalse(result.components().requestCovered());
    }

    @Test
    void selectedUnsupportedAndMissingRemainThreeDifferentUnmetDecisions() {
        var catalog = catalog(REQUEST);
        var regions = catalog.requirements().slots().stream().filter(slot -> slot.role() == Role.UNKNOWN).toList();
        var candidate = unsupported(REQUEST);
        var selected = catalog.relations().stream().filter(item -> item.regionId().equals(regions.getFirst().id())
                && item.kind() == NativeReadOnlyAnswerProposal.RelationKind.DIAGNOSIS).findFirst().orElseThrow();
        decisions(candidate).put(selected.regionId(), selected.id());
        decisions(candidate).remove(regions.getLast().id());

        var result = assess(REQUEST, candidate);

        assertEquals(Set.of(Decision.SELECTED, Decision.UNSUPPORTED, Decision.MISSING), unknown(result).stream()
                .map(ReadOnlyAnswerComponents.Component::interpretationDecision).collect(java.util.stream.Collectors.toSet()));
        assertTrue(unknown(result).stream().noneMatch(ReadOnlyAnswerComponents.Component::met));
        assertEquals(1, observations(result).size());
        assertTrue(observations(result).getFirst().met());
        assertTrue(result.answer().contains("`4.00`"));
        assertFalse(result.components().complete());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "true", "1", "[]", "{}", "\"unsupported \"", "\"MISSING\"", "\"missing-relation\""})
    void malformedOrInventedDecisionValuesCannotAcquireSupportedObservations(String encoded) throws Exception {
        var candidate = unsupported(REQUEST);
        String region = unknownIds(catalog(REQUEST)).iterator().next();
        decisions(candidate).set(region, JSON.readTree(encoded));

        globallyRejected(assess(REQUEST, candidate));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "\"unsupported\"", "1", "true"})
    void decisionContainerMustBeAnObject(String encoded) throws Exception {
        var candidate = unsupported(REQUEST);
        candidate.set("interpretationDecisions", JSON.readTree(encoded));

        globallyRejected(assess(REQUEST, candidate));
    }

    @Test
    void omittedDecisionContainerIsNotEquivalentToExplicitMissingRegionEntries() {
        var candidate = unsupported(REQUEST);
        candidate.remove("interpretationDecisions");

        globallyRejected(assess(REQUEST, candidate));
    }

    @Test
    void additionalOrKnownDecisionKeysAreRejectedEvenWithUnsupportedValues() {
        var catalog = catalog(REQUEST);
        String known = catalog.requirements().slots().stream().filter(slot -> slot.role() != Role.UNKNOWN).findFirst().orElseThrow().id();
        for (String key : List.of(known, "invented-region", "complete", "permission")) {
            var candidate = unsupported(REQUEST);
            decisions(candidate).put(key, "unsupported");
            globallyRejected(assess(REQUEST, candidate));
        }
    }

    @Test
    void duplicateJsonDecisionKeysAreNotOverwrittenDuringParsing() {
        var candidate = unsupported(REQUEST);
        String region = unknownIds(catalog(REQUEST)).iterator().next();
        String property = "\"" + region + "\":\"unsupported\"";
        String malformed = candidate.toString().replace(property, property + "," + property);
        assertNotEquals(candidate.toString(), malformed);

        globallyRejected(NativeReadOnlyAnswerProposal.assess(REQUEST, malformed, sources()));
    }

    @Test
    void authenticRelationIdsCannotBeMovedAcrossTheirOwningRegions() {
        var catalog = catalog(REQUEST);
        var regions = catalog.requirements().slots().stream().filter(slot -> slot.role() == Role.UNKNOWN).toList();
        var relation = catalog.relations().stream().filter(item -> item.regionId().equals(regions.getFirst().id())).findFirst().orElseThrow();
        var candidate = unsupported(REQUEST);
        decisions(candidate).put(regions.getLast().id(), relation.id());

        globallyRejected(assess(REQUEST, candidate));
    }

    @Test
    void authenticSecondTargetRelationCannotBeUsedWithTheFirstTarget() {
        String request = READ + "Explain exactly why the Compute button is broken. Explain exactly why the Reset button is broken.";
        List<Source> sources = sources().stream().map(source -> source.path().equals("index.html")
                ? source("index.html", source.text().replace("<p id=\"result\">", "<button id=\"reset\">Reset</button><p id=\"result\">")) : source).toList();
        var catalog = NativeReadOnlyAnswerProposal.catalog(request, sources);
        var first = catalog.targets().stream().filter(target -> target.buttonId().equals("compute")).findFirst().orElseThrow();
        var second = catalog.targets().stream().filter(target -> target.buttonId().equals("reset")).findFirst().orElseThrow();
        var other = catalog.relations().stream().filter(item -> item.targetId().equals(second.id())).findFirst().orElseThrow();
        var candidate = NativeAnswerProposalTestSupport.proposal(request, sources);
        candidate.put("targetId", first.id());
        decisions(candidate).put(other.regionId(), other.id());

        globallyRejected(NativeReadOnlyAnswerProposal.assess(request, candidate.toString(), sources));
    }

    @Test
    void changedRequestsAndChangedSourceEvidenceRejectStaleDecisions() {
        var candidate = unsupported(REQUEST);
        var relation = catalog(REQUEST).relations().getFirst();
        decisions(candidate).put(relation.regionId(), relation.id());
        globallyRejected(NativeReadOnlyAnswerProposal.assess(REQUEST + " ", candidate.toString(), sources()));
        for (String path : List.of("index.html", "engine.js", "spare.js")) {
            List<Source> changed = sources().stream().map(item -> item.path().equals(path) ? source(path, item.text() + "\n") : item).toList();
            globallyRejected(NativeReadOnlyAnswerProposal.assess(REQUEST, candidate.toString(), changed));
        }
    }

    @Test
    void anExcludedUnknownRegionRequiresAccountingButCannotSelectAnyRelation() throws Exception {
        String request = REQUEST + " If the Compute button fails, describe its behavior.";
        var catalog = catalog(request);
        var excluded = catalog.requirements().slots().getLast();
        assertEquals(Role.UNKNOWN, excluded.role());
        assertTrue(catalog.relations().stream().noneMatch(item -> item.regionId().equals(excluded.id())));
        JsonNode schema = JSON.readTree(NativeReadOnlyAnswerProposal.responseSchema(request, sources()));
        assertEquals(Set.of("unsupported"), strings(schema.at("/properties/interpretationDecisions/properties")
                .path(excluded.id()).path("enum")));
        var candidate = unsupported(request);
        assertEquals(Decision.UNSUPPORTED, unknown(assess(request, candidate)).stream()
                .filter(item -> item.requirementId().equals(excluded.id())).findFirst().orElseThrow().interpretationDecision());
        decisions(candidate).put(excluded.id(), catalog.relations().getFirst().id());
        globallyRejected(assess(request, candidate));
    }

    @Test
    void completeKnownLedgerNeedsNoInterpretationDecisions() {
        String request = READ + "Explain why the Compute button fails. Give the correct selector. "
                + "Give the flow according to guide.md for volume 24 L and elapsed 6 s. Distinguish the two JavaScript files.";
        var candidate = unsupported(request);
        assertTrue(decisions(candidate).isEmpty());
        assertTrue(assess(request, candidate).components().complete());
    }

    @Test
    void selectingEveryUnknownCannotRepairAnOmittedKnownCalculation() {
        var catalog = catalog(REQUEST);
        var candidate = unsupported(REQUEST);
        for (String region : unknownIds(catalog)) {
            var relation = catalog.relations().stream().filter(item -> item.regionId().equals(region)).findFirst().orElseThrow();
            decisions(candidate).put(region, relation.id());
        }
        String missing = catalog.requirements().slots().stream().filter(slot -> slot.role() == Role.SOURCE_DEFINITION).findFirst().orElseThrow().id();
        var results = (ArrayNode) candidate.path("results");
        for (int index = results.size() - 1; index >= 0; index--) if (results.get(index).path("slotId").asText().equals(missing)) results.remove(index);

        var outcome = assess(REQUEST, candidate);

        assertFalse(outcome.components().complete());
        assertTrue(outcome.components().components().stream().anyMatch(item -> item.requirementId().equals(missing)
                && !item.met() && item.reason().equals("NATIVE_REQUIRED_RESULT_MISSING")));
        assertFalse(outcome.answer().contains("`4.00`"));
    }

    @Test
    void selectingAnObservationCannotRepairAWrongCalculationRole() {
        var catalog = catalog(REQUEST);
        var candidate = unsupported(REQUEST);
        var relation = catalog.relations().getFirst();
        decisions(candidate).put(relation.regionId(), relation.id());
        var sourceSlot = catalog.requirements().slots().stream().filter(slot -> slot.role() == Role.SOURCE_DEFINITION).findFirst().orElseThrow();
        var implementation = catalog.formulas().stream().filter(formula -> formula.origin() == ReadOnlyAnswerProposal.FormulaOrigin.IMPLEMENTATION).findFirst().orElseThrow();
        for (JsonNode result : candidate.path("results")) if (result.path("slotId").asText().equals(sourceSlot.id()))
            ((ObjectNode) result).put("formulaId", implementation.id());

        var outcome = assess(REQUEST, candidate);

        assertFalse(outcome.components().complete());
        assertTrue(outcome.components().components().stream().anyMatch(item -> item.reason().equals("NATIVE_FORMULA_ROLE_MISMATCH")));
        assertFalse(outcome.answer().contains("`4.00`"));
    }

    @Test
    void moreThanEightSelectableRegionsAreRejectedBeforeGeneration() {
        String request = READ + "Explain exactly why the Compute button is broken. ".repeat(9);

        assertEquals("NATIVE_INTERPRETATION_BOUND", assertThrows(ReadOnlyAnswerProposal.Rejected.class,
                () -> NativeReadOnlyAnswerProposal.responseSchema(request, sources())).reason);
    }

    @Test
    void selectableObservationsCannotOverflowTheCombinedComponentBound() {
        String request = READ + "Explain exactly why the Compute button is broken. ".repeat(4)
                + "Give the flow according to guide.md for volume 24 L and elapsed 6 s. ".repeat(25);
        var ledger = dev.talos.runtime.task.AnswerResultRequirements.fromRequest(request);
        assertEquals(29, ledger.slots().size());
        assertEquals(4, ledger.slots().stream().filter(slot -> slot.role() == Role.UNKNOWN).count());

        assertEquals("NATIVE_INTERPRETATION_BOUND", assertThrows(ReadOnlyAnswerProposal.Rejected.class,
                () -> NativeReadOnlyAnswerProposal.responseSchema(request, sources())).reason);
    }

    // Only the closed decision object and enum subset is modeled, backend grammar behavior is not tested.
    private static boolean decisionSubsetAccepts(JsonNode schema, JsonNode value) {
        if (!value.isObject() || !names(value).equals(strings(schema.path("required")))) return false;
        for (String key : names(value)) if (!value.get(key).isTextual()
                || !strings(schema.path("properties").path(key).path("enum")).contains(value.get(key).asText())) return false;
        return true;
    }

    private static ObjectNode unsupported(String request) {
        var candidate = NativeAnswerProposalTestSupport.proposal(request, sources());
        var decisions = candidate.putObject("interpretationDecisions");
        unknownIds(catalog(request)).forEach(region -> decisions.put(region, "unsupported"));
        return candidate;
    }

    private static ObjectNode decisions(ObjectNode candidate) { return (ObjectNode) candidate.path("interpretationDecisions"); }
    private static NativeReadOnlyAnswerProposal.Catalog catalog(String request) { return NativeReadOnlyAnswerProposal.catalog(request, sources()); }
    private static ReadOnlyWebDiagnosticResult assess(String request, ObjectNode candidate) {
        return NativeReadOnlyAnswerProposal.assess(request, candidate.toString(), sources());
    }
    private static Set<String> unknownIds(NativeReadOnlyAnswerProposal.Catalog catalog) {
        return catalog.requirements().slots().stream().filter(slot -> slot.role() == Role.UNKNOWN)
                .map(dev.talos.runtime.task.AnswerResultRequirements.Slot::id).collect(java.util.stream.Collectors.toSet());
    }
    private static List<ReadOnlyAnswerComponents.Component> unknown(ReadOnlyWebDiagnosticResult result) {
        return result.components().components().stream().filter(item -> item.kind().equals("UNKNOWN")).toList();
    }
    private static List<ReadOnlyAnswerComponents.Component> observations(ReadOnlyWebDiagnosticResult result) {
        return result.components().components().stream().filter(item -> item.kind().equals("OBSERVATION")).toList();
    }
    private static void globallyRejected(ReadOnlyWebDiagnosticResult result) {
        assertTrue(result.assessment().limited());
        assertFalse(result.components().complete());
        assertEquals(1, result.components().components().size());
        assertFalse(result.components().components().getFirst().met());
        assertTrue(observations(result).isEmpty());
    }
    private static Set<String> names(JsonNode object) {
        Set<String> names = new HashSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }
    private static Set<String> strings(JsonNode array) {
        Set<String> strings = new HashSet<>();
        array.forEach(item -> strings.add(item.asText()));
        return strings;
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
