package dev.talos.runtime.verification;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.StaticWebRequirements;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.task.WorkspaceTargetReconciler;
import dev.talos.runtime.toolcall.ToolMutationEvidence;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void scriptOnlySelectorFixDoesNotRequireCssWhenHtmlImportsEditedScript() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# Public fixture\n");
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <body><button class="cta-button">Go</button><script src="script.js"></script></body>
                </html>
                """);
        Files.writeString(workspace.resolve("script.js"), """
                document.querySelector('.cta-button').addEventListener('click', () => console.log('ok'));
                """);
        Files.writeString(workspace.resolve("scripts.js"), """
                document.querySelector('.missing-button');
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Fix script.js by changing .missing-button to .cta-button. Do not edit scripts.js.",
                loopResult(List.of(successfulExactEdit(
                        "script.js",
                        ".missing-button",
                        ".cta-button",
                        VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status(), result.problems().toString());
        assertTrue(result.problems().stream()
                .noneMatch(p -> p.contains("HTML, CSS, and JavaScript primary files were not all present")),
                result.problems().toString());
        assertTrue(result.facts().stream()
                .anyMatch(f -> f.contains("HTML/JavaScript selector coherence passed")), result.facts().toString());
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
    void scopedCssRewriteDoesNotFailOnUnrelatedMissingJavaScriptLink() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body><main class="hero"><button class="cta-button">Join</button></main></body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), """
                body { margin: 0; font-family: system-ui, sans-serif; }
                .hero { padding: 4rem; }
                .cta-button { border: 0; padding: 1rem; }
                """);
        Files.writeString(workspace.resolve("scripts.js"), "console.log('existing interaction');\n");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Rewrite styles.css so index.html still works. Do not edit index.html. Do not edit scripts.js.",
                loopResult(List.of(successfulWrite("styles.css", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status(), result.problems().toString());
        assertFalse(result.problems().stream()
                        .anyMatch(p -> p.contains("HTML does not link JavaScript file")),
                result.problems().toString());
        assertTrue(result.facts().stream()
                        .anyMatch(f -> f.contains("Contextual static-web finding outside this turn")
                                && f.contains("HTML does not link JavaScript file: `scripts.js`")),
                result.facts().toString());
    }

    @Test
    void scopedCssRewriteStillFailsWhenCssTargetIsEmpty() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body><main class="hero"><button class="cta-button">Join</button></main></body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), "");
        Files.writeString(workspace.resolve("scripts.js"), "console.log('existing interaction');\n");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Rewrite styles.css so index.html still works. Do not edit index.html. Do not edit scripts.js.",
                loopResult(List.of(successfulWrite("styles.css", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status(), result.facts().toString());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("styles.css") && p.contains("empty")),
                result.problems().toString());
    }

    @Test
    void scopedCssRewriteStillFailsWhenHtmlDoesNotLinkCssTarget() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head></head>
                  <body><main class="hero"><button class="cta-button">Join</button></main></body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), """
                body { margin: 0; font-family: system-ui, sans-serif; }
                .hero { padding: 4rem; }
                .cta-button { border: 0; padding: 1rem; }
                """);
        Files.writeString(workspace.resolve("scripts.js"), "console.log('existing interaction');\n");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Rewrite styles.css so index.html still works. Do not edit index.html. Do not edit scripts.js.",
                loopResult(List.of(successfulWrite("styles.css", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status(), result.facts().toString());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("HTML does not link CSS file: `styles.css`")),
                result.problems().toString());
    }

    @Test
    void scopedJavaScriptRewriteStillFailsWhenHtmlDoesNotLinkJavaScriptTarget() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body><main><button id="join-list">Join</button><p id="status"></p></main></body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), "body { font-family: system-ui, sans-serif; }\n");
        Files.writeString(workspace.resolve("scripts.js"), """
                document.getElementById('join-list').addEventListener('click', () => {
                  document.getElementById('status').textContent = 'Joined';
                });
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Rewrite scripts.js so index.html actually works with styles.css. "
                        + "Do not edit index.html. Do not edit styles.css.",
                loopResult(List.of(successfulWrite("scripts.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status(), result.facts().toString());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("HTML does not link JavaScript file: `scripts.js`")),
                result.problems().toString());
    }

    @Test
    void fullStaticWebCreateStillFailsWhenHtmlDoesNotLinkJavaScriptTarget() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body><main><button id="join-list">Join</button><p id="status"></p></main></body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), "body { font-family: system-ui, sans-serif; }\n");
        Files.writeString(workspace.resolve("scripts.js"), """
                document.getElementById('join-list').addEventListener('click', () => {
                  document.getElementById('status').textContent = 'Joined';
                });
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Create a modern static website with index.html, styles.css, and scripts.js.",
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("styles.css", VerificationStatus.PASS),
                        successfulWrite("scripts.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status(), result.facts().toString());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("HTML does not link JavaScript file: `scripts.js`")),
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
    void sourceDerivedMultiSourceSummaryChecksCoverageWithoutVerifyingSemantics() throws Exception {
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

        assertEquals(TaskVerificationStatus.READBACK_ONLY, result.status(), result.problems().toString());
        assertTrue(result.summary().contains("Source-derived coverage checks passed"), result.summary());
        assertTrue(result.summary().contains("summary semantics were not fully verified"), result.summary());
        assertTrue(result.facts().stream()
                        .anyMatch(f -> f.contains("summary.md: source-derived artifact includes evidence from")
                                && f.contains("alpha.txt")
                                && f.contains("beta.txt")),
                result.facts().toString());
    }

    @Test
    void staticWebProfileDispatchDoesNotRunSourceDerivedLaneForWebSurface() throws Exception {
        Files.writeString(workspace.resolve("brief.txt"), """
                Brief records aurora zephyr lattice, crimson harbor routing, and obsidian relay capacity.
                """);
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head>
                    <meta charset="utf-8">
                    <link rel="stylesheet" href="styles.css">
                  </head>
                  <body>
                    <main class="landing">
                      <h1>Working Site</h1>
                      <button id="join-list">Join list</button>
                      <p id="status">Ready</p>
                    </main>
                    <script src="scripts.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), """
                body { font-family: system-ui, sans-serif; }
                .landing { max-width: 42rem; margin: 3rem auto; }
                """);
        Files.writeString(workspace.resolve("scripts.js"), """
                document.getElementById('join-list').addEventListener('click', () => {
                  document.getElementById('status').textContent = 'Joined';
                });
                """);

        TaskContract contract = new TaskContract(
                TaskType.FILE_CREATE,
                true,
                true,
                true,
                Set.of("index.html", "styles.css", "scripts.js"),
                Set.of("brief.txt"),
                Set.of(),
                "Summarize brief.txt into index.html, styles.css, and scripts.js as a working website.",
                "test-web-source-derived-dispatch");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                contract,
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("styles.css", VerificationStatus.PASS),
                        successfulWrite("scripts.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status(), result.problems().toString());
        assertFalse(result.problems().stream()
                        .anyMatch(p -> p.contains("source-derived summary")),
                result.problems().toString());
        assertTrue(result.facts().stream()
                        .anyMatch(f -> f.contains("Static Web capability profile selected")),
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
    void sourceDerivedOfficeDocumentSummaryChecksExtractionCoverageWithoutVerifyingSemantics() throws Exception {
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

        assertEquals(TaskVerificationStatus.READBACK_ONLY, result.status(), result.problems().toString());
        assertTrue(result.summary().contains("Source-derived coverage checks passed"), result.summary());
        assertTrue(result.summary().contains("summary semantics were not fully verified"), result.summary());
        assertTrue(result.facts().stream()
                        .anyMatch(f -> f.contains("office-summary.md: source-derived artifact includes evidence from")
                                && f.contains("report.pdf")
                                && f.contains("report.docx")
                                && f.contains("budget.xlsx")),
                result.facts().toString());
    }

    @Test
    void sourceDerivedOfficeDocumentSummaryThreadsParserExtractionEvidenceIntoReport() throws Exception {
        copyDocumentFixture("canonical-text.pdf", "report.pdf");
        copyDocumentFixture("canonical-report.docx", "report.docx");
        copyDocumentFixture("canonical-workbook.xlsx", "budget.xlsx");
        Files.writeString(workspace.resolve("office-summary.md"), """
                - The PDF evidence includes CANONICAL_PDF_TEXT_ALPHA.
                - The Word document evidence includes CANONICAL_DOCX_TEXT_BETA.
                - The workbook evidence includes CANONICAL_XLSX_TEXT_GAMMA.
                """);

        TaskVerificationEvidence evidence = StaticTaskVerifier.verifyWithEvidence(
                workspace,
                officeDocumentSummaryContract(),
                loopResult(List.of(successfulWrite("office-summary.md", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.READBACK_ONLY, evidence.compatibilityResult().status());
        assertTrue(evidence.report().authoritativeProofKinds().contains(ProofKind.PARSER_EXTRACTION.name()),
                evidence.report().toString());
        assertTrue(evidence.report().verifierResults().stream()
                        .filter(v -> v.proofKind() == ProofKind.PARSER_EXTRACTION)
                        .filter(v -> v.authority() == EvidenceAuthority.AUTHORITATIVE)
                        .filter(v -> v.coverage() == EvidenceCoverage.SCOPED)
                        .count() >= 3,
                evidence.report().toString());
        assertFalse(evidence.report().requiredClaimsSatisfied(),
                "Parser extraction evidence must not verify summary semantics.");
    }

    @Test
    void sourceDerivedOfficeDocumentSummaryFailsWhenExactMarkersMaskUnsupportedProse() throws Exception {
        copyDocumentFixture("canonical-text.pdf", "board-brief.pdf");
        copyDocumentFixture("canonical-report.docx", "client-notes.docx");
        copyDocumentFixture("canonical-workbook.xlsx", "revenue.xlsx");
        Files.writeString(workspace.resolve("office-summary.md"), """
                # Office Summary

                ## Board Brief
                The board brief outlines the strategic objectives for the upcoming fiscal year,
                highlighting key initiatives in product development, market expansion, and cost optimization.
                **Evidence**: CANONICAL_PDF_TEXT_ALPHA PDF fixture for Talos extraction evidence

                ## Client Notes
                Client notes capture feedback from recent stakeholder meetings, focusing on service delivery
                improvements, pricing discussions, and contract renewal timelines.
                **Evidence**: CANONICAL_DOCX_TEXT_BETA

                ## Revenue Report
                The revenue spreadsheet provides monthly sales figures, regional performance, year-over-year growth,
                and North American market opportunities.
                **Evidence**: A1: CANONICAL_XLSX_TEXT_GAMMA
                """);

        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Create office-summary.md summarizing board-brief.pdf, client-notes.docx, and revenue.xlsx. "
                        + "Include one distinctive exact evidence phrase from each source so I can audit source coverage.");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                contract,
                loopResult(List.of(successfulWrite("office-summary.md", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status(), result.summary());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("unsupported distinctive terms not found in source evidence")),
                result.problems().toString());
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
    void sourceDerivedOfficeDocumentSummaryFailsForSummarizingPromptWithHallucinatedEvidence() throws Exception {
        copyDocumentFixture("canonical-text.pdf", "board-brief.pdf");
        copyDocumentFixture("canonical-report.docx", "client-notes.docx");
        copyDocumentFixture("canonical-workbook.xlsx", "revenue.xlsx");
        Files.writeString(workspace.resolve("office-summary.md"), """
                # Office Summary

                ## 1. Board Brief
                - Evidence Phrase: "Strategic Vision: Expand into new markets"

                ## 2. Client Notes
                - Evidence Phrase: "Client feedback indicates a strong preference for faster support response times"

                ## 3. Revenue Data
                - Evidence Phrase: "Total revenue for Q1 2026 reached $4.2 million"
                """);

        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Create office-summary.md summarizing board-brief.pdf, client-notes.docx, and revenue.xlsx. "
                        + "Include one distinctive exact evidence phrase from each source so I can audit source coverage.");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                contract,
                loopResult(List.of(successfulWrite("office-summary.md", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status(), result.summary());
        assertTrue(result.summary().contains("Source-derived artifact verification failed"), result.summary());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("board-brief.pdf")
                                && p.contains("source-derived summary does not include distinctive evidence")),
                result.problems().toString());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("client-notes.docx")
                                && p.contains("source-derived summary does not include distinctive evidence")),
                result.problems().toString());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("revenue.xlsx")
                                && p.contains("source-derived summary does not include distinctive evidence")),
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
    void interactiveStyledBandSiteDoesNotRequireCalculatorFormResultElements() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html lang="en">
                  <head>
                    <meta charset="utf-8">
                    <title>Neon Harbor</title>
                    <link rel="stylesheet" href="style.css">
                  </head>
                  <body>
                    <main class="hero">
                      <h1>Neon Harbor</h1>
                      <p class="tagline">Late-night synthwave shows and new releases.</p>
                      <button class="cta-button" type="button">Play teaser</button>
                    </main>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("style.css"), """
                body { background: #100020; color: #f8f8ff; }
                .hero { max-width: 56rem; margin: 0 auto; padding: 6rem 2rem; }
                .tagline { color: #38f6ff; }
                .cta-button { border: 1px solid #ff4fd8; }
                """);
        Files.writeString(workspace.resolve("script.js"), """
                document.querySelector('.cta-button').addEventListener('click', () => {
                  document.body.dataset.teaser = 'ready';
                });
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Create an interactive synthwave band website with exactly index.html, style.css, and script.js.",
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("style.css", VerificationStatus.PASS),
                        successfulWrite("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status(), result.problems().toString());
        assertFalse(result.problems().stream().anyMatch(p -> p.contains("Calculator/form task")),
                result.problems().toString());
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
    void textGuideAboutBuildingWebPageDoesNotTriggerStaticWebVerification() throws Exception {
        Files.writeString(workspace.resolve("synthwave_webpage_guide.txt"), """
                # Synthwave Band Web Page Guide

                - Plan the brand palette.
                - Create HTML, CSS, and JavaScript source files later.
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Okay can you create a txt file that talks about how to build a synthwave band's web page?",
                loopResult(List.of(successfulWrite("synthwave_webpage_guide.txt", VerificationStatus.PASS))),
                0);

        assertNotEquals(TaskVerificationStatus.FAILED, result.status(), result.problems().toString());
        assertFalse(result.problems().stream()
                        .anyMatch(p -> p.contains("web coherence could not be checked")),
                result.problems().toString());
    }

    @Test
    void styleAndJavascriptInteractionFollowUpVerifiesMissingScriptReference() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html lang="en">
                  <head>
                    <meta charset="utf-8">
                    <title>Synthwave Band</title>
                    <link rel="stylesheet" href="style.css">
                  </head>
                  <body>
                    <main class="hero">
                      <h1>Synthwave Band</h1>
                      <button class="cta-button" type="button">Play</button>
                    </main>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("style.css"), """
                body { background: #100020; color: #f8f8ff; }
                .hero { padding: 6rem 2rem; }
                .cta-button { border: 1px solid #ff4fd8; }
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "But make sure there is a real modern synthwave style and JavaScript interaction. Fix the files if needed.",
                loopResult(List.of(successfulWrite("style.css", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status(), result.facts().toString());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("HTML references missing JavaScript file: `script.js`")),
                result.problems().toString());
    }

    @Test
    void staticWebVerificationFailsUnprocessedTailwindDirectivesWithoutRuntimeOrBuild() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head><link rel="stylesheet" href="style.css"></head>
                  <body><main class="min-h-screen bg-slate-950 text-pink-300">Retrocats</main>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("style.css"), """
                @tailwind base;
                @tailwind components;
                @tailwind utilities;
                """);
        Files.writeString(workspace.resolve("script.js"), "console.log('ready');\n");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Rewrite the existing site to look better with Tailwind styling.",
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("style.css", VerificationStatus.PASS),
                        successfulWrite("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status(), result.facts().toString());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("Tailwind") && p.contains("unprocessed")),
                result.problems().toString());
    }

    @Test
    void staticWebVerificationFailsTailwindApplyDirectiveWithoutRuntimeOrBuild() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head><link rel="stylesheet" href="style.css"></head>
                  <body>
                    <main><h1>Retrocats</h1><button type="button">Play</button></main>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("style.css"), """
                body { margin: 0; }
                button {
                  @apply focus:outline-none focus:ring-2 focus:ring-pink-300;
                }
                """);
        Files.writeString(workspace.resolve("script.js"), "console.log('ready');\n");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Rewrite the existing Retrocats website with Tailwind styling.",
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("style.css", VerificationStatus.PASS),
                        successfulWrite("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status(), result.facts().toString());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("@apply") && p.contains("Tailwind") && p.contains("unprocessed")),
                result.problems().toString());
    }

    @Test
    void staticWebVerificationAllowsTailwindCdnRuntime() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head>
                    <script src="https://cdn.tailwindcss.com"></script>
                    <link rel="stylesheet" href="style.css">
                  </head>
                  <body><main class="min-h-screen bg-slate-950 text-pink-300">Retrocats</main>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("style.css"), "body { margin: 0; }\n");
        Files.writeString(workspace.resolve("script.js"), "console.log('ready');\n");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Rewrite the existing site to look better with Tailwind styling.",
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("style.css", VerificationStatus.PASS),
                        successfulWrite("script.js", VerificationStatus.PASS))),
                0);

        assertFalse(result.problems().stream().anyMatch(p -> p.contains("Tailwind")),
                result.problems().toString());
    }

    @Test
    void remoteTailwindCssHrefIsNotTreatedAsMissingLocalStylesheet() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head>
                    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/tailwindcss@2.2.19/dist/tailwind.min.css">
                    <link rel="stylesheet" href="style.css">
                  </head>
                  <body><main class="min-h-screen bg-slate-950 text-pink-300">Retrocats</main>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("style.css"), "body { margin: 0; }\n");
        Files.writeString(workspace.resolve("script.js"), "console.log('ready');\n");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Create a complete Retrocats static website. Do not create local tailwind.min.css.",
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("style.css", VerificationStatus.PASS),
                        successfulWrite("script.js", VerificationStatus.PASS))),
                0);

        assertFalse(result.problems().stream()
                        .anyMatch(problem -> problem.contains("HTML references missing CSS file")
                                && problem.contains("tailwind.min.css")),
                result.problems().toString());
        assertTrue(result.problems().stream()
                        .anyMatch(problem -> problem.contains("Tailwind utility classes")),
                result.problems().toString());
        assertTrue(result.problems().stream()
                        .anyMatch(problem -> problem.contains("remote Tailwind stylesheet")
                                && problem.contains("not accepted Tailwind browser runtime/build evidence")),
                result.problems().toString());
        assertFalse(result.problems().stream()
                        .anyMatch(problem -> problem.contains("no Tailwind CDN")),
                result.problems().toString());
        assertTrue(result.facts().stream()
                        .anyMatch(limitation -> limitation.contains("cdn.jsdelivr.net")
                                && limitation.contains("tailwind.min.css")),
                result.facts().toString());
    }

    @Test
    void remoteBootstrapCssHrefIsNotTreatedAsMissingLocalStylesheet() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head>
                    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
                    <link rel="stylesheet" href="style.css">
                  </head>
                  <body><main class="container py-5">Retrocats</main>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("style.css"), "body { margin: 0; }\n");
        Files.writeString(workspace.resolve("script.js"), "console.log('ready');\n");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Create a complete Retrocats static website with Bootstrap CDN only. No local framework artifacts.",
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("style.css", VerificationStatus.PASS),
                        successfulWrite("script.js", VerificationStatus.PASS))),
                0);

        assertFalse(result.problems().stream()
                        .anyMatch(problem -> problem.contains("HTML references missing CSS file")
                                && problem.contains("bootstrap.min.css")),
                result.problems().toString());
        assertTrue(result.facts().stream()
                        .anyMatch(fact -> fact.contains("cdn.jsdelivr.net")
                                && fact.contains("bootstrap.min.css")),
                result.facts().toString());
    }

    @Test
    void staticWebVerificationAllowsGeneratedCssForUtilityClasses() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head><link rel="stylesheet" href="style.css"></head>
                  <body><main class="min-h-screen bg-slate-950 text-pink-300">Retrocats</main>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("style.css"), """
                .min-h-screen { min-height: 100vh; }
                .bg-slate-950 { background-color: #020617; }
                .text-pink-300 { color: #f9a8d4; }
                """);
        Files.writeString(workspace.resolve("script.js"), "console.log('ready');\n");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Rewrite the existing site to look better with Tailwind styling.",
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("style.css", VerificationStatus.PASS),
                        successfulWrite("script.js", VerificationStatus.PASS))),
                0);

        assertFalse(result.problems().stream().anyMatch(p -> p.contains("Tailwind")),
                result.problems().toString());
    }

    @Test
    void staticWebVerificationFailsOrphanTailwindDirectivesFile() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head><link rel="stylesheet" href="style.css"></head>
                  <body><main class="hero">Retrocats</main><script src="script.js"></script></body>
                </html>
                """);
        Files.writeString(workspace.resolve("style.css"), ".hero { color: #ff4fd8; }\n");
        Files.writeString(workspace.resolve("styles.css"), """
                @tailwind base;
                @tailwind components;
                @tailwind utilities;
                """);
        Files.writeString(workspace.resolve("script.js"), "console.log('ready');\n");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Make the changes in Tailwind by updating styles.css.",
                loopResult(List.of(successfulWrite("styles.css", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status(), result.facts().toString());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("styles.css") && p.contains("not linked")),
                result.problems().toString());
    }

    @Test
    void staticWebVerificationFailsOrphanLocalTailwindPlaceholderFile() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head>
                    <script src="https://cdn.tailwindcss.com"></script>
                    <link rel="stylesheet" href="style.css">
                  </head>
                  <body><main class="min-h-screen bg-slate-950 text-pink-300">Retrocats</main>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("style.css"), "body { margin: 0; }\n");
        Files.writeString(workspace.resolve("tailwind.css"), "/* Tailwind placeholder file */\n");
        Files.writeString(workspace.resolve("script.js"), "console.log('ready');\n");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Create the Retrocats site with valid Tailwind CDN only. No local Tailwind artifacts.",
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("style.css", VerificationStatus.PASS),
                        successfulWrite("tailwind.css", VerificationStatus.PASS),
                        successfulWrite("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status(), result.facts().toString());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("tailwind.css") && p.contains("local Tailwind artifact")),
                result.problems().toString());
    }

    @Test
    void staticWebVerificationFailsLocalBootstrapPlaceholderFile() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head>
                    <link rel="stylesheet" href="bootstrap.css">
                    <link rel="stylesheet" href="style.css">
                  </head>
                  <body><main>Retrocats</main><script src="script.js"></script></body>
                </html>
                """);
        Files.writeString(workspace.resolve("bootstrap.css"), "/* Bootstrap placeholder file */\n");
        Files.writeString(workspace.resolve("style.css"), "body { margin: 0; }\n");
        Files.writeString(workspace.resolve("script.js"), "console.log('ready');\n");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Create the Retrocats site with Bootstrap CDN only. No local framework artifacts.",
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("bootstrap.css", VerificationStatus.PASS),
                        successfulWrite("style.css", VerificationStatus.PASS),
                        successfulWrite("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status(), result.facts().toString());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("bootstrap.css") && p.contains("local Bootstrap artifact")),
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
    void requestedButtonStatusInteractionNoOpDoesNotPassStaticVerification() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body>
                    <button id="teaser-button">Show teaser</button>
                    <p id="teaser-status">Waiting.</p>
                    <script src="scripts.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), "button { font: inherit; }\n");
        Files.writeString(workspace.resolve("scripts.js"), """
                document.getElementById('teaser-button').addEventListener('click', function() {
                  document.getElementById('teaser-status').textC;
                });
                """);

        TaskVerificationEvidence evidence = StaticTaskVerifier.verifyWithEvidence(
                workspace,
                TaskContractResolver.fromUserRequest(
                        "Update scripts.js so #teaser-button updates #teaser-status when clicked."),
                loopResult(List.of(successfulWrite("scripts.js", VerificationStatus.PASS))),
                0);
        TaskVerificationResult result = evidence.compatibilityResult();

        assertEquals(TaskVerificationStatus.FAILED, result.status(), result.summary());
        assertTrue(evidence.report().authoritativeProofKinds().stream()
                .noneMatch(ProofKind.BROWSER_BEHAVIOR.name()::equals));
        assertTrue(evidence.report().problems().stream()
                        .anyMatch(problem -> problem.contains("did not change")),
                evidence.report().problems().toString());
    }

    @Test
    void requestedButtonStatusInteractionCarriesBrowserBehaviorProofWhenRuntimePasses() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body>
                    <button id="teaser-button">Show teaser</button>
                    <p id="teaser-status">Waiting.</p>
                    <script src="scripts.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), "button { font: inherit; }\n");
        Files.writeString(workspace.resolve("scripts.js"), """
                const trigger = document.getElementById('teaser-button');
                const status = document.getElementById('teaser-status');
                trigger.addEventListener('click', function() {
                  status.textContent = 'Teaser ready';
                });
                """);

        TaskVerificationEvidence evidence = StaticTaskVerifier.verifyWithEvidence(
                workspace,
                TaskContractResolver.fromUserRequest(
                        "Update scripts.js so #teaser-button updates #teaser-status when clicked."),
                loopResult(List.of(successfulWrite("scripts.js", VerificationStatus.PASS))),
                0);
        TaskVerificationResult result = evidence.compatibilityResult();

        assertEquals(TaskVerificationStatus.PASSED, result.status(), result.summary());
        assertTrue(evidence.report().requiredClaimsSatisfied(), evidence.report().toString());
        assertTrue(evidence.report().authoritativeProofKinds().contains(ProofKind.BROWSER_BEHAVIOR.name()),
                evidence.report().authoritativeProofKinds().toString());
        assertFalse(evidence.report().limitations().stream()
                        .anyMatch(limit -> limit.contains("browser/runtime behavior was not executed")),
                evidence.report().limitations().toString());
    }

    @Test
    void naturalLanguageButtonIdInteractionCarriesBrowserBehaviorProofWhenRuntimePasses() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body>
                    <button id="teaser-button">Show teaser</button>
                    <p id="teaser-status">Waiting.</p>
                    <script src="scripts.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), "button { font: inherit; }\n");
        Files.writeString(workspace.resolve("scripts.js"), """
                document.getElementById('teaser-button').addEventListener('click', function() {
                  document.getElementById('teaser-status').textContent = 'Teaser ready';
                });
                """);

        TaskVerificationEvidence evidence = StaticTaskVerifier.verifyWithEvidence(
                workspace,
                TaskContractResolver.fromUserRequest(
                        "Create a synthwave website with a button with id teaser-button "
                                + "that updates visible text in #teaser-status when clicked."),
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("styles.css", VerificationStatus.PASS),
                        successfulWrite("scripts.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, evidence.compatibilityResult().status(),
                evidence.compatibilityResult().summary());
        assertTrue(evidence.compatibilityResult().summary().contains("Required interaction verification passed"),
                evidence.compatibilityResult().summary());
        assertEquals(1, evidence.report().requiredClaimCount(), evidence.report().toString());
        assertTrue(evidence.report().requiredClaimsSatisfied(), evidence.report().toString());
        assertTrue(evidence.report().authoritativeProofKinds().contains(ProofKind.BROWSER_BEHAVIOR.name()),
                evidence.report().authoritativeProofKinds().toString());
    }

    @Test
    void browserVerifiedInteractionIsNotFailedByCssUtilityOrStateSelectors() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body>
                    <button id="teaser-button">Show teaser</button>
                    <p id="teaser-status"></p>
                    <script src="scripts.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), """
                #teaser-status.visible { opacity: 1; }
                .hidden { display: none; }
                """);
        Files.writeString(workspace.resolve("scripts.js"), """
                document.getElementById('teaser-button').addEventListener('click', function() {
                  document.getElementById('teaser-status').textContent = 'Teaser ready';
                });
                """);

        TaskVerificationEvidence evidence = StaticTaskVerifier.verifyWithEvidence(
                workspace,
                TaskContractResolver.fromUserRequest(
                        "Create a synthwave website with a button with id teaser-button "
                                + "that updates visible text in #teaser-status when clicked."),
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("styles.css", VerificationStatus.PASS),
                        successfulWrite("scripts.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, evidence.compatibilityResult().status(),
                evidence.compatibilityResult().summary());
        assertTrue(evidence.report().requiredClaimsSatisfied(), evidence.report().toString());
        assertTrue(evidence.report().authoritativeProofKinds().contains(ProofKind.BROWSER_BEHAVIOR.name()),
                evidence.report().authoritativeProofKinds().toString());
    }

    @Test
    void remoteStaticWebAssetReferenceSurfacesLimitationWithoutMaskingInteractionProof() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body>
                    <button id="teaser-button">Show teaser</button>
                    <p id="teaser-status">Waiting.</p>
                    <script src="scripts.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), """
                html {
                  background-image: url('https://images.example.test/synthwave-stage.jpg');
                }
                """);
        Files.writeString(workspace.resolve("scripts.js"), """
                document.getElementById('teaser-button').addEventListener('click', function() {
                  document.getElementById('teaser-status').textContent = 'Teaser ready';
                });
                """);

        TaskVerificationEvidence evidence = StaticTaskVerifier.verifyWithEvidence(
                workspace,
                TaskContractResolver.fromUserRequest(
                        "Create a synthwave website with a button with id teaser-button "
                                + "that updates visible text in #teaser-status when clicked."),
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("styles.css", VerificationStatus.PASS),
                        successfulWrite("scripts.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, evidence.compatibilityResult().status(),
                evidence.compatibilityResult().summary());
        assertTrue(evidence.report().requiredClaimsSatisfied(), evidence.report().toString());
        assertTrue(evidence.report().authoritativeProofKinds().contains(ProofKind.BROWSER_BEHAVIOR.name()),
                evidence.report().authoritativeProofKinds().toString());
        assertTrue(evidence.report().limitations().stream()
                        .anyMatch(limit -> limit.contains("Remote static-web asset references were not fetched")
                                && limit.contains("styles.css")
                                && limit.contains("https://images.example.test")),
                evidence.report().limitations().toString());
    }

    @Test
    void failedFirstViewportRenderBlocksStaticWebCompletion() throws Exception {
        writeCompleteStaticWebsite();

        TaskVerificationEvidence evidence = StaticTaskVerifier.verifyWithEvidence(
                workspace,
                TaskContractResolver.fromUserRequest(
                        "Create a complete modern dark synthwave static website for a band called Retrocats."),
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("styles.css", VerificationStatus.PASS),
                        successfulWrite("scripts.js", VerificationStatus.PASS))),
                0,
                (root, input) -> StaticWebRenderVerifier.RenderRunResult.failed(
                        1366,
                        768,
                        List.of("First viewport rendered as mostly blank black pixels."),
                        List.of()));

        assertEquals(TaskVerificationStatus.FAILED, evidence.compatibilityResult().status(),
                evidence.compatibilityResult().summary());
        assertTrue(evidence.compatibilityResult().problems().stream()
                        .anyMatch(problem -> problem.contains("mostly blank")),
                evidence.compatibilityResult().problems().toString());
        assertFalse(evidence.report().authoritativeProofKinds().contains(ProofKind.RENDER_COMPARISON.name()),
                evidence.report().authoritativeProofKinds().toString());
    }

    @Test
    void unavailableFirstViewportRenderSurfacesLimitationWithoutVisualProof() throws Exception {
        writeCompleteStaticWebsite();

        TaskVerificationEvidence evidence = StaticTaskVerifier.verifyWithEvidence(
                workspace,
                TaskContractResolver.fromUserRequest(
                        "Create a complete modern dark synthwave static website for a band called Retrocats."),
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("styles.css", VerificationStatus.PASS),
                        successfulWrite("scripts.js", VerificationStatus.PASS))),
                0);

        assertFalse(evidence.report().authoritativeProofKinds().contains(ProofKind.RENDER_COMPARISON.name()),
                evidence.report().authoritativeProofKinds().toString());
        assertTrue(evidence.report().limitations().stream()
                        .anyMatch(limit -> limit.contains("First-viewport render verification was unavailable")),
                evidence.report().limitations().toString());
    }

    @Test
    void pureInteractionVerificationDoesNotGainRenderProof() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body>
                    <button id="teaser-button">Show teaser</button>
                    <p id="teaser-status">Waiting.</p>
                    <script src="scripts.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), "button { font: inherit; }\n");
        Files.writeString(workspace.resolve("scripts.js"), """
                document.getElementById('teaser-button').addEventListener('click', function() {
                  document.getElementById('teaser-status').textContent = 'Teaser ready';
                });
                """);

        TaskVerificationEvidence evidence = StaticTaskVerifier.verifyWithEvidence(
                workspace,
                TaskContractResolver.fromUserRequest(
                        "Update scripts.js so #teaser-button updates #teaser-status when clicked."),
                loopResult(List.of(successfulWrite("scripts.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, evidence.compatibilityResult().status(),
                evidence.compatibilityResult().summary());
        assertTrue(evidence.report().authoritativeProofKinds().contains(ProofKind.BROWSER_BEHAVIOR.name()),
                evidence.report().authoritativeProofKinds().toString());
        assertFalse(evidence.report().authoritativeProofKinds().contains(ProofKind.RENDER_COMPARISON.name()),
                evidence.report().authoritativeProofKinds().toString());
    }

    @Test
    void explicitOfflineStaticWebRequestFailsWhenRemoteAssetReferenceRemains() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body>
                    <button id="teaser-button">Show teaser</button>
                    <p id="teaser-status">Waiting.</p>
                    <script src="scripts.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), """
                body {
                  background: #050010 url("https://cdn.example.test/neon.png") center / cover no-repeat;
                }
                """);
        Files.writeString(workspace.resolve("scripts.js"), """
                document.getElementById('teaser-button').addEventListener('click', function() {
                  document.getElementById('teaser-status').textContent = 'Teaser ready';
                });
                """);

        TaskVerificationEvidence evidence = StaticTaskVerifier.verifyWithEvidence(
                workspace,
                TaskContractResolver.fromUserRequest(
                        "Create an offline self-contained synthwave website with a button with id teaser-button "
                                + "that updates visible text in #teaser-status when clicked. Do not use remote assets."),
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("styles.css", VerificationStatus.PASS),
                        successfulWrite("scripts.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, evidence.compatibilityResult().status(),
                evidence.compatibilityResult().summary());
        assertTrue(evidence.report().requiredClaimsSatisfied(), evidence.report().toString());
        assertTrue(evidence.report().authoritativeProofKinds().contains(ProofKind.BROWSER_BEHAVIOR.name()),
                evidence.report().authoritativeProofKinds().toString());
        assertTrue(evidence.compatibilityResult().problems().stream()
                        .anyMatch(problem -> problem.contains("Explicit offline/static-web request contains remote asset references")
                                && problem.contains("https://cdn.example.test")),
                evidence.compatibilityResult().problems().toString());
    }

    @Test
    void vagueStaticVerificationRepairWithoutClaimContextDoesNotPassStaticCoherenceOnly() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body>
                    <h1>Welcome to Neon Voltage</h1>
                    <script src="scripts.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), "body { color: #fff; }\n");
        Files.writeString(workspace.resolve("scripts.js"), "console.log('Neon Voltage site is verified!');\n");

        TaskVerificationEvidence evidence = StaticTaskVerifier.verifyWithEvidence(
                workspace,
                TaskContractResolver.fromUserRequest(
                        "Fix the remaining static verification problems and make the existing Neon Voltage site verified. "
                                + "Keep exactly index.html, styles.css, and scripts.js; do not create any other files."),
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("styles.css", VerificationStatus.PASS),
                        successfulWrite("scripts.js", VerificationStatus.PASS))),
                0);

        assertNotEquals(TaskVerificationStatus.PASSED, evidence.compatibilityResult().status(),
                evidence.compatibilityResult().summary());
        assertEquals(1, evidence.report().requiredClaimCount(), evidence.report().toString());
        assertEquals(1, evidence.report().unsatisfiedRequiredClaimCount(), evidence.report().toString());
        assertTrue(evidence.report().limitations().stream()
                        .anyMatch(limit -> limit.contains("required static-web repair claim context was unavailable")),
                evidence.report().limitations().toString());
    }

    @Test
    void structuralStaticVerificationRepairWithoutInteractionClaimCanPassStaticCoherence() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <title>BMI Calculator</title>
                  <link rel="stylesheet" href="styles.css">
                </head>
                <body>
                  <main class="calculator">
                    <h1>BMI Calculator</h1>
                    <form id="bmiForm">
                      <label for="weight">Weight</label>
                      <input id="weight" type="number">
                      <label for="height">Height</label>
                      <input id="height" type="number">
                      <button type="submit">Calculate BMI</button>
                    </form>
                    <p id="result"></p>
                  </main>
                  <script src="scripts.js"></script>
                </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), ".calculator { max-width: 460px; }\n");
        Files.writeString(workspace.resolve("scripts.js"), """
                document.getElementById('bmiForm').addEventListener('submit', (event) => {
                  event.preventDefault();
                  document.getElementById('result').textContent = 'Your BMI is 22.0';
                });
                """);

        TaskVerificationEvidence evidence = StaticTaskVerifier.verifyWithEvidence(
                workspace,
                TaskContractResolver.fromUserRequest(
                        "Fix the remaining static verification problems for this 3-file webpage now. If edit_file is fragile, "
                                + "overwrite index.html, styles.css, and scripts.js with complete corrected versions."),
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("styles.css", VerificationStatus.PASS),
                        successfulWrite("scripts.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, evidence.compatibilityResult().status(),
                evidence.compatibilityResult().summary());
        assertEquals(0, evidence.report().requiredClaimCount(), evidence.report().toString());
    }

    @Test
    void invalidLinkedJavaScriptForNaturalLanguageInteractionDoesNotPassStaticWebVerification() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body>
                    <button id="teaser-button">Show teaser</button>
                    <p id="teaser-status">Waiting.</p>
                    <script src="scripts.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), "button { font: inherit; }\n");
        Files.writeString(workspace.resolve("scripts.js"), """
                document.getElementById('teaser-button').addEventListener('click', function() {
                  document.getElementById('teaser-status').textContent = 'Teaser ready';
                """);

        TaskVerificationEvidence evidence = StaticTaskVerifier.verifyWithEvidence(
                workspace,
                TaskContractResolver.fromUserRequest(
                        "Create a synthwave website with a button with id teaser-button "
                                + "that updates visible text in #teaser-status when clicked."),
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("styles.css", VerificationStatus.PASS),
                        successfulWrite("scripts.js", VerificationStatus.PASS))),
                0);

        assertNotEquals(TaskVerificationStatus.PASSED, evidence.compatibilityResult().status(),
                evidence.compatibilityResult().summary());
        assertTrue(evidence.compatibilityResult().problems().stream()
                        .anyMatch(problem -> problem.contains("JavaScript syntax")),
                evidence.compatibilityResult().problems().toString());
    }

    @Test
    void requestedButtonStatusInteractionCarriesBrowserBehaviorProofWithoutCssFile() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <body>
                    <button id="teaser-button">Show teaser</button>
                    <p id="teaser-status">Waiting.</p>
                    <script src="scripts.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("scripts.js"), """
                const trigger = document.getElementById('teaser-button');
                const status = document.getElementById('teaser-status');
                trigger.addEventListener('click', function() {
                  status.textContent = 'Teaser ready';
                });
                """);

        TaskVerificationEvidence evidence = StaticTaskVerifier.verifyWithEvidence(
                workspace,
                TaskContractResolver.fromUserRequest(
                        "Update scripts.js so #teaser-button updates #teaser-status when clicked."),
                loopResult(List.of(successfulWrite("scripts.js", VerificationStatus.PASS))),
                0);
        TaskVerificationResult result = evidence.compatibilityResult();

        assertEquals(TaskVerificationStatus.PASSED, result.status(), result.summary());
        assertTrue(evidence.report().requiredClaimsSatisfied(), evidence.report().toString());
        assertTrue(evidence.report().authoritativeProofKinds().contains(ProofKind.BROWSER_BEHAVIOR.name()),
                evidence.report().authoritativeProofKinds().toString());
        assertFalse(evidence.report().limitations().stream()
                        .anyMatch(limit -> limit.contains("browser/runtime behavior was not executed")),
                evidence.report().limitations().toString());
    }

    @Test
    void requestedButtonStatusInteractionNoOpWithoutCssFileFailsBrowserBehaviorProof() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <body>
                    <button id="teaser-button">Show teaser</button>
                    <p id="teaser-status">Waiting.</p>
                    <script src="scripts.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("scripts.js"), """
                document.getElementById('teaser-button').addEventListener('click', function() {
                  document.getElementById('teaser-status').textC;
                });
                """);

        TaskVerificationEvidence evidence = StaticTaskVerifier.verifyWithEvidence(
                workspace,
                TaskContractResolver.fromUserRequest(
                        "Update scripts.js so #teaser-button updates #teaser-status when clicked."),
                loopResult(List.of(successfulWrite("scripts.js", VerificationStatus.PASS))),
                0);
        TaskVerificationResult result = evidence.compatibilityResult();

        assertEquals(TaskVerificationStatus.FAILED, result.status(), result.summary());
        assertTrue(evidence.report().hasRequiredFailure(), evidence.report().toString());
        assertTrue(evidence.report().problems().stream()
                        .anyMatch(problem -> problem.contains("did not change")),
                evidence.report().problems().toString());
        assertFalse(result.problems().stream()
                        .anyMatch(problem -> problem.contains("small HTML/CSS/JS surface")),
                result.problems().toString());
    }

    @Test
    void requestedButtonStatusInteractionPassesWithTextContentAssignmentToBoundTarget() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body>
                    <button id="teaser-button">Show teaser</button>
                    <p id="teaser-status">Waiting.</p>
                    <script src="scripts.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), "button { font: inherit; }\n");
        Files.writeString(workspace.resolve("scripts.js"), """
                const trigger = document.getElementById('teaser-button');
                const status = document.getElementById('teaser-status');
                trigger.addEventListener('click', function() {
                  status.textContent = 'Teaser ready';
                });
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Update scripts.js so #teaser-button updates #teaser-status when clicked.",
                loopResult(List.of(successfulWrite("scripts.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status(), result.summary());
        assertTrue(result.facts().stream().anyMatch(f -> f.contains("#teaser-button")
                && f.contains("#teaser-status")), result.facts().toString());
    }

    @Test
    void requestedButtonStatusInteractionRejectsAssignmentToWrongOutputTarget() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body>
                    <button id="teaser-button">Show teaser</button>
                    <p id="teaser-status">Waiting.</p>
                    <p id="other-status">Other.</p>
                    <script src="scripts.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), "button { font: inherit; }\n");
        Files.writeString(workspace.resolve("scripts.js"), """
                document.getElementById('teaser-button').addEventListener('click', function() {
                  document.getElementById('other-status').textContent = 'Wrong target';
                });
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Update scripts.js so #teaser-button updates #teaser-status when clicked.",
                loopResult(List.of(successfulWrite("scripts.js", VerificationStatus.PASS))),
                0);

        assertNotEquals(TaskVerificationStatus.PASSED, result.status(), result.summary());
        assertTrue(result.problems().stream().anyMatch(p -> p.contains("#teaser-status")),
                result.problems().toString());
    }

    @Test
    void requestedButtonStatusInteractionPassesWithInnerTextAssignmentToBoundTarget() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body>
                    <button id="teaser-button">Show teaser</button>
                    <p id="teaser-status">Waiting.</p>
                    <script src="scripts.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), "button { font: inherit; }\n");
        Files.writeString(workspace.resolve("scripts.js"), """
                document.getElementById('teaser-button').addEventListener('click', function() {
                  document.querySelector('#teaser-status').innerText = 'Teaser ready';
                });
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Update scripts.js so #teaser-button updates #teaser-status when clicked.",
                loopResult(List.of(successfulWrite("scripts.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status(), result.summary());
    }

    @Test
    void requestedButtonStatusInteractionRejectsHandlerBoundToWrongTrigger() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body>
                    <button id="teaser-button">Show teaser</button>
                    <button id="other-button">Other</button>
                    <p id="teaser-status">Waiting.</p>
                    <script src="scripts.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), "button { font: inherit; }\n");
        Files.writeString(workspace.resolve("scripts.js"), """
                document.getElementById('other-button').addEventListener('click', function() {
                  document.getElementById('teaser-status').textContent = 'Wrong trigger';
                });
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Update scripts.js so #teaser-button updates #teaser-status when clicked.",
                loopResult(List.of(successfulWrite("scripts.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status(), result.summary());
        assertTrue(result.problems().stream().anyMatch(p ->
                        p.contains("#teaser-button") && p.contains("#teaser-status")),
                result.problems().toString());
    }

    @Test
    void pureSelectorCoherenceRequestDoesNotCreateInteractionObligation() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body>
                    <button class="cta-button">Show teaser</button>
                    <script src="scripts.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), ".cta-button { font: inherit; }\n");
        Files.writeString(workspace.resolve("scripts.js"), """
                document.querySelector('.cta-button').addEventListener('click', function() {
                  console.log('ok');
                });
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Fix the selector mismatch by changing .missing-button to .cta-button.",
                loopResult(List.of(successfulWrite("scripts.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status(), result.summary());
        assertFalse(result.summary().contains("interaction"), result.summary());
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
    void cssCompoundClassSelectorMayBeSatisfiedByJavascriptDynamicClass() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html lang="en">
                  <head>
                    <link rel="stylesheet" href="style.css">
                  </head>
                  <body>
                    <button id="toggle">Toggle Neon</button>
                    <div class="neon-box" id="box">Neon Box</div>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("style.css"), """
                .neon-box {
                  filter: brightness(1);
                }
                .neon-box.off {
                  filter: brightness(0.2);
                }
                """);
        Files.writeString(workspace.resolve("script.js"), """
                const toggleBtn = document.getElementById('toggle');
                const neonBox = document.getElementById('box');
                toggleBtn.addEventListener('click', () => {
                  neonBox.classList.add('off');
                });
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Create the full synthwave frontend now with exactly index.html, style.css, and script.js.",
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("style.css", VerificationStatus.PASS),
                        successfulWrite("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status(), result.problems().toString());
        assertFalse(result.problems().stream()
                        .anyMatch(p -> p.contains("CSS references missing class selectors: `.off`")),
                result.problems().toString());
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
        assertTrue(TargetScopeStaticVerifier.expectedTargetMatches("Index.html", "index.html", true));
        assertTrue(TargetScopeStaticVerifier.expectedTargetMatches(".\\Index.html", "./index.html", true));
        assertFalse(TargetScopeStaticVerifier.expectedTargetMatches("scripts.js", "script.js", true));
        assertFalse(TargetScopeStaticVerifier.expectedTargetMatches("Index.html", "index.html", false));
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
    void dirtyStaticWebContinuationReadmeOnlyMutationFailsExpectedTargetVerification() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head><link rel="stylesheet" href="style.css"></head>
                  <body><main>Retrocats</main><script src="script.js"></script></body>
                </html>
                """);
        Files.writeString(workspace.resolve("style.css"), "body { color: white; }");
        Files.writeString(workspace.resolve("script.js"), "console.log('retrocats');");
        Files.writeString(workspace.resolve("README.md"), "Placeholder");
        TaskContract contract = WorkspaceTargetReconciler.reconcile(
                TaskContractResolver.fromUserRequest(
                        "Make this Retrocats website even more polished and complete. "
                                + "Use Tailwind correctly, preserve facts, and repair anything unverified."),
                workspace);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                contract,
                loopResult(List.of(successfulWrite("README.md", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status(), result.summary());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("index.html: expected target was not successfully mutated")),
                result.problems().toString());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("style.css: expected target was not successfully mutated")),
                result.problems().toString());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("script.js: expected target was not successfully mutated")),
                result.problems().toString());
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
    void staticWebRewriteFailsWhenRequiredBandFactsAreDropped() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head>
                    <title>Retrocats</title>
                    <link rel="stylesheet" href="style.css">
                  </head>
                  <body>
                    <h1>Cool Band</h1>
                    <p>Retro Cat 1 and Retro Cat 2 are touring soon.</p>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("style.css"), "body { background: #111; }\n");
        Files.writeString(workspace.resolve("script.js"), "console.log('ok');\n");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Rewrite the existing Retrocats website. Preserve the band facts: Costanza, Merri, "
                        + "Cassette Love, Nine-zero vhs, Future tense, Past Perfect Vibes, Dust to Dust, "
                        + "Gold for the old, Life span, Rome, Barcelona, Berlin.",
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("style.css", VerificationStatus.PASS),
                        successfulWrite("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.problems().stream()
                        .anyMatch(problem -> problem.contains("required content facts missing")),
                result.problems().toString());
    }

    @Test
    void staticWebRewritePassesContentPreservationWhenRequiredBandFactsRemain() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head>
                    <title>Retrocats</title>
                    <link rel="stylesheet" href="style.css">
                  </head>
                  <body>
                    <h1>Retrocats</h1>
                    <p>Costanza and Merri formed Retrocats in 2024.</p>
                    <p>Cassette Love, Nine-zero vhs, Future tense, and Past Perfect Vibes.</p>
                    <p>Dust to Dust, Gold for the old, Life span.</p>
                    <p>Rome, Barcelona, Berlin.</p>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("style.css"), "body { background: #111; }\n");
        Files.writeString(workspace.resolve("script.js"), "console.log('ok');\n");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Rewrite the existing Retrocats website. Preserve the band facts: Costanza, Merri, "
                        + "Cassette Love, Nine-zero vhs, Future tense, Past Perfect Vibes, Dust to Dust, "
                        + "Gold for the old, Life span, Rome, Barcelona, Berlin.",
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("style.css", VerificationStatus.PASS),
                        successfulWrite("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status());
        assertTrue(result.facts().stream()
                        .anyMatch(fact -> fact.contains("Required static-web content facts were preserved")),
                result.facts().toString());
    }

    @Test
    void staticWebRewritePreservesRequiredDateFactsAcrossSimplePunctuation() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head>
                    <title>Retrocats</title>
                    <link rel="stylesheet" href="style.css">
                  </head>
                  <body>
                    <h1>Retrocats</h1>
                    <ul>
                      <li>Rome - 15 July 2026</li>
                      <li>Barcelona - 18 July 2026</li>
                      <li>Berlin: 22 July 2026</li>
                    </ul>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("style.css"), "body { background: #111; }\n");
        Files.writeString(workspace.resolve("script.js"), "console.log('ok');\n");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Rewrite the existing Retrocats website. Preserve the band facts: "
                        + "Rome 15 July 2026, Barcelona 18 July 2026, Berlin 22 July 2026.",
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("style.css", VerificationStatus.PASS),
                        successfulWrite("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status());
        assertTrue(result.facts().stream()
                        .anyMatch(fact -> fact.contains("Required static-web content facts were preserved")),
                result.facts().toString());
    }

    @Test
    void staticWebRewriteReportsWeakJavaScriptStringEvidenceWithoutSatisfyingVisibleFacts() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head>
                    <title>Retrocats</title>
                    <link rel="stylesheet" href="style.css">
                  </head>
                  <body>
                    <h1>Retrocats</h1>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("style.css"), "body { background: #111; }\n");
        Files.writeString(workspace.resolve("script.js"), """
                const bio = '<p>Costanza, Merri</p>';
                console.log(bio);
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Rewrite the existing Retrocats website. Preserve the band facts: Costanza, Merri.",
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("style.css", VerificationStatus.PASS),
                        successfulWrite("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.facts().stream()
                        .anyMatch(fact -> fact.contains("linked JavaScript string evidence")
                                && fact.contains("Costanza")
                                && fact.contains("Merri")),
                result.facts().toString());
        assertTrue(result.problems().stream()
                        .anyMatch(problem -> problem.contains("required content facts missing")
                                && problem.contains("Costanza")
                                && problem.contains("Merri")),
                result.problems().toString());
    }

    @Test
    void staticWebRewriteFailsWhenDurableRequiredFactsAreDroppedFromFollowUp() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head>
                    <title>Retrocats</title>
                    <script src="https://cdn.tailwindcss.com"></script>
                  </head>
                  <body>
                    <main class="min-h-screen bg-slate-950 text-pink-300">
                      <h1>Retrocats</h1>
                      <p>Formed in 2010 in Los Angeles by Alice and Bob.</p>
                    </main>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("style.css"), "body { background: #111; }\n");
        Files.writeString(workspace.resolve("script.js"), "console.log('ok');\n");
        TaskContract followUpContract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("index.html", "style.css", "script.js"),
                Set.of(),
                Set.of("tailwind.min.css"),
                "Make this Retrocats website more polished and complete.",
                "active-static-web-context",
                StaticWebRequirements.of(
                        List.of("Retrocats", "Costanza", "Merri", "Berlin 22 July 2026"),
                        Set.of("tailwind.min.css")));

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                followUpContract,
                loopResult(List.of(
                        successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite("style.css", VerificationStatus.PASS),
                        successfulWrite("script.js", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.problems().stream()
                        .anyMatch(problem -> problem.contains("required content facts missing")
                                && problem.contains("Costanza")),
                result.problems().toString());
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

    private void writeCompleteStaticWebsite() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head>
                    <meta charset="utf-8">
                    <title>Retrocats</title>
                    <link rel="stylesheet" href="styles.css">
                  </head>
                  <body>
                    <main class="hero">
                      <h1>Retrocats</h1>
                      <p>Costanza and Merri formed Retrocats in 2024.</p>
                    </main>
                    <script src="scripts.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), """
                .hero {
                  min-height: 100vh;
                  color: #ffffff;
                  background: linear-gradient(135deg, #05000a, #ff2ea6);
                }
                """);
        Files.writeString(workspace.resolve("scripts.js"), """
                document.addEventListener('DOMContentLoaded', () => {
                  document.body.dataset.ready = 'true';
                });
                """);
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
                ToolMutationEvidence.exactEdit(oldString, newString));
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
                ToolMutationEvidence.fullWriteReplacement(previousContent, newContent));
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
