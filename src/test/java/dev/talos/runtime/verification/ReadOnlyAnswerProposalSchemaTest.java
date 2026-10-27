package dev.talos.runtime.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.Source;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.SOURCE_PROJECTION_VERSION;
import static org.junit.jupiter.api.Assertions.*;

/** The bounded schema model checks structure, not backend grammar enforcement or answer meaning. */
class ReadOnlyAnswerProposalSchemaTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> KEYWORDS = Set.of("type", "properties", "required", "additionalProperties",
            "oneOf", "enum", "minItems", "maxItems", "items", "minLength", "maxLength", "pattern");

    @Test
    void schemaIsDeterministicClosedAndUsesOnlyTheQualifiedSubset() throws Exception {
        String text = ReadOnlyAnswerProposal.responseSchema(sources());
        assertEquals(text, ReadOnlyAnswerProposal.responseSchema(sources()));
        assertTrue(text.length() < 6_000, Integer.toString(text.length()));
        JsonNode schema = JSON.readTree(text);
        inspect(schema);
        assertEquals(6, schema.at("/properties/results/items/oneOf").size());
        assertEquals(3, schema.at("/properties/results/items/oneOf/2/properties/calculation/oneOf").size());
        assertEquals(Set.of("version", "target", "results"), names(schema.get("properties")));
        assertFalse(text.contains("guide.md"));
        assertFalse(text.contains("power.js"));
        assertFalse(text.contains("KEEP"));
        assertFalse(text.contains("confidence"));
        assertFalse(text.contains("permission"));
    }

    @Test
    void theExactInstructionsExamplePassesSchemaAndExistingRuntimeAssessment() throws Exception {
        List<Source> sources = sources();
        String instructions = ReadOnlyAnswerProposal.instructions(sources);
        String candidate = section(instructions, "Example output:\n", "\nEnd example.");
        String request = section(instructions, "Example request:\n", "\nExample sources:");
        assertTrue(accepts(schema(), JSON.readTree(candidate)));
        var result = ReadOnlyAnswerProposal.assess(request, candidate, sources);
        assertTrue(result.components().complete(), result.answer());
    }

    @Test
    void sourceIdsAreBoundToTheCurrentCatalogAndLinkedScriptAfterReordering() throws Exception {
        var original = sources();
        List<Source> reordered = List.of(original.get(3), original.get(2), original.get(0), original.get(1));
        JsonNode schema = JSON.readTree(ReadOnlyAnswerProposal.responseSchema(reordered));
        JsonNode calculation = schema.at("/properties/results/items/oneOf/2/properties/calculation/oneOf");
        assertEquals(List.of("s0", "s1", "s2", "s3"), texts(calculation.at("/0/properties/formulaReference/properties/sourceId/enum")));
        assertEquals(List.of("request"), texts(calculation.at("/1/properties/formulaReference/properties/sourceId/enum")));
        assertEquals(List.of("s1"), texts(calculation.at("/2/properties/formulaReference/properties/sourceId/enum")));
        ObjectNode candidate = example();
        assertFalse(accepts(schema, candidate));
        reference(candidate, 3).put("sourceId", "s1");
        assertTrue(accepts(schema, candidate));
    }

    @Test
    void theLargestUsableCatalogRemainsBounded() throws Exception {
        List<Source> sources = new ArrayList<>(sources());
        for (int index = 4; index < 8; index++) sources.add(source("note" + index + ".md", "Retained note " + index + ".\n"));
        String text = ReadOnlyAnswerProposal.responseSchema(sources);
        assertTrue(text.length() < 6_000, Integer.toString(text.length()));
        JsonNode schema = JSON.readTree(text);
        JsonNode ids = schema.at("/properties/results/items/oneOf/2/properties/calculation/oneOf/0/properties/formulaReference/properties/sourceId/enum");
        assertEquals(8, ids.size());
        assertEquals("s7", ids.get(7).textValue());
        ObjectNode candidate = example();
        reference(candidate, 2).put("sourceId", "s7");
        assertTrue(accepts(schema, candidate));
        reference(candidate, 2).put("sourceId", "s8");
        assertFalse(accepts(schema, candidate));
    }

    @Test
    void unusableCatalogsAreRejectedWithoutAnEmptyOrTextSchema() {
        List<Source> original = sources();
        assertThrows(IllegalArgumentException.class, () -> ReadOnlyAnswerProposal.responseSchema(null));
        assertThrows(IllegalArgumentException.class, () -> ReadOnlyAnswerProposal.responseSchema(List.of()));
        assertThrows(IllegalArgumentException.class, () -> ReadOnlyAnswerProposal.responseSchema(List.of(original.getFirst())));
        assertThrows(IllegalArgumentException.class, () -> ReadOnlyAnswerProposal.responseSchema(List.of(original.get(0), original.get(1), original.get(3))));
        List<Source> duplicate = new ArrayList<>(original);
        duplicate.add(original.getFirst());
        assertThrows(IllegalArgumentException.class, () -> ReadOnlyAnswerProposal.responseSchema(duplicate));
        List<Source> oversized = new ArrayList<>(original);
        for (int index = 4; index < 9; index++) oversized.add(source("note" + index + ".md", "Note " + index + ".\n"));
        assertThrows(IllegalArgumentException.class, () -> ReadOnlyAnswerProposal.responseSchema(oversized));
        Source old = original.getFirst();
        Source invalid = new Source(old.path(), old.text(), old.originId(), old.ordinal(), old.bodyHash(), "unsupported");
        assertThrows(IllegalArgumentException.class, () -> ReadOnlyAnswerProposal.responseSchema(List.of(invalid, original.get(1), original.get(2), original.get(3))));
    }

    @Test
    void kindAndOriginBranchesHaveDistinctClosedPayloads() throws Exception {
        ObjectNode source = example();
        assertTrue(accepts(schema(), source));
        ObjectNode request = example();
        calculation(request, 2).put("formulaOrigin", "REQUEST");
        reference(request, 2).put("sourceId", "request");
        for (JsonNode operand : calculation(request, 2).get("operands")) ((ObjectNode) operand).put("inputId", "");
        assertTrue(accepts(schema(), request));
        ObjectNode constant = example();
        calculation(constant, 3).putArray("operands");
        assertTrue(accepts(schema(), constant));
        for (String kind : List.of("INPUT_VALIDATION", "UNKNOWN")) {
            ObjectNode candidate = example();
            result(candidate, 0).put("kind", kind);
            assertTrue(accepts(schema(), candidate));
        }
        reject(candidate -> result(candidate, 0).put("calculation", "payload"));
        reject(candidate -> result(candidate, 4).put("answer", "claimed answer"));
        reject(candidate -> calculation(candidate, 2).put("formulaOrigin", "IMPLEMENTATION"));
        reject(candidate -> calculation(candidate, 2).put("formulaOrigin", "REQUEST"));
        reject(candidate -> reference(candidate, 3).put("uniqueQuote", "voltage + current"));
        reject(candidate -> reference(candidate, 3).put("sourceId", "s3"));
        reject(candidate -> reference(candidate, 2).putNull("uniqueQuote"));
        reject(candidate -> reference(candidate, 2).put("sourceId", "guide.md"));
        reject(candidate -> reference(candidate, 2).put("sourceId", "request"));
        reject(candidate -> ((ObjectNode) calculation(candidate, 2).at("/operands/0")).put("inputId", ""));
    }

    @Test
    void alteredShapesVersionsAndAuthorityFieldsAreRejected() throws Exception {
        reject(candidate -> candidate.put("version", 1));
        reject(candidate -> candidate.put("version", "2"));
        reject(candidate -> candidate.put("complete", true));
        reject(candidate -> candidate.putArray("target"));
        reject(candidate -> candidate.remove("target"));
        reject(candidate -> candidate.putObject("results"));
        reject(candidate -> result(candidate, 0).put("kind", "READ"));
        reject(candidate -> result(candidate, 0).put("met", true));
        reject(candidate -> result(candidate, 2).putArray("calculation"));
        reject(candidate -> calculation(candidate, 2).put("answer", 18));
        reject(candidate -> calculation(candidate, 2).remove("precisionRequestQuote"));
        reject(candidate -> calculation(candidate, 2).put("precisionRequestQuote", 2));
        reject(candidate -> ((ObjectNode) calculation(candidate, 2).at("/operands/0")).put("value", 9));
        reject(candidate -> ((ObjectNode) candidate.get("target")).put("permission", "approved"));
    }

    @Test
    void arrayShortStringAndIdentifierBoundsAreEnforcedByTheSchemaModel() throws Exception {
        reject(candidate -> candidate.putArray("results"));
        reject(candidate -> { ArrayNode results = (ArrayNode) candidate.get("results"); while (results.size() < 33) results.add(result(candidate, 0).deepCopy()); });
        reject(candidate -> { ArrayNode operands = (ArrayNode) calculation(candidate, 2).get("operands"); while (operands.size() < 33) operands.add(operands.get(0).deepCopy()); });
        reject(candidate -> result(candidate, 4).putArray("sourceIds").add("s2"));
        reject(candidate -> result(candidate, 4).putArray("sourceIds").add("s0").add("s2").add("s3"));
        reject(candidate -> result(candidate, 4).putArray("sourceIds").add("s2").add("s4"));
        reject(candidate -> result(candidate, 0).put("requestQuote", ""));
        reject(candidate -> calculation(candidate, 2).put("precisionRequestQuote", "x".repeat(257)));
        reject(candidate -> ((ObjectNode) calculation(candidate, 2).at("/operands/0")).put("requestQuote", "x".repeat(257)));
        reject(candidate -> ((ObjectNode) candidate.get("target")).put("buttonId", "#power"));
        reject(candidate -> ((ObjectNode) candidate.get("target")).put("buttonId", "x".repeat(65)));
        reject(candidate -> ((ObjectNode) calculation(candidate, 2).at("/operands/0")).put("variable", "voltage.current"));
        ObjectNode boundary = example();
        result(boundary, 0).put("requestQuote", "x".repeat(4_096));
        assertTrue(accepts(schema(), boundary));
    }

    @Test
    void onlyLongQuoteMaximumsAreOmittedFromTheGenerationProjection() throws Exception {
        JsonNode schema = schema();
        for (String pointer : List.of("/properties/target/properties/requestQuote",
                "/properties/results/items/oneOf/0/properties/requestQuote",
                "/properties/results/items/oneOf/2/properties/calculation/oneOf/0/properties/formulaReference/properties/uniqueQuote",
                "/properties/results/items/oneOf/2/properties/calculation/oneOf/1/properties/formulaReference/properties/uniqueQuote")) {
            JsonNode quote = schema.at(pointer);
            assertEquals("string", quote.path("type").textValue());
            assertEquals(1, quote.path("minLength").intValue());
            assertFalse(quote.has("maxLength"), pointer);
        }
        assertEquals(64, schema.at("/properties/target/properties/buttonId/maxLength").intValue());
        assertEquals(256, schema.at("/properties/results/items/oneOf/2/properties/calculation/oneOf/0/properties/operands/items/properties/requestQuote/maxLength").intValue());
        assertEquals(256, schema.at("/properties/results/items/oneOf/2/properties/calculation/oneOf/0/properties/precisionRequestQuote/oneOf/1/maxLength").intValue());
    }

    @Test
    void quotesAboveTheGenerationProjectionRemainRejectedAtTheRuntimeBoundary() throws Exception {
        for (Consumer<ObjectNode> change : List.<Consumer<ObjectNode>>of(
                candidate -> ((ObjectNode) candidate.get("target")).put("requestQuote", "x".repeat(4_097)),
                candidate -> result(candidate, 0).put("requestQuote", "x".repeat(4_097)),
                candidate -> reference(candidate, 2).put("uniqueQuote", "x".repeat(4_097)))) {
            ObjectNode candidate = example();
            change.accept(candidate);
            assertTrue(accepts(schema(), candidate));
            assertTrue(ReadOnlyAnswerProposal.assess(request(), candidate.toString(), sources()).assessment().limited());
        }
    }

    @Test
    void theExistingMaximumLengthValidRequestReferenceIsNotLowered() throws Exception {
        String request = request();
        String padded = request + " ".repeat(4_096 - request.length());
        ObjectNode candidate = example();
        ((ObjectNode) candidate.get("target")).put("requestQuote", padded);
        assertTrue(accepts(schema(), candidate));
        var admitted = ReadOnlyAnswerProposal.assess(padded, candidate.toString(), sources());
        assertTrue(admitted.components().complete(), admitted.answer());
        ((ObjectNode) candidate.get("target")).put("requestQuote", padded + " ");
        assertTrue(accepts(schema(), candidate));
        assertTrue(ReadOnlyAnswerProposal.assess(padded + " ", candidate.toString(), sources()).assessment().limited());
    }

    @Test
    void duplicateAndUnboundMeaningRemainsRejectedByTheRuntime() throws Exception {
        ObjectNode duplicate = example();
        ((ArrayNode) duplicate.get("results")).add(result(duplicate, 0).deepCopy());
        assertTrue(accepts(schema(), duplicate));
        assertTrue(ReadOnlyAnswerProposal.assess(request(), duplicate.toString(), sources()).assessment().limited());
        ObjectNode duplicateOperand = example();
        ArrayNode operands = (ArrayNode) calculation(duplicateOperand, 2).get("operands");
        operands.add(operands.get(0).deepCopy());
        assertTrue(accepts(schema(), duplicateOperand));
        assertTrue(ReadOnlyAnswerProposal.assess(request(), duplicateOperand.toString(), sources()).assessment().limited());
        ObjectNode falseReference = example();
        reference(falseReference, 2).put("uniqueQuote", "a plausible expression that was never observed");
        assertTrue(accepts(schema(), falseReference));
        assertFalse(ReadOnlyAnswerProposal.assess(request(), falseReference.toString(), sources()).components().complete());
        ObjectNode blank = example();
        result(blank, 0).put("requestQuote", "   ");
        assertTrue(accepts(schema(), blank));
        assertTrue(ReadOnlyAnswerProposal.assess(request(), blank.toString(), sources()).assessment().limited());
    }

    @Test
    void runtimeRetainsUtf16AndTotalCandidateBoundsBeyondTheGenerationProjection() throws Exception {
        ObjectNode supplementary = example();
        result(supplementary, 0).put("requestQuote", "\uD83D\uDE00".repeat(2_049));
        assertTrue(accepts(schema(), supplementary));
        assertTrue(ReadOnlyAnswerProposal.assess(request(), supplementary.toString(), sources()).assessment().limited());
        ObjectNode aggregate = example();
        for (JsonNode entry : aggregate.get("results")) ((ObjectNode) entry).put("requestQuote", "x".repeat(4_096));
        assertTrue(accepts(schema(), aggregate));
        assertTrue(aggregate.toString().length() > ReadOnlyAnswerProposal.MAX_CANDIDATE_CHARS);
        assertTrue(ReadOnlyAnswerProposal.assess(request(), aggregate.toString(), sources()).assessment().limited());
    }

    private static void reject(Consumer<ObjectNode> change) throws Exception {
        ObjectNode candidate = example();
        change.accept(candidate);
        assertFalse(accepts(schema(), candidate), candidate.toString());
    }

    private static void inspect(JsonNode schema) {
        schema.fieldNames().forEachRemaining(name -> assertTrue(KEYWORDS.contains(name), name));
        if (schema.has("oneOf")) {
            assertEquals(Set.of("oneOf"), names(schema));
            assertTrue(schema.get("oneOf").size() >= 2);
            schema.get("oneOf").forEach(ReadOnlyAnswerProposalSchemaTest::inspect);
            return;
        }
        String type = schema.path("type").asText();
        assertTrue(Set.of("object", "array", "string", "integer", "null").contains(type), type);
        if (type.equals("object")) {
            assertFalse(schema.get("additionalProperties").booleanValue());
            assertEquals(names(schema.get("properties")), new HashSet<>(texts(schema.get("required"))));
            schema.get("properties").forEach(ReadOnlyAnswerProposalSchemaTest::inspect);
        } else if (type.equals("array")) {
            assertTrue(schema.path("minItems").intValue() >= 0);
            assertTrue(schema.path("maxItems").intValue() <= 32);
            inspect(schema.get("items"));
        } else if (type.equals("string") && !schema.has("enum")) {
            assertEquals(1, schema.path("minLength").intValue());
            if (schema.has("maxLength")) assertTrue(Set.of(64, 256).contains(schema.get("maxLength").intValue()));
            if (schema.has("pattern")) {
                assertTrue(schema.get("pattern").textValue().startsWith("^"));
                assertTrue(schema.get("pattern").textValue().endsWith("$"));
            }
        }
    }

    private static boolean accepts(JsonNode schema, JsonNode value) {
        if (value == null) return false;
        if (schema.has("oneOf")) {
            int count = 0;
            for (JsonNode alternative : schema.get("oneOf")) if (accepts(alternative, value)) count++;
            return count == 1;
        }
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode allowed : schema.get("enum")) if (allowed.equals(value)) found = true;
            if (!found) return false;
        }
        return switch (schema.get("type").textValue()) {
            case "object" -> {
                if (!value.isObject() || !names(value).equals(names(schema.get("properties")))) yield false;
                boolean valid = true;
                for (String name : names(schema.get("properties"))) valid &= accepts(schema.get("properties").get(name), value.get(name));
                yield valid;
            }
            case "array" -> {
                if (!value.isArray() || value.size() < schema.get("minItems").intValue() || value.size() > schema.get("maxItems").intValue()) yield false;
                boolean valid = true;
                for (JsonNode item : value) valid &= accepts(schema.get("items"), item);
                yield valid;
            }
            case "string" -> value.isTextual()
                    && (!schema.has("minLength") || value.textValue().codePointCount(0, value.textValue().length()) >= schema.get("minLength").intValue())
                    && (!schema.has("maxLength") || value.textValue().codePointCount(0, value.textValue().length()) <= schema.get("maxLength").intValue())
                    && (!schema.has("pattern") || Pattern.compile(schema.get("pattern").textValue()).matcher(value.textValue()).matches());
            case "integer" -> value.isIntegralNumber();
            case "null" -> value.isNull();
            default -> throw new AssertionError("Unmodeled schema type");
        };
    }

    private static Set<String> names(JsonNode object) {
        Set<String> names = new HashSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }
    private static List<String> texts(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(value -> result.add(value.textValue()));
        return result;
    }
    private static JsonNode schema() throws Exception { return JSON.readTree(ReadOnlyAnswerProposal.responseSchema(sources())); }
    private static ObjectNode example() throws Exception {
        return (ObjectNode) JSON.readTree(section(ReadOnlyAnswerProposal.instructions(sources()), "Example output:\n", "\nEnd example."));
    }
    private static String request() { return section(ReadOnlyAnswerProposal.instructions(sources()), "Example request:\n", "\nExample sources:"); }
    private static ObjectNode result(ObjectNode candidate, int index) { return (ObjectNode) candidate.get("results").get(index); }
    private static ObjectNode calculation(ObjectNode candidate, int index) { return (ObjectNode) result(candidate, index).get("calculation"); }
    private static ObjectNode reference(ObjectNode candidate, int index) { return (ObjectNode) calculation(candidate, index).get("formulaReference"); }
    private static String section(String text, String start, String end) {
        return text.substring(text.indexOf(start) + start.length(), text.indexOf(end));
    }
    private static List<Source> sources() {
        String html = """
                <!doctype html>
                <html><body>
                <label>Voltage (V)<input id="voltage" value="1"></label>
                <label>Current (A)<input id="current" value="1"></label>
                <button id="power">Power</button><p id="result"></p>
                <script src="power.js"></script>
                </body></html>
                """;
        String script = """
                const button = document.querySelector('#switch');
                button.addEventListener('click', () => {
                  const voltage = Number(document.querySelector('#voltage').value);
                  const current = Number(document.querySelector('#current').value);
                  document.querySelector('#result').textContent = (voltage + current).toFixed(1);
                });
                """;
        return List.of(source("guide.md", "The current definition of power is voltage multiplied by current.\n"),
                source("page.html", html), source("power.js", script), source("spare.js", "const spare = 'KEEP';\n"));
    }
    private static Source source(String path, String text) {
        return new Source(path, text, UUID.nameUUIDFromBytes((path + "\n" + text).getBytes(StandardCharsets.UTF_8)).toString(),
                1, ReadOnlyWebDiagnosticResult.textHash(text), SOURCE_PROJECTION_VERSION);
    }
}
