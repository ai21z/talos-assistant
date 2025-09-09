package dev.loqj.core.search;

import java.util.*;

/** Packs snippets with a simple pinned-first policy and a total character budget. */
public final class SnippetBuilder {

    public record Snippet(String path, String text) {}

    private SnippetBuilder() {}

    /**
     * Pinned snippets are kept in order, then regular snippets are appended until the budget is exhausted.
     * Deduplicates by exact path and lightly diversifies across files (avoid too many chunks from the same file).
     */
    public static List<Snippet> packWithPinned(List<Snippet> pinned, List<Snippet> regular, int maxChars) {
        LinkedHashMap<String, Snippet> ordered = new LinkedHashMap<>();

        // Use a mutable holder so lambdas can update the remaining budget
        final int[] budget = new int[] { maxChars };

        // Helper: attempt to add snippet if budget allows
        java.util.function.Consumer<Snippet> tryAdd = s -> {
            if (s == null) return;
            String text = s.text();
            if (text == null || text.isEmpty()) return;
            if (ordered.containsKey(s.path())) return;

            int need = text.length(); // conservative: count full text length
            if (budget[0] - need < -200) return; // allow tiny overflow but not too much

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
