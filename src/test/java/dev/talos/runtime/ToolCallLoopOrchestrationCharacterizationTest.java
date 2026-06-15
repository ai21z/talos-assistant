package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.TalosTool;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolDescriptor;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallLoopOrchestrationCharacterizationTest {
    private static final Path WORKSPACE = Path.of(".").toAbsolutePath().normalize();

    @Test
    void noToolCallsReturnZeroedLoopResultWithoutInventingToolExecution() {
        ToolCallLoop loop = createLoop(echoTool());
        List<ChatMessage> messages = baseMessages("Answer normally.");

        ToolCallLoop.LoopResult result = loop.run("No tools are needed.", messages, WORKSPACE, ctxWithResponses(""));

        assertAll(
                () -> assertEquals("No tools are needed.", result.finalAnswer()),
                () -> assertEquals(0, result.iterations()),
                () -> assertEquals(0, result.toolsInvoked()),
                () -> assertEquals(0, result.failedCalls()),
                () -> assertEquals(0, result.retriedCalls()),
                () -> assertFalse(result.hitIterLimit()),
                () -> assertEquals(List.of(), result.toolNames()),
                () -> assertEquals(List.of(), result.toolOutcomes()),
                () -> assertEquals(messages, result.messages()));
    }

    @Test
    void textToolCallPathPreservesParseExecuteRepromptFinalizationOrder() {
        ToolCallLoop loop = createLoop(echoTool());
        List<ChatMessage> messages = baseMessages("Echo T823 through the tool.");

        ToolCallLoop.LoopResult result = loop.run(
                textToolCall("T823-text"),
                messages,
                WORKSPACE,
                ctxWithResponses("Final answer after text echo."));

        assertAll(
                () -> assertEquals("Final answer after text echo.", result.finalAnswer()),
                () -> assertEquals(1, result.iterations()),
                () -> assertEquals(1, result.toolsInvoked()),
                () -> assertEquals(0, result.failedCalls()),
                () -> assertFalse(result.hitIterLimit()),
                () -> assertEquals(List.of("talos.echo"), result.toolNames()),
                () -> assertEquals(1, result.toolOutcomes().size()),
                () -> assertEquals("talos.echo", result.toolOutcomes().get(0).toolName()),
                () -> assertTrue(messages.stream().anyMatch(message ->
                        "assistant".equals(message.role())
                                && message.content().contains("\"name\":\"talos.echo\""))),
                () -> assertTrue(messages.stream().anyMatch(message ->
                        "user".equals(message.role())
                                && message.content().contains("[tool_result: talos.echo]")
                                && message.content().contains("echo: T823-text"))));
    }

    @Test
    void nativeToolCallPathSeedsLoopFromNativeCallsAndPreservesPublicResultShape() {
        ToolCallLoop loop = createLoop(echoTool());
        List<ChatMessage> messages = baseMessages("Echo T823 through native tool calling.");
        List<ChatMessage.NativeToolCall> nativeCalls = List.of(new ChatMessage.NativeToolCall(
                "call-t823",
                "talos.echo",
                Map.of("input", "T823-native")));

        ToolCallLoop.LoopResult result = loop.run(
                "",
                nativeCalls,
                messages,
                WORKSPACE,
                ctxWithResponses("Final answer after native echo."));

        assertAll(
                () -> assertEquals("Final answer after native echo.", result.finalAnswer()),
                () -> assertEquals(1, result.iterations()),
                () -> assertEquals(1, result.toolsInvoked()),
                () -> assertEquals(0, result.failedCalls()),
                () -> assertFalse(result.hitIterLimit()),
                () -> assertEquals(List.of("talos.echo"), result.toolNames()),
                () -> assertEquals(1, result.toolOutcomes().size()),
                () -> assertTrue(messages.stream().anyMatch(message ->
                        "assistant".equals(message.role()) && message.hasNativeToolCalls())),
                () -> assertTrue(messages.stream().anyMatch(message ->
                        "tool".equals(message.role())
                                && "call-t823".equals(message.toolCallId())
                                && message.content().contains("echo: T823-native"))));
    }

    @Test
    void iterationLimitSetsFlagAndAppliesFinalizerNotice() {
        ToolCallLoop loop = createLoopWithMaxIterations(2, echoTool());
        List<ChatMessage> messages = baseMessages("Keep echoing until the loop stops.");

        ToolCallLoop.LoopResult result = loop.run(
                textToolCall("first"),
                messages,
                WORKSPACE,
                ctxWithResponses(textToolCall("second"), textToolCall("third")));

        assertAll(
                () -> assertEquals(2, result.iterations()),
                () -> assertEquals(2, result.toolsInvoked()),
                () -> assertTrue(result.hitIterLimit()),
                () -> assertEquals(List.of("talos.echo", "talos.echo"), result.toolNames()),
                () -> assertTrue(result.finalAnswer().contains("Some tool calls were not executed"),
                        result.finalAnswer()),
                () -> assertFalse(result.finalAnswer().contains("<tool_call>"), result.finalAnswer()),
                () -> assertFalse(result.finalAnswer().contains("```json"), result.finalAnswer()));
    }

    @Test
    void reportPinsT824MoveStayBoundary() throws Exception {
        String report = Files.readString(Path.of(
                "work-cycle-docs/reports/t823-tool-call-loop-orchestration-characterization.md"));

        assertAll(
                () -> assertTrue(report.contains("ToolCallLoopEngine")),
                () -> assertTrue(report.contains("T823 does not authorize production extraction")),
                () -> assertTrue(report.contains("Move later in T824")),
                () -> assertTrue(report.contains("Keep in ToolCallLoop")),
                () -> assertTrue(report.contains("LoopResult")),
                () -> assertTrue(report.contains("ToolOutcome")));
    }

    private static List<ChatMessage> baseMessages(String userRequest) {
        return new ArrayList<>(List.of(
                ChatMessage.system("You are Talos."),
                ChatMessage.user(userRequest)));
    }

    private static Context ctxWithResponses(String... responses) {
        return Context.builder(new Config())
                .llm(LlmClient.scripted(List.of(responses)))
                .build();
    }

    private static ToolCallLoop createLoop(TalosTool... tools) {
        return createLoopWithMaxIterations(ToolCallLoop.DEFAULT_MAX_ITERATIONS, tools);
    }

    private static ToolCallLoop createLoopWithMaxIterations(int maxIterations, TalosTool... tools) {
        ToolRegistry registry = new ToolRegistry();
        for (TalosTool tool : tools) {
            registry.register(tool);
        }
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(),
                new NoOpApprovalGate(),
                registry);
        return new ToolCallLoop(processor, maxIterations);
    }

    private static TalosTool echoTool() {
        return new TalosTool() {
            @Override
            public String name() {
                return "talos.echo";
            }

            @Override
            public String description() {
                return "Echo tool";
            }

            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor("talos.echo", "Echo back the input");
            }

            @Override
            public ToolResult execute(ToolCall call, ToolContext ctx) {
                return ToolResult.ok("echo: " + call.param("input", ""));
            }
        };
    }

    private static String textToolCall(String input) {
        return """
                ```json
                {"name":"talos.echo","arguments":{"input":"%s"}}
                ```
                """.formatted(input);
    }
}
