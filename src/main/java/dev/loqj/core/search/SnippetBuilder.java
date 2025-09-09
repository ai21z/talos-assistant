package dev.loqj.core.search;

import dev.loqj.core.spi.CorpusStore;

import java.util.*;

/** Packs text snippets for prompts with a simple budget; dedupes by path. */
public final class SnippetBuilder {
    private SnippetBuilder() {}

    public record Snippet(String path, String text) {}

    /** Pack hits from the store up to ~budget characters. Deduplicates by path. */
    public static List<Snippet> pack(CorpusStore store, List<CorpusStore.Hit> hits, int budgetChars) {
        LinkedHashMap<String, Snippet> ordered = new LinkedHashMap<>();
        for (CorpusStore.Hit h : hits) {
            if (ordered.containsKey(h.path())) continue;
            String text = store.getTextByPath(h.path());
            if (text == null || text.isBlank()) continue;
            ordered.put(h.path(), new Snippet(h.path(), text));
        }
        return trimToBudget(new ArrayList<>(ordered.values()), budgetChars);
    }

    /** Merge pinned-first, then regular snippets, and trim to budget. */
    public static List<Snippet> packWithPinned(List<Snippet> pinned, List<Snippet> regular, int budgetChars) {
        LinkedHashMap<String, Snippet> ordered = new LinkedHashMap<>();
        for (Snippet s : pinned) ordered.putIfAbsent(s.path(), s);
        for (Snippet s : regular) ordered.putIfAbsent(s.path(), s);
        return trimToBudget(new ArrayList<>(ordered.values()), budgetChars);
    }

    private static List<Snippet> trimToBudget(List<Snippet> in, int budgetChars) {
        List<Snippet> out = new ArrayList<>();
        int used = 0;
        for (Snippet s : in) {
            String t = s.text();
            if (t.length() > 1400) t = t.substring(0, 1400);
            if (used + t.length() > budgetChars && !out.isEmpty()) break;
            out.add(new Snippet(s.path(), t));
            used += t.length();
        }
        return out;
    }
}
