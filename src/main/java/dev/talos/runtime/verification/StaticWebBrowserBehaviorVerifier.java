package dev.talos.runtime.verification;

import org.htmlunit.BrowserVersion;
import org.htmlunit.HttpHeader;
import org.htmlunit.WebClient;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.WebResponseData;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.javascript.JavaScriptErrorListener;
import org.htmlunit.ScriptException;
import org.htmlunit.util.NameValuePair;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Browser/runtime verifier for simple static-web click/update interaction claims. */
final class StaticWebBrowserBehaviorVerifier {
    private static final String LOCAL_HOST = "talos.local";

    private StaticWebBrowserBehaviorVerifier() {}

    interface BrowserRunner {
        BrowserRunResult run(Path root, String htmlFile, String linkedJavaScript, TargetBinding binding);
    }

    record BrowserRunResult(
            VerificationVerdict verdict,
            List<String> facts,
            List<String> problems,
            List<String> limitations
    ) {
        BrowserRunResult {
            verdict = verdict == null ? VerificationVerdict.UNAVAILABLE : verdict;
            facts = facts == null ? List.of() : List.copyOf(facts);
            problems = problems == null ? List.of() : List.copyOf(problems);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
        }

        static BrowserRunResult verified(List<String> facts, List<String> limitations) {
            return new BrowserRunResult(VerificationVerdict.VERIFIED, facts, List.of(), limitations);
        }

        static BrowserRunResult failed(List<String> facts, List<String> problems, List<String> limitations) {
            return new BrowserRunResult(VerificationVerdict.FAILED, facts, problems, limitations);
        }

        static BrowserRunResult unavailable(String limitation) {
            return new BrowserRunResult(
                    VerificationVerdict.UNAVAILABLE,
                    List.of(),
                    List.of(),
                    limitation == null || limitation.isBlank() ? List.of("Browser behavior verifier was unavailable.")
                            : List.of(limitation.strip()));
        }
    }

    static VerificationReport verify(
            Path root,
            String request,
            StaticWebSelectorAnalyzer.Facts facts
    ) {
        return verify(root, request, facts, new HtmlUnitBrowserRunner());
    }

    static VerificationReport verify(
            Path root,
            String request,
            StaticWebSelectorAnalyzer.Facts facts,
            BrowserRunner runner
    ) {
        Optional<TargetBinding> maybeBinding = StaticWebInteractionVerifier.detectBinding(request);
        if (maybeBinding.isEmpty()) return VerificationReport.empty();
        TargetBinding binding = maybeBinding.get();
        VerificationClaim claim = new VerificationClaim(
                "static-web-interaction:" + binding.triggerSelector() + "->" + binding.outputSelector(),
                "Browser behavior " + binding.triggerSelector() + " -> " + binding.outputSelector() + ".",
                ProofKind.BROWSER_BEHAVIOR,
                binding,
                true);
        VerificationObligation obligation = new VerificationObligation(
                claim,
                Set.of(ProofKind.STATIC_INTERACTION_GUARD, ProofKind.BROWSER_BEHAVIOR),
                EvidenceAuthority.AUTHORITATIVE,
                binding);
        if (root == null || facts == null || facts.htmlFile().isBlank()) {
            return VerificationReport.ofClaim(new ClaimResult(
                    claim,
                    obligation,
                    VerificationVerdict.UNAVAILABLE,
                    ProofKind.BROWSER_BEHAVIOR,
                    EvidenceAuthority.AUTHORITATIVE,
                    EvidenceCoverage.SCOPED,
                    List.of(),
                    List.of(),
                    List.of("Browser behavior verification could not inspect the static web surface.")));
        }
        BrowserRunResult result = (runner == null ? new HtmlUnitBrowserRunner() : runner)
                .run(root.toAbsolutePath().normalize(), facts.htmlFile(), facts.js(), binding);
        ClaimResult claimResult = new ClaimResult(
                claim,
                obligation,
                result.verdict(),
                ProofKind.BROWSER_BEHAVIOR,
                EvidenceAuthority.AUTHORITATIVE,
                EvidenceCoverage.SCOPED,
                result.facts(),
                result.problems(),
                result.limitations());
        return VerificationReport.ofClaim(claimResult);
    }

    private static final class HtmlUnitBrowserRunner implements BrowserRunner {
        private static final long JAVASCRIPT_WAIT_MS = 250;

