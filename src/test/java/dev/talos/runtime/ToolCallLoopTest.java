package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
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
            @Override public ToolResult execute(ToolCall call, ToolContext ctx) {
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
            @Override public ToolResult execute(ToolCall call, ToolContext ctx) {
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

    @Test
    void standaloneRawJsonContinuationExecutesNextTool() {
        var registry = new ToolRegistry();
        registry.register(listDirTool());
        registry.register(grepTool());
        var processor = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
        var loop = new ToolCallLoop(processor);

        String initialResponse = """
                {
                  "name": "talos.list_dir",
                  "arguments": {
                    "path": "."
                  }
                }
                """;

        var messages = new ArrayList<>(List.of(ChatMessage.system("sys"), ChatMessage.user("audit workspace")));
        var ctx = Context.builder(new Config())
                .llm(LlmClient.scripted(List.of(
                        """
                        {
                          "name": "talos.grep",
                          "arguments": {
                            "pattern": "cta-button",
                            "include": "*.css"
                          }
                        }
                        """,
                        "Grounded final answer.")))
                .build();

        var result = loop.run(initialResponse, messages, WS, ctx);

        assertEquals(2, result.iterations(), "A standalone raw JSON continuation should be parsed and executed");
        assertEquals(2, result.toolsInvoked());
        assertEquals("Grounded final answer.", result.finalAnswer());
    }

    @Test
    void malformedContinuationAfterToolExecutionUsesTruthfulFallback() {
        var registry = new ToolRegistry();
        registry.register(listDirTool());
        var processor = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
        var loop = new ToolCallLoop(processor);

        String initialResponse = """
                {
                  "name": "talos.list_dir",
                  "arguments": {
                    "path": "."
                  }
                }
                """;

        var messages = new ArrayList<>(List.of(ChatMessage.system("sys"), ChatMessage.user("audit workspace")));
        var ctx = Context.builder(new Config())
                .llm(LlmClient.scripted(List.of(
                        """
                        {
                          "name": "talos.grep",
                          "arguments": {
                        """)))
                .build();

        var result = loop.run(initialResponse, messages, WS, ctx);

        assertEquals(1, result.iterations(), "Malformed continuation should stop after the first executed tool");
        assertEquals(1, result.toolsInvoked());
        assertFalse(result.finalAnswer().contains("talos.grep"));
        assertTrue(result.finalAnswer().contains("No further tool calls were executed."),
                "Should surface a truthful fallback instead of raw tool JSON");
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

        String llmResponse = """
                <tool_call>{"name": "talos.always_fail", "parameters": {"input": "x"}}</tool_call>
                """;

        var result = loop.run(llmResponse, messages, WS, defaultCtx());

        assertEquals(1, result.toolsInvoked(), "Should invoke 1 tool");
        assertEquals(1, result.failedCalls(), "Should count 1 failed call");
        assertFalse(result.hitIterLimit());
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

    // ── Issue 3: short-circuited retries must NOT count as toolsInvoked ──

    @Test
    void shortCircuitedRetryNotCountedInToolsInvoked() {
        // Directly verify: a call that is short-circuited as a duplicate should not
        // appear in toolsInvoked or toolNames. We test this via buildCallSignature
        // and the fact that retriedCalls is tracked separately.
        //
        // The full loop path for this requires 2 LLM re-prompts, which isn't possible
        // without a real model. We verify the metric semantics via the summary() contract.
        var result = new ToolCallLoop.LoopResult(
                "final", 1, 1,          // 1 real invocation
                List.of("talos.edit_file"),
                List.of(), 1, 1, false, 0, List.of(),
                0, 0, 0, 0); // 1 failed + 1 retried, 0 mutation successes; N5 counters irrelevant here

        // toolsInvoked = 1 (only the first, real execution)
        assertEquals(1, result.toolsInvoked());
        // retriedCalls = 1 (the short-circuited duplicate)
        assertEquals(1, result.retriedCalls());
        // Summary reflects failure correctly
        String s = result.summary();
        assertNotNull(s);
        assertTrue(s.contains("1 failed"));
    }

    // ── Issue 2: write_file retries on same path must NOT be short-circuited ──

    @Test
    void distinctWriteFileAttemptsNotConflated() {
        // Two write_file calls to the same path with different content should
        // produce DIFFERENT signatures so neither is incorrectly short-circuited.
        var call1 = new ToolCall("talos.write_file",
                Map.of("path", "output.txt", "content", "version 1"));
        var call2 = new ToolCall("talos.write_file",
                Map.of("path", "output.txt", "content", "version 2"));

        // write_file has no old_string, so the old code would give both hash=0
        // and the same signature. The new code must not use B3 for write_file.
        // We can't call buildCallSignature directly for write_file since the fix
        // bypasses it for non-edit tools, but we can verify via the loop that
        // both calls execute.
        var loop = createLoop(alwaysFailTool()); // will fail, but both should execute
        // Use a registry with a tool that records invocations
        var invocations = new java.util.concurrent.atomic.AtomicInteger();
        var countingWriteTool = new TalosTool() {
            @Override public String name() { return "talos.write_file"; }
            @Override public String description() { return "Counting write tool"; }
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("talos.write_file", "Write file");
            }
            @Override public ToolResult execute(ToolCall call, ToolContext ctx) {
                invocations.incrementAndGet();
                return ToolResult.fail("simulated write failure");
            }
        };

        var registry = new ToolRegistry();
        registry.register(countingWriteTool);
        var processor = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
        var testLoop = new ToolCallLoop(processor);

        // Two write_file calls in one response
        String llmResponse = """
                <tool_call>{"name": "talos.write_file", "parameters": {"path": "output.txt", "content": "v1"}}</tool_call>
                <tool_call>{"name": "talos.write_file", "parameters": {"path": "output.txt", "content": "v2"}}</tool_call>
                """;

        var messages = new ArrayList<>(List.of(ChatMessage.system("sys"), ChatMessage.user("write")));
        testLoop.run(llmResponse, messages, WS, defaultCtx());

        assertEquals(2, invocations.get(),
                "Both write_file calls must execute — duplicate-failure detection must not conflate them");
    }

    // ── Issue 4: failed read_file must not count as prior read ───────

    @Test
    void failedReadFileDoesNotSuppressEditNudge() {
        // If read_file fails, it should not count as a prior successful read.
        // We can verify the B2 nudge behavior via the loop's message trace:
        // if edit_file is called after a failed read_file on the same path,
        // the nudge should still appear.
        //
        // Full integration test requires a real workspace. We verify the
        // semantics via the recorded message content after a loop run
        // where read_file fails (file not found) then edit_file is attempted.
        // This exercises Issue 4 at the integration level.
        var readFailTool = new TalosTool() {
            @Override public String name() { return "talos.read_file"; }
            @Override public String description() { return "Always fails"; }
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("talos.read_file", "Failing read");
            }
            @Override public ToolResult execute(ToolCall call, ToolContext ctx) {
                return ToolResult.fail("File not found: missing.txt");
            }
        };
        var editTool = new TalosTool() {
            @Override public String name() { return "talos.edit_file"; }
            @Override public String description() { return "Edit"; }
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("talos.edit_file", "Edit file");
            }
            @Override public ToolResult execute(ToolCall call, ToolContext ctx) {
                return ToolResult.fail("old_string not found");
            }
        };

        var registry = new ToolRegistry();
        registry.register(readFailTool);
        registry.register(editTool);
        var processor = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
        var testLoop = new ToolCallLoop(processor);

        // read_file fails, then edit_file is called on the same path
        String llmResponse = """
                <tool_call>{"name": "talos.read_file", "parameters": {"path": "missing.txt"}}</tool_call>
                <tool_call>{"name": "talos.edit_file", "parameters": {"path": "missing.txt", "old_string": "foo", "new_string": "bar"}}</tool_call>
                """;

        var messages = new ArrayList<>(List.of(ChatMessage.system("sys"), ChatMessage.user("edit")));
        testLoop.run(llmResponse, messages, WS, defaultCtx());

        // The nudge should appear since the read failed and doesn't count
        boolean nudgePresent = messages.stream()
                .anyMatch(m -> m.content() != null && m.content().contains("did not read this file"));
        assertTrue(nudgePresent,
                "Nudge must appear when read_file failed — a failed read must not suppress the edit nudge");
    }

    // ── F1: summary() includes failure info ─────────────────────────

    @Test
    void summaryIncludesFailedCount() {
        var result = new ToolCallLoop.LoopResult(
                "final", 1, 2,
                List.of("talos.edit_file", "talos.write_file"),
                List.of(), 1, 0, false, 1, List.of(),
                0, 0, 0, 0);

        String s = result.summary();
        assertNotNull(s);
        assertTrue(s.contains("1 failed"), "Summary should mention 1 failed, got: " + s);
    }

    @Test
    void summaryIncludesIterLimitFlag() {
        var result = new ToolCallLoop.LoopResult(
                "final", 10, 10,
                List.of("talos.edit_file"),
                List.of(), 5, 3, true, 0, List.of(),
                0, 0, 0, 0);

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
            @Override public ToolResult execute(ToolCall call, ToolContext ctx) {
                return ToolResult.ok("echo: " + call.param("input", ""));
            }
        };
    }

    private static TalosTool listDirTool() {
        return new TalosTool() {
            @Override public String name() { return "talos.list_dir"; }
            @Override public String description() { return "List dir"; }
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("talos.list_dir", "List files");
            }
            @Override public ToolResult execute(ToolCall call, ToolContext ctx) {
                return ToolResult.ok("index.html\nstyle.css\nscript.js\n");
            }
        };
    }

    private static TalosTool grepTool() {
        return new TalosTool() {
            @Override public String name() { return "talos.grep"; }
            @Override public String description() { return "Search files"; }
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("talos.grep", "Search files");
            }
            @Override public ToolResult execute(ToolCall call, ToolContext ctx) {
                return ToolResult.ok("style.css:12:.cta-button");
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
            @Override public ToolResult execute(ToolCall call, ToolContext ctx) {
                return ToolResult.fail("deliberate test failure");
            }
        };
    }

    // ── Redundancy suppression helper tests ──────────────────────────

    @Test
    void isReadOnlyTool_recognizesReadTools() {
        assertTrue(ToolCallLoop.isReadOnlyTool("talos.read_file"));
        assertTrue(ToolCallLoop.isReadOnlyTool("talos.list_dir"));
        assertTrue(ToolCallLoop.isReadOnlyTool("talos.grep"));
        assertFalse(ToolCallLoop.isReadOnlyTool("talos.write_file"));
        assertFalse(ToolCallLoop.isReadOnlyTool("talos.edit_file"));
    }

    @Test
    void isMutatingTool_recognizesWriteTools() {
        assertTrue(ToolCallLoop.isMutatingTool("talos.write_file"));
        assertTrue(ToolCallLoop.isMutatingTool("talos.edit_file"));
        assertFalse(ToolCallLoop.isMutatingTool("talos.read_file"));
        assertFalse(ToolCallLoop.isMutatingTool("talos.list_dir"));
    }

    @Test
    void buildReadCallSignature_stableForSameParams() {
        var call1 = new ToolCall("talos.read_file", Map.of("path", "index.html"));
        var call2 = new ToolCall("talos.read_file", Map.of("path", "index.html"));
        assertEquals(
                ToolCallLoop.buildReadCallSignature(call1),
                ToolCallLoop.buildReadCallSignature(call2));
    }

    @Test
    void buildReadCallSignature_differentForDifferentParams() {
        var call1 = new ToolCall("talos.read_file", Map.of("path", "a.txt"));
        var call2 = new ToolCall("talos.read_file", Map.of("path", "b.txt"));
        assertNotEquals(
                ToolCallLoop.buildReadCallSignature(call1),
                ToolCallLoop.buildReadCallSignature(call2));
    }

    // ── Path canonicalization for read-only redundancy ────────────────

    @Test
    void canonicalizeReadPath_dotAndDotSlashAreEquivalent() {
        assertEquals(ToolCallLoop.canonicalizeReadPath("."),
                     ToolCallLoop.canonicalizeReadPath("./"));
    }

    @Test
    void canonicalizeReadPath_emptyAndDotAreEquivalent() {
        assertEquals(ToolCallLoop.canonicalizeReadPath(""),
                     ToolCallLoop.canonicalizeReadPath("."));
    }

    @Test
    void canonicalizeReadPath_trailingSlashStripped() {
        assertEquals(ToolCallLoop.canonicalizeReadPath("src"),
                     ToolCallLoop.canonicalizeReadPath("src/"));
    }

    @Test
    void canonicalizeReadPath_backslashNormalized() {
        assertEquals(ToolCallLoop.canonicalizeReadPath("src/main"),
                     ToolCallLoop.canonicalizeReadPath("src\\main"));
    }

    @Test
    void canonicalizeReadPath_dotSlashPrefixStripped() {
        assertEquals(ToolCallLoop.canonicalizeReadPath("index.html"),
                     ToolCallLoop.canonicalizeReadPath("./index.html"));
    }

    @Test
    void buildReadCallSignature_listDirDotAndDotSlashAreEquivalent() {
        // This is the exact transcript failure: list_dir with "." vs "./"
        var callDot = new ToolCall("talos.list_dir", Map.of("path", "."));
        var callDotSlash = new ToolCall("talos.list_dir", Map.of("path", "./"));
        assertEquals(
                ToolCallLoop.buildReadCallSignature(callDot),
                ToolCallLoop.buildReadCallSignature(callDotSlash),
                "list_dir '.' and './' must produce identical signatures");
    }
}

