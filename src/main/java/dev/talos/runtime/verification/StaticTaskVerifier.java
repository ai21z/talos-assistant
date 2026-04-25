package dev.talos.runtime.verification;

import dev.talos.runtime.TemplatePlaceholderGuard;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.tools.VerificationStatus;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intent-light post-apply verifier for local static workspace facts.
 *
 * <p>This is deliberately narrower than the future TaskContract verifier. It
 * verifies observable post-mutation facts the current runtime already knows:
 * successful mutating targets, file-level verification metadata, placeholder
 * debris, and selector coherence for small HTML/CSS/JS workspaces when the
 * user asked for selector/linkage repair.
 */
public final class StaticTaskVerifier {

    private StaticTaskVerifier() {}

    private static final Set<String> SMALL_WORKSPACE_WEB_EXTS = Set.of(
            ".html", ".htm", ".css", ".js", ".ts", ".jsx", ".tsx"
    );

    private static final Pattern HTML_CLASS_ATTR = Pattern.compile(
            "\\bclass\\s*=\\s*(['\"])(.*?)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_ID_ATTR = Pattern.compile(
            "\\bid\\s*=\\s*(['\"])(.*?)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_LINK_HREF = Pattern.compile(
            "<link\\b[^>]*\\bhref\\s*=\\s*(['\"])(.*?)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_SCRIPT_SRC = Pattern.compile(
            "<script\\b[^>]*\\bsrc\\s*=\\s*(['\"])(.*?)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_CLASS_SELECTOR = Pattern.compile("\\.([A-Za-z_][A-Za-z0-9_-]*)");
    private static final Pattern CSS_ID_SELECTOR = Pattern.compile("#([A-Za-z_][A-Za-z0-9_-]*)");
    private static final Pattern CSS_SELECTOR_PRELUDE = Pattern.compile("(?s)([^{}]+)\\{");
    private static final Pattern JS_QUERY_SELECTOR = Pattern.compile(
            "querySelector(?:All)?\\s*\\(\\s*['\"]([#.][A-Za-z_][A-Za-z0-9_-]*)['\"]\\s*\\)");
    private static final Pattern JS_GET_BY_ID = Pattern.compile(
            "getElementById\\s*\\(\\s*['\"]([A-Za-z_][A-Za-z0-9_-]*)['\"]\\s*\\)");
    private static final Pattern JS_GET_BY_CLASS = Pattern.compile(
            "getElementsByClassName\\s*\\(\\s*['\"]([A-Za-z_][A-Za-z0-9_-]*)['\"]\\s*\\)");

    public static TaskVerificationResult verify(
            Path workspace,
            String userRequest,
            ToolCallLoop.LoopResult loopResult,
            int extraMutationSuccesses
    ) {
        if (loopResult == null) {
            return TaskVerificationResult.notRun("No tool-loop result was available.");
        }

        List<ToolCallLoop.ToolOutcome> outcomes = loopResult.toolOutcomes();
        List<ToolCallLoop.ToolOutcome> successfulMutations = outcomes.stream()
                .filter(ToolCallLoop.ToolOutcome::mutating)
                .filter(ToolCallLoop.ToolOutcome::success)
                .toList();
        int totalMutationSuccesses = successfulMutations.size() + Math.max(0, extraMutationSuccesses);
        if (totalMutationSuccesses <= 0) {
            return TaskVerificationResult.notRun("No successful mutation was available to verify.");
        }
        if (workspace == null) {
            return TaskVerificationResult.unavailable(
                    "Workspace path was unavailable for post-apply verification.",
                    List.of(),
                    List.of("workspace path missing"));
        }
        if (successfulMutations.isEmpty()) {
            return TaskVerificationResult.unavailable(
                    "A mutation succeeded outside the structured tool-outcome path, so target files could not be verified.",
                    List.of(),
                    List.of("structured mutation targets unavailable"));
        }

        Path root = workspace.toAbsolutePath().normalize();
        List<String> facts = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Set<String> mutatedPaths = new LinkedHashSet<>();

        for (ToolCallLoop.ToolOutcome outcome : successfulMutations) {
            String pathHint = normalizePath(outcome.pathHint());
            if (pathHint.isBlank()) {
                problems.add(outcome.toolName() + " succeeded but did not expose a target path.");
                continue;
            }
            mutatedPaths.add(pathHint);
            verifyMutationTarget(root, pathHint, outcome.fileVerificationStatus(), facts, problems);
        }

        if (shouldCheckSelectorCoherence(userRequest)) {
            verifySmallWebWorkspace(root, facts, problems);
        }

        if (!problems.isEmpty()) {
            return TaskVerificationResult.failed(firstProblemSummary(problems), facts, problems);
        }
        return TaskVerificationResult.passed(
                "Post-apply static checks passed for " + mutatedPaths.size() + " mutated target(s).",
                facts);
    }

    private static void verifyMutationTarget(
            Path root,
            String pathHint,
            VerificationStatus fileVerificationStatus,
            List<String> facts,
            List<String> problems
    ) {
        Path target;
        try {
            target = root.resolve(pathHint).normalize();
        } catch (InvalidPathException e) {
            problems.add(pathHint + ": target path is invalid (" + e.getMessage() + ")");
            return;
        }
        if (!target.startsWith(root)) {
            problems.add(pathHint + ": target path resolves outside the workspace.");
            return;
        }
        if (!Files.isRegularFile(target)) {
            problems.add(pathHint + ": mutated target is not a readable file after apply.");
            return;
        }
        String content;
        try {
            content = Files.readString(target);
        } catch (Exception e) {
            problems.add(pathHint + ": mutated target could not be read after apply (" + e.getMessage() + ")");
            return;
        }
        if (content.isBlank()) {
            problems.add(pathHint + ": mutated target is empty after apply.");
            return;
        }
        if (TemplatePlaceholderGuard.looksLikeTemplatePlaceholder(content)) {
            problems.add(pathHint + ": mutated target contains only a template placeholder.");
            return;
        }
        if (fileVerificationStatus != null && !fileVerificationStatus.acceptable()) {
            problems.add(pathHint + ": file-level verification reported " + fileVerificationStatus.label() + ".");
            return;
        }
        facts.add(pathHint + ": mutated target exists and is readable.");
    }

    private static void verifySmallWebWorkspace(Path root, List<String> facts, List<String> problems) {
        List<String> primary = obviousPrimaryFiles(root);
        if (primary.size() < 3) {
            problems.add("selector coherence could not be checked because the workspace does not expose a small HTML/CSS/JS surface.");
            return;
        }
        String htmlFile = pickPrimary(primary, ".html", ".htm");
        String cssFile = pickPrimary(primary, ".css");
        String jsFile = pickPrimary(primary, ".js");
        if (htmlFile == null || cssFile == null || jsFile == null) {
            problems.add("selector coherence could not be checked because HTML, CSS, and JavaScript primary files were not all present.");
            return;
        }

        SelectorFacts selectors = selectorFacts(root, htmlFile, cssFile, jsFile);
        if (selectors == null) {
            problems.add("selector coherence could not be checked because primary web files could not be read.");
            return;
        }

        problems.addAll(selectors.linkageProblems());
        problems.addAll(selectors.selectorProblems());
        if (selectors.linkageProblems().isEmpty() && selectors.selectorProblems().isEmpty()) {
            facts.add("HTML/CSS/JS selector coherence passed for " + htmlFile + ", " + cssFile + ", and " + jsFile + ".");
        }
    }

    public static List<String> obviousPrimaryFiles(Path workspace) {
        if (workspace == null || !Files.isDirectory(workspace)) return List.of();
        try {
            List<Path> files = new ArrayList<>();
            try (var stream = Files.list(workspace)) {
                stream.filter(Files::isRegularFile).forEach(files::add);
            }
            if (files.isEmpty() || files.size() > 5) return List.of();
            List<String> out = new ArrayList<>();
            for (Path file : files) {
                String name = file.getFileName() == null ? "" : file.getFileName().toString();
                if (name.isBlank() || name.startsWith(".")) continue;
                String lower = name.toLowerCase(Locale.ROOT);
                int dot = lower.lastIndexOf('.');
                String ext = dot >= 0 ? lower.substring(dot) : "";
                if (!SMALL_WORKSPACE_WEB_EXTS.contains(ext)) return List.of();
                out.add(name.replace('\\', '/'));
            }
            return out.size() >= 2 ? List.copyOf(out) : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    public static List<String> missingPrimaryReads(Path workspace, Collection<String> readPaths) {
        List<String> primary = obviousPrimaryFiles(workspace);
        if (primary.isEmpty()) return List.of();
        Set<String> read = new LinkedHashSet<>();
        if (readPaths != null) {
            for (String p : readPaths) {
                if (p == null || p.isBlank()) continue;
                String normalized = p.replace('\\', '/');
                int slash = normalized.lastIndexOf('/');
                read.add(slash >= 0 ? normalized.substring(slash + 1) : normalized);
            }
        }
        List<String> missing = new ArrayList<>();
        for (String file : primary) {
            if (!read.contains(file)) missing.add(file);
        }
        return List.copyOf(missing);
    }

    public static String renderSelectorInspection(Path workspace, Collection<String> readPaths) {
        List<String> missing = missingPrimaryReads(workspace, readPaths);
        if (!missing.isEmpty()) return null;
        List<String> primary = obviousPrimaryFiles(workspace);
        String htmlFile = pickPrimary(primary, ".html", ".htm");
        String cssFile = pickPrimary(primary, ".css");
        String jsFile = pickPrimary(primary, ".js");
        if (htmlFile == null || cssFile == null || jsFile == null) return null;
        SelectorFacts facts = selectorFacts(workspace.toAbsolutePath().normalize(), htmlFile, cssFile, jsFile);
        return facts == null ? null : facts.renderInspection();
    }

    private static boolean shouldCheckSelectorCoherence(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT);
        if (lower.contains("selector") || lower.contains(".cta-button") || lower.contains("#cta-button")) {
            return true;
        }
        boolean namesWebParts = lower.contains("html")
                && (lower.contains("css") || lower.contains("stylesheet"))
                && (lower.contains("javascript") || lower.contains("script.js") || lower.contains("js"));
        boolean asksAlignment = lower.contains("match")
                || lower.contains("mismatch")
                || lower.contains("align")
                || lower.contains("linkage")
                || lower.contains("wire")
                || lower.contains("reference");
        return namesWebParts && asksAlignment;
    }

    private static SelectorFacts selectorFacts(Path root, String htmlFile, String cssFile, String jsFile) {
        try {
            String html = Files.readString(root.resolve(htmlFile));
            String css = Files.readString(root.resolve(cssFile));
            String js = Files.readString(root.resolve(jsFile));
            return new SelectorFacts(
                    htmlFile,
                    cssFile,
                    jsFile,
                    extractMatches(html, HTML_CLASS_ATTR, true),
                    extractMatches(html, HTML_ID_ATTR, false),
                    extractCssSelectors(css, CSS_CLASS_SELECTOR),
                    extractCssSelectors(css, CSS_ID_SELECTOR),
                    extractJsClasses(js),
                    extractJsIds(js),
                    extractLinkedAssets(html, HTML_LINK_HREF, ".css"),
                    extractLinkedAssets(html, HTML_SCRIPT_SRC, ".js"),
                    existingFileNames(root));
        } catch (Exception e) {
            return null;
        }
    }

    private record SelectorFacts(
            String htmlFile,
            String cssFile,
            String jsFile,
            Set<String> htmlClasses,
            Set<String> htmlIds,
            Set<String> cssClasses,
            Set<String> cssIds,
            Set<String> jsClasses,
            Set<String> jsIds,
            Set<String> linkedCssFiles,
            Set<String> linkedJsFiles,
            Set<String> existingFileNames
    ) {
        List<String> selectorProblems() {
            List<String> out = new ArrayList<>();
            Set<String> cssMissingClasses = new LinkedHashSet<>(cssClasses);
            cssMissingClasses.removeAll(htmlClasses);
            Set<String> jsMissingClasses = new LinkedHashSet<>(jsClasses);
            jsMissingClasses.removeAll(htmlClasses);
            Set<String> cssMissingIds = new LinkedHashSet<>(cssIds);
            cssMissingIds.removeAll(htmlIds);
            Set<String> jsMissingIds = new LinkedHashSet<>(jsIds);
            jsMissingIds.removeAll(htmlIds);

            if (!cssMissingClasses.isEmpty()) {
                out.add("CSS references missing class selectors: " + renderSelectors(cssMissingClasses, "."));
            }
            if (!cssMissingIds.isEmpty()) {
                out.add("CSS references missing ID selectors: " + renderSelectors(cssMissingIds, "#"));
            }
            if (!jsMissingClasses.isEmpty()) {
                out.add("JavaScript references missing class selectors: " + renderSelectors(jsMissingClasses, "."));
            }
            if (!jsMissingIds.isEmpty()) {
                out.add("JavaScript references missing IDs: " + renderSelectors(jsMissingIds, "#"));
            }
            return out;
        }

        List<String> linkageProblems() {
            List<String> out = new ArrayList<>();
            for (String css : linkedCssFiles) {
                if (!existingFileNames.contains(css)) {
                    out.add("HTML references missing CSS file: `" + css + "`");
                }
            }
            for (String js : linkedJsFiles) {
                if (!existingFileNames.contains(js)) {
                    out.add("HTML references missing JavaScript file: `" + js + "`");
                }
            }
            return out;
        }

        String renderInspection() {
            StringBuilder out = new StringBuilder();
            out.append("I checked the selectors against the actual workspace files:\n\n");
            out.append("- HTML: `").append(htmlFile).append("`\n");
            out.append("- CSS: `").append(cssFile).append("`\n");
            out.append("- JavaScript: `").append(jsFile).append("`\n\n");

            out.append("Observed in HTML:\n");
            out.append("- Classes: ").append(renderObserved(htmlClasses)).append('\n');
            out.append("- IDs: ").append(renderObserved(htmlIds)).append("\n\n");

            List<String> mismatches = new ArrayList<>();
            mismatches.addAll(selectorProblems());
            if (mismatches.isEmpty()) {
                out.append("Conclusion: I did not find selector mismatches in these files.");
            } else {
                out.append("Mismatches found:\n");
                for (String mismatch : mismatches) {
                    out.append("- ").append(mismatch).append('\n');
                }
            }
            return out.toString().stripTrailing();
        }
    }

    private static Set<String> extractMatches(String text, Pattern pattern, boolean splitOnWhitespace) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return out;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(2);
            if (value == null || value.isBlank()) continue;
            if (splitOnWhitespace) {
                for (String token : value.trim().split("\\s+")) {
                    if (!token.isBlank()) out.add(token);
                }
            } else {
                out.add(value.trim());
            }
        }
        return out;
    }

