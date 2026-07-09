package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.TalosTool;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolDescriptor;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolResult;
import dev.talos.tools.ToolRiskLevel;
import dev.talos.tools.impl.FileWriteTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AssistantTurnExecutor model dispatch characterization")
class AssistantTurnExecutorModelDispatchCharacterizationTest {

    @Test
    void nonStreamingMutationDispatchForwardsRequiredToolChoiceToProvider(@TempDir Path workspace) {
        ToolSpec writeFile = toolSpec("talos.write_file");
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("I updated result.txt.", List.of())),
                4096);
        var ctx = Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .nativeToolSpecs(List.of(writeFile))
                .build();

        AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                messages("Overwrite result.txt with exactly AFTER. Use talos.write_file."),
                workspace,
                ctx,
                new AssistantTurnExecutor.Options());

        assertFalse(out.streamed(), "mutation dispatch must stay on the buffered provider path");
        ChatRequest request = firstRequest(recorded);
        assertEquals(List.of("talos.write_file"), request.tools.stream().map(ToolSpec::name).toList());
        assertEquals(ToolChoiceMode.REQUIRED, request.controls.toolChoice());
        assertEquals(1024, request.controls.maxOutputTokens());
    }

    @Test
    void missingMutationEscalatedRetryUsesZeroTemperatureSampling(@TempDir Path workspace) {
        var registry = new ToolRegistry();
        registry.register(new FileWriteTool());
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(
                        new LlmClient.StreamResult("The file has been updated.", List.of()),
                        new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                                "call_write",
                                "talos.write_file",
                                Map.of("path", "result.txt", "content", "AFTER")))),
                        new LlmClient.StreamResult("Updated result.txt.", List.of())),
                4096);
        var ctx = Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(new ToolCallLoop(new TurnProcessor(null, new NoOpApprovalGate(), registry), 3))
                .nativeToolSpecs(specsFrom(registry))
                .build();

        AssistantTurnExecutor.execute(
                messages("Overwrite result.txt with exactly AFTER. Use talos.write_file."),
                workspace,
                ctx,
                new AssistantTurnExecutor.Options());

        assertTrue(recorded.requests().size() >= 2, "missing mutation should dispatch an escalated retry");
        assertTrue(recorded.requests().stream().anyMatch(request ->
                        request.controls.sampling().temperature() != null
                                && request.controls.sampling().temperature() == 0.0),
                "escalated missing-mutation retry must dispatch with temperature 0.0");
    }

    @Test
    void streamingAndBufferedNoToolDispatchCharacterizesFinalAnswerShape(@TempDir Path workspace) {
        String answer = "Dispatch OK.";
        var streamed = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult(answer, List.of())),
                4096);
        var buffered = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult(answer, List.of())),
                4096);
        var chunks = new ArrayList<String>();

        AssistantTurnExecutor.TurnOutput streamingOut = AssistantTurnExecutor.execute(
                messages("Answer briefly with exactly Dispatch OK."),
                workspace,
                Context.builder(new Config())
                        .llm(streamed.client())
                        .sandbox(new Sandbox(workspace, Map.of()))
                        .streamSink(chunks::add)
                        .onStreamComplete(() -> {})
                        .build(),
                new AssistantTurnExecutor.Options());
        AssistantTurnExecutor.TurnOutput bufferedOut = AssistantTurnExecutor.execute(
                messages("Answer briefly with exactly Dispatch OK."),
                workspace,
                Context.builder(new Config())
                        .llm(buffered.client())
                        .sandbox(new Sandbox(workspace, Map.of()))
                        .build(),
                new AssistantTurnExecutor.Options());

        assertTrue(streamingOut.streamed(), "stream sink should select the streaming provider path");
        assertFalse(bufferedOut.streamed(), "missing stream sink should select the buffered provider path");
        assertEquals(bufferedOut.text(), streamingOut.text());
        assertEquals(answer, streamingOut.text());
    }

    @Test
    void toolOnlyStreamingResponseInvokesOnStreamCompleteOnceBeforeToolLoop(@TempDir Path workspace) {
        AtomicInteger completions = new AtomicInteger();
        AtomicInteger completionCountAtToolExecution = new AtomicInteger(-1);
        AtomicInteger toolExecutions = new AtomicInteger();
        var registry = new ToolRegistry();
        registry.register(new CompletionAwareReadFileTool(completions, completionCountAtToolExecution, toolExecutions));
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(
                        new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                                "call_read",
                                "talos.read_file",
                                Map.of("path", "notes.txt")))),
                        new LlmClient.StreamResult("Read complete.", List.of())),
                4096);
        var chunks = new ArrayList<String>();
        var ctx = Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(new ToolCallLoop(new TurnProcessor(null, new NoOpApprovalGate(), registry), 3))
                .nativeToolSpecs(specsFrom(registry))
                .streamSink(chunks::add)
                .onStreamComplete(completions::incrementAndGet)
                .build();

        AssistantTurnExecutor.execute(
                messages("Read notes.txt and summarize it."),
                workspace,
                ctx,
                new AssistantTurnExecutor.Options());

        assertEquals(1, completions.get(), "tool-only streaming response should complete the stream once");
        assertEquals(1, toolExecutions.get(), "native tool call should enter the tool loop");
        assertEquals(1, completionCountAtToolExecution.get(),
                "stream completion should happen before tool execution starts");
        assertEquals(512, firstRequest(recorded).controls.maxOutputTokens());
    }

    @Test
    void lengthLimitedNoToolAnswerAddsVisibleWarningAndTrace(@TempDir Path workspace) {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("Partial answer", List.of(), "length")),
                4096);
        var ctx = Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .build();

        LocalTurnTraceCapture.begin(
                "trc-t989-length",
                "sid",
                1,
                "2026-07-09T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                "test-model",
                "Explain briefly.");
        try {
            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages("Explain briefly."),
                    workspace,
                    ctx,
                    new AssistantTurnExecutor.Options());
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertTrue(out.text().contains("Partial answer"), out.text());
            assertTrue(out.text().contains("output limit"), out.text());
            assertTrue(trace.warnings().stream().anyMatch(warning ->
                    "LLM_OUTPUT_LIMIT_REACHED".equals(warning.code())
                            && warning.message().contains("finish_reason=length")));
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void bufferedAbortMarkerRecordsFailedOutcome(@TempDir Path workspace) {
        var blocking = ScriptedNativeLlmClient.blockingAfterFirstChunk(5_000L);
        var ctx = Context.builder(new Config())
                .llm(blocking.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .build();

        LocalTurnTraceCapture.begin(
                "trc-t988-abort",
                "sid",
                1,
                "2026-07-09T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                "test-model",
                "Answer slowly.");
        try {
            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages("Answer slowly."),
                    workspace,
                    ctx,
                    new AssistantTurnExecutor.Options().llmTimeoutMs(150L));
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertTrue(out.text().contains("[turn aborted"), out.text());
            assertEquals("FAILED", trace.outcome().status());
            assertEquals("LLM_ABORTED", trace.outcome().classification());
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    private static List<ChatMessage> messages(String request) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("You are Talos."));
        messages.add(ChatMessage.user(request));
        return messages;
    }

    private static ToolSpec toolSpec(String name) {
        return new ToolSpec(
                name,
                "Characterization test tool",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}}}");
    }

    private static List<ToolSpec> specsFrom(ToolRegistry registry) {
        return registry.descriptors().stream()
                .map(descriptor -> new ToolSpec(
                        descriptor.name(),
                        descriptor.description(),
                        descriptor.parametersSchema()))
                .toList();
    }

    private static ChatRequest firstRequest(ScriptedNativeLlmClient.RecordedClient recorded) {
        assertFalse(recorded.requests().isEmpty(), "provider request should be recorded");
        return recorded.requests().getFirst();
    }

    private record CompletionAwareReadFileTool(
            AtomicInteger completions,
            AtomicInteger completionCountAtToolExecution,
            AtomicInteger toolExecutions
    ) implements TalosTool {
        @Override
        public String name() {
            return "talos.read_file";
        }

        @Override
        public String description() {
            return "Read a file for model-dispatch characterization.";
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    name(),
                    description(),
                    "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}",
                    ToolRiskLevel.READ_ONLY);
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext ctx) {
            completionCountAtToolExecution.set(completions.get());
            toolExecutions.incrementAndGet();
            return ToolResult.ok("notes.txt contents");
        }
    }
}
