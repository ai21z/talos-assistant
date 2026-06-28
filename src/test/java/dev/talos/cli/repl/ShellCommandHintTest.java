package dev.talos.cli.repl;

import org.junit.jupiter.api.*;

import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ShellCommandHint} (T898) - pure, side-effect-free detection
 * of a shell invocation of the talos binary typed into the REPL prompt. Such a
 * line would otherwise be routed to the model and produce a confused answer
 * (observed live: "talos setup models ..." yielded a hallucinated reply).
 */
@DisplayName("ShellCommandHint")
class ShellCommandHintTest {

    @Nested
    @DisplayName("detects a shell invocation of the talos binary")
    class Detected {
        @Test void setup_models_the_live_bug() {
            Optional<String> hint = ShellCommandHint.detect(
                    "talos setup models --profile Qwen3.6-14B-A3B-VibeForged-v2-Q6_K --write --force");
            assertTrue(hint.isPresent(), "expected a shell-command hint");
            assertTrue(hint.get().toLowerCase(Locale.ROOT).contains("terminal"),
                    "hint should point the user at their terminal");
        }

        @Test void version_short_flag()  { assertTrue(ShellCommandHint.detect("talos -v").isPresent()); }
        @Test void version_long_flag()   { assertTrue(ShellCommandHint.detect("talos --version").isPresent()); }
        @Test void doctor_subcommand()   { assertTrue(ShellCommandHint.detect("talos doctor").isPresent()); }
        @Test void rag_index_subcommand(){ assertTrue(ShellCommandHint.detect("talos rag-index").isPresent()); }
        @Test void windows_bat_binary()  { assertTrue(ShellCommandHint.detect("talos.bat setup").isPresent()); }
        @Test void windows_exe_binary()  { assertTrue(ShellCommandHint.detect("talos.exe --version").isPresent()); }
        @Test void case_insensitive()    { assertTrue(ShellCommandHint.detect("TALOS SETUP").isPresent()); }
        @Test void leading_whitespace()  { assertTrue(ShellCommandHint.detect("   talos setup").isPresent()); }
    }

    @Nested
    @DisplayName("does not match normal prompts")
    class NotMatched {
        @Test void question_mentioning_talos() {
            assertTrue(ShellCommandHint.detect("what does talos do with my files?").isEmpty());
        }
        @Test void talos_followed_by_plain_word() {
            assertTrue(ShellCommandHint.detect("talos can you read this file").isEmpty());
        }
        @Test void bare_binary_name_only() {
            assertTrue(ShellCommandHint.detect("talos").isEmpty());
        }
        @Test void unrelated_command_word() {
            assertTrue(ShellCommandHint.detect("ls src").isEmpty());
        }
        @Test void slash_command_is_not_shell() {
            assertTrue(ShellCommandHint.detect("/models").isEmpty());
        }
        @Test void null_is_empty()  { assertTrue(ShellCommandHint.detect(null).isEmpty()); }
        @Test void blank_is_empty() { assertTrue(ShellCommandHint.detect("   ").isEmpty()); }
    }
}
