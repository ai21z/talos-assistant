package dev.talos.runtime.verification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticWebBrowserBehaviorVerifierTest {
    @TempDir
    Path workspace;
    @TempDir
    Path outsideWorkspace;

    @Test
    void clickUpdatingOutputTextProducesAuthoritativeBrowserBehaviorProof() throws Exception {
        writeWebFixture("""
                const trigger = document.getElementById('teaser-button');
                const status = document.getElementById('teaser-status');
                trigger.addEventListener('click', function() {
                  status.textContent = 'Teaser ready';
                });
                """);

        VerificationReport report = StaticWebBrowserBehaviorVerifier.verify(
                workspace,
                "Update scripts.js so #teaser-button updates #teaser-status when clicked.",
                selectors());

        assertTrue(report.requiredClaimsSatisfied(), report.toString());
        assertEquals(1, report.requiredClaimCount());
        assertEquals(0, report.unsatisfiedRequiredClaimCount());
        assertTrue(report.authoritativeProofKinds().contains(ProofKind.BROWSER_BEHAVIOR.name()));
        assertTrue(report.facts().stream().anyMatch(fact -> fact.contains("Browser behavior verified")),
                report.facts().toString());
        assertTrue(report.facts().stream().anyMatch(fact -> fact.contains("requested workspace resources")
                        && fact.contains("index.html")
                        && fact.contains("scripts.js")),
                report.facts().toString());
    }

    @Test
    void noopClickHandlerFailsBrowserBehaviorProof() throws Exception {
        writeWebFixture("""
                document.getElementById('teaser-button').addEventListener('click', function() {
                  document.getElementById('teaser-status').textC;
                });
                """);

        VerificationReport report = StaticWebBrowserBehaviorVerifier.verify(
                workspace,
                "Update scripts.js so #teaser-button updates #teaser-status when clicked.",
                selectors());

        assertFalse(report.requiredClaimsSatisfied(), report.toString());
        assertTrue(report.hasRequiredFailure(), report.toString());
        assertTrue(report.problems().stream().anyMatch(problem -> problem.contains("did not change")),
                report.problems().toString());
    }

    @Test
    void fallbackLoadTimeMutationWithoutClickChangeFailsBrowserBehaviorProof() throws Exception {
        writeWebFixture("""
                window.teaserLoads = (window.teaserLoads || 0) + 1;
                document.getElementById('teaser-status').textContent = 'Loaded ' + window.teaserLoads;
                document.getElementById('teaser-button').addEventListener('click', function() {
                  document.getElementById('teaser-status').textContent;
                });
                """);

        VerificationReport report = StaticWebBrowserBehaviorVerifier.verify(
                workspace,
                "Update scripts.js so #teaser-button updates #teaser-status when clicked.",
                selectors());

        assertFalse(report.requiredClaimsSatisfied(), report.toString());
        assertTrue(report.hasRequiredFailure(), report.toString());
        assertTrue(report.limitations().stream().anyMatch(limit -> limit.contains("executing linked workspace JavaScript")),
                report.limitations().toString());
        assertTrue(report.problems().stream().anyMatch(problem -> problem.contains("did not change")),
                report.problems().toString());
    }

    @Test
    void absoluteFileScriptOutsideWorkspaceIsBlockedByBrowserRunner() throws Exception {
        Path outsideScript = outsideWorkspace.resolve("outside.js");
        Files.writeString(outsideScript, """
                document.getElementById('teaser-status').textContent = 'outside script loaded';
                """);
        writeWebFixture("""
                <!doctype html>
                <html>
                  <body>
                    <button id="teaser-button">Show teaser</button>
                    <p id="teaser-status">Waiting.</p>
                    <script src="%s"></script>
                    <script src="scripts.js"></script>
                  </body>
                </html>
                """.formatted(outsideScript.toUri()), """
                document.getElementById('teaser-button').addEventListener('click', function() {
                  document.getElementById('teaser-status').textContent = 'workspace click';
                });
                """);

        VerificationReport report = StaticWebBrowserBehaviorVerifier.verify(
                workspace,
                "Update scripts.js so #teaser-button updates #teaser-status when clicked.",
                selectors());

        assertFalse(report.requiredClaimsSatisfied(), report.toString());
        assertTrue(report.hasRequiredFailure(), report.toString());
        assertTrue(report.problems().stream().anyMatch(problem ->
                        problem.contains("Script load failed for file://<redacted>")
                                && problem.contains("Blocked non-workspace browser request")),
                report.problems().toString());
        assertFalse(report.toString().contains(outsideScript.getFileName().toString()), report.toString());
    }

    @Test
    void fallbackVerifiesWhenInlineEvalMutatesAndClickChangesOutputFurther() throws Exception {
        writeWebFixture("""
                window.teaserLoads = (window.teaserLoads || 0) + 1;
                document.getElementById('teaser-status').textContent = 'Loaded ' + window.teaserLoads;
                if (window.teaserLoads > 1) {
                  document.getElementById('teaser-button').addEventListener('click', function() {
                    document.getElementById('teaser-status').textContent = 'Clicked ' + window.teaserLoads;
                  });
                }
                """);

        VerificationReport report = StaticWebBrowserBehaviorVerifier.verify(
                workspace,
                "Update scripts.js so #teaser-button updates #teaser-status when clicked.",
                selectors());

        assertTrue(report.requiredClaimsSatisfied(), report.toString());
        assertEquals(0, report.unsatisfiedRequiredClaimCount());
        assertTrue(report.authoritativeProofKinds().contains(ProofKind.BROWSER_BEHAVIOR.name()));
        assertTrue(report.limitations().stream().anyMatch(limit -> limit.contains("executing linked workspace JavaScript")),
                report.limitations().toString());
    }

    @Test
    void unavailableRunnerReportsUnavailableRequiredClaim() throws Exception {
        writeWebFixture("""
                document.getElementById('teaser-button').addEventListener('click', function() {
                  document.getElementById('teaser-status').textContent = 'Teaser ready';
                });
                """);

        VerificationReport report = StaticWebBrowserBehaviorVerifier.verify(
                workspace,
                "Update scripts.js so #teaser-button updates #teaser-status when clicked.",
                selectors(),
                (root, htmlFile, linkedJavaScript, binding) -> StaticWebBrowserBehaviorVerifier.BrowserRunResult.unavailable(
                        "browser runner unavailable"));

        assertFalse(report.requiredClaimsSatisfied(), report.toString());
        assertTrue(report.hasRequiredUnavailable(), report.toString());
        assertTrue(report.limitations().stream().anyMatch(limit -> limit.contains("browser runner unavailable")),
                report.limitations().toString());
    }

    private void writeWebFixture(String script) throws Exception {
        writeWebFixture("""
                <!doctype html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body>
                    <button id="teaser-button">Show teaser</button>
                    <p id="teaser-status">Waiting.</p>
                    <script src="scripts.js"></script>
                  </body>
                </html>
                """, script);
    }

    private void writeWebFixture(String html, String script) throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                %s
                """.formatted(html.strip()));
        Files.writeString(workspace.resolve("styles.css"), "button { font: inherit; }\n");
        Files.writeString(workspace.resolve("scripts.js"), script);
    }

    private StaticWebSelectorAnalyzer.Facts selectors() {
        return StaticWebSelectorAnalyzer.analyze(
                workspace,
                StaticWebSurfaceDetector.obviousPrimaryFiles(workspace));
    }
}
