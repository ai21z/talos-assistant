package dev.talos.cli.repl;

import dev.talos.core.llm.ProviderInlineHtmlLiteralProjection;
import dev.talos.core.util.Sanitize;

/**
 * Bounded display-only projection of code-bearing answers. Ordinary prose can
 * stream immediately; a possible Markdown delimiter holds the remaining answer
 * until its complete display-source context can be checked by the shared
 * recognizer. The renderer redacts input chunks before introducing this seam.
 * This class does not select provider receipts or grant tool authority.
 */
final class AnswerCodeLiteralStreamProjection {
    static final int MAX_SOURCE_CHARS = 65_536;
    static final String OMITTED_NOTICE =
            "\n[answer display truncated: retained code exceeded the display limit]\n";
    private static final String CHANGED_PREFIX_NOTICE =
            "\n[answer display omitted: sanitization changed previously displayed context]\n";

    private final StringBuilder source = new StringBuilder();
    private final StringBuilder emittedPrefix = new StringBuilder();
    private boolean retaining;
    private boolean legacyOnly;
    private boolean omitted;

    String accept(String chunk) {
        if (chunk == null || chunk.isEmpty() || omitted) return "";
        if (legacyOnly) return Sanitize.sanitizeForOutput(chunk);

        int marker = firstMarker(chunk);
        if (chunk.length() > MAX_SOURCE_CHARS - source.length()) {
            if (retaining || marker >= 0) {
                omitted = true;
                source.setLength(0);
                emittedPrefix.setLength(0);
                // Do not release a partial code/hidden-content boundary when
                // the bounded full-context proof is no longer available.
                return OMITTED_NOTICE;
            }
            legacyOnly = true;
            source.setLength(0);
            emittedPrefix.setLength(0);
            return Sanitize.sanitizeForOutput(chunk);
        }

        source.append(chunk);
        if (retaining) return "";
        if (marker >= 0) retaining = true;
        String visible = Sanitize.sanitizeForOutput(
                marker < 0 ? chunk : chunk.substring(0, marker));
        emittedPrefix.append(visible);
        return visible;
    }

    String finish() {
        if (!retaining || omitted || legacyOnly) return "";
        String raw = source.toString();
        String fallback = Sanitize.sanitizeForOutput(raw);
        String projected = ProviderInlineHtmlLiteralProjection.preserveIfInert(raw, fallback);
        String prefix = emittedPrefix.toString();
        // A full-source sanitation can pair tags across an already displayed
        // prefix. It cannot be retracted; never guess a suffix in that case.
        if (!projected.startsWith(prefix)) return CHANGED_PREFIX_NOTICE;
        return projected.substring(prefix.length());
    }

    static String complete(String text) {
        String source = text == null ? "" : text;
        return ProviderInlineHtmlLiteralProjection.preserveIfInert(
                source, Sanitize.sanitizeForOutput(source));
    }

    private static int firstMarker(String text) {
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value == '`' || value == '~') return index;
        }
        return -1;
    }
}
