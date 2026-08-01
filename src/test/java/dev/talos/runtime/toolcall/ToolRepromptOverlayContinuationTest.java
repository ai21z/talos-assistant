package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.PromptToolDescriptor;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.llm.SystemPromptBuilder;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.TurnExecutionStage;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.CurrentTurnPromptInstructions;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ToolSpec;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolRepromptOverlayContinuationTest {

    @AfterEach
    void clearTurnCapture() {
        TurnTaskContractCapture.clear();
    }

    @Test
    void overlayContinuationOwnsOverlayExecutionAndRetryMechanics() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolRepromptOverlayContinuation.java"));

        assertTrue(source.contains("ToolRepromptMessageOverlay.apply("), source);
        assertTrue(source.contains("ToolRepromptChatExecutor.executeResult("), source);
        assertTrue(source.contains("ToolRepromptChatExecutor.executeRetryResult("), source);
        assertTrue(source.contains("\"tool-call loop continuation\""), source);
        assertTrue(source.contains("\"transient retry continuation\""), source);
        assertTrue(source.contains("Thread.sleep(400)"), source);
    }

    @Test
    void successfulOverlayRequestSnapshotsTemporaryMessagesAndCleansDurableHistory() {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("Reprompt answer.", List.of())),
                16_384);
        LoopState state = state(recorded.client());

        boolean continueLoop = ToolRepromptOverlayContinuation.execute(
                state,
                List.of(),
                List.of("scripts.js"),
                "Create index.html, styles.css, and scripts.js.",
                false,
                mutationTools());

        assertTrue(continueLoop);
        assertEquals("Reprompt answer.", state.currentText);
        assertEquals(1, recorded.requests().size());
        ChatRequest request = recorded.requests().getFirst();
        String payload = messageContents(request);
        assertEquals(
                List.of("talos.write_file", "talos.edit_file"),
                request.tools.stream().map(ToolSpec::name).toList());
        assertFalse(payload.contains("talos.read_file"), payload);
        assertTrue(payload.contains("talos.write_file"), payload);
        assertTrue(payload.contains("talos.edit_file"), payload);
        assertTrue(payload.contains(
                "visibleTools: talos.write_file, talos.edit_file"), payload);
        assertTrue(payload.contains("[Expected target progress]"), payload);
        assertTrue(payload.contains("[Current task - stay focused on this]"), payload);
        assertTrue(request.messages.stream()
                        .filter(message -> "user".equals(message.role()))
                        .map(ChatMessage::content)
                        .anyMatch(content -> content != null
                                && content.startsWith("[Current task - stay focused on this]")),
                "the continuation reminder must retain user authority");
        assertFalse(request.messages.stream()
                        .filter(message -> "system".equals(message.role()))
                        .map(ChatMessage::content)
                        .anyMatch(content -> content != null
                                && content.contains("Create index.html, styles.css, and scripts.js.")),
                "raw user task bytes must not gain system authority");
        assertFalse(state.messages.stream()
                        .map(ChatMessage::content)
                        .filter(content -> content != null)
                        .anyMatch(content -> content.startsWith("[Expected target progress]")
                                || content.startsWith("[Current task")),
                "temporary overlay messages must be removed from durable loop history");
        assertTrue(
                messageContents(state.messages).contains("talos.read_file"),
                "the bounded request copy must not mutate durable history");
    }

    @Test
    void missingRequiredRetryToolFailsBeforeProviderDispatch() {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult(
                        "must not be dispatched",
                        List.of())),
                16_384);
        LoopState state = state(recorded.client());
        state.setPendingActionObligation(
                PendingActionObligation.expectedTargets(
                        List.of("scripts.js")));

        boolean continueLoop = ToolRepromptOverlayContinuation.execute(
                state,
                List.of(),
                List.of("scripts.js"),
                "Create scripts.js.",
                false,
                List.of());

        assertFalse(continueLoop);
        assertTrue(recorded.requests().isEmpty());
        assertTrue(state.failureDecision.shouldStop());
        assertFalse(state.hasPendingActionObligation());
        assertTrue(
                state.failureDecision.reason().contains(
                        "required retry tool is unavailable"),
                state.failureDecision.reason());
    }

    @Test
    void missingCapturedResolutionFailsBeforeProviderDispatchOrStageMutation() {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult(
                        "must not be dispatched",
                        List.of())),
                16_384);
        LoopState state = state(recorded.client());
        TurnTaskContractCapture.clear();
        LocalTurnTraceCapture.begin(
                "trace-missing-captured-resolution",
                "sid",
                1,
                "2026-07-30T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                "test-model",
                "Create scripts.js.");
        try {
            LocalTurnTraceCapture.enterExecutionStage(
                    TurnExecutionStage.VERIFICATION);

            assertThrows(
                    IllegalStateException.class,
                    () -> ToolRepromptOverlayContinuation.execute(
                            state,
                            List.of(),
                            List.of("scripts.js"),
                            "Create scripts.js.",
                            false,
                            mutationTools()));

            assertTrue(recorded.requests().isEmpty());
            assertEquals(
                    TurnExecutionStage.VERIFICATION,
                    LocalTurnTraceCapture.currentExecutionStage());
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void connectionFailurePropagatesWithoutManufacturingAssistantText() {
        EngineException.ConnectionFailed failure = new EngineException.ConnectionFailed(
                "http://127.0.0.1:18115",
                new ConnectException("connection refused"));
        LoopState state = state(LlmClient.scriptedFailure(failure));

        EngineException.ConnectionFailed thrown = assertThrows(
                EngineException.ConnectionFailed.class,
                () -> ToolRepromptOverlayContinuation.execute(
                        state,
                        List.of(),
                        List.of("scripts.js"),
                        "Create index.html, styles.css, and scripts.js.",
                        false,
                        tools()));

        assertSame(failure, thrown);
        assertTrue(state.currentText.isEmpty());
    }

    @Test
    void runtimeThreadInterruptionDoesNotInventOperatorAuthorship() {
        EngineException.Transient failure = new EngineException.Transient(
                "transient test failure",
                new RuntimeException("fixture"),
                503);
        LoopState state = state(LlmClient.scriptedFailure(failure));
        LocalTurnTraceCapture.begin(
                "trace-runtime-thread-interrupt",
                "sid",
                1,
                "2026-07-16T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                "test-model",
                "exercise retry interruption");

        try {
            Thread.currentThread().interrupt();
            RuntimeException thrown = assertThrows(
                    RuntimeException.class,
                    () -> ToolRepromptOverlayContinuation.execute(
                            state,
                            List.of(),
                            List.of("scripts.js"),
                            "Create scripts.js.",
                            false,
                            tools()));

            assertInstanceOf(InterruptedException.class, thrown.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(state.currentText.isEmpty());
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();
            assertTrue(trace.events().stream()
                    .noneMatch(event -> "TERMINAL_ANSWER_SELECTED".equals(event.type())
                            || "DETERMINISTIC_ANSWER_SELECTED".equals(event.type())));
        } finally {
            Thread.interrupted();
            LocalTurnTraceCapture.clear();
        }
    }

    private static LoopState state(LlmClient llm) {
        List<ToolSpec> tools = tools();
        llm.setToolSpecs(tools);
        Context ctx = Context.builder(new Config())
                .llm(llm)
                .nativeToolSpecs(tools)
                .build();
        String userRequest =
                "Create index.html, styles.css, and scripts.js.";
        var resolution =
                TaskContractResolver.resolveFromUserRequest(userRequest);
        TurnTaskContractCapture.set(resolution);
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                resolution.contract(),
                ExecutionPhase.APPLY,
                tools.stream().map(ToolSpec::name).toList(),
                tools.stream().map(ToolSpec::name).toList(),
                List.of());
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system(systemPrompt(tools)),
                ChatMessage.user(userRequest)));
        CurrentTurnPromptInstructions.replaceTaskContractInstruction(
                messages,
                plan);
        return new LoopState(
                "",
                List.of(),
                messages,
                Path.of("."),
                ctx,
                null,
                10,
                0);
    }

    private static List<ToolSpec> tools() {
        return List.of(
                tool("talos.read_file"),
                tool("talos.write_file"),
                tool("talos.edit_file"));
    }

    private static List<ToolSpec> mutationTools() {
        return List.of(
                tool("talos.write_file"),
                tool("talos.edit_file"));
    }

    private static ToolSpec tool(String name) {
        return new ToolSpec(name, name, "{}");
    }

    private static String messageContents(ChatRequest request) {
        if (request == null || request.messages == null) return "";
        return request.messages.stream()
                .map(ChatMessage::content)
                .filter(content -> content != null)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private static String messageContents(List<ChatMessage> messages) {
        return messages.stream()
                .map(ChatMessage::content)
                .filter(content -> content != null)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private static String systemPrompt(List<ToolSpec> tools) {
        return SystemPromptBuilder.forUnified()
                .withNativeTools(true)
                .withPromptTools(tools.stream()
                        .map(tool -> new PromptToolDescriptor(
                                tool.name(),
                                tool.description(),
                                tool.parametersSchemaJson(),
                                false))
                        .toList())
                .withVisibleToolNames(
                        tools.stream().map(ToolSpec::name).toList())
                .build();
    }
}
