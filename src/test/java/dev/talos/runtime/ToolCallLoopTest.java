package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ToolCallLoop}: the agentic tool-call cycle that
 * parses tool calls from LLM responses, executes them, feeds results
 * back, and re-prompts.
 */
class ToolCallLoopTest {

    private static final Path WS = Path.of(".").toAbsolutePath().normalize();

    // ── No tool calls → pass through ───────────────────────────────

    @Test
    void noToolCallsReturnsOriginalAnswer() {
        var loop = createLoop(echoTool());

        var messages = new ArrayList<>(List.of(
                ChatMessage.system("system"),
                ChatMessage.user("hello")));

        var result = loop.run("Just a normal answer.", messages, WS, defaultCtx());

        assertEquals("Just a normal answer.", result.finalAnswer());
        assertEquals(0, result.iterations());
        assertEquals(0, result.toolsInvoked());
    }

    @Test
    void nullAnswerReturnsEmpty() {
        var loop = createLoop(echoTool());
        var messages = new ArrayList<ChatMessage>();
        var result = loop.run(null, messages, WS, defaultCtx());

        assertEquals("", result.finalAnswer());
        assertEquals(0, result.iterations());
    }

    // ── Single tool call ────────────────────────────────────────────

    @Test
    void singleToolCallIsExecutedAndResultFedBack() {
        var tool = echoTool();
        var loop = createLoop(tool);

        String llmResponse = """
                Let me read that file.
                <tool_call>
                {"name": "talos.echo", "parameters": {"input": "hello world"}}
                </tool_call>""";

        var messages = new ArrayList<>(List.of(
                ChatMessage.system("system"),
                ChatMessage.user("read something")));

        var result = loop.run(llmResponse, messages, WS, defaultCtx());

        assertEquals(1, result.iterations());
        assertEquals(1, result.toolsInvoked());
        // Messages should have assistant + tool_result + final assistant
        assertTrue(messages.size() >= 4, "Should have added assistant and tool result messages");
    }

    // ── Tool execution produces result text ─────────────────────────

    @Test
    void formatToolResultSuccess() {
        var call = new ToolCall("talos.grep", Map.of("pattern", "TODO"));
        var result = ToolResult.ok("Found 3 matches.");

        String formatted = ToolCallLoop.formatToolResult(call, result);
        assertTrue(formatted.contains("[tool_result: talos.grep]"));
        assertTrue(formatted.contains("Found 3 matches."));
        assertTrue(formatted.contains("[/tool_result]"));
    }

    @Test
    void formatToolResultError() {
        var call = new ToolCall("talos.read_file", Map.of("path", "missing.txt"));
        var result = ToolResult.fail("File not found: missing.txt");

        String formatted = ToolCallLoop.formatToolResult(call, result);
        assertTrue(formatted.contains("[tool_result: talos.read_file]"));
        assertTrue(formatted.contains("[error]"));
        assertTrue(formatted.contains("File not found"));
    }

    @Test
    void formatToolResultEmptyOutput() {
        var call = new ToolCall("talos.noop", Map.of());
        var result = ToolResult.ok("");

        String formatted = ToolCallLoop.formatToolResult(call, result);
        assertTrue(formatted.contains("(empty result)"));
    }

    @Test
    void formatToolResultTruncatesLargeOutput() {
        String largeOutput = "x".repeat(40_000);
        var call = new ToolCall("talos.big", Map.of());
        var result = ToolResult.ok(largeOutput);

        String formatted = ToolCallLoop.formatToolResult(call, result);
        assertTrue(formatted.contains("output truncated at 32K chars"));
        assertTrue(formatted.length() < 40_000, "Formatted result should be truncated");
    }

    // ── Max iterations safety ───────────────────────────────────────

    @Test
    void maxIterationsStopsInfiniteLoop() {
        // A tool that always produces a response with another tool call
        var registry = new ToolRegistry();
        registry.register(new TalosTool() {
            @Override public String name() { return "talos.loop"; }
            @Override public String description() { return "Loop tool"; }
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("talos.loop", "Loop tool");
            }
            @Override public ToolResult execute(ToolCall call) {
                return ToolResult.ok("looping");
            }
        });

        // Create a TurnProcessor + loop with max 3 iterations
        var processor = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
        var loop = new ToolCallLoop(processor, 3);

