package dev.talos.runtime.intent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The act of the current message, as far as carried authority is concerned (MICA invariant I1, decision D2).
 *
 * <p>Carried context and history may bind the referent of a directive. They never turn a comment, a complaint,
 * a question or an acknowledgement into a change request. A directive clause starts, after discourse words and a
 * polite request frame, with a bounded editing verb. A clause that starts with a reading verb, such as review or
 * check, is a directive only when it joins an editing verb with "and". A question is a directive only through a
 * request frame such as "can you". Removal, renaming and moving verbs are not continuation directives, because
 * those operations are resolved from the current message alone.
 *
 * <p>This recognizer only narrows. A cue that used to widen authority anywhere in a message now counts only when it
 * appears inside a directive clause of the current message.
 */
public final class ContinuationDirective {
    // Sentence punctuation ends a clause only before whitespace or the end, so a file name such as
    // style.css stays inside its clause.
    private static final Pattern CLAUSE_BREAK = Pattern.compile(
            "[.!?;:\\u2026]+(?=\\s|$)|,|\\s+(?:but|so|then|tho|though)\\s+");
    private static final Pattern WORD = Pattern.compile("[a-z']+");

    private static final Set<String> DISCOURSE = Set.of(
            "yes", "yeah", "yep", "yup", "ok", "okay", "k", "sure", "please", "pls", "plz", "now", "so", "then",
            "and", "but", "just", "hm", "hmm", "hmmm", "alright", "great", "nice", "cool", "well", "oh", "ah",
            "right", "good", "fine", "thanks", "also", "no", "nope", "wow");

    private static final List<String> REQUEST_FRAMES = List.of(
            "can you", "could you", "would you", "will you", "can u", "could u", "would u",
            "i want you to", "i'd like you to", "i would like you to", "i need you to",
            "you should", "you need to", "you must", "you can",
            "let's", "lets", "let us", "go ahead and");

    private static final Set<String> EDIT_VERBS = Set.of(
            "make", "fix", "repair", "try", "retry", "redo", "finish", "complete", "do", "apply", "edit", "modify",
            "change", "update", "rewrite", "redesign", "improve", "polish", "add", "continue", "proceed", "restyle",
            "style", "convert", "turn", "build", "refactor", "implement", "correct", "resolve", "create", "write",
            "put", "adjust", "tweak", "rework", "enhance", "upgrade", "rebuild", "finalize");

    private static final Set<String> READ_VERBS = Set.of(
            "review", "inspect", "check", "verify", "look", "examine", "test", "run");

    /** Removal verbs. A removal is resolved from the current message alone, never carried (MICA invariant I2). */
    private static final Pattern REMOVAL_VERB = Pattern.compile("(?:get rid of|delete|remove|erase|rm)\\b");

    private static final Set<String> REMOVAL_ADVERBS = Set.of(
            "completely", "permanently", "entirely", "fully", "simply", "quickly", "safely", "just", "also");

    /** "do" followed by these is a question or a negation, not a request to do something. */
    private static final Set<String> DO_NOT_DIRECTIVE = Set.of(
            "not", "nothing", "n't", "you", "u", "i", "we", "they", "he", "she", "these", "those");

    private static final Set<String> AFFIRMATIONS = Set.of(
            "yes", "yes please", "yeah", "yep", "go ahead", "go ahead please", "please go ahead", "yes go ahead",
            "ok go ahead", "okay go ahead", "do it", "do it please", "please do", "yes do it", "yes please do it",
            "proceed", "yes proceed", "please proceed", "yes fix it", "yes please fix it", "yes repair it");

    /** Objects that refer only to something said or proposed before, never to content of their own. */
    private static final Set<String> DEICTIC_PRONOUNS = Set.of(
            "it", "this", "that", "them", "these", "those", "everything");

    private static final Set<String> DEICTIC_DETERMINERS = Set.of(
            "the", "this", "that", "these", "those", "my", "your", "all");

    /** A quantifier stands for every referent it follows or names with "of", as in "apply them all". */
    private static final Set<String> DEICTIC_QUANTIFIERS = Set.of("all", "both", "each");

    private static final Set<String> DEICTIC_NOUNS = Set.of(
            "change", "changes", "edit", "edits", "fix", "fixes", "proposal", "proposals", "suggestion",
            "suggestions", "update", "updates", "plan", "rest");

    /**
     * Words beside a referentless directive that add time, politeness or a greeting but name no content. An
     * evaluative word such as nice or good is not one, because after "make it" it names the content of the change.
     */
    private static final Set<String> REFERENTLESS_TAILS = Set.of(
            "please", "pls", "plz", "now", "then", "so", "just", "also", "thanks", "thx", "ty", "cheers", "again",
            "too", "anyway", "already", "immediately", "later", "asap", "quickly", "hey", "hi", "hello");

    private static final List<List<String>> REFERENTLESS_TAIL_PAIRS = List.of(
            List.of("for", "me"), List.of("right", "now"), List.of("right", "away"), List.of("as", "well"),
            List.of("at", "once"), List.of("thank", "you"));

