package dev.talos.core.context;

import dev.talos.spi.types.ChunkMetadata;
import dev.talos.core.util.Sanitize;

import java.util.*;

/**
 * Unified context assembly: sanitizes, deduplicates, and packs snippets
 * within a token budget, producing a {@link ContextResult}.
 *
 * <p>Replaces the legacy split logic that was previously spread across
 * separate snippet builder and prompt validation classes (both removed).
 * All packing now flows through this single class.
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
 * Snippet metadata is preserved through packing and used for rich citation
 * rendering (e.g. {@code src/Foo.java:10-25 § Architecture}).
 */
public final class ContextPacker {

    private final TokenBudget budget;

    public ContextPacker(TokenBudget budget) {
        this.budget = Objects.requireNonNull(budget, "budget must not be null");
    }

    /**
     * Pack pinned + regular snippets within the token budget,
     * accounting for tokens already consumed by conversation history.
     *
     * @param systemPrompt       the system prompt (used for budget calculation)
     * @param userQuery           the user question (used for budget calculation)
     * @param historyTokens       estimated tokens consumed by conversation history
     * @param pinned              pinned snippets (highest priority)
     * @param regular             regular (retrieved) snippets
     * @param reservePerPinnedFile if true and exactly 2 distinct base files are pinned,
     *                             guarantee at least one snippet per base file
     * @return packed context result with provenance
     */
    public ContextResult pack(String systemPrompt, String userQuery, int historyTokens,
                              List<ContextResult.Snippet> pinned,
                              List<ContextResult.Snippet> regular,
                              boolean reservePerPinnedFile) {
        // Compute available character budget from token budget (history-aware)
        int availableTokens = budget.availableForSnippets(systemPrompt, userQuery, historyTokens);
        int charBudget = budget.tokensToChars(availableTokens);

        // Sanitize inputs (metadata is preserved through sanitization)
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
                    packed.add(new ContextResult.Snippet(s.path(), s.text().substring(0, take), s.metadata()));
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
            packed.add(new ContextResult.Snippet(s.path(), s.text().substring(0, take), s.metadata()));
            usedChars += take;
        }

        // Phase 3: regular snippets
        for (ContextResult.Snippet s : regSan) {
            if (usedChars >= charBudget) break;
            if (!seenPaths.add(s.path())) continue;
            int take = Math.min(charBudget - usedChars, s.text().length());
            if (take <= 0) continue;
            if (take < s.text().length()) anyTruncated = true;
            packed.add(new ContextResult.Snippet(s.path(), s.text().substring(0, take), s.metadata()));
            usedChars += take;
        }

        // Build rich citations from packed snippets using metadata
        List<String> citations = buildCitations(packed);

        // Compute token estimates for the result
        int snippetTokens = 0;
        for (ContextResult.Snippet s : packed) {
            snippetTokens += budget.estimateSnippetTokens(s.path(), s.text());
        }
        int systemTokens = budget.estimateTokens(systemPrompt);
        int queryTokens = budget.estimateTokens(userQuery);
        int totalEstimated = systemTokens + queryTokens + Math.max(0, historyTokens) + snippetTokens;

        boolean wasTrimmed = packed.size() < originalCount || anyTruncated;

        return new ContextResult(
                packed,
                citations,
                originalCount,
                packed.size(),
                wasTrimmed,
                totalEstimated,
                budget.contextMaxTokens()
        );
    }

    /**
     * Pack pinned + regular snippets within the token budget.
     * Assumes no conversation history tokens.
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
        return pack(systemPrompt, userQuery, 0, pinned, regular, reservePerPinnedFile);
    }

    /** Convenience overload without reservation. */
    public ContextResult pack(String systemPrompt, String userQuery,
                              List<ContextResult.Snippet> pinned,
                              List<ContextResult.Snippet> regular) {
        return pack(systemPrompt, userQuery, pinned, regular, false);
    }

    // ───── helpers ─────

    /**
     * Build deduplicated citations from packed snippets.
     * When metadata is available, produces rich citations like:
     * {@code src/Foo.java:10-25 § Architecture}.
     * Falls back to plain file path when metadata is absent.
     */
    public static List<String> buildCitations(List<ContextResult.Snippet> packed) {
        LinkedHashSet<String> citationSet = new LinkedHashSet<>();
        for (ContextResult.Snippet s : packed) {
            citationSet.add(formatCitation(stripChunkId(s.path()), s.metadata()));
        }
        return new ArrayList<>(citationSet);
    }

    /**
     * Format a single citation from a base path and optional metadata.
     * <ul>
     *   <li>Full metadata: {@code src/Foo.java:10-25 § Architecture}</li>
     *   <li>Lines only: {@code src/Foo.java:10-25}</li>
     *   <li>Heading only: {@code src/Foo.java § Architecture}</li>
     *   <li>No metadata: {@code src/Foo.java}</li>
     * </ul>
     * Package-private for testability.
     */
    public static String formatCitation(String basePath, ChunkMetadata meta) {
        if (meta == null || !meta.hasContent()) return basePath;
        StringBuilder sb = new StringBuilder(basePath);
        if (meta.lineStart() > 0 && meta.lineEnd() > 0) {
            sb.append(':').append(meta.lineStart()).append('-').append(meta.lineEnd());
        } else if (meta.lineStart() > 0) {
            sb.append(':').append(meta.lineStart());
        }
        if (meta.headingContext() != null && !meta.headingContext().isBlank()) {
            // Strip leading '#' characters for display
            String heading = meta.headingContext().replaceFirst("^#+\\s*", "");
            if (!heading.isBlank()) {
                sb.append(" \u00a7 ").append(heading);
            }
        }
        return sb.toString();
    }

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
            out.add(new ContextResult.Snippet(s.path(), cleanText, s.metadata()));
        }
        return out;
    }
}
