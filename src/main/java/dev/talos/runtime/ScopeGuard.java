package dev.talos.runtime;

import java.util.Set;

/**
 * Narrow, lexical trust-guard for mutating tool calls.
 *
 * <p>Driven directly by the real Talos CLI transcript
 * ({@code test-output.txt}, Turns 3 and 5): the user asked for a website
 * redesign of {@code index.html}, and the model wrote
 * {@code math_operations.py} / {@code linear_regression.py} instead.
 * Nothing in the existing runtime audited whether the <em>target</em> of
 * a {@code write_file} / {@code edit_file} call even loosely matched the
 * user's current request.
 *
 * <p>This class answers one narrow question:
 * <em>for a mutating tool call, does the target path look obviously
 * unrelated to what the user just asked for?</em>
 *
 * <p><b>Deliberately lexical, not semantic.</b> We only want to catch
 * the "obvious wrong file-type during a clearly-scoped request" shape
 * seen in the transcript. We do <b>not</b> try to understand the user's
 * intent. A request that does not look web-scoped (no markers) produces
 * no warning regardless of target, so the guard is safe by default.
 *
 * <p><b>Posture: warn, do not block.</b> The caller surfaces a warning
 * ({@link dev.talos.tools.ToolProgressSink}, log, and a diagnostic
 * prefix in the tool-result fed back to the model) but still executes
 * the call after the normal approval gate. This matches the existing
 * annotate-first posture used by R2/N3.
 */
public final class ScopeGuard {

    private ScopeGuard() {}

    /**
     * Phrases in the user's latest request that clearly scope the task
     * to web/frontend work. Kept tight and anchored to the real transcript
     * wording ("this site", "look and feel", "redesign", "index.html").
     *
     * <p>Matched case-insensitively. Substring match is intentional:
     * a request containing "redesign the page" or "change the look and
     * feel" fires, while a request like "explain this code" does not.
     */
    private static final Set<String> WEB_REQUEST_MARKERS = Set.of(
            "this site",
            "this website",
            "this page",
            "this webpage",
            "the site",
            "the website",
            "the page",
            "the webpage",
            "index.html",
            "look and feel",
            "redesign",
            "re-design",
            "restyle",
            "re-style",
            "homepage",
            "landing page",
            "frontend",
            "front-end",
            "web page",
            "webpage",
            "bmi calculator" // transcript-anchored (user's concrete UI task)
    );

    /**
     * File extensions considered on-scope for a web/frontend request.
     *
     * <p>A mutating write to any path with an extension outside this set,
     * during a web-scoped request, is what fires the guard. The set is
     * intentionally generous: we include {@code .md}, {@code .txt},
     * {@code .json}, and {@code .xml} because realistic web projects
     * ship those routinely; we exclude obviously-unrelated languages
     * like {@code .py}, {@code .java}, {@code .go}, {@code .rb} which
     * matched the transcript drift exactly.
     */
    private static final Set<String> WEB_SAFE_EXTENSIONS = Set.of(
            "html", "htm",
            "css", "scss", "sass", "less",
            "js", "mjs", "cjs", "ts", "tsx", "jsx",
            "svg", "png", "jpg", "jpeg", "gif", "webp", "ico", "avif",
            "json", "webmanifest",
            "xml",
            "md", "markdown",
            "txt",
            "woff", "woff2", "ttf", "otf", "eot"
    );

    /**
     * True iff {@code userRequest} contains at least one web-scope marker
     * (see {@link #WEB_REQUEST_MARKERS}). Package-private for direct testing.
     */
    public static boolean looksLikeWebScopedRequest(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase();
        for (String marker : WEB_REQUEST_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    /**
     * True iff the mutating-tool {@code targetPath} looks obviously
     * off-scope for the given {@code userRequest}.
     *
     * <p>Returns {@code false} (no warning) when:
     * <ul>
     *   <li>{@code targetPath} is null/blank, or</li>
     *   <li>the user request does not look web-scoped, or</li>
     *   <li>the target path has no extension (could be a Makefile,
     *       Dockerfile, etc. - out of scope for this narrow guard), or</li>
     *   <li>the extension is in the web allow-list.</li>
     * </ul>
     *
     * <p>Returns {@code true} only when the user request is clearly
     * web-scoped AND the target file's extension is outside the web
     * allow-list - the exact failure shape observed in the transcript.
     */
    public static boolean looksLikeOffScopeMutationTarget(String userRequest, String targetPath) {
        if (targetPath == null || targetPath.isBlank()) return false;
        if (!looksLikeWebScopedRequest(userRequest)) return false;

        String base = basename(targetPath);
        int dot = base.lastIndexOf('.');
        if (dot <= 0) return false; // no extension - narrow guard stays silent
        String ext = base.substring(dot + 1).toLowerCase();
        return !WEB_SAFE_EXTENSIONS.contains(ext);
    }

    /**
     * Short, user-facing warning message for an off-scope mutating target.
     * Intended for the {@link dev.talos.tools.ToolProgressSink} warning
     * channel and for the diagnostic prefix fed back to the model.
     */
    public static String warningMessage(String userRequest, String targetPath) {
        String anchor = userRequest == null ? "" : userRequest.strip();
        if (anchor.length() > 120) anchor = anchor.substring(0, 120) + "…";
        return "scope: target `" + targetPath + "` looks unrelated to the current task: «"
                + anchor + "»";
    }

    private static String basename(String path) {
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }
}

