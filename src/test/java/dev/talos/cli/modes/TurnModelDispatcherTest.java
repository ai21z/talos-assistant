package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TurnModelDispatcherTest {

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
