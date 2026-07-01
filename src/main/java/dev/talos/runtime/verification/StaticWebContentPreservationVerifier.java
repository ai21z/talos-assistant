package dev.talos.runtime.verification;

import dev.talos.runtime.task.TaskContract;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class StaticWebContentPreservationVerifier {
    private static final int MAX_EXPLICIT_FACT_SPAN = 800;

    private static final Pattern EXPLICIT_FACT_SPAN = Pattern.compile(
            "(?is)\\b(?:preserve|keep|retain)\\s+(?:the\\s+)?"
                    + "(?:band\\s+|visible\\s+|required\\s+)?(?:facts|details|content)\\s*:\\s*"
                    + "(.{1," + MAX_EXPLICIT_FACT_SPAN + "})");
    private static final Pattern REQUIRED_FACT_SPAN = Pattern.compile(
            "(?is)\\brequired\\s+(?:visible\\s+)?facts\\s*:\\s*(.{1,"
                    + MAX_EXPLICIT_FACT_SPAN + "})");
    private static final Pattern VISIBLE_TEXT_ELEMENT = Pattern.compile(
            "(?is)<(?:title|h[1-6]|p|li|td|th|figcaption|blockquote|span|a|button)[^>]*>"
                    + "(.*?)</(?:title|h[1-6]|p|li|td|th|figcaption|blockquote|span|a|button)>");
    private static final Pattern JS_SINGLE_QUOTED_STRING = Pattern.compile(
            "'((?:\\\\.|[^'\\\\]){1,240})'", Pattern.DOTALL);
    private static final Pattern JS_DOUBLE_QUOTED_STRING = Pattern.compile(
            "\"((?:\\\\.|[^\"\\\\]){1,240})\"", Pattern.DOTALL);

    private StaticWebContentPreservationVerifier() {}

    record Result(List<String> facts, List<String> problems) {
        Result {
            facts = facts == null ? List.of() : List.copyOf(facts);
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        static Result none() {
            return new Result(List.of(), List.of());
        }
    }

    static Result verify(
            TaskContract contract,
            StaticWebSelectorAnalyzer.Facts selectors,
            Map<String, String> readFileBodies
    ) {
        if (contract == null || selectors == null) return Result.none();
        List<String> requiredFacts = requiredFacts(contract, selectors, readFileBodies);
        if (requiredFacts.isEmpty()) return Result.none();

        String visibleSiteText = normalizeVisibleText(selectors.html());
        String linkedJavaScriptText = normalizeJavaScriptStringText(selectors.js());
        List<String> missing = requiredFacts.stream()
                .filter(fact -> !visibleSiteText.contains(normalizeComparable(fact)))
                .toList();
        List<String> weakJavaScriptEvidence = missing.stream()
                .filter(fact -> {
                    String comparable = normalizeComparable(fact);
                    return !comparable.isBlank() && linkedJavaScriptText.contains(comparable);
                })
                .toList();
        List<String> facts = new ArrayList<>();
        if (!weakJavaScriptEvidence.isEmpty()) {
            facts.add("linked JavaScript string evidence contains required fact text not present in initial HTML: "
                    + String.join(", ", weakJavaScriptEvidence) + ".");
        }
        if (!missing.isEmpty()) {
            return new Result(
                    facts,
                    List.of(selectors.htmlFile()
                            + ": required content facts missing after static-web rewrite: "
                            + String.join(", ", missing) + "."));
        }
        return new Result(
                List.of("Required static-web content facts were preserved in " + selectors.htmlFile() + "."),
                List.of());
    }

    private static List<String> requiredFacts(
            TaskContract contract,
            StaticWebSelectorAnalyzer.Facts selectors,
            Map<String, String> readFileBodies
    ) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (contract != null && contract.staticWebRequirements() != null) {
            out.addAll(contract.staticWebRequirements().requiredVisibleFacts());
        }
        String request = contract == null ? "" : contract.originalUserRequest();
        out.addAll(explicitFacts(request));
        out.addAll(readEvidenceFacts(request, selectors, readFileBodies));
        return List.copyOf(out);
    }

    private static List<String> explicitFacts(String request) {
        if (request == null || request.isBlank()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        addExplicitFacts(out, EXPLICIT_FACT_SPAN.matcher(request));
        addExplicitFacts(out, REQUIRED_FACT_SPAN.matcher(request));
        return List.copyOf(out);
    }

    private static void addExplicitFacts(Set<String> out, Matcher matcher) {
        while (matcher.find()) {
            String span = firstFactSentence(matcher.group(1));
            for (String piece : span.split("\\s*(?:,|;)\\s*")) {
                String fact = cleanFact(piece);
                if (isUsefulFact(fact)) out.add(fact);
            }
        }
    }

    private static List<String> readEvidenceFacts(
            String request,
            StaticWebSelectorAnalyzer.Facts selectors,
            Map<String, String> readFileBodies
    ) {
        if (!preserveExistingContentRequested(request)
                || selectors == null
                || readFileBodies == null
                || readFileBodies.isEmpty()) {
            return List.of();
        }
        String htmlFile = selectors.htmlFile();
        if (htmlFile == null || htmlFile.isBlank()) return List.of();

        String readBody = readFileBodies.entrySet().stream()
                .filter(entry -> entry.getKey() != null
                        && entry.getKey().equalsIgnoreCase(htmlFile)
                        && entry.getValue() != null
                        && !entry.getValue().isBlank())
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("");
        if (readBody.isBlank()) return List.of();

        LinkedHashSet<String> facts = new LinkedHashSet<>();
        Matcher matcher = VISIBLE_TEXT_ELEMENT.matcher(readBody);
        while (matcher.find() && facts.size() < 30) {
            String fact = cleanFact(stripHtml(matcher.group(1)));
            if (isUsefulReadbackFact(fact)) facts.add(fact);
        }
        return List.copyOf(facts);
    }

    private static boolean preserveExistingContentRequested(String request) {
        if (request == null || request.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT);
        return lower.contains("preserve existing")
                || lower.contains("keep existing")
                || lower.contains("retain existing")
                || lower.contains("preserve the current")
                || lower.contains("keep the current")
                || lower.contains("retain the current");
    }

    private static String firstFactSentence(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String normalized = raw.replace('\n', ' ').replaceAll("\\s+", " ").strip();
        Matcher end = Pattern.compile("(?<=[A-Za-z0-9)])\\.(?:\\s|$)").matcher(normalized);
        if (end.find()) {
            return normalized.substring(0, end.start() + 1);
        }
        return normalized;
    }

    private static boolean isUsefulFact(String fact) {
        return fact != null && fact.length() >= 2 && fact.length() <= 120;
    }

    private static boolean isUsefulReadbackFact(String fact) {
        if (!isUsefulFact(fact)) return false;
        String lower = fact.toLowerCase(Locale.ROOT);
        if (Set.of("home", "about", "contact", "learn more", "submit", "button").contains(lower)) {
            return false;
        }
        return lower.matches(".*[a-z].*");
    }

    private static String cleanFact(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("(?m)^\\s*\\d+\\s*[|:]\\s*", "")
                .replace('`', ' ')
                .replace('"', ' ')
                .replace('\'', ' ')
                .replaceAll("\\s+", " ")
                .replaceAll("^[\\s\\-:]+|[\\s\\-:.]+$", "")
                .strip();
    }

    private static String normalizeVisibleText(String html) {
        return normalizeComparable(stripHtml(html));
    }

    private static String normalizeJavaScriptStringText(String js) {
        if (js == null || js.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        appendJavaScriptStringText(out, JS_SINGLE_QUOTED_STRING.matcher(js));
        appendJavaScriptStringText(out, JS_DOUBLE_QUOTED_STRING.matcher(js));
        return normalizeComparable(stripHtml(out.toString()));
    }

    private static void appendJavaScriptStringText(StringBuilder out, Matcher matcher) {
        while (matcher.find()) {
            String value = matcher.group(1);
            if (value == null || value.isBlank()) continue;
            out.append(' ').append(unescapeJavaScriptString(value));
        }
    }

    private static String unescapeJavaScriptString(String value) {
        if (value == null || value.isBlank()) return "";
        return value
                .replace("\\n", " ")
                .replace("\\r", " ")
                .replace("\\t", " ")
                .replace("\\'", "'")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private static String normalizeComparable(String value) {
        if (value == null || value.isBlank()) return "";
        return value.toLowerCase(Locale.ROOT)
                .replace("&amp;", " and ")
                .replace("&nbsp;", " ")
                .replace("&ndash;", " ")
                .replace("&mdash;", " ")
                .replace("&#8211;", " ")
                .replace("&#8212;", " ")
                .replaceAll("[\\p{Punct}\\p{Pd}]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static String stripHtml(String html) {
        if (html == null || html.isBlank()) return "";
        return html.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<[^>]+>", " ")
                .replace("&amp;", "&")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }
}
