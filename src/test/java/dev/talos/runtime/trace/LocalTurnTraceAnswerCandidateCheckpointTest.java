package dev.talos.runtime.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.spi.types.ProviderResponseCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTurnTraceAnswerCandidateCheckpointTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void clearCapture() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void restoresExactFallbackOverRetryCandidateOnceWithMetadataOnlyEvidence() throws Exception {
        beginTrace("trc-answer-checkpoint-valid", 1);
        String fallback = "original model answer SECRET=alpha";
        LocalTurnTraceCapture.recordDeterministicAnswerSelected(fallback);
        LocalTurnTraceCapture.AnswerCandidateCheckpoint checkpoint =
                LocalTurnTraceCapture.checkpointExactAnswerCandidate(fallback);
        assertNotNull(checkpoint);

        LocalTurnTraceCapture.recordDeterministicAnswerSelected("rejected retry answer SECRET=beta");
        assertTrue(LocalTurnTraceCapture.restoreExactAnswerCandidate(
                checkpoint,
                fallback,
                LocalTurnTraceCapture.AnswerCandidateRestorationReason
                        .SPECULATIVE_RETRY_REJECTED));
        assertFalse(LocalTurnTraceCapture.restoreExactAnswerCandidate(
                checkpoint,
                fallback,
                LocalTurnTraceCapture.AnswerCandidateRestorationReason
                        .SPECULATIVE_RETRY_REJECTED));
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(fallback);

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        List<TurnTraceEvent> restorations = events(trace, "ANSWER_CANDIDATE_RESTORED");
        assertEquals(1, restorations.size());
        TurnTraceEvent restored = restorations.getFirst();
        assertEquals(TraceRedactor.hash(fallback), restored.data().get("answerHash"));
        assertEquals(fallback.length(), restored.data().get("answerChars"));
        assertFalse(restored.data().containsKey("answerBytes"));
        assertEquals("RUNTIME_DETERMINISTIC", restored.data().get("origin"));
        assertEquals("", restored.data().get("sourceGenerationId"));
        assertEquals("", restored.data().get("sourceAttemptId"));
        assertEquals("SPECULATIVE_RETRY_REJECTED", restored.data().get("reasonCode"));
        assertEquals("RUNTIME_DETERMINISTIC",
                event(trace, "TERMINAL_ANSWER_SELECTED").data().get("origin"));

        String json = MAPPER.writeValueAsString(trace);
        assertFalse(json.contains(fallback));
        assertFalse(json.contains("rejected retry answer SECRET=beta"));
    }

    @Test
    void restoresExactFallbackWhenFailedRetryLeftNoCandidate() {
        beginTrace("trc-answer-checkpoint-empty", 2);
        String fallback = "fallback after failed retry";
        LocalTurnTraceCapture.recordToolAnswerSelected(fallback);
        LocalTurnTraceCapture.AnswerCandidateCheckpoint checkpoint =
                LocalTurnTraceCapture.checkpointExactAnswerCandidate(fallback);
        assertNotNull(checkpoint);

        LocalTurnTraceCapture.recordModelGenerationAborted("timeout", false);
        assertTrue(LocalTurnTraceCapture.restoreExactAnswerCandidate(
                checkpoint,
                fallback,
                LocalTurnTraceCapture.AnswerCandidateRestorationReason
                        .SPECULATIVE_RETRY_FAILED));
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(fallback);

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertEquals("TOOL_DETERMINISTIC",
                event(trace, "ANSWER_CANDIDATE_RESTORED").data().get("origin"));
        assertEquals("SPECULATIVE_RETRY_FAILED",
                event(trace, "ANSWER_CANDIDATE_RESTORED").data().get("reasonCode"));
        assertEquals("TOOL_DETERMINISTIC",
                event(trace, "TERMINAL_ANSWER_SELECTED").data().get("origin"));
    }

    @Test
    void restoresExactProviderOriginAndSourceIdsWithoutCallerSuppliedLineage() {
        beginTrace("trc-answer-checkpoint-provider", 3);
        String fallback = "provider-authored fallback";
        ProviderResponseCapture.Generation generation =
                ProviderResponseCapture.openGeneration(
                        ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                        ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        attempt.requestDispatched();
        ProviderResponseCapture.Receipt receipt = attempt.respond(
                new ProviderResponseCapture.Response(
                        fallback,
                        0,
                        ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                        ProviderResponseCapture.Completion.PROVIDER_EOS,
                        false,
                        false));
        generation.complete(receipt);
        LocalTurnTraceCapture.AnswerCandidateCheckpoint checkpoint =
                LocalTurnTraceCapture.checkpointExactAnswerCandidate(fallback);
        assertNotNull(checkpoint);

        LocalTurnTraceCapture.recordDeterministicAnswerSelected("rejected retry");
        assertTrue(LocalTurnTraceCapture.restoreExactAnswerCandidate(
                checkpoint,
                fallback,
                LocalTurnTraceCapture.AnswerCandidateRestorationReason
                        .SPECULATIVE_RETRY_REJECTED));
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(fallback);

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        TurnTraceEvent restored = event(trace, "ANSWER_CANDIDATE_RESTORED");
        assertEquals("MODEL_PROVIDER", restored.data().get("origin"));
        assertEquals(receipt.generationId(), restored.data().get("sourceGenerationId"));
        assertEquals(receipt.attemptId(), restored.data().get("sourceAttemptId"));
        TurnTraceEvent terminal = event(trace, "TERMINAL_ANSWER_SELECTED");
        assertEquals("MODEL_PROVIDER", terminal.data().get("origin"));
        assertEquals(receipt.generationId(), terminal.data().get("sourceGenerationId"));
        assertEquals(receipt.attemptId(), terminal.data().get("sourceAttemptId"));
    }

    @Test
    void mismatchedFallbackCannotMintOrRestoreAndConsumesRegisteredToken() {
        beginTrace("trc-answer-checkpoint-mismatch", 4);
        String fallback = "exact fallback";
        LocalTurnTraceCapture.recordOperatorAnswerSelected(fallback);
        assertNull(LocalTurnTraceCapture.checkpointExactAnswerCandidate("not the fallback"));

        LocalTurnTraceCapture.AnswerCandidateCheckpoint checkpoint =
                LocalTurnTraceCapture.checkpointExactAnswerCandidate(fallback);
        assertNotNull(checkpoint);
        assertFalse(LocalTurnTraceCapture.restoreExactAnswerCandidate(
                checkpoint,
                "different bytes",
                LocalTurnTraceCapture.AnswerCandidateRestorationReason
                        .SPECULATIVE_RETRY_EMPTY));
        assertFalse(LocalTurnTraceCapture.restoreExactAnswerCandidate(
                checkpoint,
                fallback,
                LocalTurnTraceCapture.AnswerCandidateRestorationReason
                        .SPECULATIVE_RETRY_EMPTY));

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertTrue(events(trace, "ANSWER_CANDIDATE_RESTORED").isEmpty());
    }

    @Test
    void wrongThreadCannotRestoreOrConsumeOwnerThreadsCheckpoint() throws Exception {
        beginTrace("trc-answer-checkpoint-thread", 5);
        String fallback = "thread-bound fallback";
        LocalTurnTraceCapture.recordDeterministicAnswerSelected(fallback);
        LocalTurnTraceCapture.AnswerCandidateCheckpoint checkpoint =
                LocalTurnTraceCapture.checkpointExactAnswerCandidate(fallback);
        AtomicBoolean foreignRestored = new AtomicBoolean(true);
        AtomicReference<Throwable> foreignFailure = new AtomicReference<>();

        Thread foreign = new Thread(() -> {
            try {
                foreignRestored.set(LocalTurnTraceCapture.restoreExactAnswerCandidate(
                        checkpoint,
                        fallback,
                        LocalTurnTraceCapture.AnswerCandidateRestorationReason
                                .SPECULATIVE_RETRY_FAILED));
            } catch (Throwable failure) {
                foreignFailure.set(failure);
            }
        }, "foreign-answer-checkpoint-restorer");
        foreign.start();
        foreign.join();

        assertNull(foreignFailure.get());
        assertFalse(foreignRestored.get());
        assertTrue(LocalTurnTraceCapture.restoreExactAnswerCandidate(
                checkpoint,
                fallback,
                LocalTurnTraceCapture.AnswerCandidateRestorationReason
                        .SPECULATIVE_RETRY_FAILED));
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertEquals(1, events(trace, "ANSWER_CANDIDATE_RESTORED").size());
    }

    @Test
    void checkpointFromReplacedOrCompletedTraceIsStale() {
        beginTrace("trc-answer-checkpoint-old", 6);
        String oldFallback = "old fallback";
        LocalTurnTraceCapture.recordDeterministicAnswerSelected(oldFallback);
        LocalTurnTraceCapture.AnswerCandidateCheckpoint replaced =
                LocalTurnTraceCapture.checkpointExactAnswerCandidate(oldFallback);

        beginTrace("trc-answer-checkpoint-new", 7);
        assertFalse(LocalTurnTraceCapture.restoreExactAnswerCandidate(
                replaced,
                oldFallback,
                LocalTurnTraceCapture.AnswerCandidateRestorationReason
                        .SPECULATIVE_RETRY_FAILED));
        String newFallback = "new fallback";
        LocalTurnTraceCapture.recordDeterministicAnswerSelected(newFallback);
        LocalTurnTraceCapture.AnswerCandidateCheckpoint completed =
                LocalTurnTraceCapture.checkpointExactAnswerCandidate(newFallback);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertEquals("trc-answer-checkpoint-new", trace.traceId());
        assertFalse(LocalTurnTraceCapture.restoreExactAnswerCandidate(
                completed,
                newFallback,
                LocalTurnTraceCapture.AnswerCandidateRestorationReason
                        .SPECULATIVE_RETRY_FAILED));
        assertTrue(events(trace, "ANSWER_CANDIDATE_RESTORED").isEmpty());
    }

    @Test
    void checkpointCannotRestoreAfterTerminalAnswerWasRouted() {
        beginTrace("trc-answer-checkpoint-post-route", 8);
        String fallback = "already routed";
        LocalTurnTraceCapture.recordDeterministicAnswerSelected(fallback);
        LocalTurnTraceCapture.AnswerCandidateCheckpoint checkpoint =
                LocalTurnTraceCapture.checkpointExactAnswerCandidate(fallback);
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(fallback);

        assertFalse(LocalTurnTraceCapture.restoreExactAnswerCandidate(
                checkpoint,
                fallback,
                LocalTurnTraceCapture.AnswerCandidateRestorationReason
                        .SPECULATIVE_RETRY_FAILED));
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertTrue(events(trace, "ANSWER_CANDIDATE_RESTORED").isEmpty());
        assertEquals(1, events(trace, "TERMINAL_ANSWER_SELECTED").size());
    }

    @Test
    void reflectionForgedCheckpointIsRejectedWithoutARegistration() throws Exception {
        beginTrace("trc-answer-checkpoint-forged", 9);
        String fallback = "registered fallback";
        LocalTurnTraceCapture.recordDeterministicAnswerSelected(fallback);
        LocalTurnTraceCapture.AnswerCandidateCheckpoint registered =
                LocalTurnTraceCapture.checkpointExactAnswerCandidate(fallback);
        assertNotNull(registered);

        Field ownerField = LocalTurnTraceCapture.AnswerCandidateCheckpoint.class
                .getDeclaredField("owner");
        Field candidateField = LocalTurnTraceCapture.AnswerCandidateCheckpoint.class
                .getDeclaredField("candidate");
        ownerField.setAccessible(true);
        candidateField.setAccessible(true);
        Constructor<LocalTurnTraceCapture.AnswerCandidateCheckpoint> constructor =
                LocalTurnTraceCapture.AnswerCandidateCheckpoint.class.getDeclaredConstructor(
                        ownerField.getType(), candidateField.getType());
        constructor.setAccessible(true);
        LocalTurnTraceCapture.AnswerCandidateCheckpoint forged =
                constructor.newInstance(
                        ownerField.get(registered), candidateField.get(registered));

        assertFalse(LocalTurnTraceCapture.restoreExactAnswerCandidate(
                forged,
                fallback,
                LocalTurnTraceCapture.AnswerCandidateRestorationReason
                        .SPECULATIVE_RETRY_FAILED));
        assertTrue(LocalTurnTraceCapture.restoreExactAnswerCandidate(
                registered,
                fallback,
                LocalTurnTraceCapture.AnswerCandidateRestorationReason
                        .SPECULATIVE_RETRY_FAILED));
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertEquals(1, events(trace, "ANSWER_CANDIDATE_RESTORED").size());
    }

    private static void beginTrace(String traceId, int turnNumber) {
        LocalTurnTraceCapture.begin(
                traceId,
                "session-answer-checkpoint-tests",
                turnNumber,
                "2026-07-16T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                "test-model",
                "verify exact answer-candidate restoration lineage");
    }

    private static TurnTraceEvent event(LocalTurnTrace trace, String type) {
        return events(trace, type).getFirst();
    }

    private static List<TurnTraceEvent> events(LocalTurnTrace trace, String type) {
        return trace.events().stream()
                .filter(candidate -> type.equals(candidate.type()))
                .toList();
    }
}
