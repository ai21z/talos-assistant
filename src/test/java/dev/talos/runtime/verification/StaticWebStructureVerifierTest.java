package dev.talos.runtime.verification;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticWebStructureVerifierTest {

    @Test
    void ownsHtmlStructureAndInlineAssetFacts() {
        List<String> problems = StaticWebStructureVerifier.htmlStructureProblems(
                "index.html",
                """
                <html>
                  <body>
                    <button>Run</button
                    <script src="script.js"></script
                  </body>
                </html>
                """);

        assertTrue(problems.contains("index.html: malformed closing tag `</button>` is missing `>`."), problems::toString);
        assertTrue(problems.contains("index.html: malformed closing tag `</script>` is missing `>`."), problems::toString);
        assertFalse(problems.stream().anyMatch(problem -> problem.contains("unclosed `<button>`")), problems::toString);

        assertTrue(StaticWebStructureVerifier.hasNonBlankInlineStyle("<style>body { color: red; }</style>"));
        assertTrue(StaticWebStructureVerifier.hasNonBlankInlineScript("<script>console.log('ready');</script>"));
        assertFalse(StaticWebStructureVerifier.hasNonBlankInlineStyle("<style>   </style>"));
        assertFalse(StaticWebStructureVerifier.hasNonBlankInlineScript("<script src=\"script.js\"></script>"));
    }

    @Test
    void ownsCalculatorFormProblems() {
        List<String> problems = StaticWebStructureVerifier.calculatorFormProblems(
                "Build a BMI calculator website with separate CSS and JavaScript files.",
                "<main><h1>BMI</h1></main>");

        assertEquals(List.of(
                "Calculator/form task is missing a form or input container.",
                "Calculator/form task is missing a weight input.",
                "Calculator/form task is missing a height input.",
                "Calculator/form task is missing a submit/calculate button.",
                "Calculator/form task is missing a result output element."
        ), problems);

        assertEquals(List.of(), StaticWebStructureVerifier.calculatorFormProblems(
                "Build a BMI calculator website with separate CSS and JavaScript files.",
                """
                <form id="bmi-form">
                  <input id="weight" type="number">
                  <input id="height" type="number">
                  <button type="submit">Calculate</button>
                  <output id="result"></output>
                </form>
                """));
    }
}
