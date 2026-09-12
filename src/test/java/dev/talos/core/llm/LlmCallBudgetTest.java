package dev.talos.core.llm;

import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ProviderResponseCapture;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    void typedAbandonmentIsIdentityMetadataAndSurvivesReceiptAttachment() {
        var legacy = new LlmClient.StreamResult("partial", List.of(), "", "arbitrary prose",
                ProviderResponseCapture.AbortOrigin.RUNTIME_WATCHDOG);
        var typed = legacy.withAbandonment(ProviderResponseCapture.Abandonment.REPETITION_LOOP);
        var rebound = typed.withProvenance(ProviderResponseCapture.Receipt.none());
        assertTrue(legacy.abandonment().isEmpty());
        assertEquals(ProviderResponseCapture.Abandonment.REPETITION_LOOP,
                typed.abandonment().orElseThrow());
        assertEquals(typed.abandonment(), rebound.abandonment());
        assertEquals(legacy, typed, "public four-component record equality remains unchanged");
        assertEquals(legacy.hashCode(), typed.hashCode());
        assertEquals(legacy.toString(), typed.toString());
        assertThrows(IllegalStateException.class,
                () -> OK.withAbandonment(ProviderResponseCapture.Abandonment.REPETITION_LOOP));
    }

    @Test
    void provenanceMetadataPreservesThePublicFourComponentRecordContract() {
        assertArrayEquals(
                new String[]{"text", "toolCalls", "finishReason", "abortReason"},
                java.util.Arrays.stream(LlmClient.StreamResult.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .toArray(String[]::new));

        LlmClient.StreamResult historical = new LlmClient.StreamResult(
                "reply", List.of(), "stop", "");
        LlmClient.StreamResult evidenced = new LlmClient.StreamResult(
                "reply", List.of(), "stop", "",
                ProviderResponseCapture.AbortOrigin.NONE,
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                true,
                true,
                ProviderResponseCapture.Receipt.none());

        assertEquals(historical, evidenced,
                "evidence sidecar must not redefine established record equality");
        assertEquals(historical.hashCode(), evidenced.hashCode());
        assertEquals(historical.toString(), evidenced.toString());
        assertFalse(historical.normalized());
        assertFalse(historical.truncated());
        assertTrue(evidenced.normalized());
        assertTrue(evidenced.truncated());
    }

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
    void completed_native_tool_result_is_not_flattened_by_terminal_arbitration() {
        ChatMessage.NativeToolCall proposal = new ChatMessage.NativeToolCall(
                "call-1", "talos.read_file", Map.of("path", "README.md"));
        LlmClient.StreamResult nativeResult = new LlmClient.StreamResult(
                "", List.of(proposal), "stop", "",
                ProviderResponseCapture.Completion.PROVIDER_EOS, false, false);

        try (LlmCallBudget budget = new LlmCallBudget(0L)) {
            LlmClient.StreamResult result = budget.run(
                    ref -> nativeResult, 5_000L, null, "test", null);

            assertSame(nativeResult, result);
            assertEquals(List.of(proposal), result.toolCalls());
            assertTrue(result.hasToolCalls());
            assertFalse(result.aborted());
        }
    }

    @Test
    void completed_output_cap_result_is_not_reclassified_as_budget_abort() {
        LlmClient.StreamResult capped = new LlmClient.StreamResult(
                "partial", List.of(), "length", "",
                ProviderResponseCapture.Completion.PROVIDER_LENGTH, false, true);

        try (LlmCallBudget budget = new LlmCallBudget(0L)) {
            LlmClient.StreamResult result = budget.run(
                    ref -> capped, 5_000L, null, "test", null);

            assertSame(capped, result);
            assertTrue(result.outputLimitReached());
            assertTrue(result.truncated());
            assertFalse(result.aborted());
        }
    }

    @Test
    void worker_failure_is_rethrown_without_a_synthetic_abort() {
        IllegalStateException expected = new IllegalStateException("provider failed");

        try (LlmCallBudget budget = new LlmCallBudget(0L)) {
            IllegalStateException actual = assertThrows(
                    IllegalStateException.class,
                    () -> budget.run(ref -> {
                        throw expected;
                    }, 5_000L, null, "test", null));

            assertSame(expected, actual);
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
            assertEquals(ProviderResponseCapture.Abandonment.WALL_CLOCK_TIMEOUT,
                    result.abandonment().orElseThrow());
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
            assertEquals(ProviderResponseCapture.Abandonment.IDLE_TIMEOUT,
                    result.abandonment().orElseThrow());
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
            assertEquals(ProviderResponseCapture.Abandonment.REPETITION_LOOP,
                    result.abandonment().orElseThrow());
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

    @Test
    void explicit_generation_scope_crosses_worker_without_inheriting_thread_local_state() {
        LedgerListener ledger = new LedgerListener();
        ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(ledger, "turn-worker-hop");
        ProviderResponseCapture.Generation generation = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        AtomicReference<ProviderResponseCapture.Scope> workerThreadLocal = new AtomicReference<>();

        try (LlmCallBudget budget = new LlmCallBudget(0L)) {
            LlmClient.StreamResult result = budget.run(ref -> {
                workerThreadLocal.set(ProviderResponseCapture.currentScope());
                ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                        ProviderResponseCapture.AttemptKind.PRIMARY,
                        ProviderResponseCapture.RetryReason.NONE);
                attempt.requestDispatched();
                ProviderResponseCapture.Receipt receipt = attempt.respond(response("reply"));
                generation.complete(receipt);
                return OK;
            }, 5_000L, null, "test", null, null, generation);

            assertSame(OK, result);
        } finally {
            ProviderResponseCapture.clear();
        }

        assertNull(workerThreadLocal.get(),
                "cached worker must receive only the explicit generation, never turn ThreadLocal state");
        assertEquals(1, ledger.starts.size());
        assertEquals(1, ledger.responses.size());
        assertEquals(1, ledger.completions.size());
        assertEquals(
                ledger.starts.getFirst().attemptId(),
                ledger.completions.getFirst().selectedResponse().attemptId());
    }

    @Test
    void pooled_worker_reuse_keeps_turn_scopes_isolated() {
        LedgerListener first = new LedgerListener();
        LedgerListener second = new LedgerListener();
        AtomicReference<Thread> firstWorker = new AtomicReference<>();
        AtomicReference<Thread> secondWorker = new AtomicReference<>();
        try (LlmCallBudget budget = new LlmCallBudget(0L)) {
            runSuccessfulExplicitAttempt(budget, first, "turn-one", firstWorker);
            runSuccessfulExplicitAttempt(budget, second, "turn-two", secondWorker);
        } finally {
            ProviderResponseCapture.clear();
        }

        assertEquals(1, first.responses.size());
        assertEquals(1, second.responses.size());
        assertTrue(first.responses.getFirst().attempt().generationId().startsWith("turn-one-"));
        assertTrue(second.responses.getFirst().attempt().generationId().startsWith("turn-two-"));
        assertNotEquals(
                first.responses.getFirst().attempt().generationId(),
                second.responses.getFirst().attempt().generationId());
        assertNull(ProviderResponseCapture.currentScope(), "test cleanup must not leave a scope behind");
        assertNotNull(firstWorker.get());
        assertNotNull(secondWorker.get());
    }

    @Test
    void timeout_abandons_once_and_ignores_late_worker_response() throws Exception {
        LedgerListener ledger = new LedgerListener();
        ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(ledger, "turn-timeout");
        ProviderResponseCapture.Generation generation = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        CountDownLatch attemptStarted = new CountDownLatch(1);
        CountDownLatch releaseLateWorker = new CountDownLatch(1);
        CountDownLatch lateWorkerFinished = new CountDownLatch(1);

        try (LlmCallBudget budget = new LlmCallBudget(0L)) {
            LlmClient.StreamResult result = budget.run(ref -> {
                ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                        ProviderResponseCapture.AttemptKind.PRIMARY,
                        ProviderResponseCapture.RetryReason.NONE);
                attempt.requestDispatched();
                attemptStarted.countDown();
                while (releaseLateWorker.getCount() > 0) {
                    try {
                        releaseLateWorker.await(10, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {
                        // Deliberately emulate a provider worker that ignores cancellation.
                    }
                }
                ProviderResponseCapture.Receipt late = attempt.respond(response("late reply"));
                generation.complete(late);
                lateWorkerFinished.countDown();
                return OK;
            }, 100L, null, "test", null, null, generation);

            assertTrue(attemptStarted.await(1, TimeUnit.SECONDS));
            assertTrue(result.aborted());
            assertEquals(ProviderResponseCapture.AbortOrigin.RUNTIME_WATCHDOG, result.abortOrigin());
            releaseLateWorker.countDown();
            assertTrue(lateWorkerFinished.await(2, TimeUnit.SECONDS));
        } finally {
            ProviderResponseCapture.clear();
        }

        assertEquals(1, ledger.abandonments.size());
        assertEquals(
                ProviderResponseCapture.Abandonment.WALL_CLOCK_TIMEOUT,
                ledger.abandonments.getFirst().abandonment());
        assertTrue(ledger.responses.isEmpty(), "late provider completion must not reopen an abandoned attempt");
        assertTrue(ledger.completions.isEmpty(), "late completion must not complete the logical generation");
    }

    @Test
    void operator_abort_claim_blocks_late_worker_success_until_abandonment_is_published()
            throws Exception {
        BlockingAbandonmentListener ledger = new BlockingAbandonmentListener();
        ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(
                ledger, "turn-cancel-success-race");
        ProviderResponseCapture.Generation generation = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch workStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch workerReturned = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();

        try (LlmCallBudget budget = new LlmCallBudget(0L)) {
            Future<LlmClient.StreamResult> call = caller.submit(() -> budget.run(ref -> {
                workStarted.countDown();
                awaitIgnoringInterrupt(releaseWorker);
                workerReturned.countDown();
                return OK;
            }, 10_000L, null, "test", null, cancelled::get, generation));

            assertTrue(workStarted.await(2, TimeUnit.SECONDS));
            cancelled.set(true);
            assertTrue(ledger.abandonmentEntered.await(2, TimeUnit.SECONDS),
                    "watchdog must claim cancellation and enter the generation callback");

            releaseWorker.countDown();
            assertTrue(workerReturned.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> call.get(150, TimeUnit.MILLISECONDS),
                    "late worker success must not publish while abandonment is unfinished");

            ledger.releaseAbandonment.countDown();
            LlmClient.StreamResult result = call.get(2, TimeUnit.SECONDS);
            assertTrue(result.aborted());
            assertEquals(ProviderResponseCapture.AbortOrigin.OPERATOR_EXTERNAL, result.abortOrigin());
            assertEquals(1, ledger.abandonments.size());
            assertEquals(
                    ProviderResponseCapture.Abandonment.OPERATOR_CANCELLED,
                    ledger.abandonments.getFirst().abandonment());
            assertTrue(ledger.completions.isEmpty());
        } finally {
            releaseWorker.countDown();
            ledger.releaseAbandonment.countDown();
            caller.shutdownNow();
            ProviderResponseCapture.clear();
        }
    }

    @Test
    void watchdog_outcome_does_not_wait_for_a_close_resistant_stream() throws Exception {
        RepetitionBreaker breaker = new RepetitionBreaker(4, 3, 64);
        breaker.onChunk("abcdabcdabcdabcdabcdabcd");
        assertTrue(breaker.tripped());
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);

        try (LlmCallBudget budget = new LlmCallBudget(0L)) {
            LlmClient.StreamResult result = budget.run(ref -> {
                ref.set(() -> {
                    closeEntered.countDown();
                    awaitIgnoringInterrupt(releaseClose);
                });
                awaitIgnoringInterrupt(releaseWorker);
                return OK;
            }, 10_000L, null, "test", breaker);

            assertTrue(result.aborted());
            assertEquals(ProviderResponseCapture.AbortOrigin.RUNTIME_WATCHDOG, result.abortOrigin());
            assertTrue(closeEntered.await(2, TimeUnit.SECONDS),
                    "watchdog must still attempt to close the provider stream");
        } finally {
            releaseClose.countDown();
            releaseWorker.countDown();
        }
    }

    @Test
    void detached_provenance_generation_does_not_disable_pre_stream_timeout() throws Exception {
        ProviderResponseCapture.clear();
        ProviderResponseCapture.Generation detached = ProviderResponseCapture.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        assertTrue(detached.generationId().isBlank());
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch workerFinished = new CountDownLatch(1);

        try (LlmCallBudget budget = new LlmCallBudget(0L)) {
            LlmClient.StreamResult result = budget.run(ref -> {
                awaitIgnoringInterrupt(releaseWorker);
                workerFinished.countDown();
                return OK;
            }, 100L, null, "test", null, null, detached);

            assertTrue(result.aborted(),
                    "a detached trace generation must not suppress the runtime budget");
            assertEquals(ProviderResponseCapture.AbortOrigin.RUNTIME_WATCHDOG, result.abortOrigin());
            releaseWorker.countDown();
            assertTrue(workerFinished.await(2, TimeUnit.SECONDS));
        } finally {
            releaseWorker.countDown();
            ProviderResponseCapture.clear();
        }
    }

    @Test
    void operator_abort_claim_discards_a_late_worker_failure() throws Exception {
        BlockingAbandonmentListener ledger = new BlockingAbandonmentListener();
        ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(
                ledger, "turn-cancel-failure-race");
        ProviderResponseCapture.Generation generation = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch workStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch workerFailed = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();

        try (LlmCallBudget budget = new LlmCallBudget(0L)) {
            Future<LlmClient.StreamResult> call = caller.submit(() -> budget.run(ref -> {
                workStarted.countDown();
                awaitIgnoringInterrupt(releaseWorker);
                workerFailed.countDown();
                throw new IllegalStateException("late provider failure");
            }, 10_000L, null, "test", null, cancelled::get, generation));

            assertTrue(workStarted.await(2, TimeUnit.SECONDS));
            cancelled.set(true);
            assertTrue(ledger.abandonmentEntered.await(2, TimeUnit.SECONDS));

            releaseWorker.countDown();
            assertTrue(workerFailed.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> call.get(150, TimeUnit.MILLISECONDS),
                    "late worker failure must not replace the claimed abort");

            ledger.releaseAbandonment.countDown();
            LlmClient.StreamResult result = call.get(2, TimeUnit.SECONDS);
            assertTrue(result.aborted());
            assertEquals(ProviderResponseCapture.AbortOrigin.OPERATOR_EXTERNAL, result.abortOrigin());
            assertEquals(1, ledger.abandonments.size());
            assertTrue(ledger.completions.isEmpty());
        } finally {
            releaseWorker.countDown();
            ledger.releaseAbandonment.countDown();
            caller.shutdownNow();
            ProviderResponseCapture.clear();
        }
    }

    @Test
    void completed_provider_generation_wins_over_a_later_watchdog_observation()
            throws Exception {
        LedgerListener ledger = new LedgerListener();
        ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(
                ledger, "turn-provider-terminal-winner");
        ProviderResponseCapture.Generation generation = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch providerCompleted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();

        try (LlmCallBudget budget = new LlmCallBudget(0L)) {
            Future<LlmClient.StreamResult> call = caller.submit(() -> budget.run(ref -> {
                ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                        ProviderResponseCapture.AttemptKind.PRIMARY,
                        ProviderResponseCapture.RetryReason.NONE);
                attempt.requestDispatched();
                ProviderResponseCapture.Receipt receipt = attempt.respond(response("winner"));
                generation.complete(receipt);
                providerCompleted.countDown();
                awaitIgnoringInterrupt(releaseWorker);
                return OK;
            }, 10_000L, null, "test", null, cancelled::get, generation));

            assertTrue(providerCompleted.await(2, TimeUnit.SECONDS));
            cancelled.set(true);
            Thread.sleep(250L);
            assertFalse(call.isDone(),
                    "watchdog must leave the caller outcome for the completed provider worker");
            releaseWorker.countDown();
            assertSame(OK, call.get(2, TimeUnit.SECONDS));
            assertEquals(1, ledger.completions.size());
            assertTrue(ledger.abandonments.isEmpty());
        } finally {
            releaseWorker.countDown();
            caller.shutdownNow();
            ProviderResponseCapture.clear();
        }
    }

    private static void runSuccessfulExplicitAttempt(
            LlmCallBudget budget,
            LedgerListener ledger,
            String scopeId,
            AtomicReference<Thread> worker) {
        ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(ledger, scopeId);
        ProviderResponseCapture.Generation generation = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        budget.run(ref -> {
            worker.set(Thread.currentThread());
            assertNull(ProviderResponseCapture.currentScope());
            ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                    ProviderResponseCapture.AttemptKind.PRIMARY,
                    ProviderResponseCapture.RetryReason.NONE);
            attempt.requestDispatched();
            ProviderResponseCapture.Receipt receipt = attempt.respond(response(scopeId));
            generation.complete(receipt);
            return OK;
        }, 5_000L, null, "test", null, null, generation);
    }

    private static ProviderResponseCapture.Response response(String answer) {
        return new ProviderResponseCapture.Response(
                answer,
                0,
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                false);
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        while (latch.getCount() > 0) {
            try {
                latch.await();
            } catch (InterruptedException ignored) {
                // Deliberately emulate close-resistant provider work.
            }
        }
    }

    private static final class LedgerListener implements ProviderResponseCapture.Listener {
        final List<ProviderResponseCapture.AttemptEvent> starts = new CopyOnWriteArrayList<>();
        final List<ProviderResponseCapture.AttemptResponse> responses = new CopyOnWriteArrayList<>();
        final List<ProviderResponseCapture.AttemptAbandonment> abandonments = new CopyOnWriteArrayList<>();
        final List<ProviderResponseCapture.GenerationCompletion> completions = new CopyOnWriteArrayList<>();

        @Override
        public void attemptStarted(ProviderResponseCapture.AttemptEvent event) {
            starts.add(event);
        }

        @Override
        public ProviderResponseCapture.Receipt responseReturned(
                ProviderResponseCapture.AttemptResponse event) {
            responses.add(event);
            return null;
        }

        @Override
        public void attemptAbandoned(ProviderResponseCapture.AttemptAbandonment event) {
            abandonments.add(event);
        }

        @Override
        public void generationCompleted(ProviderResponseCapture.GenerationCompletion event) {
            completions.add(event);
        }
    }

    private static final class BlockingAbandonmentListener implements ProviderResponseCapture.Listener {
        final List<ProviderResponseCapture.GenerationAbandonment> abandonments =
                new CopyOnWriteArrayList<>();
        final List<ProviderResponseCapture.GenerationCompletion> completions =
                new CopyOnWriteArrayList<>();
        final CountDownLatch abandonmentEntered = new CountDownLatch(1);
        final CountDownLatch releaseAbandonment = new CountDownLatch(1);

        @Override
        public void generationAbandoned(ProviderResponseCapture.GenerationAbandonment event) {
            abandonments.add(event);
            abandonmentEntered.countDown();
            awaitIgnoringInterrupt(releaseAbandonment);
        }

        @Override
        public void generationCompleted(ProviderResponseCapture.GenerationCompletion event) {
            completions.add(event);
        }
    }
}

