package dev.talos.runtime.verification;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.tools.VerificationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class StaticTaskVerifierTest {

    @TempDir
    Path workspace;

    @Test
    void noSuccessfulMutationDoesNotRunVerification() {
        ToolCallLoop.LoopResult loopResult = loopResult(List.of());

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace, "Check the website.", loopResult, 0);

        assertEquals(TaskVerificationStatus.NOT_RUN, result.status());
    }

    @Test
    void literalExactMatchPassesTaskVerification() throws Exception {
        Files.writeString(workspace.resolve("index.html"), "AFTER");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Overwrite index.html with exactly AFTER. Use talos.write_file.",
                loopResult(List.of(successfulWrite("index.html", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status());
        assertTrue(result.summary().contains("Exact content verification passed"), result.summary());
        assertTrue(result.facts().stream().anyMatch(f -> f.contains("literal content matched")));
    }

    @Test
    void literalMismatchFailsInsteadOfReadbackOnly() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <html>
                <body>
                <h1>Hello World</h1>
                </body>
                </html>
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Overwrite index.html with exactly AFTER. Use talos.write_file.",
                loopResult(List.of(successfulWrite("index.html", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.summary().contains("Exact content verification failed"), result.summary());
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("index.html: exact content mismatch")));
    }

    @Test
    void scriptImportInspectionReportsScriptsJsWhenCurrentIndexImportsScriptsJs() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <html><body><script src="scripts.js"></script></body></html>
                """);

        String out = StaticTaskVerifier.renderScriptImportInspection(
                workspace,
                "Which file does index.html import for the BMI script, script.js or scripts.js?");

        assertTrue(out.contains("`index.html` imports `scripts.js`."), out);
        assertFalse(out.contains("Neither `script.js` nor `scripts.js`"), out);
    }

    @Test
    void scriptImportInspectionReportsScriptJsWhenCurrentIndexImportsScriptJs() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <html><body><script src="script.js"></script></body></html>
                """);

        String out = StaticTaskVerifier.renderScriptImportInspection(
                workspace,
                "Which file does index.html import for the BMI script, script.js or scripts.js?");

        assertTrue(out.contains("`index.html` imports `script.js`."), out);
        assertFalse(out.contains("`index.html` imports `scripts.js`."), out);
    }

    @Test
    void scriptImportInspectionReportsNeitherWhenCurrentIndexHasNoScriptImport() throws Exception {
        Files.writeString(workspace.resolve("index.html"), "AFTER\n");

        String out = StaticTaskVerifier.renderScriptImportInspection(
                workspace,
                "Which file does index.html import for the BMI script, script.js or scripts.js?");

        assertTrue(out.contains("Neither `script.js` nor `scripts.js` is imported by `index.html`."), out);
        assertTrue(out.contains("Current script imports found in `index.html`: none."), out);
    }

    @Test
    void scriptImportInspectionGroundsCandidateOnlyQuestionInCurrentIndexHtml() throws Exception {
        Files.writeString(workspace.resolve("index.html"), "AFTER\n");
        Files.writeString(workspace.resolve("script.js"), "console.log('old');\n");
        Files.writeString(workspace.resolve("scripts.js"), "console.log('new');\n");

        String out = StaticTaskVerifier.renderScriptImportInspection(
                workspace,
                "Which exact file currently imports the BMI script, script.js or scripts.js?");

        assertNotNull(out);
        assertTrue(out.contains("[Static web import check]"), out);
        assertTrue(out.contains("Neither `script.js` nor `scripts.js` is imported by `index.html`."), out);
        assertTrue(out.contains("Current script imports found in `index.html`: none."), out);
    }

    @Test
    void scriptImportInspectionUsesInferredIndexHtmlInLargerAuditFixture() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# Audit fixture\n");
        Files.writeString(workspace.resolve("notes.md"), "Private note marker.\n");
        Files.writeString(workspace.resolve("config.json"), "{\"project\":\"audit\"}\n");
        Files.writeString(workspace.resolve("report.docx"), "fake unsupported binary payload");
        Files.writeString(workspace.resolve("index.html"), "AFTER\n");
        Files.writeString(workspace.resolve("script.js"), "console.log('old');\n");
        Files.writeString(workspace.resolve("scripts.js"), "console.log('new');\n");
        Files.writeString(workspace.resolve("styles.css"), "body { margin: 0; }\n");

        String out = StaticTaskVerifier.renderScriptImportInspection(
                workspace,
                "Which exact file currently imports the BMI script, script.js or scripts.js? "
                        + "Verify from current files and answer only after inspection. "
                        + "Do not read protected files.");

        assertNotNull(out);
        assertTrue(out.contains("[Static web import check]"), out);
        assertTrue(out.contains("Neither `script.js` nor `scripts.js` is imported by `index.html`."), out);
        assertTrue(out.contains("Current script imports found in `index.html`: none."), out);
    }

    @Test
    void webDiagnosticsReportsBrokenButtonEvidenceInsteadOfOptimisticSuccess() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <link rel="stylesheet" href="styles.css">
                </head>
                <body>
                  <main>
                    <h1>Focused Button</h1>
                    <p id="result" aria-live="polite">Waiting.</p>
                  </main>
                </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), "body { font-family: sans-serif; }\n");
        Files.writeString(workspace.resolve("script.js"), """
                const button = document.querySelector('.cta-button');
                const result = document.querySelector('#result');

                if (button && result) {
                  button.addEventListener('click', () => {
                    result.textC;
                  });
                }
                """);

        String out = StaticTaskVerifier.renderWebDiagnostics(
                workspace,
                List.of("index.html", "script.js"));

        assertNotNull(out);
        assertTrue(out.contains("Static web diagnostics found:"), out);
        assertTrue(out.contains("HTML does not link JavaScript file: `script.js`"), out);
        assertTrue(out.contains("JavaScript references missing class selectors: `.cta-button`"), out);
        assertTrue(out.contains("button click handler references `#result`"), out);
        assertFalse(out.contains("did not find obvious"), out);
    }

    @Test
    void exactTwoLineReadmeLiteralPassesTaskVerification() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "T71 exact README\nLine two");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Edit README.md now using talos.write_file. "
                        + "The complete file must contain exactly two lines: "
                        + "first line T71 exact README; second line Line two; no other characters.",
                loopResult(List.of(successfulWrite("README.md", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status());
        assertTrue(result.summary().contains("Exact content verification passed"), result.summary());
        assertTrue(result.facts().stream().anyMatch(f -> f.contains("README.md: literal content matched")));
    }

    @Test
    void exactTwoLineReadmeLiteralMismatchFailsInsteadOfReadbackOnly() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "T71 exact README\nWrong second line");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Edit README.md now using talos.write_file. "
                        + "The complete file must contain exactly two lines: "
                        + "first line T71 exact README; second line Line two; no other characters.",
                loopResult(List.of(successfulWrite("README.md", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.summary().contains("Exact content verification failed"), result.summary());
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("README.md: exact content mismatch")));
    }

    @Test
    void literalExpectationTraceEventIsRedacted() throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<html>wrong</html>");
        LocalTurnTraceCapture.begin(
                "trc-test-literal",
                "session-test",
                1,
                "2026-04-29T00:00:00Z",
                "workspace-hash",
                "auto",
                "ollama",
                "qwen2.5-coder:14b",
                "Overwrite index.html with exactly AFTER. Use talos.write_file.");

        try {
            StaticTaskVerifier.verify(
                    workspace,
                    "Overwrite index.html with exactly AFTER. Use talos.write_file.",
                    loopResult(List.of(successfulWrite("index.html", VerificationStatus.PASS))),
                    0);
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            var event = trace.events().stream()
                    .filter(e -> e.type().equals("EXPECTATION_VERIFIED"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("LITERAL_CONTENT", event.data().get("kind"));
            assertEquals("FAILED", event.data().get("status"));
            assertEquals("index.html", event.data().get("pathHint"));
            assertTrue(event.data().containsKey("expectedHash"));
            assertTrue(event.data().containsKey("observedHash"));
            assertFalse(event.data().containsValue("AFTER"),
                    "default trace must not store raw literal content");
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void selectorRepairFailsWhenMutationLeavesReferencedClassMissing() throws Exception {
        writeWebFiles("""
                <!DOCTYPE html>
                <html>
                  <head><link rel="stylesheet" href="style.css"></head>
                  <body><main id="hero"><p>No CTA yet</p></main><script src="script.js"></script></body>
                </html>
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Fix index.html so the CSS and JavaScript .cta-button selector has a matching element.",
                loopResult(List.of(successfulEdit("index.html", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.problems().stream().anyMatch(p -> p.contains("`.cta-button`")));
    }

    @Test
    void selectorRepairPassesWhenHtmlProvidesReferencedClass() throws Exception {
        writeWebFiles("""
                <!DOCTYPE html>
                <html>
                  <head><link rel="stylesheet" href="style.css"></head>
                  <body><main id="hero"><a class="cta-button">Listen</a></main><script src="script.js"></script></body>
                </html>
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Fix index.html so the CSS and JavaScript .cta-button selector has a matching element.",
                loopResult(List.of(successfulEdit("index.html", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status());
        assertTrue(result.facts().stream().anyMatch(f -> f.contains("selector coherence passed")));
    }

    @Test
    void broadWebAppBuildFailsWhenJavaScriptReferencesMissingHtmlIds() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <head>
                    <link rel="stylesheet" href="styles.css">
                  </head>
                  <body>
                    <main class="calculator">
                      <h1>BMI Calculator</h1>
                      <p>No form exists yet.</p>
                    </main>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), """
                .calculator { max-width: 28rem; }
                .result { font-weight: 700; }
                """);
        Files.writeString(workspace.resolve("script.js"), """
                document.getElementById('bmi-form').addEventListener('submit', event => event.preventDefault());
                document.getElementById('weight');
                document.getElementById('height');
                document.getElementById('result');
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Can you build a small BMI calculator website here with separate CSS and JavaScript files?",
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("styles.css", VerificationStatus.PASS),
                        successfulWrite("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.problems().stream().anyMatch(p -> p.contains("JavaScript references missing IDs")));
        assertTrue(result.problems().stream().anyMatch(p -> p.contains("`#bmi-form`")));
    }

    @Test
    void broadWebAppBuildFailsWhenLinkedAssetsAreDuplicated() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <head>
                    <link rel="stylesheet" href="styles.css">
                    <link rel="stylesheet" href="styles.css">
                  </head>
                  <body>
                    <main class="calculator">
                      <h1>BMI Calculator</h1>
                      <form id="bmi-form">
                        <input id="weight" type="number">
                        <input id="height" type="number">
                        <button type="submit">Calculate</button>
                      </form>
                      <p id="result"></p>
                    </main>
                    <script src="script.js"></script>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), ".calculator { max-width: 28rem; }");
        Files.writeString(workspace.resolve("script.js"), """
                document.getElementById('bmi-form').addEventListener('submit', event => event.preventDefault());
                document.getElementById('weight');
                document.getElementById('height');
                document.getElementById('result');
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Can you build a small BMI calculator website here with separate CSS and JavaScript files?",
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("styles.css", VerificationStatus.PASS),
                        successfulWrite("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("HTML links CSS file more than once: `styles.css`")));
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("HTML links JavaScript file more than once: `script.js`")));
    }

    @Test
    void broadWebAppBuildFailsWhenHtmlIdsAreDuplicated() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <head>
                    <link rel="stylesheet" href="styles.css">
                  </head>
                  <body>
                    <main class="calculator">
                      <h1>BMI Calculator</h1>
                      <form id="bmi-form">
                        <input id="weight" type="number">
                        <input id="height" type="number">
                        <button type="submit">Calculate</button>
                      </form>
                      <p id="result"></p>
                      <div id="result"></div>
                    </main>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), ".calculator { max-width: 28rem; }");
        Files.writeString(workspace.resolve("script.js"), """
                document.getElementById('bmi-form').addEventListener('submit', event => event.preventDefault());
                document.getElementById('weight');
                document.getElementById('height');
                document.getElementById('result');
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Can you build a small BMI calculator website here with separate CSS and JavaScript files?",
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("styles.css", VerificationStatus.PASS),
                        successfulWrite("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("HTML defines duplicate IDs: `#result`")));
    }

    @Test
    void broadWebAppBuildFailsWhenJavaScriptIsPlaceholder() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <head>
                    <link rel="stylesheet" href="styles.css">
                  </head>
                  <body>
                    <main class="calculator">
                      <h1>BMI Calculator</h1>
                      <form id="bmi-form">
                        <input id="weight" type="number">
                        <input id="height" type="number">
                        <button type="submit">Calculate</button>
                      </form>
                      <p id="result"></p>
                    </main>
                    <script src="scripts.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), ".calculator { max-width: 28rem; }");
        Files.writeString(workspace.resolve("scripts.js"), "// Your JavaScript logic here");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Build a functioning BMI calculator website with separate CSS and JavaScript files.",
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("styles.css", VerificationStatus.PASS),
                        successfulWrite("scripts.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("scripts.js: JavaScript file appears to be placeholder content")));
    }

    @Test
    void calculatorWebTaskRequiresFormControlsButtonAndResult() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <head>
                    <link rel="stylesheet" href="styles.css">
                  </head>
                  <body>
                    <main class="calculator">
                      <h1>BMI Calculator</h1>
                      <p>No interactive form exists yet.</p>
                    </main>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), ".calculator { max-width: 28rem; }");
        Files.writeString(workspace.resolve("script.js"), "document.body.dataset.ready = 'true';");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Build a functioning BMI calculator website with separate CSS and JavaScript files.",
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("styles.css", VerificationStatus.PASS),
                        successfulWrite("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("Calculator/form task is missing a form")));
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("weight input")));
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("height input")));
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("submit/calculate button")));
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("result output")));
    }

    @Test
    void functionalCalculatorTaskFailsWithConcreteProblemsWhenJavaScriptIsMissing() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <head>
                    <link rel="stylesheet" href="styles.css">
                  </head>
                  <body>
                    <main class="calculator">
                      <h1>BMI Calculator</h1>
                      <label>Weight <input id="weight" type="number"></label>
                      <label>Height <input id="height" type="number"></label>
                    </main>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), ".calculator { max-width: 28rem; }");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Hi, I don't really know coding. I have this little BMI page here and it only shows a title. Can you make it actually work for me?",
                loopResult(List.of(successfulWrite("index.html", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("missing JavaScript behavior")), result.problems().toString());
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("HTML does not link a JavaScript file")), result.problems().toString());
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("submit/calculate button")), result.problems().toString());
        assertTrue(result.problems().stream()
                .noneMatch(p -> p.contains("web coherence could not be checked")), result.problems().toString());
    }

    @Test
    void functionalCalculatorTaskDetectsDuplicateIdsWithoutJavaScriptFile() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <head>
                    <link rel="stylesheet" href="styles.css">
                  </head>
                  <body>
                    <main class="calculator">
                      <h1>BMI Calculator</h1>
                      <form id="bmi-form">
                        <input id="weight" type="number">
                        <input id="height" type="number">
                        <button type="submit">Calculate</button>
                      </form>
                      <p id="result"></p>
                      <div id="result"></div>
                    </main>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), ".calculator { max-width: 28rem; }");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Can you make me a working BMI calculator webpage here?",
                loopResult(List.of(successfulWrite("index.html", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("HTML defines duplicate IDs: `#result`")),
                result.problems().toString());
        assertTrue(result.problems().stream()
                .noneMatch(p -> p.contains("web coherence could not be checked")), result.problems().toString());
    }

    @Test
    void broadWebAppBuildPassesWhenHtmlCssAndJavaScriptAreLinked() throws Exception {
        writeValidBmiWebFiles();

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Can you build a small BMI calculator website here with separate CSS and JavaScript files?",
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("styles.css", VerificationStatus.PASS),
                        successfulWrite("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status());
        assertTrue(result.summary().contains("Static web coherence checks passed"));
        assertTrue(result.facts().stream().anyMatch(f -> f.contains("HTML/CSS/JS selector coherence passed")));
    }

    @Test
    void broadWebAppBuildRequiresSeparateCssAndJavaScriptMutations() throws Exception {
        writeValidBmiWebFiles();

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Build a BMI calculator website with separate CSS and JavaScript files.",
                loopResult(List.of(successfulWrite("index.html", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("Expected web-app build to successfully mutate a CSS file")));
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("Expected web-app build to successfully mutate a JavaScript file")));
    }

    @Test
    void selfContainedHtmlWebCreationPassesWhenStaticWebProfileAllowsSingleFile() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <head>
                    <title>BMI Calculator</title>
                    <style>
                      .calculator { max-width: 28rem; }
                      .result { font-weight: 700; }
                    </style>
                  </head>
                  <body>
                    <main class="calculator">
                      <h1>BMI Calculator</h1>
                      <form id="bmi-form">
                        <label>Weight <input id="weight" type="number"></label>
                        <label>Height <input id="height" type="number"></label>
                        <button type="submit">Calculate</button>
                      </form>
                      <p id="result" class="result"></p>
                    </main>
                    <script>
                      document.getElementById('bmi-form').addEventListener('submit', event => event.preventDefault());
                      document.getElementById('weight');
                      document.getElementById('height');
                      document.getElementById('result');
                    </script>
                  </body>
                </html>
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Create a self-contained BMI calculator webpage in index.html with inline CSS and JavaScript.",
                loopResult(List.of(successfulWrite("index.html", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status(), result.problems().toString());
        assertTrue(result.facts().stream()
                .anyMatch(f -> f.contains("Static Web capability profile selected")), result.facts().toString());
        assertTrue(result.facts().stream()
                .anyMatch(f -> f.contains("self-contained HTML")), result.facts().toString());
    }

    @Test
    void genericMakeItFollowUpRunsWebCoherenceWhenMutatingSmallWebSurface() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body><main class="calculator"><h1>BMI</h1></main><script src="script.js"></script></body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), ".calculator { max-width: 28rem; }");
        Files.writeString(workspace.resolve("script.js"), "document.getElementById('bmi-form');");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Can you make it?",
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("styles.css", VerificationStatus.PASS),
                        successfulWrite("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.problems().stream().anyMatch(p -> p.contains("`#bmi-form`")));
    }

    @Test
    void scriptOnlySelectorFixUsesSiblingWebSurfaceDespiteReadme() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# Public fixture\n");
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body><button class="cta-button">Go</button><script src="script.js"></script></body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), ".cta-button { color: red; }");
        Files.writeString(workspace.resolve("script.js"), """
                document.querySelector('.cta-button').addEventListener('click', () => console.log('ok'));
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Make script.js fix the selector bug by changing .missing-button to .cta-button.",
                loopResult(List.of(successfulEdit("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status(), result.problems().toString());
        assertTrue(result.problems().stream()
                .noneMatch(p -> p.contains("web coherence could not be checked")), result.problems().toString());
        assertTrue(result.facts().stream()
                .anyMatch(f -> f.contains("HTML/CSS/JS selector coherence passed")), result.facts().toString());
    }

    @Test
    void scriptOnlySelectorFixUsesTargetAwareWebSurfaceDespiteMixedWorkspaceFiles() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# Public fixture\n");
        Files.writeString(workspace.resolve("config.json"), "{\"name\":\"t57-fixture\"}\n");
        Files.writeString(workspace.resolve("notes.md"), "ALPHA-742\n");
        Files.writeString(workspace.resolve("report.docx"), "unsupported fixture\n");
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body><button class="cta-button">Go</button><script src="script.js"></script></body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), ".cta-button { color: red; }");
        Files.writeString(workspace.resolve("script.js"), """
                document.querySelector('.cta-button').addEventListener('click', () => console.log('ok'));
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Make script.js fix the selector bug by changing .missing-button to .cta-button.",
                loopResult(List.of(successfulEdit("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status(), result.problems().toString());
        assertTrue(result.problems().stream()
                .noneMatch(p -> p.contains("web coherence could not be checked")), result.problems().toString());
        assertTrue(result.facts().stream()
                .anyMatch(f -> f.contains("HTML/CSS/JS selector coherence passed")), result.facts().toString());
    }

    @Test
    void staticWebRepairContextFilesDoNotAllNeedMutationWhenFinalSurfacePasses() throws Exception {
        writeButtonFixtureWebFiles("""
                document.querySelector('#run-button').addEventListener('click', () => {
                  document.querySelector('#result').textContent = 'Clicked';
                });
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Fix the static web button fixture. The existing index.html loads script.js; "
                        + "the button with id run-button should set #result to Clicked. "
                        + "Keep filenames index.html, styles.css, and script.js. Do not create scripts.js.",
                loopResult(List.of(successfulEdit("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status(), result.problems().toString());
        assertTrue(result.problems().stream()
                        .noneMatch(p -> p.contains("expected target was not successfully mutated")),
                result.problems().toString());
        assertTrue(result.facts().stream()
                .anyMatch(f -> f.contains("HTML/CSS/JS selector coherence passed")), result.facts().toString());
    }

    @Test
    void staticButtonFixtureFailsWhenResultHandlerHasTruncatedTextContentAssignment() throws Exception {
        writeButtonFixtureWebFiles("""
                document.querySelector('#run-button').addEventListener('click', () => {
                  document.querySelector('#result').textC;
                });
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Fix the static web button fixture. The existing index.html loads script.js; "
                        + "the button with id run-button should set #result to Clicked. "
                        + "Keep filenames index.html, styles.css, and script.js. Do not create scripts.js.",
                loopResult(List.of(successfulEdit("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status(), result.facts().toString());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("script.js")
                                && p.contains("#result")
                                && p.contains("Clicked")),
                result.problems().toString());
    }

    @Test
    void staticButtonFixturePassesWhenQuerySelectorAssignsResultTextContent() throws Exception {
        writeButtonFixtureWebFiles("""
                document.querySelector('#run-button').addEventListener('click', () => {
                  document.querySelector('#result').textContent = 'Clicked';
                });
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Fix the static web button fixture. The existing index.html loads script.js; "
                        + "the button with id run-button should set #result to Clicked. "
                        + "Keep filenames index.html, styles.css, and script.js. Do not create scripts.js.",
                loopResult(List.of(successfulEdit("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status(), result.problems().toString());
        assertTrue(result.facts().stream()
                        .anyMatch(f -> f.contains("button/result behavior passed")),
                result.facts().toString());
    }

    @Test
    void staticButtonFixturePassesWhenGetElementByIdAssignsResultTextContent() throws Exception {
        writeButtonFixtureWebFiles("""
                document.getElementById('run-button').addEventListener('click', () => {
                  document.getElementById('result').textContent = 'Clicked';
                });
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Fix the static web button fixture. The existing index.html loads script.js; "
                        + "the button with id run-button should set #result to Clicked. "
                        + "Keep filenames index.html, styles.css, and script.js. Do not create scripts.js.",
                loopResult(List.of(successfulEdit("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status(), result.problems().toString());
        assertTrue(result.facts().stream()
                        .anyMatch(f -> f.contains("button/result behavior passed")),
                result.facts().toString());
    }

    @Test
    void readOnlyWebDiagnosticsReportTruncatedButtonResultAssignment() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body>
                    <button class="cta-button" type="button">Run action</button>
                    <p id="result">Waiting.</p>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), ".cta-button { color: red; }\n");
        Files.writeString(workspace.resolve("script.js"), """
                const button = document.querySelector('.cta-button');
                const result = document.querySelector('#result');

                if (button && result) {
                  button.addEventListener('click', () => {
                    result.textC;
                  });
                }
                """);

        String out = StaticTaskVerifier.renderWebDiagnostics(workspace);

        assertNotNull(out);
        assertTrue(out.contains("Static web diagnostics found:"), out);
        assertTrue(out.contains("script.js"), out);
        assertTrue(out.contains("does not assign visible result text"), out);
    }

    @Test
    void readOnlyWebDiagnosticsAcceptVisibleButtonResultAssignment() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body>
                    <button class="cta-button" type="button">Run action</button>
                    <p id="result">Waiting.</p>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), ".cta-button { color: red; }\n");
        Files.writeString(workspace.resolve("script.js"), """
                const button = document.querySelector('.cta-button');
                const result = document.querySelector('#result');

                if (button && result) {
                  button.addEventListener('click', () => {
                    result.textContent = 'Audit action complete.';
                  });
                }
                """);

        String out = StaticTaskVerifier.renderWebDiagnostics(workspace);

        assertNotNull(out);
        assertFalse(out.contains("does not assign visible result text"), out);
    }

    @Test
    void targetAwareWebSurfaceRefusesTooManyCandidateWebFiles() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# Public fixture\n");
        Files.writeString(workspace.resolve("config.json"), "{\"name\":\"t57-fixture\"}\n");
        Files.writeString(workspace.resolve("notes.md"), "ALPHA-742\n");
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <head>
                    <link rel="stylesheet" href="styles.css">
                    <link rel="stylesheet" href="theme.css">
                    <link rel="stylesheet" href="print.css">
                  </head>
                  <body><button class="cta-button">Go</button><script src="script.js"></script><script src="app.js"></script></body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), ".cta-button { color: red; }");
        Files.writeString(workspace.resolve("theme.css"), ".theme { color: blue; }");
        Files.writeString(workspace.resolve("print.css"), ".print { color: black; }");
        Files.writeString(workspace.resolve("script.js"), """
                document.querySelector('.cta-button').addEventListener('click', () => console.log('ok'));
                """);
        Files.writeString(workspace.resolve("app.js"), "document.body.dataset.app = 'true';");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Make script.js fix the selector bug by changing .missing-button to .cta-button.",
                loopResult(List.of(successfulEdit("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status(), result.facts().toString());
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("web coherence could not be checked")), result.problems().toString());
        assertTrue(result.facts().stream()
                .noneMatch(f -> f.contains("Target-aware web surface selected")), result.facts().toString());
    }

    @Test
    void htmlMustLinkPrimaryCssAndJavaScriptForWebCoherence() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html><body><main class="calculator"><p id="result"></p></main></body></html>
                """);
        Files.writeString(workspace.resolve("styles.css"), ".calculator { max-width: 28rem; }");
        Files.writeString(workspace.resolve("script.js"), "document.getElementById('result');");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Build a BMI calculator website with separate CSS and JavaScript files.",
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("styles.css", VerificationStatus.PASS),
                        successfulWrite("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("HTML does not link CSS file: `styles.css`")));
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("HTML does not link JavaScript file: `script.js`")));
    }

    @Test
    void expectedJavaScriptTargetBeatsStaleSiblingWhenHtmlLinkIsMissing() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body>
                    <main class="calculator">
                      <form id="bmi-form">
                        <input id="weight" type="number">
                        <input id="height" type="number">
                        <button type="submit">Calculate</button>
                      </form>
                      <p id="result"></p>
                    </main>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), ".calculator { max-width: 28rem; }");
        Files.writeString(workspace.resolve("script.js"), """
                document.querySelector('.missing-button').addEventListener('click', () => console.log('stale'));
                """);
        Files.writeString(workspace.resolve("scripts.js"), """
                document.getElementById('bmi-form').addEventListener('submit', event => event.preventDefault());
                document.getElementById('weight');
                document.getElementById('height');
                document.getElementById('result');
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Create a complete static BMI calculator in this folder with index.html, styles.css, and scripts.js.",
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("styles.css", VerificationStatus.PASS),
                        successfulWrite("scripts.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("HTML does not link JavaScript file: `scripts.js`")),
                result.problems().toString());
        assertFalse(result.problems().stream().anyMatch(p -> p.contains("script.js")),
                result.problems().toString());
        assertFalse(result.problems().stream().anyMatch(p -> p.contains(".missing-button")),
                result.problems().toString());
    }

    @Test
    void negatedLegacyScriptTargetIsNotRequiredByStaticVerification() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body>
                    <main class="calculator">
                      <form id="bmi-form">
                        <input id="weight" type="number">
                        <input id="height" type="number">
                        <button type="submit">Calculate</button>
                      </form>
                      <p id="result"></p>
                    </main>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), ".calculator { max-width: 28rem; }");
        Files.writeString(workspace.resolve("script.js"), "document.querySelector('.missing-button');");
        Files.writeString(workspace.resolve("scripts.js"), """
                document.getElementById('bmi-form').addEventListener('submit', event => event.preventDefault());
                document.getElementById('weight');
                document.getElementById('height');
                document.getElementById('result');
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Create a BMI calculator web page using exactly index.html, styles.css, scripts.js. Do not use script.js.",
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("styles.css", VerificationStatus.PASS),
                        successfulWrite("scripts.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("HTML does not link JavaScript file: `scripts.js`")),
                result.problems().toString());
        assertFalse(result.problems().stream()
                        .anyMatch(p -> p.contains("script.js: expected target was not successfully mutated")),
                result.problems().toString());
        assertFalse(result.problems().stream()
                        .anyMatch(p -> p.contains("script.js") && p.contains("does not satisfy")),
                result.problems().toString());
    }

    @Test
    void linkedCssFileIsPreferredOverLegacyCssNeighbor() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body>
                    <main class="calculator">
                      <form id="bmi-form">
                        <input id="weight" type="number">
                        <input id="height" type="number">
                        <button type="submit">Calculate</button>
                      </form>
                      <p id="result"></p>
                    </main>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("style.css"), ".legacy-missing { color: red; }");
        Files.writeString(workspace.resolve("styles.css"), ".calculator { max-width: 28rem; }");
        Files.writeString(workspace.resolve("script.js"), """
                document.getElementById('bmi-form').addEventListener('submit', event => event.preventDefault());
                document.getElementById('weight');
                document.getElementById('height');
                document.getElementById('result');
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Build a BMI calculator website with separate CSS and JavaScript files.",
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("styles.css", VerificationStatus.PASS),
                        successfulWrite("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status());
    }

    @Test
    void cssHexColorsAreNotTreatedAsIdSelectors() throws Exception {
        writeWebFiles("""
                <!DOCTYPE html>
                <html>
                  <head><link rel="stylesheet" href="style.css"></head>
                  <body><main id="hero"><a class="cta-button">Listen</a></main><script src="script.js"></script></body>
                </html>
                """);
        Files.writeString(workspace.resolve("style.css"), """
                body { background: #140014; color: #f8eaff; }
                #hero { padding: 48px; }
                .cta-button { color: #ffffff; }
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Check selector linkage and the .cta-button fix.",
                loopResult(List.of(successfulEdit("index.html", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status());
    }

    @Test
    void placeholderOnlyMutationFailsVerification() throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<updated_index_html_content>");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Update index.html.",
                loopResult(List.of(successfulEdit("index.html", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.summary().contains("template placeholder"));
    }

    @Test
    void fileLevelVerificationWarningFailsTaskVerification() throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<html><body><main></main></body></html>");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Update index.html.",
                loopResult(List.of(successfulEdit("index.html", VerificationStatus.WARN))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.summary().contains("file-level verification reported warning"));
    }

    @Test
    void nonWebMutationUsesNarrowTargetReadbackWording() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# Talos\n");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Update README.md.",
                loopResult(List.of(successfulEdit("README.md", VerificationStatus.UNKNOWN))),
                0);

        assertEquals(TaskVerificationStatus.READBACK_ONLY, result.status());
        assertTrue(result.summary().contains("Target/readback checks passed"));
        assertTrue(result.summary().contains("no task-specific static verifier was applicable"));
    }

    @Test
    void markdownDocumentAboutWebpageDoesNotRunStaticWebVerifier() throws Exception {
        Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(workspace.resolve("index.html"), "<!doctype html><html><body></body></html>");
        Files.writeString(workspace.resolve("styles.css"), "body { font-family: sans-serif; }");
        Files.writeString(workspace.resolve("script.js"), "console.log('fixture');");
        Files.writeString(workspace.resolve("docs/synthwave-webpage-plan.md"), """
                # Synthwave Webpage Plan

                - Use neon accent colors.
                - Keep band tour dates easy to scan.
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Create docs/synthwave-webpage-plan.md with a concise plan for a cool looking "
                        + "synthwave webpage for a band. Use a supported text format.",
                loopResult(List.of(successfulWrite("docs/synthwave-webpage-plan.md", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.READBACK_ONLY, result.status());
        assertTrue(result.summary().contains("Target/readback checks passed"), result.summary());
        assertTrue(result.summary().contains("no task-specific static verifier was applicable"), result.summary());
        assertTrue(result.problems().stream()
                .noneMatch(problem -> problem.contains("web coherence could not be checked")),
                result.problems().toString());
    }

    @Test
    void expectedTargetMatchingCanUseWindowsCaseInsensitiveSemantics() {
        assertTrue(StaticTaskVerifier.expectedTargetMatches("Index.html", "index.html", true));
        assertTrue(StaticTaskVerifier.expectedTargetMatches(".\\Index.html", "./index.html", true));
        assertFalse(StaticTaskVerifier.expectedTargetMatches("scripts.js", "script.js", true));
        assertFalse(StaticTaskVerifier.expectedTargetMatches("Index.html", "index.html", false));
    }

    @Test
    void expectedTargetFromContractMatchesCaseDifferenceOnWindows() throws Exception {
        assumeTrue(isWindows(), "Windows-specific verifier behavior is asserted only on Windows hosts.");
        Files.writeString(workspace.resolve("index.html"), "<html><body><main></main></body></html>");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                TaskContractResolver.fromUserRequest("Edit Index.html so the title changes."),
                loopResult(List.of(successfulEdit("index.html", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.READBACK_ONLY, result.status());
        assertTrue(result.facts().stream()
                .anyMatch(f -> f.contains("Expected mutation target(s) were updated")));
    }

    @Test
    void readOnlyWebDiagnosticsReportMalformedHtmlAndCssClassTypo() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <title>BMI Calculator</title>
                  <link rel="stylesheet" href="styles.css">
                </head>
                <body>
                  <div class="calculator-container">
                    <form id="bmi-form">
                      <button type="submit">Calculate BMI</button
                    </form>
                  </div>
                  <script src="script.js"></script
                </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), """
                body { font-family: Arial, sans-serif; }
                calculator-container { max-width: 420px; }
                """);
        Files.writeString(workspace.resolve("script.js"), """
                document.getElementById('bmi-form');
                """);

        String rendered = StaticTaskVerifier.renderWebDiagnostics(workspace);

        assertTrue(rendered.contains("Static web diagnostics found:"), rendered);
        assertTrue(rendered.contains("index.html: malformed closing tag `</button>` is missing `>`."), rendered);
        assertTrue(rendered.contains("index.html: malformed closing tag `</script>` is missing `>`."), rendered);
        assertTrue(rendered.contains("`calculator-container` should probably be `.calculator-container`"), rendered);
        assertTrue(rendered.contains("No files were changed."), rendered);
    }

    @Test
    void readOnlyWebDiagnosticsUseReadPathHintsInFullAuditFixture() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# Audit fixture\n");
        Files.writeString(workspace.resolve("notes.md"), "Private note marker.\n");
        Files.writeString(workspace.resolve("config.json"), "{\"project\":\"audit\"}\n");
        Files.writeString(workspace.resolve("report.docx"), "fake unsupported binary payload");
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                <head>
                  <meta charset="utf-8">
                  <link rel="stylesheet" href="styles.css">
                </head>
                <body>
                  <button class="cta-button" type="button">Run action</button>
                  <p id="result">Waiting.</p>
                  <script src="script.js"></script>
                </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), ".cta-button { color: red; }\n");
        Files.writeString(workspace.resolve("script.js"), """
                const button = document.querySelector('.cta-button');
                const result = document.querySelector('#result');
                if (button && result) {
                  button.addEventListener('click', () => {
                    result.textC;
                  });
                }
                """);

        String rendered = StaticTaskVerifier.renderWebDiagnostics(
                workspace,
                List.of("index.html", "script.js"));

        assertNotNull(rendered);
        assertTrue(rendered.contains("Static web diagnostics found:"), rendered);
        assertTrue(rendered.contains("script.js"), rendered);
        assertTrue(rendered.contains("does not assign visible result text"), rendered);
    }

    @Test
    void expectedTargetFromContractMustBeMutated() throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<html><body><main></main></body></html>");
        Files.writeString(workspace.resolve("style.css"), "body { color: white; }");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                TaskContractResolver.fromUserRequest("Edit index.html so the title changes."),
                loopResult(List.of(successfulEdit("style.css", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("index.html: expected target was not successfully mutated")));
    }

    @Test
    void expectedScriptsJsTargetFailsWhenOnlySingularScriptJsWasMutated() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body>
                    <main class="calculator">
                      <form id="bmi-form">
                        <input id="weight" type="number">
                        <input id="height" type="number">
                        <button type="submit">Calculate</button>
                      </form>
                      <p id="result"></p>
                    </main>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), ".calculator { max-width: 28rem; }");
        Files.writeString(workspace.resolve("script.js"), """
                document.getElementById('bmi-form').addEventListener('submit', event => event.preventDefault());
                document.getElementById('weight');
                document.getElementById('height');
                document.getElementById('result');
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Create a complete static BMI calculator in this folder with index.html, styles.css, and scripts.js.",
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("styles.css", VerificationStatus.PASS),
                        successfulWrite("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("scripts.js: expected target was not successfully mutated")),
                result.problems().toString());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("script.js") && p.contains("does not satisfy")),
                result.problems().toString());
        assertFalse(result.facts().stream()
                        .anyMatch(f -> f.contains("Expected mutation target(s) were updated")),
                result.facts().toString());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private void writeWebFiles(String html) throws Exception {
        Files.writeString(workspace.resolve("index.html"), html);
        Files.writeString(workspace.resolve("style.css"), """
                body { background: #140014; }
                #hero { padding: 48px; }
                .cta-button { display: inline-block; }
                """);
        Files.writeString(workspace.resolve("script.js"), """
                document.querySelector('.cta-button');
                """);
    }

    private void writeValidBmiWebFiles() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <head>
                    <link rel="stylesheet" href="styles.css">
                  </head>
                  <body>
                    <main class="calculator">
                      <h1>BMI Calculator</h1>
                      <form id="bmi-form">
                        <input id="weight" type="number">
                        <input id="height" type="number">
                        <button type="submit">Calculate</button>
                      </form>
                      <p id="result" class="result"></p>
                    </main>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), """
                .calculator { max-width: 28rem; }
                .result { font-weight: 700; }
                """);
        Files.writeString(workspace.resolve("script.js"), """
                document.getElementById('bmi-form').addEventListener('submit', event => event.preventDefault());
                document.getElementById('weight');
                document.getElementById('height');
                document.getElementById('result');
                """);
    }

    private void writeButtonFixtureWebFiles(String script) throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                <head>
                  <meta charset="utf-8">
                  <title>Talos Button Fixture</title>
                  <link rel="stylesheet" href="styles.css">
                </head>
                <body>
                  <main>
                    <button id="run-button">Run</button>
                    <p id="result">Waiting</p>
                  </main>
                  <script src="script.js"></script>
                </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), """
                body { font-family: system-ui, sans-serif; }
                main { max-width: 32rem; margin: 2rem auto; }
                button { padding: 0.5rem 0.75rem; }
                """);
        Files.writeString(workspace.resolve("script.js"), script);
    }

    private static ToolCallLoop.ToolOutcome successfulEdit(String path, VerificationStatus verificationStatus) {
        return new ToolCallLoop.ToolOutcome(
                "talos.edit_file", path, true, true, false,
                "edited " + path, "", verificationStatus);
    }

    private static ToolCallLoop.ToolOutcome successfulWrite(String path, VerificationStatus verificationStatus) {
        return new ToolCallLoop.ToolOutcome(
                "talos.write_file", path, true, true, false,
                "wrote " + path, "", verificationStatus);
    }

    private static ToolCallLoop.LoopResult loopResult(List<ToolCallLoop.ToolOutcome> outcomes) {
        int successes = (int) outcomes.stream()
                .filter(ToolCallLoop.ToolOutcome::mutating)
                .filter(ToolCallLoop.ToolOutcome::success)
                .count();
        return new ToolCallLoop.LoopResult(
                "Done.", 1, outcomes.size(), List.of("talos.edit_file"), List.of(),
                0, 0, false, successes, List.of(),
                0, 0, 0, 0, outcomes);
    }
}
