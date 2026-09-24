package dev.talos.runtime.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.runtime.task.AnswerRequirements;
import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.Source;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.SOURCE_PROJECTION_VERSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadOnlyAnswerProposalLimitsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DIAGNOSIS = "Explain why the Calculate button fails";

    @Test
    void knownObligationsBeyondTheParserBoundCannotDisappearIntoCompleteCoverage() {
        ObjectNode proposal = proposal();
        ArrayNode results = (ArrayNode) proposal.get("results");
        List<String> clauses = new ArrayList<>(List.of(DIAGNOSIS));
        for (int index = 1; index <= 32; index++) {
            clauses.add(expressionClause(index));
            if (index < 32) addRequestCalculation(results, index);
        }
        String request = String.join(". ", clauses) + ".";
        assertEquals(32, results.size());
        assertTrue(proposal.toString().length() < ReadOnlyAnswerProposal.MAX_CANDIDATE_CHARS);
        assertTrue(AnswerRequirements.fromRequest(request).unresolved().contains("REQUIREMENT_BOUND_EXCEEDED"));

        ReadOnlyWebDiagnosticResult result = ReadOnlyAnswerProposal.assess(request, proposal.toString(), sources());

        assertTrue(result.assessment().limited(), result.answer());
        assertFalse(result.components().complete(), result.answer());
        assertFalse(result.components().deliveredIntact(result.answer()));
    }

    @Test
    void comparisonCapacityDoesNotEraseThirtyTwoValidRequestedResults() {
        ObjectNode proposal = proposal();
        ArrayNode results = (ArrayNode) proposal.get("results");
        String definition = "give the area for span 4 m and rise 3 m";
        String implementation = "give the implementation result for span 4.0 m and rise 3.0 m";
        addObservedCalculation(results, definition, "SOURCE", "s0", "span multiplied by rise", "4", "3");
        addObservedCalculation(results, implementation, "IMPLEMENTATION", "s2", null, "4.0", "3.0");
        List<String> clauses = new ArrayList<>(List.of(DIAGNOSIS, definition, implementation));
        for (int index = 1; index <= 29; index++) {
            clauses.add(expressionClause(index));
            addRequestCalculation(results, index);
        }
        String request = String.join(". ", clauses) + ".";
        assertEquals(32, results.size());
        assertTrue(proposal.toString().length() < ReadOnlyAnswerProposal.MAX_CANDIDATE_CHARS);
        AnswerRequirements requirements = AnswerRequirements.fromRequest(request);
        assertEquals(32, requirements.requirements().size());
        assertFalse(requirements.unresolved().contains("REQUIREMENT_BOUND_EXCEEDED"));

        ReadOnlyWebDiagnosticResult result = ReadOnlyAnswerProposal.assess(request, proposal.toString(), sources());

        assertEquals(32, result.components().components().size(), result.answer());
        for (int index = 0; index < 32; index++) {
            var component = result.components().components().get(index);
            assertEquals("proposal-" + (index + 1), component.requirementId());
            assertTrue(component.met(), component.reason() + ": " + component.text());
        }
        assertTrue(result.components().components().get(1).text().contains("`12.0`"));
        assertTrue(result.components().components().get(2).text().contains("`7.0`"));
        assertTrue(result.assessment().limited(), result.answer());
        assertFalse(result.components().complete(), result.answer());
        assertFalse(result.components().deliveredIntact(result.answer()));
    }

    @Test
    void anInvalidRequestQuoteAtCapacityPreservesThirtyOneValidSiblings() {
        ObjectNode proposal = proposal();
        ArrayNode results = (ArrayNode) proposal.get("results");
        List<String> clauses = new ArrayList<>(List.of(DIAGNOSIS));
        for (int index = 1; index <= 31; index++) {
            clauses.add(expressionClause(index));
            addRequestCalculation(results, index);
        }
        String request = String.join(". ", clauses) + ".";
        ((ObjectNode) results.get(1)).put("requestQuote", "a requested clause that is absent");
        assertEquals(32, results.size());
        assertTrue(proposal.toString().length() < ReadOnlyAnswerProposal.MAX_CANDIDATE_CHARS);
        AnswerRequirements requirements = AnswerRequirements.fromRequest(request);
        assertEquals(32, requirements.requirements().size());
        assertFalse(requirements.unresolved().contains("REQUIREMENT_BOUND_EXCEEDED"));

        ReadOnlyWebDiagnosticResult result = ReadOnlyAnswerProposal.assess(request, proposal.toString(), sources());

        assertEquals(32, result.components().components().size(), result.answer());
        assertEquals(31, result.components().components().stream().filter(ReadOnlyAnswerComponents.Component::met).count());
        for (int index = 0; index < 32; index++) {
            var component = result.components().components().get(index);
            assertEquals("proposal-" + (index + 1), component.requirementId());
            if (index == 1) {
                assertFalse(component.met());
                assertEquals("REFERENCE_NOT_UNIQUE", component.reason());
            } else assertTrue(component.met(), component.reason() + ": " + component.text());
        }
        assertTrue(result.components().components().getLast().text().contains("`62.0`"));
        assertTrue(result.components().components().getLast().text().contains("Additional missing requirements could not be listed"));
        assertFalse(result.components().requestCovered());
        assertTrue(result.assessment().limited(), result.answer());
        assertFalse(result.components().complete(), result.answer());
        assertFalse(result.components().deliveredIntact(result.answer()));
    }

    @Test
    void capacityNoticeCannotOverflowAnAlreadyAdmittedComponent() {
        String definition = "give the area for span 4 m and rise 3 m";
        String implementation = "give the implementation result for span 4.0 m and rise 3.0 m";
        ObjectNode baselineProposal = proposal();
        addObservedCalculation((ArrayNode) baselineProposal.get("results"), definition,
                "SOURCE", "s0", "span multiplied by rise", "4", "3");
        List<Source> sources = new ArrayList<>(sources());
        ReadOnlyWebDiagnosticResult baseline = ReadOnlyAnswerProposal.assess(
                DIAGNOSIS + ". " + definition + ".", baselineProposal.toString(), sources);
        assertTrue(baseline.components().complete(), baseline.answer());
        String baselineText = baseline.components().components().getLast().text();

        // Paths are retained metadata here. No long filesystem name is created.
        int padding = ReadOnlyAnswerComponents.MAX_COMPONENT_TEXT_CHARS - baselineText.length();
        assertTrue(padding > 0);
        String originalPath = sources.getFirst().path();
        String longPath = "a".repeat(padding) + originalPath;
        sources.set(0, source(longPath, sources.getFirst().text()));
        String expectedText = baselineText.replace("`" + originalPath + "`", "`" + longPath + "`");
        assertEquals(ReadOnlyAnswerComponents.MAX_COMPONENT_TEXT_CHARS, expectedText.length());
        assertTrue(ReadOnlyAnswerProposal.eligible(sources));

        ObjectNode proposal = proposal();
        ArrayNode results = (ArrayNode) proposal.get("results");
        addObservedCalculation(results, implementation, "IMPLEMENTATION", "s2", null, "4.0", "3.0");
        List<String> clauses = new ArrayList<>(List.of(DIAGNOSIS, implementation));
        for (int index = 1; index <= 29; index++) {
            clauses.add(expressionClause(index));
            addRequestCalculation(results, index);
        }
        clauses.add(definition);
        addObservedCalculation(results, definition, "SOURCE", "s0", "span multiplied by rise", "4", "3");
        String request = String.join(". ", clauses) + ".";
        assertEquals(32, results.size());
        assertEquals(32, AnswerRequirements.fromRequest(request).requirements().size());
        assertTrue(proposal.toString().length() < ReadOnlyAnswerProposal.MAX_CANDIDATE_CHARS);

        ReadOnlyWebDiagnosticResult result = ReadOnlyAnswerProposal.assess(request, proposal.toString(), sources);

        assertEquals(32, result.components().components().size(), result.answer());
        assertTrue(result.components().components().stream().allMatch(ReadOnlyAnswerComponents.Component::met));
        assertEquals(expectedText, result.components().components().getLast().text());
        assertFalse(result.components().requestCovered());
        assertTrue(result.assessment().limited(), result.answer());
        assertFalse(result.components().complete());
        assertFalse(result.components().deliveredIntact(result.answer()));
        assertTrue(result.answer().endsWith(ReadOnlyAnswerComponents.FOOTER));
    }

    private static ObjectNode proposal() {
        ObjectNode proposal = JSON.createObjectNode().put("version", 2);
        proposal.putObject("target").put("buttonId", "calculate").put("requestQuote", "Calculate button");
        proposal.putArray("results").addObject().put("kind", "DIAGNOSIS").put("requestQuote", DIAGNOSIS);
        return proposal;
    }

    private static String expressionClause(int index) {
        return "evaluate the expression x + " + index + " for x=" + index;
    }

    private static void addRequestCalculation(ArrayNode results, int index) {
        ObjectNode calculation = results.addObject().put("kind", "CALCULATION")
                .put("requestQuote", expressionClause(index)).putObject("calculation");
        calculation.put("formulaOrigin", "REQUEST").putNull("precisionRequestQuote");
        calculation.putObject("formulaReference").put("sourceId", "request").put("uniqueQuote", "x + " + index);
        calculation.putArray("operands").addObject().put("variable", "x").put("inputId", "")
                .put("requestQuote", "x=" + index);
    }

    private static void addObservedCalculation(ArrayNode results, String clause, String origin, String sourceId,
                                               String formula, String span, String rise) {
        ObjectNode calculation = results.addObject().put("kind", "CALCULATION").put("requestQuote", clause)
                .putObject("calculation");
        calculation.put("formulaOrigin", origin).putNull("precisionRequestQuote");
        ObjectNode reference = calculation.putObject("formulaReference").put("sourceId", sourceId);
        if (formula == null) reference.putNull("uniqueQuote");
        else reference.put("uniqueQuote", formula);
        ArrayNode operands = calculation.putArray("operands");
        operands.addObject().put("variable", "span").put("inputId", "span").put("requestQuote", "span " + span + " m");
        operands.addObject().put("variable", "rise").put("inputId", "rise").put("requestQuote", "rise " + rise + " m");
    }

    private static List<Source> sources() {
        String html = """
                <!doctype html>
                <html><body>
                <label>Span (m)<input id="span" value="1"></label>
                <label>Rise (m)<input id="rise" value="1"></label>
                <button id="calculate">Calculate</button>
                <p id="result"></p>
                <script src="main.js"></script>
                </body></html>
                """;
        String script = """
                const button = document.querySelector('#missing');
                button.addEventListener('click', () => {
                  const span = Number(document.querySelector('#span').value);
                  const rise = Number(document.querySelector('#rise').value);
                  document.querySelector('#result').textContent = (span + rise).toFixed(1);
                });
                """;
        return List.of(source("README.md", "The current area definition is span multiplied by rise.\n"),
                source("page.html", html), source("main.js", script), source("spare.js", "const spare = 'KEEP';\n"));
    }

    private static Source source(String path, String text) {
        return new Source(path, text, UUID.nameUUIDFromBytes((path + "\n" + text).getBytes(StandardCharsets.UTF_8)).toString(),
                1, ReadOnlyWebDiagnosticResult.textHash(text), SOURCE_PROJECTION_VERSION);
    }
}
