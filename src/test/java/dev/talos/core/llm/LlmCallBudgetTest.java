package dev.talos.core.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct unit coverage for {@link LlmCallBudget} (CCR-017).
 *
 * <p>Covers the behaviors the runtime depends on: fast-path with no
 * wall-clock budget, happy path under a budget, wall-clock timeout,
 * idle-chunk watchdog, repetition-breaker watchdog, active-stream close
 * on failure, and close idempotency.
 */
class LlmCallBudgetTest {

    private static final LlmClient.StreamResult OK =
            new LlmClient.StreamResult("reply", List.of());

    @Test
    void zero_wall_clock_runs_work_directly_without_scheduler() {
        try (LlmCallBudget budget = new LlmCallBudget(0L)) {
            AtomicInteger invoked = new AtomicInteger();
            LlmClient.StreamResult result = budget.run(ref -> {
                invoked.incrementAndGet();
                return OK;
            }, 0L, null, "test", null);
            assertSame(OK, result);
            assertEquals(1, invoked.get());
        }
    }

    @Test
    void happy_path_with_budget_returns_work_result() {
        try (LlmCallBudget budget = new LlmCallBudget(0L)) {
            LlmClient.StreamResult result = budget.run(
                    ref -> OK, 5_000L, null, "test", null);
            assertSame(OK, result);
            assertFalse(result.aborted(), "a completed generation must not carry abort metadata");
        }
    }

    @Test
    void wall_clock_timeout_closes_active_stream_and_returns_abort_marker() throws Exception {
        CountDownLatch workStarted = new CountDownLatch(1);
        AtomicBoolean streamClosed = new AtomicBoolean();

        try (LlmCallBudget budget = new LlmCallBudget(0L)) {
            LlmClient.StreamResult result = budget.run(ref -> {
                ref.set(() -> streamClosed.set(true));
                workStarted.countDown();
                try {
                    Thread.sleep(3_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return OK;
            }, 150L, null, "test", null);

            assertNotNull(result);
            assertTrue(result.aborted(), "wall-clock abort must carry abort metadata");
            assertTrue(result.text().contains("[turn aborted"),
                    "expected abort marker, got: " + result.text());
            assertTrue(result.text().contains("wall-clock"),
                    "expected wall-clock abort reason, got: " + result.text());
            assertTrue(result.toolCalls().isEmpty());
            assertTrue(workStarted.await(2, TimeUnit.SECONDS), "work must have started");

            long deadline = System.currentTimeMillis() + 1_500L;
            while (!streamClosed.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(25L);
            }
            assertTrue(streamClosed.get(), "budget must close the active stream on timeout");
        }
    }

    @Test
    void wall_clock_threshold_does_not_abort_while_chunks_are_still_arriving() {
        try (LlmCallBudget budget = new LlmCallBudget(250L)) {
            AtomicLong lastChunkAt = new AtomicLong(System.currentTimeMillis());
            LlmClient.StreamResult result = budget.run(ref -> {
                long deadline = System.currentTimeMillis() + 450L;
                while (System.currentTimeMillis() < deadline) {
                    lastChunkAt.set(System.currentTimeMillis());
                    try {
                        Thread.sleep(50L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return new LlmClient.StreamResult("interrupted", List.of());
                    }
                }
                return OK;
            }, 120L, lastChunkAt, "test", null);

            assertSame(OK, result,
                    "steady chunk progress inside the idle window must not trip the initial wall-clock threshold");
        }
    }

    @Test
    void idle_watchdog_aborts_when_no_chunks_arrive() throws Exception {
        AtomicBoolean streamClosed = new AtomicBoolean();
        try (LlmCallBudget budget = new LlmCallBudget(200L)) {
            AtomicLong lastChunkAt = new AtomicLong(System.currentTimeMillis());
            LlmClient.StreamResult result = budget.run(ref -> {
                ref.set(() -> streamClosed.set(true));
                try {
                    Thread.sleep(5_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return OK;
            }, 10_000L, lastChunkAt, "test", null);

            assertNotNull(result);
            assertTrue(result.aborted(), "idle abort must carry abort metadata");
            assertTrue(result.text().contains("[turn aborted"),
                    "expected abort marker, got: " + result.text());
            assertTrue(result.text().contains("no tokens"),
                    "expected idle abort reason, got: " + result.text());

            long deadline = System.currentTimeMillis() + 1_500L;
            while (!streamClosed.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(25L);
            }
            assertTrue(streamClosed.get(), "idle watchdog must close the active stream");
        }
    }

    @Test
    void repetition_breaker_aborts_when_tripped() throws Exception {
        AtomicBoolean streamClosed = new AtomicBoolean();
        RepetitionBreaker breaker = new RepetitionBreaker(4, 3, 64);
        String probe = "abcd";
        StringBuilder feed = new StringBuilder();
        for (int i = 0; i < 6; i++) feed.append(probe);
        breaker.onChunk(feed.toString());
        assertTrue(breaker.tripped(), "breaker must trip from feed fixture");

        try (LlmCallBudget budget = new LlmCallBudget(0L)) {
            LlmClient.StreamResult result = budget.run(ref -> {
                ref.set(() -> streamClosed.set(true));
                try {
                    Thread.sleep(5_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return OK;
            }, 10_000L, null, "test", breaker);

            assertNotNull(result);
            assertTrue(result.aborted(), "repetition abort must carry abort metadata");
            assertTrue(result.text().contains("[turn aborted"),
                    "expected abort marker, got: " + result.text());
            assertTrue(result.text().contains("repetition loop"),
                    "expected repetition abort reason, got: " + result.text());

            long deadline = System.currentTimeMillis() + 1_500L;
            while (!streamClosed.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(25L);
            }
            assertTrue(streamClosed.get(), "repetition watchdog must close the active stream");
        }
    }

    @Test
    void close_active_stream_is_null_safe_and_idempotent() {
        assertDoesNotThrow(() -> LlmCallBudget.closeActiveStream(null));

        AtomicReference<AutoCloseable> ref = new AtomicReference<>();
        assertDoesNotThrow(() -> LlmCallBudget.closeActiveStream(ref));

        AtomicInteger closes = new AtomicInteger();
        ref.set(closes::incrementAndGet);
        LlmCallBudget.closeActiveStream(ref);
        LlmCallBudget.closeActiveStream(ref);
        assertEquals(1, closes.get(), "closeable must be invoked exactly once");
        assertNull(ref.get(), "ref must be cleared after close");
    }

    @Test
    void close_active_stream_swallows_close_exceptions() {
        AtomicReference<AutoCloseable> ref = new AtomicReference<>(() -> {
            throw new RuntimeException("close failure is best-effort");
        });
        assertDoesNotThrow(() -> LlmCallBudget.closeActiveStream(ref));
        assertNull(ref.get());
    }

    @Test
    void close_shuts_down_executors_and_is_idempotent() {
        LlmCallBudget budget = new LlmCallBudget(1_000L);
        assertDoesNotThrow(budget::close);
        assertDoesNotThrow(budget::close, "double-close must be safe");
    }
}

