package dev.talos.cli.repl;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LineClassifier} — pure input classification, no side effects.
 */
@DisplayName("LineClassifier")
class LineClassifierTest {

    private final LineClassifier lc = new LineClassifier();

    // ═══════════════════════════════════════════════════════════════════════
    //  EMPTY classification
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("EMPTY lines")
    class Empty {
        @Test void null_is_empty()    { assertEquals(LineClassifier.LineType.EMPTY, lc.classify(null).type()); }
        @Test void empty_is_empty()   { assertEquals(LineClassifier.LineType.EMPTY, lc.classify("").type()); }
        @Test void blank_is_empty()   { assertEquals(LineClassifier.LineType.EMPTY, lc.classify("   ").type()); }
        @Test void tab_is_empty()     { assertEquals(LineClassifier.LineType.EMPTY, lc.classify("\t").type()); }
        @Test void newline_is_empty() { assertEquals(LineClassifier.LineType.EMPTY, lc.classify("\n").type()); }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  COMMAND classification
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("COMMAND lines")
    class Commands {

        @Test void slash_help() {
            var c = lc.classify("/help");
            assertEquals(LineClassifier.LineType.COMMAND, c.type());
            assertEquals("help", c.commandName());
            assertEquals("", c.argsText());
        }

        @Test void slash_k_with_arg() {
            var c = lc.classify("/k 10");
            assertEquals(LineClassifier.LineType.COMMAND, c.type());
            assertEquals("k", c.commandName());
            assertEquals("10", c.argsText());
        }

        @Test void slash_debug_with_args() {
            var c = lc.classify("/debug on");
            assertEquals("debug", c.commandName());
            assertEquals("on", c.argsText());
        }

        @Test void slash_set_model_multi_arg() {
            var c = lc.classify("/set model qwen3:8b");
            assertEquals("set", c.commandName());
            assertEquals("model qwen3:8b", c.argsText());
        }

        @Test void slash_only() {
            var c = lc.classify("/");
            assertEquals(LineClassifier.LineType.COMMAND, c.type());
            assertEquals("", c.commandName());
        }

        @Test void slash_with_trailing_space() {
            var c = lc.classify("/q ");
            assertEquals("q", c.commandName());
            assertEquals("", c.argsText());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PROMPT classification
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PROMPT lines")
    class Prompts {

        @Test void plain_text() {
            var c = lc.classify("what is java?");
            assertEquals(LineClassifier.LineType.PROMPT, c.type());
            assertEquals("what is java?", c.argsText());
        }

        @Test void leading_space_not_command() {
            // " /help" with leading space is a prompt, not a command
            var c = lc.classify(" /help");
            assertEquals(LineClassifier.LineType.PROMPT, c.type());
        }

        @Test void ls_is_prompt() {
            var c = lc.classify("ls src");
            assertEquals(LineClassifier.LineType.PROMPT, c.type());
        }

        @Test void open_is_prompt() {
            var c = lc.classify("open README.md");
            assertEquals(LineClassifier.LineType.PROMPT, c.type());
        }
    }
}

