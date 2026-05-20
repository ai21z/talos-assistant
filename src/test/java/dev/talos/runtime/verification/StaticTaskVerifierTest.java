package dev.talos.runtime.verification;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.tools.VerificationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;

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
    void exactBulletCountExpectationPassesWhenGeneratedTargetHasRequestedCount() throws Exception {
        Path notes = Files.createDirectories(workspace.resolve("notes"));
        Files.writeString(notes.resolve("generated-summary.md"), """
                - One
                - Two
                - Three
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Create notes/generated-summary.md with exactly three bullet points.",
                loopResult(List.of(successfulWrite("notes/generated-summary.md", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status());
        assertTrue(result.summary().contains("Bullet count verification passed"), result.summary());
        assertTrue(result.facts().stream()
                .anyMatch(f -> f.contains("notes/generated-summary.md: bullet count matched requested 3.")));
    }

    @Test
    void exactBulletCountExpectationFailsWhenGeneratedTargetHasWrongCount() throws Exception {
        Path notes = Files.createDirectories(workspace.resolve("notes"));
        Files.writeString(notes.resolve("generated-summary.md"), """
                - One
                - Two
                - Three
                - Four
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Create notes/generated-summary.md with exactly three bullet points.",
                loopResult(List.of(successfulWrite("notes/generated-summary.md", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.summary().contains("Bullet count verification failed"), result.summary());
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("notes/generated-summary.md: bullet count mismatch")));
    }

    @Test
    void exactBulletCountExpectationFailsWhenGeneratedTargetHasExtraProse() throws Exception {
        Path notes = Files.createDirectories(workspace.resolve("notes"));
        Files.writeString(notes.resolve("generated-summary.md"), """
                Summary:
                - One
                - Two
                - Three
                Done.
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Create notes/generated-summary.md with exactly three bullet points.",
                loopResult(List.of(successfulWrite("notes/generated-summary.md", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.summary().contains("Bullet count verification failed"), result.summary());
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("notes/generated-summary.md: bullet list contains non-bullet content")));
    }

    @Test
    void appendLineExpectationPassesWhenLineIsLastLogicalLine() throws Exception {
        Files.writeString(workspace.resolve("README.md"), """
                Intro
                Release gate note
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Append exactly this line to README.md: Release gate note",
                loopResult(List.of(successfulExactEdit(
                        "README.md",
                        "Intro\n",
                        "Intro\nRelease gate note\n",
                        VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status());
        assertTrue(result.summary().contains("Append line verification passed"), result.summary());
        assertTrue(result.facts().stream()
                .anyMatch(f -> f.contains("README.md: appended line matched requested EOF line.")));
    }

    @Test
    void appendLineExpectationFailsWhenWriteFileCannotProveAppendOnlyPreservation() throws Exception {
        Files.writeString(workspace.resolve("README.md"), """
                Intro
                Release gate note
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Append exactly this line to README.md: Release gate note",
                loopResult(List.of(successfulWrite("README.md", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.summary().contains("Append line verification failed"), result.summary());
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("README.md: talos.write_file cannot prove append-only preservation")));
    }

    @Test
    void appendLineExpectationPassesWhenFullWriteEvidencePreservesPriorContent() throws Exception {
        Files.writeString(workspace.resolve("README.md"), """
                Intro
                Release gate note
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Append exactly this line to README.md: Release gate note",
                loopResult(List.of(successfulFullWrite(
                        "README.md",
                        "Intro\n",
                        "Intro\nRelease gate note\n",
                        VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status());
        assertTrue(result.summary().contains("Append line verification passed"), result.summary());
        assertTrue(result.facts().stream()
                .anyMatch(f -> f.contains("README.md: full-write evidence preserved prior content before appended line.")));
    }

    @Test
    void appendLineExpectationFailsWhenFullWriteEvidenceRewritesPriorContent() throws Exception {
        Files.writeString(workspace.resolve("README.md"), """
                Different intro
                Release gate note
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Append exactly this line to README.md: Release gate note",
                loopResult(List.of(successfulFullWrite(
                        "README.md",
                        "Intro\n",
                        "Different intro\nRelease gate note\n",
                        VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.summary().contains("Append line verification failed"), result.summary());
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("README.md: full-file write did not preserve prior content before appended line")));
    }

    @Test
    void appendLineExpectationFailsWhenExactEditRewritesExistingContent() throws Exception {
        Files.writeString(workspace.resolve("README.md"), """
                Different intro
                Release gate note
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Append exactly this line to README.md: Release gate note",
                loopResult(List.of(successfulExactEdit(
                        "README.md",
                        "Intro\n",
                        "Different intro\nRelease gate note\n",
                        VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.summary().contains("Append line verification failed"), result.summary());
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("README.md: exact edit did not preserve prior content before appended line")));
    }

    @Test
    void appendLineExpectationFailsWhenLineMissing() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "Intro\n");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Append exactly this line to README.md: Release gate note",
                loopResult(List.of(successfulWrite("README.md", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.summary().contains("Append line verification failed"), result.summary());
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("README.md: appended line missing")));
    }

    @Test
    void appendLineExpectationFailsWhenLineDuplicated() throws Exception {
        Files.writeString(workspace.resolve("README.md"), """
                Intro
                Release gate note
                Release gate note
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Append exactly this line to README.md: Release gate note",
                loopResult(List.of(successfulWrite("README.md", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.summary().contains("Append line verification failed"), result.summary());
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("README.md: appended line count mismatch")));
    }

    @Test
    void appendLineExpectationFailsWhenLineIsNotLastLogicalLine() throws Exception {
        Files.writeString(workspace.resolve("README.md"), """
                Intro
                Release gate note
                After
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Append exactly this line to README.md: Release gate note",
                loopResult(List.of(successfulWrite("README.md", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.summary().contains("Append line verification failed"), result.summary());
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("README.md: appended line was not the final logical line")));
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
    void appendLineExpectationTraceEventIsRedacted() throws Exception {
        Files.writeString(workspace.resolve("README.md"), """
                Intro
                Release gate note
                """);
        LocalTurnTraceCapture.begin(
                "trc-test-append",
                "session-test",
                1,
                "2026-04-29T00:00:00Z",
                "workspace-hash",
                "auto",
                "ollama",
                "qwen2.5-coder:14b",
                "Append exactly this line to README.md: Release gate note");

        try {
            StaticTaskVerifier.verify(
                    workspace,
                    "Append exactly this line to README.md: Release gate note",
                    loopResult(List.of(successfulExactEdit(
                            "README.md",
                            "Intro\n",
                            "Intro\nRelease gate note\n",
                            VerificationStatus.PASS))),
                    0);
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            var event = trace.events().stream()
                    .filter(e -> e.type().equals("EXPECTATION_VERIFIED"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("APPEND_LINE", event.data().get("kind"));
            assertEquals("PASSED", event.data().get("status"));
            assertEquals("README.md", event.data().get("pathHint"));
            assertTrue(event.data().containsKey("expectedHash"));
            assertTrue(event.data().containsKey("observedHash"));
            assertFalse(event.data().containsValue("Release gate note"),
                    "default trace must not store raw appended-line content");
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void replacementExpectationTraceEventIsRedacted() throws Exception {
        Files.writeString(workspace.resolve("script.js"), "document.querySelector('#submit');\n");
        LocalTurnTraceCapture.begin(
                "trc-test-replacement",
                "session-test",
                1,
                "2026-04-29T00:00:00Z",
                "workspace-hash",
                "auto",
                "ollama",
                "qwen2.5-coder:14b",
                "Replace .missing-button with #submit in script.js.");

        try {
            StaticTaskVerifier.verify(
                    workspace,
                    "Replace .missing-button with #submit in script.js.",
                    loopResult(List.of(successfulWrite("script.js", VerificationStatus.PASS))),
                    0);
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            var event = trace.events().stream()
                    .filter(e -> e.type().equals("EXPECTATION_VERIFIED"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("TEXT_REPLACEMENT", event.data().get("kind"));
            assertEquals("PASSED", event.data().get("status"));
            assertEquals("script.js", event.data().get("pathHint"));
            assertTrue(event.data().containsKey("expectedHash"));
            assertTrue(event.data().containsKey("observedHash"));
            assertFalse(event.data().containsValue(".missing-button"),
                    "default trace must not store raw replacement old text");
            assertFalse(event.data().containsValue("#submit"),
                    "default trace must not store raw replacement new text");
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
                loopResult(List.of(successfulExactEdit(
                        "script.js",
                        ".missing-button",
                        ".cta-button",
                        VerificationStatus.PASS))),
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
                loopResult(List.of(successfulExactEdit(
                        "script.js",
                        ".missing-button",
                        ".cta-button",
                        VerificationStatus.PASS))),
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
    void staticWebSelectorReplacementFailsWhenFullWriteCorruptsReadbackBody() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                <head><link rel="stylesheet" href="styles.css"></head>
                <body>
                  <button class="cta-button">Run</button>
                  <p id="result">Waiting</p>
                  <script src="script.js"></script>
                </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), ".cta-button { color: red; }\n");
        String previous = """
                document.querySelector('.missing-button').addEventListener('click', () => {
                  document.querySelector('#result').textContent = 'Clicked';
                });
                """;
        String corrupted = """
                document.querySelector('.cta-button').addEventListener('click', () => {
                  document.querySelector('#result').textC;
                });
                """;
        Files.writeString(workspace.resolve("script.js"), corrupted);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Read script.js, then fix the selector bug by changing .missing-button to .cta-button. "
                        + "Do not edit scripts.js.",
                loopResult(List.of(successfulFullWrite(
                        "script.js",
                        previous,
                        corrupted,
                        VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status(), result.facts().toString());
        assertTrue(result.summary().contains("Replacement verification failed"), result.summary());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("script.js")
                                && p.contains("replacement preservation changed content beyond the requested text")),
                result.problems().toString());
    }

    @Test
    void sourceEvidenceFileIsNotRequiredMutationTargetForStaticWebBuild() throws Exception {
        Files.writeString(workspace.resolve("rough-brief.txt"), """
                Neon Harbor needs a synthwave landing page with a hero section,
                a tour call to action, and a mailing list signup.
                """);
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html lang="en">
                  <head>
                    <meta charset="utf-8">
                    <title>Neon Harbor</title>
                    <link rel="stylesheet" href="styles.css">
                  </head>
                  <body>
                    <main>
                      <h1>Neon Harbor</h1>
                      <p>Tour dates and mailing list signup.</p>
                      <button id="join-list">Join list</button>
                      <p id="status"></p>
                    </main>
                    <script src="scripts.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), """
                body { font-family: system-ui, sans-serif; background: #101018; color: white; }
                main { max-width: 42rem; margin: 3rem auto; }
                button { padding: 0.75rem 1rem; }
                """);
        Files.writeString(workspace.resolve("scripts.js"), """
                document.getElementById('join-list').addEventListener('click', () => {
                  document.getElementById('status').textContent = 'Signed up';
                });
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "make a real static landing page from rough-brief.txt. "
                        + "use index.html styles.css scripts.js. do not use script.js.",
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("styles.css", VerificationStatus.PASS),
                        successfulWrite("scripts.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status(), result.problems().toString());
        assertFalse(result.problems().stream()
                        .anyMatch(p -> p.contains("rough-brief.txt: expected target was not successfully mutated")),
                result.problems().toString());
    }

    @Test
    void sourceDerivedMultiSourceSummaryFailsWhenOneReadableSourceOmitted() throws Exception {
        Files.writeString(workspace.resolve("alpha.txt"), """
                Alpha source says orbital zinc inventory depends on cobalt ledger entries.
                """);
        Files.writeString(workspace.resolve("beta.txt"), """
                Beta source says amber kelp forecast depends on violet turbine output.
                """);
        Files.writeString(workspace.resolve("summary.md"), """
                - Orbital zinc inventory depends on cobalt ledger entries.
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                multiSourceSummaryContract(),
                loopResult(List.of(successfulWrite("summary.md", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.summary().contains("Source-derived artifact verification failed"), result.summary());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("beta.txt")
                                && p.contains("source-derived summary does not include distinctive evidence")),
                result.problems().toString());
        assertFalse(result.problems().stream().anyMatch(p -> p.contains("amber kelp")), result.problems().toString());
        assertFalse(result.problems().stream().anyMatch(p -> p.contains("violet turbine")), result.problems().toString());
    }

    @Test
    void sourceDerivedMultiSourceSummaryPassesWhenEachReadableSourceContributesDistinctiveFact() throws Exception {
        Files.writeString(workspace.resolve("alpha.txt"), """
                Alpha source says orbital zinc inventory depends on cobalt ledger entries.
                """);
        Files.writeString(workspace.resolve("beta.txt"), """
                Beta source says amber kelp forecast depends on violet turbine output.
                """);
        Files.writeString(workspace.resolve("summary.md"), """
                - Orbital zinc inventory depends on cobalt ledger entries.
                - Amber kelp forecast depends on violet turbine output.
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                multiSourceSummaryContract(),
                loopResult(List.of(successfulWrite("summary.md", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status(), result.problems().toString());
        assertTrue(result.summary().contains("Source-derived artifact verification passed"), result.summary());
        assertTrue(result.facts().stream()
                        .anyMatch(f -> f.contains("summary.md: source-derived artifact includes evidence from")
                                && f.contains("alpha.txt")
                                && f.contains("beta.txt")),
                result.facts().toString());
    }

    @Test
    void sourceDerivedVerifierDoesNotUseAggregateOverlapToMaskMissingSource() throws Exception {
        Files.writeString(workspace.resolve("alpha.txt"), """
                Alpha source records glacier matrix routing, cobalt ledger entries,
                orbital zinc inventory, and quartz relay capacity.
                """);
        Files.writeString(workspace.resolve("beta.txt"), """
                Beta source records amber kelp forecast and violet turbine output.
                """);
        Files.writeString(workspace.resolve("summary.md"), """
                - Glacier matrix routing, cobalt ledger entries, orbital zinc inventory,
                  and quartz relay capacity are all covered.
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                multiSourceSummaryContract(),
                loopResult(List.of(successfulWrite("summary.md", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("beta.txt")
                                && p.contains("source-derived summary does not include distinctive evidence")),
                result.problems().toString());
        assertFalse(result.problems().stream().anyMatch(p -> p.contains("amber kelp")), result.problems().toString());
        assertFalse(result.problems().stream().anyMatch(p -> p.contains("violet turbine")), result.problems().toString());
    }

    @Test
    void sourceDerivedOfficeDocumentSummaryPassesWhenEachExtractedSourceContributesDistinctiveFact() throws Exception {
        copyDocumentFixture("canonical-text.pdf", "report.pdf");
        copyDocumentFixture("canonical-report.docx", "report.docx");
        copyDocumentFixture("canonical-workbook.xlsx", "budget.xlsx");
        Files.writeString(workspace.resolve("office-summary.md"), """
                - The PDF evidence includes CANONICAL_PDF_TEXT_ALPHA.
                - The Word document evidence includes CANONICAL_DOCX_TEXT_BETA.
                - The workbook evidence includes CANONICAL_XLSX_TEXT_GAMMA.
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                officeDocumentSummaryContract(),
                loopResult(List.of(successfulWrite("office-summary.md", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status(), result.problems().toString());
        assertTrue(result.summary().contains("Source-derived artifact verification passed"), result.summary());
        assertTrue(result.facts().stream()
                        .anyMatch(f -> f.contains("office-summary.md: source-derived artifact includes evidence from")
                                && f.contains("report.pdf")
                                && f.contains("report.docx")
                                && f.contains("budget.xlsx")),
                result.facts().toString());
    }

    @Test
    void sourceDerivedOfficeDocumentSummaryFailsWhenOneExtractedSourceOmitted() throws Exception {
        copyDocumentFixture("canonical-text.pdf", "report.pdf");
        copyDocumentFixture("canonical-report.docx", "report.docx");
        copyDocumentFixture("canonical-workbook.xlsx", "budget.xlsx");
        Files.writeString(workspace.resolve("office-summary.md"), """
                - The PDF evidence includes CANONICAL_PDF_TEXT_ALPHA.
                - The Word document evidence includes CANONICAL_DOCX_TEXT_BETA.
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                officeDocumentSummaryContract(),
                loopResult(List.of(successfulWrite("office-summary.md", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.summary().contains("Source-derived artifact verification failed"), result.summary());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("budget.xlsx")
                                && p.contains("source-derived summary does not include distinctive evidence")),
                result.problems().toString());
        assertFalse(result.problems().stream().anyMatch(p -> p.contains("CANONICAL_XLSX_TEXT_GAMMA")),
                result.problems().toString());
    }

    @Test
    void styledWebpageRequestFailsWhenHtmlHasNoInlineOrLinkedStyle() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html lang="en">
                  <head>
                    <meta charset="utf-8">
                    <title>Neon Harbor</title>
                  </head>
                  <body>
                    <main>
                      <h1>Neon Harbor</h1>
                      <p>Tour dates and mailing list signup.</p>
                    </main>
                  </body>
                </html>
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Create a good modern synthwave style webpage in index.html.",
                loopResult(List.of(successfulWrite("index.html", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status(), result.facts().toString());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("Styled web task is missing CSS styling")),
                result.problems().toString());
    }

    @Test
    void styledWebpageRequestPassesWhenHtmlHasInlineStyle() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html lang="en">
                  <head>
                    <meta charset="utf-8">
                    <title>Neon Harbor</title>
                    <style>
                      body { background: #12002a; color: #f8f8ff; }
                      main { max-width: 48rem; margin: 4rem auto; }
                    </style>
                  </head>
                  <body>
                    <main>
                      <h1>Neon Harbor</h1>
                      <p>Tour dates and mailing list signup.</p>
                    </main>
                  </body>
                </html>
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Create a good modern synthwave style webpage in index.html.",
                loopResult(List.of(successfulWrite("index.html", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status(), result.problems().toString());
        assertTrue(result.facts().stream()
                        .anyMatch(f -> f.contains("Styled web checks passed")),
                result.facts().toString());
    }

    @Test
    void transcriptStyleFollowUpFailsWhenOnlyHtmlWithoutStylingWasMutated() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head><title>Synthwave Band</title></head>
                  <body><main><h1>Synthwave Band</h1></main></body>
                </html>
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "make the rest files please according to txt. I need a good modern synthwave style",
                loopResult(List.of(successfulWrite("index.html", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status(), result.facts().toString());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("Styled web task is missing CSS styling")),
                result.problems().toString());
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
    void exactEditReplacementEvidencePassesNonWebMutationVerification() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "status=new\n");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Update notes.md.",
                loopResult(List.of(successfulExactEdit(
                        "notes.md",
                        "status=old",
                        "status=new",
                        VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status());
        assertTrue(result.summary().contains("Exact edit replacement verification passed"), result.summary());
        assertTrue(result.facts().stream()
                .anyMatch(f -> f.contains("notes.md: exact edit replacement observed")),
                result.facts().toString());
    }

    @Test
    void exactEditReplacementEvidencePassesWhenAcceptedToolAliasUsed() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "status=new\n");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Update notes.md.",
                loopResult(List.of(successfulExactEditWithToolName(
                        "edit_file",
                        "notes.md",
                        "status=old",
                        "status=new",
                        VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status());
        assertTrue(result.summary().contains("Exact edit replacement verification passed"), result.summary());
    }

    @Test
    void exactEditReplacementEvidenceFailsWhenReplacementMissing() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "status=old\n");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Replace status=old with status=new in notes.md.",
                loopResult(List.of(successfulExactEdit(
                        "notes.md",
                        "status=old",
                        "status=new",
                        VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("replacement text was not observed")),
                result.problems().toString());
    }

    @Test
    void replacementExpectationPassesWhenOldRemovedAndNewPresentAfterWrite() throws Exception {
        Files.writeString(workspace.resolve("script.js"), "document.querySelector('#submit');\n");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Replace .missing-button with #submit in script.js.",
                loopResult(List.of(successfulWrite("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status());
        assertTrue(result.summary().contains("Replacement verification passed"), result.summary());
        assertTrue(result.facts().stream()
                .anyMatch(f -> f.contains("script.js: replacement text observed and old text absent.")));
    }

    @Test
    void replacementExpectationFailsWhenOldTextRemains() throws Exception {
        Files.writeString(workspace.resolve("script.js"), """
                document.querySelector('.missing-button');
                document.querySelector('#submit');
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Replace .missing-button with #submit in script.js.",
                loopResult(List.of(successfulWrite("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.summary().contains("Replacement verification failed"), result.summary());
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("script.js: replacement old text remained")));
    }

    @Test
    void replacementExpectationFailsWhenNewTextMissing() throws Exception {
        Files.writeString(workspace.resolve("script.js"), "document.querySelector('.other-button');\n");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Replace .missing-button with #submit in script.js.",
                loopResult(List.of(successfulWrite("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.summary().contains("Replacement verification failed"), result.summary());
        assertTrue(result.problems().stream()
                .anyMatch(p -> p.contains("script.js: replacement new text was not observed")));
    }

    @Test
    void replacementPreserveRestPassesWhenFullWriteEvidenceOnlyReplacesRequestedText() throws Exception {
        String previous = """
                <html>
                <head><title>Old Portal</title></head>
                <body><p>Keep this.</p></body>
                </html>
                """;
        String updated = previous.replace("Old Portal", "New Portal");
        Files.writeString(workspace.resolve("index.html"), updated);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Change the page title from Old Portal to New Portal in index.html and preserve the rest.",
                loopResult(List.of(successfulFullWrite(
                        "index.html",
                        previous,
                        updated,
                        VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status());
        assertTrue(result.summary().contains("Replacement verification passed"), result.summary());
        assertTrue(result.facts().stream()
                .anyMatch(f -> f.contains("index.html: replacement preservation matched prior content")));
    }

    @Test
    void replacementPreserveRestToleratesSingleTerminalNewlineDifferenceFromReadEvidence() throws Exception {
        String previous = """
                <html>
                <head><title>Old Portal</title></head>
                <body><p>Keep this.</p></body>
                </html>
                """;
        String updated = previous.replace("Old Portal", "New Portal");
        String updatedWithoutTerminalNewline = updated.substring(0, updated.length() - 1);
        Files.writeString(workspace.resolve("index.html"), updatedWithoutTerminalNewline);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Change the page title from Old Portal to New Portal in index.html and preserve the rest.",
                loopResult(List.of(successfulFullWrite(
                        "index.html",
                        previous,
                        updatedWithoutTerminalNewline,
                        VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status());
        assertTrue(result.summary().contains("Replacement verification passed"), result.summary());
        assertTrue(result.facts().stream()
                .anyMatch(f -> f.contains("index.html: replacement preservation matched prior content")));
    }

    @Test
    void replacementPreserveRestFailsWhenFullWriteEvidenceChangesOtherContent() throws Exception {
        String previous = """
                <html>
                <head><title>Old Portal</title></head>
                <body><p>Keep this.</p></body>
                </html>
                """;
        String updated = """
                <html>
                <head><title>New Portal</title></head>
                <body><p>Changed.</p></body>
                </html>
                """;
        Files.writeString(workspace.resolve("index.html"), updated);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Change the page title from Old Portal to New Portal in index.html and preserve the rest.",
                loopResult(List.of(successfulFullWrite(
                        "index.html",
                        previous,
                        updated,
                        VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.summary().contains("Replacement verification failed"), result.summary());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("index.html: replacement preservation changed content beyond the requested text")),
                result.problems().toString());
    }

    @Test
    void replacementPreserveRestFailsWhenWriteFileHasNoPriorContentEvidence() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <html>
                <head><title>New Portal</title></head>
                <body><p>Keep this.</p></body>
                </html>
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Change the page title from Old Portal to New Portal in index.html and preserve the rest.",
                loopResult(List.of(successfulWrite("index.html", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.summary().contains("Replacement verification failed"), result.summary());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("index.html: talos.write_file cannot prove preserve-rest replacement")),
                result.problems().toString());
    }

    @Test
    void replacementPreserveRestPassesWhenExactEditEvidenceOnlyReplacesRequestedText() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <head><title>New Portal</title></head>
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Change the page title from Old Portal to New Portal in index.html and preserve the rest.",
                loopResult(List.of(successfulExactEdit(
                        "index.html",
                        "<head><title>Old Portal</title></head>",
                        "<head><title>New Portal</title></head>",
                        VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status());
        assertTrue(result.summary().contains("Replacement verification passed"), result.summary());
        assertTrue(result.facts().stream()
                .anyMatch(f -> f.contains("index.html: exact edit evidence preserved content beyond requested replacement")));
    }

    @Test
    void replacementPreserveRestFailsWhenExactEditEvidenceChangesOtherContent() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <head data-extra="changed"><title>New Portal</title></head>
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Change the page title from Old Portal to New Portal in index.html and preserve the rest.",
                loopResult(List.of(successfulExactEdit(
                        "index.html",
                        "<head><title>Old Portal</title></head>",
                        "<head data-extra=\"changed\"><title>New Portal</title></head>",
                        VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.summary().contains("Replacement verification failed"), result.summary());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("index.html: replacement preservation exact edit changed content beyond the requested text")),
                result.problems().toString());
    }

    @Test
    void mixedExactEditAndReadbackOnlyMutationDoesNotOverclaimPassedVerification() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "status=new\n");
        Files.writeString(workspace.resolve("README.md"), "# Talos\n");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Replace status=old with status=new in notes.md and update README.md.",
                loopResult(List.of(
                        successfulExactEdit("notes.md", "status=old", "status=new", VerificationStatus.PASS),
                        successfulWrite("README.md", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.READBACK_ONLY, result.status());
        assertTrue(result.summary().contains("Target/readback checks passed"), result.summary());
        assertTrue(result.facts().stream()
                .anyMatch(f -> f.contains("notes.md: exact edit replacement observed")),
                result.facts().toString());
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

    @Test
    void forbiddenSimilarTargetMutationFailsEvenWhenExpectedTargetMutated() throws Exception {
        Files.writeString(workspace.resolve("script.js"), "document.querySelector('#submit');\n");
        Files.writeString(workspace.resolve("scripts.js"), "document.querySelector('#submit');\n");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Replace .missing-button with #submit in script.js. Do not edit scripts.js.",
                loopResult(List.of(
                        successfulWrite("script.js", VerificationStatus.PASS),
                        successfulWrite("scripts.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("scripts.js: forbidden mutation target was changed")),
                result.problems().toString());
        assertFalse(result.facts().stream()
                        .anyMatch(f -> f.contains("Expected mutation target(s) were updated")),
                result.facts().toString());
    }

    @Test
    void onlyTargetRequestFailsWhenAdditionalSiblingTargetMutated() throws Exception {
        Files.writeString(workspace.resolve("script.js"), "document.querySelector('#submit');\n");
        Files.writeString(workspace.resolve("scripts.js"), "document.querySelector('#submit');\n");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Only change script.js.",
                loopResult(List.of(
                        successfulWrite("script.js", VerificationStatus.PASS),
                        successfulWrite("scripts.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("scripts.js: non-requested mutation target was changed")),
                result.problems().toString());
        assertFalse(result.facts().stream()
                        .anyMatch(f -> f.contains("Expected mutation target(s) were updated")),
                result.facts().toString());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static TaskContract multiSourceSummaryContract() {
        return new TaskContract(
                TaskType.FILE_CREATE,
                true,
                true,
                true,
                Set.of("summary.md"),
                Set.of("alpha.txt", "beta.txt"),
                Set.of(),
                "Summarize alpha.txt and beta.txt into summary.md.",
                "test-multi-source-summary");
    }

    private static TaskContract officeDocumentSummaryContract() {
        return new TaskContract(
                TaskType.FILE_CREATE,
                true,
                true,
                true,
                Set.of("office-summary.md"),
                Set.of("report.pdf", "report.docx", "budget.xlsx"),
                Set.of(),
                "Summarize report.pdf, report.docx, and budget.xlsx into office-summary.md.",
                "test-office-document-summary");
    }

    private void copyDocumentFixture(String fixtureName, String targetName) throws Exception {
        Files.copy(documentFixture(fixtureName), workspace.resolve(targetName), StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path documentFixture(String name) throws URISyntaxException {
        URL url = StaticTaskVerifierTest.class.getResource("/document-fixtures/" + name);
        assertNotNull(url, "missing checked-in fixture: " + name);
        return Path.of(url.toURI());
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

    private static ToolCallLoop.ToolOutcome successfulExactEdit(
            String path,
            String oldString,
            String newString,
            VerificationStatus verificationStatus) {
        return successfulExactEditWithToolName(
                "talos.edit_file",
                path,
                oldString,
                newString,
                verificationStatus);
    }

    private static ToolCallLoop.ToolOutcome successfulExactEditWithToolName(
            String toolName,
            String path,
            String oldString,
            String newString,
            VerificationStatus verificationStatus) {
        return new ToolCallLoop.ToolOutcome(
                toolName, path, true, true, false,
                "edited " + path, "", verificationStatus, "",
                null,
                ToolCallLoop.MutationEvidence.exactEdit(oldString, newString));
    }

    private static ToolCallLoop.ToolOutcome successfulFullWrite(
            String path,
            String previousContent,
            String newContent,
            VerificationStatus verificationStatus) {
        return new ToolCallLoop.ToolOutcome(
                "talos.write_file", path, true, true, false,
                "wrote " + path, "", verificationStatus, "",
                null,
                ToolCallLoop.MutationEvidence.fullWriteReplacement(previousContent, newContent));
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
