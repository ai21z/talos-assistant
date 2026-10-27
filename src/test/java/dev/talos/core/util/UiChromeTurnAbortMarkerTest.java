package dev.talos.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract for the shared abort-marker discriminator. The marker is emitted
 * either as the whole result text (watchdog aborts) or appended after partial
 * output on its own line (transport loss after visible output), so detection
 * must be line-anchored anywhere in the text - a startsWith on the full text
 * misses the partial-output shape and lets confabulated partials pass as
 * normal answers.
 */
class UiChromeTurnAbortMarkerTest {

    @Test
    void markerOnlyTextIsDetected() {
        String text = "[turn aborted: streaming chat exceeded 300s wall-clock budget]";
        assertTrue(UiChrome.containsTurnAbortMarker(text));
        assertEquals(text, UiChrome.turnAbortMarkerLine(text));
    }

    @Test
    void trailingMarkerAfterPartialOutputIsDetected() {
        String marker = "[turn aborted: stream transport failed after partial output]";
        String text = "The first half of an answer that never finished\n" + marker;
        assertTrue(UiChrome.containsTurnAbortMarker(text));
        assertEquals(marker, UiChrome.turnAbortMarkerLine(text));
    }

    @Test
    void indentedMarkerLineIsDetected() {
        assertTrue(UiChrome.containsTurnAbortMarker("partial\n   [turn aborted: interrupted]"));
    }

    @Test
    void markerLineInTheMiddleIsDetected() {
        assertTrue(UiChrome.containsTurnAbortMarker(
                "partial\n[turn aborted: interrupted]\n\n[output notice]"));
    }

    @Test
    void midSentenceMentionDoesNotFire() {
        assertFalse(UiChrome.containsTurnAbortMarker(
                "The docs explain what the [turn aborted marker means."));
        assertEquals("", UiChrome.turnAbortMarkerLine(
                "The docs explain what the [turn aborted marker means."));
    }

    @Test
    void organicAbortedProseDoesNotFire() {
        assertFalse(UiChrome.containsTurnAbortMarker(
                "The operation was aborted by the user earlier this week."));
    }

    @Test
    void nullAndBlankAreSafe() {
        assertFalse(UiChrome.containsTurnAbortMarker(null));
        assertFalse(UiChrome.containsTurnAbortMarker(""));
        assertFalse(UiChrome.containsTurnAbortMarker("   \n  "));
        assertEquals("", UiChrome.turnAbortMarkerLine(null));
    }
}
