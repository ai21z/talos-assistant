package dev.talos.runtime.verification;

import dev.talos.core.security.WorkspaceContainment;
import dev.talos.runtime.task.TaskContract;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Verifies narrow, explicit static-web text postconditions from post-write readback. */
final class StaticWebContentRequirementVerifier {
    private static final String HEADING_REQUIREMENT_END =
            "(?=\\s+and\\s+(?:(?:the\\s+)?(?:status\\s+)?paragraph\\b|"
                    + "(?:do\\s+not|don't|leave|keep|preserve|touch\\s+no|change\\s+no)\\b)|[.!?]|$)";
    private static final String CONTENT_REQUIREMENT_END =
            "(?=\\s+and\\s+(?:do\\s+not|don't|leave|keep|preserve|touch\\s+no|change\\s+no)\\b|"
                    + "[.!?]|$)";
    private static final Pattern HEADING_ACTION_REQUIREMENT = Pattern.compile(
            "(?is)\\b(?:set|change|update|make)\\s+(?:the\\s+)?(?:page\\s+)?heading\\s+"
                    + "(?:is|to|should\\s+be|must\\s+be)\\s+(.+?)" + HEADING_REQUIREMENT_END);
    private static final Pattern HEADING_RESULT_REQUIREMENT = Pattern.compile(
            "(?is)\\b(?:so(?:\\s+that)?|ensure(?:\\s+that)?)\\s+(?:the\\s+)?"
                    + "(?:page\\s+)?heading\\s+(?:is|should\\s+be|must\\s+be)\\s+(.+?)"
                    + HEADING_REQUIREMENT_END);
    private static final Pattern HEADING_NORMATIVE_REQUIREMENT = Pattern.compile(
            "(?is)\\b(?:the\\s+)?(?:page\\s+)?heading\\s+(?:should|must)\\s+be\\s+(.+?)"
                    + HEADING_REQUIREMENT_END);
    private static final Pattern PARAGRAPH_ACTION_REQUIREMENT = Pattern.compile(
            "(?is)\\b(?:set|change|update|make)\\s+(?:the\\s+)?(?:status\\s+)?paragraph\\s+"
                    + "(?:to\\s+)?(?:start|begin)(?:s|ning)?(?:\\s+with)?\\s+(.+?)"
                    + CONTENT_REQUIREMENT_END);
    private static final Pattern PARAGRAPH_RESULT_REQUIREMENT = Pattern.compile(
            "(?is)\\b(?:so(?:\\s+that)?|ensure(?:\\s+that)?)\\s+(?:the\\s+)?"
                    + "(?:status\\s+)?paragraph\\s+(?:starts|begins)(?:\\s+with)?\\s+(.+?)"
                    + CONTENT_REQUIREMENT_END);
    private static final Pattern PARAGRAPH_NORMATIVE_REQUIREMENT = Pattern.compile(
            "(?is)\\b(?:the\\s+)?(?:status\\s+)?paragraph\\s+(?:should|must)\\s+"
                    + "(?:start|begin)(?:\\s+with)?\\s+(.+?)" + CONTENT_REQUIREMENT_END);
    private static final Pattern CONJOINED_PARAGRAPH_REQUIREMENT = Pattern.compile(
            "(?is)\\band\\s+(?:the\\s+)?(?:status\\s+)?paragraph\\s+"
                    + "(?:starts|begins)(?:\\s+with)?\\s+(.+?)" + CONTENT_REQUIREMENT_END);
    private static final Pattern NEGATED_ACTION_PREFIX = Pattern.compile(
            "(?is).*\\b(?:do\\s+not|don't|never)\\s+(?:ever\\s+)?$");
    private static final Pattern HTML_COMMENT = Pattern.compile("(?is)<!--.*?-->");
    private static final Pattern HEADING_ELEMENT = Pattern.compile(
            "(?is)<h([1-6])\\b[^>]*>(.*?)</h\\1\\s*>");
    private static final Pattern PARAGRAPH_ELEMENT = Pattern.compile(
            "(?is)<p\\b([^>]*)>(.*?)</p\\s*>");
    private static final Pattern STATUS_LIKE_ATTRIBUTE = Pattern.compile(
            "(?is)\\b(?:id|class|name|aria-label)\\s*=\\s*(['\"])"
                    + "[^'\"]*(?:status|message|result|output)[^'\"]*\\1");
    private static final Pattern NON_VISIBLE_BLOCK = Pattern.compile(
            "(?is)<(?:script|style|template)\\b[^>]*>.*?</(?:script|style|template)\\s*>");
    private static final Pattern HTML_TAG = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private StaticWebContentRequirementVerifier() {}

