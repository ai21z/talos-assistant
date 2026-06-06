package dev.talos.runtime.verification;

import dev.talos.runtime.task.TaskContract;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** First-viewport render verification spine. A real browser runner is a future dependency decision. */
final class StaticWebRenderVerifier {
    private static final int DEFAULT_VIEWPORT_WIDTH = 1366;
    private static final int DEFAULT_VIEWPORT_HEIGHT = 768;
    private static final String DEFAULT_UNAVAILABLE =
            "First-viewport render verification was unavailable; no render-capable runner is configured.";

    private StaticWebRenderVerifier() {}

    interface RenderRunner {
        RenderRunResult run(Path root, RenderInput input);

        static RenderRunner unavailable(String limitation) {
            return (root, input) -> RenderRunResult.unavailable(limitationOrDefault(limitation));
        }
    }

    record RenderInput(
            String htmlFile,
            String cssFile,
            String jsFile,
            String request,
            int viewportWidth,
            int viewportHeight
    ) {
        RenderInput {
            htmlFile = htmlFile == null ? "" : htmlFile.strip();
            cssFile = cssFile == null ? "" : cssFile.strip();
            jsFile = jsFile == null ? "" : jsFile.strip();
            request = request == null ? "" : request.strip();
            viewportWidth = viewportWidth <= 0 ? DEFAULT_VIEWPORT_WIDTH : viewportWidth;
            viewportHeight = viewportHeight <= 0 ? DEFAULT_VIEWPORT_HEIGHT : viewportHeight;
        }
    }

    record RenderRunResult(
            VerificationVerdict verdict,
            int viewportWidth,
            int viewportHeight,
            List<String> facts,
            List<String> problems,
            List<String> limitations,
            String screenshotPath
    ) {
        RenderRunResult {
            verdict = verdict == null ? VerificationVerdict.UNAVAILABLE : verdict;
            viewportWidth = viewportWidth <= 0 ? DEFAULT_VIEWPORT_WIDTH : viewportWidth;
            viewportHeight = viewportHeight <= 0 ? DEFAULT_VIEWPORT_HEIGHT : viewportHeight;
            facts = facts == null ? List.of() : List.copyOf(facts);
            problems = problems == null ? List.of() : List.copyOf(problems);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
            screenshotPath = screenshotPath == null ? "" : screenshotPath.strip();
        }

        static RenderRunResult verified(
                int viewportWidth,
                int viewportHeight,
                List<String> facts,
                List<String> limitations
        ) {
            return new RenderRunResult(
                    VerificationVerdict.VERIFIED,
                    viewportWidth,
                    viewportHeight,
                    facts,
                    List.of(),
                    limitations,
                    "");
        }

        static RenderRunResult failed(
                int viewportWidth,
                int viewportHeight,
                List<String> problems,
                List<String> limitations
        ) {
            return new RenderRunResult(
                    VerificationVerdict.FAILED,
                    viewportWidth,
                    viewportHeight,
                    List.of(),
                    problems,
                    limitations,
                    "");
        }

        static RenderRunResult unavailable(String limitation) {
            return new RenderRunResult(
                    VerificationVerdict.UNAVAILABLE,
                    DEFAULT_VIEWPORT_WIDTH,
                    DEFAULT_VIEWPORT_HEIGHT,
                    List.of(),
                    List.of(),
                    List.of(limitationOrDefault(limitation)),
                    "");
        }
    }

    static RenderRunner unavailableRunner() {
        return RenderRunner.unavailable(DEFAULT_UNAVAILABLE);
    }

    static VerificationReport verify(
            Path root,
            TaskContract contract,
            StaticWebSelectorAnalyzer.Facts facts
    ) {
        return verify(root, contract, facts, unavailableRunner());
    }

