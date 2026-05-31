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
        Files.writeString(workspace.resolve("scripts.js"), script);
    }

    private StaticWebSelectorAnalyzer.Facts selectors() {
        return StaticWebSelectorAnalyzer.analyze(
                workspace,
                StaticWebSurfaceDetector.obviousPrimaryFiles(workspace));
    }
}
