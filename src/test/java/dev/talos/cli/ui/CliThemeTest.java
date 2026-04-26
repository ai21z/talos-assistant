package dev.talos.cli.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CliThemeTest {

    @Test
    void disabledThemeReturnsPlainText() {
        CliTheme theme = CliTheme.forCapabilities(
                new TerminalCapabilities(ColorPolicy.NEVER, true, false, false, false));

        assertEquals("talos", theme.brand("talos"));
        assertEquals("ok", theme.success("ok"));
        assertEquals("warn", theme.warning("warn"));
    }

    @Test
    void enabledThemeWrapsTrustedRendererStyles() {
        CliTheme theme = CliTheme.forCapabilities(
                new TerminalCapabilities(ColorPolicy.ALWAYS, true, true, true, false));

        String styled = theme.error("blocked");
        assertTrue(styled.contains("blocked"));
        assertTrue(styled.contains("\033[38;5;160m"));
        assertTrue(styled.endsWith("\033[0m"));
    }

    @Test
    void semanticTokensContainInputText() {
        CliTheme theme = CliTheme.forCapabilities(
                new TerminalCapabilities(ColorPolicy.ALWAYS, true, true, true, false));

        assertTrue(theme.brand("brand").contains("brand"));
        assertTrue(theme.section("section").contains("section"));
        assertTrue(theme.active("active").contains("active"));
        assertTrue(theme.success("success").contains("success"));
        assertTrue(theme.debug("debug").contains("debug"));
        assertTrue(theme.error("error").contains("error"));
        assertTrue(theme.warning("warning").contains("warning"));
        assertTrue(theme.metadata("metadata").contains("metadata"));
        assertTrue(theme.muted("muted").contains("muted"));
        assertTrue(theme.body("body").contains("body"));
    }
}