    /**
     * Sentence punctuation and commas end a referentless clause. Joining words such as "and then" stay inside it,
     * so a clause is never cut between a join and the verb it joins.
     */
    private static final Pattern REFERENT_BREAK = Pattern.compile("[.!?;:\\u2026]+(?=\\s|$)|,");

    private static final int CLAUSE_EDITS = 1;
    private static final int CLAUSE_DEICTIC = 2;

    private ContinuationDirective() {}

    /** The directive clauses of the message, lower-cased and starting at their verb. Empty when there is none. */
    public static List<String> clauses(String message) {
        return scan(message, ContinuationDirective::directiveClause);
    }

    /**
     * The removal clauses of the message, lower-cased and starting at the removal verb (MICA invariant I2). The same
     * discourse and request-frame rules apply, so a past-tense question, a wh-question or a negation is not a removal.
     */
    public static List<String> removalClauses(String message) {
        return scan(message, ContinuationDirective::removalClause);
    }

    private static List<String> scan(String message, java.util.function.BiFunction<String, Boolean, String> act) {
        if (message == null || message.isBlank()) return List.of();
        String lower = message.strip().toLowerCase(Locale.ROOT)
                .replace('’', '\'')
                .replaceAll("\\s+", " ");
        List<String> out = new ArrayList<>();
        Matcher breaks = CLAUSE_BREAK.matcher(lower);
        int start = 0;
        while (breaks.find()) {
            add(out, act.apply(lower.substring(start, breaks.start()), breaks.group().contains("?")));
            start = breaks.end();
        }
        add(out, act.apply(lower.substring(start), false));
        return List.copyOf(out);
    }

    /** True when the current message carries at least one directive clause. */
    public static boolean present(String message) {
        return !clauses(message).isEmpty();
    }

    /** How a message names what its directives act on. */
    public enum ReferentForm {
        /** The message is not a directive, or a directive names content or a referent of its own. */
        NOT_REFERENTLESS,
        /** Every directive verb stands without an object, as in "apply" or "try again". */
        OBJECTLESS,
        /** Every directive object is deictic, as in "apply it", "make the changes" or "check it and edit it". */
        DEICTIC
    }

    /**
     * Whether the message is a directive that names nothing of its own to act on (MICA invariant I1). Such a
     * directive can only act on what is pending. After discourse words and a request frame, every clause must be
     * made of verbs joined by "and", "then" or "and then". Each verb is an editing verb, or a reading verb beside
     * one, followed by nothing or by a deictic object, and then only by discourse words or by words of time, manner
     * or politeness. A removal, rename or move verb is not an editing verb. The form does not depend on question
     * punctuation, because a mutation request is recognized with or without it.
     */
    public static ReferentForm referentForm(String message) {
        if (message == null || message.isBlank()) return ReferentForm.NOT_REFERENTLESS;
        String lower = message.strip().toLowerCase(Locale.ROOT)
                .replace('’', '\'')
                .replaceAll("\\s+", " ");
        boolean directive = false;
        boolean edits = false;
        boolean deictic = false;
        for (String raw : REFERENT_BREAK.split(lower, -1)) {
            String bare = framedRemainder(raw, false);
            if (bare.isEmpty()) continue;
            String[] words = java.util.Arrays.stream(bare.split(" "))
                    .filter(ContinuationDirective::wordOrSymbol)
                    .toArray(String[]::new);
            int start = closingWords(words, 0);
            if (start == words.length) continue;
            int form = referentlessClause(java.util.Arrays.copyOfRange(words, start, words.length));
            if (form < 0) return ReferentForm.NOT_REFERENTLESS;
            directive = true;
            edits |= (form & CLAUSE_EDITS) != 0;
            deictic |= (form & CLAUSE_DEICTIC) != 0;
        }
        if (!directive || !edits) return ReferentForm.NOT_REFERENTLESS;
        return deictic ? ReferentForm.DEICTIC : ReferentForm.OBJECTLESS;
    }

    /** The flags of a referentless clause, or -1 when a word names content, a referent or another act. */
    private static int referentlessClause(String[] words) {
        int flags = 0;
        int at = 0;
        while (true) {
            if (at >= words.length) return -1;
            String verb = words[at++];
            if (EDIT_VERBS.contains(verb)) flags |= CLAUSE_EDITS;
            else if (!READ_VERBS.contains(verb)) return -1;
            int object = deicticObject(words, at);
            if (object < 0) return -1;
            if (object > at) flags |= CLAUSE_DEICTIC;
            at = object;
            while (at < words.length && !joinsAVerb(words, at)) {
                int next = closingWords(words, at);
                if (next == at) return -1;
                at = next;
            }
            if (at >= words.length) return flags;
            at++;
            if ("and".equals(words[at - 1]) && at < words.length && "then".equals(words[at])) at++;
        }
    }