    static VerificationReport verify(Path root, TaskContract contract) {
        List<Requirement> requirements = resolve(contract == null ? "" : contract.originalUserRequest());
        if (requirements.isEmpty()) return VerificationReport.empty();

        TargetReadback target = readTarget(root, contract);
        List<ClaimResult> results = new ArrayList<>();
        for (Requirement requirement : requirements) {
            results.add(verifyRequirement(requirement, target));
        }
        return report(results);
    }

    private static List<Requirement> resolve(String request) {
        if (request == null || request.isBlank()) return List.of();
        List<Requirement> out = new ArrayList<>();
        ExpectedMatch heading = firstPositiveMatch(
                request,
                HEADING_ACTION_REQUIREMENT,
                HEADING_RESULT_REQUIREMENT,
                HEADING_NORMATIVE_REQUIREMENT);
        if (heading != null) {
            out.add(new Requirement(Kind.HEADING_TEXT, heading.expected()));
        }
        ExpectedMatch paragraph = firstPositiveMatch(
                request,
                PARAGRAPH_ACTION_REQUIREMENT,
                PARAGRAPH_RESULT_REQUIREMENT,
                PARAGRAPH_NORMATIVE_REQUIREMENT);
        if (paragraph == null && heading != null) {
            paragraph = conjoinedParagraphAfter(request, heading);
        }
        if (paragraph != null) {
            out.add(new Requirement(Kind.STATUS_PARAGRAPH_PREFIX, paragraph.expected()));
        }
        return List.copyOf(out);
    }

    private static ExpectedMatch firstPositiveMatch(String request, Pattern... patterns) {
        if (request == null || request.isBlank() || patterns == null) return null;
        for (Pattern pattern : patterns) {
            if (pattern == null) continue;
            Matcher matcher = pattern.matcher(request);
            while (matcher.find()) {
                if (hasNegatedActionPrefix(request, matcher.start())) continue;
                String expected = normalizeExpected(matcher.group(1));
                if (!expected.isBlank()) return new ExpectedMatch(expected, matcher.start(), matcher.end());
            }
        }
        return null;
    }

    private static ExpectedMatch conjoinedParagraphAfter(String request, ExpectedMatch heading) {
        if (request == null || heading == null) return null;
        Matcher matcher = CONJOINED_PARAGRAPH_REQUIREMENT.matcher(request);
        while (matcher.find()) {
            if (matcher.start() < heading.end()) continue;
            String between = request.substring(heading.end(), matcher.start());
            if (between.matches("(?s).*[.!?;].*")) continue;
            String expected = normalizeExpected(matcher.group(1));
            if (!expected.isBlank()) return new ExpectedMatch(expected, matcher.start(), matcher.end());
        }
        return null;
    }

    private static boolean hasNegatedActionPrefix(String request, int matchStart) {
        if (request == null || request.isBlank() || matchStart <= 0) return false;
        int clauseStart = Math.max(
                Math.max(request.lastIndexOf('.', matchStart - 1), request.lastIndexOf('!', matchStart - 1)),
                Math.max(request.lastIndexOf('?', matchStart - 1), request.lastIndexOf(';', matchStart - 1)));
        String prefix = request.substring(clauseStart + 1, matchStart);
        return NEGATED_ACTION_PREFIX.matcher(prefix).matches();
    }