    private static Set<String> extractCssSelectors(String css, Pattern selectorPattern) {
        Set<String> out = new LinkedHashSet<>();
        if (css == null || css.isBlank()) return out;
        Matcher preludeMatcher = CSS_SELECTOR_PRELUDE.matcher(css);
        while (preludeMatcher.find()) {
            String prelude = preludeMatcher.group(1);
            if (prelude == null || prelude.isBlank()) continue;
            Matcher selectorMatcher = selectorPattern.matcher(prelude);
            while (selectorMatcher.find()) {
                String value = selectorMatcher.group(1);
                if (value != null && !value.isBlank()) out.add(value.trim());
            }
        }
        return out;
    }

    private static Set<String> extractJsClasses(String js) {
        Set<String> out = new LinkedHashSet<>();
        if (js == null || js.isBlank()) return out;
        Matcher qs = JS_QUERY_SELECTOR.matcher(js);
        while (qs.find()) {
            String selector = qs.group(1);
            if (selector != null && selector.startsWith(".")) out.add(selector.substring(1));
        }
        Matcher gcn = JS_GET_BY_CLASS.matcher(js);
        while (gcn.find()) {
            String cls = gcn.group(1);
            if (cls != null && !cls.isBlank()) out.add(cls);
        }
        return out;
    }

