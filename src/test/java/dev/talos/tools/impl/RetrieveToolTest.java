package dev.talos.tools.impl;

import dev.talos.core.Config;
import dev.talos.core.rag.RagService;
import dev.talos.core.security.Sandbox;
import dev.talos.tools.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RetrieveTool}.
 * Uses the real RagService with a default config (no index → empty results).
 */
class RetrieveToolTest {

    private static ToolContext testContext() {
        Path workspace = Path.of(".").toAbsolutePath().normalize();
        return new ToolContext(workspace, new Sandbox(workspace, Map.of()), new Config());
    }

    @Test
    void descriptor() {
        RetrieveTool tool = new RetrieveTool(new RagService(new Config()));
        assertEquals("talos.retrieve", tool.name());
        assertNotNull(tool.descriptor().parametersSchema());
        assertTrue(tool.description().contains("retrieval"));
    }

    @Test
    void missingQueryParam() {
        RetrieveTool tool = new RetrieveTool(new RagService(new Config()));
        ToolCall call = new ToolCall("talos.retrieve", Map.of());
        ToolResult r = tool.execute(call, testContext());

        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
        assertTrue(r.errorMessage().contains("query"));
    }

    @Test
    void emptyQueryParam() {
        RetrieveTool tool = new RetrieveTool(new RagService(new Config()));
        ToolCall call = new ToolCall("talos.retrieve", Map.of("query", "  "));
        ToolResult r = tool.execute(call, testContext());

        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
    }

    @Test
    void queryWithNoIndexDoesNotCrash() {
        RetrieveTool tool = new RetrieveTool(new RagService(new Config()));
        ToolCall call = new ToolCall("talos.retrieve", Map.of("query", "test search"));
        ToolResult r = tool.execute(call, testContext());

        // With no real workspace/index, tool should either:
        //  - succeed with "No results" (empty retrieval)
        //  - fail gracefully with a retrieval error
        // It must NEVER throw.
        assertNotNull(r);
        if (r.success()) {
            assertTrue(r.output().contains("No results") || r.output().contains("result"),
                    "Expected results or 'No results': " + r.output());
        } else {
            assertNotNull(r.error());
        }
    }

    @Test
    void topKParamParsed() {
        // Just verify it doesn't crash with a top_k param
        RetrieveTool tool = new RetrieveTool(new RagService(new Config()));
        ToolCall call = new ToolCall("talos.retrieve", Map.of("query", "test", "top_k", "3"));
        ToolResult r = tool.execute(call, testContext());

        // Should not crash regardless of index state
        assertNotNull(r);
    }

    @Test
    void invalidTopKIgnored() {
        RetrieveTool tool = new RetrieveTool(new RagService(new Config()));
        ToolCall call = new ToolCall("talos.retrieve", Map.of("query", "test", "top_k", "not-a-number"));
        ToolResult r = tool.execute(call, testContext());

        // Should use default top_k, not crash
        assertNotNull(r);
    }

    @Test
    void nullContextStillFallsBackToDefaultWorkspace() {
        RetrieveTool tool = new RetrieveTool(new RagService(new Config()));
        ToolCall call = new ToolCall("talos.retrieve", Map.of("query", "test"));
        ToolResult r = tool.execute(call, null);

        assertNotNull(r);
    }
}



