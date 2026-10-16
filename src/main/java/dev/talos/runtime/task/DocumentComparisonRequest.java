package dev.talos.runtime.task;

import dev.talos.core.diff.DocumentPairAdmission.RequestedPair;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Ordered operands are bound only from bounded regions of the unchanged request. */
public record DocumentComparisonRequest(String request, List<Requirement> requirements, List<String> unresolved) {
    private static final String FILE = "(?:`[A-Za-z0-9_./\\\\-]+\\.[A-Za-z0-9]{1,12}`|[A-Za-z0-9_./\\\\-]+\\.[A-Za-z0-9]{1,12})";
    private static final String WRAPPER = "(?:please\\s+|(?:can|could|would)\\s+you\\s+(?:please\\s+)?)?";
    private static final Pattern PAIR = pattern(WRAPPER + "(?:compare|distinguish)\\s+(" + FILE + ")\\s+and\\s+(" + FILE + ")");
    private static final Pattern CHANGED = pattern("what\\s+(?:changed|differs)\\s+between\\s+(" + FILE + ")\\s+and\\s+(" + FILE + ")\\??");
    private static final Pattern EXPLAIN = pattern(WRAPPER + "(?:explain|describe)\\s+how\\s+(" + FILE + ")\\s+differs\\s+from\\s+(" + FILE + ")\\??");
    private static final Pattern SCRIPTS = pattern(WRAPPER + "(?:compare|distinguish)\\s+(?:the\\s+)?two\\s+(?:JavaScript|JS)\\s+files");
    private static final Pattern COMPARISON_ACTION = pattern("^" + WRAPPER + "(?:compare|distinguish|diff)\\b");
    private static final Pattern READ_PREREQUISITE = pattern("^" + WRAPPER + "(?:read|inspect)\\s+" + FILE
            + "(?:\\s*(?:,\\s*(?:and\\s+)?|and\\s+)" + FILE + "){0,7}");
    private static final Pattern COMPARISON_CONNECTOR = pattern("^(?:,\\s*(?:(?:and\\s+)?then\\s+|and\\s+)?"
            + "|(?:and\\s+)?then\\s+|and\\s+)");
    private static final Pattern DOCUMENT_PRONOUN = pattern("^(?:them|it|these|those|both)\\b");
    private static final Pattern COMPARISON_QUESTION = pattern("^" + WRAPPER
            + "(?:(?:explain|describe|show|summarize)\\s+)?(?:what\\s+(?:changed|differs)\\s+between\\b"
            + "|what\\s+(?:is|are)\\s+(?:the\\s+)?(?:comparisons?|differences?|distinctions?)\\b"
            + "|(?:how|what\\s+(?:is|are)|is|are|do|does)\\b.*\\b(?:differ|differs|different)\\b)");
    private static final Pattern REQUEST_HEAD = pattern("^" + WRAPPER
            + "(?:(?:explain|describe|show|summarize|list|identify|give|provide)\\s+(?:(?:me|us)\\s+)?"
            + "|tell\\s+(?:me|us)\\s+|i\\s+(?:need|want|would\\s+like)\\s+)");
    private static final Pattern COMPARISON_RELATION = pattern("\\b(?:comparisons?|diffs?|differences?|distinctions?)"
            + "\\s+(?:of|between|among|in|from)\\b");
    private static final Pattern HISTORICAL_RESULT_MODIFIER = pattern("\\b(?:earlier|previous|prior|past)\\s*$");
    private static final Pattern QUESTION_BOUNDARY = Pattern.compile("\\?\\s+");
    private static final Pattern DOCUMENT_WORD = pattern("\\b(?:files?|documents?|scripts?)\\b");
    private static final Pattern QUOTED_FILE = Pattern.compile("[`\"'\u201c\u2018][^`\"'\u201d\u2019\\r\\n]+\\.[A-Za-z0-9]{1,12}[`\"'\u201d\u2019]");

    public record Requirement(String id, int start, int end, RequestedPair pair, boolean explanationRequired) { }

    public DocumentComparisonRequest {
        requirements = List.copyOf(requirements);
        unresolved = List.copyOf(unresolved);
    }

    public static DocumentComparisonRequest from(TaskContract contract) {
        return contract == null || contract.mutationAllowed() || contract.mutationRequested()
                ? from("") : from(contract.originalUserRequest());
    }