    /** The index after the closing words that start at {@code at}, stopping before a join to a directive verb. */
    private static int closingWords(String[] words, int at) {
        while (at < words.length && !joinsAVerb(words, at)) {
            if (at + 1 < words.length && REFERENTLESS_TAIL_PAIRS.contains(List.of(words[at], words[at + 1]))) {
                at += 2;
            } else if (REFERENTLESS_TAILS.contains(words[at])) {
                at++;
            } else {
                break;
            }
        }
        return at;
    }

    /** A token with a letter, a digit or a symbol such as an emoji. Punctuation alone, as in ":)", is dropped. */
    private static boolean wordOrSymbol(String word) {
        return word.codePoints().anyMatch(point -> Character.isLetterOrDigit(point)
                || Character.getType(point) == Character.OTHER_SYMBOL
                || Character.getType(point) == Character.MATH_SYMBOL);
    }

    /** Whether "and", "then" or "and then" at {@code at} joins the clause to a following directive verb. */
    private static boolean joinsAVerb(String[] words, int at) {
        if (!"and".equals(words[at]) && !"then".equals(words[at])) return false;
        int next = at + 1;
        if ("and".equals(words[at]) && next < words.length && "then".equals(words[next])) next++;
        return next < words.length && (EDIT_VERBS.contains(words[next]) || READ_VERBS.contains(words[next]));
    }

    /**
     * The index after the deictic object that starts at {@code at}, {@code at} itself when there is none, or -1 when
     * a quantifier names something of its own with "of". A determiner without a deictic noun is left to the caller,
     * which rejects it as a word of content.
     */
    private static int deicticObject(String[] words, int at) {
        if (at >= words.length) return at;
        int noun = at;
        while (noun < words.length && noun - at < 2 && DEICTIC_DETERMINERS.contains(words[noun])) noun++;
        if (noun > at && noun < words.length && DEICTIC_NOUNS.contains(words[noun])) return noun + 1;
        if (DEICTIC_PRONOUNS.contains(words[at])) {
            int next = at + 1;
            return next < words.length && DEICTIC_QUANTIFIERS.contains(words[next]) ? next + 1 : next;
        }
        if (DEICTIC_QUANTIFIERS.contains(words[at])) {
            int next = at + 1;
            if (next < words.length && "of".equals(words[next])) {
                int named = deicticObject(words, next + 1);
                return named > next + 1 ? named : -1;
            }
            return next;
        }
        return at;
    }

    /** A bare acceptance that can bind only a typed confirmation the runtime issued. It never mutates alone. */
    public static boolean isAffirmation(String message) {
        if (message == null || message.isBlank()) return false;
        String lower = message.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[.!?,]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
        return AFFIRMATIONS.contains(lower);
    }

    private static void add(List<String> out, String clause) {
        if (!clause.isEmpty()) out.add(clause);
    }

    private static String directiveClause(String raw, boolean question) {
        String clause = framedRemainder(raw, question);
        String verb = firstWord(clause);
        if (EDIT_VERBS.contains(verb)) {
            if ("do".equals(verb)) {
                String rest = clause.substring(verb.length()).strip();
                if (DO_NOT_DIRECTIVE.contains(firstWord(rest)) || rest.startsWith("n't")) return "";
            }
            return clause;
        }
        if (READ_VERBS.contains(verb) && joinsAnEditVerb(clause)) return clause;
        return "";
    }

    // A clause that starts with the removal verb is a removal request even when it ends with a question mark.
    // A past-tense or wh-question starts with another word, so it never qualifies.
    private static String removalClause(String raw, boolean question) {
        String clause = framedRemainder(raw, false);
        while (REMOVAL_ADVERBS.contains(firstWord(clause))) {
            clause = clause.substring(firstWord(clause).length()).strip();
        }
        // A removal joined to another editing action is a compound request, left whole to the parser.
        return REMOVAL_VERB.matcher(clause).lookingAt() && !joinsAnEditVerb(clause) ? clause : "";
    }

    /** The clause after discourse words and a request frame, or empty for a question without a frame. */
    private static String framedRemainder(String raw, boolean question) {
        String clause = raw == null ? "" : raw.strip();
        boolean framed = false;
        boolean changed = true;
        while (changed && !clause.isEmpty()) {
            changed = false;
            String first = firstWord(clause);
            if (DISCOURSE.contains(first)) {
                clause = clause.substring(first.length()).strip();
                changed = true;
                continue;
            }
            for (String frame : REQUEST_FRAMES) {
                if (clause.equals(frame) || clause.startsWith(frame + " ")) {
                    clause = clause.substring(frame.length()).strip();
                    framed = true;
                    changed = true;
                    break;
                }
            }
        }
        if (question && !framed) return "";
        return clause;
    }

    private static boolean joinsAnEditVerb(String clause) {
        int at = clause.indexOf(" and ");
        while (at >= 0) {
            if (EDIT_VERBS.contains(firstWord(clause.substring(at + 5)))) return true;
            at = clause.indexOf(" and ", at + 5);
        }
        return false;
    }

    private static String firstWord(String clause) {
        Matcher matcher = WORD.matcher(clause);
        if (!matcher.lookingAt()) return "";
        return matcher.group();
    }
}
