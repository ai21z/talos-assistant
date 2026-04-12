package dev.talos.runtime;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.tools.*;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the path inference/repair logic in ToolCallLoop.
 *
 * <p>Verifies that when the LLM generates a write_file or edit_file tool call
 * without a 'path' parameter, the system can infer the target path from
 * conversation context (user messages, RAG snippets, tool history).
 *
 * <p>Reproduces the exact failure from the second test-output.txt where gemma4
 * sent {@code {"name":"talos.write_file","parameters":{"content":"<!DOCTYPE html>..."}}}
 * with no path at all.
 */
class PathInferenceTest {

    @TempDir Path workspace;

    /**
     * Strategy 2: User mentions file name in their question.
     * Message list: [system, user_question("update index.html")]
     * → should infer "index.html"
     */
    @Test
    void repair_infersPathFromUserQuestion() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("You are a helpful assistant."));
        messages.add(ChatMessage.user("can you update the index.html to look better?"));

        // Simulate the assistant response being added (as ToolCallLoop does at line 153)
        messages.add(ChatMessage.assistant(
                "<tool_call>\n{\"name\":\"talos.write_file\",\"parameters\":{\"content\":\"<!DOCTYPE html>\"}}\n</tool_call>"));

        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "content", "<!DOCTYPE html>"));

        // Use reflection-free approach: call repairMissingPath via exposed test helper
        ToolCall repaired = ToolCallLoop.testRepairMissingPath(call, messages);

        assertEquals("index.html", repaired.param("path"),
                "Should infer 'index.html' from user's question");
        assertEquals("<!DOCTYPE html>", repaired.param("content"),
                "Original content should be preserved");
    }

    /**
     * Strategy 3: User says "update it" but RAG context has file snippets.
     * → should infer path from RAG snippet headers.
     */
    @Test
    void repair_infersPathFromRagContext() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("You are a helpful assistant."));
        messages.add(ChatMessage.user(
                "Here is the retrieved context from the codebase. " +
                "Use these snippets to answer the question that follows.\n\n" +
                "[`index.html`]\n<!DOCTYPE html><html>...</html>\n\n"));
        messages.add(ChatMessage.user("update it to look better"));
        messages.add(ChatMessage.assistant(
                "<tool_call>\n{\"name\":\"talos.write_file\",\"parameters\":{\"content\":\"new content\"}}\n</tool_call>"));

        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "content", "new content"));

        ToolCall repaired = ToolCallLoop.testRepairMissingPath(call, messages);

        assertEquals("index.html", repaired.param("path"),
                "Should infer 'index.html' from RAG context snippet header");
    }

    /**
     * Strategy 1: Model previously called read_file in the same turn.
     * The assistant message has the read_file tool_call XML.
     * → should infer path from the read_file call.
     */
    @Test
    void repair_infersPathFromPriorReadFileInSameTurn() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("You are a helpful assistant."));
        messages.add(ChatMessage.user("read and then update index.html"));
        // First assistant response with read_file
        messages.add(ChatMessage.assistant(
                "<tool_call>\n{\"name\":\"talos.read_file\",\"parameters\":{\"path\":\"index.html\"}}\n</tool_call>"));
        messages.add(ChatMessage.user("[tool_result: talos.read_file]\n<!DOCTYPE html>\n[/tool_result]"));
        // Second assistant response with write_file (no path)
        messages.add(ChatMessage.assistant(
                "<tool_call>\n{\"name\":\"talos.write_file\",\"parameters\":{\"content\":\"updated\"}}\n</tool_call>"));

        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "content", "updated"));

        ToolCall repaired = ToolCallLoop.testRepairMissingPath(call, messages);

        assertEquals("index.html", repaired.param("path"),
                "Should infer 'index.html' from prior read_file tool call in the same turn");
    }

    /**
     * Strategy 4: Cross-turn inference from history.
     * History contains a user message mentioning "index.html" from a previous turn.
     * Current turn says "update it".
     */
    @Test
    void repair_infersPathFromHistoryUserMessage() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("You are a helpful assistant."));
        // History from Turn 1 (stored as final answer, no tool_call XML)
        messages.add(ChatMessage.user("Can you read the index.html?"));
        messages.add(ChatMessage.assistant("Here is the content of index.html: ..."));
        // Current turn
        messages.add(ChatMessage.user("update it to look modern"));
        messages.add(ChatMessage.assistant(
                "<tool_call>\n{\"name\":\"talos.write_file\",\"parameters\":{\"content\":\"modern html\"}}\n</tool_call>"));

        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "content", "modern html"));

        ToolCall repaired = ToolCallLoop.testRepairMissingPath(call, messages);

        assertEquals("index.html", repaired.param("path"),
                "Should infer 'index.html' from history user message (cross-turn)");
    }

    /**
     * No repair needed: path already present.
     */
    @Test
    void repair_noRepairWhenPathPresent() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("write to app.js"));

        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "path", "app.js",
                "content", "hello"));

        ToolCall repaired = ToolCallLoop.testRepairMissingPath(call, messages);

        // Should return the original call unchanged
        assertSame(call, repaired, "Should not repair when path is already present");
    }

    /**
     * No repair for non-write tools.
     */
    @Test
    void repair_noRepairForReadFile() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("read index.html"));

        ToolCall call = new ToolCall("talos.read_file", Map.of());

        ToolCall repaired = ToolCallLoop.testRepairMissingPath(call, messages);

        // Should return the original call unchanged
        assertSame(call, repaired, "Should not repair read_file calls");
    }

    /**
     * Path alias present: file_path instead of path.
     * Should not try to repair (alias is present).
     */
    @Test
    void repair_noRepairWhenAliasPresent() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("write to app.js"));

        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "file_path", "app.js",
                "content", "hello"));

        ToolCall repaired = ToolCallLoop.testRepairMissingPath(call, messages);

        assertSame(call, repaired, "Should not repair when file_path alias is present");
    }

    /**
     * No path inferable: user says something vague and no RAG context.
     * Should return original call (FileWriteTool will produce error).
     */
    @Test
    void repair_returnsOriginalWhenNoPathInferable() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("You are a helpful assistant."));
        messages.add(ChatMessage.user("make it look good"));

        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "content", "something"));

        ToolCall repaired = ToolCallLoop.testRepairMissingPath(call, messages);

        // No file reference anywhere — should return original
        assertSame(call, repaired, "Should return original call when no path can be inferred");
    }

    /**
     * Exact reproduction of test-output.txt Turn 3 failure.
     * The model called write_file with only "content" — no "path" at all.
     * The user's prior turn said "can you read the index.html?" and
     * the current question is "can you update the index.html to look better?"
     */
    @Test
    void endToEnd_testOutputTurn3Reproduction() {
        // Build messages exactly as they'd appear in the Turn 3 tool-call loop
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("You are a helpful coding assistant..."));

        // History from Turn 1 (final answer stored, not tool_call XML)
        messages.add(ChatMessage.user("Can you read the index.html?"));
        messages.add(ChatMessage.assistant("I have prepared the CSS file containing styles..."));

        // History from Turn 2
        messages.add(ChatMessage.user("What is this file?"));
        messages.add(ChatMessage.assistant("This file is the main structure for your BMI Calculator..."));

        // RAG context
        messages.add(ChatMessage.user(
                "Here is the retrieved context from the codebase. " +
                "Use these snippets to answer the question that follows.\n\n" +
                "[`index.html#0`]\n<!DOCTYPE html>\n<html lang=\"en\">...\n\n"));

        // Current user question
        messages.add(ChatMessage.user("can you update the index.html to look better?"));

        // Assistant response (what the model actually generated — no path)
        messages.add(ChatMessage.assistant(
                "<tool_call>\n" +
                "{\"name\":\"talos.write_file\",\"parameters\":{\"content\":\"<!DOCTYPE html>\\n<html>...\"}}\n" +
                "</tool_call>"));

        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "content", "<!DOCTYPE html>\n<html>..."));

        ToolCall repaired = ToolCallLoop.testRepairMissingPath(call, messages);

        assertNotNull(repaired.param("path"), "Path should have been inferred");
        assertEquals("index.html", repaired.param("path"),
                "Should infer 'index.html' from user's question");
        assertEquals("<!DOCTYPE html>\n<html>...", repaired.param("content"),
                "Content should be preserved");
    }
}

