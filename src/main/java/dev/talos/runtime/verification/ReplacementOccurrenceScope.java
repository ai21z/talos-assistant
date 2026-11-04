package dev.talos.runtime.verification;

import dev.talos.core.util.TextReplacement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * How the old text of a requested replacement occurred in its target before the change (finding F1, P17).
 *
 * <p>A request names an old value, and the prior file decides whether that value identifies one region. When it
 * occurred once, the global postconditions decide the request. When it occurred more than once, which occurrences the
 * request meant cannot be decided without the request's descriptor words, so the request postcondition stays
 * unassessed (MICA proof-scoped completion, decision D11 option A). Only facts that hold under every reading are
 * decided: whether the change landed on a prior occurrence, and whether the change is a pure substitution of
 * occurrences. Occurrences are counted by the range rule of {@link TextReplacement#occurrencesOutsideReplacement}, so
 * old text inside the new text is not an occurrence. The scope is a pure function of the prior content, the final
 * content, the old text and the new text, with line endings normalized as the existing checks do. It reads no request
 * text.
 */
record ReplacementOccurrenceScope(
        Kind kind,
        int priorOccurrences,
        int remainingOccurrences,
        boolean landed,
        Substitution substitution
) {
    /** Bounds the substitution search, so a pathological file is undecided instead of running long. */
    static final long MAX_SUBSTITUTION_WORK = 20_000_000L;

    enum Kind {
        /** The old text occurred once before the change. */
        UNIQUE,
        /** The old text occurred two or more times before the change. */
        REPEATED,
        /** The old text did not occur before the change. */
        ABSENT_BEFORE,
        /** No prior content was established from the turn's mutation evidence. */
        PRIOR_UNKNOWN
    }

    /** Whether the final content is the prior content with some prior occurrences replaced and nothing else changed. */
    enum Substitution {
        YES,
        NO,
        /** The bounded search stopped before a decision. */
        UNDECIDED,
        /** Not evaluated, because the prior did not repeat the old text. */
        NOT_EVALUATED
    }

    /** A replacement whose request postcondition was not asserted, with the counts the answer states. */
    record NotAsserted(String pathHint, int priorOccurrences, int remainingOccurrences) {
        NotAsserted {
            pathHint = pathHint == null ? "" : pathHint.strip();
        }
    }

    ReplacementOccurrenceScope {
        kind = kind == null ? Kind.PRIOR_UNKNOWN : kind;
        priorOccurrences = Math.max(0, priorOccurrences);
        remainingOccurrences = Math.max(0, remainingOccurrences);
        substitution = substitution == null ? Substitution.NOT_EVALUATED : substitution;
    }

    /** The prior content is null when it is unknown. */
    static ReplacementOccurrenceScope of(String priorContent, String finalContent, String oldText, String newText) {
        String old = TextReplacement.normalizeLineEndings(oldText);
        String replacement = TextReplacement.normalizeLineEndings(newText);
        String after = TextReplacement.normalizeLineEndings(finalContent);
        int remaining = TextReplacement.occurrencesOutsideReplacement(after, old, replacement);
        if (priorContent == null || old.isEmpty()) {
            return new ReplacementOccurrenceScope(Kind.PRIOR_UNKNOWN, 0, remaining, false, Substitution.NOT_EVALUATED);
        }
        String prior = TextReplacement.normalizeLineEndings(priorContent);
        int priorCount = TextReplacement.occurrencesOutsideReplacement(prior, old, replacement);
        // A prior occurrence was replaced: an old occurrence went away and a new occurrence that no old text covers
        // appeared. New text that was already present, or a value written elsewhere, does not count as landing.
        boolean landed = remaining < priorCount
                && TextReplacement.occurrencesOutsideReplacement(after, replacement, old)
                > TextReplacement.occurrencesOutsideReplacement(prior, replacement, old);
        if (priorCount == 0) {
            return new ReplacementOccurrenceScope(Kind.ABSENT_BEFORE, 0, remaining, landed, Substitution.NOT_EVALUATED);
        }
        if (priorCount == 1) {
            return new ReplacementOccurrenceScope(Kind.UNIQUE, 1, remaining, landed, Substitution.NOT_EVALUATED);
        }
        return new ReplacementOccurrenceScope(
                Kind.REPEATED,
                priorCount,
                remaining,
                landed,
                substitution(prior, after, old, replacement));
    }

    boolean repeated() {
        return kind == Kind.REPEATED;
    }

    boolean substitutionOnly() {
        return substitution == Substitution.YES;
    }

    NotAsserted notAsserted(String pathHint) {
        return new NotAsserted(pathHint, priorOccurrences, remainingOccurrences);
    }

    /**
     * The final content equals the prior content with a non-empty subset of the prior occurrences replaced by the new
     * text. A difference of one terminal newline is accepted, as in the one-occurrence preservation checks.
     */
    static Substitution substitution(String prior, String after, String old, String replacement) {
        return substitution(prior, after, old, replacement, MAX_SUBSTITUTION_WORK);
    }

    /** The same decision under a given work bound. */
    static Substitution substitution(String prior, String after, String old, String replacement, long maxWork) {
        Substitution exact = substitutionOf(prior, after, old, replacement, maxWork);
        if (exact == Substitution.YES) return exact;
        String strippedPrior = stripSingleTerminalNewline(prior);
        String strippedAfter = stripSingleTerminalNewline(after);
        if (strippedPrior.equals(prior) && strippedAfter.equals(after)) return exact;
        Substitution stripped = substitutionOf(strippedPrior, strippedAfter, old, replacement, maxWork);
        if (stripped == Substitution.YES) return stripped;
        return exact == Substitution.UNDECIDED || stripped == Substitution.UNDECIDED ? Substitution.UNDECIDED
                : Substitution.NO;
    }

    /**
     * A dynamic program over the occurrence index and the replaced count, kept to the window of reached counts. Each
     * loop step counts toward the work bound. Occurrences are the non-overlapping ones of the existing count, and an
     * occurrence inside prior new text is never replaced, so a new text that contains the old text, such as hello to
     * hello atlas, is exact.
     */
    private static Substitution substitutionOf(
            String prior,
            String after,
            String old,
            String replacement,
            long maxWork
    ) {
        if (prior == null || after == null || old == null || old.isEmpty()
                || replacement == null || old.equals(replacement)) {
            return Substitution.NO;
        }
        List<Integer> starts = new ArrayList<>();
        int index = 0;
        while ((index = prior.indexOf(old, index)) >= 0) {
            starts.add(index);
            index += old.length();
        }
        int count = starts.size();
        if (count == 0) return Substitution.NO;
        boolean[] covered = coveredByReplacement(prior, starts, old.length(), replacement);
        int delta = replacement.length() - old.length();
        long work = starts.getFirst();
        if (work > maxWork) return Substitution.UNDECIDED;
        if (!after.regionMatches(0, prior, 0, starts.getFirst())) return Substitution.NO;
        boolean[] reached = new boolean[count + 1];
        boolean[] next = new boolean[count + 1];
        reached[0] = true;
        int low = 0;
        int high = 0;
        for (int occurrence = 0; occurrence < count; occurrence++) {
            int segmentStart = starts.get(occurrence) + old.length();
            int segmentEnd = occurrence + 1 < count ? starts.get(occurrence + 1) : prior.length();
            int segmentLength = segmentEnd - segmentStart;
            Arrays.fill(next, low, high + 2, false);
            int nextLow = Integer.MAX_VALUE;
            int nextHigh = -1;
            for (int replaced = low; replaced <= high; replaced++) {
                work += 2;
                if (work > maxWork) return Substitution.UNDECIDED;
                if (!reached[replaced]) continue;
                long position = (long) starts.get(occurrence) + (long) replaced * delta;
                if (position < 0 || position > after.length()) continue;
                int at = (int) position;
                work += old.length() + segmentLength;
                if (after.startsWith(old, at)
                        && after.regionMatches(at + old.length(), prior, segmentStart, segmentLength)) {
                    next[replaced] = true;
                    nextLow = Math.min(nextLow, replaced);
                    nextHigh = Math.max(nextHigh, replaced);
                }
                if (covered[occurrence]) continue;
                work += replacement.length() + segmentLength;
                if (after.startsWith(replacement, at)
                        && after.regionMatches(at + replacement.length(), prior, segmentStart, segmentLength)) {
                    next[replaced + 1] = true;
                    nextLow = Math.min(nextLow, replaced + 1);
                    nextHigh = Math.max(nextHigh, replaced + 1);
                }
                if (work > maxWork) return Substitution.UNDECIDED;
            }
            if (nextHigh < 0) return Substitution.NO;
            boolean[] swap = reached;
            reached = next;
            next = swap;
            low = nextLow;
            high = nextHigh;
        }
        for (int replaced = Math.max(1, low); replaced <= high; replaced++) {
            if (reached[replaced] && after.length() == prior.length() + (long) replaced * delta) {
                return Substitution.YES;
            }
        }
        return Substitution.NO;
    }

    /** Marks each old occurrence that an occurrence of the new text in the prior content covers. */
    private static boolean[] coveredByReplacement(String prior, List<Integer> starts, int oldLength, String replacement) {
        boolean[] covered = new boolean[starts.size()];
        if (replacement.isEmpty()) return covered;
        List<int[]> ranges = new ArrayList<>();
        int index = 0;
        while ((index = prior.indexOf(replacement, index)) >= 0) {
            ranges.add(new int[] {index, index + replacement.length()});
            index += replacement.length();
        }
        int range = 0;
        for (int occurrence = 0; occurrence < starts.size(); occurrence++) {
            int start = starts.get(occurrence);
            int end = start + oldLength;
            while (range < ranges.size() && ranges.get(range)[1] < end) range++;
            covered[occurrence] = range < ranges.size() && ranges.get(range)[0] <= start;
        }
        return covered;
    }

    private static String stripSingleTerminalNewline(String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        return value.endsWith("\n") ? value.substring(0, value.length() - 1) : value;
    }
}
