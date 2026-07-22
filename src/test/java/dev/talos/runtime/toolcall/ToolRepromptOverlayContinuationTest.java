package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ToolSpec;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolRepromptOverlayContinuationTest {

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
                tools());

        assertTrue(continueLoop);
        assertEquals("Reprompt answer.", state.currentText);
        assertEquals(1, recorded.requests().size());
        ChatRequest request = recorded.requests().getFirst();
        String payload = messageContents(request);
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
        return new LoopState(
                "",
                List.of(),
                new ArrayList<>(List.of(
                        ChatMessage.system("sys"),
                        ChatMessage.user("Create index.html, styles.css, and scripts.js."))),
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
}
