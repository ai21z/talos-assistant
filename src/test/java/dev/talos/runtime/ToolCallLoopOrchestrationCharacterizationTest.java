package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.util.Sanitize;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.TurnTraceEvent;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ObservedProviderTransportFixture;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.TalosTool;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContentMetadata;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolDescriptor;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallLoopOrchestrationCharacterizationTest {
    private static final Path WORKSPACE = Path.of(".").toAbsolutePath().normalize();

    @AfterEach
    void clearTrace() {
        TurnTaskContractCapture.clear();
        LocalTurnTraceCapture.clear();
    }

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

        ToolCallLoop.LoopResult result = runCapturedRuntime(
                loop,
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

        ToolCallLoop.LoopResult result = runCapturedRuntime(
                loop,
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
        beginTrace("trc-loop-iteration-limit");
        ToolCallLoop loop = createLoopWithMaxIterations(2, echoTool());
        List<ChatMessage> messages = baseMessages("Keep echoing until the loop stops.");
        Context context = Context.builder(new Config())
                .llm(ScriptedNativeLlmClient.textToolFallbackStrings(List.of(
                        textToolCall("second"),
                        textToolCall("third"))))
                .nativeToolSpecs(List.of(new ToolSpec(
                        "talos.echo",
                        "Echo the supplied input.",
                        "{\"type\":\"object\"}")))
                .build();

        ToolCallLoop.LoopResult result = runCapturedProviderText(
                loop,
                textToolCall("first"),
                List.of("talos.echo"),
                messages,
                WORKSPACE,
                context);

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
    void unresolvedContinuationFallbackSelectsExactRuntimeAnswerCandidate() {
        beginTrace("trc-loop-unresolved-fallback");
        ToolCallLoop loop = createLoop(echoTool());
        List<ChatMessage> messages = baseMessages("Echo once, then stop truthfully if continuation is malformed.");

        ToolCallLoop.LoopResult result = runCapturedRuntime(
                loop,
                textToolCall("first"),
                messages,
                WORKSPACE,
                ctxWithResponses("""
                        {
                          "name": "talos.echo",
                          "arguments": {
                        """));

        assertEquals(
                "[Tool-call continuation could not be completed. No further tool calls were executed.]",
                result.finalAnswer());
        LocalTurnTrace trace = routeAndComplete(result);
        TurnTraceEvent terminal = event(trace, "TERMINAL_ANSWER_SELECTED");
        assertEquals(hash(result.finalAnswer()), terminal.data().get("answerHash"));
        assertEquals("RUNTIME_DETERMINISTIC", terminal.data().get("origin"),
                trace.events().toString());
        assertTrue(events(trace, "DETERMINISTIC_ANSWER_SELECTED").stream().anyMatch(candidate ->
                hash(result.finalAnswer()).equals(candidate.data().get("answerHash"))
                        && "RUNTIME_DETERMINISTIC".equals(candidate.data().get("origin"))));
    }

    @Test
    void iterationLimitRecordsOneNamedChangeAndDoesNotInventNoOpFinalization() {
        beginTrace("trc-loop-iteration-finalization");
        ToolCallLoop loop = createLoopWithMaxIterations(1, echoTool());
        List<ChatMessage> messages = baseMessages("Keep echoing until the deterministic limit stops the loop.");
        String providerContinuation = """
                Provider preface. <script>removeMe()</script>
                ```json
                {"name":"talos.echo","arguments":{"input":"second"}}
                ```
                """;
        String selectedProviderContinuation =
                Sanitize.sanitizeForOutputPreservingToolCalls(
                        providerContinuation);
        Context context = Context.builder(new Config())
                .llm(ScriptedNativeLlmClient.textToolFallbackOf(List.of(
                        new LlmClient.StreamResult(providerContinuation, List.of(), "stop"))))
                .nativeToolSpecs(List.of(new ToolSpec(
                        "talos.echo",
                        "Echo the supplied input.",
                        "{\"type\":\"object\"}")))
                .build();

        ToolCallLoop.LoopResult result = runCapturedProviderText(
                loop,
                textToolCall("first"),
                List.of("talos.echo"),
                messages,
                WORKSPACE,
                context);

        assertTrue(result.hitIterLimit());
        assertFalse(result.finalAnswer().contains("removeMe"), result.finalAnswer());
        LocalTurnTrace trace = routeAndComplete(result);
        List<TurnTraceEvent> transformed = events(trace, "ANSWER_TRANSFORMED");
        assertEquals(1, transformed.size(), trace.events().toString());
        TurnTraceEvent iterationLimit = transformed.get(0);
        assertEquals("TOOL_LOOP_ITERATION_LIMIT",
                iterationLimit.data().get("reasonCode"));
        assertEquals(
                hash(selectedProviderContinuation),
                iterationLimit.data().get("fromAnswerHash"));
        assertEquals(
                1,
                events(trace, "GENERATION_RESPONSE_CAPTURED").stream()
                        .filter(captured -> hash(selectedProviderContinuation)
                                .equals(captured.data().get("assistantHash")))
                        .count(),
                trace.events().toString());
        assertEquals(hash(result.finalAnswer()),
                iterationLimit.data().get("answerHash"));
        assertEquals(hash(result.finalAnswer()),
                event(trace, "TERMINAL_ANSWER_SELECTED").data().get("answerHash"));
    }

    @Test
    void acceptedProviderFollowupRetainsExactSourceThroughFinalization() {
        beginTrace("trc-loop-provider-finalization");
        ToolCallLoop loop = createLoop(withheldCommandOutputTool());
        List<ChatMessage> messages = baseMessages(
                "Inspect the bounded command output and synthesize without exposing withheld bytes.");
        String providerAnswer = "Provider follow-up after withheld evidence.";
        Context context = Context.builder(new Config())
                .llm(ScriptedNativeLlmClient.of(List.of(
                        new LlmClient.StreamResult(providerAnswer, List.of(), "stop"))))
                .build();

        ToolCallLoop.LoopResult result = runCapturedRuntime(
                loop,
                withheldCommandToolCall(), messages, WORKSPACE, context);

        assertTrue(result.finalAnswer().contains(providerAnswer), result.finalAnswer());
        assertTrue(result.finalAnswer().contains("withheld from model context"),
                result.finalAnswer());
        LocalTurnTrace trace = routeAndComplete(result);
        List<TurnTraceEvent> transformed = events(trace, "ANSWER_TRANSFORMED");
        assertEquals(1, transformed.size(), trace.events().toString());
        assertEquals("TOOL_LOOP_FINALIZATION",
                transformed.getFirst().data().get("reasonCode"));
        assertEquals(hash(providerAnswer),
                transformed.getFirst().data().get("fromAnswerHash"));
        assertEquals(hash(result.finalAnswer()),
                transformed.getFirst().data().get("answerHash"));

        TurnTraceEvent completed = events(trace, "GENERATION_COMPLETED").stream()
                .filter(candidate -> "FOREGROUND_PROVIDER".equals(candidate.data().get("source")))
                .findFirst()
                .orElseThrow();
        TurnTraceEvent terminal = event(trace, "TERMINAL_ANSWER_SELECTED");
        assertEquals("MODEL_PROVIDER", terminal.data().get("origin"));
        assertEquals(completed.data().get("generationId"),
                terminal.data().get("sourceGenerationId"));
        assertEquals(completed.data().get("selectedAttemptId"),
                terminal.data().get("sourceAttemptId"));
        assertEquals(hash(result.finalAnswer()), terminal.data().get("answerHash"));
    }

    private static List<ChatMessage> baseMessages(String userRequest) {
        return new ArrayList<>(List.of(
                ChatMessage.system("You are Talos."),
                ChatMessage.user(userRequest)));
    }

    private static ToolCallLoop.LoopResult runCapturedRuntime(
            ToolCallLoop loop,
            String initialAnswer,
            List<ChatMessage> messages,
            Path workspace,
            RuntimeTurnContext ctx
    ) {
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromMessages(messages),
                messages);
        try {
            return loop.run(initialAnswer, messages, workspace, ctx);
        } finally {
            TurnTaskContractCapture.clear();
        }
    }

    private static ToolCallLoop.LoopResult runCapturedRuntime(
            ToolCallLoop loop,
            String initialAnswer,
            List<ChatMessage.NativeToolCall> nativeToolCalls,
            List<ChatMessage> messages,
            Path workspace,
            RuntimeTurnContext ctx
    ) {
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromMessages(messages),
                messages);
        try {
            return loop.run(
                    initialAnswer,
                    nativeToolCalls,
                    messages,
                    workspace,
                    ctx);
        } finally {
            TurnTaskContractCapture.clear();
        }
    }

    private static ToolCallLoop.LoopResult runCapturedProviderText(
            ToolCallLoop loop,
            String initialAnswer,
            List<String> selectedToolNames,
            List<ChatMessage> messages,
            Path workspace,
            RuntimeTurnContext ctx
    ) {
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromMessages(messages),
                messages);
        try {
            ProviderResponseCapture.Generation generation =
                    ProviderResponseCapture.openGeneration(
                            ProviderResponseCapture.Source
                                    .FOREGROUND_PROVIDER,
                            ProviderResponseCapture.Purpose.TURN_RESPONSE,
                            ProviderResponseCapture.ToolAuthorityContract
                                    .bound(
                                            ProviderResponseCapture
                                                    .ToolAuthorityMode
                                                    .PROVIDER_TEXT_JSON,
                                            selectedToolNames,
                                            ToolChoiceMode.AUTO,
                                            ""));
            ProviderResponseCapture.Attempt attempt =
                    generation.startAttempt(
                            ProviderResponseCapture.AttemptKind.PRIMARY,
                            ProviderResponseCapture.RetryReason.NONE);
            ObservedProviderTransportFixture.completeTerminal(
                    attempt,
                    new ProviderTransportHandoff.DispatchEvidence(
                            "sha256:" + "d".repeat(64),
                            List.of()));
            ProviderResponseCapture.Receipt receipt =
                    attempt.respond(
                            new ProviderResponseCapture.Response(
                                    initialAnswer,
                                    List.of(),
                                    ProviderResponseCapture.Source
                                            .FOREGROUND_PROVIDER,
                                    ProviderResponseCapture.Completion
                                            .PROVIDER_EOS,
                                    false,
                                    false,
                                    true));
            generation.complete(receipt);
            return loop.run(
                    initialAnswer,
                    List.of(),
                    receipt,
                    messages,
                    workspace,
                    ctx);
        } finally {
            TurnTaskContractCapture.clear();
        }
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

    private static TalosTool withheldCommandOutputTool() {
        return new TalosTool() {
            @Override
            public String name() {
                return "talos.withheld_command";
            }

            @Override
            public String description() {
                return "Return command output that policy withholds from model context";
            }

            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor(
                        "talos.withheld_command",
                        "Return bounded command output with no model handoff");
            }

            @Override
            public ToolResult execute(ToolCall call, ToolContext ctx) {
                return ToolResult.ok(
                        "bounded private stdout",
                        ToolContentMetadata.commandOutput(
                                "test-profile",
                                false,
                                "test policy withholds command bytes"));
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

    private static String withheldCommandToolCall() {
        return """
                ```json
                {"name":"talos.withheld_command","arguments":{}}
                ```
                """;
    }

    private static void beginTrace(String traceId) {
        LocalTurnTraceCapture.begin(
                traceId,
                "session-tool-loop-lineage",
                1,
                "2026-07-16T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                "test-model",
                "verify tool-loop answer lineage");
    }

    private static LocalTurnTrace routeAndComplete(ToolCallLoop.LoopResult result) {
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(result.finalAnswer());
        return LocalTurnTraceCapture.complete();
    }

    private static TurnTraceEvent event(LocalTurnTrace trace, String type) {
        return events(trace, type).getFirst();
    }

    private static List<TurnTraceEvent> events(LocalTurnTrace trace, String type) {
        return trace.events().stream()
                .filter(candidate -> type.equals(candidate.type()))
                .toList();
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception unavailable) {
            throw new AssertionError(unavailable);
        }
    }
}