        @Override
        public BrowserRunResult run(Path root, String htmlFile, String linkedJavaScript, TargetBinding binding) {
            Path safeRoot = root == null ? null : root.toAbsolutePath().normalize();
            if (safeRoot == null || htmlFile == null || htmlFile.isBlank()) {
                return BrowserRunResult.unavailable("Browser behavior verifier did not receive a page path.");
            }
            Path htmlPath = safeRoot.resolve(htmlFile).toAbsolutePath().normalize();
            if (!htmlPath.startsWith(safeRoot)) {
                return BrowserRunResult.unavailable("Browser behavior verifier rejected a page outside the workspace.");
            }
            List<String> scriptErrors = new ArrayList<>();
            List<String> workspaceRequests = new ArrayList<>();
            try (WebClient client = new WorkspaceOnlyWebClient(safeRoot, workspaceRequests)) {
                client.getOptions().setJavaScriptEnabled(true);
                client.getOptions().setCssEnabled(false);
                client.getOptions().setDownloadImages(false);
                client.getOptions().setThrowExceptionOnScriptError(false);
                client.getOptions().setThrowExceptionOnFailingStatusCode(false);
                client.setJavaScriptErrorListener(new CapturingJavaScriptErrorListener(scriptErrors));

                HtmlPage page = client.getPage(localPageUrl(htmlFile));
                client.waitForBackgroundJavaScript(JAVASCRIPT_WAIT_MS);
                page.getElementById(id(binding.triggerSelector()));
                page.getElementById(id(binding.outputSelector()));
                String before = visibleText(page, id(binding.outputSelector()));
                click(page, id(binding.triggerSelector()));
                client.waitForBackgroundJavaScript(JAVASCRIPT_WAIT_MS);
                String after = visibleText(page, id(binding.outputSelector()));
                List<String> facts = new ArrayList<>();
                List<String> limitations = new ArrayList<>();
                boolean fallbackEvalChangedWithoutClickChange = false;
                facts.add("Browser behavior runner loaded `" + htmlFile + "` from the workspace.");
                facts.add("Browser behavior runner clicked `" + binding.triggerSelector()
                        + "` and observed `" + binding.outputSelector() + "`.");
                if (!workspaceRequests.isEmpty()) {
                    facts.add("Browser behavior runner requested workspace resources: "
                            + String.join(", ", workspaceRequests) + ".");
                }
                if (!changed(before, after) && linkedJavaScript != null && !linkedJavaScript.isBlank()) {
                    String beforeFallbackEval = visibleText(page, id(binding.outputSelector()));
                    FallbackClickObservation fallback = executeWorkspaceJavaScriptAndClick(
                            page,
                            linkedJavaScript,
                            id(binding.triggerSelector()),
                            id(binding.outputSelector()));
                    client.waitForBackgroundJavaScript(JAVASCRIPT_WAIT_MS);
                    String afterFallbackClick = fallback.afterClick();
                    if (afterFallbackClick.isBlank()) {
                        afterFallbackClick = visibleText(page, id(binding.outputSelector()));
                    }
                    before = fallback.afterEval();
                    after = afterFallbackClick;
                    fallbackEvalChangedWithoutClickChange = changed(beforeFallbackEval, before)
                            && !changed(before, after);
                    facts.add("Browser behavior runner executed the linked workspace JavaScript in the loaded page context.");
                    limitations.add("HtmlUnit browser runner did not observe the interaction before executing linked "
                            + "workspace JavaScript in-page; static linkage evidence covers the script reference.");
                }
                if (!scriptErrors.isEmpty()) {
                    return BrowserRunResult.failed(
                            facts,
                            scriptErrors.stream()
                                    .map(error -> "Browser behavior verifier observed JavaScript error: " + error)
                            .toList(),
                            limitations);
                }
                if (changed(before, after)) {
                    facts.add("Browser behavior verified `" + binding.triggerSelector()
                            + "` changed visible text on `" + binding.outputSelector() + "`.");
                    return BrowserRunResult.verified(facts, limitations);
                }
                if (fallbackEvalChangedWithoutClickChange) {
                    return BrowserRunResult.failed(
                            facts,
                            List.of("Browser behavior assertion failed: linked workspace JavaScript changed `"
                                    + binding.outputSelector()
                                    + "` before the fallback click, but clicking `"
                                    + binding.triggerSelector()
                                    + "` did not change it."),
                            limitations);
                }
                return BrowserRunResult.failed(
                        facts,
                        List.of("Browser behavior assertion failed: `" + binding.outputSelector()
                                + "` visible text did not change after clicking `" + binding.triggerSelector()
                                + "`."),
                        limitations);
            } catch (IOException | RuntimeException e) {
                return BrowserRunResult.unavailable(
                        "Browser behavior verifier could not execute the static page: " + safeMessage(e));
            }
        }

