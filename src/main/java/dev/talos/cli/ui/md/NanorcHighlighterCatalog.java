package dev.talos.cli.ui.md;

import org.jline.builtins.SyntaxHighlighter;
import org.jline.utils.AttributedString;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lazy registry of nanorc syntax highlighters for fenced code blocks (T778).
 *
 * <p>JLine's uber-jar ships the {@link SyntaxHighlighter} nanorc engine but
 * zero syntax definitions; Talos bundles its own minimal, project-licensed
 * definitions under {@code /nanorc/} (GNU nano's files are GPLv3 and are
 * deliberately NOT vendored — owner decision). Definitions cover keywords,
 * strings, comments, and numbers; they are a rendering nicety, so every
 * failure path (unknown language, missing resource, parse error) degrades
 * to plain text rather than ever failing a turn.
 */
final class NanorcHighlighterCatalog {

    private static final NanorcHighlighterCatalog SHARED = new NanorcHighlighterCatalog();

    /** Fence-tag aliases → bundled definition name. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("java", "java"),
            Map.entry("python", "python"), Map.entry("py", "python"),
            Map.entry("javascript", "javascript"), Map.entry("js", "javascript"),
            Map.entry("typescript", "javascript"), Map.entry("ts", "javascript"),
            Map.entry("json", "json"),
            Map.entry("yaml", "yaml"), Map.entry("yml", "yaml"),
            Map.entry("bash", "bash"), Map.entry("sh", "bash"),
            Map.entry("shell", "bash"), Map.entry("zsh", "bash"),
            Map.entry("diff", "diff"), Map.entry("patch", "diff"),
            Map.entry("xml", "xml"),
            Map.entry("html", "html"), Map.entry("htm", "html"),
            Map.entry("css", "css"));

    private final ConcurrentHashMap<String, Optional<SyntaxHighlighter>> cache =
            new ConcurrentHashMap<>();

    static NanorcHighlighterCatalog shared() {
        return SHARED;
    }

    /**
     * Highlights one complete code line, or returns {@code null} when no
     * highlighter is available for the language — callers fall back to the
     * plain path.
     */
    AttributedString highlight(String language, String line) {
        Optional<SyntaxHighlighter> highlighter = highlighterFor(language);
        if (highlighter.isEmpty()) {
            return null;
        }
        try {
            return highlighter.get().highlight(line);
        } catch (RuntimeException brokenRule) {
            return null;
        }
    }

    Optional<SyntaxHighlighter> highlighterFor(String language) {
        if (language == null || language.isBlank()) {
            return Optional.empty();
        }
        String definition = ALIASES.get(language.trim().toLowerCase(Locale.ROOT));
        if (definition == null) {
            return Optional.empty();
        }
        return cache.computeIfAbsent(definition, NanorcHighlighterCatalog::load);
    }

    private static Optional<SyntaxHighlighter> load(String definition) {
        try {
            SyntaxHighlighter highlighter =
                    SyntaxHighlighter.build("classpath:/nanorc/" + definition + ".nanorc");
            // Engage the definition once so a broken file fails here, not mid-stream.
            highlighter.highlight("probe");
            return Optional.of(highlighter);
        } catch (Throwable unavailable) {
            return Optional.empty();
        }
    }

    private NanorcHighlighterCatalog() {
    }
}
