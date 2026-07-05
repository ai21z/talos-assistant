package dev.talos.runtime.expectation;

import dev.talos.runtime.task.TaskContract;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
    private static final Pattern EXACT_BULLET_COUNT = Pattern.compile(
            "(?is)\\bexactly\\s+"
                    + "(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|\\d{1,2})"
                    + "\\s+(?:bullet\\s+points?|bullets?|list\\s+items?)\\b");
    private static final Pattern PRESERVE_REST = Pattern.compile(
            "(?is)\\b(?:preserve|keep|leave)\\s+(?:the\\s+)?"
                    + "(?:rest|remainder|remaining\\s+content|everything\\s+else|other\\s+content)\\b"
                    + "|\\bdo\\s+not\\s+change\\s+"
                    + "(?:anything\\s+else|the\\s+rest|everything\\s+else|other\\s+content)\\b"
                    + "|\\bwithout\\s+changing\\s+"
                    + "(?:anything\\s+else|the\\s+rest|everything\\s+else|other\\s+content)\\b");
    private static final Pattern SELECTOR_CHANGE_TO = Pattern.compile(
            "(?is)\\b(?:change|changing|update|updating)\\s+"
                    + "([#.][A-Za-z_][A-Za-z0-9_-]*)\\s+to\\s+"
                    + "([#.][A-Za-z_][A-Za-z0-9_-]*)\\b");
    private static final String NEXT_MUTATION_CLAUSE =
            "\\b(?:then|and|first|second|third|next|also)\\s+(?:replace|change|update|set)\\b";
    private static final String TARGET_REFERENCE_CLAUSE =
            "\\bin\\s+`?[A-Za-z0-9_./\\\\-]+\\.[A-Za-z0-9][A-Za-z0-9_-]*`?";
    private static final String REPLACEMENT_TEXT =
            "(?:(?!(?:" + NEXT_MUTATION_CLAUSE + "|" + TARGET_REFERENCE_CLAUSE + ")).)+?";

    private TaskExpectationResolver() {}

    public static List<TaskExpectation> resolve(TaskContract contract) {
        if (contract == null || contract.expectedTargets().isEmpty()) return List.of();
        String request = contract.originalUserRequest();
        if (request == null || request.isBlank()) return List.of();
        if (contract.expectedTargets().size() != 1) {
            return resolveTargetSpecificExpectations(contract, request);
        }
        String target = contract.expectedTargets().iterator().next();
        if (target == null || target.isBlank()) return List.of();

        String normalizedTarget = normalizePath(target);
        List<TaskExpectation> structuralExpectations = resolveStructuralExpectations(request, normalizedTarget);
        List<Candidate> candidates = new ArrayList<>();
        addTargetSpecificExactCandidates(request, normalizedTarget, candidates);
        addTargetContainingExactlyCandidates(request, normalizedTarget, candidates);
        addCompleteFileTwoLineCandidate(request, candidates);
        addGenericCandidate(request, ENTIRE_FILE_SHOULD_BE, "literal-entire-file", candidates);
        addGenericCandidate(request, CONTENT_ARGUMENT_EXACT, "literal-content-argument", candidates);
        addGenericCandidate(request, WHOLE_FILE_REPLACE, "literal-whole-file-replace", candidates);
        addGenericCandidate(request, WRITE_EXACT_CONTENT, "literal-write-exact-content", candidates);

        if (candidates.isEmpty()) return structuralExpectations;

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
        if (literals.size() != 1) return structuralExpectations;

        List<TaskExpectation> expectations = new ArrayList<>(structuralExpectations);
        expectations.add(new LiteralContentExpectation(
                normalizedTarget,
                literals.iterator().next(),
                LiteralContentExpectation.MatchMode.EXACT,
                firstSourcePattern));
        return List.copyOf(expectations);
    }

    private static List<TaskExpectation> resolveStructuralExpectations(
            String request,
            String normalizedTarget
    ) {
        if (normalizedTarget == null || normalizedTarget.isBlank()) {
            return List.of();
        }
        List<TaskExpectation> expectations = new ArrayList<>();
        ReplacementExpectation replacement = replacementExpectation(request, normalizedTarget);
        if (replacement != null) {
            expectations.add(replacement);
        }
        AppendLineExpectation appendLine = appendLineExpectation(request, normalizedTarget);
        if (appendLine != null) {
            expectations.add(appendLine);
        }
        int bulletCount = exactBulletCount(request);
        if (bulletCount > 0) {
            expectations.add(new BulletListExpectation(
                    normalizedTarget,
                    bulletCount,
                    "bullet-list-exact-count"));
        }
        return List.copyOf(expectations);
    }

    private static List<TaskExpectation> resolveTargetSpecificExpectations(
            TaskContract contract,
            String request
    ) {
        List<TaskExpectation> expectations = new ArrayList<>();
        for (String target : contract.expectedTargets()) {
            if (target == null || target.isBlank()) continue;
            String normalizedTarget = normalizePath(target);
            ReplacementExpectation replacement = replacementExpectation(request, normalizedTarget, false);
            if (replacement != null) {
                expectations.add(replacement);
            }
            List<Candidate> candidates = new ArrayList<>();
            addTargetSpecificExactCandidates(request, normalizedTarget, candidates);
            addTargetContainingExactlyCandidates(request, normalizedTarget, candidates);
            if (candidates.isEmpty()) continue;

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
            if (literals.size() == 1) {
                expectations.add(new LiteralContentExpectation(
                        normalizedTarget,
                        literals.iterator().next(),
                        LiteralContentExpectation.MatchMode.EXACT,
                        firstSourcePattern));
            }
        }
        return List.copyOf(expectations);
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

    private static int exactBulletCount(String request) {
        if (request == null || request.isBlank()) return 0;
        Matcher matcher = EXACT_BULLET_COUNT.matcher(request);
        if (!matcher.find()) return 0;
        return numberToken(matcher.group(1));
    }

    private static AppendLineExpectation appendLineExpectation(String request, String normalizedTarget) {
        if (request == null || request.isBlank() || normalizedTarget == null || normalizedTarget.isBlank()) {
            return null;
        }
        String quoted = Pattern.quote(normalizedTarget);
        Pattern appendLineAfterTarget = Pattern.compile(
                "(?is)\\bappend\\s+(?:exactly\\s+this\\s+line|one\\s+line|line)"
                        + "\\s+to\\s+`?" + quoted + "`?\\s*:\\s*(.+)");
        Matcher matcher = appendLineAfterTarget.matcher(request);
        if (!matcher.find()) {
            Pattern appendLineBeforeTarget = Pattern.compile(
                    "(?is)\\bappend\\s+(?:exactly\\s+this\\s+line|one\\s+line|the\\s+line|line)"
                            + "\\s+(.+?)\\s+to\\s+`?" + quoted + "`?"
                            + "(?=$|\\s|[`'\"),;:!?\\]]|\\.(?:$|\\s))");
            matcher = appendLineBeforeTarget.matcher(request);
            if (!matcher.find()) return null;
        }
        String line = normalizeAppendLine(matcher.group(1));
        if (line.isBlank()) return null;
        return new AppendLineExpectation(normalizedTarget, line, "append-line-exact");
    }

    private static ReplacementExpectation replacementExpectation(String request, String normalizedTarget) {
        return replacementExpectation(request, normalizedTarget, true);
    }

    private static ReplacementExpectation replacementExpectation(
            String request,
            String normalizedTarget,
            boolean allowTargetAgnosticSelector
    ) {
        if (request == null || request.isBlank() || normalizedTarget == null || normalizedTarget.isBlank()) {
            return null;
        }
        String quoted = Pattern.quote(normalizedTarget);
        boolean preserveRest = preserveRestRequested(request);
        Pattern replaceWithInTarget = Pattern.compile(
                "(?is)\\breplace\\s+(" + REPLACEMENT_TEXT + ")\\s+with\\s+"
                        + "(" + REPLACEMENT_TEXT + ")\\s+in\\s+`?"
                        + quoted + "`?(?=$|\\s|[`'\"),;:!?\\]]|\\.(?:$|\\s))");
        Matcher matcher = replaceWithInTarget.matcher(request);
        if (matcher.find()) {
            return replacementExpectation(
                    normalizedTarget,
                    matcher.group(1),
                    matcher.group(2),
                    "replacement-replace-with-in-target",
                    preserveRest);
        }
        Pattern continuedReplaceWithInTarget = Pattern.compile(
                "(?is)\\b(?:and|then|also|next|second|third)\\s+"
                        + "(" + REPLACEMENT_TEXT + ")\\s+with\\s+"
                        + "(" + REPLACEMENT_TEXT + ")\\s+in\\s+`?"
                        + quoted + "`?(?=$|\\s|[`'\"),;:!?\\]]|\\.(?:$|\\s))");
        matcher = continuedReplaceWithInTarget.matcher(request);
        if (matcher.find()) {
            return replacementExpectation(
                    normalizedTarget,
                    matcher.group(1),
                    matcher.group(2),
                    "replacement-continued-with-in-target",
                    preserveRest);
        }

        Pattern changeFromToInTarget = Pattern.compile(
                "(?is)\\b(?:change|update|set)\\s+(?:the\\s+)?(?:page\\s+)?"
                        + "(?:title|text|label|string|word|phrase)\\s+from\\s+"
                        + "(" + REPLACEMENT_TEXT + ")\\s+to\\s+"
                        + "(" + REPLACEMENT_TEXT + ")\\s+in\\s+`?"
                        + quoted + "`?(?=$|\\s|[`'\"),;:!?\\]]|\\.(?:$|\\s))");
        matcher = changeFromToInTarget.matcher(request);
        if (matcher.find()) {
            return replacementExpectation(
                    normalizedTarget,
                    matcher.group(1),
                    matcher.group(2),
                    "replacement-change-from-to-in-target",
                    preserveRest);
        }

        if (!allowTargetAgnosticSelector) return null;
        matcher = SELECTOR_CHANGE_TO.matcher(request);
        if (!matcher.find()) return null;
        return replacementExpectation(
                normalizedTarget,
                matcher.group(1),
                matcher.group(2),
                "replacement-changing-to-expected-target",
                true);
    }

    private static ReplacementExpectation replacementExpectation(
            String normalizedTarget,
            String rawOldText,
            String rawNewText,
            String sourcePattern,
            boolean preserveRest
    ) {
        String oldText = normalizeReplacementText(rawOldText);
        String newText = normalizeReplacementText(rawNewText);
        if (oldText.isBlank() || newText.isBlank()) return null;
        return new ReplacementExpectation(normalizedTarget, oldText, newText, sourcePattern, preserveRest);
    }

    private static boolean preserveRestRequested(String request) {
        return request != null && PRESERVE_REST.matcher(request).find();
    }

    private static String normalizeReplacementText(String raw) {
        if (raw == null) return "";
        String trimmed = raw.strip();
        int newline = trimmed.indexOf('\n');
        if (newline >= 0) {
            trimmed = trimmed.substring(0, newline).strip();
        }
        return stripWrappingQuotes(trimmed).strip();
    }

    private static String normalizeAppendLine(String raw) {
        if (raw == null) return "";
        String trimmed = raw.strip();
        int newline = trimmed.indexOf('\n');
        if (newline >= 0) {
            trimmed = trimmed.substring(0, newline).strip();
        }
        trimmed = stripWrappingQuotes(trimmed).strip();
        return trimmed;
    }

    private static int numberToken(String raw) {
        String token = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (token.isBlank()) return 0;
        if (token.matches("\\d{1,2}")) return Integer.parseInt(token);
        return switch (token) {
            case "one" -> 1;
            case "two" -> 2;
            case "three" -> 3;
            case "four" -> 4;
            case "five" -> 5;
            case "six" -> 6;
            case "seven" -> 7;
            case "eight" -> 8;
            case "nine" -> 9;
            case "ten" -> 10;
            case "eleven" -> 11;
            case "twelve" -> 12;
            default -> 0;
        };
    }

    private record Candidate(String literal, String sourcePattern, boolean alreadyExact) {
        private Candidate(String literal, String sourcePattern) {
            this(literal, sourcePattern, false);
        }
    }
}
