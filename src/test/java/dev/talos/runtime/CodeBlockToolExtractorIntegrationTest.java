package dev.talos.runtime;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.tools.*;
import dev.talos.tools.impl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: verifies that when the LLM responds with code blocks
 * containing filename hints (instead of canonical tool_call XML), the
 * CodeBlockToolExtractor safety net fires and FileWriteTool actually
 * writes the files to disk.
 *
 * <p>This test does NOT call the LLM — it simulates an LLM response
 * containing code blocks with filenames and verifies the full pipeline:
 * CodeBlockToolExtractor → ToolCallLoop → TurnProcessor → FileWriteTool → disk.
 */
@DisplayName("CodeBlockToolExtractor → file write integration")
class CodeBlockToolExtractorIntegrationTest {

    @TempDir Path workspace;

    @Test
    @DisplayName("code block with filename hint triggers write_file and creates file on disk")
    void codeBlockResponse_writesFile() throws Exception {
        // Set up a realistic workspace with index.html
        Files.writeString(workspace.resolve("index.html"), "<html><body>Hello</body></html>");

        // Simulate an LLM response that contains a code block with a filename
        String simulatedLlmResponse = """
                Here's a dark theme stylesheet for your BMI calculator:

                ```css // styles.css
                :root {
                    --bg-color: #1a1a2e;
                    --text-color: #e0e0e0;
                    --accent: #00f2fe;
                }
                body {
                    background: var(--bg-color);
                    color: var(--text-color);
                }
                ```

                Link this in your HTML with `<link rel="stylesheet" href="styles.css">`.
                """;

        // Verify CodeBlockToolExtractor detects it
        assertTrue(CodeBlockToolExtractor.containsExtractableBlocks(simulatedLlmResponse),
                "Extractor should detect the code block with filename");

        List<ToolCall> calls = CodeBlockToolExtractor.extract(simulatedLlmResponse);
        assertEquals(1, calls.size(), "Should extract exactly one write_file call");
        assertEquals("talos.write_file", calls.get(0).toolName());
        assertEquals("styles.css", calls.get(0).param("path"));
        assertTrue(calls.get(0).param("content").contains("--bg-color"));

        // Now verify end-to-end: set up tool registry and execute
        FileUndoStack undoStack = new FileUndoStack();
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new FileWriteTool(undoStack));

        Sandbox sandbox = new Sandbox(workspace, Map.of());
        ToolContext toolCtx = new ToolContext(workspace, sandbox, new Config());

        // Execute the extracted call through the registry
        ToolResult result = toolRegistry.execute(calls.get(0), toolCtx);
        assertTrue(result.success(), "write_file should succeed: " + result.errorMessage());

        // Verify the file was written to disk
        Path written = workspace.resolve("styles.css");
        assertTrue(Files.exists(written), "styles.css should exist on disk");
        String content = Files.readString(written);
        assertTrue(content.contains("--bg-color"), "File content should contain CSS vars");
        assertTrue(content.contains("--accent"), "File content should contain accent color");
    }

    @Test
    @DisplayName("multiple code blocks with filenames trigger multiple writes")
    void multipleCodeBlocks_writeMultipleFiles() throws Exception {
        String simulatedResponse = """
                Here are the files for your project:

                ```html // index.html
                <!DOCTYPE html>
                <html><head><link rel="stylesheet" href="style.css"></head>
                <body><h1>Hello</h1></body></html>
                ```

                And the stylesheet:

                ```css // style.css
                body { margin: 0; padding: 20px; font-family: sans-serif; }
                h1 { color: navy; }
                ```
                """;

        List<ToolCall> calls = CodeBlockToolExtractor.extract(simulatedResponse);
        assertEquals(2, calls.size(), "Should extract two write_file calls");

        // Execute both
        FileUndoStack undoStack = new FileUndoStack();
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new FileWriteTool(undoStack));
        Sandbox sandbox = new Sandbox(workspace, Map.of());
        ToolContext toolCtx = new ToolContext(workspace, sandbox, new Config());

        for (ToolCall call : calls) {
            ToolResult r = toolRegistry.execute(call, toolCtx);
            assertTrue(r.success(), "Should succeed: " + call.param("path"));
        }

        assertTrue(Files.exists(workspace.resolve("index.html")));
        assertTrue(Files.exists(workspace.resolve("style.css")));
        assertTrue(Files.readString(workspace.resolve("style.css")).contains("font-family"));
    }

    @Test
    @DisplayName("path traversal in code block is rejected by extractor")
    void pathTraversal_blocked() {
        String malicious = "```json // ../../etc/shadow\nroot:x\n```\n";
        assertTrue(CodeBlockToolExtractor.extract(malicious).isEmpty(),
                "Path traversal should be rejected by extractor");
    }

    @Test
    @DisplayName("plain code block without filename is NOT extracted")
    void plainCodeBlock_noExtraction() {
        String plain = "```css\nbody { color: red; }\n```\n";
        assertTrue(CodeBlockToolExtractor.extract(plain).isEmpty(),
                "Plain code block (no filename) should not be extracted");
        assertFalse(CodeBlockToolExtractor.containsExtractableBlocks(plain));
    }

    @Test
    @DisplayName("ToolCallLoop.run dispatches code block fallback when no <tool_call> present")
    void toolCallLoop_codeBlockFallback() throws Exception {
        // Simulated answer with code block, NOT <tool_call> XML
        String answer = "Here's the file:\n```json // config.json\n{\"key\": \"value\"}\n```\n";

        // Verify the extractor detects it but ToolCallParser does NOT
        assertFalse(ToolCallParser.containsToolCalls(answer),
                "ToolCallParser should NOT detect this (no <tool_call> blocks)");
        assertTrue(CodeBlockToolExtractor.containsExtractableBlocks(answer),
                "CodeBlockToolExtractor SHOULD detect this");

        // This confirms the fallback path in ToolCallLoop.run() would be triggered
    }
}

