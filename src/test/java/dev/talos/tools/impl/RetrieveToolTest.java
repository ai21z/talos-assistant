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
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

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
    void directCancellationFromRetrievalPropagates(@TempDir Path workspace) {
        CancellationException cancellation = new CancellationException("stop retrieval");
        RetrieveTool tool = new RetrieveTool(new RagService(new Config()) {
            @Override
            public Prepared prepare(Path ws, String query, Integer topKOverride) {
                throw cancellation;
            }
        });

        CancellationException thrown = assertThrows(
                CancellationException.class,
                () -> tool.execute(
                        new ToolCall("talos.retrieve", Map.of("query", "test")),
                        testContext(workspace)));

        assertSame(cancellation, thrown);
    }

    @Test
    void wrappedInterruptionFromRetrievalPropagatesAndRestoresFlag(@TempDir Path workspace) {
        RuntimeException wrapper = new RuntimeException(new InterruptedException("stop retrieval"));
        RetrieveTool tool = new RetrieveTool(new RagService(new Config()) {
            @Override
            public Prepared prepare(Path ws, String query, Integer topKOverride) {
                throw wrapper;
            }
        });

        try {
            RuntimeException thrown = assertThrows(
                    RuntimeException.class,
                    () -> tool.execute(
                            new ToolCall("talos.retrieve", Map.of("query", "test")),
                            testContext(workspace)));

            assertSame(wrapper, thrown);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void boundedWrapperDepthInterruptionStillPropagates(@TempDir Path workspace) {
        RuntimeException wrapper = boundedDepthWrapper(new InterruptedException("stop retrieval"));
        RetrieveTool tool = new RetrieveTool(new RagService(new Config()) {
            @Override
            public Prepared prepare(Path ws, String query, Integer topKOverride) {
                throw wrapper;
            }
        });

        try {
            RuntimeException thrown = assertThrows(
                    RuntimeException.class,
                    () -> tool.execute(
                            new ToolCall("talos.retrieve", Map.of("query", "test")),
                            testContext(workspace)));

            assertSame(wrapper, thrown);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void typedValidationBarrierDoesNotBecomeInterruption(@TempDir Path workspace) {
        RuntimeException wrapper = new RuntimeException(
                new IllegalArgumentException(new InterruptedException("not caller interruption")));
        RetrieveTool tool = new RetrieveTool(new RagService(new Config()) {
            @Override
            public Prepared prepare(Path ws, String query, Integer topKOverride) {
                throw wrapper;
            }
        });

        ToolResult result = tool.execute(
                new ToolCall("talos.retrieve", Map.of("query", "test")),
                testContext(workspace));

        assertFalse(result.success());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void ordinaryRetrievalFailureRemainsStructuredToolFailure(@TempDir Path workspace) {
        RetrieveTool tool = new RetrieveTool(new RagService(new Config()) {
            @Override
            public Prepared prepare(Path ws, String query, Integer topKOverride) {
                throw new IllegalStateException("ordinary retrieval failure");
            }
        });

        ToolResult result = tool.execute(
                new ToolCall("talos.retrieve", Map.of("query", "test")),
                testContext(workspace));

        assertFalse(result.success());
        assertEquals(ToolError.INTERNAL_ERROR, result.error().code());
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
        assertEquals(List.of("src/RetrocatsService.java"), sourcePaths(r));
    }

    private static List<String> sourcePaths(ToolResult result) {
        try {
            Method method = result.contentMetadata().getClass().getMethod("sourcePaths");
            @SuppressWarnings("unchecked")
            List<String> paths = (List<String>) method.invoke(result.contentMetadata());
            return paths;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Tool content metadata must expose structured source paths", e);
        }
    }

    private static RuntimeException boundedDepthWrapper(Throwable leaf) {
        Throwable current = leaf;
        for (int i = 0; i < 64; i++) current = new RuntimeException(current);
        return (RuntimeException) current;
    }

    // Index actions and failures are stated rather than reported as empty results.
    private static final ToolCall CALL = new ToolCall("talos.retrieve", Map.of("query", "loom"));

    @Test
    void aFailureWithNoResultsIsAFailedToolResult() {
        ToolResult result = tool(new RagService.Prepared(List.of(), List.of(), null, "Index corrupted")).execute(CALL, null);
        assertFalse(result.success(), result.toString());
        assertTrue(result.errorMessage().contains("Retrieval failed: Index corrupted"), result.errorMessage());
        assertFalse(result.toString().contains("No results found"), result.toString());
    }

    @Test
    void aFailureWithResultsIsReportedAsPartial() {
        var snippet = new ContextResult.Snippet("README.md#0", "The loom catalog.");
        ToolResult result = tool(new RagService.Prepared(List.of(snippet), List.of(), null, "vector stage failed"))
                .execute(CALL, null);
        assertTrue(result.success(), result.toString());
        assertTrue(result.output().contains("README.md"), result.output());
        assertTrue(result.output().contains("Retrieval was partial: vector stage failed"), result.output());
    }

    @Test
    void anIndexBuiltInThisCallIsStated() {
        var snippet = new ContextResult.Snippet("README.md#0", "The loom catalog.");
        var prepared = new RagService.Prepared(List.of(snippet), List.of(), null, null, List.of(),
                RagService.IndexAction.BUILT, "The workspace index was built in this call, 1 file scanned.");
        ToolResult result = tool(prepared).execute(CALL, null);
        assertTrue(result.success());
        assertTrue(result.output().startsWith("The workspace index was built in this call, 1 file scanned."), result.output());
    }

    @Test
    void aFailedIndexBuildIsAFailedToolResult() {
        var prepared = new RagService.Prepared(List.of(), List.of(), null,
                "The workspace index could not be built: disk full", List.of(),
                RagService.IndexAction.FAILED, "The workspace index could not be built: disk full");
        ToolResult result = tool(prepared).execute(CALL, null);
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("could not be built: disk full"), result.errorMessage());
    }

    @Test
    void aGenuinelyEmptyResultKeepsItsWording() {
        ToolResult result = tool(new RagService.Prepared(List.of(), List.of())).execute(CALL, null);
        assertTrue(result.success());
        assertEquals("No results found for: loom", result.output());
    }

    private static RetrieveTool tool(RagService.Prepared prepared) {
        return new RetrieveTool(new RagService(new Config(null)) {
            @Override public Prepared prepare(Path ws, String query, Integer topK) { return prepared; }
        });
    }
}
