package dev.talos.runtime.verification;

import dev.talos.runtime.task.TaskContract;

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

final class StaticWebTailwindCoherenceVerifier {
    private static final Pattern HTML_CLASS_ATTR = Pattern.compile(
            "\\bclass\\s*=\\s*(['\"])(.*?)\\1", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HTML_SCRIPT_SRC = Pattern.compile(
            "<script\\b[^>]*\\bsrc\\s*=\\s*(['\"])(.*?)\\1", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HTML_LINK_HREF = Pattern.compile(
            "<link\\b[^>]*\\bhref\\s*=\\s*(['\"])(.*?)\\1", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private StaticWebTailwindCoherenceVerifier() {}

    static List<String> problems(
            Path root,
            TaskContract contract,
            StaticWebSelectorAnalyzer.Facts selectors,
            Collection<String> mutatedPaths
    ) {
        if (root == null || selectors == null) return List.of();
        List<String> out = new ArrayList<>();
        boolean tailwindRuntime = hasTailwindRuntime(selectors.html());
        boolean tailwindBuild = hasTailwindBuild(root);
        boolean remoteTailwindStylesheet = hasRemoteTailwindStylesheet(selectors.html());
        String linkedCssDirectives = tailwindDirectiveSummary(selectors.css());
        if (!linkedCssDirectives.isBlank() && !tailwindRuntime && !tailwindBuild) {
            out.add(selectors.cssFile()
                    + ": Tailwind directives (" + linkedCssDirectives
                    + ") are unprocessed; "
                    + missingDirectiveRuntimeEvidence(remoteTailwindStylesheet));
        }
        Set<String> tailwindUtilities = tailwindLikeUtilityClasses(selectors.html());
        if (!tailwindUtilities.isEmpty()
                && !tailwindRuntime
                && !tailwindBuild
                && linkedCssDirectives.isBlank()
                && !cssDefinesAnyUtility(selectors.css(), tailwindUtilities)) {
            out.add(selectors.htmlFile()
                    + ": Tailwind utility classes are used, but "
                    + missingUtilityRuntimeEvidence(remoteTailwindStylesheet));
        }
        out.addAll(orphanTailwindProblems(
                root,
                contract,
                selectors,
                mutatedPaths,
                tailwindRuntime,
                tailwindBuild,
                remoteTailwindStylesheet));
        return out;
    }

    private static List<String> orphanTailwindProblems(
            Path root,
            TaskContract contract,
            StaticWebSelectorAnalyzer.Facts selectors,
            Collection<String> mutatedPaths,
            boolean tailwindRuntime,
            boolean tailwindBuild,
            boolean remoteTailwindStylesheet
    ) {
        if (mutatedPaths == null || mutatedPaths.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String path : mutatedPaths) {
            String normalized = normalize(path);
            boolean localTailwindArtifact = isLocalTailwindArtifact(normalized);
            boolean forbiddenTailwindArtifact = contract != null
                    && contract.forbiddenTargets().stream()
                    .map(StaticWebTailwindCoherenceVerifier::normalize)
                    .anyMatch(forbidden -> forbidden.equalsIgnoreCase(normalized));
            boolean linkedOrPrimaryCss = selectors.linkedCssFiles().contains(normalized)
                    || normalized.equals(selectors.cssFile());
            if (normalized.isBlank()
                    || !normalized.endsWith(".css")
                    || (linkedOrPrimaryCss && !localTailwindArtifact && !forbiddenTailwindArtifact)) {
                continue;
            }
            String css = read(root, normalized);
            if (localTailwindArtifact || forbiddenTailwindArtifact) {
                out.add(normalized
                        + ": local Tailwind artifact is unsupported without an explicit build-backed local artifact request.");
                String directives = tailwindDirectiveSummary(css);
                if (!directives.isBlank() && !tailwindRuntime && !tailwindBuild) {
                    out.add(normalized
                            + ": Tailwind directives (" + directives
                            + ") are unprocessed; "
                            + missingDirectiveRuntimeEvidence(remoteTailwindStylesheet));
                }
            } else {
                String directives = tailwindDirectiveSummary(css);
                if (directives.isBlank()) continue;
                out.add(normalized + ": Tailwind CSS file is not linked from HTML.");
                if (!tailwindRuntime && !tailwindBuild) {
                    out.add(normalized
                            + ": Tailwind directives (" + directives
                            + ") are unprocessed; "
                            + missingDirectiveRuntimeEvidence(remoteTailwindStylesheet));
                }
            }
        }
        return out;
    }

    private static boolean isLocalTailwindArtifact(String path) {
        if (path == null || path.isBlank()) return false;
        String normalized = normalize(path).toLowerCase(Locale.ROOT);
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return name.equals("tailwind.css")
                || name.equals("tailwind.min.css")
                || (name.startsWith("tailwind.") && name.endsWith(".css"));
    }

    private static boolean hasTailwindRuntime(String html) {
        if (html == null || html.isBlank()) return false;
        Matcher matcher = HTML_SCRIPT_SRC.matcher(html);
        while (matcher.find()) {
            String src = matcher.group(2);
            if (src == null || src.isBlank()) continue;
            String lower = src.strip().toLowerCase(Locale.ROOT);
            if (lower.startsWith("//")) {
                lower = "https:" + lower;
            }
            if (lower.startsWith("https://cdn.tailwindcss.com")
                    || lower.startsWith("http://cdn.tailwindcss.com")
                    || lower.startsWith("https://cdn.jsdelivr.net/npm/@tailwindcss/browser")
                    || lower.startsWith("http://cdn.jsdelivr.net/npm/@tailwindcss/browser")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRemoteTailwindStylesheet(String html) {
        if (html == null || html.isBlank()) return false;
        Matcher matcher = HTML_LINK_HREF.matcher(html);
        while (matcher.find()) {
            String href = matcher.group(2);
            if (href == null || href.isBlank()) continue;
            String lower = href.strip().toLowerCase(Locale.ROOT);
            if (lower.startsWith("//")) {
                lower = "https:" + lower;
            }
            if ((lower.startsWith("http://") || lower.startsWith("https://"))
                    && lower.contains("tailwind")
                    && lower.contains(".css")) {
                return true;
            }
        }
        return false;
    }

    private static String missingDirectiveRuntimeEvidence(boolean remoteTailwindStylesheet) {
        if (remoteTailwindStylesheet) {
            return "a remote Tailwind stylesheet is linked, but it is not accepted Tailwind "
                    + "browser runtime/build evidence; no local build configuration was found.";
        }
        return "no accepted Tailwind browser runtime or local build configuration was found.";
    }

    private static String missingUtilityRuntimeEvidence(boolean remoteTailwindStylesheet) {
        if (remoteTailwindStylesheet) {
            return "a remote Tailwind stylesheet is linked, but it is not accepted Tailwind "
                    + "browser runtime/build evidence; no local build configuration or generated CSS "
                    + "definitions were found.";
        }
        return "no accepted Tailwind browser runtime, local build configuration, or generated CSS "
                + "definitions were found.";
    }

    private static boolean hasTailwindBuild(Path root) {
        try {
            if (Files.isRegularFile(root.resolve("tailwind.config.js"))
                    || Files.isRegularFile(root.resolve("tailwind.config.cjs"))
                    || Files.isRegularFile(root.resolve("tailwind.config.mjs"))
                    || Files.isRegularFile(root.resolve("tailwind.config.ts"))
                    || Files.isRegularFile(root.resolve("postcss.config.js"))
                    || Files.isRegularFile(root.resolve("postcss.config.cjs"))) {
                return true;
            }
            Path packageJson = root.resolve("package.json");
            return Files.isRegularFile(packageJson)
                    && Files.readString(packageJson).toLowerCase(Locale.ROOT).contains("tailwindcss");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean containsTailwindDirective(String css) {
        return !tailwindDirectiveSummary(css).isBlank();
    }

    private static String tailwindDirectiveSummary(String css) {
        if (css == null || css.isBlank()) return "";
        String lower = css.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> directives = new LinkedHashSet<>();
        addDirectiveIfPresent(directives, lower, "@tailwind base");
        addDirectiveIfPresent(directives, lower, "@tailwind components");
        addDirectiveIfPresent(directives, lower, "@tailwind utilities");
        addDirectiveIfPresent(directives, lower, "@apply");
        addDirectiveIfPresent(directives, lower, "@theme");
        addDirectiveIfPresent(directives, lower, "@source");
        addDirectiveIfPresent(directives, lower, "@utility");
        addDirectiveIfPresent(directives, lower, "@variant");
        addDirectiveIfPresent(directives, lower, "@custom-variant");
        addDirectiveIfPresent(directives, lower, "@reference");
        addDirectiveIfPresent(directives, lower, "@config");
        addDirectiveIfPresent(directives, lower, "@plugin");
        if (lower.contains("@import \"tailwindcss\"") || lower.contains("@import 'tailwindcss'")) {
            directives.add("@import tailwindcss");
        }
        return String.join(", ", directives);
    }

    private static void addDirectiveIfPresent(Set<String> directives, String lower, String directive) {
        if (lower != null && lower.contains(directive)) {
            directives.add(directive);
        }
    }

    private static Set<String> tailwindLikeUtilityClasses(String html) {
        if (html == null || html.isBlank()) return Set.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher matcher = HTML_CLASS_ATTR.matcher(html);
        while (matcher.find()) {
            String value = matcher.group(2);
            if (value == null || value.isBlank()) continue;
            for (String token : value.split("\\s+")) {
                String normalized = token.strip();
                if (looksTailwindUtility(normalized)) {
                    out.add(normalized);
                }
            }
        }
        return Set.copyOf(out);
    }

    private static boolean looksTailwindUtility(String token) {
        if (token == null || token.isBlank()) return false;
        String lower = token.toLowerCase(Locale.ROOT);
        return lower.startsWith("bg-")
                || lower.startsWith("text-")
                || lower.startsWith("min-h-")
                || lower.startsWith("max-w-")
                || lower.startsWith("mx-")
                || lower.startsWith("my-")
                || lower.startsWith("px-")
                || lower.startsWith("py-")
                || lower.startsWith("p-")
                || lower.startsWith("m-")
                || lower.startsWith("rounded")
                || lower.startsWith("shadow")
                || lower.equals("flex")
                || lower.equals("grid")
                || lower.equals("container");
    }

    private static boolean cssDefinesAnyUtility(String css, Set<String> utilities) {
        if (css == null || css.isBlank() || utilities == null || utilities.isEmpty()) return false;
        for (String utility : utilities) {
            if (css.contains("." + escapeCssSelectorToken(utility))) {
                return true;
            }
        }
        return false;
    }

    private static String escapeCssSelectorToken(String token) {
        return token == null ? "" : token.replace(":", "\\:").replace("/", "\\/");
    }

    private static String read(Path root, String relative) {
        try {
            Path resolved = root.resolve(relative).normalize();
            if (!resolved.startsWith(root.normalize()) || !Files.isRegularFile(resolved)) return "";
            return Files.readString(resolved);
        } catch (Exception e) {
            return "";
        }
    }

    private static String normalize(String path) {
        if (path == null) return "";
        String normalized = path.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }
}
