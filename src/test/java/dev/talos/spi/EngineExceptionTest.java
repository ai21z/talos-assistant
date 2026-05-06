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
    void responseError_carries_status_and_body() {
        var ex = new EngineException.ResponseError(500, "internal server error");
        assertEquals(500, ex.httpStatus());
        assertTrue(ex.getMessage().contains("500"));
        assertTrue(ex.getMessage().contains("internal server error"));
    }

    @Test
    void responseError_truncates_long_body() {
        String longBody = "x".repeat(500);
        var ex = new EngineException.ResponseError(502, longBody);
        // Should be truncated to ~200 chars
        assertTrue(ex.getMessage().length() < longBody.length());
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
    void malformedResponse_carries_context_without_http_status() {
        var ex = new EngineException.MalformedResponse("compat chat response", "{\"unexpected\":true}");
        assertEquals(0, ex.httpStatus());
        assertTrue(ex.getMessage().contains("compat chat response"));
        assertTrue(ex.getMessage().contains("unexpected"));
    }

    @Test
    void malformedResponse_diagnostic_preview_is_capped_and_redacted() {
        String body = "token=SECRET-VALUE " + "x".repeat(800);
        var ex = new EngineException.MalformedResponse("compat chat stream tool arguments", body);

        assertEquals("compat chat stream tool arguments", ex.context());
        assertEquals(body.length(), ex.bodyChars());
        assertTrue(ex.bodyHash().startsWith("sha256:"));
        assertTrue(ex.bodyPreview().contains("token=[redacted]"));
        assertFalse(ex.bodyPreview().contains("SECRET-VALUE"));
        assertTrue(ex.bodyPreview().length() <= 501);
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

