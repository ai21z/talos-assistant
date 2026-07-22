package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class ToolRepromptChatExecutorTest {

    @Test
    void executeCopiesTextAndNativeToolCallsIntoState() {
        ChatMessage.NativeToolCall call = new ChatMessage.NativeToolCall(
                "call-1",
                "talos.write_file",
                Map.of("path", "README.md", "content", "# Updated\n"));
        LoopState state = state(ScriptedNativeLlmClient.of(List.of(
                new LlmClient.StreamResult("I will update README.md.", List.of(call)))));

        boolean continueLoop = ToolRepromptChatExecutor.execute(
                state,
                state.messages,
                tools(),
                ChatRequestControls.defaults(),
                "test reprompt");

        assertTrue(continueLoop);
        assertEquals("I will update README.md.", state.currentText);
        assertEquals(List.of(call), state.currentNativeCalls);
    }

    @Test
    void emptyResultUsesPendingMutationSummariesBeforeGenericFallback() {
        LoopState state = state(ScriptedNativeLlmClient.of(List.of(
                new LlmClient.StreamResult("", List.of()))));
        state.pendingMutationSummaries.add("[ok] Updated README.md");

        boolean continueLoop = ToolRepromptChatExecutor.execute(
                state,
                state.messages,
                tools(),
                ChatRequestControls.defaults(),
                "test reprompt");

        assertFalse(continueLoop);
        assertEquals("[ok] Updated README.md", state.currentText);
        assertTrue(state.currentNativeCalls.isEmpty());
    }

    @Test
    void pendingActionObligationBreachWinsBeforeGenericNoAnswerFallback() {
        LoopState state = state(ScriptedNativeLlmClient.of(List.of(
                new LlmClient.StreamResult("", List.of()))));
        state.setPendingActionObligation(PendingActionObligation.expectedTargets(List.of("README.md")));

        boolean continueLoop = ToolRepromptChatExecutor.execute(
                state,
                state.messages,
                tools(),
                ChatRequestControls.defaults(),
                "test reprompt");

        assertFalse(continueLoop);
        assertTrue(state.failureDecision.shouldStop());
        assertTrue(state.failureDecision.reason().contains("EXPECTED_TARGETS_REMAINING"),
                state.failureDecision.reason());
        assertTrue(state.currentText.contains("[Action obligation failed: pending expected target progress"),
                state.currentText);
        assertTrue(state.currentNativeCalls.isEmpty());
    }

    @Test
    void modelNotFoundPropagatesWithoutManufacturingAssistantText() {
        EngineException.ModelNotFound missing = new EngineException.ModelNotFound("missing-model");
        LoopState state = state(LlmClient.scriptedFailure(missing));

        EngineException.ModelNotFound thrown = assertThrows(
                EngineException.ModelNotFound.class,
                () -> ToolRepromptChatExecutor.execute(
                        state,
                        state.messages,
                        tools(),
                        ChatRequestControls.defaults(),
                        "test reprompt"));

        assertSame(missing, thrown);
        assertTrue(state.currentText.isEmpty());
        assertTrue(state.currentNativeCalls.isEmpty());
    }

    @Test
    void connectionFailurePropagatesWithoutManufacturingAssistantText() {
        EngineException.ConnectionFailed failure = new EngineException.ConnectionFailed(
                "http://127.0.0.1:18115",
                new ConnectException("connection refused"));
        LoopState state = state(LlmClient.scriptedFailure(failure));

        EngineException.ConnectionFailed thrown = assertThrows(
                EngineException.ConnectionFailed.class,
                () -> ToolRepromptChatExecutor.execute(
                        state,
                        state.messages,
                        tools(),
                        ChatRequestControls.defaults(),
                        "test reprompt"));

        assertSame(failure, thrown);
        assertTrue(state.currentText.isEmpty());
        assertTrue(state.currentNativeCalls.isEmpty());
    }

    @Test
    void directCancellationPropagatesWithoutManufacturingAssistantText() {
        CancellationException cancellation = new CancellationException("stop reprompt");
        LoopState state = state(LlmClient.scriptedFailure(cancellation));

        CancellationException thrown = assertThrows(
                CancellationException.class,
                () -> ToolRepromptChatExecutor.execute(
                        state,
                        state.messages,
                        tools(),
                        ChatRequestControls.defaults(),
                        "test reprompt"));

        assertSame(cancellation, thrown);
        assertTrue(state.currentText.isEmpty());
        assertTrue(state.currentNativeCalls.isEmpty());
    }

    @Test
    void genericWrappedCancellationPropagatesWithoutManufacturingAssistantText() {
        RuntimeException wrapped = new RuntimeException(
                new CancellationException("stop reprompt"));
        LoopState state = state(LlmClient.scriptedFailure(wrapped));

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> ToolRepromptChatExecutor.execute(
                        state,
                        state.messages,
                        tools(),
                        ChatRequestControls.defaults(),
                        "test reprompt"));

        assertSame(wrapped, thrown);
        assertTrue(state.currentText.isEmpty());
        assertTrue(state.currentNativeCalls.isEmpty());
    }

    @Test
    void genericWrappedInterruptionPropagatesAndRestoresInterruptFlag() {
        RuntimeException wrapped = new RuntimeException(
                new InterruptedException("stop reprompt"));
        LoopState state = state(LlmClient.scriptedFailure(wrapped));

        try {
            RuntimeException thrown = assertThrows(
                    RuntimeException.class,
                    () -> ToolRepromptChatExecutor.execute(
                            state,
                            state.messages,
                            tools(),
                            ChatRequestControls.defaults(),
                            "test reprompt"));

            assertSame(wrapped, thrown);
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(state.currentText.isEmpty());
            assertTrue(state.currentNativeCalls.isEmpty());
        } finally {
            Thread.interrupted();
        }
    }

    private static LoopState state(LlmClient llm) {
        List<ToolSpec> tools = tools();
        Context ctx = Context.builder(new Config())
                .llm(llm)
                .nativeToolSpecs(tools)
                .build();
        return new LoopState(
                "",
                List.of(),
                new ArrayList<>(List.of(
                        ChatMessage.system("sys"),
                        ChatMessage.user("Update README.md."))),
                Path.of("."),
                ctx,
                null,
                5,
                0);
    }

    private static List<ToolSpec> tools() {
        return List.of(
                new ToolSpec("talos.read_file", "Read", "{}"),
                new ToolSpec("talos.write_file", "Write", "{}"),
                new ToolSpec("talos.edit_file", "Edit", "{}"));
    }
}
