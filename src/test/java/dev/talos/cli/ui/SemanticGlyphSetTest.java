package dev.talos.cli.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SemanticGlyphSetTest {
    private static final TerminalCapabilities UNICODE =
            new TerminalCapabilities(ColorPolicy.NEVER, true, false, true, false);
    private static final TerminalCapabilities ASCII =
            new TerminalCapabilities(ColorPolicy.NEVER, true, false, false, true);

    @Test
    void safeUnicodeUsesOnlyApprovedRendererGlyphs() {
        SemanticGlyphSet glyphs = SemanticGlyphSet.forCapabilities(UNICODE);

        assertEquals("•", glyphs.bullet());
        assertEquals("→", glyphs.arrow());
        assertEquals("✓", glyphs.success());
        assertEquals("!", glyphs.warning());
        assertEquals("x", glyphs.error());
        assertEquals("│", glyphs.vertical());
        assertEquals("─", glyphs.horizontal());
        assertEquals("┌", glyphs.topLeft());
        assertEquals("└", glyphs.bottomLeft());
        assertEquals("·", glyphs.dot());
    }

    @Test
    void asciiFallbackUsesNoQuestionMarksOrUnicode() {
        SemanticGlyphSet glyphs = SemanticGlyphSet.forCapabilities(ASCII);

        String all = String.join("", glyphs.bullet(), glyphs.arrow(), glyphs.success(),
                glyphs.warning(), glyphs.error(), glyphs.vertical(), glyphs.horizontal(),
                glyphs.topLeft(), glyphs.bottomLeft(), glyphs.dot());

        assertFalse(all.contains("?"));
        assertTrue(all.codePoints().allMatch(cp -> cp >= 0x20 && cp <= 0x7E),
                "ASCII glyph set must be terminal-safe: " + all);
        assertEquals("*", glyphs.bullet());
        assertEquals("->", glyphs.arrow());
        assertEquals("ok", glyphs.success());
        assertEquals("!", glyphs.warning());
        assertEquals("x", glyphs.error());
    }
}
