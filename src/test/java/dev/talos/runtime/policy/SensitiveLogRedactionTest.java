package dev.talos.runtime.policy;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
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
    void runtime_sanitizer_redacts_private_document_fact_canaries() {
        String redacted = ProtectedContentPolicy.sanitizeText("""
                Patient Name: Eleni Nikolaou
                Address: 42 Fictional Street, Athens
                Diagnosis: fictional-condition-alpha
                Tax ID: EL-TAX-483920
                Invoice Total: 1837.42 EUR
                """);

        assertFalse(redacted.contains("Eleni Nikolaou"), redacted);
        assertFalse(redacted.contains("42 Fictional Street"), redacted);
        assertFalse(redacted.contains("fictional-condition-alpha"), redacted);
        assertFalse(redacted.contains("EL-TAX-483920"), redacted);
        assertFalse(redacted.contains("1837.42 EUR"), redacted);
        assertTrue(redacted.contains("[redacted-private-document-canary]"), redacted);
    }

    @Test
    void debug_log_sanitizes_protected_paths() {
        assertTrue(ProtectedContentPolicy.looksProtectedPathString(".env"));
        assertTrue(ProtectedContentPolicy.looksProtectedPathString("secrets/private-notes.md"));
        assertTrue(ProtectedContentPolicy.looksProtectedPathString("protected/private-notes.md"));
    }

    @Test
    void malformed_tool_payload_log_is_redacted() {
        String payload = "{\"arguments\":{\"pattern\":\"FILE_DISCOVERED_CANARY_LOG_PAYLOAD\",\"path\":\".env\"}}";

        String rendered = SafeLogFormatter.value(payload);

        assertFalse(rendered.contains("FILE_DISCOVERED_CANARY_LOG_PAYLOAD"));
        assertFalse(rendered.contains(".env"));
        assertTrue(rendered.contains("[redacted-canary]"));
        assertTrue(rendered.contains("<protected-path>"));
    }

    @Test
    void exception_message_logs_redact_canaries() {
        RuntimeException error = new RuntimeException(
                "failed reading secrets/private-notes.md: API_TOKEN=FILE_DISCOVERED_CANARY_LOG_EXCEPTION");

        String rendered = SafeLogFormatter.throwableMessage(error);

        assertFalse(rendered.contains("FILE_DISCOVERED_CANARY_LOG_EXCEPTION"));
        assertFalse(rendered.contains("secrets/private-notes.md"));
        assertTrue(rendered.contains("API_TOKEN=[redacted]"));
        assertTrue(rendered.contains("<protected-path>"));
    }

    @Test
    void all_tool_execution_debug_params_are_sanitized() throws Exception {
        String source = source("src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java");

        assertTrue(source.contains("SafeLogFormatter.parameters(effective.parameters())"), source);
    }

    @Test
    void log_callsite_toolcallparser_malformed_payload_redacts_canary() throws Exception {
        String source = source("src/main/java/dev/talos/runtime/ToolCallParser.java");

        assertTrue(source.contains("SafeLogFormatter.value(json)"), source);
        assertFalse(source.contains("LOG.warn(\"tool_call missing 'name' field: {}\", json)"), source);
    }

    @Test
    void log_callsite_json_session_store_redacts_exception_message() throws Exception {
        String source = source("src/main/java/dev/talos/runtime/JsonSessionStore.java");

        assertTrue(source.contains("SafeLogFormatter.throwableMessage(e)"), source);
        assertFalse(source.contains("e.getMessage()"), source);
    }

    @Test
    void log_callsite_provider_exception_redacts_canary() throws Exception {
        String compat = source("src/main/java/dev/talos/engine/compat/CompatChatClient.java");
        String ollama = source("src/main/java/dev/talos/engine/ollama/OllamaChatClient.java");

        assertTrue(compat.contains("SafeLogFormatter.throwableMessage(e)"), compat);
        assertTrue(ollama.contains("SafeLogFormatter.throwableMessage(e)"), ollama);
    }

    @Test
    void no_log_callsite_uses_raw_exception_message() throws Exception {
        try (var paths = Files.walk(Path.of("src/main/java"))) {
            var offenders = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> {
                        try {
                            return Files.readAllLines(path).stream()
                                    .filter(line -> line.contains("LOG."))
                                    .filter(line -> line.contains("getMessage()") || line.contains("e.toString()"))
                                    .filter(line -> !line.contains("SafeLogFormatter"))
                                    .map(line -> path + ": " + line.strip());
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .toList();
            assertTrue(offenders.isEmpty(), offenders.toString());
        }
    }

    private static String source(String path) throws Exception {
        return Files.readString(Path.of(path));
    }
}
