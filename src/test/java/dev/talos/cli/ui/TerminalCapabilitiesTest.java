package dev.talos.cli.ui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TerminalCapabilitiesTest {

    @Test
    void noColorForcesNeverPolicy() {
        TerminalCapabilities caps = TerminalCapabilities.detect(
                Map.of("NO_COLOR", "1", "TERM", "xterm-256color"),
                true,
                "Windows 11",
                StandardCharsets.UTF_8,
                null);

        assertEquals(ColorPolicy.NEVER, caps.colorPolicy());
        assertFalse(caps.colorEnabled());
    }

    @Test
    void dumbTerminalDisablesColorAndUnicode() {
        TerminalCapabilities caps = TerminalCapabilities.detect(
                Map.of("TERM", "dumb", "TALOS_COLOR", "true"),
                true,
                "Windows 11",
                StandardCharsets.UTF_8,
                null);

        assertTrue(caps.dumbTerminal());
        assertFalse(caps.colorEnabled());
        assertFalse(caps.unicodeSafe());
    }

    @Test
    void autoPolicyDisablesColorForNonInteractiveOutput() {
        TerminalCapabilities caps = TerminalCapabilities.detect(
                Map.of("TERM", "xterm-256color"),
                false,
                "Linux",
                StandardCharsets.UTF_8,
                ColorPolicy.AUTO);

        assertFalse(caps.interactive());
        assertFalse(caps.colorEnabled());
        assertFalse(caps.unicodeSafe());
    }

    @Test
    void alwaysPolicyCanForceColorWhenTerminalIsNotDumb() {
        TerminalCapabilities caps = TerminalCapabilities.detect(
                Map.of("TERM", "xterm-256color"),
                false,
                "Linux",
                StandardCharsets.UTF_8,
                ColorPolicy.ALWAYS);

        assertTrue(caps.colorEnabled());
    }

    @Test
    void windowsTerminalIsUnicodeSafeWhenInteractive() {
        TerminalCapabilities caps = TerminalCapabilities.detect(
                Map.of("WT_SESSION", "abc"),
                true,
                "Windows 11",
                StandardCharsets.ISO_8859_1,
                ColorPolicy.AUTO);

        assertTrue(caps.unicodeSafe());
    }
}
