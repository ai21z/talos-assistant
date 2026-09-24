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

import static org.junit.jupiter.api.Assertions.*;

class NativeRoleSchemaTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String READ = "Read guide.md, index.html, engine.js and spare.js. ";
    private static final String REQUEST = READ + "Explain why the Compute button fails and give the correct selector. "
            + "For volume 24 L and elapsed 6 s, give the flow according to guide.md "
            + "and the mathematical value of the expression in engine.js separately. "
            + "Explain any disagreement and distinguish the two JavaScript files.";
    private static final String INTERPRETED = READ + "Explain exactly why the Compute button is broken. "
            + "Identify the selector that should be used and explain how engine.js differs from spare.js.";
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
    private static final Set<String> KEYWORDS = Set.of("type", "properties", "required", "additionalProperties",
            "oneOf", "enum", "items", "minItems", "maxItems");

    @Test
    void closedVersionSixSchemaAdmitsACorrectIndependentMultiresultSelection() throws Exception {
        JsonNode schema = schema(REQUEST, sources());
        inspect(schema);
        var candidate = proposal(REQUEST, sources());

        assertEquals(6, schema.at("/properties/version/enum/0").intValue());
        assertTrue(accepts(schema, candidate));
        assertTrue(NativeReadOnlyAnswerProposal.assess(REQUEST, candidate.toString(), sources()).components().complete());
        assertTrue(schema.toString().length() <= NativeReadOnlyAnswerProposal.MAX_SCHEMA_CHARS);
        assertTrue(schema.at("/properties/results/items/oneOf").size() <= NativeReadOnlyAnswerProposal.MAX_SCHEMA_BRANCHES);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DIAGNOSIS", "SELECTOR", "SCRIPT_DISTINCTION", "COMPARISON"})
    void numericPayloadCannotBeGeneratedForANonnumericRole(String role) throws Exception {
        var catalog = NativeReadOnlyAnswerProposal.catalog(REQUEST, sources());
        var candidate = proposal(REQUEST, sources());
        var calculation = result(candidate, slot(catalog, Role.SOURCE_DEFINITION));
        var nonnumeric = result(candidate, slot(catalog, Role.valueOf(role)));
        nonnumeric.set("formulaId", calculation.get("formulaId"));
        nonnumeric.set("bindingIds", calculation.get("bindingIds"));

        assertFalse(accepts(schema(REQUEST, sources()), candidate));
        var outcome = NativeReadOnlyAnswerProposal.assess(REQUEST, candidate.toString(), sources());
        assertFalse(outcome.components().complete());
        assertTrue(outcome.components().components().stream().anyMatch(item -> item.reason().equals("NATIVE_RESULT_PAYLOAD_INVALID")));
    }

    @Test
    void sourceAndImplementationFormulaRolesCannotBeInterchangedEvenWithAuthenticOperands() throws Exception {
        var catalog = NativeReadOnlyAnswerProposal.catalog(REQUEST, sources());
        for (Role role : List.of(Role.SOURCE_DEFINITION, Role.IMPLEMENTATION)) {
            var candidate = proposal(REQUEST, sources());
            var changed = result(candidate, slot(catalog, role));
            var peer = result(candidate, slot(catalog, role == Role.SOURCE_DEFINITION ? Role.IMPLEMENTATION : Role.SOURCE_DEFINITION));
            changed.set("formulaId", peer.get("formulaId"));
            changed.set("bindingIds", peer.get("bindingIds"));
            assertFalse(accepts(schema(REQUEST, sources()), candidate));
            assertFalse(NativeReadOnlyAnswerProposal.assess(REQUEST, candidate.toString(), sources()).components().complete());
        }
    }

    @Test
    void authenticOperandsFromAnotherResultCannotBeBorrowed() throws Exception {
        var catalog = NativeReadOnlyAnswerProposal.catalog(REQUEST, sources());
        var candidate = proposal(REQUEST, sources());
        result(candidate, slot(catalog, Role.SOURCE_DEFINITION)).set("bindingIds",
                result(candidate, slot(catalog, Role.IMPLEMENTATION)).get("bindingIds"));

        assertFalse(accepts(schema(REQUEST, sources()), candidate));
        assertFalse(NativeReadOnlyAnswerProposal.assess(REQUEST, candidate.toString(), sources()).components().complete());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3})
    void operandCountMustEqualTheSelectedFormulaVariableCount(int count) throws Exception {
        var catalog = NativeReadOnlyAnswerProposal.catalog(REQUEST, sources());
        var candidate = proposal(REQUEST, sources());
        var entry = result(candidate, slot(catalog, Role.SOURCE_DEFINITION));
        var original = entry.get("bindingIds").deepCopy();
        var changed = entry.putArray("bindingIds");
        for (int i = 0; i < count; i++) changed.add(original.get(i % original.size()));

        assertFalse(accepts(schema(REQUEST, sources()), candidate));
        assertFalse(NativeReadOnlyAnswerProposal.assess(REQUEST, candidate.toString(), sources()).components().complete());
    }

    @Test
    void duplicateOperandsRemainASeparateRuntimeGuardBeyondTheSchemaSubset() throws Exception {
        var catalog = NativeReadOnlyAnswerProposal.catalog(REQUEST, sources());
        var candidate = proposal(REQUEST, sources());
        var bindings = (ArrayNode) result(candidate, slot(catalog, Role.SOURCE_DEFINITION)).path("bindingIds");
        bindings.set(1, bindings.get(0));

        assertTrue(accepts(schema(REQUEST, sources()), candidate));
        var outcome = NativeReadOnlyAnswerProposal.assess(REQUEST, candidate.toString(), sources());
        assertFalse(outcome.components().complete());
        assertTrue(outcome.components().components().stream().anyMatch(item -> item.reason().equals("NATIVE_BINDING_DUPLICATE")));
    }

    @Test
    void omissionIsSchemaAdmissibleButCannotSatisfyARequiredResult() throws Exception {
        var candidate = proposal(REQUEST, sources());
        String missing = candidate.path("results").get(0).path("slotId").asText();
        ((ArrayNode) candidate.path("results")).remove(0);

        assertTrue(accepts(schema(REQUEST, sources()), candidate));
        var outcome = NativeReadOnlyAnswerProposal.assess(REQUEST, candidate.toString(), sources());
        assertFalse(outcome.components().complete());
        assertTrue(outcome.components().components().stream().anyMatch(item -> item.requirementId().equals(missing)
                && !item.met() && item.reason().equals("NATIVE_REQUIRED_RESULT_MISSING")));
    }

    @Test
    void nullCalculationPayloadRemainsAnExplicitUnsupportedResult() throws Exception {
        var catalog = NativeReadOnlyAnswerProposal.catalog(REQUEST, sources());
        var candidate = proposal(REQUEST, sources());
        var entry = result(candidate, slot(catalog, Role.SOURCE_DEFINITION));
        entry.putNull("formulaId");
        entry.putArray("bindingIds");

        assertTrue(accepts(schema(REQUEST, sources()), candidate));
        var outcome = NativeReadOnlyAnswerProposal.assess(REQUEST, candidate.toString(), sources());
        assertFalse(outcome.components().complete());
        assertTrue(outcome.components().components().stream().anyMatch(item -> item.kind().equals("CALCULATION") && !item.met()));
    }

    @Test
    void requestExpressionAndPrecisionReferencesRemainScopedToTheirOwnOccurrences() throws Exception {
        String request = READ + "Explain why the Compute button fails. "
                + "Evaluate total / count for total = 21 and count = 4 to 3 decimal places. "
                + "Evaluate total / count for total = 18 and count = 4 to 1 decimal place.";
        var catalog = NativeReadOnlyAnswerProposal.catalog(request, sources());
        var slots = catalog.requirements().slots().stream().filter(item -> item.role() == Role.REQUEST_EXPRESSION).toList();
        assertEquals(2, slots.size());
        var valid = proposal(request, sources());
        assertTrue(accepts(schema(request, sources()), valid));
        for (String field : List.of("formulaId", "bindingIds", "precisionId")) {
            var changed = valid.deepCopy();
            var second = result(changed, slots.get(1).id()).get(field);
            assertNotEquals(result(changed, slots.get(0).id()).get(field), second);
            result(changed, slots.get(0).id()).set(field, second);
            assertFalse(accepts(schema(request, sources()), changed), field);
            assertFalse(NativeReadOnlyAnswerProposal.assess(request, changed.toString(), sources()).components().complete(), field);
        }
    }

    @Test
    void unsupportedUnitsCannotUseHtmlDefaultsAsOperandBindings() throws Exception {
        String request = REQUEST.replace("24 L", "24 kg");
        var catalog = NativeReadOnlyAnswerProposal.catalog(request, sources());
        var candidate = proposal(request, sources());
        assertFalse(accepts(schema(request, sources()), candidate));
        assertTrue(catalog.bindings().stream().noneMatch(binding -> binding.variable().equals("volume")));
        assertFalse(NativeReadOnlyAnswerProposal.assess(request, candidate.toString(), sources()).components().complete());
    }

    @ParameterizedTest
    @ValueSource(strings = {"permission", "complete", "answer", "tools", "role"})
    void extraResultOrRootFieldsCannotAddAuthorityOrCompletion(String field) throws Exception {
        var candidate = proposal(REQUEST, sources());
        candidate.put(field, "forbidden");
        assertFalse(accepts(schema(REQUEST, sources()), candidate));
        assertFalse(NativeReadOnlyAnswerProposal.assess(REQUEST, candidate.toString(), sources()).components().complete());
        candidate = proposal(REQUEST, sources());
        ((ObjectNode) candidate.path("results").get(0)).put(field, "forbidden");
        assertFalse(accepts(schema(REQUEST, sources()), candidate));
        assertFalse(NativeReadOnlyAnswerProposal.assess(REQUEST, candidate.toString(), sources()).components().complete());
    }

    @Test
    void aTypedRelationCannotBeGeneratedForAnotherUnresolvedRegion() throws Exception {
        var catalog = NativeReadOnlyAnswerProposal.catalog(INTERPRETED, sources());
        var regions = catalog.relations().stream().map(NativeReadOnlyAnswerProposal.SourceRelation::regionId).distinct().toList();
        assertTrue(regions.size() >= 2);
        var relation = catalog.relations().stream().filter(item -> item.regionId().equals(regions.get(0))).findFirst().orElseThrow();
        var candidate = proposal(INTERPRETED, sources());
        var decisions = (ObjectNode) candidate.path("interpretationDecisions");
        decisions.put(relation.regionId(), relation.id());
        assertTrue(accepts(schema(INTERPRETED, sources()), candidate));
        decisions.put(regions.get(1), relation.id());

        assertFalse(accepts(schema(INTERPRETED, sources()), candidate));
        assertFalse(NativeReadOnlyAnswerProposal.assess(INTERPRETED, candidate.toString(), sources()).components().complete());
    }

    @Test
    void suppliedVersionAndCatalogIdentityMustMatchTheExactGeneratedSchema() throws Exception {
        var candidate = proposal(REQUEST, sources());
        for (int version : List.of(3, 4, 5, 7)) {
            candidate.put("version", version);
            assertFalse(accepts(schema(REQUEST, sources()), candidate));
            assertEquals("NATIVE_VERSION_INVALID", NativeReadOnlyAnswerProposal.assess(
                    REQUEST, candidate.toString(), sources()).components().components().getFirst().reason());
        }
        candidate = proposal(REQUEST, sources());
        candidate.put("catalogId", "0".repeat(64));
        assertFalse(accepts(schema(REQUEST, sources()), candidate));
    }

    @Test
    void excessiveSerializedSchemaLengthIsRejectedBeforeAnyProviderRequest() {
        String request = READ + "Explain why the Compute button fails. "
                + "Give the flow according to guide.md for volume 24 L and elapsed 6 s.";
        List<Source> sources = formulaSources(100);
        var catalog = NativeReadOnlyAnswerProposal.catalog(request, sources);

        assertEquals(101, catalog.formulas().size());
        assertEquals(100, catalog.bindings().size());
        assertEquals("NATIVE_SCHEMA_BOUND", assertThrows(ReadOnlyAnswerProposal.Rejected.class,
                () -> NativeReadOnlyAnswerProposal.responseSchema(request, sources)).reason);
    }

    @Test
    void schemaBranchLimitIsReachedIndependentlyOfTheCatalogBindingLimit() {
        String request = READ + "Explain why the Compute button fails. "
                + "Give the flow according to guide.md for volume 24 L and elapsed 6 s. ".repeat(8);
        List<Source> sources = formulaSources(32);
        var catalog = NativeReadOnlyAnswerProposal.catalog(request, sources);

        assertEquals(33, catalog.formulas().size());
        assertEquals(256, catalog.bindings().size());
        assertEquals(8, catalog.requirements().slots().stream().filter(item -> item.role() == Role.SOURCE_DEFINITION).count());
        assertEquals("NATIVE_SCHEMA_BOUND", assertThrows(ReadOnlyAnswerProposal.Rejected.class,
                () -> NativeReadOnlyAnswerProposal.responseSchema(request, sources)).reason);
    }

    private static List<Source> formulaSources(int count) {
        String guide = java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(value -> "Flow is volume plus " + value + ".\n").collect(java.util.stream.Collectors.joining());
        return sources().stream().map(source -> source.path().equals("guide.md") ? source("guide.md", guide) : source).toList();
    }

    // Only the emitted closed schema subset is modeled, backend grammar behavior is not tested.
    private static boolean accepts(JsonNode schema, JsonNode value) {
        if (value == null) return false;
        if (schema.has("oneOf")) {
            int count = 0;
            for (JsonNode branch : schema.get("oneOf")) if (accepts(branch, value)) count++;
            return count == 1;
        }
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode allowed : schema.get("enum")) if (allowed.equals(value)) found = true;
            if (!found) return false;
        }
        return switch (schema.path("type").asText()) {
            case "object" -> {
                if (!value.isObject() || !names(value).equals(names(schema.path("properties")))) yield false;
                boolean valid = true;
                for (String name : names(schema.path("properties"))) valid &= accepts(schema.path("properties").get(name), value.get(name));
                yield valid;
            }
            case "array" -> {
                if (!value.isArray() || value.size() < schema.path("minItems").intValue()
                        || value.size() > schema.path("maxItems").intValue()) yield false;
                boolean valid = true;
                for (JsonNode item : value) valid &= accepts(schema.get("items"), item);
                yield valid;
            }
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "null" -> value.isNull();
            default -> throw new AssertionError("Unmodeled schema type");
        };
    }

    private static void inspect(JsonNode schema) {
        schema.fieldNames().forEachRemaining(name -> assertTrue(KEYWORDS.contains(name), name));
        if (schema.has("oneOf")) {
            assertEquals(Set.of("oneOf"), names(schema));
            assertTrue(schema.path("oneOf").size() >= 2);
            schema.get("oneOf").forEach(NativeRoleSchemaTest::inspect);
        } else if (schema.path("type").asText().equals("object")) {
            assertTrue(schema.has("additionalProperties"));
            assertTrue(schema.get("additionalProperties").isBoolean());
            assertFalse(schema.path("additionalProperties").booleanValue());
            Set<String> required = new HashSet<>();
            schema.path("required").forEach(item -> required.add(item.asText()));
            assertEquals(names(schema.path("properties")), required);
            schema.path("properties").forEach(NativeRoleSchemaTest::inspect);
        } else if (schema.path("type").asText().equals("array")) {
            assertTrue(schema.path("minItems").intValue() >= 0);
            assertTrue(schema.path("maxItems").intValue() <= 32);
            inspect(schema.get("items"));
        } else {
            assertTrue(Set.of("string", "integer", "null").contains(schema.path("type").asText()));
        }
    }

    private static Set<String> names(JsonNode object) {
        Set<String> names = new HashSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JsonNode schema(String request, List<Source> sources) throws Exception {
        JsonNode schema = JSON.readTree(NativeReadOnlyAnswerProposal.responseSchema(request, sources));
        inspect(schema);
        return schema;
    }

    private static ObjectNode proposal(String request, List<Source> sources) {
        return NativeAnswerProposalTestSupport.proposal(request, sources);
    }

    private static String slot(NativeReadOnlyAnswerProposal.Catalog catalog, Role role) {
        return catalog.requirements().slots().stream().filter(item -> item.role() == role).findFirst().orElseThrow().id();
    }

    private static ObjectNode result(ObjectNode candidate, String slot) {
        for (JsonNode item : candidate.path("results")) if (item.path("slotId").asText().equals(slot)) return (ObjectNode) item;
        throw new AssertionError("Missing result fixture");
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
