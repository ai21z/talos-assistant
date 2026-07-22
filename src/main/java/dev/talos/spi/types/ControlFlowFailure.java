package dev.talos.spi.types;

import dev.talos.spi.EngineException;

import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Type-only, cycle-bounded recognition of caller cancellation and interruption. */
public final class ControlFlowFailure {
    public enum Kind { NONE, CANCELLATION, INTERRUPTION }

    private ControlFlowFailure() {}

    public static Kind classify(Throwable throwable) {
        Throwable current = throwable;
        if (current == null || current instanceof Error) return Kind.NONE;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int depth = 0; depth < 64; depth++) {
            if (!seen.add(current)) return Kind.NONE;
            if (current instanceof CancellationException) return Kind.CANCELLATION;
            if (current instanceof InterruptedException) return Kind.INTERRUPTION;
            if (semanticBarrier(current) || !genericWrapper(current)) return Kind.NONE;
            Throwable cause = current.getCause();
            if (cause == null || seen.contains(cause)) return Kind.NONE;
            current = cause;
        }
        if (current instanceof CancellationException) return Kind.CANCELLATION;
        if (current instanceof InterruptedException) return Kind.INTERRUPTION;
        return Kind.NONE;
    }

    public static boolean isCancellation(Throwable throwable) {
        return classify(throwable) == Kind.CANCELLATION;
    }

    public static boolean isInterruption(Throwable throwable) {
        return classify(throwable) == Kind.INTERRUPTION;
    }

    /** Preserve recognized control flow without interpreting exception prose. */
    public static RuntimeException propagate(Throwable throwable) {
        Kind kind = classify(throwable);
        if (kind == Kind.NONE) {
            throw new IllegalArgumentException(
                    "failure is neither cancellation nor interruption");
        }
        if (kind == Kind.INTERRUPTION) Thread.currentThread().interrupt();
        return throwable instanceof RuntimeException runtime
                ? runtime
                : new RuntimeException(throwable);
    }

    private static boolean genericWrapper(Throwable throwable) {
        return throwable instanceof RuntimeException
                || throwable instanceof ExecutionException
                || throwable instanceof CompletionException
                || throwable instanceof InvocationTargetException;
    }

    private static boolean semanticBarrier(Throwable throwable) {
        return throwable instanceof EngineException
                || throwable instanceof TimeoutException
                || throwable instanceof IllegalArgumentException;
    }
}
