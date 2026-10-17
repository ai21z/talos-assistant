package dev.talos.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class SanitizeTerminalOutputTest {

    @Test
    void asciiFallbackPreservesCommonMeaning() {
        String input = "left ← right → wait… yes ✓ no ❌ warn ⚠ <= ≤ >= ≥ quote “x”";

        String output = Sanitize.toAsciiFallback(input);

        assertEquals("left <- right -> wait... yes [ok] no [error] warn [warning] <= <= >= >= quote \"x\"", output);
    }

    @Test
    void terminalOutputDowngradesOnlyWhenUnicodeUnsafe() {
        String input = "Use tools — then verify…";

        assertEquals("Use tools — then verify…", Sanitize.sanitizeForTerminalOutput(input, true));
        assertEquals("Use tools - then verify...", Sanitize.sanitizeForTerminalOutput(input, false));
    }

    @Test
    void terminalOutputStillStripsUnsafeSequences() {
        String input = "Hello \u001B[31mWorld\u001B[0m <think>secret</think> — done";

        String output = Sanitize.sanitizeForTerminalOutput(input, false);

        assertFalse(output.contains("\u001B"));
        assertFalse(output.contains("<think>"));
        assertEquals("Hello World  - done", output);
    }

    @Test
    void asciiFallbackTurnsNarrowNoBreakSpacesAroundInlineCodeIntoSpaces() {
        String input = "I\u2019m unable to view the text of the\u202f"
                + "`medical\u2011notes.docx`\u202ffile.";

        String output = Sanitize.toAsciiFallback(input);

        assertEquals("I'm unable to view the text of the `medical-notes.docx` file.", output);
        assertFalse(output.contains("?"), output);
    }

    @Test
    void plainPunctuationUsesTheFallbackTableOnlyForSelectedCharacters() {
        String punctuation = "  ‘’‛′“”‟″"
                + "‐‑‒–—―−…";
        assertEquals(Sanitize.toAsciiFallback(punctuation), Sanitize.plainPunctuation(punctuation, cp -> true));

        String text = "Nera’s well‑kept lamp → café 海";
        assertEquals("Nera's well-kept lamp → café 海", Sanitize.plainPunctuation(text, cp -> true));
        assertEquals("Nera’s well-kept lamp → café 海",
                Sanitize.plainPunctuation(text, cp -> cp == 0x2011));
        assertEquals(text, Sanitize.plainPunctuation(text, cp -> false));
    }
}
