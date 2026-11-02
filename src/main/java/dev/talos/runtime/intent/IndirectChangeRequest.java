package dev.talos.runtime.intent;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Recognizes an indirect change request, a stated wish for a different result without a directive (MICA decision D6).
 *
 * <p>"a dark landing page is more appropriate than this. Can you help me?" asks for a change without naming one. Such
 * a turn stays read-only. Recognition only lets the runtime end the answer with an offer the user may accept on the
 * next turn. It never exposes a write tool and never grants authority by itself.
 */
public final class IndirectChangeRequest {
    private static final List<Pattern> DESIRES = List.of(
            Pattern.compile("\\b(?:is|would be|seems|sounds) more (?:appropriate|suitable|fitting)\\b"),
            Pattern.compile("\\bi(?: want| need| would like|'d like| wish) (?:it|this|the (?:page|site|website|design|"
                    + "layout|style|theme)) to\\b"),
            Pattern.compile("\\b(?:it|this|the (?:page|site|website|design|layout|style|theme)) should "
                    + "(?:be|look|feel|have)\\b"),
            // A preference that the files stay as they are is not a wish for a change.
            Pattern.compile("\\bi(?: would|'d)? prefer\\b(?! (?:(?:that |if )?you |it )?(?:not|don't|dont|do not|"
                    + "didn't|did not|wouldn't|would not)\\b)"),
            Pattern.compile("\\bhelp me (?:make|change|turn|redesign|restyle|update|fix|improve|convert|rewrite|edit|"
                    + "modernize|style)\\b"),
            Pattern.compile("\\bwould (?:look|be) better (?:as|with|if|in)\\b"));
    private static final Pattern HELP = Pattern.compile(
            "\\b(?:can|could|would|will) (?:you|u) help\\b|\\bhelp me\\b|\\bhelp with\\b");
    private static final Pattern DISLIKE = Pattern.compile(
            "\\bi (?:don't|dont|do not) (?:like|love)\\b|\\bnot what i (?:wanted|want|asked for)\\b|\\bi hate\\b"
                    + "|\\b(?:looks|is) (?:bad|ugly|boring|terrible|awful|dull)\\b");
    // Help that only explains or teaches is a question, not a wish for a change.
    private static final Pattern HELP_TO_UNDERSTAND = Pattern.compile(
            "\\bhelp me (?:understand|learn|see|know|find|figure|read|explain|decide|choose)\\b");
    // A question word anywhere in a sentence that ends with a question mark, or a sentence that opens as a yes or no
    // question.
    private static final Pattern QUESTION = Pattern.compile(
            "\\b(?:what|which|how|why|where|when|who|whose)\\b[^.!?]*\\?"
                    + "|(?:^|[.!?,]\\s*)(?:is|are|does|do|should|would|could)\\b[^.!?]*\\?");

    private IndirectChangeRequest() {}

    /**
     * True when the current message states a wish for a different result and carries no directive, no removal and no
     * prohibition of changes.
     */
    public static boolean recognized(String message) {
        if (message == null || message.isBlank()) return false;
        if (ContinuationDirective.present(message) || !ContinuationDirective.removalClauses(message).isEmpty()) {
            return false;
        }
        if (dev.talos.runtime.task.TaskContractResolver.explicitlyForbidsMutation(message)) return false;
        String lower = message.strip().toLowerCase(Locale.ROOT)
                .replace('’', '\'')
                .replaceAll("\\s+", " ");
        if (QUESTION.matcher(lower).find() && !HELP.matcher(lower).find()) return false;
        if (HELP_TO_UNDERSTAND.matcher(lower).find()) return false;
        for (Pattern desire : DESIRES) {
            if (desire.matcher(lower).find()) return true;
        }
        return HELP.matcher(lower).find() && DISLIKE.matcher(lower).find();
    }
}
