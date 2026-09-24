package dev.talos.runtime.verification;

import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.Source;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NativeCatalogBoundaryTest {
    private static final String REQUEST = "Read guide.md, index.html and calc.js. "
            + "Explain why the Compute button fails and give the correct selector. "
            + "Give the flow according to guide.md for volume 24 L and elapsed 6 s.";
    private static final String HTML = """
            <!doctype html><html lang="en"><head><meta charset="utf-8"><title>Flow</title></head><body>
            <label>Volume (L)<input id="volume" value="24"></label>
            <label>Elapsed (s)<input id="elapsed" value="6"></label>
            <button id="compute">Compute</button>
            <p id="result"></p><script src="calc.js"></script>
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
    void unsupportedMemberAndConditionalExpressionsAreNotSplitIntoValidPrefixes() {
        for (String expression : List.of("volume + elapsed.toString()", "volume / elapsed ? 1 : 2",
                "volume ? volume * elapsed : volume + elapsed", "volume + elapsed[0]",
                "volume + elapsed!", "volume + elapsed; volume / elapsed",
                "missing + volume / elapsed", "missing - volume / elapsed", "missing.volume / elapsed",
                "missing >> volume / elapsed")) {
            var catalog = NativeReadOnlyAnswerProposal.catalog(REQUEST, sources("The flow is " + expression + ".\n"));
            assertTrue(catalog.formulas().stream().noneMatch(formula ->
                    formula.origin() == ReadOnlyAnswerProposal.FormulaOrigin.SOURCE), expression);
        }
    }

    @Test
    void uppercaseWordOperatorsAndLeadingDecimalsRetainExactSpans() {
        for (String expression : List.of("VOLUME DIVIDED BY ELAPSED", ".5 * volume / elapsed")) {
            String text = "Formula: " + expression + ".\n";
            var catalog = NativeReadOnlyAnswerProposal.catalog(REQUEST, sources(text));
            var formulas = catalog.formulas().stream().filter(formula ->
                    formula.origin() == ReadOnlyAnswerProposal.FormulaOrigin.SOURCE).toList();
            assertEquals(1, formulas.size(), expression);
            assertEquals(expression, formulas.getFirst().expression());
            assertEquals(expression, text.substring(formulas.getFirst().span().start(), formulas.getFirst().span().end()));
        }
    }

    @Test
    void repeatedRequestVariablesRetainTheirParsedExpressionOccurrence() {
        String clause = "Evaluate a for a = 2";
        String request = REQUEST.substring(0, REQUEST.indexOf("Give the flow")) + clause + ". " + clause + ".";
        var catalog = NativeReadOnlyAnswerProposal.catalog(request, sources("The flow is volume divided by elapsed.\n"));
        var expressions = catalog.formulas().stream().filter(formula ->
                formula.origin() == ReadOnlyAnswerProposal.FormulaOrigin.REQUEST).toList();
        assertEquals(2, expressions.size());
        assertNotEquals(expressions.get(0).span(), expressions.get(1).span());
        for (var expression : expressions) assertEquals("a", request.substring(expression.span().start(), expression.span().end()));
    }

    @Test
    void nativeJsonStructureCannotBeRepairedOrDowngraded() throws Exception {
        List<Source> sources = sources("The flow is volume divided by elapsed.\n");
        String valid = NativeAnswerProposalTestSupport.candidate(REQUEST, sources);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var wrongType = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(valid);
        wrongType.put("version", "6");
        var wrongResults = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(valid);
        wrongResults.putNull("results");
        for (String candidate : List.of("null", "[]", valid + " {}", "```json\n" + valid + "\n```",
                valid.replaceFirst("\\{", "{\"version\":6,"), wrongType.toString(), wrongResults.toString())) {
            var assessed = NativeReadOnlyAnswerProposal.assess(REQUEST, candidate, sources);
            assertTrue(assessed.assessment().limited(), candidate);
            assertFalse(assessed.components().requestCovered(), candidate);
            assertFalse(assessed.components().complete(), candidate);
        }
    }

    private static List<Source> sources(String guide) {
        return List.of(source("guide.md", guide), source("index.html", HTML), source("calc.js", SCRIPT));
    }

    private static Source source(String path, String text) {
        return new Source(path, text, java.util.UUID.nameUUIDFromBytes(path.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(), 1,
                ReadOnlyWebDiagnosticResult.textHash(text), "numbered-read-lf-v1");
    }
}
