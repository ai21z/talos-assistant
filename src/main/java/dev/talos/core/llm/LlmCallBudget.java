package dev.talos.core.llm;

import dev.talos.core.util.UiChrome;

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
        final AtomicReference<AutoCloseable> activeStream = new AtomicReference<>();
        java.util.concurrent.ScheduledFuture<?> watchdog = null;
        CompletableFuture<LlmClient.StreamResult> future;
        long initialChunkAt = lastChunkAt == null ? -1L : lastChunkAt.get();

        if (wallClockMs <= 0) {
            return work.apply(activeStream);
        }

        future = CompletableFuture.supplyAsync(() -> work.apply(activeStream), llmCallExecutor);

        boolean wantIdleWatchdog = defaultIdleMs > 0 && lastChunkAt != null;
        boolean wantRepetitionWatchdog = breaker != null;
        if (wantIdleWatchdog || wantRepetitionWatchdog) {
            long tickMs = wantIdleWatchdog
                    ? Math.max(500L, Math.min(defaultIdleMs / 4L, 5_000L))
                    : 500L;
            final CompletableFuture<LlmClient.StreamResult> futureRef = future;
            watchdog = watchdogExecutor.scheduleAtFixedRate(() -> {
                if (futureRef.isDone()) return;
                if (wantRepetitionWatchdog && breaker.tripped()) {
                    if (futureRef.completeExceptionally(new RepetitionException(
                            breaker.substringLen(), breaker.maxRepeats()))) {
                        closeActiveStream(activeStream);
                    }
                    return;
                }
                if (wantIdleWatchdog) {
                    long since = System.currentTimeMillis() - lastChunkAt.get();
                    if (since > defaultIdleMs) {
                        if (futureRef.completeExceptionally(new IdleStreamException(defaultIdleMs))) {
                            closeActiveStream(activeStream);
                        }
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
                    activeStream,
                    wallClockMs,
                    lastChunkAt,
                    initialChunkAt,
                    label);
        } catch (TimeoutException te) {
            closeActiveStream(activeStream);
            future.cancel(true);
            return wallClockAbort(label, wallClockMs);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof IdleStreamException idle) {
                closeActiveStream(activeStream);
                future.cancel(true);
                return idleAbort(label, idle.idleMs);
            }
            if (cause instanceof RepetitionException repetition) {
                closeActiveStream(activeStream);
                future.cancel(true);
                String msg = UiChrome.TURN_ABORTED_PREFIX + ": " + label + " entered a repetition loop - "
                        + "the same " + repetition.substringLen + "-character pattern repeated "
                        + repetition.maxRepeats + "+ times in the streamed output. "
                        + "Try a smaller model, rephrase the prompt, or clear session memory with /clear.]";
                return new LlmClient.StreamResult(msg, List.of());
            }
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            if (cause instanceof Error error) throw error;
            throw new RuntimeException(cause);
        } catch (InterruptedException ie) {
            closeActiveStream(activeStream);
            future.cancel(true);
            Thread.currentThread().interrupt();
            return new LlmClient.StreamResult(UiChrome.TURN_ABORTED_PREFIX + ": interrupted]", List.of());
        } finally {
            if (watchdog != null) watchdog.cancel(false);
        }
    }

    private LlmClient.StreamResult waitWithProgressAwareWallClock(
            CompletableFuture<LlmClient.StreamResult> future,
            AtomicReference<AutoCloseable> activeStream,
            long wallClockMs,
            AtomicLong lastChunkAt,
            long initialChunkAt,
            String label
    ) throws InterruptedException, ExecutionException, TimeoutException {
        long startedAt = System.currentTimeMillis();
        long firstDeadline = startedAt + wallClockMs;
        boolean observedProgress = false;
        while (true) {
            long now = System.currentTimeMillis();
            long waitMs = Math.max(1L, firstDeadline - now);
            if (now >= firstDeadline) {
                long currentChunkAt = lastChunkAt.get();
                observedProgress = observedProgress || currentChunkAt > initialChunkAt;
                long idleForMs = now - currentChunkAt;
                if (observedProgress && idleForMs <= defaultIdleMs) {
                    waitMs = Math.max(1L, Math.min(idleRemainingMs(idleForMs), watchdogPollMs()));
                } else if (observedProgress) {
                    closeActiveStream(activeStream);
                    future.cancel(true);
                    return idleAbort(label, defaultIdleMs);
                } else {
                    throw new TimeoutException("no chunk progress before wall-clock budget");
                }
            }
            try {
                return future.get(waitMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException timedOut) {
                if (System.currentTimeMillis() < firstDeadline) {
                    throw timedOut;
                }
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
        return new LlmClient.StreamResult(msg, List.of());
    }

    private static LlmClient.StreamResult idleAbort(String label, long idleMs) {
        String msg = UiChrome.TURN_ABORTED_PREFIX + ": " + label + " produced no tokens for "
                + (idleMs / 1000) + "s - model appears wedged. "
                + "Try a smaller model or raise limits.llm_idle_ms in config.]";
        return new LlmClient.StreamResult(msg, List.of());
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

    private static final class IdleStreamException extends RuntimeException {
        final long idleMs;

        IdleStreamException(long idleMs) {
            super("idle stream > " + idleMs + " ms");
            this.idleMs = idleMs;
        }
    }

    private static final class RepetitionException extends RuntimeException {
        final int substringLen;
        final int maxRepeats;

        RepetitionException(int substringLen, int maxRepeats) {
            super("repetition detected: " + substringLen + "-char probe × " + maxRepeats);
            this.substringLen = substringLen;
            this.maxRepeats = maxRepeats;
        }
    }
}