    private static ClaimResult verifyRequirement(Requirement requirement, TargetReadback target) {
        String targetName = target.pathHint().isBlank() ? "HTML target" : "`" + target.pathHint() + "`";
        VerificationClaim claim = new VerificationClaim(
                "static-web-content:" + requirement.kind().id() + ":" + target.pathHint(),
                requirement.kind().description() + " in " + targetName + ".",
                ProofKind.READBACK,
                null,
                true);
        VerificationObligation obligation = new VerificationObligation(
                claim,
                Set.of(ProofKind.READBACK),
                EvidenceAuthority.AUTHORITATIVE,
                null);
        if (!target.available()) {
            return new ClaimResult(
                    claim,
                    obligation,
                    VerificationVerdict.UNAVAILABLE,
                    ProofKind.READBACK,
                    EvidenceAuthority.AUTHORITATIVE,
                    EvidenceCoverage.EXACT,
                    List.of(),
                    List.of(),
                    List.of(target.problem()));
        }

        boolean matched = switch (requirement.kind()) {
            case HEADING_TEXT -> headingTextMatches(target.content(), requirement.expected());
            case STATUS_PARAGRAPH_PREFIX -> statusParagraphStartsWith(target.content(), requirement.expected());
        };
        if (matched) {
            return new ClaimResult(
                    claim,
                    obligation,
                    VerificationVerdict.VERIFIED,
                    ProofKind.READBACK,
                    EvidenceAuthority.AUTHORITATIVE,
                    EvidenceCoverage.EXACT,
                    List.of(target.pathHint() + ": " + requirement.kind().fact() + "."),
                    List.of(),
                    List.of());
        }
        return new ClaimResult(
                claim,
                obligation,
                VerificationVerdict.FAILED,
                ProofKind.READBACK,
                EvidenceAuthority.AUTHORITATIVE,
                EvidenceCoverage.EXACT,
                List.of(),
                List.of(target.pathHint() + ": " + requirement.kind().problem() + "."),
                List.of());
    }

    private static TargetReadback readTarget(Path workspace, TaskContract contract) {
        if (workspace == null) {
            return TargetReadback.unavailable("Static-web content verification workspace was unavailable.");
        }
        Path root = workspace.toAbsolutePath().normalize();
        List<String> expectedHtmlTargets = contract == null
                ? List.of()
                : contract.expectedTargets().stream()
                .map(StaticWebContentRequirementVerifier::normalizePath)
                .filter(StaticWebContentRequirementVerifier::isHtmlPath)
                .distinct()
                .toList();
        String target;
        if (expectedHtmlTargets.size() == 1) {
            target = expectedHtmlTargets.getFirst();
        } else if (expectedHtmlTargets.size() > 1) {
            return TargetReadback.unavailable(
                    "Static-web content verification could not bind explicit requirements to one HTML target.");
        } else {
            List<String> visibleHtml = visibleHtmlTargets(root);
            if (visibleHtml.size() != 1) {
                return TargetReadback.unavailable(
                        "Static-web content verification could not bind explicit requirements to one HTML target.");
            }
            target = visibleHtml.getFirst();
        }

        try {
            Path candidate = root.resolve(target).toAbsolutePath().normalize();
            if (!WorkspaceContainment.contains(root, candidate) || !Files.isRegularFile(candidate)) {
                return TargetReadback.unavailable(
                        "Static-web content verification target is not a readable workspace file: `" + target + "`.");
            }
            return new TargetReadback(target, Files.readString(candidate), "");
        } catch (Exception e) {
            return TargetReadback.unavailable(
                    "Static-web content verification could not read target `" + target + "`.");
        }
    }

