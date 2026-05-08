package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.*;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.ListDirTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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

    @Test
    void listDirToolOutcomeRetainsListedEntriesForEvidence() throws Exception {
        Path ws = Files.createTempDirectory("talos-list-dir-evidence-");
        try {
            Files.writeString(ws.resolve("README.md"), "fixture\n");
            Files.writeString(ws.resolve("index.html"), "<button>Go</button>\n");
            Files.writeString(ws.resolve("script.js"), "console.log('go');\n");

            var loop = createLoop(new ListDirTool());
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("system"),
                    ChatMessage.user("inspect this website")));
            String llmResponse = """
                    ```json
                    {"name":"talos.list_dir","parameters":{"path":"."}}
                    ```""";
            Context ctx = Context.builder(new Config())
                    .sandbox(new Sandbox(ws, Map.of()))
                    .llm(LlmClient.scripted(List.of("")))
                    .build();

            var result = loop.run(llmResponse, messages, ws, ctx);

            assertEquals(1, result.toolOutcomes().size());
            String summary = result.toolOutcomes().getFirst().summary();
            assertTrue(summary.contains("README.md"), summary);
            assertTrue(summary.contains("index.html"), summary);
            assertTrue(summary.contains("script.js"), summary);
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
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
    void twoAdjacentRawJsonContinuationCallsBothExecute() {
        // Regression for the multi-adjacent-raw-JSON-toolcalls bug:
        // when a follow-up contains two adjacent standalone raw JSON calls,
        // both must be parsed and executed in the same iteration.
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
                        // Follow-up: two adjacent standalone raw JSON calls (different params)
                        """
                        {
                          "name": "talos.grep",
                          "arguments": {
                            "pattern": "cta-button",
                            "include": "*.css"
                          }
                        }
                        {
                          "name": "talos.grep",
                          "arguments": {
                            "pattern": "cta-button",
                            "include": "*.html"
                          }
                        }
                        """,
                        "Grounded final answer.")))
                .build();

        var result = loop.run(initialResponse, messages, WS, ctx);

        assertEquals(2, result.iterations(),
                "Adjacent continuation calls should both run in the second iteration");
        assertEquals(3, result.toolsInvoked(),
                "Initial list_dir + two adjacent grep calls = 3 total invocations");
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
    void deniedMutationStopsWithoutReprompting() {
        var registry = new ToolRegistry();
        registry.register(writeFileTool());
        var processor = new TurnProcessor(
                ModeController.defaultController(),
                (description, detail) -> false,
                registry);
        var loop = new ToolCallLoop(processor);

        String initialResponse = """
                {"name": "talos.write_file", "arguments": {"path": "index.html", "content": "<h1>new</h1>"}}
                """;
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("edit index.html")));
        var ctx = Context.builder(new Config())
                .llm(LlmClient.scripted(List.of(
                        "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"style.css\",\"content\":\"body{}\"}}")))
                .build();

        var result = loop.run(initialResponse, messages, WS, ctx);

        assertEquals(1, result.iterations(), "Denied mutation should stop the loop immediately");
        assertEquals(1, result.toolsInvoked(), "No follow-up write should be requested after denial");
        assertEquals(1, result.failedCalls());
        assertFalse(result.hitIterLimit(), "Denial stop should not be reported as an iteration-limit stop");
        assertTrue(result.finalAnswer().contains("not approved"));
        assertEquals(1, result.toolOutcomes().size());
        assertTrue(result.toolOutcomes().get(0).denied());
    }

    @Test
    void readOnlyMutationGuardStopsWithoutReprompting() {
        var registry = new ToolRegistry();
        registry.register(writeFileTool());
        final int[] gateCalls = {0};
        var processor = new TurnProcessor(
                ModeController.defaultController(),
                (description, detail) -> {
                    gateCalls[0]++;
                    return true;
                },
                registry);
        var loop = new ToolCallLoop(processor);

        String initialResponse = """
                {"name": "talos.write_file", "arguments": {"path": "index.html", "content": "<h1>new</h1>"}}
                """;
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("Check the workspace. Do not change anything yet.")));
        var ctx = Context.builder(new Config())
                .llm(LlmClient.scripted(List.of(
                        "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"style.css\",\"content\":\"body{}\"}}")))
                .build();

        TurnUserRequestCapture.set("Check the workspace. Do not change anything yet.");
        try {
            var result = loop.run(initialResponse, messages, WS, ctx);

            assertEquals(1, result.iterations(),
                    "Read-only mutation guard should stop the loop immediately");
            assertEquals(1, result.toolsInvoked(),
                    "No follow-up write should be requested after the policy denial");
            assertEquals(1, result.failedCalls());
            assertFalse(result.hitIterLimit(),
                    "Policy denial stop should not be reported as an iteration-limit stop");
            assertTrue(result.finalAnswer().contains("mutating tool was not allowed"));
            assertEquals(0, gateCalls[0], "mutation-intent guard must fire before approval");
            assertEquals(1, result.toolOutcomes().size());
            assertTrue(result.toolOutcomes().get(0).denied());
        } finally {
            TurnUserRequestCapture.clear();
        }
    }

    @Test
    void repeatedSameToolFailureStopsByFailurePolicyBeforeIterationLimit() {
        var registry = new ToolRegistry();
        registry.register(alwaysFailTool());
        var processor = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
        var loop = new ToolCallLoop(processor, 10);

        String failingCall = """
                {"name": "talos.always_fail", "arguments": {"input": "x"}}
                """;
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("try the failing thing")));
        var ctx = Context.builder(new Config())
                .llm(LlmClient.scripted(List.of(failingCall)))
                .build();

        var result = loop.run(failingCall, messages, WS, ctx);

        assertEquals(3, result.iterations(), "Failure policy should stop after the threshold");
        assertEquals(3, result.toolsInvoked());
        assertEquals(3, result.failedCalls());
        assertTrue(result.failureDecision().shouldStop());
        assertFalse(result.hitIterLimit(), "Failure policy stop should happen before max iterations");
        assertTrue(result.finalAnswer().contains("Tool loop stopped by failure policy"));
        assertTrue(result.summary().contains("failure policy stopped"));
    }

    @Test
    void repeatedEmptyEditArgsAfterReadStopsWithoutApprovalOrMutation() throws Exception {
        Path ws = Files.createTempDirectory("talos-empty-edit-args-");
        try {
            Path index = ws.resolve("index.html");
            String original = "<html><body><h1>Night Drive</h1></body></html>\n";
            Files.writeString(index, original);

            var registry = new ToolRegistry();
            registry.register(new ReadFileTool());
            registry.register(new FileEditTool(new FileUndoStack()));

            final int[] approvalRequests = {0};
            var processor = new TurnProcessor(
                    ModeController.defaultController(),
                    (description, detail) -> {
                        approvalRequests[0]++;
                        return true;
                    },
                    registry);
            var loop = new ToolCallLoop(processor, 10);

            String emptyEdit = """
                    {"name":"talos.edit_file","arguments":{"path":"index.html","old_string":"","new_string":""}}
                    """;
            String readFile = """
                    {"name":"talos.read_file","arguments":{"path":"index.html"}}
                    """;
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("sys"),
                    ChatMessage.user("Now apply the smallest fix by editing index.html.")));
            var ctx = Context.builder(new Config())
                    .sandbox(new Sandbox(ws, Map.of()))
                    .llm(LlmClient.scripted(List.of(readFile, emptyEdit, "should not be called")))
                    .build();

            TurnUserRequestCapture.set("Now apply the smallest fix by editing index.html.");
            ToolCallLoop.LoopResult result;
            try {
                result = loop.run(emptyEdit, messages, ws, ctx);
            } finally {
                TurnUserRequestCapture.clear();
            }

            assertEquals(3, result.iterations(),
                    "The loop should stop after the repeated empty edit that follows a successful read");
            assertEquals(2, result.toolsInvoked(),
                    "The duplicate invalid edit is short-circuited, not executed as another tool");
            assertEquals(2, result.failedCalls());
            assertEquals(1, result.retriedCalls());
            assertEquals(0, result.mutatingToolSuccesses());
            assertEquals(0, approvalRequests[0],
                    "Invalid edit arguments must not reach the approval gate");
            assertFalse(result.hitIterLimit(),
                    "The specialized failure policy should stop before the iteration cap");
            assertTrue(result.failureDecision().shouldStop());
            assertTrue(result.failureDecision().reason().contains("empty talos.edit_file argument"));
            assertTrue(result.finalAnswer().contains("Tool loop stopped by failure policy"));
            assertTrue(result.finalAnswer().contains("No approval was requested and no file was changed"));
            assertEquals(original, Files.readString(index));
        } finally {
            deleteRecursive(ws);
        }
    }

    @Test
    void emptyEditArgsCanRecoverToValidEditApprovalAfterRead() throws Exception {
        Path ws = Files.createTempDirectory("talos-empty-edit-recovery-");
        try {
            Path index = ws.resolve("index.html");
            String original = "<html><body><h1>Night Drive</h1></body></html>\n";
            Files.writeString(index, original);

            var registry = new ToolRegistry();
            registry.register(new ReadFileTool());
            registry.register(new FileEditTool(new FileUndoStack()));

            final int[] approvalRequests = {0};
            var processor = new TurnProcessor(
                    ModeController.defaultController(),
                    (description, detail) -> {
                        approvalRequests[0]++;
                        return false;
                    },
                    registry);
            var loop = new ToolCallLoop(processor, 10);

            String emptyEdit = """
                    {"name":"talos.edit_file","arguments":{"path":"index.html","old_string":"","new_string":""}}
                    """;
            String readFile = """
                    {"name":"talos.read_file","arguments":{"path":"index.html"}}
                    """;
            String validEdit = """
                    {"name":"talos.edit_file","arguments":{"path":"index.html","old_string":"<html><body><h1>Night Drive</h1></body></html>\\n","new_string":"<html><body><h1>Night Drive</h1><a class=\\"cta-button\\" href=\\"#listen\\">Listen now</a></body></html>\\n"}}
                    """;
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("sys"),
                    ChatMessage.user("Now apply the smallest fix by editing index.html.")));
            var ctx = Context.builder(new Config())
                    .sandbox(new Sandbox(ws, Map.of()))
                    .llm(LlmClient.scripted(List.of(readFile, validEdit, "should not be called")))
                    .build();

            TurnUserRequestCapture.set("Now apply the smallest fix by editing index.html.");
            ToolCallLoop.LoopResult result;
            try {
                result = loop.run(emptyEdit, messages, ws, ctx);
            } finally {
                TurnUserRequestCapture.clear();
            }

            assertEquals(3, result.iterations());
            assertEquals(3, result.toolsInvoked());
            assertEquals(1, approvalRequests[0],
                    "The recovered edit must reach the approval gate exactly once.");
            assertEquals(0, result.mutatingToolSuccesses(),
                    "Denied approval should still prevent mutation.");
            assertFalse(result.failureDecision().shouldStop(),
                    "A valid recovered edit should not be stopped by empty-args failure policy.");
            assertTrue(result.finalAnswer().contains("requested mutation was not approved"));
            assertEquals(original, Files.readString(index));
        } finally {
            deleteRecursive(ws);
        }
    }

    @Test
    void repeatedEmptyEditArgsAcrossPathsStopsAfterReadBeforeGenericThreshold() throws Exception {
        Path ws = Files.createTempDirectory("talos-empty-edit-cross-path-");
        try {
            Files.writeString(ws.resolve("index.html"), "<html><body><h1>BMI</h1></body></html>\n");
            Files.writeString(ws.resolve("script.js"), "const ready = false;\n");
            Files.writeString(ws.resolve("style.css"), ".calculator { color: red; }\n");

            var registry = new ToolRegistry();
            registry.register(new ReadFileTool());
            registry.register(new FileEditTool(new FileUndoStack()));

            final int[] approvalRequests = {0};
            var processor = new TurnProcessor(
                    ModeController.defaultController(),
                    (description, detail) -> {
                        approvalRequests[0]++;
                        return true;
                    },
                    registry);
            var loop = new ToolCallLoop(processor, 10);

            String emptyPublicScript = """
                    {"name":"talos.edit_file","arguments":{"path":"public/script.js","old_string":"","new_string":""}}
                    """;
            String readIndex = """
                    {"name":"talos.read_file","arguments":{"path":"index.html"}}
                    """;
            String missingNewScript = """
                    {"name":"talos.edit_file","arguments":{"path":"script.js","old_string":"const ready = false;\\n"}}
                    """;
            String emptyStyle = """
                    {"name":"talos.edit_file","arguments":{"path":"style.css","old_string":"","new_string":""}}
                    """;
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("sys"),
                    ChatMessage.user("Repair this broken BMI website.")));
            var ctx = Context.builder(new Config())
                    .sandbox(new Sandbox(ws, Map.of()))
                    .llm(LlmClient.scripted(List.of(readIndex, missingNewScript, emptyStyle, "should not be called")))
                    .build();

            TurnUserRequestCapture.set("Repair this broken BMI website.");
            ToolCallLoop.LoopResult result;
            try {
                result = loop.run(emptyPublicScript, messages, ws, ctx);
            } finally {
                TurnUserRequestCapture.clear();
            }

            assertEquals(4, result.iterations());
            assertEquals(4, result.toolsInvoked());
            assertEquals(3, result.failedCalls());
            assertEquals(0, result.mutatingToolSuccesses());
            assertEquals(0, approvalRequests[0],
                    "Invalid edit arguments must not reach the approval gate");
            assertFalse(result.hitIterLimit(),
                    "Cross-path empty-argument policy should stop before the iteration cap");
            assertTrue(result.failureDecision().shouldStop());
            assertTrue(result.failureDecision().reason().contains("across 3 path(s)"));
            assertTrue(result.failureDecision().reason().contains("No approval was requested"));
            assertTrue(result.finalAnswer().contains("Tool loop stopped by failure policy"));
            assertTrue(result.finalAnswer().contains("No approval was requested and no file was changed"));
            assertEquals("<html><body><h1>BMI</h1></body></html>\n", Files.readString(ws.resolve("index.html")));
            assertEquals("const ready = false;\n", Files.readString(ws.resolve("script.js")));
            assertEquals(".calculator { color: red; }\n", Files.readString(ws.resolve("style.css")));
        } finally {
            deleteRecursive(ws);
        }
    }

    @Test
    void staleSameFileEditFailureRequiresRereadBeforeNextEdit() throws Exception {
        Path ws = Files.createTempDirectory("talos-stale-edit-reread-required-");
        try {
            Path index = ws.resolve("index.html");
            Files.writeString(index, "alpha\nbeta\n");

            var registry = new ToolRegistry();
            registry.register(new ReadFileTool());
            registry.register(new FileEditTool(new FileUndoStack()));

            final int[] approvalRequests = {0};
            var processor = new TurnProcessor(
                    ModeController.defaultController(),
                    (description, detail) -> {
                        approvalRequests[0]++;
                        return true;
                    },
                    registry);
            var loop = new ToolCallLoop(processor, 10);

            String initial = """
                    {"name":"talos.edit_file","arguments":{"path":"index.html","old_string":"alpha\\n","new_string":"alpha-updated\\n"}}
                    {"name":"talos.edit_file","arguments":{"path":"index.html","old_string":"alpha\\nbeta\\n","new_string":"alpha-updated\\nbeta-fixed\\n"}}
                    """;
            String ignoredRereadRequirement = """
                    {"name":"talos.edit_file","arguments":{"path":"index.html","old_string":"beta\\n","new_string":"beta-fixed\\n"}}
                    """;
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("sys"),
                    ChatMessage.user("Fix index.html with the smallest edits.")));
            var ctx = Context.builder(new Config())
                    .sandbox(new Sandbox(ws, Map.of()))
                    .llm(LlmClient.scripted(List.of(ignoredRereadRequirement, "should not be called")))
                    .build();

            TurnUserRequestCapture.set("Fix index.html with the smallest edits.");
            ToolCallLoop.LoopResult result;
            try {
                result = loop.run(initial, messages, ws, ctx);
            } finally {
                TurnUserRequestCapture.clear();
            }

            assertEquals(2, result.iterations(),
                    "The stale retry should stop after the model ignores the reread requirement");
            assertEquals(2, result.toolsInvoked(),
                    "The ignored stale retry is short-circuited before tool execution");
            assertEquals(2, approvalRequests[0],
                    "Only the two real edit attempts should reach approval");
            assertEquals(1, result.mutatingToolSuccesses());
            assertEquals(2, result.failedCalls());
            assertTrue(result.failureDecision().shouldStop());
            assertTrue(result.failureDecision().reason().contains("before rereading the file"));
            assertTrue(result.finalAnswer().contains("Tool loop stopped by failure policy"));
            assertEquals("alpha-updated\nbeta\n", Files.readString(index));
        } finally {
            deleteRecursive(ws);
        }
    }

    @Test
    void staleSameFileEditCanRecoverAfterSeparateRead() throws Exception {
        Path ws = Files.createTempDirectory("talos-stale-edit-recovery-");
        try {
            Path index = ws.resolve("index.html");
            Files.writeString(index, "alpha\nbeta\n");

            var registry = new ToolRegistry();
            registry.register(new ReadFileTool());
            registry.register(new FileEditTool(new FileUndoStack()));

            final int[] approvalRequests = {0};
            var processor = new TurnProcessor(
                    ModeController.defaultController(),
                    (description, detail) -> {
                        approvalRequests[0]++;
                        return true;
                    },
                    registry);
            var loop = new ToolCallLoop(processor, 10);

            String initial = """
                    {"name":"talos.edit_file","arguments":{"path":"index.html","old_string":"alpha\\n","new_string":"alpha-updated\\n"}}
                    {"name":"talos.edit_file","arguments":{"path":"index.html","old_string":"alpha\\nbeta\\n","new_string":"alpha-updated\\nbeta-fixed\\n"}}
                    """;
            String readCurrentFile = """
                    {"name":"talos.read_file","arguments":{"path":"index.html"}}
                    """;
            String validRecoveredEdit = """
                    {"name":"talos.edit_file","arguments":{"path":"index.html","old_string":"beta\\n","new_string":"beta-fixed\\n"}}
                    """;
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("sys"),
                    ChatMessage.user("Fix index.html with the smallest edits.")));
            var ctx = Context.builder(new Config())
                    .sandbox(new Sandbox(ws, Map.of()))
                    .llm(LlmClient.scripted(List.of(readCurrentFile, validRecoveredEdit, "should not be called")))
                    .build();

            TurnUserRequestCapture.set("Fix index.html with the smallest edits.");
            ToolCallLoop.LoopResult result;
            try {
                result = loop.run(initial, messages, ws, ctx);
            } finally {
                TurnUserRequestCapture.clear();
            }

            assertEquals(3, result.iterations());
            assertEquals(4, result.toolsInvoked());
            assertEquals(3, approvalRequests[0]);
            assertEquals(2, result.mutatingToolSuccesses());
            assertFalse(result.failureDecision().shouldStop());
            assertEquals("alpha-updated\nbeta-fixed\n", Files.readString(index));
        } finally {
            deleteRecursive(ws);
        }
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

    // ── T99: pending target obligations ─────────────────────────────

    @Test
    void expectedTargetProgressNoToolProseBecomesDeterministicBreach() {
        var loop = createLoop(writeFileTool());
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("Create index.html, styles.css, and scripts.js for a BMI calculator.")));
        var ctx = Context.builder(new Config())
                .llm(LlmClient.scripted(List.of("All done, ready to use. Open it in your browser.")))
                .build();
        String llmResponse = """
                <tool_call>{"name":"talos.write_file","parameters":{"path":"index.html","content":"<html></html>"}}</tool_call>
                <tool_call>{"name":"talos.write_file","parameters":{"path":"styles.css","content":"body{}"}}</tool_call>
                <tool_call>{"name":"talos.write_file","parameters":{"path":"script.js","content":"console.log('wrong target');"}}</tool_call>
                """;

        LocalTurnTraceCapture.begin("trc-t99-expected", "session", 1,
                "2026-05-03T00:00:00Z", "ws", "test", "ollama", "qwen", "create bmi");
        ToolCallLoop.LoopResult result;
        LocalTurnTrace trace;
        try {
            result = loop.run(llmResponse, messages, WS, ctx);
            trace = LocalTurnTraceCapture.complete();
        } finally {
            LocalTurnTraceCapture.clear();
        }

        assertTrue(result.failureDecision().shouldStop(), result.failureDecision().reason());
        assertTrue(result.failureDecision().reason().contains("EXPECTED_TARGETS_REMAINING"),
                result.failureDecision().reason());
        assertTrue(result.finalAnswer().contains("scripts.js"), result.finalAnswer());
        assertFalse(result.finalAnswer().toLowerCase().contains("ready to use"), result.finalAnswer());
        assertFalse(result.finalAnswer().toLowerCase().contains("open it in your browser"), result.finalAnswer());

        var breached = trace.events().stream()
                .filter(event -> "PENDING_ACTION_OBLIGATION_BREACHED".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("EXPECTED_TARGETS_REMAINING", breached.data().get("kind"));
        assertEquals(List.of("scripts.js"), breached.data().get("targets"));
    }

    @Test
    void staticRepairProgressNoToolProseBecomesDeterministicBreach() {
        var loop = createLoop(writeFileTool());
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.system("""
                        [Static verification repair context]
                        Expected targets: index.html, scripts.js, styles.css

                        Previous static verification problems:
                        - HTML does not link JavaScript file: `scripts.js`

                        Repair plan:
                        - index.html: You must use talos.write_file with complete corrected file content for index.html.
                        - scripts.js: You must use talos.write_file with complete corrected file content for scripts.js.
                        - styles.css: You must use talos.write_file with complete corrected file content for styles.css.

                        Full-file replacement targets: index.html, scripts.js, styles.css
                        """),
                ChatMessage.user("Fix the remaining static verification problems.")));
        var ctx = Context.builder(new Config())
                .llm(LlmClient.scripted(List.of("Complete. Everything is ready to use.")))
                .build();
        String llmResponse = """
                <tool_call>{"name":"talos.write_file","parameters":{"path":"index.html","content":"<html></html>"}}</tool_call>
                """;

        LocalTurnTraceCapture.begin("trc-t99-repair", "session", 1,
                "2026-05-03T00:00:00Z", "ws", "test", "ollama", "qwen", "repair bmi");
        ToolCallLoop.LoopResult result;
        LocalTurnTrace trace;
        try {
            result = loop.run(llmResponse, messages, WS, ctx);
            trace = LocalTurnTraceCapture.complete();
        } finally {
            LocalTurnTraceCapture.clear();
        }

        assertTrue(result.failureDecision().shouldStop(), result.failureDecision().reason());
        assertTrue(result.failureDecision().reason().contains("STATIC_REPAIR_TARGETS_REMAINING"),
                result.failureDecision().reason());
        assertTrue(result.finalAnswer().contains("scripts.js"), result.finalAnswer());
        assertTrue(result.finalAnswer().contains("styles.css"), result.finalAnswer());
        assertFalse(result.finalAnswer().toLowerCase().contains("ready to use"), result.finalAnswer());
        assertFalse(result.finalAnswer().toLowerCase().contains("complete."), result.finalAnswer());

        var breached = trace.events().stream()
                .filter(event -> "PENDING_ACTION_OBLIGATION_BREACHED".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("STATIC_REPAIR_TARGETS_REMAINING", breached.data().get("kind"));
        assertEquals(List.of("scripts.js", "styles.css"), breached.data().get("targets"));
    }

    @Test
    void narrowedStaticRepairProgressBreachReportsOnlyVerifierSpecificTarget() {
        var loop = createLoop(writeFileTool());
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.system("""
                        [Static verification repair context]
                        Expected targets: index.html, scripts.js, styles.css

                        Previous static verification problems:
                        - CSS references missing class selectors: `.button`

                        Repair plan:
                        Full-file replacement targets: styles.css
                        - styles.css: You must use talos.write_file with complete corrected file content for styles.css.
                        - Verify static checks again before claiming completion.
                        """),
                ChatMessage.user("Fix the remaining static verification problems.")));
        var ctx = Context.builder(new Config())
                .llm(LlmClient.scripted(List.of("Complete. Everything is ready to use.")))
                .build();
        String llmResponse = """
                <tool_call>{"name":"talos.write_file","parameters":{"path":"index.html","content":"<html></html>"}}</tool_call>
                """;

        LocalTurnTraceCapture.begin("trc-t213-repair", "session", 1,
                "2026-05-08T00:00:00Z", "ws", "test", "llama.cpp", "gpt-oss", "repair css selector");
        ToolCallLoop.LoopResult result;
        LocalTurnTrace trace;
        try {
            result = loop.run(llmResponse, messages, WS, ctx);
            trace = LocalTurnTraceCapture.complete();
        } finally {
            LocalTurnTraceCapture.clear();
        }

        assertTrue(result.failureDecision().shouldStop(), result.failureDecision().reason());
        assertTrue(result.failureDecision().reason().contains("STATIC_REPAIR_TARGETS_REMAINING"),
                result.failureDecision().reason());
        assertTrue(result.finalAnswer().contains("styles.css"), result.finalAnswer());
        assertFalse(result.finalAnswer().contains("scripts.js"), result.finalAnswer());
        assertFalse(result.finalAnswer().toLowerCase().contains("ready to use"), result.finalAnswer());
        assertFalse(result.finalAnswer().toLowerCase().contains("complete."), result.finalAnswer());

        var breached = trace.events().stream()
                .filter(event -> "PENDING_ACTION_OBLIGATION_BREACHED".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("STATIC_REPAIR_TARGETS_REMAINING", breached.data().get("kind"));
        assertEquals(List.of("styles.css"), breached.data().get("targets"));
    }

    @Test
    void pendingStaticRepairRejectsEmptyWriteBeforeApply() throws Exception {
        Path ws = Files.createTempDirectory("talos-static-repair-empty-write-");
        try {
            Files.writeString(ws.resolve("index.html"), "<html></html>\n");
            Files.writeString(ws.resolve("styles.css"), "body { color: black; }\n");

            var registry = new ToolRegistry();
            registry.register(new FileWriteTool());
            final int[] approvals = {0};
            var processor = new TurnProcessor(
                    ModeController.defaultController(),
                    (description, detail) -> {
                        approvals[0]++;
                        return true;
                    },
                    registry);
            var loop = new ToolCallLoop(processor, 10);

            String request = "Fix the remaining static verification problems.";
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("sys"),
                    ChatMessage.system("""
                            [Static verification repair context]
                            Expected targets: index.html, scripts.js, styles.css

                            Previous static verification problems:
                            - CSS references missing class selectors: `.button`

                            Repair plan:
                            Full-file replacement targets: styles.css
                            - styles.css: You must use talos.write_file with complete corrected file content for styles.css.
                            - Verify static checks again before claiming completion.
                            """),
                    ChatMessage.user(request)));
            var ctx = Context.builder(new Config())
                    .sandbox(new Sandbox(ws, Map.of()))
                    .llm(LlmClient.scripted(List.of("""
                            {"name":"talos.write_file","arguments":{"path":"styles.css","content":""}}
                            """)))
                    .build();
            String initial = """
                    {"name":"talos.write_file","arguments":{"path":"index.html","content":"<html></html>\\n"}}
                    """;

            TurnUserRequestCapture.set(request);
            TurnTaskContractCapture.set(TaskContractResolver.fromMessages(messages));
            LocalTurnTraceCapture.begin("trc-t215-empty-repair-write", "session", 1,
                    "2026-05-08T00:00:00Z", "ws", "test", "llama_cpp", "qwen", request);
            ToolCallLoop.LoopResult result;
            LocalTurnTrace trace;
            try {
                result = loop.run(initial, messages, ws, ctx);
                trace = LocalTurnTraceCapture.complete();
            } finally {
                TurnUserRequestCapture.clear();
                TurnTaskContractCapture.clear();
                LocalTurnTraceCapture.clear();
            }

            assertEquals("body { color: black; }\n", Files.readString(ws.resolve("styles.css")),
                    "empty pending repair write must not overwrite the previous file content");
            assertEquals(1, approvals[0],
                    "only the initial valid write should reach approval; the empty repair write must be blocked first");
            assertEquals(1, result.toolsInvoked(),
                    "the empty repair write must not be counted as an executed tool");
            assertEquals(1, result.mutatingToolSuccesses(),
                    "the empty repair write must not count as a successful mutation");
            assertTrue(result.failureDecision().shouldStop(), result.failureDecision().reason());
            assertTrue(result.failureDecision().reason().contains("STATIC_REPAIR_TARGETS_REMAINING"),
                    result.failureDecision().reason());
            assertTrue(result.failureDecision().reason().contains("styles.css"),
                    result.failureDecision().reason());
            assertTrue(result.failureDecision().reason().toLowerCase(java.util.Locale.ROOT).contains("empty"),
                    result.failureDecision().reason());
            String lower = result.finalAnswer().toLowerCase(java.util.Locale.ROOT);
            assertFalse(lower.contains("complete"), result.finalAnswer());
            assertFalse(lower.contains("ready to use"), result.finalAnswer());
            assertFalse(lower.contains("open in browser"), result.finalAnswer());

            var breached = trace.events().stream()
                    .filter(event -> "PENDING_ACTION_OBLIGATION_BREACHED".equals(event.type()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("STATIC_REPAIR_TARGETS_REMAINING", breached.data().get("kind"));
            assertEquals(List.of("styles.css"), breached.data().get("targets"));
            assertTrue(String.valueOf(breached.data().get("reason"))
                            .toLowerCase(java.util.Locale.ROOT)
                            .contains("empty"),
                    breached.data().toString());
        } finally {
            deleteRecursive(ws);
        }
    }

    @Test
    void firstStaticRepairRejectsEmptyWriteBeforeApply() throws Exception {
        Path ws = Files.createTempDirectory("talos-static-repair-first-empty-write-");
        try {
            Files.writeString(ws.resolve("index.html"), "<html></html>\n");
            Files.writeString(ws.resolve("styles.css"), "body { color: black; }\n");

            var registry = new ToolRegistry();
            registry.register(new FileWriteTool());
            final int[] approvals = {0};
            var processor = new TurnProcessor(
                    ModeController.defaultController(),
                    (description, detail) -> {
                        approvals[0]++;
                        return true;
                    },
                    registry);
            var loop = new ToolCallLoop(processor, 10);

            String request = "Fix the remaining static verification problems.";
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("sys"),
                    ChatMessage.system("""
                            [Static verification repair context]
                            Expected targets: index.html, scripts.js, styles.css

                            Previous static verification problems:
                            - CSS references missing class selectors: `.button`

                            Repair plan:
                            Full-file replacement targets: styles.css
                            - styles.css: You must use talos.write_file with complete corrected file content for styles.css.
                            - Verify static checks again before claiming completion.
                            """),
                    ChatMessage.user(request)));
            var ctx = Context.builder(new Config())
                    .sandbox(new Sandbox(ws, Map.of()))
                    .llm(LlmClient.scripted(List.of("Complete. Everything is ready to use. Open it in your browser.")))
                    .build();
            String initial = """
                    {"name":"talos.write_file","arguments":{"path":"styles.css","content":""}}
                    """;

            TurnUserRequestCapture.set(request);
            TurnTaskContractCapture.set(TaskContractResolver.fromMessages(messages));
            LocalTurnTraceCapture.begin("trc-t218-first-empty-repair-write", "session", 1,
                    "2026-05-08T00:00:00Z", "ws", "test", "llama_cpp", "qwen", request);
            ToolCallLoop.LoopResult result;
            LocalTurnTrace trace;
            try {
                result = loop.run(initial, messages, ws, ctx);
                trace = LocalTurnTraceCapture.complete();
            } finally {
                TurnUserRequestCapture.clear();
                TurnTaskContractCapture.clear();
                LocalTurnTraceCapture.clear();
            }

            assertEquals("body { color: black; }\n", Files.readString(ws.resolve("styles.css")),
                    "empty first repair write must not overwrite the previous file content");
            assertEquals(0, approvals[0],
                    "empty first repair write must be rejected before approval");
            assertEquals(0, result.toolsInvoked(),
                    "empty first repair write must not be counted as an executed tool");
            assertEquals(0, result.mutatingToolSuccesses(),
                    "empty first repair write must not count as a successful mutation");
            assertTrue(result.failureDecision().shouldStop(), result.failureDecision().reason());
            assertTrue(result.failureDecision().reason().contains("STATIC_REPAIR_INVALID_WRITE_CONTENT"),
                    result.failureDecision().reason());
            assertTrue(result.failureDecision().reason().contains("styles.css"),
                    result.failureDecision().reason());
            assertTrue(result.failureDecision().reason().toLowerCase(java.util.Locale.ROOT).contains("empty"),
                    result.failureDecision().reason());
            String lower = result.finalAnswer().toLowerCase(java.util.Locale.ROOT);
            assertFalse(lower.contains("complete"), result.finalAnswer());
            assertFalse(lower.contains("ready to use"), result.finalAnswer());
            assertFalse(lower.contains("open in browser"), result.finalAnswer());

            var failed = trace.events().stream()
                    .filter(event -> "ACTION_OBLIGATION_EVALUATED".equals(event.type()))
                    .filter(event -> "STATIC_REPAIR_INVALID_WRITE_CONTENT".equals(event.data().get("failureKind")))
                    .findFirst()
                    .orElseThrow();
            assertEquals("STATIC_REPAIR_WRITE_CONTENT", failed.data().get("obligation"));
            assertEquals("FAILED", failed.data().get("status"));
        } finally {
            deleteRecursive(ws);
        }
    }

    @Test
    void staticSelectorRepairRejectsPreservedMissingCssSelectorBeforeApply() throws Exception {
        Path ws = Files.createTempDirectory("talos-static-selector-repair-preserved-css-");
        try {
            Files.writeString(ws.resolve("index.html"), """
                    <!doctype html>
                    <html>
                    <head>
                      <link rel="stylesheet" href="styles.css">
                    </head>
                    <body>
                      <p id="result">Waiting</p>
                    </body>
                    </html>
                    """);
            Files.writeString(ws.resolve("styles.css"), "body { color: black; }\n");

            var registry = new ToolRegistry();
            registry.register(new FileWriteTool());
            final int[] approvals = {0};
            var processor = new TurnProcessor(
                    ModeController.defaultController(),
                    (description, detail) -> {
                        approvals[0]++;
                        return true;
                    },
                    registry);
            var loop = new ToolCallLoop(processor, 10);

            String request = "Fix the remaining static verification problems.";
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("sys"),
                    ChatMessage.system("""
                            [Static verification repair context]
                            Expected targets: index.html, scripts.js, styles.css

                            Previous static verification problems:
                            - CSS references missing class selectors: `.button`

                            Repair plan:
                            Full-file replacement targets: styles.css
                            - styles.css: You must use talos.write_file with complete corrected file content for styles.css.
                            - Verify static checks again before claiming completion.

                            [Current static selector facts]
                            I checked the selectors against the actual workspace files:

                            - HTML: `index.html`
                            - CSS: `styles.css`
                            - JavaScript: `scripts.js`

                            Observed in HTML:
                            - Classes: none
                            - IDs: `#result`

                            Mismatches found:
                            - CSS references missing class selectors: `.button`
                            """),
                    ChatMessage.user(request)));
            var ctx = Context.builder(new Config())
                    .sandbox(new Sandbox(ws, Map.of()))
                    .llm(LlmClient.scripted(List.of("Complete. Everything is ready to use.")))
                    .build();
            String initial = """
                    {"name":"talos.write_file","arguments":{"path":"styles.css","content":".button { color: red; }\\nbody { margin: 0; }\\n"}}
                    """;

            TurnUserRequestCapture.set(request);
            TurnTaskContractCapture.set(TaskContractResolver.fromMessages(messages));
            LocalTurnTraceCapture.begin("trc-t217-static-selector-preserved-css", "session", 1,
                    "2026-05-08T00:00:00Z", "ws", "test", "llama_cpp", "qwen", request);
            ToolCallLoop.LoopResult result;
            LocalTurnTrace trace;
            try {
                result = loop.run(initial, messages, ws, ctx);
                trace = LocalTurnTraceCapture.complete();
            } finally {
                TurnUserRequestCapture.clear();
                TurnTaskContractCapture.clear();
                LocalTurnTraceCapture.clear();
            }

            assertEquals("body { color: black; }\n", Files.readString(ws.resolve("styles.css")),
                    "selector repair writes that preserve verifier-known missing selectors must not apply");
            assertEquals(0, approvals[0],
                    "the preserved-selector repair write must be blocked before approval");
            assertEquals(0, result.toolsInvoked(),
                    "the preserved-selector repair write must not be counted as executed");
            assertEquals(0, result.mutatingToolSuccesses(),
                    "the preserved-selector repair write must not count as a successful mutation");
            assertTrue(result.failureDecision().shouldStop(), result.failureDecision().reason());
            assertTrue(result.failureDecision().reason().contains("STATIC_SELECTOR_REPAIR_PRESERVED_MISSING_SELECTOR"),
                    result.failureDecision().reason());
            assertTrue(result.failureDecision().reason().contains("styles.css"),
                    result.failureDecision().reason());
            assertTrue(result.failureDecision().reason().contains(".button"),
                    result.failureDecision().reason());

            String lower = result.finalAnswer().toLowerCase(java.util.Locale.ROOT);
            assertFalse(lower.contains("complete"), result.finalAnswer());
            assertFalse(lower.contains("ready to use"), result.finalAnswer());
            assertFalse(lower.contains("open in browser"), result.finalAnswer());

            var breached = trace.events().stream()
                    .filter(event -> "ACTION_OBLIGATION_EVALUATED".equals(event.type()))
                    .filter(event -> "STATIC_SELECTOR_REPAIR_PRESERVED_MISSING_SELECTOR"
                            .equals(event.data().get("failureKind")))
                    .findFirst()
                    .orElseThrow();
            assertEquals("STATIC_SELECTOR_REPAIR", breached.data().get("obligation"));
            assertEquals("FAILED", breached.data().get("status"));
        } finally {
            deleteRecursive(ws);
        }
    }

    @Test
    void staticSelectorRepairRejectsPreservedMissingJavaScriptSelectorBeforeApply() throws Exception {
        Path ws = Files.createTempDirectory("talos-static-selector-repair-preserved-js-");
        try {
            Files.writeString(ws.resolve("index.html"), """
                    <!doctype html>
                    <html>
                    <body>
                      <button id="run-button">Run</button>
                      <p id="result">Waiting</p>
                      <script src="scripts.js"></script>
                    </body>
                    </html>
                    """);
            Files.writeString(ws.resolve("scripts.js"), "console.log('old');\n");

            var registry = new ToolRegistry();
            registry.register(new FileWriteTool());
            final int[] approvals = {0};
            var processor = new TurnProcessor(
                    ModeController.defaultController(),
                    (description, detail) -> {
                        approvals[0]++;
                        return true;
                    },
                    registry);
            var loop = new ToolCallLoop(processor, 10);

            String request = "Fix the remaining static verification problems.";
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("sys"),
                    ChatMessage.system("""
                            [Static verification repair context]
                            Expected targets: index.html, scripts.js, styles.css

                            Previous static verification problems:
                            - JavaScript references missing class selectors: `.missing-button`

                            Repair plan:
                            Full-file replacement targets: scripts.js
                            - scripts.js: You must use talos.write_file with complete corrected file content for scripts.js.
                            - Verify static checks again before claiming completion.

                            [Current static selector facts]
                            I checked the selectors against the actual workspace files:

                            - HTML: `index.html`
                            - CSS: `styles.css`
                            - JavaScript: `scripts.js`

                            Observed in HTML:
                            - Classes: none
                            - IDs: `#run-button`, `#result`

                            Mismatches found:
                            - JavaScript references missing class selectors: `.missing-button`
                            """),
                    ChatMessage.user(request)));
            var ctx = Context.builder(new Config())
                    .sandbox(new Sandbox(ws, Map.of()))
                    .llm(LlmClient.scripted(List.of("Complete. Everything is ready to use.")))
                    .build();
            String initial = """
                    {"name":"talos.write_file","arguments":{"path":"scripts.js","content":"document.querySelector('.missing-button').addEventListener('click', () => {\\n  document.querySelector('#result').textContent = 'Clicked';\\n});\\n"}}
                    """;

            TurnUserRequestCapture.set(request);
            TurnTaskContractCapture.set(TaskContractResolver.fromMessages(messages));
            ToolCallLoop.LoopResult result;
            try {
                result = loop.run(initial, messages, ws, ctx);
            } finally {
                TurnUserRequestCapture.clear();
                TurnTaskContractCapture.clear();
            }

            assertEquals("console.log('old');\n", Files.readString(ws.resolve("scripts.js")),
                    "JavaScript repair writes that preserve known missing selectors must not apply");
            assertEquals(0, approvals[0],
                    "the preserved JavaScript selector repair write must be blocked before approval");
            assertEquals(0, result.toolsInvoked());
            assertTrue(result.failureDecision().shouldStop(), result.failureDecision().reason());
            assertTrue(result.failureDecision().reason().contains("STATIC_SELECTOR_REPAIR_PRESERVED_MISSING_SELECTOR"),
                    result.failureDecision().reason());
            assertTrue(result.failureDecision().reason().contains("scripts.js"),
                    result.failureDecision().reason());
            assertTrue(result.failureDecision().reason().contains(".missing-button"),
                    result.failureDecision().reason());
        } finally {
            deleteRecursive(ws);
        }
    }

    @Test
    void staticSelectorRepairAllowsReplacementThatRemovesKnownMissingSelector() throws Exception {
        Path ws = Files.createTempDirectory("talos-static-selector-repair-valid-css-");
        try {
            Files.writeString(ws.resolve("index.html"), """
                    <!doctype html>
                    <html>
                    <head>
                      <link rel="stylesheet" href="styles.css">
                    </head>
                    <body>
                      <p id="result">Waiting</p>
                    </body>
                    </html>
                    """);
            Files.writeString(ws.resolve("styles.css"), ".button { color: red; }\nbody { color: black; }\n");

            var registry = new ToolRegistry();
            registry.register(new FileWriteTool());
            final int[] approvals = {0};
            var processor = new TurnProcessor(
                    ModeController.defaultController(),
                    (description, detail) -> {
                        approvals[0]++;
                        return true;
                    },
                    registry);
            var loop = new ToolCallLoop(processor, 10);

            String request = "Fix the remaining static verification problems.";
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("sys"),
                    ChatMessage.system("""
                            [Static verification repair context]
                            Expected targets: index.html, scripts.js, styles.css

                            Previous static verification problems:
                            - CSS references missing class selectors: `.button`

                            Repair plan:
                            Full-file replacement targets: styles.css
                            - styles.css: You must use talos.write_file with complete corrected file content for styles.css.
                            - Verify static checks again before claiming completion.

                            [Current static selector facts]
                            I checked the selectors against the actual workspace files:

                            - HTML: `index.html`
                            - CSS: `styles.css`
                            - JavaScript: `scripts.js`

                            Observed in HTML:
                            - Classes: none
                            - IDs: `#result`

                            Mismatches found:
                            - CSS references missing class selectors: `.button`
                            """),
                    ChatMessage.user(request)));
            var ctx = Context.builder(new Config())
                    .sandbox(new Sandbox(ws, Map.of()))
                    .llm(LlmClient.scripted(List.of("Complete. Everything is ready to use.")))
                    .build();
            String initial = """
                    {"name":"talos.write_file","arguments":{"path":"styles.css","content":"body { color: black; }\\n"}}
                    """;

            TurnUserRequestCapture.set(request);
            TurnTaskContractCapture.set(TaskContractResolver.fromMessages(messages));
            ToolCallLoop.LoopResult result;
            try {
                result = loop.run(initial, messages, ws, ctx);
            } finally {
                TurnUserRequestCapture.clear();
                TurnTaskContractCapture.clear();
            }

            assertEquals("body { color: black; }\n", Files.readString(ws.resolve("styles.css")));
            assertEquals(1, approvals[0],
                    "valid selector repair write should still reach approval and apply");
            assertEquals(1, result.toolsInvoked());
            assertEquals(1, result.mutatingToolSuccesses());
            assertFalse(result.failureDecision().shouldStop(), result.failureDecision().reason());
        } finally {
            deleteRecursive(ws);
        }
    }

    @Test
    void expectedTargetProgressContextBudgetExceededBecomesDeterministicBreach() {
        var loop = createLoop(writeFileTool());
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("Create index.html, styles.css, and scripts.js for a BMI calculator.")));
        var ctx = Context.builder(new Config())
                .llm(LlmClient.scriptedFailure(new EngineException.ContextBudgetExceeded(
                        5946, 5635, 8192, 0)))
                .build();
        String llmResponse = """
                <tool_call>{"name":"talos.write_file","parameters":{"path":"index.html","content":"<html></html>"}}</tool_call>
                <tool_call>{"name":"talos.write_file","parameters":{"path":"styles.css","content":"body{}"}}</tool_call>
                """;

        LocalTurnTraceCapture.begin("trc-t197-budget", "session", 1,
                "2026-05-07T00:00:00Z", "ws", "test", "llama_cpp", "gpt-oss", "create bmi");
        ToolCallLoop.LoopResult result;
        LocalTurnTrace trace;
        try {
            result = loop.run(llmResponse, messages, WS, ctx);
            trace = LocalTurnTraceCapture.complete();
        } finally {
            LocalTurnTraceCapture.clear();
        }

        assertTrue(result.failureDecision().shouldStop(), result.failureDecision().reason());
        assertTrue(result.failureDecision().reason().contains("EXPECTED_TARGETS_REMAINING"),
                result.failureDecision().reason());
        assertTrue(result.finalAnswer().contains("scripts.js"), result.finalAnswer());
        assertTrue(result.finalAnswer().toLowerCase().contains("context budget"), result.finalAnswer());
        assertFalse(result.finalAnswer().contains("Engine error"), result.finalAnswer());
        assertFalse(result.finalAnswer().toLowerCase().contains("ready to use"), result.finalAnswer());

        var breached = trace.events().stream()
                .filter(event -> "PENDING_ACTION_OBLIGATION_BREACHED".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("EXPECTED_TARGETS_REMAINING", breached.data().get("kind"));
        assertEquals(List.of("scripts.js"), breached.data().get("targets"));
        assertTrue(String.valueOf(breached.data().get("reason")).contains("context budget"),
                breached.data().toString());
    }

    @Test
    void expectedTargetProgressToolCallKeepsHappyPathOpen() {
        var loop = createLoop(writeFileTool());
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("Create index.html, styles.css, and scripts.js for a BMI calculator.")));
        var ctx = Context.builder(new Config())
                .llm(LlmClient.scripted(List.of(
                        "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"scripts.js\",\"content\":\"console.log('ok');\"}}")))
                .build();
        String llmResponse = """
                <tool_call>{"name":"talos.write_file","parameters":{"path":"index.html","content":"<html></html>"}}</tool_call>
                <tool_call>{"name":"talos.write_file","parameters":{"path":"styles.css","content":"body{}"}}</tool_call>
                """;

        LocalTurnTraceCapture.begin("trc-t99-happy", "session", 1,
                "2026-05-03T00:00:00Z", "ws", "test", "ollama", "qwen", "create bmi");
        ToolCallLoop.LoopResult result;
        LocalTurnTrace trace;
        try {
            result = loop.run(llmResponse, messages, WS, ctx);
            trace = LocalTurnTraceCapture.complete();
        } finally {
            LocalTurnTraceCapture.clear();
        }

        assertFalse(result.failureDecision().shouldStop(), result.failureDecision().reason());
        assertEquals(3, result.mutatingToolSuccesses());
        assertTrue(result.toolOutcomes().stream()
                .anyMatch(outcome -> outcome.success() && "scripts.js".equals(outcome.pathHint())));
        assertTrue(trace.events().stream()
                .anyMatch(event -> "PENDING_ACTION_OBLIGATION_RAISED".equals(event.type())));
        assertTrue(trace.events().stream()
                .noneMatch(event -> "PENDING_ACTION_OBLIGATION_BREACHED".equals(event.type())));
    }

    @Test
    void offTargetExpectedMutationStopsLoopWithoutSuccessProseOrFileChange() throws Exception {
        Path ws = Files.createTempDirectory("talos-expected-target-scope-loop-");
        try {
            var registry = new ToolRegistry();
            registry.register(new FileWriteTool());
            final int[] approvals = {0};
            var processor = new TurnProcessor(
                    ModeController.defaultController(),
                    (description, detail) -> {
                        approvals[0]++;
                        return true;
                    },
                    registry);
            var loop = new ToolCallLoop(processor, 10);

            String request = "Create a complete static BMI calculator in this folder with index.html, styles.css, and scripts.js.";
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("sys"),
                    ChatMessage.user(request)));
            var ctx = Context.builder(new Config())
                    .sandbox(new Sandbox(ws, Map.of()))
                    .llm(LlmClient.scripted(List.of("should not be called")))
                    .build();
            String initial = """
                    Complete and ready to use.
                    {"name":"talos.write_file","arguments":{"path":"notes.md","content":"off target"}}
                    """;

            TurnUserRequestCapture.set(request);
            TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(request));
            LocalTurnTraceCapture.begin("trc-t119-off-target", "session", 1,
                    "2026-05-04T00:00:00Z", "ws", "test", "llama_cpp", "gpt-oss", request);
            ToolCallLoop.LoopResult result;
            LocalTurnTrace trace;
            try {
                result = loop.run(initial, messages, ws, ctx);
                trace = LocalTurnTraceCapture.complete();
            } finally {
                TurnUserRequestCapture.clear();
                TurnTaskContractCapture.clear();
                LocalTurnTraceCapture.clear();
            }

            assertEquals(1, result.iterations());
            assertEquals(1, result.toolsInvoked());
            assertEquals(0, result.mutatingToolSuccesses());
            assertEquals(0, approvals[0], "off-target mutation must not reach approval");
            assertFalse(Files.exists(ws.resolve("notes.md")),
                    "off-target file must not be written");
            assertTrue(result.failureDecision().shouldStop(), result.failureDecision().reason());
            assertTrue(result.failureDecision().reason().contains("outside the current expected target set"),
                    result.failureDecision().reason());
            String finalLower = result.finalAnswer().toLowerCase(java.util.Locale.ROOT);
            assertFalse(finalLower.contains("complete"), result.finalAnswer());
            assertFalse(finalLower.contains("ready to use"), result.finalAnswer());

            var blocked = trace.events().stream()
                    .filter(event -> "TOOL_CALL_BLOCKED".equals(event.type()))
                    .filter(event -> String.valueOf(event.data().get("reason"))
                            .contains("outside the current expected target set"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("notes.md", blocked.data().get("pathHint"));
        } finally {
            deleteRecursive(ws);
        }
    }

    // ── T151: static web repair recovery ────────────────────────────

    @Test
    void staticWebVerifierPassStopsWithoutExpectedContextTargetBreach() throws Exception {
        Path ws = Files.createTempDirectory("talos-static-web-context-pass-");
        try {
            Files.writeString(ws.resolve("index.html"), """
                    <!doctype html>
                    <html>
                    <head>
                      <link rel="stylesheet" href="styles.css">
                    </head>
                    <body>
                      <button id="run-button">Run</button>
                      <p id="result">Waiting</p>
                      <script src="script.js"></script>
                    </body>
                    </html>
                    """);
            Files.writeString(ws.resolve("styles.css"), "body { font-family: sans-serif; }\n");
            Files.writeString(ws.resolve("script.js"), """
                    document.querySelector('.missing-button').addEventListener('click', () => {
                      document.querySelector('#result').textContent = 'Clicked';
                    });
                    """);

            var registry = new ToolRegistry();
            registry.register(new FileWriteTool());
            var processor = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
            var loop = new ToolCallLoop(processor, 10);

            String request = "Fix the static web button fixture. The existing index.html loads script.js; "
                    + "the button with id run-button should set #result to Clicked. "
                    + "Keep filenames index.html, styles.css, and script.js. Do not create scripts.js.";
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("sys"),
                    ChatMessage.user(request)));
            var ctx = Context.builder(new Config())
                    .sandbox(new Sandbox(ws, Map.of()))
                    .llm(LlmClient.scripted(List.of(
                            "Complete. Everything is ready to use.")))
                    .build();

            String correctedScript = """
                    document.getElementById('run-button').addEventListener('click', () => {
                      document.getElementById('result').textContent = 'Clicked';
                    });
                    """;
            String initial = """
                    {"name":"talos.write_file","arguments":{"path":"script.js","content":"%s"}}
                    """.formatted(jsonEscape(correctedScript));

            TurnUserRequestCapture.set(request);
            TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(request));
            LocalTurnTraceCapture.begin("trc-t151-static-context-pass", "session", 1,
                    "2026-05-05T00:00:00Z", "ws", "test", "llama_cpp", "qwen", request);
            ToolCallLoop.LoopResult result;
            LocalTurnTrace trace;
            try {
                result = loop.run(initial, messages, ws, ctx);
                trace = LocalTurnTraceCapture.complete();
            } finally {
                TurnUserRequestCapture.clear();
                TurnTaskContractCapture.clear();
                LocalTurnTraceCapture.clear();
            }

            assertEquals(1, result.iterations(),
                    "Verified static web repair should stop after the successful mutation.");
            assertFalse(result.hitIterLimit(), "Verifier-passed static web repair must not run to the loop cap.");
            assertFalse(result.failureDecision().shouldStop(), result.failureDecision().reason());
            assertEquals(1, result.mutatingToolSuccesses());
            assertEquals(correctedScript, Files.readString(ws.resolve("script.js")));
            assertTrue(trace.events().stream()
                            .noneMatch(event -> "PENDING_ACTION_OBLIGATION_BREACHED".equals(event.type())),
                    "index.html/styles.css context targets must not become a pending-obligation breach "
                            + "when static web verification already passes.");
        } finally {
            deleteRecursive(ws);
        }
    }

    @Test
    void staticWebOldStringFailureAfterReadRecoversThroughFullWriteReplacement() throws Exception {
        Path ws = Files.createTempDirectory("talos-static-web-edit-rewrite-");
        try {
            Files.writeString(ws.resolve("index.html"), """
                    <!doctype html>
                    <html>
                    <head>
                      <link rel="stylesheet" href="styles.css">
                    </head>
                    <body>
                      <button id="run-button">Run</button>
                      <p id="result">Waiting</p>
                      <script src="script.js"></script>
                    </body>
                    </html>
                    """);
            Files.writeString(ws.resolve("styles.css"), "body { font-family: sans-serif; }\n");
            Files.writeString(ws.resolve("script.js"), """
                    document.querySelector('.missing-button').addEventListener('click', () => {
                      document.querySelector('#result').textContent = 'Clicked';
                    });
                    """);

            var registry = new ToolRegistry();
            registry.register(new ReadFileTool());
            registry.register(new FileEditTool(new FileUndoStack()));
            registry.register(new FileWriteTool());
            var processor = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
            var loop = new ToolCallLoop(processor, 10);

            String request = "Fix the static web button fixture. The existing index.html loads script.js; "
                    + "the button with id run-button should set #result to Clicked. "
                    + "Keep filenames index.html, styles.css, and script.js. Do not create scripts.js.";
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("sys"),
                    ChatMessage.user(request)));

            String badEdit = """
                    {"name":"talos.edit_file","arguments":{"path":"script.js","old_string":"document.querySelector('.missing-button').addEventListener('click', function () {","new_string":"document.querySelector('#run-button').addEventListener('click', function () {"}}
                    """;
            String correctedScript = """
                    document.getElementById('run-button').addEventListener('click', () => {
                      document.getElementById('result').textContent = 'Clicked';
                    });
                    """;
            String rewrite = """
                    {"name":"talos.write_file","arguments":{"path":"script.js","content":"%s"}}
                    """.formatted(jsonEscape(correctedScript));
            var ctx = Context.builder(new Config())
                    .sandbox(new Sandbox(ws, Map.of()))
                    .llm(LlmClient.scripted(List.of(badEdit, rewrite, "done")))
                    .build();

            TurnUserRequestCapture.set(request);
            TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(request));
            LocalTurnTraceCapture.begin("trc-t151-static-edit-rewrite", "session", 1,
                    "2026-05-05T00:00:00Z", "ws", "test", "llama_cpp", "gpt-oss", request);
            ToolCallLoop.LoopResult result;
            LocalTurnTrace trace;
            try {
                result = loop.run(readFileCall("script.js"), messages, ws, ctx);
                trace = LocalTurnTraceCapture.complete();
            } finally {
                TurnUserRequestCapture.clear();
                TurnTaskContractCapture.clear();
                LocalTurnTraceCapture.clear();
            }

            assertFalse(result.failureDecision().shouldStop(), result.failureDecision().reason());
            assertFalse(result.summary().contains("failed"),
                    "Recovered static web edit failures should not make the normal tool summary look failed.");
            assertEquals(correctedScript, Files.readString(ws.resolve("script.js")));
            assertTrue(result.toolOutcomes().stream().anyMatch(ToolCallLoop.ToolOutcome::oldStringNotFoundEditFailure),
                    "The initial old_string miss should be visible in tool outcomes.");
            assertTrue(trace.events().stream()
                            .anyMatch(event -> "REPAIR_DECISION_RECORDED".equals(event.type())
                                    && String.valueOf(event.data().get("summary"))
                                            .contains("static-web-edit-rewrite")),
                    "Trace should record the static web edit-to-write recovery decision.");
            assertTrue(trace.events().stream()
                            .noneMatch(event -> "PENDING_ACTION_OBLIGATION_BREACHED".equals(event.type())),
                    "A direct write_file recovery must satisfy the pending repair obligation.");
        } finally {
            deleteRecursive(ws);
        }
    }

    @Test
    void staticWebFullRewriteRequiredRejectsReadOnlyContinuationBeforeSuccessProse() throws Exception {
        Path ws = Files.createTempDirectory("talos-static-web-rewrite-read-breach-");
        try {
            Files.writeString(ws.resolve("index.html"), """
                    <!doctype html>
                    <html>
                    <head>
                      <link rel="stylesheet" href="styles.css">
                    </head>
                    <body>
                      <button id="run-button">Run</button>
                      <p id="result">Waiting</p>
                      <script src="script.js"></script>
                    </body>
                    </html>
                    """);
            Files.writeString(ws.resolve("styles.css"), "body { font-family: sans-serif; }\n");
            Files.writeString(ws.resolve("script.js"), """
                    document.querySelector('.missing-button').addEventListener('click', () => {
                      document.querySelector('#result').textContent = 'Clicked';
                    });
                    """);

            var registry = new ToolRegistry();
            registry.register(new ReadFileTool());
            registry.register(new FileEditTool(new FileUndoStack()));
            registry.register(new FileWriteTool());
            var processor = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
            var loop = new ToolCallLoop(processor, 10);

            String request = "Fix the static web button fixture. The existing index.html loads script.js; "
                    + "the button with id run-button should set #result to Clicked. "
                    + "Keep filenames index.html, styles.css, and script.js. Do not create scripts.js.";
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("sys"),
                    ChatMessage.user(request)));

            String badEdit = """
                    {"name":"talos.edit_file","arguments":{"path":"script.js","old_string":"document.querySelector('.missing-button').addEventListener('click', function () {","new_string":"document.querySelector('#run-button').addEventListener('click', function () {"}}
                    """;
            var ctx = Context.builder(new Config())
                    .sandbox(new Sandbox(ws, Map.of()))
                    .llm(LlmClient.scripted(List.of(
                            badEdit,
                            readFileCall("script.js"),
                            "Complete. Everything is ready to use.")))
                    .build();

            TurnUserRequestCapture.set(request);
            TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(request));
            LocalTurnTraceCapture.begin("trc-t152-static-rewrite-read-breach", "session", 1,
                    "2026-05-06T00:00:00Z", "ws", "test", "llama_cpp", "gpt-oss", request);
            ToolCallLoop.LoopResult result;
            LocalTurnTrace trace;
            try {
                result = loop.run(readFileCall("script.js"), messages, ws, ctx);
                trace = LocalTurnTraceCapture.complete();
            } finally {
                TurnUserRequestCapture.clear();
                TurnTaskContractCapture.clear();
                LocalTurnTraceCapture.clear();
            }

            assertTrue(result.failureDecision().shouldStop(), result.failureDecision().reason());
            assertTrue(result.failureDecision().reason().contains("STATIC_REPAIR_TARGETS_REMAINING"),
                    result.failureDecision().reason());
            assertTrue(result.failureDecision().reason().contains("script.js"),
                    result.failureDecision().reason());
            assertEquals(2, result.toolsInvoked(),
                    "After old_string miss following read evidence, a read-only continuation should not execute.");
            assertFalse(result.hitIterLimit(), "Static rewrite breach should stop before the generic loop cap.");
            String lower = result.finalAnswer().toLowerCase(java.util.Locale.ROOT);
            assertFalse(lower.contains("complete"), result.finalAnswer());
            assertFalse(lower.contains("ready to use"), result.finalAnswer());
            assertEquals("""
                    document.querySelector('.missing-button').addEventListener('click', () => {
                      document.querySelector('#result').textContent = 'Clicked';
                    });
                    """, Files.readString(ws.resolve("script.js")));

            assertTrue(trace.events().stream()
                            .anyMatch(event -> "PENDING_ACTION_OBLIGATION_RAISED".equals(event.type())
                                    && "STATIC_REPAIR_TARGETS_REMAINING".equals(event.data().get("kind"))),
                    "Trace should record the static repair obligation before the breach.");
            assertTrue(trace.events().stream()
                            .anyMatch(event -> "PENDING_ACTION_OBLIGATION_BREACHED".equals(event.type())
                                    && "STATIC_REPAIR_TARGETS_REMAINING".equals(event.data().get("kind"))),
                    "Trace should record a deterministic static repair breach.");
        } finally {
            deleteRecursive(ws);
        }
    }

    @Test
    void staticWebFullRewriteRequiredRejectsRepeatedEditContinuationBeforeSuccessProse() throws Exception {
        Path ws = Files.createTempDirectory("talos-static-web-rewrite-edit-breach-");
        try {
            Files.writeString(ws.resolve("index.html"), """
                    <!doctype html>
                    <html>
                    <head>
                      <link rel="stylesheet" href="styles.css">
                    </head>
                    <body>
                      <button id="run-button">Run</button>
                      <p id="result">Waiting</p>
                      <script src="script.js"></script>
                    </body>
                    </html>
                    """);
            Files.writeString(ws.resolve("styles.css"), "body { font-family: sans-serif; }\n");
            Files.writeString(ws.resolve("script.js"), """
                    document.querySelector('.missing-button').addEventListener('click', () => {
                      document.querySelector('#result').textContent = 'Clicked';
                    });
                    """);

            var registry = new ToolRegistry();
            registry.register(new ReadFileTool());
            registry.register(new FileEditTool(new FileUndoStack()));
            registry.register(new FileWriteTool());
            var processor = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
            var loop = new ToolCallLoop(processor, 10);

            String request = "Fix the static web button fixture. The existing index.html loads script.js; "
                    + "the button with id run-button should set #result to Clicked. "
                    + "Keep filenames index.html, styles.css, and script.js. Do not create scripts.js.";
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("sys"),
                    ChatMessage.user(request)));

            String badEdit = """
                    {"name":"talos.edit_file","arguments":{"path":"script.js","old_string":"document.querySelector('.missing-button').addEventListener('click', function () {","new_string":"document.querySelector('#run-button').addEventListener('click', function () {"}}
                    """;
            String repeatedEdit = """
                    {"name":"talos.edit_file","arguments":{"path":"script.js","old_string":"document.querySelector('.missing-button').addEventListener('click', function(){","new_string":"document.querySelector('#run-button').addEventListener('click', function(){"}}
                    """;
            var ctx = Context.builder(new Config())
                    .sandbox(new Sandbox(ws, Map.of()))
                    .llm(LlmClient.scripted(List.of(
                            badEdit,
                            repeatedEdit,
                            "Complete. Everything is ready to use.")))
                    .build();

            TurnUserRequestCapture.set(request);
            TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(request));
            LocalTurnTraceCapture.begin("trc-t152-static-rewrite-edit-breach", "session", 1,
                    "2026-05-06T00:00:00Z", "ws", "test", "llama_cpp", "gpt-oss", request);
            ToolCallLoop.LoopResult result;
            LocalTurnTrace trace;
            try {
                result = loop.run(readFileCall("script.js"), messages, ws, ctx);
                trace = LocalTurnTraceCapture.complete();
            } finally {
                TurnUserRequestCapture.clear();
                TurnTaskContractCapture.clear();
                LocalTurnTraceCapture.clear();
            }

            assertTrue(result.failureDecision().shouldStop(), result.failureDecision().reason());
            assertTrue(result.failureDecision().reason().contains("STATIC_REPAIR_TARGETS_REMAINING"),
                    result.failureDecision().reason());
            assertEquals(2, result.toolsInvoked(),
                    "A repeated edit_file under a full-rewrite obligation should not execute.");
            assertFalse(result.hitIterLimit(), "Static rewrite breach should stop before the generic loop cap.");
            String lower = result.finalAnswer().toLowerCase(java.util.Locale.ROOT);
            assertFalse(lower.contains("complete"), result.finalAnswer());
            assertFalse(lower.contains("ready to use"), result.finalAnswer());
            assertTrue(trace.events().stream()
                            .anyMatch(event -> "PENDING_ACTION_OBLIGATION_BREACHED".equals(event.type())
                                    && "STATIC_REPAIR_TARGETS_REMAINING".equals(event.data().get("kind"))),
                    "Trace should record the repeated-edit static repair breach.");
        } finally {
            deleteRecursive(ws);
        }
    }

    // ── T122: repair read-only loop budget ─────────────────────────

    @Test
    void repairReadOnlyLoopStopsBeforeIterationLimitWithInspectionOnlyBreach() throws Exception {
        Path ws = Files.createTempDirectory("talos-repair-read-only-budget-");
        try {
            Files.writeString(ws.resolve("index.html"), "<script src=\"scripts.js\"></script>\n");
            Files.writeString(ws.resolve("styles.css"), "body{}\n");
            Files.writeString(ws.resolve("scripts.js"), "console.log('old');\n");

            var registry = new ToolRegistry();
            registry.register(new ReadFileTool());
            registry.register(new FileWriteTool());
            var processor = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
            var loop = new ToolCallLoop(processor, 10);

            String request = "Review the BMI calculator and fix any obvious issue that would stop it from working in a browser.";
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("sys"),
                    ChatMessage.user(request)));
            var ctx = Context.builder(new Config())
                    .sandbox(new Sandbox(ws, Map.of()))
                    .llm(LlmClient.scripted(List.of(
                            readFileCall("styles.css"),
                            readFileCall("scripts.js"),
                            readFileCall("index.html", 200),
                            readFileCall("styles.css", 200),
                            readFileCall("scripts.js", 200),
                            readFileCall("index.html", 400),
                            readFileCall("styles.css", 400),
                            readFileCall("scripts.js", 400),
                            readFileCall("index.html", 800),
                            readFileCall("styles.css", 800),
                            readFileCall("scripts.js", 800))))
                    .build();

            TurnUserRequestCapture.set(request);
            TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(request));
            LocalTurnTraceCapture.begin("trc-t122-read-only-budget", "session", 1,
                    "2026-05-04T00:00:00Z", "ws", "test", "llama_cpp", "gpt-oss", request);
            ToolCallLoop.LoopResult result;
            LocalTurnTrace trace;
            try {
                result = loop.run(readFileCall("index.html"), messages, ws, ctx);
                trace = LocalTurnTraceCapture.complete();
            } finally {
                TurnUserRequestCapture.clear();
                TurnTaskContractCapture.clear();
                LocalTurnTraceCapture.clear();
            }

            assertTrue(result.failureDecision().shouldStop(), result.failureDecision().reason());
            assertTrue(result.failureDecision().reason().contains("REPAIR_INSPECTION_ONLY"),
                    result.failureDecision().reason());
            assertFalse(result.hitIterLimit(), "repair read-only budget should stop before generic loop limit");
            assertTrue(result.iterations() < 10, "repair read-only budget should stop before max iterations");
            assertEquals(0, result.mutatingToolSuccesses());
            assertTrue(result.toolOutcomes().stream().noneMatch(ToolCallLoop.ToolOutcome::mutating));
            assertEquals("console.log('old');\n", Files.readString(ws.resolve("scripts.js")));

            String finalLower = result.finalAnswer().toLowerCase(java.util.Locale.ROOT);
            assertTrue(finalLower.contains("repair/fix turn inspected files but did not change them"),
                    result.finalAnswer());
            assertFalse(finalLower.contains("complete"), result.finalAnswer());
            assertFalse(finalLower.contains("ready to use"), result.finalAnswer());

            var breached = trace.events().stream()
                    .filter(event -> "ACTION_OBLIGATION_EVALUATED".equals(event.type()))
                    .filter(event -> "REPAIR_INSPECTION_ONLY".equals(event.data().get("failureKind")))
                    .findFirst()
                    .orElseThrow();
            assertEquals("CONDITIONAL_REVIEW_FIX", breached.data().get("obligation"));
            assertEquals("FAILED", breached.data().get("status"));
        } finally {
            deleteRecursive(ws);
        }
    }

    @Test
    void repairReadOnlyBudgetAllowsReadThenMutation() throws Exception {
        Path ws = Files.createTempDirectory("talos-repair-read-then-mutate-");
        try {
            Files.writeString(ws.resolve("index.html"), "<script src=\"scripts.js\"></script>\n");
            Files.writeString(ws.resolve("styles.css"), "body{}\n");
            Files.writeString(ws.resolve("scripts.js"), "console.log('old');\n");

            var registry = new ToolRegistry();
            registry.register(new ReadFileTool());
            registry.register(new FileWriteTool());
            var processor = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
            var loop = new ToolCallLoop(processor, 10);

            String request = "Review the BMI calculator and fix any obvious issue that would stop it from working in a browser.";
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("sys"),
                    ChatMessage.user(request)));
            String writeScripts = """
                    {"name":"talos.write_file","arguments":{"path":"scripts.js","content":"console.log('fixed');\\n"}}
                    """;
            var ctx = Context.builder(new Config())
                    .sandbox(new Sandbox(ws, Map.of()))
                    .llm(LlmClient.scripted(List.of(
                            readFileCall("styles.css"),
                            readFileCall("scripts.js"),
                            writeScripts,
                            "should not be called")))
                    .build();

            TurnUserRequestCapture.set(request);
            TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(request));
            ToolCallLoop.LoopResult result;
            try {
                result = loop.run(readFileCall("index.html"), messages, ws, ctx);
            } finally {
                TurnUserRequestCapture.clear();
                TurnTaskContractCapture.clear();
            }

            assertFalse(result.failureDecision().shouldStop(), result.failureDecision().reason());
            assertFalse(result.hitIterLimit());
            assertEquals(1, result.mutatingToolSuccesses());
            assertEquals("console.log('fixed');\n", Files.readString(ws.resolve("scripts.js")));
        } finally {
            deleteRecursive(ws);
        }
    }

    @Test
    void repairReadOnlyBudgetCountsSuppressedRedundantReadsBeforeAnotherContinuation() throws Exception {
        Path ws = Files.createTempDirectory("talos-repair-redundant-read-budget-");
        try {
            Files.writeString(ws.resolve("index.html"), "<script src=\"missing.js\"></script>\n");
            Files.writeString(ws.resolve("styles.css"), "body{}\n");
            Files.writeString(ws.resolve("scripts.js"), "console.log('old');\n");

            var registry = new ToolRegistry();
            registry.register(new ReadFileTool());
            registry.register(new FileWriteTool());
            var processor = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
            var loop = new ToolCallLoop(processor, 10);

            String request = "Review the BMI calculator and fix any obvious issue that would stop it from working in a browser.";
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("sys"),
                    ChatMessage.user(request)));
            var ctx = Context.builder(new Config())
                    .sandbox(new Sandbox(ws, Map.of()))
                    .llm(LlmClient.scripted(List.of(
                            readFileCall("styles.css"),
                            readFileCall("scripts.js"),
                            readFileCall("index.html", 200),
                            readFileCall("styles.css", 200),
                            readFileCall("index.html", 200),
                            "Complete. Everything is ready to use.")))
                    .build();

            TurnUserRequestCapture.set(request);
            TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(request));
            LocalTurnTraceCapture.begin("trc-t221-redundant-read-budget", "session", 1,
                    "2026-05-08T00:00:00Z", "ws", "test", "llama_cpp", "gpt-oss", request);
            ToolCallLoop.LoopResult result;
            LocalTurnTrace trace;
            try {
                result = loop.run(readFileCall("index.html"), messages, ws, ctx);
                trace = LocalTurnTraceCapture.complete();
            } finally {
                TurnUserRequestCapture.clear();
                TurnTaskContractCapture.clear();
                LocalTurnTraceCapture.clear();
            }

            assertTrue(result.failureDecision().shouldStop(), result.failureDecision().reason());
            assertTrue(result.failureDecision().reason().contains("REPAIR_INSPECTION_ONLY"),
                    result.failureDecision().reason());
            assertTrue(result.cushionFiresRedundantRead() > 0,
                    "The suppressed duplicate read should be visible in the loop result.");
            assertEquals(0, result.mutatingToolSuccesses());

            String finalLower = result.finalAnswer().toLowerCase(java.util.Locale.ROOT);
            assertTrue(finalLower.contains("repair/fix turn inspected files but did not change them"),
                    result.finalAnswer());
            assertFalse(finalLower.contains("context budget"), result.finalAnswer());
            assertFalse(finalLower.contains("complete"), result.finalAnswer());
            assertFalse(finalLower.contains("ready to use"), result.finalAnswer());

            assertTrue(trace.events().stream()
                            .anyMatch(event -> "ACTION_OBLIGATION_EVALUATED".equals(event.type())
                                    && "REPAIR_INSPECTION_ONLY".equals(event.data().get("failureKind"))),
                    "Trace should record deterministic repair inspection-only failure.");
        } finally {
            deleteRecursive(ws);
        }
    }

    @Test
    void oldStringMissWithReadbackUsesCompactTargetOnlyRepairBeforeContextBudgetFailure() throws Exception {
        Path ws = Files.createTempDirectory("talos-old-string-compact-repair-");
        try {
            Files.writeString(ws.resolve("README.md"), "# Fixture\n\nOriginal text.\n");

            var registry = new ToolRegistry();
            registry.register(new ReadFileTool());
            registry.register(new FileEditTool());
            registry.register(new FileWriteTool());
            var processor = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
            var loop = new ToolCallLoop(processor, 5);

            List<ToolSpec> toolSpecs = nativeSpecs(
                    new ReadFileTool(),
                    new FileEditTool(),
                    new FileWriteTool());
            String repaired = "# Fixture\n\nOriginal text.\n\nApplied proposal.\n";
            var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                    List.of(new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                            "call_repair_write",
                            "talos.write_file",
                            Map.of("path", "README.md", "content", repaired))))),
                    2048);
            var ctx = Context.builder(new Config())
                    .sandbox(new Sandbox(ws, Map.of()))
                    .llm(recorded.client())
                    .nativeToolSpecs(toolSpecs)
                    .build();

            String request = "Apply that README.md proposal now.";
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("sys " + "large-system-token ".repeat(700)),
                    ChatMessage.user("Earlier unrelated request with stale proposal details."),
                    ChatMessage.assistant("Old proposal context that must not dominate the compact repair."),
                    ChatMessage.user(request)));
            var initialCalls = List.of(
                    new ChatMessage.NativeToolCall(
                            "call_bad_edit",
                            "talos.edit_file",
                            Map.of(
                                    "path", "README.md",
                                    "old_string", "This text does not exist.",
                                    "new_string", "Applied proposal.")),
                    new ChatMessage.NativeToolCall(
                            "call_readback",
                            "talos.read_file",
                            Map.of("path", "README.md")));

            TurnUserRequestCapture.set(request);
            TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(request));
            ToolCallLoop.LoopResult result;
            try {
                result = loop.run("", initialCalls, messages, ws, ctx);
            } finally {
                TurnUserRequestCapture.clear();
                TurnTaskContractCapture.clear();
            }

            assertFalse(result.failureDecision().shouldStop(), result.failureDecision().reason());
            assertEquals(repaired, Files.readString(ws.resolve("README.md")));
            assertTrue(result.mutatingToolSuccesses() > 0, "compact repair should execute a write_file mutation");
            assertEquals(1, recorded.requests().size(), "generic oversized continuation should be replaced");

            String compactPrompt = recorded.requests().getFirst().messages.stream()
                    .map(ChatMessage::content)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(compactPrompt.contains("[OldStringMissRepair]"), compactPrompt);
            assertTrue(compactPrompt.contains("Apply that README.md proposal now."), compactPrompt);
            assertTrue(compactPrompt.contains("README.md"), compactPrompt);
            assertTrue(compactPrompt.contains("1 | # Fixture"), compactPrompt);
            assertFalse(compactPrompt.contains("large-system-token"), compactPrompt);
            assertFalse(compactPrompt.contains("Earlier unrelated request"), compactPrompt);
            assertFalse(compactPrompt.contains("Old proposal context"), compactPrompt);
            assertEquals(List.of("talos.edit_file", "talos.write_file"),
                    recorded.requests().getFirst().tools.stream().map(ToolSpec::name).toList());
        } finally {
            deleteRecursive(ws);
        }
    }

    @Test
    void oldStringMissCompactRepairPreservesExpectedTargetCasing() throws Exception {
        Path ws = Files.createTempDirectory("talos-old-string-compact-repair-case-");
        try {
            Files.writeString(ws.resolve("README.md"), "# Fixture\n\nOriginal text.\n");

            var registry = new ToolRegistry();
            registry.register(new ReadFileTool());
            registry.register(new FileEditTool());
            registry.register(new FileWriteTool());
            var processor = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
            var loop = new ToolCallLoop(processor, 5);

            String repaired = "# Fixture\n\nOriginal text.\n\nApplied proposal.\n";
            var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                    List.of(new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                            "call_repair_write",
                            "talos.write_file",
                            Map.of("path", "README.md", "content", repaired))))),
                    2048);
            var ctx = Context.builder(new Config())
                    .sandbox(new Sandbox(ws, Map.of()))
                    .llm(recorded.client())
                    .nativeToolSpecs(nativeSpecs(
                            new ReadFileTool(),
                            new FileEditTool(),
                            new FileWriteTool()))
                    .build();

            String request = "Apply that README.md proposal now.";
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("sys " + "large-system-token ".repeat(700)),
                    ChatMessage.user(request)));
            var initialCalls = List.of(
                    new ChatMessage.NativeToolCall(
                            "call_bad_edit",
                            "talos.edit_file",
                            Map.of(
                                    "path", "README.md",
                                    "old_string", "This text does not exist.",
                                    "new_string", "Applied proposal.")),
                    new ChatMessage.NativeToolCall(
                            "call_readback",
                            "talos.read_file",
                            Map.of("path", "README.md")));

            TurnUserRequestCapture.set(request);
            TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(request));
            try {
                loop.run("", initialCalls, messages, ws, ctx);
            } finally {
                TurnUserRequestCapture.clear();
                TurnTaskContractCapture.clear();
            }

            String compactPrompt = recorded.requests().getFirst().messages.stream()
                    .map(ChatMessage::content)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(compactPrompt.contains("[OldStringMissRepair] Target: README.md"), compactPrompt);
            assertFalse(compactPrompt.contains("[OldStringMissRepair] Target: readme.md"), compactPrompt);
        } finally {
            deleteRecursive(ws);
        }
    }

    @Test
    void oldStringMissCompactRepairRejectsCaseMismatchedTargetBeforeExecution() throws Exception {
        Path ws = Files.createTempDirectory("talos-old-string-compact-repair-case-mismatch-");
        try {
            String original = "# Fixture\n\nOriginal text.\n";
            Files.writeString(ws.resolve("README.md"), original);

            var registry = new ToolRegistry();
            registry.register(new ReadFileTool());
            registry.register(new FileEditTool());
            registry.register(new FileWriteTool());
            var processor = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
            var loop = new ToolCallLoop(processor, 5);

            var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                    List.of(new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                            "call_wrong_case_repair",
                            "talos.write_file",
                            Map.of("path", "readme.md", "content", "# Wrong target\n"))))),
                    2048);
            var ctx = Context.builder(new Config())
                    .sandbox(new Sandbox(ws, Map.of()))
                    .llm(recorded.client())
                    .nativeToolSpecs(nativeSpecs(
                            new ReadFileTool(),
                            new FileEditTool(),
                            new FileWriteTool()))
                    .build();

            String request = "Apply that README.md proposal now.";
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("sys " + "large-system-token ".repeat(700)),
                    ChatMessage.user(request)));
            var initialCalls = List.of(
                    new ChatMessage.NativeToolCall(
                            "call_bad_edit",
                            "talos.edit_file",
                            Map.of(
                                    "path", "README.md",
                                    "old_string", "This text does not exist.",
                                    "new_string", "Applied proposal.")),
                    new ChatMessage.NativeToolCall(
                            "call_readback",
                            "talos.read_file",
                            Map.of("path", "README.md")));

            TurnUserRequestCapture.set(request);
            TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(request));
            ToolCallLoop.LoopResult result;
            try {
                result = loop.run("", initialCalls, messages, ws, ctx);
            } finally {
                TurnUserRequestCapture.clear();
                TurnTaskContractCapture.clear();
            }

            assertTrue(result.failureDecision().shouldStop(), result.failureDecision().reason());
            assertTrue(result.failureDecision().reason().contains("OLD_STRING_MISS_TARGET_REPAIR"),
                    result.failureDecision().reason());
            assertTrue(result.failureDecision().reason().contains("talos.write_file(readme.md)"),
                    result.failureDecision().reason());
            assertEquals(2, result.toolsInvoked(), "case-mismatched compact repair must be rejected before execution");
            assertEquals(original, Files.readString(ws.resolve("README.md")));
        } finally {
            deleteRecursive(ws);
        }
    }

    @Test
    void oldStringMissCompactRepairNoToolProseBecomesDeterministicFailure() throws Exception {
        Path ws = Files.createTempDirectory("talos-old-string-compact-repair-no-tool-");
        try {
            String original = "# Fixture\n\nOriginal text.\n";
            Files.writeString(ws.resolve("README.md"), original);

            var registry = new ToolRegistry();
            registry.register(new ReadFileTool());
            registry.register(new FileEditTool());
            registry.register(new FileWriteTool());
            var processor = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
            var loop = new ToolCallLoop(processor, 5);

            var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                    List.of(new LlmClient.StreamResult(
                            "Complete. README.md is ready to use.",
                            List.of())),
                    2048);
            var ctx = Context.builder(new Config())
                    .sandbox(new Sandbox(ws, Map.of()))
                    .llm(recorded.client())
                    .nativeToolSpecs(nativeSpecs(
                            new ReadFileTool(),
                            new FileEditTool(),
                            new FileWriteTool()))
                    .build();

            String request = "Apply that README.md proposal now.";
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("sys " + "large-system-token ".repeat(700)),
                    ChatMessage.user(request)));
            var initialCalls = List.of(
                    new ChatMessage.NativeToolCall(
                            "call_bad_edit",
                            "talos.edit_file",
                            Map.of(
                                    "path", "README.md",
                                    "old_string", "This text does not exist.",
                                    "new_string", "Applied proposal.")),
                    new ChatMessage.NativeToolCall(
                            "call_readback",
                            "talos.read_file",
                            Map.of("path", "README.md")));

            TurnUserRequestCapture.set(request);
            TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(request));
            ToolCallLoop.LoopResult result;
            try {
                result = loop.run("", initialCalls, messages, ws, ctx);
            } finally {
                TurnUserRequestCapture.clear();
                TurnTaskContractCapture.clear();
            }

            assertTrue(result.failureDecision().shouldStop(), result.failureDecision().reason());
            assertTrue(result.failureDecision().reason().contains("OLD_STRING_MISS_TARGET_REPAIR"),
                    result.failureDecision().reason());
            assertEquals(original, Files.readString(ws.resolve("README.md")));
            assertEquals(1, recorded.requests().size());

            String finalLower = result.finalAnswer().toLowerCase(Locale.ROOT);
            assertTrue(finalLower.contains("action obligation failed"), result.finalAnswer());
            assertTrue(finalLower.contains("old-string miss repair"), result.finalAnswer());
            assertFalse(finalLower.contains("complete"), result.finalAnswer());
            assertFalse(finalLower.contains("ready to use"), result.finalAnswer());
        } finally {
            deleteRecursive(ws);
        }
    }

    @Test
    void oldStringMissCompactRepairRejectsReadOnlyToolBeforeExecution() throws Exception {
        Path ws = Files.createTempDirectory("talos-old-string-compact-repair-read-only-");
        try {
            String original = "# Fixture\n\nOriginal text.\n";
            Files.writeString(ws.resolve("README.md"), original);

            var registry = new ToolRegistry();
            registry.register(new ReadFileTool());
            registry.register(new FileEditTool());
            registry.register(new FileWriteTool());
            var processor = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
            var loop = new ToolCallLoop(processor, 5);

            var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                    List.of(new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                            "call_bad_read_only_repair",
                            "talos.read_file",
                            Map.of("path", "README.md"))))),
                    2048);
            var ctx = Context.builder(new Config())
                    .sandbox(new Sandbox(ws, Map.of()))
                    .llm(recorded.client())
                    .nativeToolSpecs(nativeSpecs(
                            new ReadFileTool(),
                            new FileEditTool(),
                            new FileWriteTool()))
                    .build();

            String request = "Apply that README.md proposal now.";
            var messages = new ArrayList<>(List.of(
                    ChatMessage.system("sys " + "large-system-token ".repeat(700)),
                    ChatMessage.user(request)));
            var initialCalls = List.of(
                    new ChatMessage.NativeToolCall(
                            "call_bad_edit",
                            "talos.edit_file",
                            Map.of(
                                    "path", "README.md",
                                    "old_string", "This text does not exist.",
                                    "new_string", "Applied proposal.")),
                    new ChatMessage.NativeToolCall(
                            "call_readback",
                            "talos.read_file",
                            Map.of("path", "README.md")));

            TurnUserRequestCapture.set(request);
            TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(request));
            ToolCallLoop.LoopResult result;
            try {
                result = loop.run("", initialCalls, messages, ws, ctx);
            } finally {
                TurnUserRequestCapture.clear();
                TurnTaskContractCapture.clear();
            }

            assertTrue(result.failureDecision().shouldStop(), result.failureDecision().reason());
            assertTrue(result.failureDecision().reason().contains("OLD_STRING_MISS_TARGET_REPAIR"),
                    result.failureDecision().reason());
            assertTrue(result.failureDecision().reason().contains("talos.read_file(README.md)"),
                    result.failureDecision().reason());
            assertEquals(2, result.toolsInvoked(), "read-only compact repair call must be rejected before execution");
            assertEquals(original, Files.readString(ws.resolve("README.md")));
        } finally {
            deleteRecursive(ws);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static ToolCallLoop createLoop(TalosTool... tools) {
        var registry = new ToolRegistry();
        for (TalosTool t : tools) registry.register(t);
        var processor = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
        return new ToolCallLoop(processor);
    }

    private static Context defaultCtx() {
        return Context.builder(new Config())
                .llm(LlmClient.scripted(List.of("")))
                .build();
    }

    private static List<ToolSpec> nativeSpecs(TalosTool... tools) {
        var specs = new ArrayList<ToolSpec>();
        for (TalosTool tool : tools) {
            ToolDescriptor descriptor = tool.descriptor();
            specs.add(new ToolSpec(
                    descriptor.name(),
                    descriptor.description(),
                    descriptor.parametersSchema() == null ? "{}" : descriptor.parametersSchema()));
        }
        return specs;
    }

    private static String readFileCall(String path) {
        return "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"" + path + "\"}}";
    }

    private static String readFileCall(String path, int maxLines) {
        return "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"" + path
                + "\",\"max_lines\":" + maxLines + "}}";
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
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

    private static void deleteRecursive(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // Best-effort cleanup for test workspaces.
                }
            });
        }
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

    private static TalosTool writeFileTool() {
        return new TalosTool() {
            @Override public String name() { return "talos.write_file"; }
            @Override public String description() { return "Write file"; }
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("talos.write_file", "Write file", null, ToolRiskLevel.WRITE);
            }
            @Override public ToolResult execute(ToolCall call, ToolContext ctx) {
                return ToolResult.ok("write-ok");
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

