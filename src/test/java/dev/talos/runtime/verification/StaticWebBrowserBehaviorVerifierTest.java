package dev.talos.runtime.verification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.htmlunit.BrowserVersion;
import org.htmlunit.WebClient;
import org.htmlunit.html.HtmlPage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticWebBrowserBehaviorVerifierTest {
    @TempDir
    Path workspace;
    @TempDir
    Path outsideWorkspace;

    @Test
    void browserFailureBoundaryPropagatesTypedControlFlowAndRetainsOrdinaryFallback() {
        CancellationException cancellation = new CancellationException("stop browser verifier");
        assertSame(cancellation, assertThrows(CancellationException.class, () ->
                StaticWebBrowserBehaviorVerifier.browserFailureResult(cancellation)));

        InterruptedException interruption = new InterruptedException("interrupt browser verifier");
        RuntimeException wrapped = new RuntimeException(interruption);
        try {
            RuntimeException observed = assertThrows(RuntimeException.class, () ->
                    StaticWebBrowserBehaviorVerifier.browserFailureResult(wrapped));
            assertSame(wrapped, observed);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }

        StaticWebBrowserBehaviorVerifier.BrowserRunResult unavailable =
                StaticWebBrowserBehaviorVerifier.browserFailureResult(
                        new IOException("ordinary browser failure"));
        assertEquals(VerificationVerdict.UNAVAILABLE, unavailable.verdict());

        StaticWebBrowserBehaviorVerifier.BrowserRunResult runtimeUnavailable =
                StaticWebBrowserBehaviorVerifier.browserFailureResult(
                        new RuntimeException("ordinary browser runtime failure"));
        assertEquals(VerificationVerdict.UNAVAILABLE, runtimeUnavailable.verdict());
    }

    @Test
    void interruptedStatusCannotBecomeACompletedBrowserVerification() throws Exception {
        writeWebFixture("""
                document.getElementById('teaser-button').addEventListener('click', function() {
                  document.getElementById('teaser-status').textContent = 'Teaser ready';
                });
                """);
        AtomicInteger runnerCalls = new AtomicInteger();

        try {
            Thread.currentThread().interrupt();
            RuntimeException beforeRun = assertThrows(RuntimeException.class, () ->
                    StaticWebBrowserBehaviorVerifier.verify(
                            workspace,
                            "Update scripts.js so #teaser-button updates #teaser-status when clicked.",
                            selectors(),
                            (root, htmlFile, linkedJavaScript, binding) -> {
                                runnerCalls.incrementAndGet();
                                return StaticWebBrowserBehaviorVerifier.BrowserRunResult.unavailable("not reached");
                            }));
            assertTrue(beforeRun.getCause() instanceof InterruptedException);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, runnerCalls.get());
        } finally {
            Thread.interrupted();
        }

        try {
            RuntimeException afterRun = assertThrows(RuntimeException.class, () ->
                    StaticWebBrowserBehaviorVerifier.verify(
                            workspace,
                            "Update scripts.js so #teaser-button updates #teaser-status when clicked.",
                            selectors(),
                            (root, htmlFile, linkedJavaScript, binding) -> {
                                runnerCalls.incrementAndGet();
                                Thread.currentThread().interrupt();
                                return StaticWebBrowserBehaviorVerifier.BrowserRunResult.unavailable(
                                        "dependency swallowed interruption");
                            }));
            assertTrue(afterRun.getCause() instanceof InterruptedException);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, runnerCalls.get());
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"const", "let"})
    void htmlUnitGlobalStatusBindingCompatibilityWitness(String declaration) throws Exception {
        try (WebClient client = new WebClient(BrowserVersion.CHROME)) {
            client.getOptions().setCssEnabled(false);
            HtmlPage page = client.loadHtmlCodeIntoCurrentWindow("""
                    <!doctype html><html><body>
                    <button id="probe">Click</button><p id="out">Waiting</p>
                    <script>
                    %s status = document.getElementById('out');
                    const outputNode = document.getElementById('out');
                    document.body.setAttribute('data-original-type', typeof status);
                    document.body.setAttribute('data-unshadowed-type', typeof outputNode);
                    document.getElementById('probe').addEventListener('click', function () {
                      document.body.setAttribute('data-click-type', typeof status);
                      document.body.setAttribute('data-click-value', String(status));
                      status.textContent = 'Clicked';
                    });
                    </script></body></html>
                    """.formatted(declaration));
            page.getElementById("probe").click();
            // Pinned-engine witness, not the expected product behavior. Native Chrome
            // observes object|object|object|Clicked for this unchanged page.
            assertEquals("string|object|string|Waiting",
                    page.getBody().getAttribute("data-original-type") + "|"
                            + page.getBody().getAttribute("data-unshadowed-type") + "|"
                            + page.getBody().getAttribute("data-click-type") + "|"
                            + page.getElementById("out").getTextContent(), page.asXml());
        }
    }

    @Test
    void clickUpdatingOutputTextProducesAuthoritativeBrowserBehaviorProof() throws Exception {
        writeWebFixture("""
                window.teaserLoads = (window.teaserLoads || 0) + 1;
                const trigger = document.getElementById('teaser-button');
                const statusOutput = document.getElementById('teaser-status');
                trigger.addEventListener('click', function() {
                  if (window.teaserLoads === 1) statusOutput.textContent = 'Teaser ready';
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

    @ParameterizedTest
    @ValueSource(strings = {
            "const status = document.getElementById('teaser-status');",
            "const {output: status} = {output: document.getElementById('teaser-status')};"
    })
    void originalGlobalStatusBindingIsUnavailableNotAProductFailureOrSuccess(String declaration) throws Exception {
        // Preserve the valid original page that the previous duplicate-eval fallback
        // masked. A simulation capability gap must not certify or condemn the page.
        writeWebFixture("""
                const trigger = document.getElementById('teaser-button');
                %s
                trigger.addEventListener('click', function() {
                  status.textContent = 'Teaser ready';
                });
                """.formatted(declaration));
        VerificationReport report = StaticWebBrowserBehaviorVerifier.verify(workspace,
                "Update scripts.js so #teaser-button updates #teaser-status when clicked.", selectors());
        assertFalse(report.requiredClaimsSatisfied(), report.toString());
        assertTrue(report.hasRequiredUnavailable(), report.toString());
        assertFalse(report.hasRequiredFailure(), report.toString());
        assertFalse(report.authoritativeProofKinds().contains(ProofKind.BROWSER_BEHAVIOR.name()));
        assertTrue(report.limitations().stream().anyMatch(text -> text.contains("Window.status")),
                report.toString());
    }

    @Test
    void inlineGlobalStatusBindingAlsoRemainsUnavailable() throws Exception {
        writeWebFixture("""
                <!doctype html><html><body>
                <button id="teaser-button">Click</button><p id="teaser-status">Waiting</p>
                <script>const status = document.getElementById('teaser-status');</script>
                <script src="scripts.js"></script></body></html>
                """, """
                document.getElementById('teaser-button').addEventListener('click', function() {
                  status.textContent = 'Clicked';
                });
                """);
        VerificationReport report = StaticWebBrowserBehaviorVerifier.verify(workspace,
                "Update scripts.js so #teaser-button updates #teaser-status when clicked.", selectors());
        assertTrue(report.hasRequiredUnavailable(), report.toString());
        assertFalse(report.hasRequiredFailure(), report.toString());
        assertFalse(report.requiredClaimsSatisfied(), report.toString());
    }

    @Test
    void compatibilityObservationRecognizesBindingsNotLookalikeText() {
        for (String source : List.of("const status = {};", "let status;",
                "const \\u0073tatus = {};", "const {value: status} = {};",
                "const {status} = {};", "let [status] = [];",
                "const {nested: [status]} = {};", "const [status = {}] = [];",
                "const {value: status = {}} = {};", "const [...status] = [];", "const {...status} = {};")) {
            assertTrue(StaticWebJavaScriptSyntaxVerifier.hasUnsupportedGlobalStatusBinding(source), source);
        }
        for (String source : List.of("// const status = {};", "'const status = {}';",
                "function bind() { const status = {}; }", "{ let status; }",
                "const {status: outputNode} = {};", "const {value: outputNode} = {value: status};",
                "const [outputNode = status] = [];", "const {value: outputNode = status} = {};",
                "const outputNode = {};", "var status = '';")) {
            assertFalse(StaticWebJavaScriptSyntaxVerifier.hasUnsupportedGlobalStatusBinding(source), source);
        }
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
    void loadTimeMutationWithoutClickChangeFailsWithoutReexecutingScripts() throws Exception {
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
        assertFalse(report.facts().stream().anyMatch(fact -> fact.contains("executed the linked")), report.toString());
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
    void workspaceRelativeLinkedScriptIsBlockedWithoutReadingExternalBytes() throws Exception {
        writeWebFixture("""
                document.getElementById('teaser-button').addEventListener('click', function() {
                  document.getElementById('teaser-status').textContent = 'local fixture';
                });
                """);
        StaticWebSelectorAnalyzer.Facts preLinkSelectors = selectors();
        Path outsideScript = Files.writeString(outsideWorkspace.resolve("linked-external.js"), """
                document.getElementById('teaser-button').addEventListener('click', function() {
                  document.getElementById('teaser-status').textContent =
                      'T1170_EXTERNAL_BROWSER_CANARY';
                });
                """);
        Files.delete(workspace.resolve("scripts.js"));
        createSymbolicLinkOrAbort(workspace.resolve("scripts.js"), outsideScript);

        VerificationReport report = StaticWebBrowserBehaviorVerifier.verify(
                workspace,
                "Update scripts.js so #teaser-button updates #teaser-status when clicked.",
                preLinkSelectors);

        assertFalse(report.requiredClaimsSatisfied(),
                "workspace-relative linked JavaScript must not produce authoritative proof");
        assertTrue(report.hasRequiredUnavailable() || report.hasRequiredFailure(), report.toString());
        assertFalse(report.toString().contains("T1170_EXTERNAL_BROWSER_CANARY"), report.toString());
        assertFalse(report.toString().contains(outsideScript.toString()), report.toString());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void workspaceRelativeWindowsJunctionScriptIsBlockedWithoutExternalBytes()
            throws Exception {
        Path assets = Files.createDirectory(workspace.resolve("assets"));
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html><html><body>
                <button id="teaser-button">Show teaser</button>
                <p id="teaser-status">Waiting.</p>
                <script src="assets/scripts.js"></script>
                </body></html>
                """);
        Files.writeString(workspace.resolve("styles.css"), "button { font: inherit; }\n");
        Files.writeString(assets.resolve("scripts.js"), """
                document.getElementById('teaser-button').addEventListener('click', function() {
                  document.getElementById('teaser-status').textContent = 'ordinary';
                });
                """);
        StaticWebSelectorAnalyzer.Facts ordinaryFacts =
                StaticWebSelectorAnalyzer.analyze(
                        workspace,
                        List.of("index.html", "styles.css", "assets/scripts.js"),
                        List.of("assets/scripts.js"));
        assertNotNull(ordinaryFacts);

        Files.delete(assets.resolve("scripts.js"));
        Files.delete(assets);
        Path externalAssets = Files.createDirectory(outsideWorkspace.resolve("external-assets"));
        Files.writeString(externalAssets.resolve("scripts.js"), """
                document.getElementById('teaser-status').textContent =
                    'T1170_WINDOWS_BROWSER_CANARY';
                """);
        Process process = new ProcessBuilder(
                "cmd.exe", "/d", "/c", "mklink", "/J",
                assets.toString(), externalAssets.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(
                process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);

        VerificationReport report = StaticWebBrowserBehaviorVerifier.verify(
                workspace,
                "Update assets/scripts.js so #teaser-button updates #teaser-status when clicked.",
                ordinaryFacts);

        assertFalse(report.requiredClaimsSatisfied(), report.toString());
        assertTrue(report.hasRequiredUnavailable() || report.hasRequiredFailure(), report.toString());
        assertFalse(report.toString().contains("T1170_WINDOWS_BROWSER_CANARY"), report.toString());
        assertFalse(report.toString().contains(externalAssets.toString()), report.toString());
    }

    @Test
    void pageRequiringASecondScriptEvaluationCannotReceiveAuthoritativeSuccess() throws Exception {
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

        assertFalse(report.requiredClaimsSatisfied(), report.toString());
        assertEquals(1, report.unsatisfiedRequiredClaimCount());
        assertTrue(report.hasRequiredFailure(), report.toString());
        assertFalse(report.authoritativeProofKinds().contains(ProofKind.BROWSER_BEHAVIOR.name()));
        assertEquals(TaskVerificationStatus.FAILED,
                VerificationOutcomeGate.compatibilityOverride(report, List.of()).orElseThrow().status());
        assertFalse(report.facts().stream().anyMatch(fact -> fact.contains("executed the linked")), report.toString());
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

    private static void createSymbolicLinkOrAbort(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException unavailable) {
            Assumptions.abort("symbolic-link fixture is unavailable: "
                    + unavailable.getClass().getSimpleName());
        }
    }
}
