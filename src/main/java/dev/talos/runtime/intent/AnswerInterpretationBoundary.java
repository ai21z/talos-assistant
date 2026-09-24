package dev.talos.runtime.intent;

import dev.talos.runtime.task.AnswerRequirements;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Structural eligibility is checked without establishing request meaning. */
public final class AnswerInterpretationBoundary {
    private static final Pattern WORD = Pattern.compile(
            "[\\p{L}\\p{N}_]+(?:['\\u2018\\u2019][\\p{L}\\p{N}_]+)*");
    private static final Set<String> EXCLUDED = Set.of(
            "no", "not", "never", "without", "unless", "except", "instead",
            "cannot", "can't", "don't", "doesn't", "didn't", "isn't", "aren't",
            "wasn't", "weren't", "won't", "wouldn't", "shouldn't", "couldn't",
            "mustn't", "needn't", "haven't", "hasn't", "hadn't",
            "if", "when", "whenever", "until", "provided", "assuming",
            "suppose", "supposing", "imagine", "imagining", "pretend",
            "hypothetical", "hypothetically", "example", "examples",
            "quote", "quoted", "quoting", "says", "said", "saying", "told",
            "instructed", "instruction", "instructions", "requested",
            "ask", "asks", "asked", "reports", "reported", "reporting",
            "claims", "claimed", "writes", "wrote");

    private AnswerInterpretationBoundary() {}

    public static boolean eligible(String originalRequest, int start, int end) {
        if (originalRequest == null || originalRequest.isBlank()
                || originalRequest.length() > AnswerRequirements.MAX_REQUEST_CHARS
                || start < 0 || end <= start || end > originalRequest.length()
                || originalRequest.substring(start, end).isBlank()
                || !validUnicode(originalRequest)
                || splitsToken(originalRequest, start) || splitsToken(originalRequest, end)) {
            return false;
        }
        RequestLexicalView view = RequestLexicalView.of(originalRequest);
        if (view.containsProviderRemovedControl(0, originalRequest.length())
                || view.containsLoneCarriageReturn(0, originalRequest.length())
                || view.containsMalformedDelimiter(0, originalRequest.length())) {
            return false;
        }
        int envelopeStart = view.sentenceStartBefore(0, start);
        int envelopeEnd = view.sentenceEndAfter(end - 1);
        if (!view.isUnquotedRange(envelopeStart, envelopeEnd)
                || !WORD.matcher(originalRequest).region(start, end).find()) return false;
        for (int index = envelopeStart; index < envelopeEnd; index++) {
            if (!view.isTopLevel(index) || originalRequest.charAt(index) == '>') return false;
        }
        Matcher words = WORD.matcher(originalRequest).region(envelopeStart, envelopeEnd);
        boolean hasWord = false;
        while (words.find()) {
            hasWord = true;
            String word = words.group().toLowerCase(Locale.ROOT)
                    .replace('\u2018', '\'').replace('\u2019', '\'');
            if (EXCLUDED.contains(word)) return false;
        }
        return hasWord;
    }

    private static boolean splitsToken(String request, int offset) {
        return offset > 0 && offset < request.length()
                && tokenCharacter(request.charAt(offset - 1))
                && tokenCharacter(request.charAt(offset));
    }

    private static boolean tokenCharacter(char value) {
        return Character.isLetterOrDigit(value) || Character.isSurrogate(value)
                || value == '_' || value == '\'' || value == '\u2018' || value == '\u2019';
    }

    private static boolean validUnicode(String request) {
        for (int index = 0; index < request.length(); index++) {
            char value = request.charAt(index);
            if (Character.getType(value) == Character.FORMAT
                    || Character.isISOControl(value) && value != '\t' && value != '\n' && value != '\r') return false;
            if (Character.isHighSurrogate(value)) {
                if (index + 1 == request.length() || !Character.isLowSurrogate(request.charAt(index + 1))) return false;
                index++;
            } else if (Character.isLowSurrogate(value)) return false;
        }
        return true;
    }
}
