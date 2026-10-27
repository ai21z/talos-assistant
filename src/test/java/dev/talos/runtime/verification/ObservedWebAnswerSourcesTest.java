package dev.talos.runtime.verification;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservedWebAnswerSourcesTest {
    private static final String SIMPLE_HTML = html("""
            <label>Distance (m)<input id="distance"></label>
            <label>Duration (s)<input id="duration"></label>
            <button id="run-now">Measure</button><p id="answer"></p>
            """);
    private static final String SIMPLE_SCRIPT = script("#run-old",
            input("distance", "distance") + input("duration", "duration"), "distance / duration", 2);

    @Test
    void heldOutPanelAreaUsesContainingLabelsAndDifferentOperandValues() {
        String markup = html("""
                <label>Panel span (cm)<input id="panel-span" value="99"></label>
                <label>Panel rise (cm)<input id="panel-rise" value="99"></label>
                <button id="panel-measure">Measure panel</button><p id="answer"></p>
                """);
        String source = script("#panel-meas", input("span", "panel-span") + input("rise", "panel-rise"),
                "span * rise", 1);
        var result = ObservedWebAnswerSources.inspect(markup, source);
        assertTrue(result.html().supported());
        assertEquals("", result.html().labelReason());
        assertEquals("Panel span", result.html().inputs().get("panel-span").label());
        assertEquals("cm", result.html().inputs().get("panel-rise").unit());
        assertEquals("99", result.html().inputs().get("panel-span").defaultValue());
        assertEquals("Measure panel", result.html().buttons().getFirst().label());
        assertEquals("#panel-meas", result.script().selector());
        assertEquals(Map.of("span", "panel-span", "rise", "panel-rise"), result.script().operands());
        assertComputation(result.script(), Map.of("span", "3.75", "rise", "6.4"), "24/1", "24.0");
    }

    @Test
    void heldOutFlowRateBindsExplicitForLabelsIndependentlyOfHtmlOrder() {
        String markup = html("""
                <input id="elapsed" value="10"><input id="volume" value="1">
                <label for="volume">Discharge (L)</label><label for="elapsed">Elapsed (min)</label>
                <button id="flow-start">Estimate flow</button><p id="answer"></p>
                """);
        String source = script("#flow-missing", input("minutes", "elapsed") + input("litres", "volume"),
                "litres / minutes", 3);
        var result = ObservedWebAnswerSources.inspect(markup, source);
        assertTrue(result.html().supported());
        assertEquals("", result.html().labelReason());
        assertEquals("Elapsed", result.html().inputs().get("elapsed").label());
        assertEquals("L", result.html().inputs().get("volume").unit());
        assertEquals(Map.of("minutes", "elapsed", "litres", "volume"), result.script().operands());
        assertComputation(result.script(), Map.of("litres", "33", "minutes", "4.4"), "15/2", "7.500");
    }

    @Test
    void heldOutParenthesizedFormulaRetainsSubtractionAndDenominatorAddition() {
        String markup = html("""
                <section><label for="gross">Gross (u)</label><input id="gross"></section>
                <section><label>Tare (u)<input id="tare"></label></section>
                <label>Count (n)<input id="count"></label><label>Reserve (n)<input id="reserve"></label>
                <button id="net">Compute net</button><p id="answer"></p>
                """);
        String source = script("#net-old", input("gross", "gross") + input("tare", "tare")
                        + input("count", "count") + input("reserve", "reserve"),
                "(gross - tare) / (count + reserve)", 2);
        var result = ObservedWebAnswerSources.inspect(markup, source);
        assertTrue(result.html().supported());
        var parsed = BoundedAnswerArithmetic.parse(result.script().expression());
        assertEquals("((gross - tare) / (count + reserve))", parsed.canonical());
        assertNotEquals(BoundedAnswerArithmetic.parse("gross - tare / count + reserve").canonical(), parsed.canonical());
        assertComputation(result.script(), Map.of("gross", "17.4", "tare", "2.4", "count", "1.25", "reserve", "1.75"),
                "5/1", "5.00");
    }

    @Test
    void heldOutNegativeProductAndUnarySquareAreDifferentSourceRelations() {
        String declarations = input("drift", "drift");
        var product = ObservedWebAnswerSources.inspect(SIMPLE_HTML,
                script("#measure", declarations, "(-drift) * (-drift)", 2)).script();
        var negative = ObservedWebAnswerSources.inspect(SIMPLE_HTML,
                script("#measure", declarations, "-(drift * drift)", 2)).script();
        assertComputation(product, Map.of("drift", "-1.25"), "25/16", "1.56");
        assertComputation(negative, Map.of("drift", "-1.25"), "-25/16", "-1.56");
        assertNotEquals(BoundedAnswerArithmetic.parse(product.expression()).canonical(),
                BoundedAnswerArithmetic.parse(negative.expression()).canonical());
    }

    @Test
    void heldOutExistingWrongTargetIsPreservedForTheRequirementLayerToReject() {
        String markup = html("""
                <label>Cost (EUR)<input id="cost"></label><label>Count (n)<input id="count"></label>
                <button id="reprice">Reprice</button><button id="cancel">Cancel</button><p id="answer"></p>
                """);
        var result = ObservedWebAnswerSources.inspect(markup,
                script("#cancel", input("cost", "cost") + input("count", "count"), "cost / count", 2));
        assertTrue(result.html().supported());
        assertTrue(result.script().supported());
        assertEquals("#cancel", result.script().selector());
        assertEquals(List.of("reprice", "cancel"), result.html().buttons().stream().map(ObservedWebAnswerSources.Button::id).toList());
        assertEquals(List.of("Reprice", "Cancel"), result.html().buttons().stream().map(ObservedWebAnswerSources.Button::label).toList());
        assertComputation(result.script(), Map.of("cost", "19.95", "count", "7"), "57/20", "2.85");
    }

    @Test
    void heldOutRepeatedUnitsRemainDistinctLabelsAndSourceVariables() {
        String markup = html("""
                <label>Near (m)<input id="near"></label><label>Far (m)<input id="far"></label>
                <button id="separation">Separation</button><p id="answer"></p>
                """);
        var result = ObservedWebAnswerSources.inspect(markup,
                script("#sep", input("farEdge", "far") + input("nearEdge", "near"), "farEdge - nearEdge", 2));
        assertEquals("m", result.html().inputs().get("near").unit());
        assertEquals("m", result.html().inputs().get("far").unit());
        assertEquals("Near", result.html().inputs().get("near").label());
        assertEquals("Far", result.html().inputs().get("far").label());
        assertEquals(Map.of("farEdge", "far", "nearEdge", "near"), result.script().operands());
        assertComputation(result.script(), Map.of("farEdge", "11.125", "nearEdge", "2.375"), "35/4", "8.75");
    }

    @Test
    void preservesConcreteQueryAndListenerLinesWithoutClaimingExecution() {
        var result = ObservedWebAnswerSources.inspect(SIMPLE_HTML, SIMPLE_SCRIPT);
        assertTrue(result.script().supported());
        assertTrue(result.script().calculationSupported());
        assertEquals("click", result.script().event());
        assertEquals(1, result.script().queryLine());
        assertEquals(2, result.script().listenerLine());
        assertEquals("answer", result.script().outputId());
        assertEquals(Set.of("distance", "duration", "run-now", "answer"), result.html().ids());
        assertEquals(3, result.html().inputs().get("distance").line());
    }

    @Test
    void balancedHtmlAndLiteralTextAreRequiredForLabelsAndTargets() {
        for (String markup : List.of("<div><button id=\"a\">Run</div></button>",
                "<button id=\"a\"><span>Run</span></button>",
                "<button id=\"a\">R<!-- concealed -->un</button>",
                "<button id=\"a\">R&amp;un</button>",
                "<button id=\"a\" onclick=\"run()\">Run</button>",
                "<button id=\"a\" id=\"b\">Run</button>",
                "<button id=\"a\">Run</button><p id=\"a\"></p>",
                "<label>First (m)<label>Second (m)<input id=\"x\"></label></label>",
                "<label>First (m)<input id=\"x\"><input id=\"y\"></label>",
                "<label><span>First (m)</span><input id=\"x\"></label>",
                "<script>document.write('replacement');</script>",
                "<template><button id=\"a\">Run</button></template>")) {
            assertFalse(ObservedWebAnswerSources.inspect(markup, SIMPLE_SCRIPT).html().supported(), markup);
        }
    }

    @Test
    void ambiguousAndUnmatchedLabelRelationsAreExplicitWithoutLosingButtonFacts() {
        for (String labels : List.of("<label for=\"distance\">Distance (m)</label><label for=\"distance\">Length (m)</label>",
                "<label for=\"missing\">Distance (m)</label>", "<label for=\"distance\">Distance</label>")) {
            var result = ObservedWebAnswerSources.inspect(html(labels
                    + "<input id=\"distance\"><button id=\"run-now\">Measure</button>"), SIMPLE_SCRIPT);
            assertTrue(result.html().supported());
            assertEquals("INPUT_LABEL_UNSUPPORTED", result.html().labelReason());
            assertEquals("Measure", result.html().buttons().getFirst().label());
        }
    }

    @Test
    void rejectsDynamicScopedAndDifferentLookupOrListenerApis() {
        for (String source : List.of(
                SIMPLE_SCRIPT.replace("document.querySelector('#run-old')", "document.querySelector('#' + 'run-old')"),
                SIMPLE_SCRIPT.replace("document.querySelector('#run-old')", "document.querySelectorAll('#run-old')"),
                SIMPLE_SCRIPT.replace("document.querySelector('#run-old')", "document.getElementById('run-old')"),
                SIMPLE_SCRIPT.replace("document.querySelector('#run-old')", "container.querySelector('#run-old')"),
                SIMPLE_SCRIPT.replace("document.querySelector('#run-old')", "document['querySelector']('#run-old')"),
                SIMPLE_SCRIPT.replace("addEventListener", "removeEventListener"),
                SIMPLE_SCRIPT.replace("'click'", "'submit'"),
                SIMPLE_SCRIPT.replace("const trigger", "let trigger"),
                "const document = fake;\n" + SIMPLE_SCRIPT,
                SIMPLE_SCRIPT.replace("const trigger", "const document").replace("trigger.addEventListener", "document.addEventListener"))) {
            assertFalse(ObservedWebAnswerSources.inspect(SIMPLE_HTML, source).script().supported(), source);
        }
    }

    @Test
    void unsupportedCallbackStatementsDoNotEraseAnEstablishedLookupListenerRelation() {
        for (String source : List.of(
                SIMPLE_SCRIPT.replace("Number(document.querySelector('#distance').value)", "parseFloat(document.querySelector('#distance').value)"),
                SIMPLE_SCRIPT.replace("Number(document.querySelector('#distance').value)", "Number(document.querySelector('#distance').textContent)"),
                SIMPLE_SCRIPT.replace("distance / duration", "Math.pow(distance, duration)"),
                SIMPLE_SCRIPT.replace("distance / duration", "distance ** duration"),
                SIMPLE_SCRIPT.replace("textContent", "innerHTML"),
                SIMPLE_SCRIPT.replace("toFixed(2)", "toFixed(distance)"),
                SIMPLE_SCRIPT.replace("toFixed(2)", "toFixed(13)"),
                SIMPLE_SCRIPT.replace("const duration", "let duration"),
                SIMPLE_SCRIPT.replace("const duration", "const Number"))) {
            var script = ObservedWebAnswerSources.inspect(SIMPLE_HTML, source).script();
            // Unsupported lexical forms can reject the whole source before parsing.
            if (source.contains("**")) {
                assertFalse(script.calculationSupported(), source);
            } else {
                assertTrue(script.supported(), source);
                assertFalse(script.calculationSupported(), source);
                assertEquals("#run-old", script.selector());
            }
        }
    }

    @Test
    void namedCallbacksCannotShadowTheDomOrNumericConversionBindings() {
        for (String functionName : List.of("document", "Number")) {
            String source = SIMPLE_SCRIPT.replace("() => {", "function " + functionName + "() {");
            var result = ObservedWebAnswerSources.inspect(SIMPLE_HTML, source).script();
            assertTrue(result.supported(), functionName);
            assertFalse(result.calculationSupported(), functionName);
        }
    }

    @Test
    void generatorCallbacksCannotEstablishExecutionOfTheCalculationBody() {
        String source = SIMPLE_SCRIPT.replace("() => {", "function* () {");
        var result = ObservedWebAnswerSources.inspect(SIMPLE_HTML, source).script();
        assertFalse(result.calculationSupported());
    }

    @Test
    void constructorSyntaxDoesNotAcquireOrdinaryApiCallSemantics() {
        for (String source : List.of(
                SIMPLE_SCRIPT.replace("document.querySelector('#run-old')", "new document.querySelector('#run-old')"),
                SIMPLE_SCRIPT.replace("trigger.addEventListener(", "new trigger.addEventListener("),
                SIMPLE_SCRIPT.replace("Number(document.querySelector('#distance').value)", "new Number(document.querySelector('#distance').value)"),
                SIMPLE_SCRIPT.replace("(distance / duration).toFixed(2)", "new (distance / duration).toFixed(2)"))) {
            assertFalse(ObservedWebAnswerSources.inspect(SIMPLE_HTML, source).script().calculationSupported(), source);
        }
    }

    @Test
    void legacyOctalLiteralsCannotBeRelabeledAsExactDecimalArithmetic() {
        String source = script("#run-old", input("distance", "distance"), "distance * 010", 2);
        var result = ObservedWebAnswerSources.inspect(SIMPLE_HTML, source).script();
        assertFalse(result.calculationSupported());
    }

    @Test
    void stringsAndCommentsAreInertWhileTemplatesAndRegularExpressionsAreUnsupported() {
        String comment = "/* document = replacement; Number = fake; (((((((((((((((((((((((( */\n";
        var commented = ObservedWebAnswerSources.inspect(SIMPLE_HTML, comment + SIMPLE_SCRIPT).script();
        assertTrue(commented.calculationSupported());
        assertEquals(2, commented.queryLine());
        for (String source : List.of(
                SIMPLE_SCRIPT.replace("'#run-old'", "`#run-old`"),
                SIMPLE_SCRIPT.replace("'#run-old'", "/run-old/"),
                SIMPLE_SCRIPT.replace("'#run-old'", "'document.querySelector(\"#inert\")'"),
                "/* unterminated " + SIMPLE_SCRIPT,
                SIMPLE_SCRIPT.replace("'#run-old'", "'unterminated"))) {
            assertFalse(ObservedWebAnswerSources.inspect(SIMPLE_HTML, source).script().supported(), source);
        }
    }

    @Test
    void sourceAdmissionBoundsPrecedeTheJavascriptParser() {
        for (String source : List.of(";".repeat(2_049), " ".repeat(16_001),
                "(".repeat(65) + "x" + ")".repeat(65), "+".repeat(17) + "x;",
                "a=".repeat(17) + "1;", "typeof ".repeat(17) + "x;", "void ".repeat(17) + "x;",
                "delete ".repeat(17) + "x;", "new ".repeat(17) + "Object;", "x**".repeat(17) + "x;")) {
            var script = ObservedWebAnswerSources.inspect(SIMPLE_HTML, source).script();
            assertEquals("JAVASCRIPT_ADMISSION_UNSUPPORTED", script.reason(), source);
        }
        assertEquals("HTML_LIMIT", ObservedWebAnswerSources.inspect(" ".repeat(16_001), SIMPLE_SCRIPT).html().reason());
        assertFalse(ObservedWebAnswerSources.inspect("<div>".repeat(65) + "</div>".repeat(65), SIMPLE_SCRIPT).html().supported());
    }

    private static String html(String body) {
        return "<!doctype html>\n<html><body>\n" + body + "\n</body></html>";
    }

    private static String input(String variable, String id) {
        return "  const " + variable + " = Number(document.querySelector('#" + id + "').value);\n";
    }

    private static String script(String selector, String declarations, String expression, int precision) {
        return "const trigger = document.querySelector('" + selector + "');\n"
                + "trigger.addEventListener('click', () => {\n" + declarations
                + "  document.querySelector('#answer').textContent = (" + expression + ").toFixed(" + precision + ");\n"
                + "});\n";
    }

    private static void assertComputation(ObservedWebAnswerSources.Script script, Map<String, String> values,
                                          String exact, String rounded) {
        assertTrue(script.supported(), script.reason());
        assertTrue(script.calculationSupported(), script.calculationReason());
        var result = BoundedAnswerArithmetic.parse(script.expression()).evaluate(values, script.precision());
        assertTrue(result.successful(), result.status().name());
        assertEquals(exact, result.exact());
        assertEquals(rounded, result.rounded());
    }
}