    private static Set<String> extractJsIds(String js) {
        Set<String> out = new LinkedHashSet<>();
        if (js == null || js.isBlank()) return out;
        Matcher qs = JS_QUERY_SELECTOR.matcher(js);
        while (qs.find()) {
            String selector = qs.group(1);
            if (selector != null && selector.startsWith("#")) out.add(selector.substring(1));
        }
        Matcher gid = JS_GET_BY_ID.matcher(js);
        while (gid.find()) {
            String id = gid.group(1);
            if (id != null && !id.isBlank()) out.add(id);
        }
        return out;
    }

    private static Set<String> extractLinkedAssets(String html, Pattern pattern, String extension) {
        Set<String> out = new LinkedHashSet<>();
        if (html == null || html.isBlank()) return out;
        Matcher matcher = pattern.matcher(html);
        while (matcher.find()) {
            String value = matcher.group(2);
            if (value == null || value.isBlank()) continue;
            String normalized = value.replace('\\', '/').strip();
            int query = normalized.indexOf('?');
            if (query >= 0) normalized = normalized.substring(0, query);
            int hash = normalized.indexOf('#');
            if (hash >= 0) normalized = normalized.substring(0, hash);
            if (!normalized.toLowerCase(Locale.ROOT).endsWith(extension)) continue;
            int slash = normalized.lastIndexOf('/');
            out.add(slash >= 0 ? normalized.substring(slash + 1) : normalized);
        }
        return out;
    }

