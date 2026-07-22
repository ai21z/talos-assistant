package dev.talos.runtime;

import dev.talos.spi.EngineException;
import dev.talos.spi.types.ControlFlowFailure;

import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Type-only failure classification shared by turn evidence and the CLI envelope. */
public final class TurnFailureClassifier {
    private TurnFailureClassifier() {}

    public enum ErrorKind {
        NONE,
        ENGINE_MODEL_NOT_FOUND,
        ENGINE_CONNECTION_FAILED,
        ENGINE_TRANSIENT,
        ENGINE_CONTEXT_BUDGET,
        ENGINE_MALFORMED_RESPONSE,
        ENGINE_RESPONSE_ERROR,
        TIMEOUT,
        VALIDATION,
        CANCELLATION,
        INTERRUPTION,
        UNEXPECTED
    }

    public record Classification(ErrorKind kind, int code, String exceptionClass) {
        public Classification {
            kind = kind == null ? ErrorKind.UNEXPECTED : kind;
            exceptionClass = Objects.toString(exceptionClass, "").strip();
            if (kind == ErrorKind.NONE) {
                if (code != 0 || !exceptionClass.isEmpty()) {
                    throw new IllegalArgumentException(
                            "a non-failure classification cannot carry failure evidence");
                }
            } else if (code <= 0 || exceptionClass.isEmpty()) {
                throw new IllegalArgumentException(
                        "a failure classification requires a safe code and exception class");
            }
        }

        public static Classification none() {
            return new Classification(ErrorKind.NONE, 0, "");
        }
    }

    public static Classification classify(Throwable throwable) {
        Throwable failure = unwrap(Objects.requireNonNull(throwable, "turn failure"));
        ErrorKind kind;
        int code;
        if (failure instanceof EngineException.ModelNotFound) {
            kind = ErrorKind.ENGINE_MODEL_NOT_FOUND;
            code = 404;
        } else if (failure instanceof EngineException.ConnectionFailed) {
            kind = ErrorKind.ENGINE_CONNECTION_FAILED;
            code = 503;
        } else if (failure instanceof EngineException.Transient) {
            kind = ErrorKind.ENGINE_TRANSIENT;
            code = 503;
        } else if (failure instanceof EngineException.ContextBudgetExceeded) {
            kind = ErrorKind.ENGINE_CONTEXT_BUDGET;
            code = 400;
        } else if (failure instanceof EngineException.MalformedResponse) {
            kind = ErrorKind.ENGINE_MALFORMED_RESPONSE;
            code = 502;
        } else if (failure instanceof EngineException.ResponseError responseError) {
            kind = ErrorKind.ENGINE_RESPONSE_ERROR;
            code = responseError.httpStatus() >= 100 && responseError.httpStatus() <= 599
                    ? responseError.httpStatus()
                    : 500;
        } else if (failure instanceof TimeoutException) {
            kind = ErrorKind.TIMEOUT;
            code = 408;
        } else if (failure instanceof IllegalArgumentException) {
            kind = ErrorKind.VALIDATION;
            code = 400;
        } else if (failure instanceof CancellationException) {
            kind = ErrorKind.CANCELLATION;
            code = 500;
        } else if (failure instanceof InterruptedException) {
            kind = ErrorKind.INTERRUPTION;
            code = 500;
        } else {
            kind = ErrorKind.UNEXPECTED;
            code = 500;
        }
        return new Classification(kind, code, failure.getClass().getName());
    }

    /** True only when the bounded type graph resolves to caller interruption. */
    public static boolean isInterruption(Throwable throwable) {
        return ControlFlowFailure.isInterruption(throwable);
    }

    /** True only when the bounded type graph resolves to explicit cancellation. */
    public static boolean isCancellation(Throwable throwable) {
        return ControlFlowFailure.isCancellation(throwable);
    }

    /**
     * Preserves cancellation/interruption as control flow at adapter boundaries.
     * The original runtime wrapper is retained; checked interruption is wrapped
     * only after restoring the caller's interrupted status.
     */
    public static RuntimeException propagateControlFlow(Throwable throwable) {
        return ControlFlowFailure.propagate(throwable);
    }

    /**
     * Unwraps generic invocation/runtime wrappers without discarding an
     * already-typed failure. Cause traversal is identity- and depth-bounded so
     * hostile or malformed cyclic cause graphs cannot prevent settlement.
     */
    public static Throwable unwrap(Throwable throwable) {
        if (throwable == null || throwable instanceof Error) return throwable;
        Throwable current = throwable;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int depth = 0; depth < 64; depth++) {
            if (!seen.add(current) || typedFailure(current)) return current;
            Throwable cause = current.getCause();
            if (cause == null || seen.contains(cause) || !wrapper(current)) return current;
            current = cause;
        }
        return current;
    }

    private static boolean wrapper(Throwable throwable) {
        return throwable instanceof RuntimeException
                || throwable instanceof ExecutionException
                || throwable instanceof CompletionException
                || throwable instanceof InvocationTargetException;
    }

    private static boolean typedFailure(Throwable throwable) {
        return throwable instanceof EngineException
                || throwable instanceof TimeoutException
                || throwable instanceof IllegalArgumentException
                || throwable instanceof CancellationException
                || throwable instanceof InterruptedException;
    }
}
