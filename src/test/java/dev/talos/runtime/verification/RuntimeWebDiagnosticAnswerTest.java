package dev.talos.runtime.verification;

import dev.talos.runtime.task.AnswerRequirements;
import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.Source;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.SOURCE_PROJECTION_VERSION;
import static dev.talos.runtime.verification.ReadOnlyAnswerComponents.Support.CONTRADICTED;
import static dev.talos.runtime.verification.ReadOnlyAnswerComponents.Support.ESTABLISHED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeWebDiagnosticAnswerTest {
    private static final String HISTORICAL_REQUEST = "Read README.md, index.html, script.js and scripts.js. "
            + "Explain why the Calculate button fails, give the correct selector and the BMI for 72 kg and 1.8 m, "
            + "and distinguish the two JavaScript files. Do not edit files or claim tests were run.";
    private static final String AREA_REQUEST = "Read notes.md, page.html, calculator.js and archived.js. "
            + "Explain why the Measure button fails, give the correct selector and the area for width 12 m and height 2.5 m, "
            + "and distinguish the two JavaScript files. Do not edit files or claim tests were run.";
    private static final String HISTORICAL_HTML = """
            <!doctype html>
            <html><body>
            <label>Weight (kg)<input id="weight" value="99"></label>
            <label>Height (m)<input id="height" value="99"></label>
            <button id="calculate">Calculate</button>
            <p id="result"></p>
            <script src="script.js"></script>
            </body></html>
            """;
    private static final String AREA_HTML = """
            <!doctype html>
            <html><body>
            <label for="wide">Width (m)</label><input id="wide" value="99">
            <label for="high">Height (m)</label><input id="high" value="99">
            <button id="measure">Measure</button>
            <p id="result"></p>
            <script src="calculator.js"></script>
            </body></html>
            """;
    private static final String HISTORICAL_SCRIPT = script("#calc", "weight", "weight", "height", "height", "weight / (height * height)", 1);
    private static final String AREA_SCRIPT = script("#meas", "span", "wide", "rise", "high", "span * rise", 1);
    private static final String HISTORICAL_PROSE_README = "# Calculator review fixture\n\n"
            + "The calculator in index.html loads script.js. scripts.js is an unrelated sentinel and must stay unchanged. "
            + "The Calculate button should compute BMI as weight divided by height squared. This fixture is local test data.\n";

    @Test
    void actualHistoricalRequestProducesAllFourCheckedComponents() {
        List<Source> sources = historical();
        ReadOnlyWebDiagnosticResult result = assess(HISTORICAL_REQUEST, sources);

        assertComplete(result);
        assertEquals(4, result.components().components().size());
        assertTrue(component(result, "DIAGNOSIS").text().contains("lookup would be null"));
        assertTrue(component(result, "DIAGNOSIS").text().contains("not an observed browser result"));
        assertTrue(component(result, "SELECTOR").text().contains("`#calc` should be replaced by `#calculate`"));
        assertTrue(component(result, "CALCULATION").text().contains("`200/9`"));
        assertTrue(component(result, "CALCULATION").text().contains("`22.2` to 1 decimal places"));
        assertFalse(component(result, "CALCULATION").derivationHash().isEmpty());
        assertTrue(component(result, "SCRIPT_DISTINCTION").text().contains("`index.html` links `script.js`"));
        assertTrue(component(result, "SCRIPT_DISTINCTION").text().contains("`scripts.js` was also read"));
        assertTrue(result.answer().endsWith(ReadOnlyAnswerComponents.FOOTER));
        for (var item : result.components().components()) {
            assertFalse(item.references().isEmpty(), item.kind());
            for (var reference : item.references()) {
                Source original = sources.stream().filter(source -> source.observationId().equals(reference.observationId())).findFirst().orElseThrow();
                assertEquals(ReadOnlyWebDiagnosticResult.textHash(original.text()), reference.textHash());
                assertTrue(reference.start() >= 0);
                assertTrue(reference.end() <= original.text().length());
            }
        }
    }

    @Test
    void changedNamesOperationAndRepeatedUnitsProduceIndependentArea() {
        ReadOnlyWebDiagnosticResult result = assess(AREA_REQUEST, area());
        assertComplete(result);
        assertTrue(component(result, "SELECTOR").text().contains("`#meas` should be replaced by `#measure`"));
        assertTrue(component(result, "CALCULATION").text().contains("`30/1`"));
        assertTrue(component(result, "CALCULATION").text().contains("`30.0` to 1 decimal places"));
        assertFalse(result.answer().contains("99"));
    }

    @Test
    void conflictingDefinitionsKeepDiagnosisAndDistinctionWhileCalculationIsLimited() {
        List<Source> sources = replace(area(), "notes.md", "The Measure button should compute area as width multiplied by height.\n"
                + "area = width plus height.\n");
        ReadOnlyWebDiagnosticResult result = assess(AREA_REQUEST, sources);

        assertLimited(result);
        assertEquals(ESTABLISHED, component(result, "DIAGNOSIS").support());
        assertEquals(ESTABLISHED, component(result, "SELECTOR").support());
        assertEquals(ESTABLISHED, component(result, "SCRIPT_DISTINCTION").support());
        assertFalse(component(result, "CALCULATION").met());
        assertEquals("FORMULA_DEFINITION_UNAVAILABLE", component(result, "CALCULATION").reason());
        assertTrue(result.answer().contains(component(result, "DIAGNOSIS").text()));
        assertTrue(result.answer().contains(component(result, "SCRIPT_DISTINCTION").text()));
    }

    @Test
    void sourceCodeDisagreementCannotBecomeAnAgreedApplicationCalculation() {
        List<Source> sources = replace(area(), "calculator.js", AREA_SCRIPT.replace("span * rise", "span + rise"));
        ReadOnlyWebDiagnosticResult result = assess(AREA_REQUEST, sources);

        assertLimited(result);
        assertEquals(CONTRADICTED, component(result, "CALCULATION").support());
        assertEquals("FORMULA_SOURCE_DISAGREEMENT", component(result, "CALCULATION").reason());
        assertEquals(ESTABLISHED, component(result, "DIAGNOSIS").support());
        assertFalse(result.answer().contains("`30.0`"));
    }

    @Test
    void aWrongExistingButtonCannotBeCertifiedAsTheMissingRequestedLookup() {
        String html = AREA_HTML.replace("<p id=\"result\">", "<button id=\"cancel\">Cancel</button><p id=\"result\">");
        List<Source> sources = replace(replace(area(), "page.html", html), "calculator.js", AREA_SCRIPT.replace("#meas'", "#cancel'"));
        ReadOnlyWebDiagnosticResult result = assess(AREA_REQUEST, sources);

        assertLimited(result);
        assertFalse(component(result, "DIAGNOSIS").met());
        assertFalse(component(result, "SELECTOR").met());
        assertEquals(ESTABLISHED, component(result, "CALCULATION").support());
        assertFalse(result.answer().contains("lookup would be null"));
    }

    @Test
    void requestUnitMismatchesCannotBeConvertedOrFilledFromDefaults() {
        ReadOnlyWebDiagnosticResult result = assess(HISTORICAL_REQUEST.replace("1.8 m", "180 cm"), historical());
        assertLimited(result);
        assertFalse(component(result, "CALCULATION").met());
        assertEquals("REQUEST_OPERAND_AMBIGUOUS", component(result, "CALCULATION").reason());
        assertEquals(ESTABLISHED, component(result, "DIAGNOSIS").support());
        assertFalse(result.answer().contains("`22.2`"));
    }

    @Test
    void repeatedUnitsNeedExplicitOperandNames() {
        ReadOnlyWebDiagnosticResult result = assess(AREA_REQUEST.replace("width 12 m and height 2.5 m", "12 m and 2.5 m"), area());
        assertLimited(result);
        assertEquals("REQUEST_OPERAND_AMBIGUOUS", component(result, "CALCULATION").reason());
        assertFalse(component(result, "CALCULATION").met());
    }

    @Test
    void duplicateInputLabelsCannotBeChosenByPosition() {
        List<Source> sources = replace(area(), "page.html", AREA_HTML.replace("Height (m)", "Width (m)"));
        ReadOnlyWebDiagnosticResult result = assess(AREA_REQUEST, sources);
        assertLimited(result);
        assertEquals("FORMULA_OPERAND_AMBIGUOUS", component(result, "CALCULATION").reason());
        assertEquals(ESTABLISHED, component(result, "DIAGNOSIS").support());
    }

    @Test
    void explicitRequestExpressionDoesNotRequireSourceInputBinding() {
        String request = "Read notes.md, page.html, calculator.js and archived.js. Explain why the Measure button fails. "
                + "Give the correct selector. Evaluate (gross - tare) / elapsed for gross = 41, tare = 5 and elapsed = 8 to 3 decimal places. "
                + "Distinguish the two JavaScript files. Do not edit files or claim tests were run.";
        List<Source> sources = replace(area(), "notes.md", "The source does not define this standalone request expression.\n");
        sources = replace(sources, "page.html", AREA_HTML.replace("<label for=\"wide\">Width (m)</label>", "")
                .replace("<label for=\"high\">Height (m)</label>", ""));
        ReadOnlyWebDiagnosticResult result = assess(request, sources);

        assertComplete(result);
        assertTrue(component(result, "CALCULATION").text().contains("expression supplied in the request"));
        assertTrue(component(result, "CALCULATION").text().contains("`9/2`"));
        assertTrue(component(result, "CALCULATION").text().contains("`4.500` to 3 decimal places"));
        assertTrue(component(result, "CALCULATION").references().isEmpty());
    }

    @Test
    void explicitRequestPrecisionOverridesTheSourceDisplayPrecision() {
        ReadOnlyWebDiagnosticResult result = assess(HISTORICAL_REQUEST.replace("1.8 m,", "1.8 m to 2 decimal places,"), historical());
        assertComplete(result);
        assertTrue(component(result, "CALCULATION").text().contains("`22.22` to 2 decimal places"));
    }

    @Test
    void unknownRequestClauseIsDeliveredAsAnUnmetComponent() {
        ReadOnlyWebDiagnosticResult result = assess(AREA_REQUEST + " Also explain the accessibility issues.", area());
        assertLimited(result);
        assertFalse(result.components().requestCovered());
        assertEquals(ESTABLISHED, component(result, "CALCULATION").support());
        assertFalse(component(result, "UNKNOWN").met());
        assertTrue(result.answer().contains("additional requested component"));
    }

    @Test
    void unknownRequestWithoutCalculationCannotFallBackToLegacyCompletion() {
        String request = "Read notes.md, page.html, calculator.js and archived.js. "
                + "Explain why the Measure button fails. Give the correct selector. Also explain accessibility issues.";
        ReadOnlyWebDiagnosticResult result = assess(request, area());
        assertLimited(result);
        assertFalse(result.components().requestCovered());
        assertEquals(ESTABLISHED, component(result, "DIAGNOSIS").support());
        assertEquals(ESTABLISHED, component(result, "SELECTOR").support());
        assertFalse(component(result, "UNKNOWN").met());
        assertTrue(result.components().components().stream().noneMatch(item -> item.kind().equals("CALCULATION")));
    }

    @Test
    void questionsAreNotAffirmativeDefinitionDocuments() {
        for (String definition : List.of("The Measure button should compute area as width multiplied by height?",
                "area = width multiplied by height?")) {
            ReadOnlyWebDiagnosticResult result = assess(AREA_REQUEST, replace(area(), "notes.md", definition));
            assertLimited(result);
            assertFalse(component(result, "CALCULATION").met(), definition);
            assertEquals(ESTABLISHED, component(result, "DIAGNOSIS").support());
        }
    }

    @Test
    void negationAndMisleadingHeadingsCannotBeDiscardedFromTheDefinitionDocument() {
        for (String context : List.of("The following statement is false.",
                "Do not use the following definition.", "# Rejected calculation", "# Incorrect historical formula")) {
            ReadOnlyWebDiagnosticResult result = assess(AREA_REQUEST,
                    replace(area(), "notes.md", context + "\narea = width multiplied by height.\n"));
            assertLimited(result);
            assertFalse(component(result, "CALCULATION").met(), context);
            assertEquals(ESTABLISHED, component(result, "DIAGNOSIS").support());
        }
    }

    @Test
    void originalFourSentenceReadmeRemainsOutsideTheClosedDefinitionDocumentGrammar() {
        for (String readme : List.of(HISTORICAL_PROSE_README,
                HISTORICAL_PROSE_README.substring(HISTORICAL_PROSE_README.indexOf("\n\n") + 2))) {
            ReadOnlyWebDiagnosticResult result = assess(HISTORICAL_REQUEST,
                    replace(historical(), "README.md", readme));
            assertLimited(result);
            assertEquals("FORMULA_DEFINITION_UNAVAILABLE", component(result, "CALCULATION").reason());
            assertFalse(component(result, "CALCULATION").met());
            assertEquals(ESTABLISHED, component(result, "DIAGNOSIS").support());
            assertEquals(ESTABLISHED, component(result, "SELECTOR").support());
            assertEquals(ESTABLISHED, component(result, "SCRIPT_DISTINCTION").support());
            assertFalse(result.answer().contains("`22.2`"));
        }
    }

    @Test
    void explicitRequestExpressionStillCompletesWithTheOriginalProseReadme() {
        String request = "Read README.md, index.html, script.js and scripts.js. Explain why the Calculate button fails. "
                + "Give the correct selector. Evaluate weight divided by height squared for weight = 72 and height = 1.8 "
                + "to 1 decimal place. Distinguish the two JavaScript files. Do not edit files or claim tests were run.";
        ReadOnlyWebDiagnosticResult result = assess(request,
                replace(historical(), "README.md", HISTORICAL_PROSE_README));
        assertComplete(result);
        assertTrue(component(result, "CALCULATION").text().contains("expression supplied in the request"));
        assertTrue(component(result, "CALCULATION").text().contains("`22.2`"));
        assertTrue(component(result, "CALCULATION").references().isEmpty());
    }

    @Test
    void quotedNegatedAndHypotheticalSourceDeclarationsDoNotSupplyAFormula() {
        for (String text : List.of("\"The Measure button should compute area as width multiplied by height.\"",
                "The Measure button should not compute area as width multiplied by height.",
                "If required, the Measure button should compute area as width multiplied by height.",
                "> area = width multiplied by height.", "```\narea = width multiplied by height.\n```")) {
            ReadOnlyWebDiagnosticResult result = assess(AREA_REQUEST, replace(area(), "notes.md", text));
            assertLimited(result);
            assertFalse(component(result, "CALCULATION").met(), text);
            assertEquals(ESTABLISHED, component(result, "DIAGNOSIS").support());
        }
    }

    @Test
    void inertMarkdownStructuresCannotSupplyAnAffirmativeQuantityDefinition() {
        String equation = "area = width multiplied by height.";
        for (String text : List.of("<!--\n" + equation + "\n-->",
                "<blockquote>\n" + equation + "\n</blockquote>",
                "    " + equation,
                "````text\n```\n" + equation + "\n````",
                "~~~~text\n~~~\n" + equation + "\n~~~~",
                "```text\n~~~\n" + equation + "\n```")) {
            ReadOnlyWebDiagnosticResult result = assess(AREA_REQUEST, replace(area(), "notes.md", text));
            assertLimited(result);
            assertFalse(component(result, "CALCULATION").met(), text);
            assertEquals(ESTABLISHED, component(result, "DIAGNOSIS").support());
        }
    }

    @Test
    void sourceNegationOrConditionalContextCannotBeDiscardedAtThePreviousLine() {
        for (String premise : List.of("The following statement is false:",
                "If the old implementation is being described:",
                "This is a quoted example:", "Do not use the following definition:")) {
            String text = premise + "\narea = width multiplied by height.\n";
            ReadOnlyWebDiagnosticResult result = assess(AREA_REQUEST, replace(area(), "notes.md", text));
            assertLimited(result);
            assertFalse(component(result, "CALCULATION").met(), premise);
            assertEquals(ESTABLISHED, component(result, "DIAGNOSIS").support());
        }
    }

    @Test
    void exactButtonIdIsBoundThroughItsObservedLabelToTheProjectDefinition() {
        ReadOnlyWebDiagnosticResult result = assess(AREA_REQUEST.replace("Measure button fails", "#measure button fails"), area());
        assertComplete(result);
        assertTrue(component(result, "CALCULATION").text().contains("`30.0`"));
        assertTrue(component(result, "SELECTOR").text().contains("`#measure`"));
    }

    @Test
    void onlyTheSelectedObservationsCanChangeTheDerivedCalculation() {
        List<Source> retained = historical();
        ReadOnlyWebDiagnosticResult before = assess(HISTORICAL_REQUEST, retained);
        List<Source> changed = replace(retained, "script.js", HISTORICAL_SCRIPT.replace("weight / (height * height)", "weight * height"));
        ReadOnlyWebDiagnosticResult changedResult = assess(HISTORICAL_REQUEST, changed);
        ReadOnlyWebDiagnosticResult after = assess(HISTORICAL_REQUEST, retained);
        assertComplete(before);
        assertLimited(changedResult);
        assertEquals(before, after);
        assertNotEquals(component(before, "CALCULATION"), component(changedResult, "CALCULATION"));
    }

    @Test
    void rawResponseWordingNumbersAndMalformedJsonDoNotAuthorizeDerivedComponents() {
        ReadOnlyWebDiagnosticResult baseline = assess(HISTORICAL_REQUEST, historical());
        for (String raw : List.of(earlierAnswer(), latestAnswer(), "BMI = 900. The tests passed. Use #invented.",
                "{\"answer\":\"22.19\",\"answer\":\"900\",\"trusted\":true", "No calculation supplied.")) {
            ReadOnlyWebDiagnosticResult result = StaticTaskVerifier.assessReadOnlyWebDiagnostics(historical(), HISTORICAL_REQUEST, raw);
            assertComplete(result);
            assertEquals(baseline.answer(), result.answer());
            assertEquals(baseline.components(), result.components());
            assertFalse(result.assessment().preserved());
        }
        assertEquals("ec222de0ce8adc90b574833fcce563b1024c0123770b74748815ea2c69c8e474", ReadOnlyWebDiagnosticResult.textHash(earlierAnswer()));
        assertEquals("40f24fae74a62a48adbc235b59c79f2ada1cbbbc968d321e20405768af518801", ReadOnlyWebDiagnosticResult.textHash(latestAnswer()));
    }

    @Test
    void outputBudgetRemovesWholeRequiredComponentsAndLosesCompleteCoverage() {
        ReadOnlyWebDiagnosticResult result = assess(HISTORICAL_REQUEST, historical());
        ReadOnlyAnswerComponents components = result.components();
        long maximum = result.answer().length() - component(result, "SCRIPT_DISTINCTION").text().length() / 2;
        String bounded = components.present(result.answer(), UnaryOperator.identity(), maximum);

        assertTrue(bounded.length() <= maximum);
        assertTrue(bounded.contains("Required components were omitted by the output limit"));
        assertTrue(bounded.contains(component(result, "DIAGNOSIS").text()));
        assertFalse(bounded.contains(component(result, "SCRIPT_DISTINCTION").text()));
        assertFalse(components.deliveredIntact(bounded));
        assertEquals("", components.present(result.answer(), UnaryOperator.identity(), 0));
        assertEquals("[Answer incomplete]", components.present(result.answer(), UnaryOperator.identity(), 19));
        assertEquals("", components.present(result.answer(), UnaryOperator.identity(), 18));
    }

    @Test
    void privacyRemovalIsNotRestoredByASecondPresentationPass() {
        ReadOnlyWebDiagnosticResult result = assess(HISTORICAL_REQUEST, historical());
        String privateComponent = component(result, "CALCULATION").text();
        String sanitized = result.components().present(result.answer(), text -> text.replace(privateComponent, "[withheld]"), 10_000);
        String presentedAgain = result.components().present(sanitized, UnaryOperator.identity(), 10_000);

        assertTrue(sanitized.contains("[withheld]"));
        assertFalse(sanitized.contains("`22.2`"));
        assertEquals(sanitized, presentedAgain);
        assertFalse(result.components().deliveredIntact(presentedAgain));
    }

    @Test
    void laterReplacementCannotInheritDeliveryOfTheDerivedComponents() {
        ReadOnlyWebDiagnosticResult result = assess(HISTORICAL_REQUEST, historical());
        String replacement = "The answer was withheld after a later privacy decision.";
        assertTrue(result.components().deliveredIntact(result.answer()));
        assertFalse(result.components().deliveredIntact(replacement));
        assertEquals(replacement, result.components().present(replacement, UnaryOperator.identity(), 10_000));
        assertFalse(result.components().deliveredIntact(result.answer().replace("`22.2`", "`22.19`")));
    }

    private static ReadOnlyWebDiagnosticResult assess(String request, List<Source> sources) {
        AnswerRequirements requirements = AnswerRequirements.fromRequest(request);
        assertTrue(requirements.applicable(), request);
        ObservedStaticWebSources.Selection selected = ObservedStaticWebSources.select(sources);
        assertTrue(selected.available(), selected.detail());
        ReadOnlyWebDiagnosticResult result = RuntimeWebDiagnosticAnswer.assess(requirements, sources, selected);
        assertNotNull(result);
        return result;
    }

    private static ReadOnlyAnswerComponents.Component component(ReadOnlyWebDiagnosticResult result, String kind) {
        return result.components().components().stream().filter(item -> item.kind().equals(kind)).findFirst().orElseThrow();
    }

    private static void assertComplete(ReadOnlyWebDiagnosticResult result) {
        assertEquals(ReadOnlyWebAnswerAssessment.Disposition.RUNTIME_DERIVED, result.assessment().disposition(), result.answer());
        assertTrue(result.components().complete(), result.answer());
        assertTrue(result.components().deliveredIntact(result.answer()));
    }

    private static void assertLimited(ReadOnlyWebDiagnosticResult result) {
        assertEquals(ReadOnlyWebAnswerAssessment.Disposition.LIMITED, result.assessment().disposition(), result.answer());
        assertFalse(result.components().complete());
        assertFalse(result.components().deliveredIntact(result.answer()));
    }

    private static List<Source> historical() {
        return List.of(source("README.md", "The Calculate button should compute BMI as weight divided by height squared.\n"),
                source("index.html", HISTORICAL_HTML), source("script.js", HISTORICAL_SCRIPT),
                source("scripts.js", "const unrelatedSentinel = 'unchanged';\n"));
    }

    private static List<Source> area() {
        return List.of(source("notes.md", "The Measure button should compute area as width multiplied by height.\n"),
                source("page.html", AREA_HTML), source("calculator.js", AREA_SCRIPT),
                source("archived.js", "const archivedValue = 'unchanged';\n"));
    }

    private static Source source(String path, String text) {
        return new Source(path, text, UUID.nameUUIDFromBytes((path + "\n" + text).getBytes(StandardCharsets.UTF_8)).toString(),
                1, ReadOnlyWebDiagnosticResult.textHash(text), SOURCE_PROJECTION_VERSION);
    }

    private static List<Source> replace(List<Source> sources, String path, String text) {
        assertTrue(sources.stream().anyMatch(item -> item.path().equals(path)));
        return sources.stream().map(item -> item.path().equals(path) ? source(path, text) : item).toList();
    }

    private static String script(String selector, String left, String leftId, String right, String rightId,
                                 String expression, int precision) {
        return "const trigger = document.querySelector('" + selector + "');\n"
                + "trigger.addEventListener('click', () => {\n"
                + "  const " + left + " = Number(document.querySelector('#" + leftId + "').value);\n"
                + "  const " + right + " = Number(document.querySelector('#" + rightId + "').value);\n"
                + "  document.querySelector('#result').textContent = (" + expression + ").toFixed(" + precision + ");\n"
                + "});\n";
    }

    private static String earlierAnswer() {
        return "The Calculate button fails because the JavaScript code in `script.js` is using the incorrect "
                + "selector `#calc` instead of `#calculate`. The correct selector should be `#calculate` to match the ID "
                + "of the Calculate button in `index.html`.\n\n"
                + "To calculate the BMI for 72 kg and 1.8 m, you would use the formula: BMI = weight (kg) / (height (m) ^ 2). "
                + "Plugging in the given values:\n\n"
                + "BMI = 72 / (1.8 * 1.8) = 72 / 3.24 \u2248 22.2\n\n"
                + "The `script.js` file is the one that is actually being used by `index.html` to handle the BMI calculation. "
                + "The `scripts.js` file is an unrelated sentinel and must stay unchanged.";
    }

    private static String latestAnswer() {
        return "The Calculate button fails because the JavaScript code in `script.js` is using an incorrect selector "
                + "(`#calc`) for the button, which does not match the actual button ID (`#calculate`) in `index.html`.\n\n"
                + "The correct selector for the Calculate button in `script.js` should be `#calculate`.\n\n"
                + "To calculate the BMI for 72 kg and 1.8 m:\n"
                + "- BMI = weight / (height * height)\n"
                + "- BMI = 72 / (1.8 * 1.8)\n"
                + "- BMI = 72 / 3.24\n"
                + "- BMI \u2248 22.19\n\n"
                + "The two JavaScript files are:\n"
                + "1. `script.js`: This file is correctly linked in `index.html` and contains the logic for calculating the BMI.\n"
                + "2. `scripts.js`: This file is not linked in `index.html` and serves as an unrelated sentinel that must stay unchanged.";
    }
}
