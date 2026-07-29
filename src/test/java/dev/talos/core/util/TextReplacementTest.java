package dev.talos.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextReplacementTest {
    @Test
    void rawUniqueMatchPreservesRawReplacementText() {
        TextReplacement.Match match = TextReplacement.findUniqueMatch(
                "alpha\nbeta\n",
                "alpha\nbeta",
                "alpha\r\nBETA");

        assertEquals(1, match.count());
        assertEquals(0, match.startRaw());
        assertEquals("alpha\nbeta".length(), match.endRaw());
        assertEquals("alpha\r\nBETA", match.replacement());
    }

    @Test
    void normalizedUniqueMatchMapsLfNeedleToCrLfRawOffsets() {
        String content = "alpha\r\nbeta\r\ngamma\r\n";
        TextReplacement.Match match = TextReplacement.findUniqueMatch(
                content,
                "alpha\nbeta",
                "alpha\nBETA");

        assertEquals(1, match.count());
        assertEquals(0, match.startRaw());
        assertEquals("alpha\r\nbeta".length(), match.endRaw());
        assertEquals("alpha\r\nBETA", match.replacement());
    }

    @Test
    void normalizedUniqueMatchMapsPrecedingCrLfToRawStartOffset() {
        String content = "alpha\r\nbeta\r\ngamma\r\n";
        TextReplacement.Match match = TextReplacement.findUniqueMatch(
                content,
                "beta\ngamma",
                "BETA\ngamma");

        assertEquals(1, match.count());
        assertEquals("alpha\r\n".length(), match.startRaw());
        assertEquals("alpha\r\nbeta\r\ngamma".length(), match.endRaw());
        assertEquals("BETA\r\ngamma", match.replacement());
    }

    @Test
    void normalizedDeletionEndingAtEofMapsRawEndAndPreservesCrLf() {
        String content = "alpha\r\nbeta\r\n";
        TextReplacement.Match match = TextReplacement.findUniqueMatch(
                content,
                "beta\n",
                "");

        assertEquals(1, match.count());
        assertEquals("alpha\r\n".length(), match.startRaw());
        assertEquals(content.length(), match.endRaw());
        assertEquals("", match.replacement());
        assertEquals(
                "alpha\r\n",
                content.substring(0, match.startRaw())
                        + match.replacement()
                        + content.substring(match.endRaw()));
    }

    @Test
    void normalizedAmbiguousMatchReturnsCountWithoutRawOffsets() {
        TextReplacement.Match match = TextReplacement.findUniqueMatch(
                "alpha\r\nbeta\r\nalpha\r\nbeta\r\n",
                "alpha\nbeta",
                "alpha\nBETA");

        assertEquals(2, match.count());
        assertEquals(-1, match.startRaw());
        assertEquals(-1, match.endRaw());
    }

    @Test
    void rawMatchDoesNotMaskSecondNormalizedEquivalentMatch() {
        TextReplacement.Match match = TextReplacement.findUniqueMatch(
                "alpha\nbeta\n---\nalpha\r\nbeta\r\n",
                "alpha\nbeta",
                "alpha\nBETA");

        assertEquals(2, match.count());
        assertEquals(-1, match.startRaw());
        assertEquals(-1, match.endRaw());
    }

    @Test
    void normalizedNoMatchReturnsZeroWithoutRawOffsets() {
        TextReplacement.Match match = TextReplacement.findUniqueMatch(
                "alpha\r\nbeta\r\n",
                "gamma\nbeta",
                "gamma\nBETA");

        assertEquals(0, match.count());
        assertEquals(-1, match.startRaw());
        assertEquals(-1, match.endRaw());
    }

    @Test
    void nullAndEmptyInputsReturnNoMatchWithoutRawOffsets() {
        assertEquals(
                new TextReplacement.Match(0, -1, -1, ""),
                TextReplacement.findUniqueMatch(null, "alpha", null));
        assertEquals(
                new TextReplacement.Match(0, -1, -1, "replacement"),
                TextReplacement.findUniqueMatch("", "alpha", "replacement"));
        assertEquals(
                new TextReplacement.Match(0, -1, -1, "replacement"),
                TextReplacement.findUniqueMatch("alpha", null, "replacement"));
        assertEquals(
                new TextReplacement.Match(0, -1, -1, "replacement"),
                TextReplacement.findUniqueMatch("alpha", "", "replacement"));
    }

    @Test
    void oldTextPresenceFlagsShrinkEditThatDidNotLand() {
        assertTrue(TextReplacement.oldTextRemainsOutsideReplacement(
                "    return value;\n",
                "    return value;",
                "return value;"));
    }

    @Test
    void oldTextPresenceAllowsOldTextWhenCoveredByGrowthReplacement() {
        assertFalse(TextReplacement.oldTextRemainsOutsideReplacement(
                "version 2026.7\n",
                "2026",
                "2026.7"));
    }

    @Test
    void oldTextPresenceFlagsStaleOldTextOutsideGrowthReplacement() {
        assertTrue(TextReplacement.oldTextRemainsOutsideReplacement(
                "version 2026\nversion 2026.7\n",
                "2026",
                "2026.7"));
    }

    @Test
    void containsNormalizedMatchesLfNeedleAgainstCrLfContent() {
        assertTrue(TextReplacement.containsNormalized("alpha\r\nbeta\r\n", "alpha\nbeta"));
    }
}