        private static URL localPageUrl(String htmlFile) throws MalformedURLException {
            try {
                return new URI("http", LOCAL_HOST, "/" + normalizeWebPath(htmlFile), null).toURL();
            } catch (URISyntaxException e) {
                throw new MalformedURLException("Invalid workspace page path: " + safeMessage(e));
            }
        }

        private static String normalizeWebPath(String path) {
            return path == null ? "" : path.replace('\\', '/');
        }
    }

    private static final class WorkspaceOnlyWebClient extends WebClient {
        private final Path root;
        private final List<String> workspaceRequests;

        WorkspaceOnlyWebClient(Path root, List<String> workspaceRequests) {
            super(BrowserVersion.CHROME);
            this.root = root;
            this.workspaceRequests = workspaceRequests == null ? new ArrayList<>() : workspaceRequests;
        }

        @Override
        public WebResponse loadWebResponse(WebRequest request) throws IOException {
            URL url = request == null ? null : request.getUrl();
            if (url == null) {
                throw new IOException("Blocked browser request with no URL.");
            }
            String protocol = url.getProtocol();
            if ("about".equalsIgnoreCase(protocol) || "data".equalsIgnoreCase(protocol)) {
                return super.loadWebResponse(request);
            }
            if (("http".equalsIgnoreCase(protocol) || "https".equalsIgnoreCase(protocol))
                    && LOCAL_HOST.equalsIgnoreCase(url.getHost())) {
                return workspaceResponse(request, url);
            }
            throw new IOException("Blocked non-workspace browser request: " + redactedUrl(url));
        }

        private WebResponse workspaceResponse(WebRequest request, URL url) throws IOException {
            Path requested = workspacePath(url);
            if (!requested.startsWith(root)) {
                throw new IOException("Blocked non-workspace browser request: " + redactedUrl(url));
            }
            record(requested);
            if (!Files.exists(requested) || Files.isDirectory(requested)) {
                WebResponseData data = new WebResponseData(
                        ("Missing workspace resource: " + root.relativize(requested)).getBytes(StandardCharsets.UTF_8),
                        404,
                        "Not Found",
                        List.of(new NameValuePair(HttpHeader.CONTENT_TYPE, "text/plain; charset=UTF-8")));
                return new WebResponse(data, request, 0);
            }
            byte[] body = Files.readAllBytes(requested);
            WebResponseData data = new WebResponseData(
                    body,
                    200,
                    "OK",
                    List.of(new NameValuePair(HttpHeader.CONTENT_TYPE, contentType(requested))));
            return new WebResponse(data, request, 0);
        }

        private Path workspacePath(URL url) throws IOException {
            String decoded;
            try {
                decoded = url.toURI().getPath();
            } catch (URISyntaxException e) {
                throw new IOException("Invalid workspace browser request URL.");
            }
            String relative = decoded == null ? "" : decoded.startsWith("/") ? decoded.substring(1) : decoded;
            return root.resolve(relative).toAbsolutePath().normalize();
        }

        private void record(Path requested) {
            try {
                if (requested.startsWith(root)) {
                    workspaceRequests.add("`" + root.relativize(requested).toString().replace('\\', '/') + "`");
                }
            } catch (IllegalArgumentException e) {
                // Request accounting is evidence-only; allow/deny remains authoritative.
            }
        }

        private static String contentType(Path path) {
            String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase();
            if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html; charset=UTF-8";
            if (name.endsWith(".js")) return "text/javascript; charset=UTF-8";
            if (name.endsWith(".css")) return "text/css; charset=UTF-8";
            if (name.endsWith(".json")) return "application/json; charset=UTF-8";
            return "application/octet-stream";
        }
    }

