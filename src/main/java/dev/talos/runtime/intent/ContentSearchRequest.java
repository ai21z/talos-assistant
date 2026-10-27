package dev.talos.runtime.intent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded recognition of a scoped text search as the requested result: find the files or lines that
 * hold a term. The request asks for search evidence, not for an explanation of the project, so a
 * workspace word inside it does not make it a project explanation. Quoted material is masked before
 * matching and never interpreted as the request. The positive intent text is used, so every caller
 * judges the same words. This grants no tool or mutation authority.
 */
public final class ContentSearchRequest {
    private static final int MAX_LENGTH = 4096;
    private static final String SCOPE = "(?:(?:this|the|our|my|current|active|whole|entire)\\s+)*"
            + "(?:workspace|repo|repository|project|codebase|folder|directory|tree|source\\s+code|source|code|files?)\\b";
    private static final String PREPOSITION = "(?:in|across|through|throughout|within|inside|under)";
    private static final String HOLDS = "\\b(?:contains?|holds?|has|have|mentions?|includes?|defines?|uses?|references?)\\b";
    // A term starts with a word character or a masked quote, and is not a pronoun such as "for me".
    private static final String TERM = "\\s*(?!(?:me|us|him|her|them|you)\\b)[\\p{L}\\p{N}_x]";
    private static final Pattern SCOPED_SEARCH_FOR = Pattern.compile("(?is)"
            + "\\b(?:search|grep|scan|hunt)\\b[^.?!]{0,40}?(?:" + PREPOSITION + "\\s+)?" + SCOPE
            + "[^.?!]{0,40}?\\bfor\\b" + TERM);
    private static final Pattern SEARCH_FOR_IN_SCOPE = Pattern.compile("(?is)"
            + "\\b(?:search|grep|scan|look|hunt|check)\\s+for\\s+" + TERM.substring(3) + "[^.?!]{0,160}?\\b"
            + PREPOSITION + "\\s+" + SCOPE);
    private static final Pattern WHICH_FILE_HOLDS = Pattern.compile("(?is)"
            + "\\b(?:which|what)\\s+files?\\b[^.?!]{0,60}?" + HOLDS + TERM);
    private static final Pattern FIND_FILES_THAT = Pattern.compile("(?is)"
            + "\\bfind\\s+(?:(?:every|all|any|each|the|those)\\s+)?files?\\b[^.?!]{0,60}?\\b(?:that|which)\\s+"
            + HOLDS.substring(2) + TERM);
    private static final Pattern WHERE_IS_DEFINED = Pattern.compile("(?is)"
            + "\\bwhere\\s+(?:is|are|does|do)\\s+\\S[^.?!]{0,80}?"
            + "\\b(?:defined|declared|used|mentioned|referenced|implemented|appears?)\\b");
    private static final Pattern[] FORMS = {
            SCOPED_SEARCH_FOR, SEARCH_FOR_IN_SCOPE, WHICH_FILE_HOLDS, FIND_FILES_THAT, WHERE_IS_DEFINED};
    // Only a condition or a withdrawal that governs the search itself, before it in its sentence, cancels it.
    private static final Pattern CONDITIONAL_BEFORE = Pattern.compile(
            "(?is)\\b(?:if|unless|optionally|pretend|imagine|suppose|hypothetically)\\b");
    private static final Pattern WITHDRAWN_BEFORE = Pattern.compile(
            "(?is)\\b(?:do\\s+not|don['’]?t|dont|never|without|avoid|stop)\\s*$");
    private static final Pattern SENTENCE_END = Pattern.compile("[.?!]");

    private ContentSearchRequest() {}

    /** The request in lower case with quoted material blanked, length preserved, for marker checks. */
    public static String unquotedLower(String request) {
        if (request == null || request.isBlank()) return request == null ? "" : request;
        return RequestLexicalView.of(request).maskedLower();
    }

    /** True when the request asks, at top level and outside quotes, for a text search over workspace content. */
    public static boolean matches(String request) {
        if (request == null || request.isBlank() || request.length() > MAX_LENGTH) return false;
        String positive = ConversationEvidenceRequest.interpret(request).positiveIntentText();
        if (positive == null || positive.isBlank()) return false;
        var lexical = RequestLexicalView.of(positive);
        if (lexical.containsMalformedDelimiter(0, positive.length())
                || lexical.containsProviderRemovedControl(0, positive.length())) return false;
        // A quoted span may be the search term. It stays opaque, but it counts as a term.
        StringBuilder matchable = new StringBuilder(lexical.maskedLower());
        for (int i = 0; i < matchable.length(); i++) {
            if (lexical.isQuoted(i)) matchable.setCharAt(i, 'x');
        }
        String text = matchable.toString();
        for (Pattern form : FORMS) {
            Matcher matcher = form.matcher(text);
            while (matcher.find()) {
                if (!governedByConditionOrWithdrawal(text, matcher.start())) return true;
            }
        }
        return false;
    }

    private static boolean governedByConditionOrWithdrawal(String text, int searchStart) {
        int sentenceStart = 0;
        Matcher end = SENTENCE_END.matcher(text);
        while (end.find() && end.start() < searchStart) sentenceStart = end.end();
        String prefix = text.substring(sentenceStart, searchStart);
        return CONDITIONAL_BEFORE.matcher(prefix).find() || WITHDRAWN_BEFORE.matcher(prefix).find();
    }
}
