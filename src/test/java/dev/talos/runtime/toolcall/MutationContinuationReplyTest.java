package dev.talos.runtime.toolcall;

import dev.talos.core.llm.LlmClient;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ProviderResponseCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * After a continuation that followed completed changes, an unusable reply leaves the turn as its completed changes
 * left it (P20 and its second review): a reply ended by a watchdog, a stream close or the transport and an incomplete
 * reply end the turn with the summaries of every change. An operator cancellation and a thread interruption keep their
 * own handling, and without a continuation every reply is handled as before. Real LoopState and reply application.
 */
class MutationContinuationReplyTest {
    private static final String SUMMARY = "✓ Edited notes.md: replaced 1 line(s) with 1 line(s)";

    @ParameterizedTest
    @EnumSource(value = ProviderResponseCapture.AbortOrigin.class,
            names = {"RUNTIME_WATCHDOG", "RUNTIME_STREAM_CLOSE", "PROVIDER_TRANSPORT"})
    void aReplyEndedByTheRuntimeOrTransportAfterAContinuationEndsWithTheChanges(
            ProviderResponseCapture.AbortOrigin origin) {
        LoopState state = continued();

        boolean continues = ToolRepromptChatExecutor.applyResult(state, aborted(origin), "fallback");

        assertFalse(continues);
        assertEquals(SUMMARY, state.currentText);
        assertTrue(state.currentNativeCalls.isEmpty());
    }

    @Test
    void anIncompleteReplyAfterAContinuationEndsWithTheChanges() {
        LoopState state = continued();
        var truncated = new LlmClient.StreamResult("Now I will also add", List.of(), "length");

        boolean continues = ToolRepromptChatExecutor.applyResult(state, truncated, "fallback");

        assertFalse(continues);
        assertEquals(SUMMARY, state.currentText);
    }

    @Test
    void anOperatorCancellationAfterAContinuationStillPropagates() {
        LoopState state = continued();

        assertThrows(CancellationException.class, () -> ToolRepromptChatExecutor.applyResult(
                state, aborted(ProviderResponseCapture.AbortOrigin.OPERATOR_EXTERNAL), "fallback"));
    }

    @Test
    void withoutAContinuationAnAbortedReplyIsHandledAsBefore() {
        LoopState state = state();
        state.mutatingToolSuccesses = 1;
        state.pendingMutationSummaries.add(SUMMARY);

        assertThrows(RuntimeException.class, () -> ToolRepromptChatExecutor.applyResult(
                state, aborted(ProviderResponseCapture.AbortOrigin.RUNTIME_WATCHDOG), "fallback"));
    }

    @Test
    void aUsableReplyAfterAContinuationIsApplied() {
        LoopState state = continued();
        var reply = new LlmClient.StreamResult("Changed the heading.", List.of(), "stop");

        boolean continues = ToolRepromptChatExecutor.applyResult(state, reply, "fallback");

        assertTrue(continues);
        assertEquals("Changed the heading.", state.currentText);
    }

    private static LlmClient.StreamResult aborted(ProviderResponseCapture.AbortOrigin origin) {
        return new LlmClient.StreamResult("partial", List.of(), "", "generation aborted", origin);
    }

    private static LoopState continued() {
        LoopState state = state();
        state.mutatingToolSuccesses = 1;
        state.pendingMutationSummaries.add(SUMMARY);
        state.mutationContinuations = 1;
        return state;
    }

    private static LoopState state() {
        return new LoopState(
                "",
                List.of(),
                new ArrayList<>(List.of(ChatMessage.system("sys"), ChatMessage.user("Update notes.md."))),
                Path.of("."),
                null,
                null,
                10,
                0);
    }
}
