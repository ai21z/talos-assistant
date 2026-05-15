package dev.talos.runtime.policy;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveLogRedactionTest {

    @Test
    void debug_log_sanitizes_tool_parameters() {
        Map<String, String> params = ProtectedContentPolicy.sanitizeToolParameters(Map.of(
                "pattern", "FILE_DISCOVERED_CANARY_T275_SECRET",
                "path", ".env",
                "content", "API_TOKEN=t275-token-should-not-appear"));

        String rendered = params.toString();
        assertFalse(rendered.contains("FILE_DISCOVERED_CANARY_T275_SECRET"));
        assertFalse(rendered.contains("t275-token-should-not-appear"));
        assertFalse(rendered.contains(".env"));
        assertTrue(rendered.contains("[redacted-canary]"));
        assertTrue(rendered.contains("<protected-path>"));
    }

    @Test
    void command_trace_sanitizes_stdout_stderr_canaries() {
        String redacted = ProtectedContentPolicy.sanitizeText(
                "stdout FILE_DISCOVERED_CANARY_T275_ENV\npassword=t275-password-should-not-appear");

        assertFalse(redacted.contains("FILE_DISCOVERED_CANARY_T275_ENV"));
        assertFalse(redacted.contains("t275-password-should-not-appear"));
        assertTrue(redacted.contains("[redacted-canary]"));
        assertTrue(redacted.contains("password=[redacted]"));
    }

    @Test
    void debug_log_sanitizes_protected_paths() {
        assertTrue(ProtectedContentPolicy.looksProtectedPathString(".env"));
        assertTrue(ProtectedContentPolicy.looksProtectedPathString("secrets/private-notes.md"));
        assertTrue(ProtectedContentPolicy.looksProtectedPathString("protected/private-notes.md"));
    }
}
