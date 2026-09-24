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
    private static final Pattern COMPARISON_WORD = pattern("\\b(?:compare|comparison|comparisons|comparing|diff|differ|differs|difference|differences|different|distinguish|changed)\\b");
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
            // This routing test can require an unresolved result, never authorize operands.
            boolean assessment = COMPARISON_WORD.matcher(clause).find()
                    && (Pattern.compile(FILE).matcher(clause).find() || DOCUMENT_WORD.matcher(clause).find())
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