    private static List<String> visibleHtmlTargets(Path root) {
        if (root == null || !Files.isDirectory(root)) return List.of();
        try {
            return StaticWebSurfaceDetector.visibleRegularFiles(root).stream()
                    .map(StaticWebSurfaceDetector::visibleFileName)
                    .map(StaticWebContentRequirementVerifier::normalizePath)
                    .filter(StaticWebContentRequirementVerifier::isHtmlPath)
                    .sorted()
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static boolean headingTextMatches(String html, String expected) {
        String safeHtml = withoutComments(html);
        Matcher matcher = HEADING_ELEMENT.matcher(safeHtml);
        while (matcher.find()) {
            if (visibleText(matcher.group(2)).equals(expected)) return true;
        }
        return false;
    }

    private static boolean statusParagraphStartsWith(String html, String expected) {
        String safeHtml = withoutComments(html);
        List<Paragraph> paragraphs = new ArrayList<>();
        Matcher matcher = PARAGRAPH_ELEMENT.matcher(safeHtml);
        while (matcher.find()) {
            paragraphs.add(new Paragraph(
                    matcher.group(1) == null ? "" : matcher.group(1),
                    visibleText(matcher.group(2))));
        }
        List<Paragraph> semantic = paragraphs.stream().filter(Paragraph::statusLike).toList();
        List<Paragraph> candidates = semantic.isEmpty() ? paragraphs : semantic;
        return candidates.stream().map(Paragraph::text).anyMatch(text -> text.startsWith(expected));
    }

    private static String visibleText(String htmlFragment) {
        String value = htmlFragment == null ? "" : htmlFragment;
        value = HTML_COMMENT.matcher(value).replaceAll(" ");
        value = NON_VISIBLE_BLOCK.matcher(value).replaceAll(" ");
        value = HTML_TAG.matcher(value).replaceAll(" ");
        value = decodeCommonEntities(value);
        return WHITESPACE.matcher(value).replaceAll(" ").strip();
    }

    private static String decodeCommonEntities(String value) {
        return value
                .replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private static String withoutComments(String html) {
        return HTML_COMMENT.matcher(html == null ? "" : html).replaceAll(" ");
    }

    private static String normalizeExpected(String raw) {
        String value = raw == null ? "" : WHITESPACE.matcher(raw).replaceAll(" ").strip();
        while (value.endsWith(",")) value = value.substring(0, value.length() - 1).stripTrailing();
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '`' && last == '`')
                    || (first == '\'' && last == '\'')
                    || (first == '"' && last == '"')) {
                value = value.substring(1, value.length() - 1).strip();
            }
        }
        return value;
    }

    private static VerificationReport report(List<ClaimResult> results) {
        if (results == null || results.isEmpty()) return VerificationReport.empty();
        List<String> facts = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        for (ClaimResult result : results) {
            facts.addAll(result.facts());
            problems.addAll(result.problems());
            limitations.addAll(result.limitations());
        }
        return new VerificationReport(results, List.of(), facts, problems, limitations);
    }

    private static boolean isHtmlPath(String path) {
        String lower = normalizePath(path).toLowerCase(Locale.ROOT);
        return lower.endsWith(".html") || lower.endsWith(".htm");
    }

    private static String normalizePath(String path) {
        String normalized = path == null ? "" : path.strip().replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        return normalized;
    }

    private enum Kind {
        HEADING_TEXT(
                "heading",
                "Requested page heading",
                "requested heading text was observed in an HTML heading element",
                "requested heading text was not observed in an HTML heading element"),
        STATUS_PARAGRAPH_PREFIX(
                "status-paragraph-prefix",
                "Requested status paragraph prefix",
                "requested status paragraph prefix was observed",
                "requested status paragraph prefix was not observed");

        private final String id;
        private final String description;
        private final String fact;
        private final String problem;

        Kind(String id, String description, String fact, String problem) {
            this.id = id;
            this.description = description;
            this.fact = fact;
            this.problem = problem;
        }

        String id() {
            return id;
        }

        String description() {
            return description;
        }

        String fact() {
            return fact;
        }

        String problem() {
            return problem;
        }
    }

    private record Requirement(Kind kind, String expected) {}

    private record ExpectedMatch(String expected, int start, int end) {}

    private record TargetReadback(String pathHint, String content, String problem) {
        TargetReadback {
            pathHint = pathHint == null ? "" : pathHint;
            content = content == null ? "" : content;
            problem = problem == null ? "" : problem;
        }

        static TargetReadback unavailable(String problem) {
            return new TargetReadback("", "", problem);
        }

        boolean available() {
            return problem.isBlank();
        }
    }

    private record Paragraph(String attributes, String text) {
        Paragraph {
            attributes = attributes == null ? "" : attributes;
            text = text == null ? "" : text;
        }

        boolean statusLike() {
            return STATUS_LIKE_ATTRIBUTE.matcher(attributes).find();
        }
    }
}
