package dev.talos.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@link EngineException} sealed hierarchy.
 * Validates exception metadata, guidance strings, and sealed-permit structure.
 */
class EngineExceptionTest {

    // ═══════════════════════════════════════════════════════════════════════
    //  ModelNotFound
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void modelNotFound_carries_model_name() {
        var ex = new EngineException.ModelNotFound("qwen3:8b");
        assertEquals("qwen3:8b", ex.model());
        assertEquals(404, ex.httpStatus());
        assertTrue(ex.getMessage().contains("qwen3:8b"));
    }

    @Test
    void modelNotFound_guidance_is_backend_neutral() {
        var ex = new EngineException.ModelNotFound("llama3:latest");
        assertTrue(ex.guidance().contains("selected backend"));
        assertTrue(ex.guidance().contains("talos status --verbose"));
    }

    @Test
    void modelNotFound_null_model_safe() {
        var ex = new EngineException.ModelNotFound(null);
        assertEquals("", ex.model());
        assertNotNull(ex.guidance());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ConnectionFailed
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void connectionFailed_carries_host_and_guidance() {
        var cause = new java.net.ConnectException("Connection refused");
        var ex = new EngineException.ConnectionFailed("http://127.0.0.1:11434", cause);

        assertEquals(0, ex.httpStatus());
        assertTrue(ex.getMessage().contains("127.0.0.1:11434"));
        assertTrue(ex.guidance().contains("talos status --verbose"));
        assertSame(cause, ex.getCause());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Transient
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void transient_carries_status_and_guidance() {
        var ex = new EngineException.Transient("Backend returned 503", 503);
        assertEquals(503, ex.httpStatus());
        assertTrue(ex.guidance().contains("try again"));
    }

    @Test
    void transient_with_cause() {
        var cause = new RuntimeException("timeout");
        var ex = new EngineException.Transient("timed out", cause, 408);
        assertEquals(408, ex.httpStatus());
        assertSame(cause, ex.getCause());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ResponseError
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void responseError_carries_status_and_body_diagnostics_without_raw_body() {
        var ex = new EngineException.ResponseError(
                500,
                "{\"error\":\"backend echoed Eleni Nikolaou and API_TOKEN=raw-provider-token\"}");
        assertEquals(500, ex.httpStatus());
        assertTrue(ex.getMessage().contains("500"));
        assertTrue(ex.bodyHash().startsWith("sha256:"), ex.bodyHash());
        assertTrue(ex.bodyChars() > 0);
        assertTrue(ex.getMessage().contains("bodyHash=sha256:"), ex.getMessage());
        assertTrue(ex.getMessage().contains("bodyChars="), ex.getMessage());
        assertFalse(ex.getMessage().contains("Eleni Nikolaou"), ex.getMessage());
        assertFalse(ex.getMessage().contains("raw-provider-token"), ex.getMessage());
    }

    @Test
    void responseError_truncates_long_body() {
        String longBody = "x".repeat(500);
        var ex = new EngineException.ResponseError(502, longBody);
        assertTrue(ex.getMessage().contains("bodyHash=sha256:"), ex.getMessage());
        assertFalse(ex.getMessage().contains("x".repeat(200)), ex.getMessage());
    }

    @Test
    void responseError_preserves_context_budget_signal_without_raw_body() {
        String body = "request (4383 tokens) exceeds the available context size (4096 tokens)";
        var ex = new EngineException.ResponseError(400, body);

        assertTrue(ex.bodyLooksContextBudgetExceeded());
        assertFalse(ex.getMessage().contains("4383 tokens"), ex.getMessage());
        assertTrue(ex.getMessage().contains("bodyHash=sha256:"), ex.getMessage());
    }

    @Test
    void responseError_null_body_safe() {
        var ex = new EngineException.ResponseError(418, null);
        assertEquals(418, ex.httpStatus());
        assertNotNull(ex.getMessage());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  MalformedResponse
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void malformedResponse_carries_context_without_raw_provider_body() {
        var ex = new EngineException.MalformedResponse(
                "compat chat response",
                "{\"unexpected\":\"Eleni Nikolaou\", \"token\":\"raw-provider-token\"}");
        assertEquals(0, ex.httpStatus());
        assertTrue(ex.getMessage().contains("compat chat response"));
        assertTrue(ex.getMessage().contains("bodyHash=sha256:"), ex.getMessage());
        assertTrue(ex.getMessage().contains("bodyChars="), ex.getMessage());
        assertFalse(ex.getMessage().contains("Eleni Nikolaou"), ex.getMessage());
        assertFalse(ex.getMessage().contains("raw-provider-token"), ex.getMessage());
        assertEquals("", ex.bodyPreview());
    }

    @Test
    void malformedResponse_diagnostics_are_hash_and_length_only() {
        String body = "token=SECRET-VALUE Eleni Nikolaou " + "x".repeat(800);
        var ex = new EngineException.MalformedResponse("compat chat stream tool arguments", body);

        assertEquals("compat chat stream tool arguments", ex.context());
        assertEquals(body.length(), ex.bodyChars());
        assertTrue(ex.bodyHash().startsWith("sha256:"));
        assertEquals("", ex.bodyPreview());
        assertFalse(ex.getMessage().contains("SECRET-VALUE"), ex.getMessage());
        assertFalse(ex.getMessage().contains("Eleni Nikolaou"), ex.getMessage());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Sealed hierarchy
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void all_subtypes_are_engine_exceptions() {
        assertInstanceOf(EngineException.class, new EngineException.ModelNotFound("m"));
        assertInstanceOf(EngineException.class, new EngineException.ConnectionFailed("h", null));
        assertInstanceOf(EngineException.class, new EngineException.Transient("t", 503));
        assertInstanceOf(EngineException.class, new EngineException.ResponseError(500, "b"));
        assertInstanceOf(EngineException.class, new EngineException.MalformedResponse("shape", "body"));
    }

    @Test
    void subtypes_are_runtime_exceptions() {
        // Unchecked so callers can catch or let propagate
        assertInstanceOf(RuntimeException.class, new EngineException.ModelNotFound("m"));
        assertInstanceOf(RuntimeException.class, new EngineException.ConnectionFailed("h", null));
    }

    @Test
    void guidance_never_null() {
        assertEquals("", new EngineException.ResponseError(500, "x").guidance());
        assertNotNull(new EngineException.ModelNotFound("m").guidance());
        assertNotNull(new EngineException.ConnectionFailed("h", null).guidance());
        assertNotNull(new EngineException.Transient("t", 503).guidance());
        assertNotNull(new EngineException.MalformedResponse("shape", "body").guidance());
    }
}

