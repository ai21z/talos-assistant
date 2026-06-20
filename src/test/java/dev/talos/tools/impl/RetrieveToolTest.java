package dev.talos.tools.impl;

import dev.talos.core.Config;
import dev.talos.core.context.ContextResult;
import dev.talos.core.index.SymbolHit;
import dev.talos.core.index.SymbolKind;
import dev.talos.spi.types.ChunkMetadata;
import dev.talos.core.rag.RagService;
import dev.talos.core.security.Sandbox;
import dev.talos.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RetrieveTool}.
 * Uses the real RagService with a default config (no index → empty results).
 */
class RetrieveToolTest {

    private static ToolContext testContext(Path workspace) {
        workspace = workspace.toAbsolutePath().normalize();
        return new ToolContext(workspace, new Sandbox(workspace, Map.of()), new Config());
    }

    @Test
    void retrieve_uses_neutral_safety_for_path_omission_and_text_redaction() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/talos/tools/impl/RetrieveTool.java"));
        String baseline = Files.readString(Path.of("config/architecture-boundary-baseline.txt"));

        assertTrue(source.contains("import dev.talos.safety.ProtectedContentSanitizer;"), source);
        assertTrue(source.contains("import dev.talos.safety.ProtectedWorkspacePaths;"), source);
        assertFalse(source.contains("dev.talos.runtime.policy.ProtectedContentPolicy"), source);
        assertFalse(baseline.contains(
                        "tools-no-runtime|src/main/java/dev/talos/tools/impl/RetrieveTool.java|dev.talos.runtime.policy.ProtectedContentPolicy"),
                baseline);
    }

    @Test
    void descriptor() {
        RetrieveTool tool = new RetrieveTool(new RagService(new Config()));
        assertEquals("talos.retrieve", tool.name());
        assertNotNull(tool.descriptor().parametersSchema());
        assertTrue(tool.description().contains("retrieval"));
    }

    @Test
    void missingQueryParam(@TempDir Path workspace) {
        RetrieveTool tool = new RetrieveTool(new RagService(new Config()));
        ToolCall call = new ToolCall("talos.retrieve", Map.of());
        ToolResult r = tool.execute(call, testContext(workspace));

        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
        assertTrue(r.errorMessage().contains("query"));
    }

    @Test
    void emptyQueryParam(@TempDir Path workspace) {
        RetrieveTool tool = new RetrieveTool(new RagService(new Config()));
        ToolCall call = new ToolCall("talos.retrieve", Map.of("query", "  "));
        ToolResult r = tool.execute(call, testContext(workspace));

        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
    }

    @Test
    void queryWithNoIndexDoesNotCrash(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "Tiny retrieve fixture workspace.\n");
        RetrieveTool tool = new RetrieveTool(new RagService(new Config()));
        ToolCall call = new ToolCall("talos.retrieve", Map.of("query", "test search"));
        ToolResult r = tool.execute(call, testContext(workspace));

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
    void topKParamParsed(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "Tiny retrieve fixture workspace.\n");
        // Just verify it doesn't crash with a top_k param
        RetrieveTool tool = new RetrieveTool(new RagService(new Config()));
        ToolCall call = new ToolCall("talos.retrieve", Map.of("query", "test", "top_k", "3"));
        ToolResult r = tool.execute(call, testContext(workspace));

        // Should not crash regardless of index state
        assertNotNull(r);
    }

    @Test
    void invalidTopKIgnored(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "Tiny retrieve fixture workspace.\n");
        RetrieveTool tool = new RetrieveTool(new RagService(new Config()));
        ToolCall call = new ToolCall("talos.retrieve", Map.of("query", "test", "top_k", "not-a-number"));
        ToolResult r = tool.execute(call, testContext(workspace));

        // Should use default top_k, not crash
        assertNotNull(r);
    }

    @Test
    void nullContextStillFallsBackToDefaultWorkspace() {
        RetrieveTool tool = new RetrieveTool(new RagService(new Config()) {
            @Override
            public Prepared prepare(Path ws, String query, Integer topKOverride) {
                assertNotNull(ws);
                return new Prepared(List.of(), List.of());
            }
        });
        ToolCall call = new ToolCall("talos.retrieve", Map.of("query", "test"));
        ToolResult r = tool.execute(call, null);

        assertNotNull(r);
    }

    @Test
    void retrieve_does_not_leak_dirty_index_canary(@TempDir Path workspace) {
        RetrieveTool tool = new RetrieveTool(new RagService(new Config()) {
            @Override
            public Prepared prepare(Path ws, String query, Integer topKOverride) {
                return new Prepared(
                        List.of(new ContextResult.Snippet(
                                ".env",
                                "TALOS_SECRET=DO_NOT_LEAK_T267_ENV",
                                ChunkMetadata.empty())),
                        List.of(".env"));
            }
        });

        ToolResult r = tool.execute(new ToolCall("talos.retrieve", Map.of("query", "DO_NOT_LEAK_T267_ENV")),
                testContext(workspace));

        assertTrue(r.success());
        assertFalse(r.output().contains("DO_NOT_LEAK_T267_ENV"));
        assertTrue(r.output().contains("[redacted") || r.output().contains("protected content"));
    }

    @Test
    void retrieve_redactsBareSecretShapesThroughDirectSafetyCaller(@TempDir Path workspace) {
        String jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
                + "eyJzdWIiOiJ0YWxvcyIsIm5hbWUiOiJUZXN0In0."
                + "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        RetrieveTool tool = new RetrieveTool(new RagService(new Config()) {
            @Override
            public Prepared prepare(Path ws, String query, Integer topKOverride) {
                return new Prepared(
                        List.of(new ContextResult.Snippet(
                                "README.md",
                                "Authorization bearer " + jwt,
                                ChunkMetadata.empty())),
                        List.of("README.md"));
            }
        });

        ToolResult r = tool.execute(new ToolCall("talos.retrieve", Map.of("query", "auth")),
                testContext(workspace));

        assertTrue(r.success());
        assertFalse(r.output().contains(jwt), r.output());
        assertTrue(r.output().contains("[redacted]"), r.output());
        assertTrue(r.output().contains("Some retrieval snippets contained protected markers or secret-like values"),
                r.output());
    }

    @Test
    void retrieve_renders_symbolHitEvidenceBeforeSnippets(@TempDir Path workspace) {
        RetrieveTool tool = new RetrieveTool(new RagService(new Config()) {
            @Override
            public Prepared prepare(Path ws, String query, Integer topKOverride) {
                return new Prepared(
                        List.of(new ContextResult.Snippet(
                                "src/RetrocatsService.java#0",
                                "public class RetrocatsService {}",
                                ChunkMetadata.empty())),
                        List.of("src/RetrocatsService.java"),
                        null,
                        null,
                        List.of(new SymbolHit(
                                "src/RetrocatsService.java",
                                "RetrocatsService",
                                SymbolKind.CLASS,
                                1,
                                1,
                                "public class RetrocatsService")));
            }
        });

        ToolResult r = tool.execute(new ToolCall("talos.retrieve", Map.of("query", "RetrocatsService")),
                testContext(workspace));

        assertTrue(r.success());
        assertTrue(r.output().contains("Symbol signature matches (not full file contents):"));
        assertFalse(r.output().contains("exact code evidence"));
        assertTrue(r.output().contains("RetrocatsService"));
        assertTrue(r.output().contains("CLASS"));
        assertTrue(r.output().contains("src/RetrocatsService.java:1"));
        assertTrue(r.output().indexOf("Symbol signature matches") < r.output().indexOf("Found 1 snippet result"));
    }
}



