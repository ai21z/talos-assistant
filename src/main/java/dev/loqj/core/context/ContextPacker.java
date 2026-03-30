package dev.loqj.core.context;

import dev.loqj.core.util.Sanitize;

import java.util.*;

/**
 * Unified context assembly: sanitizes, deduplicates, and packs snippets
 * within a token budget, producing a {@link ContextResult}.
 *
 * <p>Replaces the split logic previously spread across:
 * <ul>
 *   <li>{@code SnippetBuilder.packWithPinned()} — character-based budget, dedup, sanitize</li>
 *   <li>{@code PromptValidator.validateAndTrim()} — token-based trimming from end of list</li>
 * </ul>
 *
 * <p>Packing order:
 * <ol>
 *   <li>If {@code reservePerPinnedFile} and exactly 2 distinct base files are pinned,
 *       reserve one snippet per base file first.</li>
 *   <li>Remaining pinned snippets (deduped by path).</li>
 *   <li>Regular (retrieved) snippets fill the remaining budget.</li>
 * </ol>
 *
 * <p>All snippet texts are sanitized for prompt safety before packing.
 * The result includes provenance metadata for diagnostics.
 */
public final class ContextPacker {

    private final TokenBudget budget;

    public ContextPacker(TokenBudget budget) {
        this.budget = Objects.requireNonNull(budget, "budget must not be null");
    }

    /**
     * Pack pinned + regular snippets within the token budget.
     *
     * @param systemPrompt       the system prompt (used for budget calculation)
     * @param userQuery           the user question (used for budget calculation)
     * @param pinned              pinned snippets (highest priority)
     * @param regular             regular (retrieved) snippets
     * @param reservePerPinnedFile if true and exactly 2 distinct base files are pinned,
     *                             guarantee at least one snippet per base file
     * @return packed context result with provenance
     */
    public ContextResult pack(String systemPrompt, String userQuery,
                              List<ContextResult.Snippet> pinned,
                              List<ContextResult.Snippet> regular,
                              boolean reservePerPinnedFile) {
        // Compute available character budget from token budget
        int availableTokens = budget.availableForSnippets(systemPrompt, userQuery);
        int charBudget = budget.tokensToChars(availableTokens);

        // Sanitize inputs
        List<ContextResult.Snippet> pinnedSan = sanitizeAll(pinned);
        List<ContextResult.Snippet> regSan = sanitizeAll(regular);

        int originalCount = pinnedSan.size() + regSan.size();

        // Dedup + pack within budget
        LinkedHashSet<String> seenPaths = new LinkedHashSet<>();
        List<ContextResult.Snippet> packed = new ArrayList<>();
        int usedChars = 0;
        boolean anyTruncated = false;  // track text truncation, not just snippet drops

        // Phase 1: reservation for two-file comparison
        if (reservePerPinnedFile && pinnedSan.size() >= 2) {
            LinkedHashSet<String> pinnedBases = new LinkedHashSet<>();
            for (ContextResult.Snippet s : pinnedSan) {
                pinnedBases.add(stripChunkId(s.path()));
            }
            if (pinnedBases.size() == 2) {
                LinkedHashSet<String> reservedBases = new LinkedHashSet<>();
                for (ContextResult.Snippet s : pinnedSan) {
                    if (usedChars >= charBudget) break;
                    String base = stripChunkId(s.path());
                    if (reservedBases.contains(base)) continue;
                    if (!seenPaths.add(s.path())) continue;

                    int take = Math.min(charBudget - usedChars, s.text().length());
                    if (take <= 0) continue;
                    if (take < s.text().length()) anyTruncated = true;
                    packed.add(new ContextResult.Snippet(s.path(), s.text().substring(0, take)));
                    usedChars += take;
                    reservedBases.add(base);
                    if (reservedBases.size() == 2) break;
                }
            }
        }

        // Phase 2: remaining pinned snippets
        for (ContextResult.Snippet s : pinnedSan) {
            if (usedChars >= charBudget) break;
            if (!seenPaths.add(s.path())) continue;
            int take = Math.min(charBudget - usedChars, s.text().length());
            if (take <= 0) continue;
            if (take < s.text().length()) anyTruncated = true;
            packed.add(new ContextResult.Snippet(s.path(), s.text().substring(0, take)));
            usedChars += take;
        }

        // Phase 3: regular snippets
        for (ContextResult.Snippet s : regSan) {
            if (usedChars >= charBudget) break;
            if (!seenPaths.add(s.path())) continue;
            int take = Math.min(charBudget - usedChars, s.text().length());
            if (take <= 0) continue;
            if (take < s.text().length()) anyTruncated = true;
            packed.add(new ContextResult.Snippet(s.path(), s.text().substring(0, take)));
            usedChars += take;
        }

        // Build citations (deduplicated base file paths)
        LinkedHashSet<String> citationSet = new LinkedHashSet<>();
        for (ContextResult.Snippet s : packed) {
            citationSet.add(stripChunkId(s.path()));
        }

        // Compute token estimates for the result
        int snippetTokens = 0;
        for (ContextResult.Snippet s : packed) {
            snippetTokens += budget.estimateSnippetTokens(s.path(), s.text());
        }
        int systemTokens = budget.estimateTokens(systemPrompt);
        int queryTokens = budget.estimateTokens(userQuery);
        int totalEstimated = systemTokens + queryTokens + snippetTokens;

        boolean wasTrimmed = packed.size() < originalCount || anyTruncated;

        return new ContextResult(
                packed,
                new ArrayList<>(citationSet),
                originalCount,
                packed.size(),
                wasTrimmed,
                totalEstimated,
                budget.contextMaxTokens()
        );
    }

    /** Convenience overload without reservation. */
    public ContextResult pack(String systemPrompt, String userQuery,
                              List<ContextResult.Snippet> pinned,
                              List<ContextResult.Snippet> regular) {
        return pack(systemPrompt, userQuery, pinned, regular, false);
    }

    // ───── helpers ─────

    private static String stripChunkId(String path) {
        if (path == null) return "";
        int i = path.indexOf('#');
        return (i < 0) ? path : path.substring(0, i);
    }

    private static List<ContextResult.Snippet> sanitizeAll(List<ContextResult.Snippet> xs) {
        List<ContextResult.Snippet> out = new ArrayList<>();
        if (xs == null) return out;
        for (ContextResult.Snippet s : xs) {
            if (s == null) continue;
            String cleanText = Sanitize.sanitizeForPrompt(s.text());
            out.add(new ContextResult.Snippet(s.path(), cleanText));
        }
        return out;
    }
}

