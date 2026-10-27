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
import java.util.regex.Pattern;

import static dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.SOURCE_PROJECTION_VERSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic checks of the supplied example do not establish model understanding. */
class ReadOnlyAnswerProposalInstructionsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BMI_REQUEST = "Read README.md, index.html, script.js and scripts.js. "
            + "Explain why the Calculate button fails, give the correct selector and the BMI for 72 kg and 1.8 m, "
            + "and distinguish the two JavaScript files. Do not edit files or claim tests were run.";
    private static final String PANEL_REQUEST = "Read README.md, index.html, script.js and scripts.js. "
            + "Explain why the Calculate button fails and give the correct selector. For span 12 m and rise 2.5 m, "
            + "give the panel area according to README.md and the mathematical value of the expression in script.js separately. "
            + "Explain any disagreement and distinguish the two JavaScript files. Do not edit files or claim tests were run.";

    // All four normalized original observations are retained for transplantation checks.
    private static final String README = """
            # Calculator review fixture

            The calculator in index.html loads script.js. scripts.js is an unrelated sentinel and must stay unchanged. The Calculate button should compute BMI as weight divided by height squared. This fixture is local test data.
            """;
    private static final String HTML = """
            <!doctype html>
            <html lang="en">
            <head><meta charset="utf-8"><title>BMI fixture</title></head>
            <body>
            <label>Weight (kg)<input id="weight" value="72"></label>
            <label>Height (m)<input id="height" value="1.8"></label>
            <button id="calculate">Calculate</button>
            <p id="result"></p>
            <script src="script.js"></script>
            </body>
            </html>
            """;
    private static final String SCRIPT = """
            const button = document.querySelector('#calc');
            button.addEventListener('click', () => {
              const weight = Number(document.querySelector('#weight').value);
              const height = Number(document.querySelector('#height').value);
              document.querySelector('#result').textContent = (weight / (height * height)).toFixed(1);
            });
            """;
    private static final String SENTINEL = """
            // Unrelated sentinel. This file is not loaded by index.html.
            const sentinel = 'KEEP_UNCHANGED';
            """;

    @Test
    void exactInstructionExampleIsUsableAgainstItsOwnCompleteUnrelatedFixture() {
        List<Source> sources = power("voltage * current");
        String instructions = ReadOnlyAnswerProposal.instructions(sources);
        String request = section(instructions, "Example request:\n", "\nExample sources:");
        String candidate = section(instructions, "Example output:\n", "\nEnd example.");
        assertTrue(ReadOnlyAnswerProposal.eligible(sources));

        ReadOnlyWebDiagnosticResult result = ReadOnlyAnswerProposal.assess(request, candidate, sources);
        complete(result);
        assertEquals(6, result.components().components().size());
        assertTrue(component(result, "DIAGNOSIS").text().contains("#switch"));
        assertTrue(component(result, "SELECTOR").text().contains("#power"));
        assertTrue(component(result, "SCRIPT_DISTINCTION").text().contains("power.js"));
        assertTrue(component(result, "SCRIPT_DISTINCTION").text().contains("spare.js"));
        number(component(result, "CALCULATION").text(), "18.0");
        assertEquals("ATTRIBUTED_INTERPRETATION", component(result, "CALCULATION").support().name());
        assertTrue(result.answer().contains("not treated as proof of meaning"));
        assertTrue(result.answer().endsWith(ReadOnlyAnswerComponents.FOOTER));
        for (var part : result.components().components()) {
            for (var reference : part.references()) {
                Source observed = sources.stream().filter(source -> source.observationId().equals(reference.observationId()))
                        .findFirst().orElseThrow();
                assertEquals(ReadOnlyWebDiagnosticResult.textHash(observed.text()), reference.textHash());
                assertTrue(reference.start() >= 0 && reference.end() <= observed.text().length());
            }
        }
    }

    @Test
    void independentDefinitionAndImplementationPayloadsSupportTheRequestedComparison() throws Exception {
        ObjectNode proposal = example();
        ReadOnlyWebDiagnosticResult result = assess(exampleRequest(), proposal, power("voltage + current"));
        complete(result);
        number(result.components().components().get(2).text(), "18.0");
        number(result.components().components().get(3).text(), "11.0");
        assertTrue(component(result, "COMPARISON").text().contains("disagree for those inputs"));
        assertEquals("voltage multiplied by current", reference(proposal).get("uniqueQuote").asText());
        assertEquals("IMPLEMENTATION", proposal.at("/results/3/calculation/formulaOrigin").asText());
        assertEquals("s2", proposal.at("/results/3/calculation/formulaReference/sourceId").asText());
        assertTrue(proposal.at("/results/3/calculation/formulaReference/uniqueQuote").isNull());
    }

    @Test
    void instructionExampleSelectsReferencesWithoutCarryingAnswersOrAuthority() throws Exception {
        ObjectNode proposal = example();
        assertEquals(2, proposal.get("version").intValue());
        assertEquals(3, proposal.size());
        assertEquals(5, proposal.get("results").size());
        assertEquals("CALCULATION", proposal.at("/results/2/kind").asText());
        assertEquals("CALCULATION", proposal.at("/results/3/kind").asText());
        for (String forbidden : List.of("answer", "value", "model", "met", "complete", "confidence", "permission",
                "execution", "requirements", "unresolvedRequestQuotes")) {
            assertTrue(proposal.findValues(forbidden).isEmpty(), forbidden);
        }
        assertFalse(exampleText().contains("18.0"));
        assertFalse(exampleText().contains("11.0"));
        String instructions = ReadOnlyAnswerProposal.instructions(original());
        assertTrue(instructions.contains("full unique requested"));
        assertTrue(instructions.contains("Each calculation has its own formula, operands and precision"));
    }

    @Test
    void obsoleteVersionAndSingleCalculationContractAreRejectedWithoutFallback() throws Exception {
        ObjectNode obsoleteVersion = example();
        obsoleteVersion.put("version", 1);
        ReadOnlyWebDiagnosticResult invalidVersion = assess(exampleRequest(), obsoleteVersion, power("voltage * current"));
        limited(invalidVersion);
        assertEquals("PROPOSAL_VERSION_INVALID", invalidVersion.components().components().getFirst().reason());

        ObjectNode obsoleteShape = example();
        obsoleteShape.set("calculation", calculation(obsoleteShape).deepCopy());
        ReadOnlyWebDiagnosticResult invalidShape = assess(exampleRequest(), obsoleteShape, power("voltage * current"));
        limited(invalidShape);
        assertEquals("PROPOSAL_FIELDS_INVALID", invalidShape.components().components().getFirst().reason());
    }

    @Test
    void unchangedNeutralExampleIsNotEvidenceForEitherRealAcceptanceFixture() {
        String candidate = exampleText();
        for (var entry : List.of(new Fixture(BMI_REQUEST, original()), new Fixture(PANEL_REQUEST, panel()))) {
            ReadOnlyWebDiagnosticResult result = ReadOnlyAnswerProposal.assess(entry.request(), candidate, entry.sources());
            limited(result);
            assertTrue(result.components().components().stream().noneMatch(ReadOnlyAnswerComponents.Component::met));
            assertFalse(result.answer().contains("power.js"));
            assertFalse(result.answer().contains("18.0"));
        }
    }

    @Test
    void partiallyAdaptedExampleCannotSmuggleItsFormulaOrOperandValues() throws Exception {
        ObjectNode copiedCalculation = bmi();
        ((ObjectNode) copiedCalculation.at("/results/2")).set("calculation", calculation(example()));
        ObjectNode copiedOperands = bmi();
        calculation(copiedOperands).set("operands", calculation(example()).get("operands"));
        for (ObjectNode proposal : List.of(copiedCalculation, copiedOperands)) {
            ReadOnlyWebDiagnosticResult result = assess(BMI_REQUEST, proposal, original());
            limited(result);
            assertEquals(proposal == copiedCalculation ? "REFERENCE_NOT_UNIQUE" : "RESULT_REFERENCE_OUTSIDE_SCOPE",
                    component(result, "CALCULATION").reason());
            assertTrue(component(result, "DIAGNOSIS").met());
            assertFalse(result.answer().contains("18.0"));
        }
    }

    @Test
    void actualCatalogUsesObservationOrderAndDoesNotInheritExampleAliases() {
        List<Source> sources = original();
        List<Source> reordered = List.of(sources.get(3), sources.get(1), sources.get(0), sources.get(2));
        String instructions = ReadOnlyAnswerProposal.instructions(reordered);
        assertEquals("s0 = scripts.js\ns1 = index.html\ns2 = README.md\ns3 = script.js\n",
                instructions.substring(instructions.indexOf("Actual source catalog:\n") + "Actual source catalog:\n".length()));
        assertEquals(exampleText(), section(instructions, "Example output:\n", "\nEnd example."));
    }

    @Test
    void literalSourceWordsAndLiteralRequestOperandsAdmitTheOriginalAnswer() throws Exception {
        ReadOnlyWebDiagnosticResult result = assess(BMI_REQUEST, bmi(), original());
        complete(result);
        number(component(result, "CALCULATION").text(), "22.2");
        assertTrue(component(result, "CALCULATION").text().contains("README.md"));
    }

    @Test
    void sourceOnlyFormulaCannotBeRelabeledAsARequestExpression() throws Exception {
        for (String formula : List.of("weight divided by height squared", "weight / (height * height)")) {
            ObjectNode proposal = bmi();
            calculation(proposal).put("formulaOrigin", "REQUEST");
            reference(proposal).put("sourceId", "request").put("uniqueQuote", formula);
            for (var operand : calculation(proposal).get("operands")) ((ObjectNode) operand).put("inputId", "");
            ReadOnlyWebDiagnosticResult result = assess(BMI_REQUEST, proposal, original());
            limited(result);
            assertEquals("RESULT_REFERENCE_OUTSIDE_SCOPE", component(result, "CALCULATION").reason());
            assertTrue(component(result, "DIAGNOSIS").met());
        }
    }

    @Test
    void algebraTranslatedFromSourceWordsIsNotAnExactSourceQuote() throws Exception {
        ObjectNode proposal = bmi();
        reference(proposal).put("uniqueQuote", "weight / (height * height)");
        ReadOnlyWebDiagnosticResult result = assess(BMI_REQUEST, proposal, original());
        limited(result);
        assertEquals("REFERENCE_NOT_UNIQUE", component(result, "CALCULATION").reason());
    }

    @Test
    void explicitlyQuotedRequestExpressionUsesNamedUnitlessBindings() throws Exception {
        String request = "Explain why the Power button fails, give the correct selector, "
                + "calculate the expression (voltage * current) for voltage=9 and current=2 to 3 decimal places, "
                + "and distinguish the two JavaScript files.";
        ObjectNode proposal = example();
        ((ArrayNode) proposal.get("results")).remove(3);
        ((ObjectNode) proposal.at("/results/2")).put("requestQuote",
                "calculate the expression (voltage * current) for voltage=9 and current=2 to 3 decimal places");
        calculation(proposal).put("formulaOrigin", "REQUEST").put("precisionRequestQuote", "3 decimal places");
        reference(proposal).put("sourceId", "request").put("uniqueQuote", "(voltage * current)");
        ((ObjectNode) calculation(proposal).at("/operands/0")).put("inputId", "").put("requestQuote", "voltage=9");
        ((ObjectNode) calculation(proposal).at("/operands/1")).put("inputId", "").put("requestQuote", "current=2");

        ReadOnlyWebDiagnosticResult result = assess(request, proposal, power("voltage * current"));
        complete(result);
        number(component(result, "CALCULATION").text(), "18.000");
        reference(proposal).put("sourceId", "s0");
        ReadOnlyWebDiagnosticResult wrongOrigin = assess(request, proposal, power("voltage * current"));
        limited(wrongOrigin);
        assertEquals("FORMULA_ORIGIN_INVALID", component(wrongOrigin, "CALCULATION").reason());
    }

    @Test
    void changedCaseAndInsertedRequestWordsAreStillRejected() throws Exception {
        for (String changed : List.of("Give the correct selector", "give the replacement selector")) {
            ObjectNode proposal = bmi();
            ((ObjectNode) proposal.at("/results/1")).put("requestQuote", changed);
            ReadOnlyWebDiagnosticResult result = assess(BMI_REQUEST, proposal, original());
            limited(result);
            assertTrue(result.components().components().stream().anyMatch(part -> part.reason().equals("REFERENCE_NOT_UNIQUE")));
        }
        ObjectNode proposal = bmi();
        String repeated = BMI_REQUEST + " A quoted heading repeats: give the correct selector.";
        ReadOnlyWebDiagnosticResult result = assess(repeated, proposal, original());
        limited(result);
        assertTrue(result.components().components().stream().anyMatch(part -> part.reason().equals("REFERENCE_NOT_UNIQUE")));
    }

    @Test
    void filenameReferencesRemainInvalidDespiteTheirPresenceInTheCatalog() throws Exception {
        for (List<String> ids : List.of(List.of("script.js", "scripts.js"), List.of("s2", "s7"))) {
            ObjectNode proposal = bmi();
            ArrayNode references = ((ObjectNode) proposal.at("/results/3")).putArray("sourceIds");
            ids.forEach(references::add);
            ReadOnlyWebDiagnosticResult result = assess(BMI_REQUEST, proposal, original());
            limited(result);
            assertEquals("SOURCE_REFERENCE_INVALID", component(result, "SCRIPT_DISTINCTION").reason());
            assertTrue(component(result, "CALCULATION").met());
        }
    }

    @Test
    void requiredPayloadsCannotBeNull() throws Exception {
        for (String field : List.of("target", "calculation", "sourceIds")) {
            ObjectNode proposal = bmi();
            if (field.equals("target")) proposal.putNull(field);
            else ((ObjectNode) proposal.at(field.equals("calculation") ? "/results/2" : "/results/3")).putNull(field);
            ReadOnlyWebDiagnosticResult result = assess(BMI_REQUEST, proposal, original());
            limited(result);
            assertEquals(field.equals("sourceIds") ? "PROPOSAL_ARRAY_INVALID" : "PROPOSAL_FIELDS_INVALID",
                    result.components().components().getFirst().reason());
        }
    }

    @Test
    void markdownProseAndInventedAnswerFieldsDoNotGainAdmission() throws Exception {
        String valid = bmi().toString();
        for (String candidate : List.of("```json\n" + valid + "\n```", "Here is the object:\n" + valid,
                valid + "\nThe answer is 22.2.")) {
            limited(ReadOnlyAnswerProposal.assess(BMI_REQUEST, candidate, original()));
        }
        ObjectNode extra = bmi();
        extra.put("answer", "22.2");
        ReadOnlyWebDiagnosticResult result = assess(BMI_REQUEST, extra, original());
        limited(result);
        assertEquals("PROPOSAL_FIELDS_INVALID", result.components().components().getFirst().reason());
    }

    @Test
    void unresolvedValidationDoesNotEraseSupportedSelections() throws Exception {
        String quote = "explain whether the handler validates empty or nonnumeric input before using it";
        String request = BMI_REQUEST + " Also " + quote + ".";
        ObjectNode proposal = bmi();
        ((ArrayNode) proposal.get("results")).addObject().put("kind", "INPUT_VALIDATION").put("requestQuote", quote);
        ReadOnlyWebDiagnosticResult result = assess(request, proposal, original());
        limited(result);
        for (String kind : List.of("DIAGNOSIS", "SELECTOR", "CALCULATION", "SCRIPT_DISTINCTION"))
            assertTrue(component(result, kind).met(), kind);
        number(result.answer(), "22.2");
        assertEquals("INPUT_VALIDATION_UNRESOLVED", component(result, "INPUT_VALIDATION").reason());

        proposal.putArray("unresolvedRequestQuotes").add(quote);
        ReadOnlyWebDiagnosticResult obsoleteField = assess(request, proposal, original());
        limited(obsoleteField);
        assertEquals("PROPOSAL_FIELDS_INVALID", obsoleteField.components().components().getFirst().reason());
    }

    @Test
    void authenticExampleFormulaTextDoesNotProveItsRelevance() throws Exception {
        List<Source> sources = power("voltage * current");
        List<Source> obsolete = new ArrayList<>(sources);
        obsolete.set(0, source("guide.md", "An obsolete definition was voltage multiplied by current. "
                + "It is rejected. The current definition is voltage plus current.\n"));
        ReadOnlyWebDiagnosticResult result = assess(exampleRequest(), example(), obsolete);
        // This structurally admitted interpretation is a semantic acceptance failure.
        assertEquals("ATTRIBUTED_INTERPRETATION", component(result, "CALCULATION").support().name());
        assertTrue(result.answer().contains("not treated as proof of meaning"));
        number(component(result, "CALCULATION").text(), "18.0");
    }

    @Test
    void exampleDoesNotBypassCandidateRequestOrObservedContextBounds() {
        String valid = exampleText();
        List<Source> sources = power("voltage * current");
        limited(ReadOnlyAnswerProposal.assess(exampleRequest(), " ".repeat(ReadOnlyAnswerProposal.MAX_CANDIDATE_CHARS) + valid, sources));
        limited(ReadOnlyAnswerProposal.assess(exampleRequest() + " ".repeat(AnswerRequirements.MAX_REQUEST_CHARS), valid, sources));
        List<Source> excessiveCount = new ArrayList<>(sources);
        for (int index = 0; index < 5; index++) excessiveCount.add(source("note-" + index + ".md", "Retained note " + index));
        assertFalse(ReadOnlyAnswerProposal.eligible(excessiveCount));
        limited(ReadOnlyAnswerProposal.assess(exampleRequest(), valid, excessiveCount));
        List<Source> excessiveText = new ArrayList<>(sources);
        excessiveText.add(source("notes.md", "x".repeat(16_000)));
        assertFalse(ReadOnlyAnswerProposal.eligible(excessiveText));
        limited(ReadOnlyAnswerProposal.assess(exampleRequest(), valid, excessiveText));
        List<Source> unobservedLink = List.of(sources.get(0), sources.get(1), sources.get(3));
        assertFalse(ReadOnlyAnswerProposal.eligible(unobservedLink));
        limited(ReadOnlyAnswerProposal.assess(exampleRequest(), valid, unobservedLink));
    }

    private static ObjectNode bmi() throws Exception {
        ObjectNode proposal = example();
        ((ArrayNode) proposal.get("results")).remove(3);
        ((ObjectNode) proposal.get("target")).put("buttonId", "calculate").put("requestQuote", "Calculate button");
        ((ObjectNode) proposal.at("/results/0")).put("requestQuote", "Explain why the Calculate button fails");
        ((ObjectNode) proposal.at("/results/2")).put("requestQuote", "the BMI for 72 kg and 1.8 m");
        reference(proposal).put("uniqueQuote", "weight divided by height squared");
        ((ObjectNode) calculation(proposal).at("/operands/0")).put("variable", "weight").put("inputId", "weight").put("requestQuote", "72 kg");
        ((ObjectNode) calculation(proposal).at("/operands/1")).put("variable", "height").put("inputId", "height").put("requestQuote", "1.8 m");
        return proposal;
    }

    private static ObjectNode calculation(ObjectNode proposal) { return (ObjectNode) proposal.at("/results/2/calculation"); }
    private static ObjectNode reference(ObjectNode proposal) { return (ObjectNode) calculation(proposal).get("formulaReference"); }
    private static ObjectNode example() throws Exception { return (ObjectNode) JSON.readTree(exampleText()); }
    private static String exampleText() {
        return section(ReadOnlyAnswerProposal.instructions(original()), "Example output:\n", "\nEnd example.");
    }
    private static String exampleRequest() {
        return section(ReadOnlyAnswerProposal.instructions(original()), "Example request:\n", "\nExample sources:");
    }
    private static String section(String text, String start, String end) {
        int from = text.indexOf(start);
        assertTrue(from >= 0);
        assertEquals(from, text.lastIndexOf(start));
        int to = text.indexOf(end, from + start.length());
        assertTrue(to >= 0);
        assertEquals(to, text.lastIndexOf(end));
        return text.substring(from + start.length(), to);
    }
    private static ReadOnlyWebDiagnosticResult assess(String request, ObjectNode proposal, List<Source> sources) {
        return ReadOnlyAnswerProposal.assess(request, proposal.toString(), sources);
    }
    private static ReadOnlyAnswerComponents.Component component(ReadOnlyWebDiagnosticResult result, String kind) {
        return result.components().components().stream().filter(part -> part.kind().equals(kind)).findFirst().orElseThrow();
    }
    private static void complete(ReadOnlyWebDiagnosticResult result) {
        assertFalse(result.assessment().limited(), result.answer());
        assertTrue(result.components().complete(), result.answer());
        assertTrue(result.components().deliveredIntact(result.answer()));
        assertEquals("INTERPRETED", result.components().coverageOrigin().name());
    }
    private static void limited(ReadOnlyWebDiagnosticResult result) {
        assertTrue(result.assessment().limited(), result.answer());
        assertFalse(result.components().complete());
        assertFalse(result.components().deliveredIntact(result.answer()));
    }
    private static void number(String text, String expected) {
        assertTrue(Pattern.compile("(?<![0-9.])" + Pattern.quote(expected) + "(?![0-9.])").matcher(text).find(), text);
    }
    private static List<Source> power(String expression) {
        String html = """
                <!doctype html>
                <html lang="en">
                <head><meta charset="utf-8"><title>Power fixture</title></head>
                <body>
                <label>Voltage (V)<input id="voltage" value="1"></label>
                <label>Current (A)<input id="current" value="1"></label>
                <button id="power">Power</button>
                <p id="result"></p>
                <script src="power.js"></script>
                </body>
                </html>
                """;
        String script = """
                const button = document.querySelector('#switch');
                button.addEventListener('click', () => {
                  const voltage = Number(document.querySelector('#voltage').value);
                  const current = Number(document.querySelector('#current').value);
                  document.querySelector('#result').textContent = (%s).toFixed(1);
                });
                """.formatted(expression);
        return List.of(source("guide.md", "The current definition of power is voltage multiplied by current.\n"),
                source("page.html", html), source("power.js", script), source("spare.js", "const spare = 'KEEP';\n"));
    }
    private static List<Source> original() {
        return List.of(source("README.md", README), source("index.html", HTML), source("script.js", SCRIPT), source("scripts.js", SENTINEL));
    }
    private static List<Source> panel() {
        String readme = README.replace("Calculator review fixture", "Panel calculator review fixture")
                .replace("BMI as weight divided by height squared", "panel area as span multiplied by rise");
        String html = HTML.replace("Weight (kg)", "Span (m)").replace("Height (m)", "Rise (m)")
                .replace("id=\"weight\" value=\"72\"", "id=\"span\" value=\"12\"")
                .replace("id=\"height\" value=\"1.8\"", "id=\"rise\" value=\"2.5\"");
        String script = SCRIPT.replace("weight", "span").replace("height", "rise")
                .replace("span / (rise * rise)", "span + rise");
        return List.of(source("README.md", readme), source("index.html", html), source("script.js", script), source("scripts.js", SENTINEL));
    }
    private static Source source(String path, String text) {
        return new Source(path, text, UUID.nameUUIDFromBytes((path + "\n" + text).getBytes(StandardCharsets.UTF_8)).toString(),
                1, ReadOnlyWebDiagnosticResult.textHash(text), SOURCE_PROJECTION_VERSION);
    }
    private record Fixture(String request, List<Source> sources) {}
}
