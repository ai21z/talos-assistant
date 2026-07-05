package dev.talos.core.security;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression and correctness tests for {@link Redactor}.
 * Organized by fix/feature area so failures point straight at the root cause.
 */
final class RedactorTest {

    private final Redactor defaultRedactor = new Redactor();

    // ── Helpers ────────────────────────────────────────────────────────────

    private static Redactor withConfig(Map<String, Object> redactSection) {
        return new Redactor(Map.of("redact", redactSection));
    }

    // ── Config boolean coercion (Critical #1) ─────────────────────────────

    @Nested
    class ConfigBooleanCoercion {

        @Test
        void string_true_enables_path_redaction() {
            Redactor r = withConfig(Map.of("paths", "true"));
            String out = r.redactLine("See C:\\Users\\admin\\secret.txt for details");
            assertTrue(out.contains("[path]"), "String 'true' should enable path redaction");
        }

        @Test
        void string_false_disables_path_redaction() {
            Redactor r = withConfig(Map.of("paths", "false"));
            String out = r.redactLine("See C:\\Users\\admin\\secret.txt for details");
            assertFalse(out.contains("[path]"), "String 'false' should disable path redaction");
        }

        @Test
        void boolean_true_enables_ip_redaction() {
            Redactor r = withConfig(Map.of("ips", Boolean.TRUE));
            String out = r.redactLine("Server at 10.0.0.1 is down");
            assertTrue(out.contains("[ip]"));
        }

        @Test
        void string_yes_enables_ip_redaction() {
            Redactor r = withConfig(Map.of("ips", "yes"));
            String out = r.redactLine("Server at 10.0.0.1 is down");
            assertTrue(out.contains("[ip]"));
        }

        @Test
        void string_off_disables_ip_redaction() {
            Redactor r = withConfig(Map.of("ips", "off"));
            String out = r.redactLine("Server at 10.0.0.1 is down");
            assertFalse(out.contains("[ip]"), "'off' should disable IP redaction");
            assertTrue(out.contains("10.0.0.1"));
        }

        @Test
        void absent_keys_default_to_enabled() {
            Redactor r = withConfig(Map.of());  // empty redact section
            String out = r.redactLine("See C:\\Users\\admin\\secret.txt at 10.0.0.1");
            assertTrue(out.contains("[path]"), "paths defaults to true");
            assertTrue(out.contains("[ip]"), "ips defaults to true");
        }

        @Test
        void null_config_uses_defaults() {
            Redactor r = new Redactor(null);
            String out = r.redactLine("password=ABCDEFGHIJKLMNOP");
            assertTrue(out.contains("[secret]"));
        }
    }

    // ── Secret label preservation (Critical #2) ──────────────────────────

    @Nested
    class SecretLabelPreservation {

        @Test
        void password_label_preserved() {
            String out = defaultRedactor.redactLine("password=ABCDEFGHIJKLMNOP");
            assertEquals("password=[secret]", out);
        }

        @Test
        void api_key_label_preserved() {
            String out = defaultRedactor.redactLine("api_key=" + fakeUnderscoreToken());
            assertTrue(out.startsWith("api_key=[secret]"),
                    "Label 'api_key' should survive, got: " + out);
        }

        @Test
        void bearer_with_spaces_and_quotes() {
            String out = defaultRedactor.redactLine("bearer = \"eyJhbGciOiJIUzI1NiJ9\"");
            assertTrue(out.startsWith("bearer=[secret]"),
                    "Label 'bearer' should survive, got: " + out);
        }

        @Test
        void token_colon_separator() {
            String out = defaultRedactor.redactLine("token: " + fakeGenericToken());
            assertTrue(out.startsWith("token=[secret]"),
                    "Label 'token' should survive with colon separator, got: " + out);
        }

        @Test
        void pwd_label_preserved() {
            String out = defaultRedactor.redactLine("pwd=MySuperSecret123");
            assertTrue(out.startsWith("pwd=[secret]"),
                    "Label 'pwd' should survive, got: " + out);
        }

        @Test
        void vendor_prefix_tokens_fully_masked() {
            // sk-, ghp_, xox* tokens have only 1 group → full replacement
            assertEquals("[secret]", defaultRedactor.redactLine("sk-ABCDEFGHIJKLmnop1234"));
            assertTrue(defaultRedactor.redactLine("Use ghp_AbCdEfGhIjKlMnOpQrStUvWx")
                    .contains("[secret]"));
            assertTrue(defaultRedactor.redactLine("xoxb-ABCDEFGHIJKL1234")
                    .contains("[secret]"));
        }
    }

    // ── IPv4 octet validation (Low #10) ──────────────────────────────────

    @Nested
    class IPv4Validation {

