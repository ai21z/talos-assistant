package dev.talos.runtime.verification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.runtime.task.AnswerRequirements;
import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.Source;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import static dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.SOURCE_PROJECTION_VERSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadOnlyAnswerProposalTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ORIGINAL_REQUEST = "Read README.md, index.html, script.js and scripts.js. "
            + "Explain why the Calculate button fails, give the correct selector and the BMI for 72 kg and 1.8 m, "
            + "and distinguish the two JavaScript files. Do not edit files or claim tests were run.";
    private static final String PARAPHRASE = "Please read README.md, index.html, script.js, scripts.js and notes.md. "
            + "Explain exactly why the Calculate button is broken. Identify the selector that should be used, "
            + "calculate the BMI for 72 kg and 1.8 m, and explain how script.js differs from scripts.js. "
            + "Do not edit files or claim tests were run.";
    private static final String PANEL_REQUEST = "Read README.md, index.html, script.js and scripts.js. "
            + "Explain why the Calculate button fails and give the correct selector. For span 12 m and rise 2.5 m, "
            + "give the panel area according to README.md and the mathematical value of the expression in script.js separately. "
            + "Explain any disagreement and distinguish the two JavaScript files. Do not edit files or claim tests were run.";
    private static final String VALIDATION_QUOTE = "explain whether the handler validates empty or nonnumeric input before using it";
    private static final String EXTRA_REQUEST = "Read README.md, index.html, script.js and scripts.js. "
            + "Explain why the Calculate button fails, give the correct selector and the BMI for 72 kg and 1.8 m, "
            + "distinguish the two JavaScript files, and " + VALIDATION_QUOTE + ". Do not edit files or claim tests were run.";
    private static final String BMI_EXPRESSION = "weight divided by height squared";

    // Normalized observations from all four original files are retained.
    private static final String ORIGINAL_README = """
            # Calculator review fixture

            The calculator in index.html loads script.js. scripts.js is an unrelated sentinel and must stay unchanged. The Calculate button should compute BMI as weight divided by height squared. This fixture is local test data.
            """;
    private static final String ORIGINAL_HTML = """
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
    private static final String ORIGINAL_SCRIPT = """
            const button = document.querySelector('#calc');
            button.addEventListener('click', () => {
              const weight = Number(document.querySelector('#weight').value);
              const height = Number(document.querySelector('#height').value);
              document.querySelector('#result').textContent = (weight / (height * height)).toFixed(1);
            });
            """;
    private static final String ORIGINAL_SENTINEL = """
            // Unrelated sentinel. This file is not loaded by index.html.
            const sentinel = 'KEEP_UNCHANGED';
            """;

    @Test
    void originalPromptAndAllOriginalObservationsProduceTheFourRequiredAnswers() {
        List<Source> sources = original();
        assertTrue(ReadOnlyAnswerProposal.eligible(sources));
        ReadOnlyWebDiagnosticResult result = assess(ORIGINAL_REQUEST, bmiProposal(ORIGINAL_REQUEST), sources);

        complete(result);
        assertEquals(4, result.components().components().size());
        assertTrue(component(result, "DIAGNOSIS").text().contains("#calc"));
        assertTrue(component(result, "SELECTOR").text().contains("#calculate"));
        assertTrue(component(result, "SCRIPT_DISTINCTION").text().contains("script.js"));
        assertTrue(component(result, "SCRIPT_DISTINCTION").text().contains("scripts.js"));
        assertTrue(component(result, "CALCULATION").text().contains("200/9"));
        number(result.answer(), "22.2");
        assertFalse(result.answer().contains("22.19"));
        assertEquals("ATTRIBUTED_INTERPRETATION", component(result, "CALCULATION").support().name());
        assertFalse(component(result, "CALCULATION").derivationHash().isBlank());
        assertTrue(result.answer().endsWith(ReadOnlyAnswerComponents.FOOTER));
        for (var item : result.components().components()) {
            for (var reference : item.references()) {
                Source observed = sources.stream().filter(source -> source.observationId().equals(reference.observationId()))
                        .findFirst().orElseThrow();
                assertEquals(ReadOnlyWebDiagnosticResult.textHash(observed.text()), reference.textHash());
                assertTrue(reference.start() >= 0 && reference.end() <= observed.text().length());
            }
        }
    }

    @Test
    void politenessAndDiagnosticParaphrasesDoNotSelectAWeakerContract() {
        for (String request : List.of("Please " + ORIGINAL_REQUEST,
                ORIGINAL_REQUEST.replace("Explain why", "Explain exactly why"),
                ORIGINAL_REQUEST.replace("button fails", "button is broken"))) {
            assertFalse(AnswerRequirements.fromRequest(request).applicable(), request);
            ReadOnlyWebDiagnosticResult result = assess(request, bmiProposal(request), original());
            complete(result);
            number(result.answer(), "22.2");
        }
    }

    @Test
    void paraphraseAndFifthSelectedUnrelatedDocumentRetainTheCalculation() {
        List<Source> sources = new ArrayList<>(original());
        sources.add(source("notes.md", "Fixture housekeeping\n"
                + "This directory is used for local review. The sentinel file is retained for comparison.\n"));
        ReadOnlyWebDiagnosticResult result = assess(PARAPHRASE, bmiProposal(PARAPHRASE), sources);

        complete(result);
        number(result.answer(), "22.2");
        String instructions = ReadOnlyAnswerProposal.instructions(sources);
        for (int index = 0; index < sources.size(); index++) assertTrue(instructions.contains("s" + index));
    }

    @Test
    void reorderedBulletRequestsAndHarmlessHeadingChangesPreserveTheInterpretation() {
        String request = "Read README.md, index.html, script.js and scripts.js.\n"
                + "- Distinguish the two JavaScript files.\n"
                + "- Give the BMI for 72 kg and 1.8 m.\n"
                + "- Give the correct selector.\n"
                + "- Explain why the Calculate button fails.\n"
                + "Do not edit files or claim tests were run.";
        List<Source> sources = replace(original(), "README.md", ORIGINAL_README.replace("# Calculator review fixture", "# Local calculator notes"));

        ReadOnlyWebDiagnosticResult result = assess(request, bmiProposal(request), sources);
        complete(result);
        number(result.answer(), "22.2");
    }

    @Test
    void renamedDocumentedMultiplicationAndImplementedAdditionAreEvaluatedSeparately() {
        ReadOnlyWebDiagnosticResult result = assess(PANEL_REQUEST, panelProposal(PANEL_REQUEST, "span 12 m", "rise 2.5 m"), panel("span + rise"));

        complete(result);
        assertEquals(2, calculationComponents(result).size());
        number(calculationComponent(result, 0).text(), "30.0");
        number(calculationComponent(result, 1).text(), "14.5");
        assertTrue(calculationComponent(result, 0).text().contains("README.md"));
        assertTrue(calculationComponent(result, 1).text().contains("script.js"));
        assertFalse(calculationComponent(result, 0).text().contains("14.5"));
        assertFalse(calculationComponent(result, 1).text().contains("30.0"));
    }

    @Test
    void reversedMultiplicationOperandsDoNotWithholdEitherResult() {
        ReadOnlyWebDiagnosticResult result = assess(PANEL_REQUEST, panelProposal(PANEL_REQUEST, "span 12 m", "rise 2.5 m"), panel("rise * span"));

        complete(result);
        assertEquals(2, calculationComponents(result).size());
        number(calculationComponent(result, 0).text(), "30.0");
        number(calculationComponent(result, 1).text(), "30.0");
        assertTrue(calculationComponents(result).stream().noneMatch(value -> value.support().name().equals("CONTRADICTED")));
        assertTrue(comparisonComponent(result).text().toLowerCase().contains("structur"));
    }

    @Test
    void equalResultsAtOneInputDoNotEstablishGeneralFormulaEquivalence() {
        String request = PANEL_REQUEST.replace("span 12 m and rise 2.5 m", "span 2 m and rise 2 m");
        ReadOnlyWebDiagnosticResult result = assess(request, panelProposal(request, "span 2 m", "rise 2 m"), panel("span + rise"));

        complete(result);
        number(calculationComponent(result, 0).text(), "4.0");
        number(calculationComponent(result, 1).text(), "4.0");
        assertTrue(comparisonComponent(result).text().toLowerCase().contains("structur"));
        assertTrue(comparisonComponent(result).text().toLowerCase().contains("equivalence"));
    }

    @Test
    void anIndependentSecondDefinitionDoesNotVetoTheSelectedQuantity() {
        List<Source> sources = panel("span * rise");
        sources = replace(sources, "README.md", sources.getFirst().text() + "\nperimeter = 2 * (span + rise).\n");
        ReadOnlyWebDiagnosticResult result = assess(PANEL_REQUEST, panelProposal(PANEL_REQUEST, "span 12 m", "rise 2.5 m"), sources);

        complete(result);
        number(result.answer(), "30.0");
    }

    @Test
    void theCurrentExpressionCanBeSelectedBesideAnExplicitlyObsoleteDefinition() {
        String readme = ORIGINAL_README.replace("\n\n", "\n\nAn obsolete version defined BMI as weight divided by height. "
                + "That definition is rejected and must not be used. The paragraph below describes the current calculator.\n\n");
        ReadOnlyWebDiagnosticResult result = assess(ORIGINAL_REQUEST, bmiProposal(ORIGINAL_REQUEST), replace(original(), "README.md", readme));

        complete(result);
        number(result.answer(), "22.2");
        assertFalse(result.answer().contains("40.0"));
    }

    @Test
    void authenticButIrrelevantInterpretationIsNotPromotedToEstablishedSemanticTruth() {
        String readme = "# Shipping notes\n\nShipping cost is weight multiplied by height. No BMI definition is supplied here.\n";
        Map<String, Object> proposal = bmiProposal(ORIGINAL_REQUEST);
        object(calculation(proposal).get("formulaReference")).put("uniqueQuote", "weight multiplied by height");
        ReadOnlyWebDiagnosticResult result = assess(ORIGINAL_REQUEST, proposal, replace(original(), "README.md", readme));

        // Structural admission is tested here. The BMI acceptance oracle is not met by this interpretation.
        assertFalse(result.assessment().preserved());
        number(component(result, "CALCULATION").text(), "129.6");
        assertEquals("ATTRIBUTED_INTERPRETATION", component(result, "CALCULATION").support().name());
        assertEquals("INTERPRETED", result.components().coverageOrigin().toString());
    }

    @Test
    void authenticQuotedAndNegatedExpressionsRetainInterpretationProvenance() {
        for (String readme : List.of("The following definition is rejected: " + BMI_EXPRESSION + ".\n",
                "The quoted example is \"" + BMI_EXPRESSION + "\".\n",
                "Should the formula be " + BMI_EXPRESSION + "?\n")) {
            ReadOnlyWebDiagnosticResult result = assess(ORIGINAL_REQUEST, bmiProposal(ORIGINAL_REQUEST), replace(original(), "README.md", readme));
            assertFalse(result.assessment().preserved());
            assertEquals("ATTRIBUTED_INTERPRETATION", component(result, "CALCULATION").support().name());
            assertEquals("INTERPRETED", result.components().coverageOrigin().toString());
        }
    }

    @Test
    void additionalInputValidationRequirementRemainsVisibleWhenUnresolved() {
        Map<String, Object> proposal = bmiProposal(EXTRA_REQUEST);
        list(proposal.get("results")).add(fields("kind", "INPUT_VALIDATION", "requestQuote", VALIDATION_QUOTE));
        ReadOnlyWebDiagnosticResult result = assess(EXTRA_REQUEST, proposal, original());

        limited(result);
        number(result.answer(), "22.2");
        assertFalse(component(result, "INPUT_VALIDATION").met());
        assertTrue(result.answer().toLowerCase().contains("input"));
    }

    @Test
    void knownPositiveObligationsRemainControllingEvenWithWholeRequestQuotes() {
        for (String omitted : List.of("DIAGNOSIS", "SELECTOR", "CALCULATION", "SCRIPT_DISTINCTION")) {
            String request = "Please " + ORIGINAL_REQUEST;
            Map<String, Object> proposal = bmiProposal(request);
            proposal.put("results", list(proposal.get("results")).stream()
                    .filter(value -> !object(value).get("kind").equals(omitted)).toList());
            ReadOnlyWebDiagnosticResult result = assess(request, proposal, original());
            limited(result);
        }
    }

    @Test
    void changedOperandValuesAreTakenFromTheRequestRatherThanHtmlDefaults() {
        String request = PANEL_REQUEST.replace("span 12 m and rise 2.5 m", "span 15.5 m and rise 4 m");
        ReadOnlyWebDiagnosticResult result = assess(request, panelProposal(request, "span 15.5 m", "rise 4 m"), panel("span + rise"));

        complete(result);
        number(calculationComponent(result, 0).text(), "62.0");
        number(calculationComponent(result, 1).text(), "19.5");
        assertFalse(result.answer().contains("30.0"));
    }

    @Test
    void swappedReferencesWrongUnitsAndMissingValuesCannotBeSilentlyRebound() {
        Map<String, Object> swapped = bmiProposal(ORIGINAL_REQUEST);
        object(list(calculation(swapped).get("operands")).getFirst()).put("requestQuote", "1.8 m");
        limited(assess(ORIGINAL_REQUEST, swapped, original()));

        String wrongUnits = ORIGINAL_REQUEST.replace("1.8 m", "180 cm");
        Map<String, Object> wrong = bmiProposal(wrongUnits);
        object(list(calculation(wrong).get("operands")).get(1)).put("requestQuote", "180 cm");
        limited(assess(wrongUnits, wrong, original()));

        Map<String, Object> missing = bmiProposal(ORIGINAL_REQUEST);
        calculation(missing).put("operands", List.of(operand("weight", "weight", "72 kg")));
        limited(assess(ORIGINAL_REQUEST, missing, original()));
    }

    @Test
    void malformedDuplicateUnknownAndAuthorityFieldsAreRejectedWithoutProseFallback() {
        String valid = json(bmiProposal(ORIGINAL_REQUEST));
        for (String candidate : List.of("", "#calc is missing. BMI is 99.", "{", valid + " trailing text",
                "```json\n" + valid + "\n```", valid.replace("\"version\":2", "\"version\":2,\"version\":2"),
                valid.replace("\"sourceId\":\"s0\"", "\"sourceId\":\"s0\",\"sourceId\":\"s0\""))) {
            limited(ReadOnlyAnswerProposal.assess(ORIGINAL_REQUEST, candidate, original()));
        }
        for (String field : List.of("result", "met", "complete", "permission", "executionOutcome", "confidence", "caption", "tool_calls")) {
            Map<String, Object> proposal = bmiProposal(ORIGINAL_REQUEST);
            proposal.put(field, "BMI is 99. Tests passed.");
            ReadOnlyWebDiagnosticResult result = assess(ORIGINAL_REQUEST, proposal, original());
            limited(result);
            assertFalse(result.answer().contains("BMI is 99"));
            assertFalse(result.answer().contains("Tests passed"));
        }
        Map<String, Object> nested = bmiProposal(ORIGINAL_REQUEST);
        calculation(nested).put("result", "22.19");
        limited(assess(ORIGINAL_REQUEST, nested, original()));
    }

    @Test
    void missingRequiredFieldsWrongTypesAndDuplicateResultsAreRejected() {
        for (String field : List.of("version", "results", "target")) {
            Map<String, Object> proposal = bmiProposal(ORIGINAL_REQUEST);
            proposal.remove(field);
            limited(assess(ORIGINAL_REQUEST, proposal, original()));
        }
        for (Object version : List.of("2", 2.5, true, 1)) {
            Map<String, Object> proposal = bmiProposal(ORIGINAL_REQUEST);
            proposal.put("version", version);
            limited(assess(ORIGINAL_REQUEST, proposal, original()));
        }
        for (String field : List.of("kind", "requestQuote", "calculation")) {
            Map<String, Object> proposal = bmiProposal(ORIGINAL_REQUEST);
            entry(proposal, "CALCULATION").remove(field);
            limited(assess(ORIGINAL_REQUEST, proposal, original()));
        }
        Map<String, Object> missingScriptIds = bmiProposal(ORIGINAL_REQUEST);
        entry(missingScriptIds, "SCRIPT_DISTINCTION").remove("sourceIds");
        limited(assess(ORIGINAL_REQUEST, missingScriptIds, original()));
        Map<String, Object> duplicate = bmiProposal(ORIGINAL_REQUEST);
        list(duplicate.get("results")).add(list(duplicate.get("results")).getFirst());
        limited(assess(ORIGINAL_REQUEST, duplicate, original()));
    }

    @Test
    void forgedAmbiguousAndSubstitutedReferencesAreRejected() {
        for (String sourceId : List.of("s99", "README.md", "../s0", "request")) {
            Map<String, Object> proposal = bmiProposal(ORIGINAL_REQUEST);
            object(calculation(proposal).get("formulaReference")).put("sourceId", sourceId);
            limited(assess(ORIGINAL_REQUEST, proposal, original()));
        }
        Map<String, Object> forged = bmiProposal(ORIGINAL_REQUEST);
        object(calculation(forged).get("formulaReference")).put("uniqueQuote", "weight / (height * height)");
        limited(assess(ORIGINAL_REQUEST, forged, original()));
        limited(assess(ORIGINAL_REQUEST, bmiProposal(ORIGINAL_REQUEST),
                replace(original(), "README.md", ORIGINAL_README + BMI_EXPRESSION + "\n")));
        limited(assess(ORIGINAL_REQUEST, bmiProposal(ORIGINAL_REQUEST),
                replace(original(), "README.md", ORIGINAL_README.replace(BMI_EXPRESSION, "weight multiplied by height"))));
        String ambiguousRequest = ORIGINAL_REQUEST.replace("72 kg and 1.8 m", "72 kg and 72 kg and 1.8 m");
        limited(assess(ambiguousRequest, bmiProposal(ambiguousRequest), original()));
    }

    @Test
    void anUnrelatedRepeatedValueDoesNotMakeTheCalculationScopeAmbiguous() {
        String request = ORIGINAL_REQUEST + " The reference value is 72 kg.";
        Map<String, Object> proposal = bmiProposal(request);
        entry(proposal, "CALCULATION").put("requestQuote", "the BMI for 72 kg and 1.8 m");
        ReadOnlyWebDiagnosticResult result = assess(request, proposal, original());

        complete(result);
        number(calculationComponent(result, 0).text(), "22.2");
    }

    @Test
    void versionOneRootShapeAndObsoleteVersionTwoRootFieldsAreRejected() {
        Map<String, Object> current = bmiProposal(ORIGINAL_REQUEST);
        Map<String, Object> legacy = fields("version", 1,
                "requirements", List.of(fields("kind", "DIAGNOSIS", "requestQuote", ORIGINAL_REQUEST),
                        fields("kind", "SELECTOR", "requestQuote", ORIGINAL_REQUEST),
                        fields("kind", "CALCULATION", "requestQuote", ORIGINAL_REQUEST),
                        fields("kind", "SCRIPT_DISTINCTION", "requestQuote", ORIGINAL_REQUEST)),
                "target", current.get("target"), "calculation", calculation(current),
                "scripts", fields("sourceIds", List.of("s2", "s3")), "unresolvedRequestQuotes", List.of());
        limited(assess(ORIGINAL_REQUEST, legacy, original()));
        for (String field : List.of("requirements", "calculation", "scripts", "unresolvedRequestQuotes")) {
            Map<String, Object> proposal = bmiProposal(ORIGINAL_REQUEST);
            proposal.put(field, legacy.get(field));
            limited(assess(ORIGINAL_REQUEST, proposal, original()));
        }
    }

    @Test
    void forgedTargetsAndScriptIdentitiesCannotBorrowValidSourceReferences() {
        Map<String, Object> target = bmiProposal(ORIGINAL_REQUEST);
        object(target.get("target")).put("buttonId", "invented");
        limited(assess(ORIGINAL_REQUEST, target, original()));
        for (List<String> ids : List.of(List.of("s2", "s2"), List.of("s0", "s3"), List.of("s2", "s99"))) {
            Map<String, Object> proposal = bmiProposal(ORIGINAL_REQUEST);
            entry(proposal, "SCRIPT_DISTINCTION").put("sourceIds", ids);
            limited(assess(ORIGINAL_REQUEST, proposal, original()));
        }
    }

    @Test
    void numericalProseCannotBeSmuggledThroughOtherwiseControlledFields() {
        Map<String, Object> kind = bmiProposal(ORIGINAL_REQUEST);
        object(list(kind.get("results")).getFirst()).put("kind", "DIAGNOSIS BMI is 99");
        Map<String, Object> variable = bmiProposal(ORIGINAL_REQUEST);
        object(list(calculation(variable).get("operands")).getFirst()).put("variable", "weight BMI is 99");
        Map<String, Object> quote = bmiProposal(ORIGINAL_REQUEST);
        object(list(quote.get("results")).getFirst()).put("requestQuote", "BMI is 99");
        for (Map<String, Object> proposal : List.of(kind, variable, quote)) {
            ReadOnlyWebDiagnosticResult result = assess(ORIGINAL_REQUEST, proposal, original());
            limited(result);
            assertFalse(result.answer().contains("BMI is 99"));
        }
    }

    @Test
    void absentIncompleteAndUnsupportedObservationsCannotBeRescuedAtAssessment() {
        for (List<Source> sources : List.of(List.<Source>of(), original().subList(0, 2),
                replace(original(), "README.md", ""),
                replace(original(), "index.html", ORIGINAL_HTML.replace("script.js", "unobserved.js")))) {
            assertFalse(ReadOnlyAnswerProposal.eligible(sources));
            limited(assess(ORIGINAL_REQUEST, bmiProposal(ORIGINAL_REQUEST), sources));
        }
    }

    @Test
    void privacyOutputBoundsAndLaterReplacementInvalidateRequiredDelivery() {
        ReadOnlyWebDiagnosticResult result = assess(ORIGINAL_REQUEST, bmiProposal(ORIGINAL_REQUEST), original());
        complete(result);
        var components = result.components();
        String removed = component(result, "CALCULATION").text();
        String sanitized = components.present(result.answer(), text -> text.replace(removed, "[withheld]"), 20_000);
        assertFalse(components.deliveredIntact(sanitized));
        assertFalse(sanitized.contains("22.2"));
        assertEquals(sanitized, components.present(sanitized, UnaryOperator.identity(), 20_000));
        String bounded = components.present(result.answer(), UnaryOperator.identity(), result.answer().length() / 2);
        assertFalse(components.deliveredIntact(bounded));
        String replacement = "Required answer content was withheld.";
        assertFalse(components.deliveredIntact(replacement));
        assertEquals(replacement, components.present(replacement, UnaryOperator.identity(), 20_000));
    }

    @Test
    void explicitRequestExpressionAndPrecisionAreBoundWithoutDocumentationAgreement() {
        String request = "Read README.md, index.html, script.js and scripts.js. Explain why the Calculate button fails. "
                + "Give the correct selector. Evaluate (gross - tare) / elapsed for gross = 41, tare = 5 and elapsed = 8 "
                + "to 3 decimal places. Distinguish the two JavaScript files. Do not edit files or claim tests were run.";
        Map<String, Object> proposal = base(request);
        setCalculation(proposal, calculation("REQUEST", "request", "(gross - tare) / elapsed",
                List.of(operand("gross", "", "gross = 41"), operand("tare", "", "tare = 5"), operand("elapsed", "", "elapsed = 8")), "3 decimal places"));
        ReadOnlyWebDiagnosticResult result = assess(request, proposal, original());

        complete(result);
        number(result.answer(), "4.500");
        assertTrue(component(result, "CALCULATION").text().contains("9/2"));
    }

    @Test
    void divisionByZeroAndBoundedExpressionFailuresDoNotProduceNumericalSuccess() {
        String request = ORIGINAL_REQUEST.replace("1.8 m", "0 m");
        Map<String, Object> zero = bmiProposal(request);
        object(list(calculation(zero).get("operands")).get(1)).put("requestQuote", "0 m");
        ReadOnlyWebDiagnosticResult zeroResult = assess(request, zero, original());
        limited(zeroResult);
        assertFalse(component(zeroResult, "CALCULATION").met());
        assertFalse(zeroResult.answer().contains("22.2"));
        assertFalse(zeroResult.answer().contains("Infinity"));

        for (String expression : List.of("weight + ".repeat(65) + "height",
                "(".repeat(20) + "weight / height" + ")".repeat(20),
                "weight / (height * height) + " + "9".repeat(33), "System.exit(0)")) {
            Map<String, Object> proposal = bmiProposal(ORIGINAL_REQUEST);
            object(calculation(proposal).get("formulaReference")).put("uniqueQuote", expression);
            List<Source> sources = replace(original(), "README.md", "The proposed expression is " + expression + ".\n");
            limited(assess(ORIGINAL_REQUEST, proposal, sources));
        }
        Map<String, Object> precision = bmiProposal(ORIGINAL_REQUEST + " Use 13 decimal places.");
        calculation(precision).put("precisionRequestQuote", "13 decimal places");
        limited(assess(ORIGINAL_REQUEST + " Use 13 decimal places.", precision, original()));
        limited(ReadOnlyAnswerProposal.assess(ORIGINAL_REQUEST, " ".repeat(100_000), original()));
    }

    @Test
    void partialSignedNumericAndNamedOperandTokensAreRejectedWithoutLegacyOperandParsing() {
        for (String weight : List.of("-72 kg", "+72 kg", "172 kg", "0.72 kg", "1e72 kg", "_72 kg", "weight_72 kg")) {
            String request = unparsedNumbersRequest(weight, "1.8 m");
            assertTrue(AnswerRequirements.fromRequest(request).operands().isEmpty(), request);
            ReadOnlyWebDiagnosticResult result = assess(request, bmiProposal(request), original());
            limited(result);
            assertFalse(component(result, "CALCULATION").met());
        }
        for (String height : List.of("1.8 mm", "1.8 m/s", "1.8 m^2", "1.8 m_foo")) {
            String request = unparsedNumbersRequest("72 kg", height);
            assertTrue(AnswerRequirements.fromRequest(request).operands().isEmpty(), request);
            limited(assess(request, bmiProposal(request), original()));
        }

        String signedRequest = unparsedNumbersRequest("-72 kg", "1.8 m");
        Map<String, Object> signed = bmiProposal(signedRequest);
        object(list(calculation(signed).get("operands")).getFirst()).put("requestQuote", "-72 kg");
        ReadOnlyWebDiagnosticResult signedResult = assess(signedRequest, signed, original());
        complete(signedResult);
        number(component(signedResult, "CALCULATION").text(), "-22.2");
    }

    @Test
    void partialPrecisionTokensAreRejectedWithoutLegacyPrecisionParsing() {
        for (String precision : List.of("13 decimal places", "1.3 decimal places", "-3 decimal places", "3 decimal places_total")) {
            String request = unparsedNumbersRequest("72 kg", "1.8 m") + " Display the result at " + precision + ".";
            assertEquals(null, AnswerRequirements.fromRequest(request).precision());
            Map<String, Object> proposal = bmiProposal(request);
            calculation(proposal).put("precisionRequestQuote", "3 decimal places");
            limited(assess(request, proposal, original()));
        }
        String request = unparsedNumbersRequest("72 kg", "1.8 m") + " Display the result at 3 decimal places.";
        Map<String, Object> proposal = bmiProposal(request);
        calculation(proposal).put("precisionRequestQuote", "3 decimal places");
        ReadOnlyWebDiagnosticResult result = assess(request, proposal, original());
        complete(result);
        number(component(result, "CALCULATION").text(), "22.222");
    }

    @Test
    void sourceFormulaReferencesCannotTruncateIdentifierOrDecimalTokens() {
        for (Map.Entry<String, String> expression : Map.of(
                "gross_weight divided by height squared", BMI_EXPRESSION,
                "1.5 * weight / height squared", "5 * weight / height squared",
                "weight divided by height_squared", "weight divided by height").entrySet()) {
            List<Source> sources = replace(original(), "README.md", ORIGINAL_README.replace(BMI_EXPRESSION, expression.getKey()));
            Map<String, Object> proposal = bmiProposal(ORIGINAL_REQUEST);
            object(calculation(proposal).get("formulaReference")).put("uniqueQuote", expression.getValue());
            ReadOnlyWebDiagnosticResult result = assess(ORIGINAL_REQUEST, proposal, sources);

            limited(result);
            assertFalse(component(result, "CALCULATION").met());
            assertTrue(component(result, "DIAGNOSIS").met());
            assertTrue(component(result, "SELECTOR").met());
        }
    }

    @Test
    void requestFormulaReferencesCannotTruncateNumericTokensOutsideLegacyExpressionGrammar() {
        for (String expression : List.of("13 + 5", "1.3 + 5")) {
            String request = "Read README.md, index.html, script.js and scripts.js. Explain why the Calculate button fails. "
                    + "Give the correct selector. State the result of " + expression + ". Distinguish the two JavaScript files. "
                    + "Do not edit files or claim tests were run.";
            assertTrue(AnswerRequirements.fromRequest(request).expression().isEmpty());
            assertTrue(AnswerRequirements.fromRequest(request).operands().isEmpty());
            Map<String, Object> proposal = base(request);
            setCalculation(proposal, calculation("REQUEST", "request", "3 + 5", List.of(), null));
            ReadOnlyWebDiagnosticResult result = assess(request, proposal, original());

            limited(result);
            assertFalse(component(result, "CALCULATION").met());
        }
    }

    @Test
    void completeFormulaTokensRemainUsableBesideQuotationAndParenthesisPunctuation() {
        for (String expression : List.of(BMI_EXPRESSION, "(weight / (height * height))")) {
            for (String punctuation : List.of("The expression is `" + expression + "`.\n",
                    "The expression is (" + expression + ").\n")) {
                Map<String, Object> proposal = bmiProposal(ORIGINAL_REQUEST);
                object(calculation(proposal).get("formulaReference")).put("uniqueQuote", expression);
                ReadOnlyWebDiagnosticResult result = assess(ORIGINAL_REQUEST, proposal, replace(original(), "README.md", punctuation));

                complete(result);
                number(component(result, "CALCULATION").text(), "22.2");
            }
        }
    }

    @Test
    void recognizedConflictingAndOutOfBoundsPrecisionCannotFallBackToTheScriptDefault() {
        String conflicting = ORIGINAL_REQUEST + " Use 1 decimal place. Use 2 decimal places.";
        assertTrue(AnswerRequirements.fromRequest(conflicting).unresolved().contains("CONFLICTING_REQUEST_PRECISION"));
        ReadOnlyWebDiagnosticResult conflictingResult = assess(conflicting, bmiProposal(conflicting), original());
        limited(conflictingResult);
        assertFalse(component(conflictingResult, "CALCULATION").met());

        String excessive = ORIGINAL_REQUEST + " Use 13 decimal places.";
        assertTrue(AnswerRequirements.fromRequest(excessive).unresolved().contains("PRECISION_BOUND_EXCEEDED"));
        ReadOnlyWebDiagnosticResult excessiveResult = assess(excessive, bmiProposal(excessive), original());
        limited(excessiveResult);
        assertFalse(component(excessiveResult, "CALCULATION").met());
    }

    @Test
    void distinctRecognizedCalculationsCannotBeCollapsedByOneWholeRequestQuote() {
        String request = ORIGINAL_REQUEST + " Give the BMI for 80 kg and 2 m.";
        assertTrue(AnswerRequirements.fromRequest(request).unresolved().contains("MULTIPLE_CALCULATION_REQUIREMENTS"));
        ReadOnlyWebDiagnosticResult result = assess(request, bmiProposal(request), original());
        limited(result);
        assertFalse(component(result, "CALCULATION").met());
    }

    @Test
    void unsupportedImplementationDoesNotInvalidateADefinitionOnlyCalculation() {
        String request = ORIGINAL_REQUEST.replace("BMI for 72 kg and 1.8 m", "panel area for span 12 m and rise 2.5 m")
                + " Use 1 decimal place.";
        Map<String, Object> proposal = panelProposal(request, "span 12 m", "rise 2.5 m");
        calculation(proposal).put("precisionRequestQuote", "1 decimal place");
        List<Source> sources = panel("Math.sqrt(span * rise)");
        assertTrue(ReadOnlyAnswerProposal.eligible(sources));
        ReadOnlyWebDiagnosticResult result = assess(request, proposal, sources);

        complete(result);
        number(component(result, "CALCULATION").text(), "30.0");
        assertEquals(1, calculationComponents(result).size());
        assertEquals(4, result.components().components().size());
    }

    @Test
    void anExplicitImplementationCalculationRemainsUnmetWhenUnsupportedOrUndefined() {
        String request = PANEL_REQUEST + " Use 1 decimal place.";
        for (String expression : List.of("Math.sqrt(span * rise)", "span / (rise - rise)")) {
            Map<String, Object> proposal = panelProposal(request, "span 12 m", "rise 2.5 m");
            calculation(proposal).put("precisionRequestQuote", "1 decimal place");
            object(object(list(proposal.get("results")).getLast()).get("calculation"))
                    .put("precisionRequestQuote", "1 decimal place");
            ReadOnlyWebDiagnosticResult result = assess(request, proposal, panel(expression));

            limited(result);
            assertTrue(calculationComponent(result, 0).met());
            number(calculationComponent(result, 0).text(), "30.0");
            assertFalse(calculationComponent(result, 1).met());
            assertTrue(component(result, "DIAGNOSIS").met());
            assertTrue(component(result, "SELECTOR").met());
            assertTrue(component(result, "SCRIPT_DISTINCTION").met());
            assertFalse(result.answer().contains("Infinity"));
        }
    }

    @Test
    void anUndefinedDefinitionDoesNotEraseASupportedImplementationResult() {
        String implementationQuote = "the mathematical value of the expression in script.js for 72 kg and 0 m separately";
        String request = ORIGINAL_REQUEST.replace("1.8 m", "0 m") + " Also report " + implementationQuote + ".";
        Map<String, Object> proposal = bmiProposal(request);
        entry(proposal, "CALCULATION").put("requestQuote", "the BMI for 72 kg and 0 m");
        object(list(calculation(proposal).get("operands")).get(1)).put("requestQuote", "0 m");
        list(proposal.get("results")).add(calculationEntry(implementationQuote,
                calculation("IMPLEMENTATION", "s2", null,
                        List.of(operand("weight", "weight", "72 kg"), operand("height", "height", "0 m")), null)));
        List<Source> sources = replace(original(), "script.js", ORIGINAL_SCRIPT.replace("weight / (height * height)", "weight + height"));
        ReadOnlyWebDiagnosticResult result = assess(request, proposal, sources);

        limited(result);
        assertFalse(calculationComponent(result, 0).met());
        assertTrue(calculationComponent(result, 1).met(), result.answer());
        number(calculationComponent(result, 1).text(), "72.0");
        assertTrue(calculationComponent(result, 0).text().contains("Division by zero"));
        assertFalse(result.answer().contains("Infinity"));
    }

    @Test
    void twoDefinitionsUseTheirOwnOperandsAndPrecision() {
        String areaQuote = "Give the panel area for span 12 m and rise 2.5 m to 1 decimal place";
        String perimeterQuote = "Give the perimeter for span 8 m and rise 3 m to 2 decimal places";
        String request = calculationRequest(areaQuote, perimeterQuote);
        Map<String, Object> proposal = twoDefinitionProposal(request, areaQuote, perimeterQuote);
        ReadOnlyWebDiagnosticResult result = assess(request, proposal, twoDefinitions());

        complete(result);
        assertEquals(2, calculationComponents(result).size());
        number(calculationComponent(result, 0).text(), "30.0");
        number(calculationComponent(result, 1).text(), "22.00");
        assertFalse(calculationComponent(result, 0).text().contains("22.00"));
        assertFalse(calculationComponent(result, 1).text().contains("30.0"));
        assertTrue(result.components().components().stream().noneMatch(value -> value.kind().equals("COMPARISON")));
    }

    @Test
    void missingSecondResultCannotBeCoveredByBroadQuotesOnOtherEntries() {
        String areaQuote = "Give the panel area for span 12 m and rise 2.5 m to 1 decimal place";
        String perimeterQuote = "Give the perimeter for span 8 m and rise 3 m to 2 decimal places";
        String request = calculationRequest(areaQuote, perimeterQuote);
        Map<String, Object> proposal = twoDefinitionProposal(request, areaQuote, perimeterQuote);
        list(proposal.get("results")).removeLast();
        ReadOnlyWebDiagnosticResult result = assess(request, proposal, twoDefinitions());

        limited(result);
        assertTrue(calculationComponent(result, 0).met());
        number(calculationComponent(result, 0).text(), "30.0");
        assertFalse(result.components().requestCovered());
        assertFalse(result.answer().contains("22.00"));
    }

    @Test
    void invalidFirstFormulaReferencePreservesAnIndependentSecondDefinition() {
        String areaQuote = "Give the panel area for span 12 m and rise 2.5 m to 1 decimal place";
        String perimeterQuote = "Give the perimeter for span 8 m and rise 3 m to 2 decimal places";
        String request = calculationRequest(areaQuote, perimeterQuote);
        Map<String, Object> proposal = twoDefinitionProposal(request, areaQuote, perimeterQuote);
        object(calculation(proposal).get("formulaReference")).put("uniqueQuote", "span divided by zero");
        ReadOnlyWebDiagnosticResult result = assess(request, proposal, twoDefinitions());

        limited(result);
        assertFalse(calculationComponent(result, 0).met());
        assertTrue(calculationComponent(result, 1).met());
        number(calculationComponent(result, 1).text(), "22.00");
        assertTrue(component(result, "DIAGNOSIS").met());
        assertTrue(component(result, "SELECTOR").met());
        assertTrue(component(result, "SCRIPT_DISTINCTION").met());
    }

    @Test
    void repeatedOperandQuotesAreResolvedWithinSeparateRequestExpressionScopes() {
        String divisionQuote = "Evaluate gross / elapsed for gross = 41 and elapsed = 8 to 2 decimal places";
        String differenceQuote = "Evaluate gross - elapsed for gross = 41 and elapsed = 8 to 3 decimal places";
        String request = calculationRequest(divisionQuote, differenceQuote);
        Map<String, Object> proposal = base(request);
        entry(proposal, "CALCULATION").put("requestQuote", divisionQuote);
        setCalculation(proposal, calculation("REQUEST", "request", "gross / elapsed",
                List.of(operand("gross", "", "gross = 41"), operand("elapsed", "", "elapsed = 8")), "2 decimal places"));
        list(proposal.get("results")).add(calculationEntry(differenceQuote,
                calculation("REQUEST", "request", "gross - elapsed",
                        List.of(operand("gross", "", "gross = 41"), operand("elapsed", "", "elapsed = 8")), "3 decimal places")));
        ReadOnlyWebDiagnosticResult result = assess(request, proposal, original());

        complete(result);
        assertEquals(2, calculationComponents(result).size());
        number(calculationComponent(result, 0).text(), "5.13");
        number(calculationComponent(result, 1).text(), "33.000");
    }

    @Test
    void operandsCannotBeBorrowedFromAnIndependentSiblingCalculationScope() {
        String areaQuote = "Give the panel area for span 12 m and rise 2.5 m to 1 decimal place";
        String perimeterQuote = "Give the perimeter for span 8 m and rise 3 m to 2 decimal places";
        String request = calculationRequest(areaQuote, perimeterQuote);
        Map<String, Object> proposal = twoDefinitionProposal(request, areaQuote, perimeterQuote);
        object(list(calculation(proposal).get("operands")).getFirst()).put("requestQuote", "span 8 m");
        ReadOnlyWebDiagnosticResult result = assess(request, proposal, twoDefinitions());

        limited(result);
        assertFalse(calculationComponent(result, 0).met());
        assertTrue(calculationComponent(result, 1).met());
        number(calculationComponent(result, 1).text(), "22.00");
        assertFalse(calculationComponent(result, 0).text().contains("20.0"));
    }

    @Test
    void implementationAliasesAreBoundToTheirObservedHtmlInputIds() {
        String implementationQuote = "the mathematical value of the expression in script.js for 72 kg and 1.8 m separately";
        String request = ORIGINAL_REQUEST + " Also report " + implementationQuote + ".";
        Map<String, Object> proposal = bmiProposal(request);
        entry(proposal, "CALCULATION").put("requestQuote", "the BMI for 72 kg and 1.8 m");
        list(proposal.get("results")).add(calculationEntry(implementationQuote,
                calculation("IMPLEMENTATION", "s2", null,
                        List.of(operand("mass", "weight", "72 kg"), operand("stature", "height", "1.8 m")), null)));
        String script = ORIGINAL_SCRIPT.replace("const weight =", "const mass =")
                .replace("const height =", "const stature =")
                .replace("weight / (height * height)", "mass / (stature * stature)");
        ReadOnlyWebDiagnosticResult result = assess(request, proposal, replace(original(), "script.js", script));

        complete(result);
        assertEquals(2, calculationComponents(result).size());
        number(calculationComponent(result, 0).text(), "22.2");
        number(calculationComponent(result, 1).text(), "22.2");
        assertTrue(calculationComponent(result, 1).text().contains("script.js"));
        assertTrue(comparisonComponent(result).met());
    }

    @Test
    void aNonconcreteResultCannotBorrowOperandsOnlySuppliedForAnotherCalculation() {
        String areaQuote = "Give the panel area for span 12 m and rise 2.5 m";
        String implementationQuote = "Report the mathematical value of the expression in script.js separately";
        String request = calculationRequest(areaQuote, implementationQuote);
        Map<String, Object> proposal = base(request);
        entry(proposal, "CALCULATION").put("requestQuote", areaQuote);
        setCalculation(proposal, calculation("SOURCE", "s0", "span multiplied by rise",
                List.of(operand("span", "span", "span 12 m"), operand("rise", "rise", "rise 2.5 m")), null));
        list(proposal.get("results")).add(calculationEntry(implementationQuote,
                calculation("IMPLEMENTATION", "s2", null,
                        List.of(operand("span", "span", "span 12 m"), operand("rise", "rise", "rise 2.5 m")), null)));
        ReadOnlyWebDiagnosticResult result = assess(request, proposal, panel("span + rise"));

        limited(result);
        assertTrue(calculationComponent(result, 0).met());
        assertFalse(calculationComponent(result, 1).met());
        number(calculationComponent(result, 0).text(), "30.0");
        assertFalse(result.answer().contains("14.5"));
    }

    @Test
    void definitionAndImplementationWithDifferentInputsDoNotProduceAComparison() {
        String areaQuote = "Give the panel area for span 12 m and rise 2.5 m";
        String implementationQuote = "Report the mathematical value of the expression in script.js for span 3 m and rise 4 m";
        String request = calculationRequest(areaQuote, implementationQuote);
        Map<String, Object> proposal = panelProposal(request, "span 12 m", "rise 2.5 m");
        entry(proposal, "CALCULATION").put("requestQuote", areaQuote);
        list(proposal.get("results")).add(calculationEntry(implementationQuote,
                calculation("IMPLEMENTATION", "s2", null,
                        List.of(operand("span", "span", "span 3 m"), operand("rise", "rise", "rise 4 m")), null)));
        ReadOnlyWebDiagnosticResult result = assess(request, proposal, panel("span + rise"));

        complete(result);
        number(calculationComponent(result, 0).text(), "30.0");
        number(calculationComponent(result, 1).text(), "7.0");
        assertTrue(result.components().components().stream().noneMatch(value -> value.kind().equals("COMPARISON")));
    }

    @Test
    void differentDisplayPrecisionsDoNotTurnEqualExactValuesIntoADisagreement() {
        String bmiQuote = "Give the BMI for 72 kg and 1.8 m to 1 decimal place";
        String implementationQuote = "Report the mathematical value of the expression in script.js for 72 kg and 1.8 m to 3 decimal places";
        String request = calculationRequest(bmiQuote, implementationQuote);
        Map<String, Object> proposal = bmiProposal(request);
        entry(proposal, "CALCULATION").put("requestQuote", bmiQuote);
        calculation(proposal).put("precisionRequestQuote", "1 decimal place");
        list(proposal.get("results")).add(calculationEntry(implementationQuote,
                calculation("IMPLEMENTATION", "s2", null,
                        List.of(operand("weight", "weight", "72 kg"), operand("height", "height", "1.8 m")), "3 decimal places")));
        ReadOnlyWebDiagnosticResult result = assess(request, proposal, original());

        complete(result);
        number(calculationComponent(result, 0).text(), "22.2");
        number(calculationComponent(result, 1).text(), "22.222");
        assertTrue(comparisonComponent(result).text().contains("agree"));
        assertFalse(comparisonComponent(result).text().contains("disagree"));
    }

    @Test
    void aConstantImplementationNeedsNoOperandBindingsAndSurvivesAnInvalidDefinition() {
        List<Source> sources = panel("42");
        Map<String, Object> proposal = panelProposal(PANEL_REQUEST, "span 12 m", "rise 2.5 m");
        Map<String, Object> implementation = object(object(list(proposal.get("results")).getLast()).get("calculation"));
        implementation.put("operands", List.of());
        assertTrue(ReadOnlyAnswerProposal.eligible(sources));

        ReadOnlyWebDiagnosticResult result = assess(PANEL_REQUEST, proposal, sources);

        complete(result);
        assertEquals(2, calculationComponents(result).size());
        number(calculationComponent(result, 0).text(), "30.0");
        number(calculationComponent(result, 1).text(), "42.0");
        assertTrue(calculationComponent(result, 1).text().contains("`42/1`"));
        assertTrue(calculationComponent(result, 1).text().contains("script.js"));
        assertTrue(comparisonComponent(result).met());
        assertTrue(comparisonComponent(result).text().contains("disagree"));
        assertTrue(component(result, "DIAGNOSIS").met());
        assertTrue(component(result, "SELECTOR").met());
        assertTrue(component(result, "SCRIPT_DISTINCTION").met());

        object(calculation(proposal).get("formulaReference")).put("uniqueQuote", "an absent definition");
        ReadOnlyWebDiagnosticResult partial = assess(PANEL_REQUEST, proposal, sources);

        limited(partial);
        assertFalse(calculationComponent(partial, 0).met());
        assertTrue(calculationComponent(partial, 1).met());
        number(calculationComponent(partial, 1).text(), "42.0");
        assertTrue(partial.components().components().stream().noneMatch(value -> value.kind().equals("COMPARISON")));
    }

    @Test
    void anImplementationUsingASubsetOfCompatibleInputsIsComparedInEitherResultOrder() {
        for (boolean reversed : List.of(false, true)) {
            Map<String, Object> proposal = panelProposal(PANEL_REQUEST, "span 12 m", "rise 2.5 m");
            List<Object> results = list(proposal.get("results"));
            Map<String, Object> implementation = object(object(results.getLast()).get("calculation"));
            implementation.put("operands", List.of(operand("span", "span", "span 12 m")));
            if (reversed) java.util.Collections.swap(results, 2, results.size() - 1);

            ReadOnlyWebDiagnosticResult result = assess(PANEL_REQUEST, proposal, panel("span"));

            complete(result);
            assertEquals(2, calculationComponents(result).size());
            number(calculationComponent(result, reversed ? 1 : 0).text(), "30.0");
            number(calculationComponent(result, reversed ? 0 : 1).text(), "12.0");
            assertEquals(1, result.components().components().stream().filter(value -> value.kind().equals("COMPARISON")).count());
            assertTrue(comparisonComponent(result).met());
            assertTrue(comparisonComponent(result).text().contains("disagree"));
        }
    }

    @Test
    void anImplementationUsingASubsetWithAConflictingSharedInputIsNotCompared() {
        String areaQuote = "Give the panel area for span 12 m and rise 2.5 m";
        String implementationQuote = "Report the mathematical value of the expression in script.js for span 3 m";
        String request = calculationRequest(areaQuote, implementationQuote);
        Map<String, Object> proposal = panelProposal(request, "span 12 m", "rise 2.5 m");
        entry(proposal, "CALCULATION").put("requestQuote", areaQuote);
        list(proposal.get("results")).add(calculationEntry(implementationQuote,
                calculation("IMPLEMENTATION", "s2", null, List.of(operand("span", "span", "span 3 m")), null)));

        ReadOnlyWebDiagnosticResult result = assess(request, proposal, panel("span"));

        complete(result);
        number(calculationComponent(result, 0).text(), "30.0");
        number(calculationComponent(result, 1).text(), "3.0");
        assertTrue(result.components().components().stream().noneMatch(value -> value.kind().equals("COMPARISON")));
    }

    @Test
    void sourceCalculationReferencesTheLinkedScriptWhenItSuppliesDisplayPrecision() {
        List<Source> sources = original();
        ReadOnlyWebDiagnosticResult result = assess(ORIGINAL_REQUEST, bmiProposal(ORIGINAL_REQUEST), sources);

        complete(result);
        ReadOnlyAnswerComponents.Component calculation = calculationComponent(result, 0);
        number(calculation.text(), "22.2");
        assertTrue(calculation.text().contains("the linked script's display precision"));
        assertFullReference(calculation, sources.get(2));
    }

    @Test
    void requestCalculationReferencesTheLinkedScriptWhenItSuppliesDisplayPrecision() {
        String clause = "Evaluate gross / elapsed for gross = 41 and elapsed = 8";
        String request = calculationRequest(clause);
        Map<String, Object> proposal = base(request);
        entry(proposal, "CALCULATION").put("requestQuote", clause);
        setCalculation(proposal, calculation("REQUEST", "request", "gross / elapsed",
                List.of(operand("gross", "", "gross = 41"), operand("elapsed", "", "elapsed = 8")), null));
        List<Source> sources = original();
        ReadOnlyWebDiagnosticResult result = assess(request, proposal, sources);

        complete(result);
        ReadOnlyAnswerComponents.Component calculation = calculationComponent(result, 0);
        number(calculation.text(), "5.1");
        assertFalse(calculation.text().contains("5.13"));
        assertTrue(calculation.text().contains("the linked script's display precision"));
        assertFullReference(calculation, sources.get(2));
    }

    private static void assertFullReference(ReadOnlyAnswerComponents.Component component, Source source) {
        assertEquals(1, component.references().stream().filter(reference ->
                reference.observationId().equals(source.observationId())
                        && reference.textHash().equals(ReadOnlyWebDiagnosticResult.textHash(source.text()))
                        && reference.start() == 0 && reference.end() == source.text().length()).count(), component.text());
    }

    private static String calculationRequest(String... clauses) {
        return "Read README.md, index.html, script.js and scripts.js. "
                + "Explain why the Calculate button fails. Give the correct selector. "
                + String.join(". ", clauses) + ". Distinguish the two JavaScript files. Do not edit files or claim tests were run.";
    }

    private static Map<String, Object> twoDefinitionProposal(String request, String areaQuote, String perimeterQuote) {
        Map<String, Object> proposal = panelProposal(request, "span 12 m", "rise 2.5 m");
        entry(proposal, "CALCULATION").put("requestQuote", areaQuote);
        calculation(proposal).put("precisionRequestQuote", "1 decimal place");
        list(proposal.get("results")).add(calculationEntry(perimeterQuote,
                calculation("SOURCE", "s0", "2 * (span + rise)",
                        List.of(operand("span", "span", "span 8 m"), operand("rise", "rise", "rise 3 m")), "2 decimal places")));
        return proposal;
    }

    private static List<Source> twoDefinitions() {
        List<Source> sources = panel("span * rise");
        return replace(sources, "README.md", sources.getFirst().text() + "\nThe perimeter is 2 * (span + rise).\n");
    }

    private static String unparsedNumbersRequest(String weight, String height) {
        return "Read README.md, index.html, script.js and scripts.js. Explain exactly why the Calculate button is broken. "
                + "The requested BMI uses " + weight + " and " + height + ". State the replacement selector "
                + "and distinguish the two JavaScript files. Do not edit files or claim tests were run.";
    }

    private static ReadOnlyWebDiagnosticResult assess(String request, Map<String, Object> proposal, List<Source> sources) {
        return ReadOnlyAnswerProposal.assess(request, json(proposal), sources);
    }

    private static void complete(ReadOnlyWebDiagnosticResult result) {
        assertNotNull(result);
        assertFalse(result.assessment().preserved());
        assertFalse(result.assessment().limited(), result.answer());
        assertTrue(result.components().complete(), result.answer());
        assertTrue(result.components().deliveredIntact(result.answer()));
        assertEquals("INTERPRETED", result.components().coverageOrigin().toString());
    }

    private static void limited(ReadOnlyWebDiagnosticResult result) {
        assertNotNull(result);
        assertTrue(result.assessment().limited(), result.answer());
        assertFalse(result.assessment().preserved());
        assertFalse(result.components().complete());
        assertFalse(result.components().deliveredIntact(result.answer()));
    }

    private static void number(String text, String expected) {
        assertTrue(Pattern.compile("(?<![0-9.])" + Pattern.quote(expected) + "(?![0-9.])").matcher(text).find(), text);
    }

    private static ReadOnlyAnswerComponents.Component component(ReadOnlyWebDiagnosticResult result, String kind) {
        return result.components().components().stream().filter(value -> value.kind().equals(kind)).findFirst().orElseThrow();
    }

    private static List<ReadOnlyAnswerComponents.Component> calculationComponents(ReadOnlyWebDiagnosticResult result) {
        return result.components().components().stream().filter(value -> value.kind().equals("CALCULATION")).toList();
    }

    private static ReadOnlyAnswerComponents.Component calculationComponent(ReadOnlyWebDiagnosticResult result, int index) {
        return calculationComponents(result).get(index);
    }

    private static ReadOnlyAnswerComponents.Component comparisonComponent(ReadOnlyWebDiagnosticResult result) {
        return component(result, "COMPARISON");
    }

    private static Map<String, Object> bmiProposal(String request) {
        Map<String, Object> proposal = base(request);
        setCalculation(proposal, calculation("SOURCE", "s0", BMI_EXPRESSION,
                List.of(operand("weight", "weight", "72 kg"), operand("height", "height", "1.8 m")), null));
        return proposal;
    }

    private static Map<String, Object> panelProposal(String request, String spanQuote, String riseQuote) {
        Map<String, Object> proposal = base(request);
        setCalculation(proposal, calculation("SOURCE", "s0", "span multiplied by rise",
                List.of(operand("span", "span", spanQuote), operand("rise", "rise", riseQuote)), null));
        if (request.contains("the mathematical value of the expression in script.js separately")) {
            entry(proposal, "CALCULATION").put("requestQuote", "give the panel area according to README.md");
            list(proposal.get("results")).add(calculationEntry(
                    "the mathematical value of the expression in script.js separately",
                    calculation("IMPLEMENTATION", "s2", null,
                            List.of(operand("span", "span", spanQuote), operand("rise", "rise", riseQuote)), null)));
        }
        return proposal;
    }

    private static Map<String, Object> base(String request) {
        List<Object> results = new ArrayList<>();
        for (String kind : List.of("DIAGNOSIS", "SELECTOR", "CALCULATION", "SCRIPT_DISTINCTION"))
            results.add(fields("kind", kind, "requestQuote", request));
        Map<String, Object> proposal = fields("version", 2,
                "target", fields("buttonId", "calculate", "requestQuote", "Calculate"), "results", results);
        entry(proposal, "CALCULATION").put("calculation", null);
        entry(proposal, "SCRIPT_DISTINCTION").put("sourceIds", List.of("s2", "s3"));
        return proposal;
    }

    private static Map<String, Object> entry(Map<String, Object> proposal, String kind) {
        return list(proposal.get("results")).stream().map(ReadOnlyAnswerProposalTest::object)
                .filter(value -> value.get("kind").equals(kind)).findFirst().orElseThrow();
    }

    private static Map<String, Object> calculation(Map<String, Object> proposal) {
        return object(entry(proposal, "CALCULATION").get("calculation"));
    }

    private static void setCalculation(Map<String, Object> proposal, Map<String, Object> calculation) {
        entry(proposal, "CALCULATION").put("calculation", calculation);
    }

    private static Map<String, Object> calculationEntry(String requestQuote, Map<String, Object> calculation) {
        return fields("kind", "CALCULATION", "requestQuote", requestQuote, "calculation", calculation);
    }

    private static Map<String, Object> calculation(String origin, String sourceId, String expression, List<Map<String, Object>> operands, String precision) {
        return fields("formulaOrigin", origin, "formulaReference", fields("sourceId", sourceId, "uniqueQuote", expression),
                "operands", operands, "precisionRequestQuote", precision);
    }

    private static Map<String, Object> operand(String variable, String inputId, String requestQuote) {
        return fields("variable", variable, "inputId", inputId, "requestQuote", requestQuote);
    }

    private static Map<String, Object> fields(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) result.put((String) entries[index], entries[index + 1]);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) { return (List<Object>) value; }

    private static String json(Map<String, Object> value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new AssertionError(failure); }
    }

    private static List<Source> original() {
        return List.of(source("README.md", ORIGINAL_README), source("index.html", ORIGINAL_HTML),
                source("script.js", ORIGINAL_SCRIPT), source("scripts.js", ORIGINAL_SENTINEL));
    }

    private static List<Source> panel(String expression) {
        String readme = ORIGINAL_README.replace("Calculator review fixture", "Panel calculator review fixture")
                .replace("BMI as weight divided by height squared", "panel area as span multiplied by rise");
        String html = ORIGINAL_HTML.replace("Weight (kg)", "Span (m)").replace("Height (m)", "Rise (m)")
                .replace("id=\"weight\" value=\"72\"", "id=\"span\" value=\"12\"")
                .replace("id=\"height\" value=\"1.8\"", "id=\"rise\" value=\"2.5\"");
        String script = ORIGINAL_SCRIPT.replace("weight", "span").replace("height", "rise")
                .replace("span / (rise * rise)", expression);
        return List.of(source("README.md", readme), source("index.html", html), source("script.js", script), source("scripts.js", ORIGINAL_SENTINEL));
    }

    private static List<Source> replace(List<Source> sources, String path, String text) {
        return sources.stream().map(value -> value.path().equals(path) ? source(path, text) : value).toList();
    }

    private static Source source(String path, String text) {
        return new Source(path, text, UUID.nameUUIDFromBytes((path + "\n" + text).getBytes(StandardCharsets.UTF_8)).toString(),
                1, ReadOnlyWebDiagnosticResult.textHash(text), SOURCE_PROJECTION_VERSION);
    }
}
