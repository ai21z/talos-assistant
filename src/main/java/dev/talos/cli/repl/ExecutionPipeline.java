package dev.talos.cli.repl;

import dev.talos.runtime.Result;

import dev.talos.spi.EngineException;

import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * ExecutionPipeline
 * - Central place for cross-cutting concerns (rate limiting, audit, error envelopes)
 * - Always returns a Result for rendering; never throws into the REPL loop
 */
public final class ExecutionPipeline {

    @FunctionalInterface
    public interface Op<T> {
        T get() throws Exception; // allow checked exceptions
    }

    private final TokenBucket bucket = new TokenBucket();

    /**
     * Run a unit of work under the pipeline.
     *
     * @param op     Work that returns a Result (may return null) and can throw
     * @param ctx    Runtime context (limits, audit, redactor, etc.)
     * @param label  Short label for audit/diagnostics (e.g., "/help", "(prompt)")
     */
    public Result run(Op<Result> op, Context ctx, String label) {
        // 1) Rate limit (global per ReplRouter instance)
        int rate = ctx.limits().ratePerSec();
        if (!bucket.tryConsume(rate)) {
            try {
                ctx.audit().log("rate_limited", Map.of("op", label, "rate_per_sec", rate));
            } catch (Throwable ignore) {}
            return new Result.Info("Too many requests. Please slow down.");
        }

        // 2) Execute with envelope
        try {
            Result r = op.get();
            if (r == null) return new Result.Info("(no result)");
            return r;
        } catch (Throwable t) {
            Throwable ex = unwrap(t);
            String msg = ex.getMessage();
            if (msg == null || msg.isBlank()) msg = ex.getClass().getSimpleName();
            msg = ctx.redactor().redactLine(msg);

            // Append guidance from EngineException subtypes
            String guidance = "";
            if (ex instanceof EngineException ee && !ee.guidance().isEmpty()) {
                guidance = "\n  → " + ee.guidance();
            }

            // Classify the error code from the exception type
            int code = classifyError(ex);

            // minimal redacted audit
            try {
                ctx.audit().log("error", Map.of(
                        "op", label,
                        "ex", ex.getClass().getName(),
                        "code", code
                ));
            } catch (Throwable ignore) {}

            return new Result.Error(msg + guidance, code);
        }
    }

    /**
     * Maps an exception to an appropriate error code:
     * <ul>
     *   <li>404 — model not found</li>
     *   <li>408 — timeout</li>
     *   <li>502 — malformed backend response</li>
     *   <li>503 — connection failed or transient backend error</li>
     *   <li>400 — illegal argument / validation</li>
     *   <li>500 — everything else (unexpected)</li>
     * </ul>
     */
    static int classifyError(Throwable ex) {
        if (ex instanceof EngineException.ModelNotFound)    return 404;
        if (ex instanceof EngineException.ConnectionFailed) return 503;
        if (ex instanceof EngineException.Transient)        return 503;
        if (ex instanceof EngineException.MalformedResponse) return 502;
        if (ex instanceof EngineException.ResponseError re) return re.httpStatus() > 0 ? re.httpStatus() : 500;
        if (ex instanceof TimeoutException)                 return 408;
        if (ex instanceof IllegalArgumentException)         return 400;
        return 500;
    }

    private static Throwable unwrap(Throwable t) {
        // Preserve Errors; unwrap typical wrapper exceptions
        if (t instanceof Error) return t;
        Throwable cur = t;
        while (cur.getCause() != null
                && (cur instanceof RuntimeException
                || cur.getClass().getName().endsWith("InvocationTargetException"))) {
            cur = cur.getCause();
        }
        return cur;
    }

    /** Simple 1-second token bucket; rate<=0 disables limiting. */
    private static final class TokenBucket {
        private long windowStartMs = System.currentTimeMillis();
        private int tokens = Integer.MAX_VALUE;

        synchronized boolean tryConsume(int ratePerSec) {
            if (ratePerSec <= 0) return true; // disabled
            long now = System.currentTimeMillis();
            if (now - windowStartMs >= 1000L) {
                windowStartMs = now;
                tokens = ratePerSec;
            }
            if (tokens > 0) { tokens--; return true; }
            return false;
        }
    }
}