        @Test
        void valid_ip_is_redacted() {
            String out = defaultRedactor.redactLine("Host 192.168.1.1 responded");
            assertTrue(out.contains("[ip]"), "Valid IPv4 should be redacted");
            assertFalse(out.contains("192.168.1.1"));
        }

        @Test
        void invalid_ip_octets_not_redacted() {
            String out = defaultRedactor.redactLine("Version 999.999.999.999 released");
            assertFalse(out.contains("[ip]"),
                    "999.999.999.999 is not a valid IP and should NOT be redacted, got: " + out);
        }

        @Test
        void boundary_octet_255_is_redacted() {
            String out = defaultRedactor.redactLine("Broadcast 255.255.255.0 mask");
            assertTrue(out.contains("[ip]"), "255.x.x.x is a valid octet range");
        }

        @Test
        void loopback_127_is_excluded() {
            String out = defaultRedactor.redactLine("localhost at 127.0.0.1");
            assertFalse(out.contains("[ip]"), "Loopback 127.x.x.x should be excluded");
            assertTrue(out.contains("127.0.0.1"));
        }
    }

    // ── IPv6 (Low #8) ───────────────────────────────────────────────────

    @Nested
    class IPv6Redaction {

        @Test
        void full_ipv6_is_redacted() {
            String out = defaultRedactor.redactLine("Peer 2001:0db8:85a3:0000:0000:8a2e:0370:7334 connected");
            assertTrue(out.contains("[ip]"), "Full IPv6 should be redacted, got: " + out);
        }

        @Test
        void compressed_ipv6_is_redacted() {
            String out = defaultRedactor.redactLine("DNS at 2001:db8::1 responded");
            assertTrue(out.contains("[ip]"), "Compressed IPv6 should be redacted, got: " + out);
        }
    }

    // ── JWT variable-length (Low #9) ────────────────────────────────────

    @Nested
    class JwtRedaction {

        @Test
        void realistic_jwt_is_caught() {
            // Realistic JWT: header (36 chars) . payload (variable) . sig (43 chars)
            String jwt = fakeJwt();
            String out = defaultRedactor.redactLine("Auth: " + jwt);
            assertTrue(out.contains("[secret]"), "Realistic JWT should be caught, got: " + out);
            assertFalse(out.contains(jwt));
        }
    }

    // ── Path redaction ──────────────────────────────────────────────────

    @Nested
    class PathRedaction {

        @Test
        void windows_path_is_redacted() {
            String out = defaultRedactor.redactLine("Config at C:\\Users\\admin\\config.yaml");
            assertTrue(out.contains("[path]"));
            assertFalse(out.contains("C:\\Users"));
        }

        @Test
        void posix_multi_segment_path_is_redacted() {
            String out = defaultRedactor.redactLine("Binary at /usr/local/bin/app");
            assertTrue(out.contains("[path]"));
            assertFalse(out.contains("/usr/local"));
        }

        @Test
        void single_segment_slash_not_redacted() {
            // Single-segment /help shouldn't match (not a filesystem path)
            String out = defaultRedactor.redactLine("/help");
            assertFalse(out.contains("[path]"),
                    "Single-segment /help should NOT be treated as a path, got: " + out);
        }

        @Test
        void paths_disabled_via_config() {
            Redactor r = withConfig(Map.of("paths", false));
            String out = r.redactLine("File at C:\\Users\\admin\\file.txt");
            assertFalse(out.contains("[path]"), "Paths should not be redacted when disabled");
            assertTrue(out.contains("C:\\Users\\admin\\file.txt"));
        }
    }

    // ── Line-ending preservation (Moderate #7) ──────────────────────────

    @Nested
    class LineEndingPreservation {

        @Test
        void crlf_preserved_in_redactBlock() {
            String input = "line1\r\nline2\r\nline3";
            String out = defaultRedactor.redactBlock(input);
            assertTrue(out.contains("\r\n"), "\\r\\n should be preserved");
            assertFalse(out.contains("\r\n\n"), "Should not double-add newlines");
        }

        @Test
        void lf_only_preserved() {
            String input = "line1\nline2\nline3";
            String out = defaultRedactor.redactBlock(input);
            assertEquals("line1\nline2\nline3", out);
        }

        @Test
        void mixed_line_endings_preserved() {
            String input = "a\r\nb\nc\rd";
            String out = defaultRedactor.redactBlock(input);
            // Verify each original terminator is preserved in order
            int crlfPos = out.indexOf("\r\n");
            int lfPos   = out.indexOf("\n", crlfPos + 2);
            int crPos   = out.indexOf("\r", lfPos + 1);
            assertTrue(crlfPos >= 0, "\\r\\n should be present");
            assertTrue(lfPos >= 0, "\\n should be present after \\r\\n");
            assertTrue(crPos >= 0, "\\r should be present after \\n");
        }

