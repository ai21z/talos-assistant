package dev.talos.core.search;

import dev.talos.core.util.Sanitize;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Builds and combines snippets with the following guarantees:
 * - Snippet text is sanitized before being sent to the model
 * - Deduplication by path with first occurrence winning
 * - Pinned-first ordering is preserved, then remaining regular snippets
 * - Global maxCharsBudget is enforced across the packed list
 * - Optional reservation: guarantees ≥1 snippet per pinned base file
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
     * Packs pinned snippets first, then fills with regular snippets up to maxChars budget.
     * Duplicates (by path) are removed with the first occurrence winning.
     * All snippet texts are sanitized and truncated as needed.
     */
    public static List<Snippet> packWithPinned(List<Snippet> pinned, List<Snippet> regular, int maxCharsBudget) {
        return packWithPinned(pinned, regular, maxCharsBudget, false);
    }

    /**
     * Extended packing with optional per-file reservation.
     *
     * @param pinned List of pinned snippets (priority)
     * @param regular List of regular snippets (fill remaining budget)
     * @param maxCharsBudget Maximum character budget for all snippets combined
     * @param reservePerPinnedFile If true and exactly 2 distinct base files are pinned,
     *                             at least one chunk per base file is reserved
     */
    public static List<Snippet> packWithPinned(List<Snippet> pinned, List<Snippet> regular,
                                                int maxCharsBudget, boolean reservePerPinnedFile) {
        final int budgetInit = Math.max(0, maxCharsBudget);
        int budget = budgetInit;

        // Sanitize text for prompt use (strip control/ansi and suspicious html)
        List<Snippet> pinnedSan = sanitizeAll(pinned);
        List<Snippet> regSan    = sanitizeAll(regular);

        // Track seen paths to dedupe while preserving order
        LinkedHashSet<String> seenPaths = new LinkedHashSet<>();
        List<Snippet> out = new ArrayList<>();

        // If reservation is requested, ensure exactly 2 distinct base files exist
        if (reservePerPinnedFile && pinnedSan.size() >= 2) {
            LinkedHashSet<String> pinnedBases = new LinkedHashSet<>();
            for (Snippet s : pinnedSan) {
                String base = stripChunkId(s.path);
                pinnedBases.add(base);
            }

            if (pinnedBases.size() == 2) {
                // Reserve one snippet per base file
                LinkedHashSet<String> reservedBases = new LinkedHashSet<>();
                for (Snippet s : pinnedSan) {
                    if (budget <= 0) break;
                    String base = stripChunkId(s.path);

                    // Skip if a snippet for this base file was already reserved
                    if (reservedBases.contains(base)) continue;

                    // Mark path as seen
                    if (!markSeen(seenPaths, s.path)) continue;

                    // Take as much as budget allows
                    int take = Math.min(budget, s.text.length());
                    if (take <= 0) continue;

                    out.add(new Snippet(s.path, s.text.substring(0, take)));
                    budget -= take;
                    reservedBases.add(base);

                    // Stop once one snippet per base file has been reserved
                    if (reservedBases.size() == 2) break;
                }
            }
        }

        // Add remaining pinned snippets (skip those already added)
        for (Snippet s : pinnedSan) {
            if (budget <= 0) break;
            if (!markSeen(seenPaths, s.path)) continue;
            int take = Math.min(budget, s.text.length());
            if (take <= 0) continue;
            out.add(new Snippet(s.path, s.text.substring(0, take)));
            budget -= take;
        }

        // Fill with regular snippets
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

    /**
     * Strips chunk ID suffix from a path (everything after #).
     */
    private static String stripChunkId(String path) {
        if (path == null) return "";
        int i = path.indexOf('#');
        return (i < 0) ? path : path.substring(0, i);
    }

    /**
     * Marks a path as seen in the deduplication set.
     * @return true if the path was not already present
     */
    private static boolean markSeen(LinkedHashSet<String> seen, String path) {
        if (path == null) path = "";
        return seen.add(path);
    }

    /**
     * Sanitizes all snippets in a list for safe prompt use.
     */
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
