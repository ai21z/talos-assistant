package dev.talos.runtime.verification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.runtime.task.AnswerResultRequirements;
import dev.talos.runtime.task.ReadOnlyRequestMeaning;
import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.Source;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.SOURCE_PROJECTION_VERSION;
import static org.junit.jupiter.api.Assertions.*;

class NativeReadOnlyAnswerProposalTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REQUEST = "Read guide.md, index.html, engine.js and spare.js. "
            + "Explain why the Compute button fails and give the correct selector. "
            + "For volume 24 L and elapsed 6 s, give the flow rate according to guide.md "
            + "and the mathematical value of the expression in engine.js separately. "
            + "Explain any disagreement and distinguish the two JavaScript files. "
            + "Do not edit files or claim tests were run.";
    private static final String DEFINITION = "volume divided by elapsed";
    private static final String GUIDE = "# Flow fixture\n\n"
            + "The Compute button should compute flow rate as volume divided by elapsed. "
            + "index.html loads engine.js. spare.js is unrelated.\n";
    private static final String HTML = """
            <!doctype html>
            <html lang="en">
            <head><meta charset="utf-8"><title>Flow fixture</title></head>
            <body>
            <label>Volume (L)<input id="volume" value="100"></label>
            <label>Elapsed (s)<input id="elapsed" value="20"></label>
            <button id="compute">Compute</button>
            <p id="result"></p>
            <script src="engine.js"></script>
            </body>
            </html>
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
    void automaticallyConstructedCatalogRetainsIndependentResultsAndExactFormulaSpan() {
        var catalog = NativeReadOnlyAnswerProposal.catalog(REQUEST, sources());

        assertEquals(6, catalog.requirements().slots().size());
        assertEquals(1, slots(catalog, AnswerResultRequirements.Role.SOURCE_DEFINITION).size());
        assertEquals(1, slots(catalog, AnswerResultRequirements.Role.IMPLEMENTATION).size());
        assertEquals(1, slots(catalog, AnswerResultRequirements.Role.COMPARISON).size());
        var formula = formula(catalog, ReadOnlyAnswerProposal.FormulaOrigin.SOURCE);
        assertEquals(DEFINITION, formula.expression());
        assertEquals(DEFINITION, sources().getFirst().text().substring(formula.span().start(), formula.span().end()));
        assertEquals(java.util.Set.of("volume", "elapsed"), formula.variables());
        assertTrue(catalog.bindings().stream().allMatch(binding -> binding.span().start() >= 0
                && binding.span().end() <= REQUEST.length()));
        assertEquals(catalog.id(), NativeReadOnlyAnswerProposal.catalog(REQUEST, sources()).id());
    }

    @Test
    void nativeIdsProduceBothValuesAndTheRequiredComparisonWithoutCopiedProse() {
        ReadOnlyWebDiagnosticResult result = assess(REQUEST, proposal(REQUEST, sources()), sources());

        complete(result);
        number(result.answer(), "4.00");
        number(result.answer(), "30.00");
        assertTrue(result.answer().contains("disagree"), result.answer());
        assertEquals(2, components(result, "CALCULATION").size());
        assertEquals(1, components(result, "COMPARISON").size());
        assertTrue(result.answer().contains("#compute"));
        assertFalse(json(proposal(REQUEST, sources())).contains("requestQuote"));
    }

    @Test
    void implementationFormulaCannotSatisfyTheDocumentedResultSlot() {
        var catalog = NativeReadOnlyAnswerProposal.catalog(REQUEST, sources());
        var candidate = proposal(REQUEST, sources());
        var entry = result(candidate, slot(catalog, AnswerResultRequirements.Role.SOURCE_DEFINITION).id());
        entry.put("formulaId", formula(catalog, ReadOnlyAnswerProposal.FormulaOrigin.IMPLEMENTATION).id());

        ReadOnlyWebDiagnosticResult result = assess(REQUEST, candidate, sources());

        limited(result);
    }

    @Test
    void sourceFormulaCannotSatisfyTheImplementationResultSlot() {
        var catalog = NativeReadOnlyAnswerProposal.catalog(REQUEST, sources());
        var candidate = proposal(REQUEST, sources());
        result(candidate, slot(catalog, AnswerResultRequirements.Role.IMPLEMENTATION).id())
                .put("formulaId", formula(catalog, ReadOnlyAnswerProposal.FormulaOrigin.SOURCE).id());

        limited(assess(REQUEST, candidate, sources()));
    }

    @Test
    void aConstantImplementationCannotSupplyTheDefinitionWithoutOperandConfusion() {
        List<Source> changed = replace(sources(), "engine.js", SCRIPT.replace("volume + elapsed", "42"));
        var catalog = NativeReadOnlyAnswerProposal.catalog(REQUEST, changed);
        var candidate = proposal(REQUEST, changed);
        var entry = result(candidate, slot(catalog, AnswerResultRequirements.Role.SOURCE_DEFINITION).id());
        entry.put("formulaId", formula(catalog, ReadOnlyAnswerProposal.FormulaOrigin.IMPLEMENTATION).id());
        entry.put("bindingIds", List.of());

        limited(assess(REQUEST, candidate, changed));
    }

    @Test
    void aConstantImplementationRemainsUsableWhenInputDeclarationsAreUnused() {
        List<Source> changed = replace(sources(), "engine.js", SCRIPT.replace("volume + elapsed", "42"));

        ReadOnlyWebDiagnosticResult result = assess(REQUEST, proposal(REQUEST, changed), changed);

        complete(result);
        number(result.answer(), "4.00");
        number(result.answer(), "42.00");
        assertTrue(result.answer().contains("disagree"));
    }

    @Test
    void anImplementationUsingOneDeclaredInputDoesNotRequireAnUnusedOperand() {
        List<Source> changed = replace(sources(), "engine.js", SCRIPT.replace("volume + elapsed", "volume"));

        ReadOnlyWebDiagnosticResult result = assess(REQUEST, proposal(REQUEST, changed), changed);

        complete(result);
        number(result.answer(), "4.00");
        number(result.answer(), "24.00");
        assertTrue(result.answer().contains("disagree"));
    }

    @Test
    void anotherAuthenticSourceCannotSatisfyAnExplicitlyNamedDefinitionSource() {
        List<Source> extended = new ArrayList<>(sources());
        extended.add(source("other.md", "The flow rate is volume multiplied by elapsed.\n"));
        var catalog = NativeReadOnlyAnswerProposal.catalog(REQUEST, extended);
        var other = catalog.formulas().stream().filter(value -> value.origin()
                == ReadOnlyAnswerProposal.FormulaOrigin.SOURCE && value.sourceId().equals("s4")).toList();
        if (other.isEmpty()) {
            complete(assess(REQUEST, proposal(REQUEST, extended), extended));
            return;
        }
        var candidate = proposal(REQUEST, extended);
        var slot = slot(catalog, AnswerResultRequirements.Role.SOURCE_DEFINITION);
        var entry = result(candidate, slot.id());
        entry.put("formulaId", other.getFirst().id());
        entry.put("bindingIds", catalog.bindings().stream().filter(binding -> binding.slotId().equals(slot.id())
                        && binding.formulaId().equals(other.getFirst().id()))
                .map(NativeReadOnlyAnswerProposal.OperandBinding::id).toList());

        limited(assess(REQUEST, candidate, extended));
    }

    @Test
    void omittedImplementationCannotBeCoveredByTheDocumentedResult() {
        var catalog = NativeReadOnlyAnswerProposal.catalog(REQUEST, sources());
        var candidate = proposal(REQUEST, sources());
        remove(candidate, slot(catalog, AnswerResultRequirements.Role.IMPLEMENTATION).id());

        ReadOnlyWebDiagnosticResult result = assess(REQUEST, candidate, sources());

        limited(result);
        assertFalse(result.components().requestCovered());
    }

    @Test
    void omittedComparisonRemainsAnIndependentMissingObligation() {
        var catalog = NativeReadOnlyAnswerProposal.catalog(REQUEST, sources());
        var candidate = proposal(REQUEST, sources());
        remove(candidate, slot(catalog, AnswerResultRequirements.Role.COMPARISON).id());

        ReadOnlyWebDiagnosticResult result = assess(REQUEST, candidate, sources());

        limited(result);
        assertFalse(result.components().requestCovered());
    }

    @Test
    void duplicatedSlotDoesNotBecomeAdditionalCoverage() {
        var candidate = proposal(REQUEST, sources());
        results(candidate).add(new LinkedHashMap<>(results(candidate).getFirst()));

        limited(assess(REQUEST, candidate, sources()));
    }

    @Test
    void responseOrderDoesNotChangeResultRolesOrComparisonDependencies() {
        var candidate = proposal(REQUEST, sources());
        Collections.reverse(results(candidate));

        ReadOnlyWebDiagnosticResult result = assess(REQUEST, candidate, sources());

        complete(result);
        number(result.answer(), "4.00");
        number(result.answer(), "30.00");
        assertEquals(1, components(result, "COMPARISON").size());
        assertTrue(result.answer().contains("disagree"));
    }

    @Test
    void aChangedRequestInvalidatesTheFormerCatalog() {
        var candidate = proposal(REQUEST, sources());
        String changed = REQUEST.replace("volume 24 L", "volume 25 L");

        assertNotEquals(NativeReadOnlyAnswerProposal.catalog(REQUEST, sources()).id(),
                NativeReadOnlyAnswerProposal.catalog(changed, sources()).id());
        limited(assess(changed, candidate, sources()));
    }

    @Test
    void changedSourceBytesInvalidateTheFormerCatalog() {
        var candidate = proposal(REQUEST, sources());
        List<Source> changed = replace(sources(), "guide.md", GUIDE + "An additional retained note.\n");

        assertNotEquals(NativeReadOnlyAnswerProposal.catalog(REQUEST, sources()).id(),
                NativeReadOnlyAnswerProposal.catalog(REQUEST, changed).id());
        limited(assess(REQUEST, candidate, changed));
    }

    @Test
    void changedObservationIdentityInvalidatesTheFormerCatalogEvenWithIdenticalText() {
        var candidate = proposal(REQUEST, sources());
        List<Source> changed = new ArrayList<>(sources());
        Source original = changed.getFirst();
        changed.set(0, new Source(original.path(), original.text(), "different-observation-origin", 1,
                original.bodyHash(), original.projectionVersion()));

        assertNotEquals(NativeReadOnlyAnswerProposal.catalog(REQUEST, sources()).id(),
                NativeReadOnlyAnswerProposal.catalog(REQUEST, changed).id());
        limited(assess(REQUEST, candidate, changed));
    }

    @Test
    void sourceOrderChangesInvalidateOldIdsButFreshIdsRemainUsable() {
        var candidate = proposal(REQUEST, sources());
        List<Source> reordered = new ArrayList<>(sources());
        Collections.swap(reordered, 0, 3);

        limited(assess(REQUEST, candidate, reordered));
        complete(assess(REQUEST, proposal(REQUEST, reordered), reordered));
    }

    @Test
    void staleUnknownAndWrongTypeIdsCannotBecomeReferences() {
        for (String field : List.of("catalogId", "targetId")) {
            var candidate = proposal(REQUEST, sources());
            candidate.put(field, "unavailable-id");
            limited(assess(REQUEST, candidate, sources()));
        }
        for (String field : List.of("slotId", "formulaId", "precisionId")) {
            var catalog = NativeReadOnlyAnswerProposal.catalog(REQUEST, sources());
            var candidate = proposal(REQUEST, sources());
            result(candidate, slot(catalog, AnswerResultRequirements.Role.SOURCE_DEFINITION).id())
                    .put(field, "unavailable-id");
            limited(assess(REQUEST, candidate, sources()));
        }
        var catalog = NativeReadOnlyAnswerProposal.catalog(REQUEST, sources());
        var candidate = proposal(REQUEST, sources());
        result(candidate, slot(catalog, AnswerResultRequirements.Role.SOURCE_DEFINITION).id())
                .put("bindingIds", List.of(catalog.targets().getFirst().id()));
        limited(assess(REQUEST, candidate, sources()));
    }

    @Test
    void wrongUnitsCannotBeReboundToTheHtmlDefaultOrAnUnrelatedInput() {
        String request = REQUEST.replace("volume 24 L", "volume 24 kg");

        ReadOnlyWebDiagnosticResult result = assess(request, proposal(request, sources()), sources());

        limited(result);
        assertFalse(result.answer().contains("`4.00`"));
        assertFalse(result.answer().contains("`30.00`"));
    }

    @Test
    void missingAndDuplicatedOperandBindingsCannotProduceACompleteAnswer() {
        var catalog = NativeReadOnlyAnswerProposal.catalog(REQUEST, sources());
        String id = slot(catalog, AnswerResultRequirements.Role.SOURCE_DEFINITION).id();
        var missing = proposal(REQUEST, sources());
        result(missing, id).put("bindingIds", List.of());
        limited(assess(REQUEST, missing, sources()));

        var duplicate = proposal(REQUEST, sources());
        List<String> bindings = strings(result(duplicate, id).get("bindingIds"));
        assertFalse(bindings.isEmpty());
        result(duplicate, id).put("bindingIds", List.of(bindings.getFirst(), bindings.getFirst()));
        limited(assess(REQUEST, duplicate, sources()));
    }

    @Test
    void anOperandIdForOneResultCannotBeBorrowedByAnotherResult() {
        var catalog = NativeReadOnlyAnswerProposal.catalog(REQUEST, sources());
        var candidate = proposal(REQUEST, sources());
        String sourceSlot = slot(catalog, AnswerResultRequirements.Role.SOURCE_DEFINITION).id();
        String implementationSlot = slot(catalog, AnswerResultRequirements.Role.IMPLEMENTATION).id();
        result(candidate, implementationSlot).put("bindingIds", result(candidate, sourceSlot).get("bindingIds"));

        limited(assess(REQUEST, candidate, sources()));
    }

    @Test
    void identicalRepeatedClausesKeepDistinctOccurrenceIdsAndRequiredResults() {
        String clause = "Give the flow rate according to guide.md for volume 24 L and elapsed 6 s";
        String request = "Read guide.md, index.html, engine.js and spare.js. "
                + "Explain why the Compute button fails. Give the correct selector. "
                + clause + ". " + clause + ". Distinguish the two JavaScript files. "
                + "Do not edit files or claim tests were run.";
        var catalog = NativeReadOnlyAnswerProposal.catalog(request, sources());
        var slots = slots(catalog, AnswerResultRequirements.Role.SOURCE_DEFINITION);

        assertEquals(2, slots.size());
        assertNotEquals(slots.get(0).id(), slots.get(1).id());
        assertNotEquals(slots.get(0).start(), slots.get(1).start());
        assertEquals(request.substring(slots.get(0).start(), slots.get(0).end()),
                request.substring(slots.get(1).start(), slots.get(1).end()));
        var candidate = proposal(request, sources());
        ReadOnlyWebDiagnosticResult result = assess(request, candidate, sources());
        complete(result);
        assertEquals(2, components(result, "CALCULATION").size());

        remove(candidate, slots.get(1).id());
        limited(assess(request, candidate, sources()));
    }

    @Test
    void requestExpressionIdsRemainBoundToTheirOwnVariablesAndPrecision() {
        String request = "Read guide.md, index.html, engine.js and spare.js. "
                + "Explain why the Compute button fails. Give the correct selector. "
                + "Evaluate total / count for total = 21 and count = 4 to 3 decimal places. "
                + "Distinguish the two JavaScript files. Do not edit files or claim tests were run.";
        var catalog = NativeReadOnlyAnswerProposal.catalog(request, sources());

        assertEquals(1, slots(catalog, AnswerResultRequirements.Role.REQUEST_EXPRESSION).size());
        assertEquals("total / count", formula(catalog, ReadOnlyAnswerProposal.FormulaOrigin.REQUEST).expression());
        ReadOnlyWebDiagnosticResult result = assess(request, proposal(request, sources()), sources());
        complete(result);
        number(result.answer(), "5.250");
    }

    @Test
    void sourceExpressionsAreNotRecoveredByTruncatingUnsupportedOperators() {
        for (String expression : List.of("volume ** elapsed", "volume % elapsed", "volume / elapsed / unknown")) {
            List<Source> changed = replace(sources(), "guide.md", GUIDE.replace(DEFINITION, expression));
            var catalog = NativeReadOnlyAnswerProposal.catalog(REQUEST, changed);

            assertTrue(catalog.formulas().stream().noneMatch(value -> value.origin()
                    == ReadOnlyAnswerProposal.FormulaOrigin.SOURCE), expression);
        }
    }

    @Test
    void nativeSchemaAndInstructionsContainIdsWithoutFreeProseReferenceFields() throws Exception {
        var catalog = NativeReadOnlyAnswerProposal.catalog(REQUEST, sources());
        String schemaText = NativeReadOnlyAnswerProposal.responseSchema(REQUEST, sources());
        JsonNode schema = JSON.readTree(schemaText);
        String instructions = NativeReadOnlyAnswerProposal.instructions(REQUEST, sources());

        assertEquals("object", schema.path("type").textValue());
        assertFalse(schema.path("additionalProperties").booleanValue());
        assertTrue(schema.path("properties").has("catalogId"));
        assertTrue(schema.path("properties").has("targetId"));
        assertTrue(schema.path("properties").has("results"));
        assertTrue(instructions.contains(catalog.id()));
        assertFalse(schemaText.contains("requestQuote"));
        assertFalse(schemaText.contains("uniqueQuote"));
        assertFalse(schemaText.contains("formulaOrigin"));
        assertEquals(schemaText, NativeReadOnlyAnswerProposal.responseSchema(REQUEST, sources()));
    }

    @Test
    void versionNineExplainsCalculationSelectionsWithoutChangingHistoricalProtocolsOrSchemaChoices() throws Exception {
        var sources = sources();
        var meaning = ReadOnlyRequestMeaning.compile(REQUEST);
        String instructions = NativeReadOnlyAnswerProposal.evaluationInstructions(meaning, sources);
        String calculationContract = """
                Calculation roles are SOURCE_DEFINITION, IMPLEMENTATION and REQUEST_EXPRESSION.
                For a calculation, the value is either a selection object with formulaId, bindingIds and precisionId,
                or the string "unsupported" when no suitable selection is available.
                In a selection object, formulaId selects a formula of the required role and source, and bindingIds is an array containing one binding ID per formula variable.
                """;
        assertTrue(instructions.contains(calculationContract));
        assertEquals(instructions.indexOf(calculationContract), instructions.lastIndexOf(calculationContract));
        assertFalse(instructions.contains("For a calculation, a formula of the required role and source is selected"));
        for (String historical : List.of(NativeReadOnlyAnswerProposal.instructions(REQUEST, sources),
                NativeReadOnlyAnswerProposal.instructions(meaning, sources),
                NativeReadOnlyAnswerProposal.decisionInstructions(meaning, sources))) {
            assertFalse(historical.contains("Calculation roles are SOURCE_DEFINITION, IMPLEMENTATION and REQUEST_EXPRESSION."));
        }

        JsonNode schema = JSON.readTree(NativeReadOnlyAnswerProposal.evaluationSchema(meaning, sources));
        assertEquals(9, schema.at("/properties/version/enum/0").intValue());
        JsonNode resultSchema = schema.at("/properties/results");
        assertEquals(meaning.requirements().slots().size(), resultSchema.path("required").size());
        assertEquals(2, meaning.requirements().slots().stream().filter(AnswerResultRequirements.Slot::calculation).count());
        for (var slot : meaning.requirements().slots()) if (slot.calculation()) {
            JsonNode alternatives = resultSchema.path("properties").path(slot.id()).path("oneOf");
            assertEquals(2, alternatives.size());
            assertEquals(JSON.readTree("{\"type\":\"string\",\"enum\":[\"unsupported\"]}"), alternatives.get(0));
            JsonNode selection = alternatives.get(1);
            assertEquals("object", selection.path("type").asText());
            assertFalse(selection.path("additionalProperties").asBoolean());
            assertEquals(JSON.valueToTree(List.of("formulaId", "bindingIds", "precisionId")), selection.path("required"));
            assertEquals(3, selection.path("properties").size());
        }
        var unknown = meaning.requirements().slots().stream()
                .filter(slot -> slot.role() == AnswerResultRequirements.Role.UNKNOWN).toList();
        assertEquals(1, unknown.size());
        JsonNode unsupported = JSON.readTree("{\"type\":\"string\",\"enum\":[\"unsupported\"]}");
        assertEquals(unsupported, resultSchema.path("properties").path(unknown.getFirst().id()));
        assertEquals(unsupported, schema.at("/properties/interpretationDecisions/properties").path(unknown.getFirst().id()));
        assertEquals(JSON.valueToTree(List.of(unknown.getFirst().id())), schema.at("/properties/interpretationDecisions/required"));
    }

    @Test
    void extraFieldsAndLiteralV2ReferencesAreRejectedByTheNativeContract() {
        var candidate = proposal(REQUEST, sources());
        candidate.put("answer", "The answer was verified.");
        limited(assess(REQUEST, candidate, sources()));

        candidate = proposal(REQUEST, sources());
        results(candidate).getFirst().put("requestQuote", "Explain why the Compute button fails");
        limited(assess(REQUEST, candidate, sources()));
    }

    @Test
    void anOutOfRangeIntegralVersionCannotBeTruncatedToTheSupportedVersion() {
        var candidate = proposal(REQUEST, sources());
        candidate.put("version", 4_294_967_302L);

        limited(assess(REQUEST, candidate, sources()));
    }

    private static Map<String, Object> proposal(String request, List<Source> sources) {
        var catalog = NativeReadOnlyAnswerProposal.catalog(request, sources);
        assertFalse(catalog.targets().isEmpty(), request);
        List<Map<String, Object>> results = new ArrayList<>();
        for (var slot : catalog.requirements().slots()) {
            String formulaId = null;
            List<String> bindingIds = List.of();
            if (slot.calculation()) {
                ReadOnlyAnswerProposal.FormulaOrigin origin = switch (slot.role()) {
                    case SOURCE_DEFINITION -> ReadOnlyAnswerProposal.FormulaOrigin.SOURCE;
                    case IMPLEMENTATION -> ReadOnlyAnswerProposal.FormulaOrigin.IMPLEMENTATION;
                    case REQUEST_EXPRESSION -> ReadOnlyAnswerProposal.FormulaOrigin.REQUEST;
                    default -> throw new AssertionError(slot.role());
                };
                var formula = formula(catalog, origin);
                formulaId = formula.id();
                bindingIds = catalog.bindings().stream().filter(binding -> binding.slotId().equals(slot.id())
                                && binding.formulaId().equals(formula.id()))
                        .map(NativeReadOnlyAnswerProposal.OperandBinding::id).toList();
            }
            String precisionId = catalog.precisions().stream().filter(value -> value.slotId().equals(slot.id()))
                    .map(NativeReadOnlyAnswerProposal.PrecisionReference::id).findFirst().orElse(null);
            results.add(fields("slotId", slot.id(), "formulaId", formulaId,
                    "bindingIds", bindingIds, "precisionId", precisionId));
        }
        return fields("version", 6, "catalogId", catalog.id(), "targetId", catalog.targets().getFirst().id(),
                "results", results, "interpretationDecisions", java.util.Map.of());
    }

    private static NativeReadOnlyAnswerProposal.FormulaReference formula(
            NativeReadOnlyAnswerProposal.Catalog catalog, ReadOnlyAnswerProposal.FormulaOrigin origin) {
        return catalog.formulas().stream().filter(value -> value.origin() == origin).findFirst().orElseThrow();
    }

    private static List<AnswerResultRequirements.Slot> slots(NativeReadOnlyAnswerProposal.Catalog catalog,
                                                            AnswerResultRequirements.Role role) {
        return catalog.requirements().slots().stream().filter(value -> value.role() == role).toList();
    }

    private static AnswerResultRequirements.Slot slot(NativeReadOnlyAnswerProposal.Catalog catalog,
                                                       AnswerResultRequirements.Role role) {
        List<AnswerResultRequirements.Slot> matches = slots(catalog, role);
        assertEquals(1, matches.size(), role.name());
        return matches.getFirst();
    }

    private static ReadOnlyWebDiagnosticResult assess(String request, Map<String, Object> candidate,
                                                       List<Source> sources) {
        return NativeReadOnlyAnswerProposal.assess(request, json(candidate), sources);
    }

    private static void complete(ReadOnlyWebDiagnosticResult result) {
        assertFalse(result.assessment().limited(), result.answer());
        assertTrue(result.components().complete(), result.answer());
        assertTrue(result.components().requestCovered(), result.answer());
        assertTrue(result.components().deliveredIntact(result.answer()));
        assertTrue(result.components().components().stream().allMatch(component -> component.support()
                == ReadOnlyAnswerComponents.Support.ATTRIBUTED_INTERPRETATION));
    }

    private static void limited(ReadOnlyWebDiagnosticResult result) {
        assertTrue(result.assessment().limited(), result.answer());
        assertFalse(result.components().complete(), result.answer());
    }

    private static List<ReadOnlyAnswerComponents.Component> components(ReadOnlyWebDiagnosticResult result, String kind) {
        return result.components().components().stream().filter(value -> value.kind().equals(kind)).toList();
    }

    private static void number(String text, String expected) {
        assertTrue(Pattern.compile("(?<![0-9.])" + Pattern.quote(expected) + "(?![0-9.])").matcher(text).find(), text);
    }

    private static Map<String, Object> result(Map<String, Object> proposal, String id) {
        return results(proposal).stream().filter(value -> id.equals(value.get("slotId"))).findFirst().orElseThrow();
    }

    private static void remove(Map<String, Object> proposal, String id) {
        assertTrue(results(proposal).removeIf(value -> id.equals(value.get("slotId"))));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> results(Map<String, Object> proposal) {
        return (List<Map<String, Object>>) proposal.get("results");
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return (List<String>) value;
    }

    private static Map<String, Object> fields(Object... entries) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) value.put((String) entries[index], entries[index + 1]);
        return value;
    }

    private static String json(Map<String, Object> value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException invalid) { throw new AssertionError(invalid); }
    }

    private static List<Source> sources() {
        return List.of(source("guide.md", GUIDE), source("index.html", HTML), source("engine.js", SCRIPT),
                source("spare.js", "// Unrelated retained source.\nconst marker = 'PRESERVED';\n"));
    }

    private static List<Source> replace(List<Source> sources, String path, String text) {
        return sources.stream().map(value -> value.path().equals(path) ? source(path, text) : value).toList();
    }

    private static Source source(String path, String text) {
        return new Source(path, text, UUID.nameUUIDFromBytes((path + "\n" + text).getBytes(StandardCharsets.UTF_8)).toString(),
                1, ReadOnlyWebDiagnosticResult.textHash(text), SOURCE_PROJECTION_VERSION);
    }
}
