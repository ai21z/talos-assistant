package dev.talos.core.rag;

import dev.talos.core.context.ContextResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RagService.Prepared} error-reason surfacing.
 */
class RagServicePreparedErrorTest {

    @Test
    void prepared_without_error_has_no_error_reason() {
        var p = new RagService.Prepared(List.of(), List.of());
        assertFalse(p.hasError());
        assertNull(p.errorReason());
    }

    @Test
    void prepared_with_trace_has_no_error() {
        var p = new RagService.Prepared(List.of(), List.of(), null);
        assertFalse(p.hasError());
    }

    @Test
    void prepared_with_error_reason_reports_it() {
        var p = new RagService.Prepared(List.of(), List.of(), null, "Index corrupted");
        assertTrue(p.hasError());
        assertEquals("Index corrupted", p.errorReason());
    }

    @Test
    void prepared_with_blank_error_reason_is_not_error() {
        var p = new RagService.Prepared(List.of(), List.of(), null, "  ");
        assertFalse(p.hasError());
    }

    @Test
    void prepared_with_snippets_and_error() {
        var snippet = new ContextResult.Snippet("file.java", "content");
        var p = new RagService.Prepared(List.of(snippet), List.of("file.java"), null, "partial failure");
        assertTrue(p.hasError());
        assertEquals(1, p.snippets().size());
        assertEquals("partial failure", p.errorReason());
    }

    @Test
    void prepared_null_snippets_safe() {
        var p = new RagService.Prepared(null, null, null, "error");
        assertTrue(p.hasError());
        assertTrue(p.snippets().isEmpty());
        assertTrue(p.citations().isEmpty());
    }

    @Test
    void prepared_snippetMaps_converts_correctly() {
        var snippet = new ContextResult.Snippet("src/Main.java", "class Main {}");
        var p = new RagService.Prepared(List.of(snippet), List.of("src/Main.java"));
        var maps = p.snippetMaps();
        assertEquals(1, maps.size());
        assertEquals("src/Main.java", maps.get(0).get("path"));
        assertEquals("class Main {}", maps.get(0).get("text"));
    }
}

