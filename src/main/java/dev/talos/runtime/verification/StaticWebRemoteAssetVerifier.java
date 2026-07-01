package dev.talos.runtime.verification;

import dev.talos.runtime.task.TaskContract;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Static-web verifier for remote asset references in otherwise local website tasks. */
final class StaticWebRemoteAssetVerifier {
    private static final Pattern REMOTE_URL = Pattern.compile(
            "\\bhttps?://[^\\s'\"()<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern HTML_TAG = Pattern.compile("(?is)<([a-z][a-z0-9-]*)\\b([^>]*)>");
    private static final Pattern HTML_REMOTE_ATTR = Pattern.compile(
            "(?i)\\b(?:src|href|poster)\\s*=\\s*(['\"])(https?://.*?)\\1");
    private static final Set<String> HTML_ASSET_TAGS = Set.of(
            "audio", "embed", "iframe", "img", "input", "link", "script", "source", "track", "video");

    private StaticWebRemoteAssetVerifier() {}

    record Result(VerificationReport report, List<String> blockingProblems) {
        Result {
            report = report == null ? VerificationReport.empty() : report;
            blockingProblems = blockingProblems == null ? List.of() : List.copyOf(blockingProblems);
        }

        static Result empty() {
            return new Result(VerificationReport.empty(), List.of());
        }
    }

    static Result verify(TaskContract contract, StaticWebSelectorAnalyzer.Facts facts) {
        if (facts == null) return Result.empty();
        String request = contract == null ? "" : contract.originalUserRequest();
        boolean requiresLocalAssets = explicitlyRequiresLocalAssets(request);
        if (!requiresLocalAssets && explicitlyAllowsRemoteAssets(request)) return Result.empty();

        List<RemoteReference> references = remoteReferences(facts);
        if (references.isEmpty()) return Result.empty();

        String rendered = renderReferences(references);
        String limitation = "Remote static-web asset references were not fetched or verified for local/offline "
                + "behavior: " + rendered + ".";
        VerifierResult verifierResult = new VerifierResult(
                null,
                ProofKind.STATIC_COHERENCE,
                EvidenceAuthority.SUPPLEMENTAL,
                EvidenceCoverage.SCOPED,
                VerificationVerdict.UNVERIFIED,
                List.of(),
                List.of(),
                List.of(limitation));
        VerificationReport report = new VerificationReport(
                List.of(),
                List.of(verifierResult),
                List.of(),
                List.of(),
                List.of(limitation));
        if (!requiresLocalAssets) {
            return new Result(report, List.of());
        }
        String problem = "Explicit offline/static-web request contains remote asset references: "
                + rendered + ".";
        return new Result(report, List.of(problem));
    }

    private static List<RemoteReference> remoteReferences(StaticWebSelectorAnalyzer.Facts facts) {
        LinkedHashSet<RemoteReference> out = new LinkedHashSet<>();
        collectHtmlAssetReferences(out, facts.htmlFile(), facts.html());
        collectGenericRemoteReferences(out, facts.cssFile(), stripCssComments(facts.css()));
        collectGenericRemoteReferences(out, facts.jsFile(), facts.js());
        return List.copyOf(out);
    }

    private static void collectHtmlAssetReferences(
            LinkedHashSet<RemoteReference> out,
            String file,
            String html
    ) {
        if (html == null || html.isBlank()) return;
        Matcher tagMatcher = HTML_TAG.matcher(html);
        while (tagMatcher.find()) {
            String tag = tagMatcher.group(1) == null
                    ? ""
                    : tagMatcher.group(1).toLowerCase(Locale.ROOT);
            if (!HTML_ASSET_TAGS.contains(tag)) continue;
            String attributes = tagMatcher.group(2) == null ? "" : tagMatcher.group(2);
            Matcher attrMatcher = HTML_REMOTE_ATTR.matcher(attributes);
            while (attrMatcher.find()) {
                add(out, file, attrMatcher.group(2));
            }
        }
    }

    private static void collectGenericRemoteReferences(
            LinkedHashSet<RemoteReference> out,
            String file,
            String text
    ) {
        if (text == null || text.isBlank()) return;
        Matcher matcher = REMOTE_URL.matcher(text);
        while (matcher.find()) {
            add(out, file, matcher.group());
        }
    }

    private static void add(LinkedHashSet<RemoteReference> out, String file, String rawUrl) {
        String safeUrl = safeUrl(rawUrl);
        if (safeUrl.isBlank()) return;
        out.add(new RemoteReference(file == null ? "" : file, safeUrl));
    }

    private static String stripCssComments(String css) {
        if (css == null || css.isBlank()) return "";
        return CSS_BLOCK_COMMENT.matcher(css).replaceAll("");
    }

    private static String renderReferences(List<RemoteReference> references) {
        List<String> rendered = new ArrayList<>();
        int max = Math.min(3, references.size());
        for (int i = 0; i < max; i++) {
            RemoteReference ref = references.get(i);
            rendered.add("`" + ref.file() + "` -> `" + ref.url() + "`");
        }
        if (references.size() > max) {
            rendered.add("... " + (references.size() - max) + " more");
        }
        return String.join(", ", rendered);
    }

    private static String safeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return "";
        String trimmed = rawUrl.strip();
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return trimmedWithoutQuery(trimmed);
            String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "" : uri.getRawPath();
            String out = scheme.toLowerCase(Locale.ROOT) + "://" + host + path;
            return out.length() <= 160 ? out : out.substring(0, 157) + "...";
        } catch (IllegalArgumentException e) {
            return trimmedWithoutQuery(trimmed);
        }
    }

    private static String trimmedWithoutQuery(String value) {
        int query = value.indexOf('?');
        int fragment = value.indexOf('#');
        int end = value.length();
        if (query >= 0) end = query;
        if (fragment >= 0) end = Math.min(end, fragment);
        String out = value.substring(0, end);
        return out.length() <= 160 ? out : out.substring(0, 157) + "...";
    }

    private static boolean explicitlyRequiresLocalAssets(String request) {
        String lower = normalize(request);
        return lower.contains("offline")
                || lower.contains("self-contained")
                || lower.contains("self contained")
                || lower.contains("local-only")
                || lower.contains("local only")
                || lower.contains("only local")
                || lower.contains("no remote")
                || lower.contains("no external")
                || lower.contains("do not use remote")
                || lower.contains("don't use remote")
                || lower.contains("without remote")
                || lower.contains("without external");
    }

    private static boolean explicitlyAllowsRemoteAssets(String request) {
        String lower = normalize(request);
        return lower.contains("use remote assets")
                || lower.contains("remote assets are ok")
                || lower.contains("remote assets are okay")
                || lower.contains("external assets are ok")
                || lower.contains("external assets are okay")
                || lower.contains("use external assets")
                || lower.contains("cdn assets")
                || lower.contains("use a cdn")
                || lower.contains("use unsplash")
                || lower.contains("remote background image");
    }

    private static String normalize(String request) {
        return request == null ? "" : request.toLowerCase(Locale.ROOT);
    }

    private record RemoteReference(String file, String url) {}
}
