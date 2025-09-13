package dev.loqj.core.search;

import dev.loqj.core.util.Sanitize;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Builds/combines snippets. Ensures:
 * - snippet text is sanitized before being sent to the model
 * - dedupe-by-path with first occurrence winning
 * - pinned-first ordering preserved, then remaining regular
 * - global maxCharsBudget enforced across the packed list
 */
public final class SnippetBuilder {

    public record Snippet(String path, String text) {
        public Snippet {
            path = Objects.requireNonNullElse(path, "");
            text = Objects.requireNonNullElse(text, "");
        }
    }

    private SnippetBuilder() {}

    /**
     * Pack pinned snippets first, then fill with regular snippets up to maxChars budget.
     * Duplicates (by path) are removed with the first occurrence winning.
     * All snippet texts are sanitized and truncated as needed.
     */
    public static List<Snippet> packWithPinned(List<Snippet> pinned, List<Snippet> regular, int maxCharsBudget) {
        final int budgetInit = Math.max(0, maxCharsBudget);
        int budget = budgetInit;

        // sanitize text for prompt use (strip control/ansi and suspicious html)
        List<Snippet> pinnedSan = sanitizeAll(pinned);
        List<Snippet> regSan    = sanitizeAll(regular);

        // track seen paths to dedupe while preserving order
        LinkedHashSet<String> seenPaths = new LinkedHashSet<>();
        List<Snippet> out = new ArrayList<>();

        // helper: add snippet if path is new and budget allows
        for (Snippet s : pinnedSan) {
            if (budget <= 0) break;
            if (!markSeen(seenPaths, s.path)) continue;
            int take = Math.min(budget, s.text.length());
            if (take <= 0) continue;
            out.add(new Snippet(s.path, s.text.substring(0, take)));
            budget -= take;
        }
        for (Snippet s : regSan) {
            if (budget <= 0) break;
            if (!markSeen(seenPaths, s.path)) continue;
            int take = Math.min(budget, s.text.length());
            if (take <= 0) continue;
            out.add(new Snippet(s.path, s.text.substring(0, take)));
            budget -= take;
        }
        return out;
    }

    private static boolean markSeen(LinkedHashSet<String> seen, String path) {
        if (path == null) path = "";
        // returns true if it wasn't already there
        return seen.add(path);
    }

    private static List<Snippet> sanitizeAll(List<Snippet> xs) {
        List<Snippet> out = new ArrayList<>();
        if (xs == null) return out;
        for (Snippet s : xs) {
            if (s == null) continue;
            String cleanText = Sanitize.sanitizeForPrompt(s.text);
            out.add(new Snippet(s.path, cleanText));
        }
        return out;
    }
}
