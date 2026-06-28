package dev.talos.tools.impl;

import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.tools.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that tool parameter aliasing works - verifying that models can use
 * alternative parameter names (file_path, text, etc.) and still have tools
 * execute successfully.
 *
 * <p>These tests reproduce the exact failures observed in test-output.txt
 * where gemma4 used non-canonical parameter names.
 */
class ParameterAliasingTest {

    @TempDir Path workspace;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        Sandbox sandbox = new Sandbox(workspace, Map.of());
        ctx = new ToolContext(workspace, sandbox, new Config());
    }

    // ── FileWriteTool parameter aliases ─────────────────────────────

    /**
     * Reproduces Turn 5 from test-output.txt:
     * Model sent {"name":"write_file","parameters":{"file_path":"index.html","text":"..."}}
     * Previously failed with: "Missing required parameter: path"
     */
    @Test
    void writeFile_withFilePathAndText() throws IOException {
        FileWriteTool tool = new FileWriteTool();
        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "file_path", "index.html",
                "text", "<!DOCTYPE html><html></html>"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success(), "Should accept file_path + text: " + r.errorMessage());
        assertTrue(r.output().contains("Created"));
        assertEquals("<!DOCTYPE html><html></html>", Files.readString(workspace.resolve("index.html")));
    }

    /**
     * Reproduces Turn 3 from test-output.txt (after alias resolution):
     * Model sent {"name":"writeFile","parameters":{"file":"index.html","text":"..."}}
     */
    @Test
    void writeFile_withFileAndText() throws IOException {
        FileWriteTool tool = new FileWriteTool();
        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "file", "style.css",
                "text", "body { margin: 0; }"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success(), "Should accept file + text: " + r.errorMessage());
        assertEquals("body { margin: 0; }", Files.readString(workspace.resolve("style.css")));
    }

    @Test
    void writeFile_canonicalParamsStillWork() throws IOException {
        FileWriteTool tool = new FileWriteTool();
        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "path", "test.txt",
                "content", "canonical"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success(), "Canonical params must still work: " + r.errorMessage());
        assertEquals("canonical", Files.readString(workspace.resolve("test.txt")));
    }

    @Test
    void writeFile_canonicalTakesPrecedenceOverAlias() throws IOException {
        // If both "path" and "file_path" are present, "path" (canonical) wins
        FileWriteTool tool = new FileWriteTool();
        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "path", "correct.txt",
                "file_path", "wrong.txt",
                "content", "hello"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success());
        assertTrue(Files.exists(workspace.resolve("correct.txt")));
        assertFalse(Files.exists(workspace.resolve("wrong.txt")));
    }

    // ── FileEditTool parameter aliases ──────────────────────────────

    @Test
    void editFile_withAliasedParams() throws IOException {
        Files.writeString(workspace.resolve("app.js"), "let x = 1;\nlet y = 2;\n");

        FileEditTool tool = new FileEditTool();
        ToolCall call = new ToolCall("talos.edit_file", Map.of(
                "file_path", "app.js",
                "oldString", "let x = 1;",
                "newString", "const x = 1;"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success(), "Should accept aliased params: " + r.errorMessage());
        String content = Files.readString(workspace.resolve("app.js"));
        assertTrue(content.contains("const x = 1;"));
    }

    // ── ReadFileTool parameter aliases ───────────────────────────────

    @Test
    void readFile_withFilePath() throws IOException {
        Files.writeString(workspace.resolve("readme.md"), "# Hello");

        ReadFileTool tool = new ReadFileTool();
        ToolCall call = new ToolCall("talos.read_file", Map.of(
                "file_path", "readme.md"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success(), "Should accept file_path: " + r.errorMessage());
        assertTrue(r.output().contains("# Hello"));
    }

    // ── ToolRegistry name aliasing ──────────────────────────────────

    /**
     * Reproduces Turn 3 from test-output.txt:
     * Model sent {"name":"writeFile",...}
     * Previously failed with: "Unknown tool: writeFile"
     */
    @Test
    void registry_resolvesCamelCaseWriteFile() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileWriteTool());

        TalosTool tool = registry.get("writeFile");
        assertNotNull(tool, "writeFile (camelCase) should resolve to talos.write_file");
        assertEquals("talos.write_file", tool.name());
    }

    @Test
    void registry_resolvesCamelCaseReadFile() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());

        TalosTool tool = registry.get("readFile");
        assertNotNull(tool, "readFile (camelCase) should resolve");
        assertEquals("talos.read_file", tool.name());
    }

    @Test
    void registry_resolvesCamelCaseEditFile() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileEditTool());

        TalosTool tool = registry.get("editFile");
        assertNotNull(tool, "editFile (camelCase) should resolve");
        assertEquals("talos.edit_file", tool.name());
    }

    @Test
    void registry_resolvesCamelCaseListDir() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ListDirTool());

        TalosTool tool = registry.get("listDir");
        assertNotNull(tool, "listDir (camelCase) should resolve");
        assertEquals("talos.list_dir", tool.name());
    }

    @Test
    void registry_snakeCaseStillWorks() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileWriteTool());

        assertNotNull(registry.get("write_file"), "write_file should resolve");
        assertNotNull(registry.get("talos.write_file"), "talos.write_file should resolve");
        assertNotNull(registry.get("file_write"), "file_write should resolve");
    }

    @Test
    void registry_mixedCaseResolves() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileWriteTool());

        // Models sometimes emit various casings
        assertNotNull(registry.get("WriteFile"), "WriteFile (PascalCase) should resolve");
        assertNotNull(registry.get("WRITEFILE"), "WRITEFILE (upper) should resolve");
    }

    // ── End-to-end: exact reproduction of test-output.txt Turn 5 ────

    /**
     * Full end-to-end: model sends write_file with file_path and text,
     * ToolRegistry resolves the name, FileWriteTool accepts the aliased params.
     */
    @Test
    void endToEnd_turn5Reproduction() throws IOException {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileWriteTool());

        // Exactly what the model sent in test-output.txt Turn 5
        ToolCall call = new ToolCall("write_file", Map.of(
                "file_path", "index.html",
                "text", "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n</head>\n<body>\n</body>\n</html>"));

        TalosTool tool = registry.get(call.toolName());
        assertNotNull(tool, "write_file should resolve to talos.write_file");

        ToolResult r = tool.execute(call, ctx);
        assertTrue(r.success(), "Should succeed with aliased params: " + r.errorMessage());

        String written = Files.readString(workspace.resolve("index.html"));
        assertTrue(written.contains("<!DOCTYPE html>"));
    }

    /**
     * Full end-to-end: model sends writeFile with file and text,
     * ToolRegistry resolves the camelCase name, FileWriteTool accepts aliased params.
     */
    @Test
    void endToEnd_turn3Reproduction() throws IOException {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileWriteTool());

        // Exactly what the model sent in test-output.txt Turn 3
        ToolCall call = new ToolCall("writeFile", Map.of(
                "file", "index.html",
                "text", "<!DOCTYPE html>"));

        TalosTool tool = registry.get(call.toolName());
        assertNotNull(tool, "writeFile should resolve to talos.write_file");

        ToolResult r = tool.execute(call, ctx);
        assertTrue(r.success(), "Should succeed with aliased params: " + r.errorMessage());

        assertEquals("<!DOCTYPE html>", Files.readString(workspace.resolve("index.html")));
    }
}

