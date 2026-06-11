package dev.talos.cli.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure decision tests for the JDK-22-proof console fallback (T769).
 *
 * <p>The isatty primary path needs a real terminal and is exercised by the
 * manual PTY cycle; these tests pin the fallback matrix that previously
 * mis-detected redirected output as interactive on JDK 22+.
 */
class InteractiveTtyTest {

    @Test
    void nullConsoleIsNeverInteractive() {
        // JDK <= 21 redirected/piped: console is null.
        assertFalse(InteractiveTty.consoleFallbackDecision(false, null));
        assertFalse(InteractiveTty.consoleFallbackDecision(false, true));
        assertFalse(InteractiveTty.consoleFallbackDecision(false, false));
    }

    @Test
    void jdk21ConsolePresentMeansInteractive() {
        // JDK <= 21: Console.isTerminal() does not exist (null), but a
        // non-null console implies a real terminal.
        assertTrue(InteractiveTty.consoleFallbackDecision(true, null));
    }

    @Test
    void jdk22RedirectedConsoleIsNotInteractive() {
        // JDK 22+: System.console() is non-null even when redirected;
        // isTerminal()=false is the disambiguator. This is the exact case
        // the legacy console-null check got wrong.
        assertFalse(InteractiveTty.consoleFallbackDecision(true, false));
    }

    @Test
    void jdk22RealTerminalConsoleIsInteractive() {
        assertTrue(InteractiveTty.consoleFallbackDecision(true, true));
    }

    @Test
    void reflectiveIsTerminalToleratesNullConsole() {
        assertNull(InteractiveTty.consoleIsTerminal(null));
    }
}
