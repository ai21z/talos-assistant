package dev.talos.cli.ui;

import dev.talos.core.util.Sanitize;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptRendererTest {
    @Test
    void plainPromptKeepsStableTextContract() {
        var caps = new TerminalCapabilities(ColorPolicy.NEVER, true, false, true, false);

        String prompt = PromptRenderer.render("auto", false, CliTheme.forCapabilities(caps));

        assertEquals("talos [auto] > ", prompt);
    }

    @Test
    void styledPromptStripsToSameStableTextContract() {
        var caps = new TerminalCapabilities(ColorPolicy.ALWAYS, true, true, true, false);

        String prompt = PromptRenderer.render("auto", true, CliTheme.forCapabilities(caps));

        assertTrue(prompt.contains("\033["));
        assertEquals("talos [auto] > ", Sanitize.stripAnsi(prompt));
    }
}
