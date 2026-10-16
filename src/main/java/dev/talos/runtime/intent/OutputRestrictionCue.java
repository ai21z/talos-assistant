package dev.talos.runtime.intent;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Coarse detection of wording that may limit what an answer shows.
 *
 * <p>No meaning is interpreted and no authority is granted. The vocabulary is broad on purpose:
 * a false positive only withholds detail, while a false negative could disclose it. A sentence that
 * consists only of a mutation prohibition, such as "don't change any files", is not treated as an
 * output restriction. Anything after the prohibited mutation keeps the sentence restriction-shaped.
 */
public final class OutputRestrictionCue {
    private static final Pattern SENTENCE = Pattern.compile("[.!?;:\\n]+");
    private static final Pattern CUE = Pattern.compile("\\b(?:don't|dont|do\\s+not|never|without|only|just"
            + "|didn't|did\\s+not|not\\s+the|nothing\\s+(?:more|else)|to\\s+yourself|leave\\s+out|refrain"
            + "|not\\s+(?:show|list|reveal|display|print|tell|mention|name|include|share)"
            + "|no\\s+(?:names?|file\\s*names?|paths?|contents?|details?|list|listing|entries)"
            + "|private|privately|secret|hide|hidden\\s+from|withhold|withheld|conceal|confidential"
            + "|except|exclude|excluding|omit|skip|avoid|redact|redacted)\\b");
    private static final String MUTATION_VERB = "(?:change|edit|modify|write|delete|remove|create|touch|save"
            + "|alter|overwrite|rename|move)";
    private static final Pattern MUTATION_ONLY = Pattern.compile("^(?:(?:please|and|but|also|then|so),?\\s+)*"
            + "(?:don't|dont|do\\s+not|never)\\s+" + MUTATION_VERB
            + "(?:\\s*,?\\s*(?:or|and)?\\s*" + MUTATION_VERB + ")*"
            + "(?:\\s+(?:to|in|on))?"
            + "(?:\\s+(?:any|the|these|those|this|that|my|our|a))?"
            + "(?:\\s+(?:thing|things|anything|file|files|folder|folders|directory|workspace|repo|repository"
            + "|project|code|it|them|disk))?"
            + "(?:\\s*,?\\s*(?:now|yet|please|here|at\\s+all|for\\s+now))*$");

    private OutputRestrictionCue() {}

    public static boolean present(String request) {
        if (request == null || request.isBlank()) return false;
        String lower = request.replace('’', '\'').toLowerCase(Locale.ROOT);
        for (String sentence : SENTENCE.split(lower)) {
            String text = sentence.strip().replaceAll("\\s+", " ");
            if (!CUE.matcher(text).find()) continue;
            if (MUTATION_ONLY.matcher(text).matches()) continue;
            return true;
        }
        return false;
    }
}