    private static Set<String> existingFileNames(Path root) {
        Set<String> out = new LinkedHashSet<>();
        try (var stream = Files.list(root)) {
            stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName() == null ? "" : path.getFileName().toString())
                    .filter(name -> !name.isBlank())
                    .forEach(out::add);
        } catch (Exception ignored) {
            // Linkage verification will fail elsewhere if primary files cannot be read.
        }
        return out;
    }

    private static String pickPrimary(List<String> files, String... exts) {
        for (String file : files) {
            String lower = file.toLowerCase(Locale.ROOT);
            for (String ext : exts) {
                if (lower.endsWith(ext)) return file;
            }
        }
        return null;
    }

    private static String normalizePath(String path) {
        if (path == null) return "";
        String normalized = path.replace('\\', '/');
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.startsWith("./") && normalized.length() > 2) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static String firstProblemSummary(List<String> problems) {
        if (problems == null || problems.isEmpty()) return "Static verification failed.";
        String first = problems.get(0);
        if (first.length() > 220) first = first.substring(0, 217) + "...";
        return first;
    }

    private static String renderObserved(Set<String> values) {
        if (values == null || values.isEmpty()) return "none";
        return values.stream().sorted().map(v -> "`" + v + "`").reduce((a, b) -> a + ", " + b).orElse("none");
    }

    private static String renderSelectors(Set<String> values, String prefix) {
        return values.stream().sorted().map(v -> "`" + prefix + v + "`")
                .reduce((a, b) -> a + ", " + b).orElse("none");
    }
}