    public static DocumentComparisonRequest from(String request) {
        String text = request == null ? "" : request;
        if (text.length() > AnswerRequirements.MAX_REQUEST_CHARS) return new DocumentComparisonRequest(text, List.of(), List.of());
        var meaning = ReadOnlyRequestMeaning.compile(text);
        String boundaryText = boundaryText(text);
        String assessmentBoundary = assessmentBoundary(text);
        var result = new ArrayList<Requirement>();
        var unresolved = new ArrayList<String>();
        for (var slot : meaning.requirements().slots()) {
            if (slot.role() != AnswerResultRequirements.Role.UNKNOWN) continue;
            String clause = text.substring(slot.start(), slot.end()).strip();
            // A present comparison request is required, not a mention of earlier work or changed files.
            // This routing test can require an unresolved result, never authorize operands.
            boolean assessment = (comparisonIntent(text.substring(slot.start(), slot.end()),
                    assessmentBoundary.substring(slot.start(), slot.end()))
                    || precedingReadComparison(text, assessmentBoundary, meaning, slot))
                    && dev.talos.runtime.intent.AnswerInterpretationBoundary.eligible(
                        assessmentBoundary, slot.start(), slot.end());
            if (!dev.talos.runtime.intent.AnswerInterpretationBoundary.eligible(boundaryText, slot.start(), slot.end())) {
                if (assessment) unresolved.add(slot.id());
                continue;
            }
            RequestedPair pair = null;
            boolean explanation = false;
            for (var pattern : List.of(PAIR, CHANGED, EXPLAIN)) {
                var match = pattern.matcher(clause);
                if (!match.matches()) continue;
                pair = new RequestedPair(path(match.group(1)), path(match.group(2)));
                explanation = pattern == EXPLAIN || clause.toLowerCase(Locale.ROOT).matches(".*\\bdistinguish\\b.*");
                break;
            }
            if (pair == null && SCRIPTS.matcher(clause).matches()) {
                var scripts = meaning.readReferences().stream().filter(read -> meaning.regions().stream().anyMatch(region ->
                        region.disposition() == ReadOnlyRequestMeaning.Disposition.READ
                        && region.start() <= read.start() && region.end() >= read.end()
                        && dev.talos.runtime.intent.AnswerInterpretationBoundary.eligible(boundaryText, region.start(), region.end())))
                        .map(ReadOnlyRequestMeaning.ReadReference::path)
                        .map(DocumentComparisonRequest::path)
                        .filter(value -> value.toLowerCase(Locale.ROOT).endsWith(".js")).distinct().toList();
                if (scripts.size() == 2) pair = new RequestedPair(scripts.get(0), scripts.get(1));
                explanation = true;
            }
            if (pair != null) result.add(new Requirement(slot.id(), slot.start(), slot.end(), pair, explanation));
            else if (assessment) unresolved.add(slot.id());
        }
        return new DocumentComparisonRequest(text, result, unresolved);
    }