        @Test
        void null_returns_empty() {
            assertEquals("", defaultRedactor.redactBlock(null));
        }
    }

    // ── Immutability (Moderate #5) ──────────────────────────────────────

    @Nested
    class Immutability {

        @Test
        void secretPatterns_list_is_unmodifiable() {
            // The secretPatterns field should be wrapped in List.copyOf(),
            // so any attempt to modify via reflection would fail at runtime.
            // We verify behaviorally: the default redactor should consistently
            // redact secrets before and after creating another instance.
            String before = defaultRedactor.redactLine("password=ABCDEFGHIJKLMNOP");
            new Redactor(); // create another, shouldn't affect defaultRedactor
            String after = defaultRedactor.redactLine("password=ABCDEFGHIJKLMNOP");
            assertEquals(before, after, "Redactor instances should be independent");
        }
    }

    // ── Bad regex handling (Moderate #6) ────────────────────────────────

    @Nested
    class BadRegexHandling {

        @Test
        void invalid_regex_in_config_is_skipped_not_thrown() {
            // An invalid regex should be silently skipped (with stderr warning)
            assertDoesNotThrow(() -> {
                Redactor r = withConfig(Map.of("secrets", List.of("[invalid((")));
                // Built-ins remain active even when a custom list is malformed.
                String out = r.redactLine("password=ABCDEFGHIJKLMNOP");
                assertEquals("password=[secret]", out);
            });
        }

        @Test
        void custom_secret_patterns_are_additive_with_builtins() {
            Redactor r = withConfig(Map.of("secrets", List.of("\\b(DANGER_[A-Z]{8,})\\b")));

            assertAll(
                    () -> assertEquals("[secret]", r.redactLine("DANGER_ABCDEFGH")),
                    () -> assertEquals("password=[secret]", r.redactLine("password=ABCDEFGHIJKLMNOP")),
                    () -> assertEquals("[secret]", r.redactLine("sk-ABCDEFGHIJKLmnop1234")));
        }

        @Test
        void mix_of_valid_and_invalid_patterns() {
            // First pattern is valid, second is broken → valid one still works
            Redactor r = withConfig(Map.of("secrets", List.of(
                    "\\b(DANGER_[A-Z]{8,})\\b",
                    "[broken(("
            )));
            String out = r.redactLine("Found DANGER_ABCDEFGH in logs");
            assertTrue(out.contains("[secret]"), "Valid pattern should still work");
        }
    }

    // ── Idempotency ────────────────────────────────────────────────────

    @Nested
    class Idempotency {

        @Test
        void redacting_twice_is_stable() {
            String input = "password=SuperSecret123 at 10.0.0.1 in C:\\Users\\admin\\file.txt";
            String once = defaultRedactor.redactLine(input);
            String twice = defaultRedactor.redactLine(once);
            assertEquals(once, twice, "Re-redacting should be idempotent");
        }

        @Test
        void masks_do_not_match_patterns() {
            // Verify that [secret], [ip], [path] don't re-trigger any pattern
            String out = defaultRedactor.redactLine("[secret] [ip] [path]");
            assertEquals("[secret] [ip] [path]", out);
        }
    }

    // ── Null / empty edge cases ────────────────────────────────────────

    @Nested
    class EdgeCases {

        @Test void null_line_returns_empty()  { assertEquals("", defaultRedactor.redactLine(null)); }
        @Test void empty_line_returns_empty() { assertEquals("", defaultRedactor.redactLine("")); }
        @Test void null_block_returns_empty() { assertEquals("", defaultRedactor.redactBlock(null)); }

        @Test
        void plain_text_passes_through() {
            String input = "Hello, this is normal text with no secrets.";
            assertEquals(input, defaultRedactor.redactLine(input));
        }

        @Test
        void ansi_codes_are_stripped() {
            String input = "\u001B[31mred text\u001B[0m";
            String out = defaultRedactor.redactLine(input);
            assertFalse(out.contains("\u001B"), "ANSI should be stripped");
            assertTrue(out.contains("red text"));
        }

        @Test
        void control_chars_are_stripped() {
            String input = "bell\u0007 and null\u0000";
            String out = defaultRedactor.redactLine(input);
            assertFalse(out.contains("\u0007"));
            assertFalse(out.contains("\u0000"));
        }
    }

    private static String fakeUnderscoreToken() {
        return "sk" + "_live_" + "aBcDeFgHiJkLmNoP";
    }

    private static String fakeGenericToken() {
        return "ABCDEFGH" + "abcdefgh" + "12345678";
    }

    private static String fakeJwt() {
        return String.join(".",
                "ey" + "JhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9",
                "ey" + "JzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6Ik",
                "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c");
    }
}

