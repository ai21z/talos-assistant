package dev.talos.runtime.verification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class StaticWebSelectorAnalyzer {

    private static final Pattern HTML_CLASS_ATTR = Pattern.compile(
            "\\bclass\\s*=\\s*(['\"])(.*?)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_ID_ATTR = Pattern.compile(
            "\\bid\\s*=\\s*(['\"])(.*?)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_LINK_HREF = Pattern.compile(
            "<link\\b[^>]*\\bhref\\s*=\\s*(['\"])(.*?)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_SCRIPT_SRC = Pattern.compile(
            "<script\\b[^>]*\\bsrc\\s*=\\s*(['\"])(.*?)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern CSS_CLASS_SELECTOR = Pattern.compile("\\.([A-Za-z_][A-Za-z0-9_-]*)");
    private static final Pattern CSS_ID_SELECTOR = Pattern.compile("#([A-Za-z_][A-Za-z0-9_-]*)");
    private static final Pattern CSS_SELECTOR_PRELUDE = Pattern.compile("(?s)([^{}]+)\\{");
    private static final Pattern JS_QUERY_SELECTOR = Pattern.compile(
            "querySelector(?:All)?\\s*\\(\\s*['\"]([#.][A-Za-z_][A-Za-z0-9_-]*)['\"]\\s*\\)");
    private static final Pattern JS_GET_BY_ID = Pattern.compile(
            "getElementById\\s*\\(\\s*['\"]([A-Za-z_][A-Za-z0-9_-]*)['\"]\\s*\\)");
    private static final Pattern JS_GET_BY_CLASS = Pattern.compile(
            "getElementsByClassName\\s*\\(\\s*['\"]([A-Za-z_][A-Za-z0-9_-]*)['\"]\\s*\\)");
    private static final Pattern JS_CLASSLIST_DYNAMIC_CLASS = Pattern.compile(
            "classList\\s*\\.\\s*(?:add|toggle)\\s*\\(\\s*['\"]([A-Za-z_][A-Za-z0-9_-]*)['\"]\\s*\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern JS_RESULT_CLICKED_TEXT_ASSIGNMENT = Pattern.compile(
            "(?:querySelector\\s*\\(\\s*['\"]#result['\"]\\s*\\)"
                    + "|getElementById\\s*\\(\\s*['\"]result['\"]\\s*\\))"
                    + "\\s*\\.\\s*(?:textContent|innerText)\\s*=\\s*(['\"])\\s*Clicked\\s*\\1",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern JS_CLICK_EVENT_LISTENER = Pattern.compile(
            "addEventListener\\s*\\(\\s*['\"]click['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern JS_VISIBLE_TEXT_ASSIGNMENT = Pattern.compile(
            "\\.\\s*(?:textContent|innerText)\\s*=", Pattern.CASE_INSENSITIVE);

    private StaticWebSelectorAnalyzer() {}

    static Facts analyze(Path root, List<String> primaryFiles) {
        return analyze(root, primaryFiles, List.of());
    }

    static Facts analyze(
            Path root,
            List<String> primaryFiles,
            Collection<String> preferredAssetFiles
    ) {
        try {
            String htmlFile = pickPrimary(primaryFiles, ".html", ".htm");
            if (htmlFile == null) return null;
            String html = Files.readString(root.resolve(htmlFile));
            Set<String> htmlClasses = extractMatches(html, HTML_CLASS_ATTR, true);
            List<String> htmlIdOccurrences = htmlIdOccurrences(html);
            Set<String> htmlIds = new LinkedHashSet<>(htmlIdOccurrences);
            List<String> linkedCssOccurrences = linkedCssOccurrences(html);
            List<String> linkedJsOccurrences = linkedJavaScriptOccurrences(html);
            Set<String> linkedCssFiles = new LinkedHashSet<>(linkedCssOccurrences);
            Set<String> linkedJsFiles = new LinkedHashSet<>(linkedJsOccurrences);
            String cssFile = pickLinkedPreferredOrPrimary(primaryFiles, linkedCssFiles, preferredAssetFiles, ".css");
            String jsFile = pickLinkedPreferredOrPrimary(primaryFiles, linkedJsFiles, preferredAssetFiles, ".js");
            if (cssFile == null || jsFile == null) return null;
            String css = Files.readString(root.resolve(cssFile));
            String js = Files.readString(root.resolve(jsFile));
            return new Facts(
                    htmlFile,
                    cssFile,
                    jsFile,
                    htmlClasses,
                    htmlIds,
                    htmlIdOccurrences,
                    extractCssSelectors(css, CSS_CLASS_SELECTOR),
                    extractCssSelectors(css, CSS_ID_SELECTOR),
                    extractBareClassSelectors(css, htmlClasses),
                    extractJsClasses(js),
                    extractJsDynamicClasses(js),
                    extractJsIds(js),
                    linkedCssFiles,
                    linkedJsFiles,
                    linkedCssOccurrences,
                    linkedJsOccurrences,
                    html,
                    css,
                    js,
                    existingFileNames(root));
        } catch (Exception e) {
            return null;
        }
    }

    record Facts(
            String htmlFile,
            String cssFile,
            String jsFile,
            Set<String> htmlClasses,
            Set<String> htmlIds,
            List<String> htmlIdOccurrences,
            Set<String> cssClasses,
            Set<String> cssIds,
            Set<String> cssBareClassSelectors,
            Set<String> jsClasses,
            Set<String> jsDynamicClasses,
            Set<String> jsIds,
            Set<String> linkedCssFiles,
            Set<String> linkedJsFiles,
            List<String> linkedCssOccurrences,
            List<String> linkedJsOccurrences,
            String html,
            String css,
            String js,
            Set<String> existingFileNames
    ) {
        Facts {
            htmlFile = htmlFile == null ? "" : htmlFile;
            cssFile = cssFile == null ? "" : cssFile;
            jsFile = jsFile == null ? "" : jsFile;
            htmlClasses = stableSet(htmlClasses);
            htmlIds = stableSet(htmlIds);
            htmlIdOccurrences = htmlIdOccurrences == null ? List.of() : List.copyOf(htmlIdOccurrences);
            cssClasses = stableSet(cssClasses);
            cssIds = stableSet(cssIds);
            cssBareClassSelectors = stableSet(cssBareClassSelectors);
            jsClasses = stableSet(jsClasses);
            jsDynamicClasses = stableSet(jsDynamicClasses);
            jsIds = stableSet(jsIds);
            linkedCssFiles = stableSet(linkedCssFiles);
            linkedJsFiles = stableSet(linkedJsFiles);
            linkedCssOccurrences = linkedCssOccurrences == null ? List.of() : List.copyOf(linkedCssOccurrences);
            linkedJsOccurrences = linkedJsOccurrences == null ? List.of() : List.copyOf(linkedJsOccurrences);
            html = html == null ? "" : html;
            css = css == null ? "" : css;
            js = js == null ? "" : js;
            existingFileNames = stableSet(existingFileNames);
        }

        List<String> contentProblems() {
            List<String> out = new ArrayList<>();
            if (looksLikeNearPlaceholder(html, "html")) {
                out.add(htmlFile + ": HTML file appears to be placeholder content.");
            }
            if (looksLikeNearPlaceholder(css, "css")) {
                out.add(cssFile + ": CSS file appears to be placeholder content.");
            }
            if (looksLikeNearPlaceholder(js, "javascript")) {
                out.add(jsFile + ": JavaScript file appears to be placeholder content.");
            }
            return out;
        }

        List<String> selectorProblems() {
            List<String> out = new ArrayList<>();
            for (String id : duplicateValues(htmlIdOccurrences)) {
                out.add("HTML defines duplicate IDs: `#" + id + "`");
            }
            Set<String> cssMissingClasses = new LinkedHashSet<>(cssClasses);
            cssMissingClasses.removeAll(htmlClasses);
            cssMissingClasses.removeAll(jsDynamicClasses);
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
            if (!cssBareClassSelectors.isEmpty()) {
                out.add("CSS likely uses bare element selectors where HTML defines classes: "
                        + renderBareClassSelectorHints(cssBareClassSelectors));
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
            for (String css : duplicateValues(linkedCssOccurrences)) {
                out.add("HTML links CSS file more than once: `" + css + "`");
            }
            for (String js : duplicateValues(linkedJsOccurrences)) {
                out.add("HTML links JavaScript file more than once: `" + js + "`");
            }
            if (!linkedCssFiles.contains(cssFile)) {
                out.add("HTML does not link CSS file: `" + cssFile + "`");
            }
            if (!linkedJsFiles.contains(jsFile)) {
                out.add("HTML does not link JavaScript file: `" + jsFile + "`");
            }
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

        List<String> buttonResultBehaviorProblems(String request) {
            if (!expectsRunButtonResultClicked(request)) return List.of();
            List<String> out = new ArrayList<>();
            if (!jsIds.contains("run-button")) {
                out.add(jsFile + ": JavaScript does not reference `#run-button` for the requested button behavior.");
            }
            if (!hasClickedResultAssignment(js)) {
                out.add(jsFile + ": JavaScript does not assign `#result` text to `Clicked` for the requested button behavior.");
            }
            return out;
        }

        List<String> genericButtonResultDiagnosticProblems() {
            if (!jsIds.contains("result")) return List.of();
            if (!JS_CLICK_EVENT_LISTENER.matcher(js).find()) return List.of();
            if (JS_VISIBLE_TEXT_ASSIGNMENT.matcher(js).find()) return List.of();
            return List.of(jsFile
                    + ": button click handler references `#result` but does not assign visible result text "
                    + "with `textContent` or `innerText`.");
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
            mismatches.addAll(linkageProblems());
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

    static List<String> linkedCssOccurrences(String html) {
        return extractLinkedAssetOccurrences(html, HTML_LINK_HREF, ".css");
    }

    static List<String> linkedJavaScriptOccurrences(String html) {
        return extractLinkedAssetOccurrences(html, HTML_SCRIPT_SRC, ".js");
    }

    static List<String> htmlIdOccurrences(String html) {
        return extractMatchOccurrences(html, HTML_ID_ATTR, false);
    }

    static Set<String> duplicateValues(List<String> values) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> duplicates = new LinkedHashSet<>();
        if (values == null) return duplicates;
        for (String value : values) {
            if (!seen.add(value)) duplicates.add(value);
        }
        return duplicates;
    }

    static Set<String> existingFileNames(Path root) {
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

    static String pickPrimary(List<String> files, String... exts) {
        for (String file : files) {
            String lower = file.toLowerCase(Locale.ROOT);
            for (String ext : exts) {
                if (lower.endsWith(ext)) return file;
            }
        }
        return null;
    }

    static boolean expectsRunButtonResultClicked(String request) {
        if (request == null || request.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT);
        return lower.contains("run-button")
                && lower.contains("result")
                && lower.contains("clicked");
    }

    private static <T> Set<T> stableSet(Set<T> values) {
        return values == null ? Set.of() : java.util.Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    private static Set<String> extractMatches(String text, Pattern pattern, boolean splitOnWhitespace) {
        return new LinkedHashSet<>(extractMatchOccurrences(text, pattern, splitOnWhitespace));
    }

    private static List<String> extractMatchOccurrences(String text, Pattern pattern, boolean splitOnWhitespace) {
        List<String> out = new ArrayList<>();
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
        Matcher preludeMatcher = CSS_SELECTOR_PRELUDE.matcher(stripCssComments(css));
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

    private static Set<String> extractBareClassSelectors(String css, Set<String> htmlClasses) {
        Set<String> out = new LinkedHashSet<>();
        if (css == null || css.isBlank() || htmlClasses == null || htmlClasses.isEmpty()) return out;
        Matcher preludeMatcher = CSS_SELECTOR_PRELUDE.matcher(stripCssComments(css));
        while (preludeMatcher.find()) {
            String prelude = preludeMatcher.group(1);
            if (prelude == null || prelude.isBlank()) continue;
            for (String selector : prelude.split(",")) {
                String trimmed = selector.strip();
                if (htmlClasses.contains(trimmed)) {
                    out.add(trimmed);
                }
            }
        }
        return out;
    }

    private static String stripCssComments(String css) {
        return css == null ? "" : CSS_BLOCK_COMMENT.matcher(css).replaceAll(" ");
    }

    private static boolean looksLikeNearPlaceholder(String content, String kind) {
        if (content == null) return false;
        String trimmed = content.strip();
        if (trimmed.isEmpty()) return true;
        String lower = trimmed.toLowerCase(Locale.ROOT);
        String commentless = lower
                .replaceAll("(?s)<!--.*?-->", " ")
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)^\\s*//.*$", " ")
                .strip();
        if (commentless.isBlank()) return true;
        String normalized = lower.replaceAll("\\s+", " ");
        return normalized.contains("your " + kind + " logic here")
                || normalized.contains("your " + kind + " code here")
                || normalized.contains(kind + " logic here")
                || normalized.contains(kind + " code here")
                || normalized.contains("add " + kind + " here");
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

    private static Set<String> extractJsDynamicClasses(String js) {
        Set<String> out = new LinkedHashSet<>();
        if (js == null || js.isBlank()) return out;
        Matcher matcher = JS_CLASSLIST_DYNAMIC_CLASS.matcher(js);
        while (matcher.find()) {
            String cls = matcher.group(1);
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

    private static List<String> extractLinkedAssetOccurrences(String html, Pattern pattern, String extension) {
        List<String> out = new ArrayList<>();
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

    private static String pickLinkedPreferredOrPrimary(
            List<String> files,
            Set<String> linkedFiles,
            Collection<String> preferredFiles,
            String ext
    ) {
        if (files == null || files.isEmpty()) return null;
        if (linkedFiles != null) {
            for (String linked : linkedFiles) {
                for (String file : files) {
                    if (file.equals(linked) && hasExtension(file, ext)) return file;
                }
            }
        }
        if (preferredFiles != null) {
            boolean caseInsensitive = expectedTargetMatchingIsCaseInsensitive();
            for (String preferred : preferredFiles) {
                String normalized = normalizePath(preferred);
                if (normalized.isBlank() || normalized.contains("/") || !hasExtension(normalized, ext)) {
                    continue;
                }
                for (String file : files) {
                    if (hasExtension(file, ext)
                            && expectedTargetMatches(file, normalized, caseInsensitive)) {
                        return file;
                    }
                }
            }
        }
        return pickPrimary(files, ext);
    }

    private static boolean hasExtension(String path, String... exts) {
        if (path == null || exts == null) return false;
        String lower = normalizePath(path).toLowerCase(Locale.ROOT);
        for (String ext : exts) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
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

    private static boolean expectedTargetMatches(String expectedTarget, String mutatedPath, boolean caseInsensitive) {
        String expected = normalizePath(expectedTarget);
        String mutated = normalizePath(mutatedPath);
        if (expected.isBlank() || mutated.isBlank()) return false;
        if (caseInsensitive) {
            return expected.equalsIgnoreCase(mutated);
        }
        return expected.equals(mutated);
    }

    private static boolean expectedTargetMatchingIsCaseInsensitive() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean hasClickedResultAssignment(String js) {
        return js != null && JS_RESULT_CLICKED_TEXT_ASSIGNMENT.matcher(js).find();
    }

    private static String renderObserved(Set<String> values) {
        if (values == null || values.isEmpty()) return "none";
        return values.stream().sorted().map(v -> "`" + v + "`").reduce((a, b) -> a + ", " + b).orElse("none");
    }

    private static String renderSelectors(Set<String> values, String prefix) {
        return values.stream().sorted().map(v -> "`" + prefix + v + "`")
                .reduce((a, b) -> a + ", " + b).orElse("none");
    }

    private static String renderBareClassSelectorHints(Set<String> values) {
        return values.stream()
                .sorted()
                .map(v -> "`" + v + "` should probably be `." + v + "`")
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");
    }
}
