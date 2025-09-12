package dev.loqj.core.search;

import dev.loqj.core.util.Sanitize;

import java.util.*;
import java.util.function.Consumer;

/**
 * Builds the final ordered snippet list with "pinned-first packing".
 * API is stable; tests rely on it. Also sanitizes snippet text and
 * applies a light per-snippet clip to avoid pathological huge chunks.
 */
public final class SnippetBuilder {

    public record Snippet(String path, String text) {}

    /**
     * Combine pinned + regular snippets into an ordered list within a char budget.
     * - Keeps first occurrence of each path (dedupe)
     * - File diversification for regulars (max 3 per file)
     * - Small negative overflow allowed (<= 200 chars) to not drop last useful chunk
     * - NEW: sanitize text and lightly clip each snippet before budgeting
     */
    public static List<Snippet> packWithPinned(List<Snippet> pinned,
                                               List<Snippet> regular,
                                               int maxChars) {
        if (maxChars <= 0) return List.of();

        // Share budget across items; also per-snippet soft cap (min(2000, budget/2) but >= 256)
        final int perCap = Math.max(256, Math.min(2000, maxChars / 2));

        final LinkedHashMap<String, Snippet> ordered = new LinkedHashMap<>();
        final int[] budget = { maxChars };

        Consumer<Snippet> tryAdd = (Snippet sRaw) -> {
            if (sRaw == null) return;

            // sanitize + clip before any length accounting
            String clean = Sanitize.sanitizeForPrompt(sRaw.text() == null ? "" : sRaw.text());
            if (clean.length() > perCap) clean = Sanitize.clip(clean, perCap);

            Snippet s = new Snippet(sRaw.path(), clean);

            if (ordered.containsKey(s.path())) return;

            int need = s.text().length(); // conservative budget by characters
            if (budget[0] - need < -200) return; // allow tiny overflow, but not too much

            ordered.put(s.path(), s);
            budget[0] -= need;
        };

        // 1) pinned
        if (pinned != null) {
            for (Snippet s : pinned) tryAdd.accept(s);
        }

        // 2) regular with light file diversification
        if (regular != null) {
            Map<String, Integer> perFile = new HashMap<>();
            for (Snippet s : regular) {
                String base = s.path();
                int idx = base.indexOf('#');
                if (idx >= 0) base = base.substring(0, idx);

                int count = perFile.getOrDefault(base, 0);
                if (count >= 3) continue; // avoid too many from same file

                tryAdd.accept(s);

                if (ordered.containsKey(s.path())) {
                    perFile.put(base, count + 1);
                }

                if (budget[0] <= 0) break;
            }
        }

        return new ArrayList<>(ordered.values());
    }
}
