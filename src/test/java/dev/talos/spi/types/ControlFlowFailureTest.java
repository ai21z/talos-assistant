package dev.talos.spi.types;

import dev.talos.spi.EngineException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlFlowFailureTest {

    @Test
    void recognizesCancellationAndInterruptionThroughBoundedGenericWrappers() {
        RuntimeException cancellation = new RuntimeException(
                new CancellationException("cancelled"));
        Throwable interruption = new InterruptedException("interrupted");
        for (int index = 0; index < 64; index++) {
            interruption = new RuntimeException(interruption);
        }

        assertEquals(ControlFlowFailure.Kind.CANCELLATION,
                ControlFlowFailure.classify(cancellation));
        assertEquals(ControlFlowFailure.Kind.INTERRUPTION,
                ControlFlowFailure.classify(interruption));
    }

    @Test
    void semanticEngineFailureDoesNotBorrowControlFlowFromItsCause() {
        EngineException.ConnectionFailed failure = new EngineException.ConnectionFailed(
                "local engine", new InterruptedException("unproven cause"));

        assertEquals(ControlFlowFailure.Kind.NONE,
                ControlFlowFailure.classify(failure));
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void propagationRetainsRuntimeWrapperAndRestoresOwnedInterrupt() {
        RuntimeException failure = new RuntimeException(
                new InterruptedException("owned interrupt"));
        try {
            RuntimeException propagated = ControlFlowFailure.propagate(failure);

            assertSame(failure, propagated);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void cyclicAndNonControlFailuresFailClosed() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second");
        first.initCause(second);
        second.initCause(first);

        assertEquals(ControlFlowFailure.Kind.NONE,
                ControlFlowFailure.classify(first));
        assertThrows(IllegalArgumentException.class,
                () -> ControlFlowFailure.propagate(first));
    }
}
