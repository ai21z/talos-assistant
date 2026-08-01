package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TurnModelDispatcherTest {

    @AfterEach
    void clearPromptCapture() {
        PromptDebugCapture.clear();
    }

    @Test
    void initialAndRetryDispatchUseTheSameLfCanonicalTransportBytes() {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(
                        new LlmClient.StreamResult("first", List.of()),
                        new LlmClient.StreamResult("second", List.of())),
                16_384);
        ToolSpec read = new ToolSpec(
                "talos.read_file",
                "Read one file",
                "{\"type\":\"object\"}");
        Context ctx = Context.builder(new Config())
                .llm(recorded.client())
                .nativeToolSpecs(List.of(read))
                .build();
        TaskContract contract = new TaskContract(
                TaskType.WORKSPACE_EXPLAIN,
                false,
                false,
                false,
                Set.of(),
                Set.of(),
                "line one\r\nline two");
        CurrentTurnPlan plan = CurrentTurnPlan.compatibility(
                contract,
                ExecutionPhase.INSPECT,
                List.of("talos.read_file"),
                List.of(),
                List.of());
        ChatMessage.NativeToolCall call = new ChatMessage.NativeToolCall(
                "call-1",
                "talos.read_file",
                Map.of("path", "notes.txt"));
        List<ChatMessage> messages = List.of(
                ChatMessage.system("base\u0000\u001b[31m\r\nsystem"),
                ChatMessage.user("line one\r\nline two"),
                ChatMessage.assistantWithToolCalls("", List.of(call)),
                ChatMessage.toolResult(
                        "call-1",
                        "progress\rremaining\u0000\u001b[31m"));

        PromptDebugCapture.clear();

        TurnModelDispatcher.dispatchBuffered(ctx, messages, plan);
        TurnModelDispatcher.dispatchBufferedRetry(
                ctx,
                messages,
                plan,
                RetryDispatchPurpose.INSPECT_COMPLETENESS);

        assertEquals(2, recorded.requests().size());
        assertEquals(2, PromptDebugCapture.history().size());
        for (int index = 0; index < recorded.requests().size(); index++) {
            var request = recorded.requests().get(index);
            var promptSnapshot = PromptDebugCapture.history().get(index);
            assertEquals(request.messages, promptSnapshot.messages());
            assertEquals(request.tools, promptSnapshot.tools());
            assertEquals(request.controls, promptSnapshot.controls());
            assertEquals(List.of("talos.read_file"), request.tools.stream()
                    .map(ToolSpec::name)
                    .toList());
            assertEquals(
                    "line one\nline two",
                    request.messages.stream()
                            .filter(message -> "user".equals(message.role()))
                            .map(ChatMessage::content)
                            .filter("line one\nline two"::equals)
                            .findFirst()
                            .orElseThrow());
            ChatMessage toolResult = request.messages.stream()
                    .filter(message -> "tool".equals(message.role()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("progressremaining", toolResult.content());
            assertEquals("call-1", toolResult.toolCallId());
            ChatMessage assistantCall = request.messages.stream()
                    .filter(message -> "assistant".equals(message.role()))
                    .filter(message -> !message.toolCalls().isEmpty())
                    .findFirst()
                    .orElseThrow();
            assertEquals(List.of(call), assistantCall.toolCalls());
            assertTrue(request.messages.stream()
                    .map(ChatMessage::content)
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(content ->
                            content.contains("obligation: INSPECT_REQUIRED")
                                    && content.contains(
                                            "visibleTools: talos.read_file")));
            assertTrue(request.messages.stream()
                    .map(ChatMessage::content)
                    .filter(java.util.Objects::nonNull)
                    .noneMatch(content -> content.indexOf('\r') >= 0));
        }
    }

    @Test
    void bufferedDispatchSurfacesInnerBudgetAbortInsteadOfOuterTimeout() throws Exception {
        ScriptedNativeLlmClient.BlockingClient blocking =
                ScriptedNativeLlmClient.blockingAfterFirstChunk(5_000L);
        Context ctx = Context.builder(new Config())
                .llm(blocking.client())
                .build();
        CurrentTurnPlan plan = CurrentTurnPlan.compatibility(
                TaskContract.unknown("slow answer"),
                ExecutionPhase.INSPECT,
                List.of(),
                List.of(),
                List.of());

        CompletableFuture<LlmClient.StreamResult> dispatch = CompletableFuture.supplyAsync(() -> {
            try {
                return TurnModelDispatcher.dispatchBufferedWithTimeout(
                        ctx,
                        List.of(ChatMessage.user("answer slowly")),
                        plan,
                        1_000L);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });

        assertTrue(blocking.firstChunkEmitted().await(2, TimeUnit.SECONDS),
                "engine fixture should emit its first chunk before blocking");
        LlmClient.StreamResult result = dispatch.get(4, TimeUnit.SECONDS);
        assertTrue(result.text().contains("[turn aborted: non-streaming chat"),
                "buffered dispatch must return the LlmCallBudget abort marker, got: " + result.text());
        assertTrue(blocking.streamClosed().get(),
                "inner budget must close the provider stream on timeout");
        assertEquals(1, blocking.chatCalls().get(),
                "Talos-initiated abort must not retry or start another generation");
    }
}
