package dev.talos.cli.repl;

import dev.talos.core.llm.ProviderInlineHtmlLiteralProjection;
import dev.talos.core.util.Sanitize;

/** Complete selected text is projected before terminal layout. No raw streaming state is retained. */
final class AnswerCodeLiteralStreamProjection {
    static final int MAX_SOURCE_CHARS = 65_536;

    static String complete(String text) {
        String source = dev.talos.runtime.ToolCallStreamFilter.projectComplete(text);
        return ProviderInlineHtmlLiteralProjection.preserveIfInert(
                source, Sanitize.sanitizeForOutput(source));
    }
}
