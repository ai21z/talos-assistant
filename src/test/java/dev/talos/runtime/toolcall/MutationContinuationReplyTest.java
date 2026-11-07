package dev.talos.runtime.toolcall;

import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.trace.LocalTurnTraceCapture.ToolCallAuthorityRejectionReason;
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
 * reply end the turn with the summaries of every change, and so does a reply whose calls were all refused before
 * execution (P21, D17 option A). An operator cancellation and a thread interruption keep their
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

    @Test
    void aReplyWhoseCallsWereAllRefusedAfterAContinuationEndsWithTheChanges() {
        // P21 (finding LP-F1, D17 option A). A refused call never ran, so the reply is unusable.
        LoopState state = continued();
        state.toolOutcomes.add(ToolOutcomeFactory.failedAuthority(null, "blocked"));

        assertTrue(MutationContinuationDecision.endIfEveryCallRefused(state, refused(1, 0)));
        assertEquals(SUMMARY, state.currentText);
        assertTrue(state.currentNativeCalls.isEmpty());
        assertFalse(state.failureDecision.shouldStop(), "No failure stop is set for an unusable continuation reply.");
    }

    @Test
    void aRefusedCallKeepsItsOwnHandlingWithoutAContinuationOrWhenAnotherCallOfTheIterationWasDispatched() {
        LoopState without = state();
        without.mutatingToolSuccesses = 1;
        without.pendingMutationSummaries.add(SUMMARY);
        without.toolOutcomes.add(ToolOutcomeFactory.failedAuthority(null, "blocked"));
        assertFalse(MutationContinuationDecision.endIfEveryCallRefused(without, refused(1, 0)),
                "Without a continuation the existing stop holds.");

        LoopState continued = continued();
        continued.toolOutcomes.add(ToolOutcomeFactory.failedAuthority(null, "blocked"));
        assertFalse(MutationContinuationDecision.endIfEveryCallRefused(continued, refused(1, 1)),
                "A call that ran in the same iteration keeps the existing handling.");
        assertFalse(MutationContinuationDecision.endIfEveryCallRefused(continued, refused(2, 0)),
                "A failure other than the refusal means a call was dispatched.");
        assertFalse(MutationContinuationDecision.endIfEveryCallRefused(continued, null));
        assertEquals("", continued.currentText);
    }

    @Test
    void anAuthorityRejectionOtherThanATextCallKeepsItsHardStopAfterAContinuation() {
        // P21 review: as in D7, only a call written as text is a reply form. A stale generation, a lineage mismatch or
        // a rejection without a recorded reason keeps the existing stop, so it stays visible.
        LoopState state = continued();
        state.toolOutcomes.add(ToolOutcomeFactory.failedAuthority(null, "blocked"));

        assertFalse(MutationContinuationDecision.endIfEveryCallRefused(state, refused(1, 0,
                List.of(ToolCallAuthorityRejectionReason.PROVIDER_GENERATION_STALE))));
        assertFalse(MutationContinuationDecision.endIfEveryCallRefused(state, refused(1, 0,
                List.of(ToolCallAuthorityRejectionReason.PROVIDER_TOOL_MODE_MISMATCH,
                        ToolCallAuthorityRejectionReason.LINEAGE_CALL_MISMATCH))));
        assertFalse(MutationContinuationDecision.endIfEveryCallRefused(state, refused(1, 0, List.of())));
        assertEquals("", state.currentText);
    }

    private static ToolCallExecutionStage.IterationOutcome refused(int failures, int successes) {
        return refused(failures, successes, List.of(ToolCallAuthorityRejectionReason.PROVIDER_TOOL_MODE_MISMATCH));
    }

    private static ToolCallExecutionStage.IterationOutcome refused(
            int failures, int successes, List<ToolCallAuthorityRejectionReason> reasons) {
        return new ToolCallExecutionStage.IterationOutcome(0, List.of(), failures, false, false, false, successes,
                List.of(), true, 0, reasons);
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