    public boolean active() { return !requirements.isEmpty(); }
    public boolean requiresAssessment() { return active() || !unresolved.isEmpty(); }
    public boolean comparisonOnly() {
        return ReadOnlyRequestMeaning.compile(request).readReferences().isEmpty() && comparisonResultsOnly();
    }
    public boolean comparisonResultsOnly() {
        if (!active()) return false;
        var meaning = ReadOnlyRequestMeaning.compile(request);
        return meaning.requirements().slots().stream()
                .allMatch(slot -> requirements.stream().anyMatch(value -> value.id().equals(slot.id())))
                && meaning.requirements().regionReasons().keySet().stream()
                .allMatch(id -> requirements.stream().anyMatch(value -> value.id().equals(id)));
    }
    public List<Requirement> matching(RequestedPair pair) {
        if (pair == null) return List.of();
        var normalized = new RequestedPair(path(pair.left()), path(pair.right()));
        return requirements.stream().filter(value -> value.pair().equals(normalized)).toList();
    }
    private static String path(String value) {
        if (value == null) return "";
        String literal = value.startsWith("`") && value.endsWith("`") ? value.substring(1, value.length() - 1) : value;
        try { return java.nio.file.Path.of(literal.replace('\\', '/')).normalize().toString().replace('\\', '/'); }
        catch (java.nio.file.InvalidPathException invalid) { return ""; }
    }
    private static Pattern pattern(String value) { return Pattern.compile(value, Pattern.CASE_INSENSITIVE); }
    private static boolean precedingReadComparison(String original, String masked,
            ReadOnlyRequestMeaning meaning, AnswerResultRequirements.Slot slot) {
        ReadOnlyRequestMeaning.Region predecessor = null;
        for (var region : meaning.regions()) {
            if (region.end() <= slot.start() && (predecessor == null || region.end() > predecessor.end()))
                predecessor = region;
        }
        if (predecessor == null || predecessor.disposition() != ReadOnlyRequestMeaning.Disposition.READ
                || !dev.talos.runtime.intent.AnswerInterpretationBoundary.eligible(
                        masked, predecessor.start(), predecessor.end())) return false;
        // Comma parsing can place the read prerequisite in a separate adjacent region.
        // The connector is rechecked across the original gap without projecting new pair authority.
        return coordinatedComparison(original.substring(predecessor.start(), slot.end()),
                masked.substring(predecessor.start(), slot.end()));
    }
    private static boolean comparisonIntent(String original, String masked) {
        // A meaning slot can contain consecutive questions. Subsegments only retain unresolved
        // obligations. Quoted file atoms are masked and the original whole-slot boundary still applies.
        int start = 0;
        var questions = QUESTION_BOUNDARY.matcher(masked);
        while (questions.find()) {
            if (comparisonSegment(original.substring(start, questions.start()), masked.substring(start, questions.start()))) return true;
            start = questions.end();
        }
        return comparisonSegment(original.substring(start), masked.substring(start));
    }
    private static boolean comparisonSegment(String original, String masked) {
        if (coordinatedComparison(original, masked)) return true;
        if (!Pattern.compile(FILE).matcher(original).find() && !DOCUMENT_WORD.matcher(original).find()) return false;
        String text = masked.strip();
        if (COMPARISON_ACTION.matcher(text).find() || COMPARISON_QUESTION.matcher(text).find()) return true;
        var head = REQUEST_HEAD.matcher(text);
        if (!head.find()) return false;
        var relation = COMPARISON_RELATION.matcher(text).region(head.end(), text.length());
        if (!relation.find()) return false;
        // Only a temporal modifier immediately owning the comparison noun establishes a prior
        // result. A trailing work modifier can describe the operands, so its obligation is retained.
        return !HISTORICAL_RESULT_MODIFIER.matcher(text.substring(head.end(), relation.start())).find();
    }
    private static boolean coordinatedComparison(String original, String masked) {
        int leading = original.length() - original.stripLeading().length();
        var read = READ_PREREQUISITE.matcher(original.substring(leading));
        if (!read.find()) return false;
        int continuation = leading + read.end();
        while (continuation < masked.length() && Character.isWhitespace(masked.charAt(continuation))) continuation++;
        var connector = COMPARISON_CONNECTOR.matcher(masked.substring(continuation));
        if (!connector.find()) return false;
        continuation += connector.end();
        var action = COMPARISON_ACTION.matcher(masked.substring(continuation));
        if (!action.find()) return false;
        continuation += action.end();
        // A read dependency can establish document reference for an unresolved comparison.
        // It cannot supply an ordered pair or change the original region's eligibility.
        String reference = original.substring(continuation).strip();
        return DOCUMENT_PRONOUN.matcher(reference).find() || DOCUMENT_WORD.matcher(reference).find()
                || Pattern.compile(FILE).matcher(reference).find();
    }
    private static String boundaryText(String request) {
        var masked = new StringBuilder(request);
        var files = Pattern.compile(FILE).matcher(request);
        while (files.find()) for (int index = files.start(); index < files.end(); index++) masked.setCharAt(index, ' ');
        return masked.toString();
    }
    private static String assessmentBoundary(String request) {
        var masked = new StringBuilder(request);
        var files = QUOTED_FILE.matcher(request);
        while (files.find()) for (int index = files.start(); index < files.end(); index++) masked.setCharAt(index, ' ');
        return boundaryText(masked.toString());
    }
    @Override public String toString() { return "DocumentComparisonRequest[requirements=" + requirements.size() + "]"; }
}
