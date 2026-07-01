package dev.talos.cli.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AnsiColor}: escape sequence generation, convenience wrappers,
 * constants, and detection utility methods.
 *
 * <p>Since color detection depends on runtime environment (System.console(),
 * env vars), we test the <em>API contract</em> rather than specific on/off states.
 */
class AnsiColorTest {

    // ── esc() ────────────────────────────────────────────────────────────────

    @Test
    void esc_returns_string_not_null() {
        // Whether color is enabled or not, esc() must never return null
        assertNotNull(AnsiColor.esc("38;5;99"));
        assertNotNull(AnsiColor.esc("0"));
        assertNotNull(AnsiColor.esc("1"));
    }

    @Test
    void esc_when_enabled_produces_ansi_sequence() {
        // If color IS enabled, the output must contain the CSI sequence
        if (AnsiColor.isEnabled()) {
            assertTrue(AnsiColor.esc("38;5;99").contains("\033[38;5;99m"));
        }
    }

    @Test
    void esc_when_disabled_produces_empty_string() {
        // If color is NOT enabled, esc should return empty string
        if (!AnsiColor.isEnabled()) {
            assertEquals("", AnsiColor.esc("38;5;99"));
            assertEquals("", AnsiColor.esc("0"));
        }
    }

    // ── fg() ─────────────────────────────────────────────────────────────────

    @Test
    void fg_returns_string_not_null() {
        assertNotNull(AnsiColor.fg(99));
        assertNotNull(AnsiColor.fg(0));
        assertNotNull(AnsiColor.fg(255));
    }

    @Test
    void fg_when_enabled_contains_256_color_code() {
        if (AnsiColor.isEnabled()) {
            String result = AnsiColor.fg(208);
            assertTrue(result.contains("38;5;208"), "fg(208) should contain 256-color code");
        }
    }

    // ── brand gradient constants exist and are non-null ─────────────────────

    @Test
    void brand_gradient_constants_are_non_null() {
        assertNotNull(AnsiColor.PURPLE, "PURPLE");
        assertNotNull(AnsiColor.VIOLET, "VIOLET");
        assertNotNull(AnsiColor.BLUE, "BLUE");
        assertNotNull(AnsiColor.ORANGE, "ORANGE");
    }

    @Test
    void semantic_color_constants_are_non_null() {
        assertNotNull(AnsiColor.GREY, "GREY");
        assertNotNull(AnsiColor.DIM, "DIM");
        assertNotNull(AnsiColor.GREEN, "GREEN");
        assertNotNull(AnsiColor.RED, "RED");
        assertNotNull(AnsiColor.YELLOW, "YELLOW");
        assertNotNull(AnsiColor.WHITE, "WHITE");
    }

    @Test
    void formatting_constants_are_non_null() {
        assertNotNull(AnsiColor.BOLD, "BOLD");
        assertNotNull(AnsiColor.DIM_ATTR, "DIM_ATTR");
        assertNotNull(AnsiColor.RESET, "RESET");
    }

    // ── convenience wrappers ─────────────────────────────────────────────────

    @Test
    void convenience_wrappers_contain_input_text() {
        String text = "hello";
        assertTrue(AnsiColor.purple(text).contains(text));
        assertTrue(AnsiColor.violet(text).contains(text));
        assertTrue(AnsiColor.blue(text).contains(text));
        assertTrue(AnsiColor.orange(text).contains(text));
        assertTrue(AnsiColor.grey(text).contains(text));
        assertTrue(AnsiColor.dim(text).contains(text));
        assertTrue(AnsiColor.green(text).contains(text));
        assertTrue(AnsiColor.red(text).contains(text));
        assertTrue(AnsiColor.yellow(text).contains(text));
        assertTrue(AnsiColor.bold(text).contains(text));
    }

    @Test
    void convenience_wrappers_end_with_reset_when_enabled() {
        if (AnsiColor.isEnabled()) {
            String reset = AnsiColor.RESET;
            assertTrue(AnsiColor.purple("x").endsWith(reset));
            assertTrue(AnsiColor.blue("x").endsWith(reset));
            assertTrue(AnsiColor.bold("x").endsWith(reset));
            assertTrue(AnsiColor.red("x").endsWith(reset));
        }
    }

    @Test
    void convenience_wrappers_return_plain_text_when_disabled() {
        if (!AnsiColor.isEnabled()) {
            assertEquals("hello", AnsiColor.purple("hello"));
            assertEquals("hello", AnsiColor.blue("hello"));
            assertEquals("hello", AnsiColor.bold("hello"));
        }
    }

    // ── brand() ──────────────────────────────────────────────────────────────

    @Test
    void brand_contains_input_text() {
        assertTrue(AnsiColor.brand("talos").contains("talos"));
    }

    @Test
    void brand_uses_bold_and_violet_when_enabled() {
        if (AnsiColor.isEnabled()) {
            String result = AnsiColor.brand("talos");
            assertTrue(result.startsWith(AnsiColor.BOLD));
            assertTrue(result.contains(AnsiColor.VIOLET));
            assertTrue(result.endsWith(AnsiColor.RESET));
        }
    }

    // ── detection flags ──────────────────────────────────────────────────────

    @Test
    void isEnabled_returns_boolean_without_exception() {
        // Just verify it doesn't throw
        boolean result = AnsiColor.isEnabled();
        assertTrue(result || !result); // tautology - we only care about no-throw
    }

    @Test
    void isUnicodeSafe_returns_boolean_without_exception() {
        boolean result = AnsiColor.isUnicodeSafe();
        assertTrue(result || !result);
    }
}

