package dev.talos.runtime.intent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Whole-request historical action selection, without fresh execution authority. */
public record HistoricalAttributionRequest(String originalRequest, List<Result> results) {
    public enum Action { FILE_EDIT, TEST_EXECUTION }
    public record Result(Action action, String target) {
        public Result {
            if (action == null || target == null
                    || action == Action.FILE_EDIT && !validTarget(target)
                    || action == Action.TEST_EXECUTION && !target.isEmpty()) {
                throw new IllegalArgumentException("Historical attribution result");
            }
        }
    }
    private static final int MAX_LENGTH = 2048;
    private static final int MAX_RESULTS = 16;
    private static final String ACTOR = "(?:you|talos)";
    private static final String POLITE = "(?:please,? )?";
    private static final Pattern QUESTION = Pattern.compile("(?i)" + POLITE
            + "(?:for (?:my|the) (?:notes|report|handoff),? )?(?:"
            + "(?:did " + ACTOR + "|have you|has talos) "
            + "|(?:can|could|may) i (?:say|report|write|note)(?: that)? " + ACTOR + " "
            + "|is it (?:correct|accurate|supported) to (?:say|report)(?: that)? " + ACTOR + " "
            + ")(?<actions>.+)");
    private static final String SCOPE = "(?: (?:for me|earlier|previously|"
            + "(?:in|during) (?:this|the current) (?:session|conversation|chat)))?";
    private static final String TARGET = "(?:[A-Za-z0-9_./\\\\-]+|`[A-Za-z0-9_./\\\\-]+`|"
            + "\"[A-Za-z0-9_./\\\\-]+\"|'[A-Za-z0-9_./\\\\-]+')";
    private static final Pattern EDIT = Pattern.compile("(?i)(?:" + ACTOR + " )?"
            + "(?:edit|edited|update|updated|change|changed|modify|modified|write|written) "
            + "(?:the file )?(?<target>" + TARGET + ")" + SCOPE);
    private static final Pattern TESTS = Pattern.compile("(?i)(?:" + ACTOR + " )?"
            + "(?:run|ran|execute|executed) (?:any |the )?tests" + SCOPE);
    private static final Pattern QUALIFIER = Pattern.compile("(?i)" + POLITE + "(?:"
            + "correct (?:that|this|the) statement if it is not supported"
            + "|(?:state|include) (?:any )?(?:uncertainty|limitations)"
            + "|(?:do not|don't|dont) (?:change|edit|read|inspect|run|execute)"
            + "(?: (?:files|anything|tests|commands))?"
            + "(?: or (?:change|edit|read|inspect|run|execute)(?: files| anything| tests| commands)?){0,3}"
            + "(?: now)?|keep (?:it|the answer) (?:short|brief)|be honest|briefly)");

    public HistoricalAttributionRequest {
        results = List.copyOf(results);
        if (originalRequest == null || originalRequest.isBlank() || originalRequest.length() > MAX_LENGTH
                || results.isEmpty() || results.size() > MAX_RESULTS) {
            throw new IllegalArgumentException("Historical attribution bounds");
        }
    }

    public static Optional<HistoricalAttributionRequest> parse(String original) {
        if (original == null || original.isBlank() || original.length() > MAX_LENGTH) return Optional.empty();
        var view = RequestLexicalView.of(original);
        if (view.containsMalformedDelimiter(0, original.length())
                || view.containsProviderRemovedControl(0, original.length())
                || view.containsLoneCarriageReturn(0, original.length())) return Optional.empty();
        var results = new LinkedHashSet<Result>();
        int sentences = 0;
        for (int start = 0; start < original.length();) {
            int end = view.sentenceEndAfter(start);
            var span = view.trimmedSpan(start, end);
            if (++sentences > MAX_RESULTS || span.start() >= span.end()
                    || !view.isTopLevel(span.start()) || view.isQuoted(span.start())) return Optional.empty();
            String text = view.substring(span).strip().replace('\u2019', '\'')
                    .replaceAll("\\s+", " ").replaceAll("[.!?]+$", "");
            if (QUALIFIER.matcher(text).matches()) {
                if (!view.isUnquotedRange(span.start(), span.end())) return Optional.empty();
            } else {
                var question = QUESTION.matcher(text);
                if (!question.matches()) return Optional.empty();
                for (String action : question.group("actions").split("(?i)\\s+and\\s+", -1)) {
                    var edit = EDIT.matcher(action);
                    if (edit.matches()) {
                        String target = edit.group("target");
                        if (target.startsWith("`") || target.startsWith("\"") || target.startsWith("'"))
                            target = target.substring(1, target.length() - 1);
                        target = target.replace('\\', '/');
                        while (target.startsWith("./")) target = target.substring(2);
                        if (!validTarget(target)) return Optional.empty();
                        results.add(new Result(Action.FILE_EDIT, target));
                    } else if (TESTS.matcher(action).matches()) {
                        results.add(new Result(Action.TEST_EXECUTION, ""));
                    } else return Optional.empty();
                    if (results.size() > MAX_RESULTS) return Optional.empty();
                }
            }
            start = end;
        }
        return results.isEmpty() ? Optional.empty()
                : Optional.of(new HistoricalAttributionRequest(original, new ArrayList<>(results)));
    }

    public WorkStatusRequest asWorkStatusRequest() {
        var roles = new LinkedHashSet<WorkStatusRequest.Role>();
        for (var result : results) roles.add(result.action() == Action.FILE_EDIT
                ? WorkStatusRequest.Role.ACTIONS : WorkStatusRequest.Role.TESTING);
        return new WorkStatusRequest(originalRequest, true, WorkStatusRequest.Coverage.COVERED,
                List.of(new WorkStatusRequest.Component(0, originalRequest.length(), Set.copyOf(roles))));
    }

    private static boolean validTarget(String target) {
        return !target.isEmpty() && target.length() <= 256 && target.matches("[A-Za-z0-9_./-]+")
                && !target.startsWith("/") && !target.endsWith("/") && target.contains(".")
                && java.util.Arrays.stream(target.split("/", -1))
                        .noneMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."));
    }
}
