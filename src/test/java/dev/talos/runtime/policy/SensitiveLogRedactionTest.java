package dev.talos.runtime.policy;

import dev.talos.safety.SafeLogFormatter;
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
        assertTrue(ProtectedContentPolicy.looksProtectedPathString(".git/config"));
        assertTrue(ProtectedContentPolicy.looksProtectedPathString(".github/workflows/deploy.yml"));
        assertTrue(ProtectedContentPolicy.looksProtectedPathString(".aws/credentials"));
        assertTrue(ProtectedContentPolicy.looksProtectedPathString(".gnupg/trustdb.gpg"));
        assertTrue(ProtectedContentPolicy.looksProtectedPathString("keys/service.pfx"));
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

    @Test
    void high_risk_user_controlled_log_values_are_safely_handled() throws Exception {
        String registry = source("src/main/java/dev/talos/tools/ToolRegistry.java");
        String editTool = source("src/main/java/dev/talos/tools/impl/FileEditTool.java");
        String writeTool = source("src/main/java/dev/talos/tools/impl/FileWriteTool.java");
        String reranker = source("src/main/java/dev/talos/core/rerank/ScoreThresholdReranker.java");

        assertTrue(registry.contains("Fuzzy tool match resolved"), registry);
        assertTrue(registry.contains("Alias tool match resolved"), registry);
        assertFalse(registry.contains("SafeLogFormatter.value(name)"), registry);
        assertFalse(registry.contains("name, tool.name()"), registry);
        assertFalse(registry.contains("name, decision.canonicalToolName()"), registry);

        // T755: in-tool sanitization (and its path-bearing debug logging) is
        // gone — sanitization happens once, pre-approval, in the runtime's
        // MarkdownCommentaryCallNormalizer, and is trace-recorded as
        // hash/byte summaries only. The tools must not log or sanitize.
        assertFalse(editTool.contains("ContentSanitizer"), editTool);
        assertFalse(editTool.contains("LOG.debug"), editTool);
        assertFalse(writeTool.contains("ContentSanitizer"), writeTool);
        assertFalse(writeTool.contains("LOG.debug"), writeTool);

        assertTrue(reranker.contains("Rerank: dropping candidate (score {}, below threshold {})"), reranker);
        assertFalse(reranker.contains("SafeLogFormatter.value(c.path())"), reranker);
        assertFalse(reranker.contains("c.path(), c.score(), threshold"), reranker);
    }

    @Test
    void broader_runtime_diagnostics_safe_format_paths_models_and_endpoint_values() throws Exception {
        String firstRun = source("src/main/java/dev/talos/app/ui/TerminalFirstRun.java");
        String embeddings = source("src/main/java/dev/talos/core/embed/EmbeddingsClient.java");
        String lucene = source("src/main/java/dev/talos/core/index/LuceneStore.java");
        String executor = source("src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java");
        String reprompt = source("src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java");
        String overlayContinuation = source(
                "src/main/java/dev/talos/runtime/toolcall/ToolRepromptOverlayContinuation.java");
        String support = source("src/main/java/dev/talos/runtime/toolcall/ToolCallSupport.java");

        assertTrue(firstRun.contains("SafeLogFormatter.value(SENTINEL)"), firstRun);
        assertFalse(firstRun.contains("SENTINEL, ex"), firstRun);

        assertTrue(embeddings.contains("SafeLogFormatter.value(this.host)"), embeddings);
        assertFalse(embeddings.contains("services.\", this.host"), embeddings);
        assertFalse(embeddings.contains("from {} {} — skipping\", ep.path, ep.param"), embeddings);
        assertFalse(embeddings.contains("Empty embedding from {} {} (continuing to next attempt)\", ep.path, ep.param"),
                embeddings);
        assertFalse(embeddings.contains("Batch embedding size mismatch from {} {} (expected {}, got {})\",\n                            ep.path, ep.param"),
                embeddings);

        assertTrue(lucene.contains("SafeLogFormatter.value(path)"), lucene);
        assertFalse(lucene.contains("Skip vector for {} (have={}, expected={})\", path"), lucene);

        assertTrue(executor.contains("SafeLogFormatter.value(mnf.model())"), executor);
        assertFalse(executor.contains("LOG.warn(\"Model not found: {}\", mnf.model())"), executor);

        assertFalse(reprompt.contains("mnf.model()"), reprompt);
        assertTrue(overlayContinuation.contains("SafeLogFormatter.value(mnf.model())"), overlayContinuation);
        assertFalse(reprompt.contains("state.iterations, mnf.model()"), reprompt);
        assertFalse(reprompt.contains("retryName, mnf.model()"), reprompt);

        assertTrue(support.contains("SafeLogFormatter.value(call.toolName())"), support);
        assertFalse(support.contains("call.toolName());"), support);
    }

    private static String source(String path) throws Exception {
        return Files.readString(Path.of(path));
    }
}
