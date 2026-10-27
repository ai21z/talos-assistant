package dev.talos.core.llm;

import dev.talos.core.util.UiChrome;
import dev.talos.spi.types.ProviderResponseCapture;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

final class LlmCallBudget implements AutoCloseable {

    private final long defaultIdleMs;
    private final ExecutorService llmCallExecutor =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "talos-llm-call");
                t.setDaemon(true);
                return t;
            });
    private final ScheduledExecutorService watchdogExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "talos-llm-watchdog");
                t.setDaemon(true);
                return t;
            });

    LlmCallBudget(long defaultIdleMs) {
        this.defaultIdleMs = defaultIdleMs;
    }

    LlmClient.StreamResult run(Function<AtomicReference<AutoCloseable>, LlmClient.StreamResult> work,
                               long wallClockMs,
                               AtomicLong lastChunkAt,
                               String label,
                               RepetitionBreaker breaker) {
        return run(work, wallClockMs, lastChunkAt, label, breaker, null);
    }

    LlmClient.StreamResult run(Function<AtomicReference<AutoCloseable>, LlmClient.StreamResult> work,
                               long wallClockMs,
                               AtomicLong lastChunkAt,
                               String label,
                               RepetitionBreaker breaker,
                               Supplier<Boolean> cancelled) {
        return run(work, wallClockMs, lastChunkAt, label, breaker, cancelled, null);
    }

    LlmClient.StreamResult run(Function<AtomicReference<AutoCloseable>, LlmClient.StreamResult> work,
                               long wallClockMs,
                               AtomicLong lastChunkAt,
                               String label,
                               RepetitionBreaker breaker,
                               Supplier<Boolean> cancelled,
                               ProviderResponseCapture.Generation generation) {
        final AtomicReference<AutoCloseable> activeStream = new AtomicReference<>();
        java.util.concurrent.ScheduledFuture<?> watchdog = null;
        // Progress is reset once per logical call, before work is submitted.
        // Zero remains unseen until decoded text or a complete native call
        // is reported. Startup and internal retries cannot prime progress.
        if (lastChunkAt != null) {
            lastChunkAt.set(0L);
        }

        if (wallClockMs <= 0) {
            if (isCancelled(cancelled)) {
                if (generation != null) {
                    generation.abandon(ProviderResponseCapture.Abandonment.OPERATOR_CANCELLED);
                }
                return LlmClient.abortedStreamResult(
                        "", "interrupted", ProviderResponseCapture.AbortOrigin.OPERATOR_EXTERNAL);
            }
            return work.apply(activeStream);
        }

        TerminalArbiter arbiter = new TerminalArbiter(activeStream, generation);
        CompletableFuture<LlmClient.StreamResult> future = arbiter.outcome();
        try {
            llmCallExecutor.execute(dev.talos.safety.ContentRestrictionScope.propagate(() -> {
                try {
                    arbiter.workSucceeded(work.apply(activeStream));
                } catch (Throwable failure) {
                    arbiter.workFailed(failure);
                }
            }));
        } catch (RuntimeException submissionFailure) {
            arbiter.workFailed(submissionFailure);
        }

        boolean wantIdleWatchdog = defaultIdleMs > 0 && lastChunkAt != null;
        boolean wantRepetitionWatchdog = breaker != null;
        boolean wantCancelWatchdog = cancelled != null;
        if (wantIdleWatchdog || wantRepetitionWatchdog || wantCancelWatchdog) {
            long tickMs = wantCancelWatchdog
                    ? 100L
                    : wantIdleWatchdog
                    ? Math.max(500L, Math.min(defaultIdleMs / 4L, 5_000L))
                    : 500L;
            watchdog = watchdogExecutor.scheduleAtFixedRate(() -> {
                if (arbiter.decided()) return;
                if (wantCancelWatchdog && isCancelled(cancelled)) {
                    arbiter.abort(
                            ProviderResponseCapture.Abandonment.OPERATOR_CANCELLED,
                            LlmClient.abortedStreamResult(
                                    "", "interrupted",
                                    ProviderResponseCapture.AbortOrigin.OPERATOR_EXTERNAL));
                    return;
                }
                if (wantRepetitionWatchdog && breaker.tripped()) {
                    arbiter.abort(
                            ProviderResponseCapture.Abandonment.REPETITION_LOOP,
                            repetitionAbort(label, breaker.substringLen(), breaker.maxRepeats()));
                    return;
                }
                if (wantIdleWatchdog) {
                    long currentChunkAt = lastChunkAt.get();
                    long since = System.currentTimeMillis() - currentChunkAt;
                    if (currentChunkAt != 0L && since > defaultIdleMs) {
                        arbiter.abort(
                                ProviderResponseCapture.Abandonment.IDLE_TIMEOUT,
                                idleAbort(label, defaultIdleMs));
                    }
                }
            }, tickMs, tickMs, TimeUnit.MILLISECONDS);
        }

        boolean progressAwareWallClock = wantIdleWatchdog;

        try {
            if (!progressAwareWallClock) {
                return future.get(wallClockMs, TimeUnit.MILLISECONDS);
            }
            return waitWithProgressAwareWallClock(
                    future,
                    wallClockMs,
                    lastChunkAt,
                    label,
                    arbiter);
        } catch (TimeoutException te) {
            arbiter.abort(
                    ProviderResponseCapture.Abandonment.WALL_CLOCK_TIMEOUT,
                    wallClockAbort(label, wallClockMs));
            return awaitWinner(future, false);
        } catch (ExecutionException ee) {
            return rethrowWorkerFailure(ee.getCause());
        } catch (InterruptedException ie) {
            arbiter.abort(
                    ProviderResponseCapture.Abandonment.INTERRUPTION_CAUSE_UNPROVEN,
                    new LlmClient.StreamResult(
                            UiChrome.TURN_ABORTED_PREFIX + ": interrupted]",
                            List.of(), "", "interrupted",
                            ProviderResponseCapture.AbortOrigin.RUNTIME_THREAD_INTERRUPT));
            return awaitWinner(future, true);
        } finally {
            if (watchdog != null) watchdog.cancel(false);
        }
    }

    private LlmClient.StreamResult waitWithProgressAwareWallClock(
            CompletableFuture<LlmClient.StreamResult> future,
            long wallClockMs,
            AtomicLong lastChunkAt,
            String label,
            TerminalArbiter arbiter
    ) throws InterruptedException, ExecutionException, TimeoutException {
        long startedAt = System.currentTimeMillis();
        long firstDeadline = startedAt + wallClockMs;
        boolean observedProgress = false;
        while (true) {
            long now = System.currentTimeMillis();
            long waitMs = Math.max(1L, firstDeadline - now);
            if (now >= firstDeadline) {
                if (future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled()) {
                    // A completed generation always wins over an abort
                    // decision made from stale idle facts.
                    return future.get(1L, TimeUnit.MILLISECONDS);
                }
                long currentChunkAt = lastChunkAt.get();
                observedProgress = observedProgress || currentChunkAt != 0L;
                long idleForMs = now - currentChunkAt;
                if (observedProgress && idleForMs <= defaultIdleMs) {
                    waitMs = Math.max(1L, Math.min(idleRemainingMs(idleForMs), watchdogPollMs()));
                } else if (observedProgress) {
                    arbiter.abort(
                            ProviderResponseCapture.Abandonment.IDLE_TIMEOUT,
                            idleAbort(label, defaultIdleMs));
                    return future.get();
                } else {
                    throw new TimeoutException("no chunk progress before wall-clock budget");
                }
            }
            try {
                return future.get(waitMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException timedOut) {
                // Re-evaluate instead of rethrowing: future.get measures its
                // wait monotonically while the deadline uses the wall clock,
                // so a coarse tick or backward step can wake this thread
                // fractionally before the deadline reads as crossed. The
                // loop recomputes the residual wait; aborting here would
                // kill a progressing generation with a false wall-clock
                // reason.
            }
        }
    }

    private long idleRemainingMs(long idleForMs) {
        return Math.max(1L, defaultIdleMs - idleForMs);
    }

    private long watchdogPollMs() {
        return Math.max(500L, Math.min(defaultIdleMs / 4L, 5_000L));
    }

    private static LlmClient.StreamResult wallClockAbort(String label, long wallClockMs) {
        String msg = UiChrome.TURN_ABORTED_PREFIX + ": " + label + " exceeded "
                + (wallClockMs / 1000) + "s wall-clock budget - model is hung "
                + "or producing tokens too slowly. Try a smaller model, a shorter prompt, "
                + "or raise limits.llm_timeout_ms in config.]";
        return new LlmClient.StreamResult(
                msg, List.of(), "", "wall-clock budget exceeded",
                ProviderResponseCapture.AbortOrigin.RUNTIME_WATCHDOG);
    }

    private static LlmClient.StreamResult idleAbort(String label, long idleMs) {
        String msg = UiChrome.TURN_ABORTED_PREFIX + ": " + label + " produced no tokens for "
                + (idleMs / 1000) + "s - model appears wedged. "
                + "Try a smaller model or raise limits.llm_idle_ms in config.]";
        return new LlmClient.StreamResult(
                msg, List.of(), "", "idle timeout - no tokens",
                ProviderResponseCapture.AbortOrigin.RUNTIME_WATCHDOG);
    }

    private static LlmClient.StreamResult repetitionAbort(
            String label,
            int substringLen,
            int maxRepeats) {
        String msg = UiChrome.TURN_ABORTED_PREFIX + ": " + label + " entered a repetition loop - "
                + "the same " + substringLen + "-character pattern repeated "
                + maxRepeats + "+ times in the streamed output. "
                + "Try a smaller model, rephrase the prompt, or clear session memory with /clear.]";
        return new LlmClient.StreamResult(
                msg, List.of(), "", "repetition loop",
                ProviderResponseCapture.AbortOrigin.RUNTIME_WATCHDOG);
    }

    private static boolean isCancelled(Supplier<Boolean> cancelled) {
        return cancelled != null && Boolean.TRUE.equals(cancelled.get());
    }

    private static LlmClient.StreamResult awaitWinner(
            CompletableFuture<LlmClient.StreamResult> future,
            boolean restoreInterrupt) {
        boolean interrupted = restoreInterrupt;
        try {
            while (true) {
                try {
                    return future.get();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                } catch (ExecutionException failure) {
                    return rethrowWorkerFailure(failure.getCause());
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static LlmClient.StreamResult rethrowWorkerFailure(Throwable cause) {
        if (cause instanceof RuntimeException runtimeException) throw runtimeException;
        if (cause instanceof Error error) throw error;
        throw new RuntimeException(cause);
    }

    static void closeActiveStream(AtomicReference<AutoCloseable> ref) {
        if (ref == null) return;
        AutoCloseable closeable = ref.getAndSet(null);
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
            // best-effort close from watchdog or timeout path
        }
    }

    @Override
    public void close() {
        try {
            llmCallExecutor.shutdownNow();
        } catch (Exception ignored) {}
        try {
            watchdogExecutor.shutdownNow();
        } catch (Exception ignored) {}
    }

    /**
     * Single publication point for the worker and every deterministic abort
     * source. A winner is claimed before generation state or the caller-visible
     * future is changed, so a worker returning while an abandonment callback is
     * still running cannot publish a contradictory successful result.
     */
    private static final class TerminalArbiter {
        private enum Decision { PENDING, WORK_SUCCESS, WORK_FAILURE, ABORT }

        private Decision decision = Decision.PENDING;
        private final CompletableFuture<LlmClient.StreamResult> outcome =
                new CompletableFuture<>();
        private final AtomicReference<AutoCloseable> activeStream;
        private final ProviderResponseCapture.Generation generation;

        private TerminalArbiter(
                AtomicReference<AutoCloseable> activeStream,
                ProviderResponseCapture.Generation generation) {
            this.activeStream = activeStream;
            this.generation = generation;
        }

        private CompletableFuture<LlmClient.StreamResult> outcome() {
            return outcome;
        }

        private synchronized boolean decided() {
            return decision != Decision.PENDING;
        }

        private synchronized void workSucceeded(LlmClient.StreamResult result) {
            if (decision != Decision.PENDING) return;
            decision = Decision.WORK_SUCCESS;
            outcome.complete(result);
        }

        private synchronized void workFailed(Throwable failure) {
            if (decision != Decision.PENDING) return;
            decision = Decision.WORK_FAILURE;
            outcome.completeExceptionally(failure);
        }

        private synchronized boolean abort(
                ProviderResponseCapture.Abandonment abandonment,
                LlmClient.StreamResult result) {
            if (decision != Decision.PENDING) return false;
            // Claim the evidence terminal while holding the same arbiter lock
            // used by worker publication. If a provider completion/failure
            // already won the generation lock, leave the caller outcome
            // pending for that worker fact instead of publishing a
            // contradictory synthetic abort.
            if (generation != null
                    && generation.attached()
                    && !generation.tryAbandon(abandonment)) {
                return false;
            }
            if (generation != null && generation.attached()) {
                generation.awaitOrQuarantineTransport(
                        ProviderResponseCapture.TRANSPORT_SETTLEMENT_GRACE_MS);
            }
            decision = Decision.ABORT;
            AutoCloseable abandonedStream = activeStream.getAndSet(null);
            outcome.complete(result.withAbandonment(abandonment));
            // A resistant close must not block the caller or the watchdog.
            // The detached handle is attempted once, independently of budget
            // executor shutdown. Transport settlement is not inferred from it.
            if (abandonedStream != null) {
                try {
                    Thread.ofVirtual().name("talos-llm-abandoned-stream-close").start(() -> {
                        try {
                            abandonedStream.close();
                        } catch (Exception ignored) {
                            // The selected terminal outcome is retained.
                        }
                    });
                } catch (RuntimeException ignored) {
                    // No inline close fallback may block terminal return.
                }
            }
            return true;
        }
    }
}
