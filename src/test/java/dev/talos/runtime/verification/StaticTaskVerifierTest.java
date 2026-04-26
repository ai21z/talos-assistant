package dev.talos.runtime.verification;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.tools.VerificationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void linkedCssFileIsPreferredOverLegacyCssNeighbor() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body><main class="calculator"></main><script src="script.js"></script></body>
                </html>
                """);
        Files.writeString(workspace.resolve("style.css"), ".legacy-missing { color: red; }");
        Files.writeString(workspace.resolve("styles.css"), ".calculator { max-width: 28rem; }");
        Files.writeString(workspace.resolve("script.js"), "document.querySelector('.calculator');");

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

        assertEquals(TaskVerificationStatus.PASSED, result.status());
        assertTrue(result.summary().contains("Target/readback checks passed"));
        assertTrue(result.summary().contains("no task-specific static verifier was applicable"));
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
