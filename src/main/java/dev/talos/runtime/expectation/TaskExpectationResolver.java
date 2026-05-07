package dev.talos.runtime.expectation;

import dev.talos.runtime.task.TaskContract;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves narrow deterministic task expectations from explicit user wording. */
public final class TaskExpectationResolver {

    private static final Pattern WRITE_EXACT_CONTENT = Pattern.compile(
            "(?is)\\bwrite\\s+exactly\\s+this\\s+content\\s*:\\s*(.+)");
    private static final Pattern ENTIRE_FILE_SHOULD_BE = Pattern.compile(
            "(?is)\\b(?:the\\s+)?entire\\s+file\\s+should\\s+be\\s+(.+)");
    private static final Pattern CONTENT_ARGUMENT_EXACT = Pattern.compile(
            "(?is)\\bcontent\\s+argument\\s+to\\s+the\\s+exact\\s+(?:five\\s+letters|content|string|text)?\\s*(.+)");
    private static final Pattern WHOLE_FILE_REPLACE = Pattern.compile(
            "(?is)\\breplace\\s+the\\s+whole\\s+file\\s+with\\s+(.+)");
    private static final Pattern COMPLETE_FILE_TWO_LINES = Pattern.compile(
            "(?is)\\b(?:the\\s+)?(?:complete|entire)\\s+file\\s+"
                    + "(?:must|should)\\s+contain\\s+exactly\\s+two\\s+lines\\s*:\\s*"
                    + "first\\s+line\\s+(.+?)\\s*;\\s*"
                    + "second\\s+line\\s+(.+?)\\s*;\\s*"
                    + "no\\s+other\\s+characters\\b");

    private TaskExpectationResolver() {}

    public static List<TaskExpectation> resolve(TaskContract contract) {
        if (contract == null || contract.expectedTargets().size() != 1) return List.of();
        String request = contract.originalUserRequest();
        if (request == null || request.isBlank()) return List.of();
        String target = contract.expectedTargets().iterator().next();
        if (target == null || target.isBlank()) return List.of();

        String normalizedTarget = normalizePath(target);
        List<Candidate> candidates = new ArrayList<>();
        addTargetSpecificExactCandidates(request, normalizedTarget, candidates);
        addTargetContainingExactlyCandidates(request, normalizedTarget, candidates);
        addCompleteFileTwoLineCandidate(request, candidates);
        addGenericCandidate(request, ENTIRE_FILE_SHOULD_BE, "literal-entire-file", candidates);
        addGenericCandidate(request, CONTENT_ARGUMENT_EXACT, "literal-content-argument", candidates);
        addGenericCandidate(request, WHOLE_FILE_REPLACE, "literal-whole-file-replace", candidates);
        addGenericCandidate(request, WRITE_EXACT_CONTENT, "literal-write-exact-content", candidates);

        if (candidates.isEmpty()) return List.of();

        LinkedHashSet<String> literals = new LinkedHashSet<>();
        String firstSourcePattern = "";
        for (Candidate candidate : candidates) {
            String literal = candidate.alreadyExact()
                    ? normalizeExactLiteral(candidate.literal())
                    : normalizeLiteral(candidate.literal());
            if (literal.isBlank()) continue;
            literals.add(literal);
            if (firstSourcePattern.isBlank()) firstSourcePattern = candidate.sourcePattern();
        }
        if (literals.size() != 1) return List.of();

        return List.of(new LiteralContentExpectation(
                normalizedTarget,
                literals.iterator().next(),
                LiteralContentExpectation.MatchMode.EXACT,
                firstSourcePattern));
    }

    private static void addTargetSpecificExactCandidates(
            String request,
            String target,
            List<Candidate> candidates
    ) {
        String quoted = Pattern.quote(target);
        Pattern overwriteWithExactly = Pattern.compile(
                "(?is)\\b(?:overwrite|set|replace)\\s+`?" + quoted
                        + "`?\\s+(?:with|to)\\s+exactly\\s+(.+)");
        Matcher matcher = overwriteWithExactly.matcher(request);
        while (matcher.find()) {
            candidates.add(new Candidate(matcher.group(1), "literal-overwrite-exactly"));
        }
    }

