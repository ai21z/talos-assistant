package dev.talos.runtime.verification;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticWebRenderVerifierTest {
    @TempDir
    Path workspace;

    @Test
    void unavailableRunnerReportsRenderLimitationWithoutVerifiedProof() throws Exception {
        writeFixture();

        VerificationReport report = StaticWebRenderVerifier.verify(
                workspace,
                contract(),
                selectors(),
                StaticWebRenderVerifier.RenderRunner.unavailable("render runner unavailable"));

        assertFalse(report.hasRequiredClaims(), report.toString());
        assertFalse(report.authoritativeProofKinds().contains(ProofKind.RENDER_COMPARISON.name()),
                report.authoritativeProofKinds().toString());
        assertTrue(report.limitations().stream()
                        .anyMatch(limit -> limit.contains("render runner unavailable")),
                report.limitations().toString());
        assertTrue(report.verifierResults().stream()
                        .anyMatch(result -> result.proofKind() == ProofKind.RENDER_COMPARISON
                                && result.verdict() == VerificationVerdict.UNAVAILABLE),
                report.verifierResults().toString());
    }

    @Test
    void visibleFirstViewportProducesAuthoritativeRenderProof() throws Exception {
        writeFixture();

        VerificationReport report = StaticWebRenderVerifier.verify(
                workspace,
                contract(),
                selectors(),
                (root, input) -> StaticWebRenderVerifier.RenderRunResult.verified(
                        1366,
                        768,
                        List.of("First viewport contains visible primary brand text: Retrocats."),
                        List.of("Screenshot artifact unavailable in fake runner.")));

        assertTrue(report.authoritativeProofKinds().contains(ProofKind.RENDER_COMPARISON.name()),
                report.authoritativeProofKinds().toString());
        assertTrue(report.facts().stream()
                        .anyMatch(fact -> fact.contains("First viewport contains visible primary brand text")),
                report.facts().toString());
        assertEquals(VerificationVerdict.VERIFIED, report.verifierResults().get(0).verdict());
    }

    @Test
    void blankFirstViewportFailsRenderVerification() throws Exception {
        writeFixture();

        VerificationReport report = StaticWebRenderVerifier.verify(
                workspace,
                contract(),
                selectors(),
                (root, input) -> StaticWebRenderVerifier.RenderRunResult.failed(
                        1366,
                        768,
                        List.of("First viewport rendered as mostly blank black pixels."),
                        List.of()));

        assertFalse(report.authoritativeProofKinds().contains(ProofKind.RENDER_COMPARISON.name()),
                report.authoritativeProofKinds().toString());
        assertTrue(report.problems().stream()
                        .anyMatch(problem -> problem.contains("mostly blank")),
                report.problems().toString());
        assertEquals(VerificationVerdict.FAILED, report.verifierResults().get(0).verdict());
    }

    @Test
    void belowFoldBrandContentFailsRenderVerification() throws Exception {
        writeFixture();

        VerificationReport report = StaticWebRenderVerifier.verify(
                workspace,
                contract(),
                selectors(),
                (root, input) -> StaticWebRenderVerifier.RenderRunResult.failed(
                        1366,
                        768,
                        List.of("Primary brand/content was not visible in the first viewport."),
                        List.of()));

        assertTrue(report.problems().stream()
                        .anyMatch(problem -> problem.contains("not visible in the first viewport")),
                report.problems().toString());
    }

    @Test
    void failedRemoteAssetRequestIsSurfacedAsRenderProblem() throws Exception {
        writeFixture();

        VerificationReport report = StaticWebRenderVerifier.verify(
                workspace,
                contract(),
                selectors(),
                (root, input) -> StaticWebRenderVerifier.RenderRunResult.failed(
                        1366,
                        768,
                        List.of("Render request failed for https://images.example.test/hero.jpg: net::ERR_FAILED."),
                        List.of("Render proof depends on browser request telemetry.")));

        assertTrue(report.problems().stream()
                        .anyMatch(problem -> problem.contains("Render request failed")
                                && problem.contains("https://images.example.test/hero.jpg")),
                report.problems().toString());
        assertTrue(report.limitations().stream()
                        .anyMatch(limit -> limit.contains("browser request telemetry")),
                report.limitations().toString());
    }

    @Test
    void nonVisualStaticWebTaskDoesNotRunRenderVerifier() throws Exception {
        writeFixture();

        VerificationReport report = StaticWebRenderVerifier.verify(
                workspace,
                TaskContractResolver.fromUserRequest(
                        "Update scripts.js so #teaser-button updates #teaser-status when clicked."),
                selectors(),
                (root, input) -> StaticWebRenderVerifier.RenderRunResult.failed(
                        1366,
                        768,
                        List.of("Should not run for pure interaction task."),
                        List.of()));

        assertEquals(VerificationReport.empty(), report);
    }

    private void writeFixture() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body>
                    <main class="hero"><h1>Retrocats</h1><p>Costanza and Merri</p></main>
                    <script src="scripts.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), """
                .hero { min-height: 100vh; color: #fff; background: #05000a; }
                """);
        Files.writeString(workspace.resolve("scripts.js"), "console.log('Retrocats ready');\n");
    }

    private TaskContract contract() {
        return TaskContractResolver.fromUserRequest(
                "Create a complete modern dark synthwave static website for a band called Retrocats.");
    }

    private StaticWebSelectorAnalyzer.Facts selectors() {
        return StaticWebSelectorAnalyzer.analyze(
                workspace,
                StaticWebSurfaceDetector.obviousPrimaryFiles(workspace));
    }
}
