package dev.talos.runtime.intent;

import java.util.ArrayList;
import java.util.List;

/** Current top-level sentences are exposed without rewriting request identity. */
public final class CurrentRequestClauses {
    private static final int MAX_LENGTH = 8192;
    private static final int MAX_CLAUSES = 32;

    private CurrentRequestClauses() {}

    public record Clause(int start, int end, String text) {}

    /** Existing finite scope rules remain attached when top-level coordination is separated. */
    public static List<Clause> unscopedCoordinated(String request) {
        var sentences = sentences(request);
        if (sentences.isEmpty()) return List.of();
        var view = RequestLexicalView.of(request);
        var result = new ArrayList<Clause>();
        for (var sentence : sentences) {
            int start = sentence.start();
            var separators = java.util.regex.Pattern.compile("(?iu)[,;]|\\b(?:and|then)\\b")
                    .matcher(request).region(start, sentence.end());
            while (separators.find()) {
                if (!view.isUnquotedRange(separators.start(), separators.end())
                        || !view.isTopLevel(separators.start())) continue;
                addUnscoped(result, view, sentence, start, separators.start());
                start = separators.end();
            }
            addUnscoped(result, view, sentence, start, sentence.end());
            if (result.size() > MAX_CLAUSES) return List.of();
        }
        return List.copyOf(result);
    }

    private static void addUnscoped(List<Clause> result, RequestLexicalView view, Clause sentence, int start, int end) {
        var span = view.trimmedSpan(start, end);
        if (span.end() > span.start()
                && CanonicalMutationClauseMatcher.finiteScopeKind(view.masked().substring(sentence.start(), span.start()))
                    == CanonicalMutationClauseMatcher.ScopeKind.NONE
                && CanonicalMutationClauseMatcher.finitePostfixScopeKind(view.masked().substring(span.end(), sentence.end()))
                    == CanonicalMutationClauseMatcher.ScopeKind.NONE) {
            result.add(new Clause(span.start(), span.end(), view.substring(span)));
        }
    }

    public static List<Clause> sentences(String request) {
        if (request == null || request.isBlank() || request.length() > MAX_LENGTH) return List.of();
        RequestLexicalView view = RequestLexicalView.of(request);
        if (view.containsMalformedDelimiter(0, request.length())
                || view.containsProviderRemovedControl(0, request.length())
                || view.containsLoneCarriageReturn(0, request.length())) return List.of();
        for (int index = 0; index < request.length(); index++) {
            if (Character.getType(request.charAt(index)) == Character.FORMAT) return List.of();
            if (view.isQuoted(index) && (index == 0 || !view.isQuoted(index - 1))
                    && view.exactQuotedContentStartingAt(index, request.length()) == null) return List.of();
        }
        List<Clause> clauses = new ArrayList<>();
        int start = 0;
        for (int index = 0; index <= request.length(); index++) {
            if (index < request.length() && (view.isQuoted(index) || !view.isTopLevel(index)
                    || !view.isSentenceBoundaryAt(index))) continue;
            int end = index == request.length() ? index : index + 1;
            RequestLexicalView.Span span = view.trimmedSpan(start, end);
            if (span.end() > span.start() && !view.isQuoted(span.start())
                    && view.isTopLevel(span.start())) {
                String text = view.substring(span);
                clauses.add(new Clause(span.start(), span.end(), text));
                if (clauses.size() > MAX_CLAUSES) return List.of();
            }
            start = end;
        }
        return List.copyOf(clauses);
    }
}