    static VerificationReport verify(
            Path root,
            TaskContract contract,
            StaticWebSelectorAnalyzer.Facts facts,
            RenderRunner runner
    ) {
        if (!shouldVerify(contract, facts)) return VerificationReport.empty();
        VerificationClaim claim = new VerificationClaim(
                "static-web-render:first-viewport",
                "First-viewport render verification.",
                ProofKind.RENDER_COMPARISON,
                null,
                false);
        if (root == null || facts == null || facts.htmlFile().isBlank()) {
            return report(claim, RenderRunResult.unavailable(
                    "First-viewport render verification was unavailable because the static web surface was incomplete."),
                    "");
        }
        RenderInput input = new RenderInput(
                facts.htmlFile(),
                facts.cssFile(),
                facts.jsFile(),
                contract == null ? "" : contract.originalUserRequest(),
                DEFAULT_VIEWPORT_WIDTH,
                DEFAULT_VIEWPORT_HEIGHT);
        RenderRunner safeRunner = runner == null ? unavailableRunner() : runner;
        RenderRunResult result;
        try {
            result = safeRunner.run(root.toAbsolutePath().normalize(), input);
        } catch (RuntimeException e) {
            result = RenderRunResult.unavailable(
                    "First-viewport render verification was unavailable: " + safeMessage(e));
        }
        return report(claim, result, input.htmlFile());
    }

    private static VerificationReport report(VerificationClaim claim, RenderRunResult result, String htmlFile) {
        RenderRunResult safeResult = result == null ? RenderRunResult.unavailable(DEFAULT_UNAVAILABLE) : result;
        List<String> facts = new ArrayList<>();
        if (safeResult.verdict() != VerificationVerdict.UNAVAILABLE) {
            facts.add("First-viewport render runner inspected `" + renderTarget(htmlFile)
                    + "` at " + safeResult.viewportWidth() + "x" + safeResult.viewportHeight() + ".");
        }
        facts.addAll(safeResult.facts());
        if (!safeResult.screenshotPath().isBlank()) {
            facts.add("First-viewport render screenshot artifact: `" + safeResult.screenshotPath() + "`.");
        }
        VerifierResult verifierResult = new VerifierResult(
                claim,
                ProofKind.RENDER_COMPARISON,
                EvidenceAuthority.AUTHORITATIVE,
                EvidenceCoverage.SCOPED,
                safeResult.verdict(),
                facts,
                safeResult.problems(),
                safeResult.limitations());
        return new VerificationReport(
                List.of(),
                List.of(verifierResult),
                facts,
                safeResult.problems(),
                safeResult.limitations());
    }

    private static String renderTarget(String htmlFile) {
        return htmlFile == null || htmlFile.isBlank() ? "static web page" : htmlFile;
    }

    private static boolean shouldVerify(TaskContract contract, StaticWebSelectorAnalyzer.Facts facts) {
        if (contract == null || facts == null || !contract.mutationRequested()) return false;
        String lower = contract.originalUserRequest() == null
                ? ""
                : contract.originalUserRequest().toLowerCase(Locale.ROOT);
        if (lower.isBlank()) return false;
        return mentionsStrongPresentationIntent(lower)
                || (mentionsWebSurface(lower) && mentionsWebPresentationIntent(lower));
    }

    private static boolean mentionsWebSurface(String lower) {
        return lower.contains("website")
                || lower.contains("webpage")
                || lower.contains("web page")
                || lower.contains("landing page")
                || lower.contains("site")
                || lower.contains("index.html")
                || lower.contains(".html");
    }

    private static boolean mentionsStrongPresentationIntent(String lower) {
        return lower.contains("modern")
                || lower.contains("visual")
                || lower.contains("design")
                || lower.contains("synthwave")
                || lower.contains("hero")
                || lower.contains("viewport")
                || lower.contains("polished")
                || lower.contains("complete")
                || lower.contains("dark")
                || lower.contains("theme")
                || lower.contains("look")
                || lower.contains("style");
    }

    private static boolean mentionsWebPresentationIntent(String lower) {
        return mentionsStrongPresentationIntent(lower) || lower.contains("complete");
    }

    private static String limitationOrDefault(String limitation) {
        return limitation == null || limitation.isBlank() ? DEFAULT_UNAVAILABLE : limitation.strip();
    }

    private static String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return throwable == null ? "unknown error" : throwable.getClass().getSimpleName();
        }
        return throwable.getMessage().replace('\r', ' ').replace('\n', ' ').strip();
    }
}