    private static void addTargetContainingExactlyCandidates(
            String request,
            String target,
            List<Candidate> candidates
    ) {
        String quoted = Pattern.quote(target);
        Pattern createContainingExactly = Pattern.compile(
                "(?is)\\b(?:create|write|add)\\s+`?" + quoted
                        + "`?\\s+(?:with\\s+content\\s+)?containing\\s+exactly\\s+(.+)");
        Matcher matcher = createContainingExactly.matcher(request);
        while (matcher.find()) {
            candidates.add(new Candidate(matcher.group(1), "literal-create-containing-exactly"));
        }
    }

    private static void addCompleteFileTwoLineCandidate(String request, List<Candidate> candidates) {
        Matcher matcher = COMPLETE_FILE_TWO_LINES.matcher(request);
        while (matcher.find()) {
            String firstLine = normalizeLineLiteral(matcher.group(1));
            String secondLine = normalizeLineLiteral(matcher.group(2));
            if (firstLine.isBlank() && secondLine.isBlank()) continue;
            candidates.add(new Candidate(
                    firstLine + "\n" + secondLine,
                    "literal-complete-file-two-lines",
                    true));
        }
    }

    private static void addGenericCandidate(
            String request,
            Pattern pattern,
            String sourcePattern,
            List<Candidate> candidates
    ) {
        Matcher matcher = pattern.matcher(request);
        while (matcher.find()) {
            candidates.add(new Candidate(matcher.group(1), sourcePattern));
        }
    }

    private static String normalizeLiteral(String raw) {
        if (raw == null) return "";
        String literal = firstSentenceOrLine(raw).strip();
        literal = stripCodeFence(literal).strip();
        literal = stripWrappingQuotes(literal).strip();
        return literal;
    }

    private static String normalizeExactLiteral(String raw) {
        if (raw == null) return "";
        String literal = raw.strip();
        literal = stripCodeFence(literal).strip();
        literal = stripWrappingQuotes(literal).strip();
        return literal;
    }

    private static String normalizeLineLiteral(String raw) {
        return stripWrappingQuotes(raw == null ? "" : raw.strip()).strip();
    }

    private static String firstSentenceOrLine(String raw) {
        String trimmed = raw == null ? "" : raw.strip();
        if (trimmed.isBlank()) return "";
        if (trimmed.startsWith("```")) return trimmed;
        int newline = trimmed.indexOf('\n');
        String oneLine = newline >= 0 ? trimmed.substring(0, newline) : trimmed;
        Matcher terminator = Pattern.compile("(?<!\\.)[.!?](?:\\s|$)").matcher(oneLine);
        if (terminator.find()) {
            return oneLine.substring(0, terminator.start());
        }
        return oneLine;
    }

    private static String stripCodeFence(String value) {
        String trimmed = value == null ? "" : value.strip();
        if (!trimmed.startsWith("```")) return trimmed;
        int firstLine = trimmed.indexOf('\n');
        int endFence = trimmed.lastIndexOf("```");
        if (firstLine < 0 || endFence <= firstLine) return trimmed;
        return trimmed.substring(firstLine + 1, endFence);
    }

    private static String stripWrappingQuotes(String value) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.length() < 2) return trimmed;
        char first = trimmed.charAt(0);
        char last = trimmed.charAt(trimmed.length() - 1);
        if ((first == '"' && last == '"')
                || (first == '\'' && last == '\'')
                || (first == '`' && last == '`')) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String normalizePath(String path) {
        String normalized = path == null ? "" : path.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private record Candidate(String literal, String sourcePattern, boolean alreadyExact) {
        private Candidate(String literal, String sourcePattern) {
            this(literal, sourcePattern, false);
        }
    }
}