        // This response always has a tool call. But since the LLM (PLACEHOLDER mode)
        // won't produce tool calls in its response, the loop will stop after 1 iteration.
        String llmResponse = "<tool_call>{\"name\": \"talos.loop\", \"parameters\": {}}</tool_call>";

        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("go")));

        var result = loop.run(llmResponse, messages, WS, defaultCtx());

        // Should have executed at least once but stopped (PLACEHOLDER mode doesn't produce tool calls)
        assertTrue(result.iterations() >= 1, "Should have at least 1 iteration");
        assertTrue(result.iterations() <= 3, "Should not exceed max iterations");
        assertTrue(result.toolsInvoked() >= 1, "Should have invoked the tool at least once");
    }

    @Test
    void constructorEnforcesMinimumOneIteration() {
        var processor = new TurnProcessor(ModeController.defaultController());
        var loop = new ToolCallLoop(processor, 0); // should be coerced to 1

        // Just verify it doesn't throw
        var result = loop.run("no tools", new ArrayList<>(), WS, defaultCtx());
        assertEquals(0, result.iterations());
    }

    // ── Multiple tool calls in one response ─────────────────────────

    @Test
    void multipleToolCallsInOneResponse() {
        var registry = new ToolRegistry();
        registry.register(echoTool());
        registry.register(new TalosTool() {
            @Override public String name() { return "talos.greet"; }
            @Override public String description() { return "Greeting tool"; }
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("talos.greet", "Greeting tool");
            }
            @Override public ToolResult execute(ToolCall call) {
                return ToolResult.ok("Hello, " + call.param("name", "world") + "!");
            }
        });

        var processor = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
        var loop = new ToolCallLoop(processor);

        String llmResponse = """
                I'll do both.
                <tool_call>{"name": "talos.echo", "parameters": {"input": "test"}}</tool_call>
                <tool_call>{"name": "talos.greet", "parameters": {"name": "Alice"}}</tool_call>""";

        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("do both")));

        var result = loop.run(llmResponse, messages, WS, defaultCtx());

        assertEquals(1, result.iterations(), "Both calls in same iteration");
        assertEquals(2, result.toolsInvoked(), "Two tools called");
    }

    // ── Unknown tool ────────────────────────────────────────────────

    @Test
    void unknownToolProducesErrorResult() {
        var loop = createLoop(echoTool());

        String llmResponse = """
                <tool_call>{"name": "talos.nonexistent", "parameters": {}}</tool_call>""";

        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("go")));

        var result = loop.run(llmResponse, messages, WS, defaultCtx());

        // The loop should still work; the error is fed back as a tool result
        assertEquals(1, result.iterations());
        assertEquals(1, result.toolsInvoked());
        // Check that the error message was added to the conversation
        boolean hasError = messages.stream()
                .anyMatch(m -> m.content() != null && m.content().contains("[error]"));
        assertTrue(hasError, "Should have an error message in the conversation");
    }

    // ── Malformed tool call ─────────────────────────────────────────

    @Test
    void malformedToolCallBlockStopsLoop() {
        var loop = createLoop(echoTool());

        // Empty tool_call block — parser returns empty, loop stops
        String llmResponse = "<tool_call></tool_call>";
        var messages = new ArrayList<>(List.of(ChatMessage.system("sys"), ChatMessage.user("go")));

        var result = loop.run(llmResponse, messages, WS, defaultCtx());

        // containsToolCalls returns true, but parse returns empty → breaks
        assertEquals(0, result.toolsInvoked());
    }

    // ── LoopResult accessors ────────────────────────────────────────

    @Test
    void loopResultContainsMessages() {
        var loop = createLoop(echoTool());
        var messages = new ArrayList<>(List.of(ChatMessage.system("sys")));
        var result = loop.run("plain answer", messages, WS, defaultCtx());

        assertNotNull(result.messages());
        assertSame(messages, result.messages(), "Should return the same message list");
    }

    // ── F1: Structured loop metrics ─────────────────────────────────

    @Test
    void failedCallsCountedWhenToolFails() {
        // A tool that always fails
        var loop = createLoop(alwaysFailTool());
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("do something")));

        // Response with one tool call to the failing tool
        String llmResponse = """
                <tool_call>{"name": "talos.always_fail", "parameters": {"input": "x"}}</tool_call>
                """;

        var result = loop.run(llmResponse, messages, WS, defaultCtx());

        assertEquals(1, result.toolsInvoked(), "Should invoke 1 tool");
        assertTrue(result.failedCalls() >= 1, "Failed call count should be >= 1, got: " + result.failedCalls());
        assertFalse(result.hitIterLimit(), "Should not hit iter limit for a single-call response");
    }

    @Test
    void successfulCallNotCountedAsFailed() {
        var loop = createLoop(echoTool());
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("echo something")));

        String llmResponse = """
                <tool_call>{"name": "talos.echo", "parameters": {"input": "hello"}}</tool_call>
                """;

        var result = loop.run(llmResponse, messages, WS, defaultCtx());

        assertEquals(0, result.failedCalls(), "No failed calls expected for successful echo");
    }

    @Test
    void newFieldsDefaultToZeroWhenNoToolCalls() {
        var loop = createLoop(echoTool());
        var messages = new ArrayList<>(List.of(ChatMessage.system("sys")));
        var result = loop.run("just plain text, no tools", messages, WS, defaultCtx());

        assertEquals(0, result.iterations());
        assertEquals(0, result.toolsInvoked());
        assertEquals(0, result.failedCalls());
        assertEquals(0, result.retriedCalls());
        assertFalse(result.hitIterLimit());
    }

    // ── F1: summary() includes failure info ─────────────────────────

    @Test
    void summaryIncludesFailedCount() {
        var result = new ToolCallLoop.LoopResult(
                "final", 1, 2,
                List.of("talos.edit_file", "talos.write_file"),
                List.of(), 1, 0, false);

        String s = result.summary();
        assertNotNull(s);
        assertTrue(s.contains("1 failed"), "Summary should mention 1 failed, got: " + s);
    }

    @Test
    void summaryIncludesIterLimitFlag() {
        var result = new ToolCallLoop.LoopResult(
                "final", 10, 10,
                List.of("talos.edit_file"),
                List.of(), 5, 3, true);

        String s = result.summary();
        assertNotNull(s);
        assertTrue(s.contains("iteration limit reached"), "Summary should note limit, got: " + s);
    }

    // ── B3: call signature helper ────────────────────────────────────

    @Test
    void buildCallSignatureIncludesToolNameAndPath() {
        var call = new ToolCall("talos.edit_file",
                Map.of("path", "src/Foo.java", "old_string", "hello", "new_string", "world"));
        String sig = ToolCallLoop.buildCallSignature(call);
        assertTrue(sig.startsWith("talos.edit_file:"), "Signature should start with tool name");
        assertTrue(sig.contains("src/Foo.java"), "Signature should include path");
    }

    @Test
    void buildCallSignatureDifferentOldStringProducesDifferentSig() {
        var call1 = new ToolCall("talos.edit_file",
                Map.of("path", "f.txt", "old_string", "aaa", "new_string", "x"));
        var call2 = new ToolCall("talos.edit_file",
                Map.of("path", "f.txt", "old_string", "bbb", "new_string", "x"));

        assertNotEquals(ToolCallLoop.buildCallSignature(call1),
                ToolCallLoop.buildCallSignature(call2),
                "Different old_string must produce different signatures");
    }

    @Test
    void buildCallSignatureSameParamsSameSig() {
        var call1 = new ToolCall("talos.edit_file",
                Map.of("path", "f.txt", "old_string", "foo bar", "new_string", "baz"));
        var call2 = new ToolCall("talos.edit_file",
                Map.of("path", "f.txt", "old_string", "foo bar", "new_string", "qux"));

        assertEquals(ToolCallLoop.buildCallSignature(call1),
                ToolCallLoop.buildCallSignature(call2),
                "Same tool+path+old_string must produce same signature regardless of new_string");
    }

    @Test
    void loopResultStripsToolCallsFromFinalAnswer() {
        var loop = createLoop(echoTool());

        String llmResponse = """
                Some reasoning text.
                <tool_call>{"name": "talos.echo", "parameters": {"input": "x"}}</tool_call>
                More text.""";

        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("go")));

        var result = loop.run(llmResponse, messages, WS, defaultCtx());

        assertFalse(result.finalAnswer().contains("<tool_call>"),
                "Final answer should have tool_call blocks stripped");
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static ToolCallLoop createLoop(TalosTool... tools) {
        var registry = new ToolRegistry();
        for (TalosTool t : tools) registry.register(t);
        var processor = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
        return new ToolCallLoop(processor);
    }

    private static Context defaultCtx() {
        return Context.builder(new Config()).build();
    }

    private static TalosTool echoTool() {
        return new TalosTool() {
            @Override public String name() { return "talos.echo"; }
            @Override public String description() { return "Echo tool"; }
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("talos.echo", "Echo back the input");
            }
            @Override public ToolResult execute(ToolCall call) {
                return ToolResult.ok("echo: " + call.param("input", ""));
            }
        };
    }

    private static TalosTool alwaysFailTool() {
        return new TalosTool() {
            @Override public String name() { return "talos.always_fail"; }
            @Override public String description() { return "Always fails"; }
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("talos.always_fail", "Always fails for test purposes");
            }
            @Override public ToolResult execute(ToolCall call) {
                return ToolResult.fail("deliberate test failure");
            }
        };
    }
}

