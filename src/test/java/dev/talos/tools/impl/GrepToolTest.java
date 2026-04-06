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

class GrepToolTest {

    @TempDir Path workspace;
    private GrepTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() throws IOException {
        tool = new GrepTool();
        Sandbox sandbox = new Sandbox(workspace, Map.of());
        ctx = new ToolContext(workspace, sandbox, new Config());

        Files.writeString(workspace.resolve("App.java"),
                "package com.example;\npublic class App {\n    public void run() {}\n}\n");
        Files.writeString(workspace.resolve("README.md"),
                "# My Project\nThis is a demo project.\nSee App.java for details.\n");
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/Util.java"),
                "package com.example;\npublic class Util {\n    public static String hello() { return \"hello\"; }\n}\n");
        Files.createDirectories(workspace.resolve(".git"));
        Files.writeString(workspace.resolve(".git/config"), "some git config with public");
    }

    @Test void descriptor() {
        assertEquals("talos.grep", tool.name());
        assertNotNull(tool.descriptor().parametersSchema());
    }

    @Test void plainTextSearch() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "public class")), ctx);
        assertTrue(r.success());
        assertTrue(r.output().contains("App.java"));
        assertTrue(r.output().contains("Util.java"));
    }

    @Test void regexSearch() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "class\\s+\\w+", "regex", "true")), ctx);
        assertTrue(r.success());
        assertTrue(r.output().contains("App.java"));
    }

    @Test void includeGlobFilter() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "public", "include", "*.java")), ctx);
        assertTrue(r.success());
        assertTrue(r.output().contains(".java"));
        assertFalse(r.output().contains("README.md"));
    }

    @Test void noMatchesFound() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "xyznonexistentxyz")), ctx);
        assertTrue(r.success());
        assertTrue(r.output().contains("No matches"));
    }

    @Test void maxResultsRespected() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "public", "max_results", "1")), ctx);
        assertTrue(r.success());
        assertTrue(r.output().contains("1 match"));
    }

    @Test void skipsGitDirectory() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "git config")), ctx);
        assertTrue(r.success());
        assertTrue(r.output().contains("No matches"));
    }

    @Test void missingPatternParam() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of()), ctx);
        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
    }

    @Test void invalidRegexReturnsError() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "[invalid", "regex", "true")), ctx);
        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
    }

    @Test void matchesIncludeLineNumbers() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "class App", "include", "*.java")), ctx);
        assertTrue(r.success());
        // GrepTool format: "path:line | content"
        assertTrue(r.output().contains(":2 "), "Expected line number in output: " + r.output());
    }

    @Test void caseInsensitiveByDefault() {
        var r = tool.execute(new ToolCall("talos.grep", Map.of("pattern", "PUBLIC CLASS")), ctx);
        assertTrue(r.success());
        assertFalse(r.output().contains("No matches"));
    }
}
