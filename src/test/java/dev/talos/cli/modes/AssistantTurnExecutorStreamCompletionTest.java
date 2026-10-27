package dev.talos.cli.modes;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantTurnExecutorStreamCompletionTest {

    @Test
    void typedControlFlowCannotBeSwallowedByStreamCompletion() {
        CancellationException cancellation = new CancellationException("cancel stream completion");
        assertSame(cancellation, assertThrows(CancellationException.class, () ->
                AssistantTurnExecutor.runStreamCompletion(() -> { throw cancellation; })));

        RuntimeException wrappedInterruption = new RuntimeException(
                new InterruptedException("interrupt stream completion"));
        try {
            RuntimeException observed = assertThrows(RuntimeException.class, () ->
                    AssistantTurnExecutor.runStreamCompletion(() -> { throw wrappedInterruption; }));
            assertSame(wrappedInterruption, observed);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptedStatusStopsStreamCompletionBeforeAndAfterCallback() {
        AtomicInteger calls = new AtomicInteger();
        try {
            Thread.currentThread().interrupt();
            RuntimeException before = assertThrows(RuntimeException.class, () ->
                    AssistantTurnExecutor.runStreamCompletion(calls::incrementAndGet));
            assertTrue(before.getCause() instanceof InterruptedException);
            assertEquals(0, calls.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }

        try {
            RuntimeException after = assertThrows(RuntimeException.class, () ->
                    AssistantTurnExecutor.runStreamCompletion(() -> {
                        calls.incrementAndGet();
                        Thread.currentThread().interrupt();
                    }));
            assertTrue(after.getCause() instanceof InterruptedException);
            assertEquals(1, calls.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void ordinaryPresentationFailureRetainsCompatibilityFallback() {
        AssistantTurnExecutor.runStreamCompletion(() -> {
            throw new IllegalStateException("presentation unavailable");
        });

        try {
            RuntimeException observed = assertThrows(RuntimeException.class, () ->
                    AssistantTurnExecutor.runStreamCompletion(() -> {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("presentation failed after interruption");
                    }));
            assertTrue(observed.getCause() instanceof InterruptedException);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
