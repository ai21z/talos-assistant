package dev.talos.runtime.task;

import dev.talos.runtime.trace.PromptAuditRedactor;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Durable static-web semantic requirements derived from explicit user text. */
public record StaticWebRequirements(
        List<String> requiredVisibleFacts,
        Set<String> forbiddenArtifacts
) {
    public static final int MAX_FACTS = 40;
    public static final int MAX_FACT_CHARS = 120;
    public static final int MAX_RENDER_CHARS = 900;
    private static final int MAX_EXPLICIT_FACT_SPAN = 1_000;

    private static final Pattern EXPLICIT_FACT_SPAN = Pattern.compile(
            "(?is)\\b(?:preserve|keep|retain)\\s+(?:these\\s+|the\\s+)?"
                    + "(?:band\\s+|visible\\s+|required\\s+)?(?:facts|details|content)\\s*:\\s*"
                    + "(.{1," + MAX_EXPLICIT_FACT_SPAN + "})");
    private static final Pattern REQUIRED_FACT_SPAN = Pattern.compile(
            "(?is)\\brequired\\s+(?:visible\\s+)?facts\\s*:\\s*(.{1,"
                    + MAX_EXPLICIT_FACT_SPAN + "})");

    public StaticWebRequirements {
        requiredVisibleFacts = normalizeFacts(requiredVisibleFacts);
        forbiddenArtifacts = normalizeArtifacts(forbiddenArtifacts);
    }

    public static StaticWebRequirements none() {
        return new StaticWebRequirements(List.of(), Set.of());
    }

    public static StaticWebRequirements of(List<String> requiredVisibleFacts, Set<String> forbiddenArtifacts) {
        return new StaticWebRequirements(requiredVisibleFacts, forbiddenArtifacts);
    }

    public static StaticWebRequirements fromRequest(String request, Set<String> forbiddenTargets) {
        return new StaticWebRequirements(explicitFacts(request), forbiddenTargets);
    }

    public StaticWebRequirements merge(StaticWebRequirements other) {
        if (other == null || other.isEmpty()) return this;
        LinkedHashSet<String> facts = new LinkedHashSet<>(requiredVisibleFacts);
        facts.addAll(other.requiredVisibleFacts());
        LinkedHashSet<String> artifacts = new LinkedHashSet<>(forbiddenArtifacts);
        artifacts.addAll(other.forbiddenArtifacts());
        return new StaticWebRequirements(List.copyOf(facts), artifacts);
    }

    public boolean isEmpty() {
        return requiredVisibleFacts.isEmpty() && forbiddenArtifacts.isEmpty();
    }

    public String renderForPlan() {
        if (isEmpty()) return "";
        StringBuilder out = new StringBuilder("staticWebRequirements{");
        if (!requiredVisibleFacts.isEmpty()) {
            out.append("requiredVisibleFacts=").append(requiredVisibleFacts);
        }
        if (!forbiddenArtifacts.isEmpty()) {
            if (!requiredVisibleFacts.isEmpty()) out.append(", ");
            out.append("forbiddenArtifacts=").append(forbiddenArtifacts.stream().sorted().toList());
        }
        out.append('}');
        return PromptAuditRedactor.preview(out.toString(), MAX_RENDER_CHARS);
    }

    public static List<String> explicitFacts(String request) {
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
                if (out.size() >= MAX_FACTS) return;
            }
        }
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
        return fact != null && fact.length() >= 2 && fact.length() <= MAX_FACT_CHARS;
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

    private static List<String> normalizeFacts(List<String> rawFacts) {
        if (rawFacts == null || rawFacts.isEmpty()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : rawFacts) {
            String fact = cleanFact(raw);
            if (isUsefulFact(fact)) out.add(fact);
            if (out.size() >= MAX_FACTS) break;
        }
        return List.copyOf(out);
    }

    private static Set<String> normalizeArtifacts(Set<String> rawArtifacts) {
        if (rawArtifacts == null || rawArtifacts.isEmpty()) return Set.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : rawArtifacts) {
            String artifact = normalizeArtifact(raw);
            if (!artifact.isBlank()) out.add(artifact);
        }
        return Collections.unmodifiableSet(out);
    }

    private static String normalizeArtifact(String raw) {
        if (raw == null) return "";
        String value = raw.strip()
                .replace('\\', '/')
                .replaceAll("^[`'\"(\\[]+", "")
                .replaceAll("[`'\"),.;:!?\\]]+$", "")
                .toLowerCase(Locale.ROOT);
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        return value;
    }
}
