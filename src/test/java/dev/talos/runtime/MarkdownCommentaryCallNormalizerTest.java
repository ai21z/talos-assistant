package dev.talos.runtime;

import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T755: pre-approval markdown-commentary sanitization of write/edit tool
 * calls. Detailed strip-detection behavior is pinned by ContentSanitizerTest;
 * these tests pin the call-replacement mechanics (key selection, key
 * preservation, accounting, exemptions).
 */
class MarkdownCommentaryCallNormalizerTest {

    private static final String COMMENTARY =
            "body { color: red; }\n```\n## Summary\n- Adjusted the body color\n";
    private static final String SANITIZED = "body { color: red; }\n";

    @Test
    void stripsWriteContentAndPreservesKey() {
        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "path", "styles.css",
                "content", COMMENTARY));

        var n = MarkdownCommentaryCallNormalizer.normalizeWriteContent(call);

        assertTrue(n.changed());
        assertEquals("content", n.key());
        assertEquals(SANITIZED, n.call().param("content"));
        assertEquals("styles.css", n.call().param("path"));
        assertEquals(COMMENTARY.length() - SANITIZED.length(), n.strippedChars());
        assertEquals(COMMENTARY, n.beforeValue());
        assertEquals(SANITIZED, n.afterValue());
    }

    @Test
    void stripsAliasContentKeyAndWritesBackToSameKey() {
        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "path", "styles.css",
                "text", COMMENTARY));

        var n = MarkdownCommentaryCallNormalizer.normalizeWriteContent(call);

        assertTrue(n.changed());
        assertEquals("text", n.key());
        assertEquals(SANITIZED, n.call().param("text"));
        assertNull(n.call().param("content"), "must not introduce a new key");
    }

    @Test
    void stripsEditNewStringAliasKey() {
        ToolCall call = new ToolCall("talos.edit_file", Map.of(
                "path", "styles.css",
                "old_string", "red",
                "replacement", COMMENTARY));

        var n = MarkdownCommentaryCallNormalizer.normalizeEditNewString(call);

        assertTrue(n.changed());
        assertEquals("replacement", n.key());
        assertEquals(SANITIZED, n.call().param("replacement"));
        assertEquals("red", n.call().param("old_string"), "old_string untouched");
    }

    @Test
    void markdownTargetIsExempt() {
        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "path", "docs/README.md",
                "content", COMMENTARY));

        var n = MarkdownCommentaryCallNormalizer.normalizeWriteContent(call);

        assertFalse(n.changed());
        assertSame(call, n.call());
    }

    @Test
    void cleanContentPassesThroughUnchanged() {
        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "path", "styles.css",
                "content", "body { color: red; }\n"));

        var n = MarkdownCommentaryCallNormalizer.normalizeWriteContent(call);

        assertFalse(n.changed());
        assertSame(call, n.call());
    }

    @Test
    void missingContentKeyPassesThrough() {
        ToolCall call = new ToolCall("talos.write_file", Map.of("path", "styles.css"));

        assertFalse(MarkdownCommentaryCallNormalizer.normalizeWriteContent(call).changed());
        assertFalse(MarkdownCommentaryCallNormalizer.normalizeEditNewString(call).changed());
    }

    @Test
    void nullCallPassesThrough() {
        var n = MarkdownCommentaryCallNormalizer.normalizeWriteContent(null);
        assertFalse(n.changed());
        assertNull(n.call());
    }
}