    private static final class CapturingJavaScriptErrorListener implements JavaScriptErrorListener {
        private final List<String> errors;

        CapturingJavaScriptErrorListener(List<String> errors) {
            this.errors = errors;
        }

        @Override
        public void scriptException(HtmlPage page, ScriptException scriptException) {
            errors.add(safeMessage(scriptException));
        }

        @Override
        public void timeoutError(HtmlPage page, long allowedTime, long executionTime) {
            errors.add("JavaScript timeout after " + executionTime + " ms.");
        }

        @Override
        public void malformedScriptURL(HtmlPage page, String url, MalformedURLException malformedURLException) {
            errors.add("Malformed script URL: " + redactedUrl(url));
        }

        @Override
        public void loadScriptError(HtmlPage page, URL scriptUrl, Exception exception) {
            errors.add("Script load failed for " + redactedUrl(scriptUrl) + ": " + safeMessage(exception));
        }

        @Override
        public void warn(String message, String sourceName, int line, String lineSource, int lineOffset) {
            // HtmlUnit warnings are not proof of failed user-visible behavior.
        }
    }

    private static void click(HtmlPage page, String id) throws IOException {
        page.getElementById(id).click();
    }

    private record FallbackClickObservation(String afterEval, String afterClick) {}

    private static FallbackClickObservation executeWorkspaceJavaScriptAndClick(
            HtmlPage page,
            String linkedJavaScript,
            String triggerId,
            String outputId
    ) {
        Object result = page.executeJavaScript("""
                (function() {
                %s
                  var outputAfterEval = document.getElementById('%s');
                  var textAfterEval = outputAfterEval ? (outputAfterEval.innerText || outputAfterEval.textContent || '') : '';
                  var el = document.getElementById('%s');
                  if (el) {
                    if (typeof el.click === 'function') {
                      el.click();
                    } else {
                      var event = document.createEvent('MouseEvents');
                      event.initEvent('click', true, true);
                      el.dispatchEvent(event);
                    }
                  }
                  var output = document.getElementById('%s');
                  var textAfterClick = output ? (output.innerText || output.textContent || '') : '';
                  return String(textAfterEval) + '\\u0000' + String(textAfterClick);
                })();
                """.formatted(linkedJavaScript, jsString(outputId), jsString(triggerId), jsString(outputId)))
                .getJavaScriptResult();
        if (result == null) return new FallbackClickObservation("", "");
        String text = result.toString();
        if ("undefined".equalsIgnoreCase(text)) return new FallbackClickObservation("", "");
        String[] parts = text.split("\u0000", -1);
        return new FallbackClickObservation(
                parts.length > 0 ? parts[0].strip() : "",
                parts.length > 1 ? parts[1].strip() : "");
    }

    private static String visibleText(HtmlPage page, String id) {
        Object result = page.executeJavaScript("""
                (function() {
                  var el = document.getElementById('%s');
                  if (!el) return '';
                  return el.innerText || el.textContent || '';
                })();
                """.formatted(jsString(id))).getJavaScriptResult();
        if (result != null) {
            String text = result.toString();
            if (!text.isBlank() && !"undefined".equalsIgnoreCase(text)) {
                return text.strip();
            }
        }
        DomElement element = page.getElementById(id);
        if (element == null) return "";
        String text = element.asNormalizedText();
        if (text == null || text.isBlank()) {
            text = element.getTextContent();
        }
        return text == null ? "" : text.strip();
    }

    private static boolean changed(String before, String after) {
        return after != null && !after.isBlank() && !after.equals(before == null ? "" : before);
    }

    private static String id(String selector) {
        if (selector == null) return "";
        String out = selector.strip();
        return out.startsWith("#") ? out.substring(1) : out;
    }

    private static String jsString(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String redactedUrl(URL url) {
        if (url == null) return "<unknown>";
        return url.getProtocol() + "://<redacted>";
    }

    private static String redactedUrl(String url) {
        if (url == null || url.isBlank()) return "<unknown>";
        int colon = url.indexOf(':');
        return colon > 0 ? url.substring(0, colon) + "://<redacted>" : "<redacted>";
    }

    private static String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return throwable == null ? "unknown error" : throwable.getClass().getSimpleName();
        }
        return throwable.getMessage().replace('\r', ' ').replace('\n', ' ').strip();
    }
}
