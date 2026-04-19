package dev.talos.core.llm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the SPI-level async stream close seam (item 6).
 *
 * <p>When the wall-clock, idle, or repetition watchdog trips in
 * {@link LlmClient#closeActiveStream(AtomicReference)} is the only mechanism
 * that can unblock a worker thread stuck in a synchronous socket read:
 * {@code Thread.interrupt()} alone cannot wake the JDK {@code HttpClient}
 * body reader. These tests pin the contract of the helper so future
 * refactors cannot silently revert to the leak behavior described in the
 * {@code engineAssembledWithMessagesFull} javadoc.
 */
class LlmClientAsyncCloseTest {

    @Test
    void close_invokes_autocloseable_and_nulls_ref() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        AutoCloseable c = closes::incrementAndGet;
        AtomicReference<AutoCloseable> ref = new AtomicReference<>(c);

        LlmClient.closeActiveStream(ref);

        assertEquals(1, closes.get(), "close() must be invoked exactly once");
        assertNull(ref.get(), "ref must be cleared after close so a second caller is a no-op");
    }

    @Test
    void close_is_idempotent_across_multiple_callers() {
        AtomicInteger closes = new AtomicInteger();
        AutoCloseable c = closes::incrementAndGet;
        AtomicReference<AutoCloseable> ref = new AtomicReference<>(c);

        LlmClient.closeActiveStream(ref);
        LlmClient.closeActiveStream(ref); // watchdog + ExecutionException catch
        LlmClient.closeActiveStream(ref);

        assertEquals(1, closes.get(),
                "getAndSet(null) must prevent double-close when watchdog and outer catch both fire");
    }

    @Test
    void close_tolerates_null_ref() {
        assertDoesNotThrow(() -> LlmClient.closeActiveStream(null));
    }

    @Test
    void close_tolerates_empty_ref() {
        AtomicReference<AutoCloseable> ref = new AtomicReference<>(null);
        assertDoesNotThrow(() -> LlmClient.closeActiveStream(ref));
    }

    @Test
    void close_swallows_exceptions_from_autocloseable() {
        AtomicReference<AutoCloseable> ref = new AtomicReference<>(() -> {
            throw new RuntimeException("socket already dead");
        });

        // The watchdog runs on a scheduled executor; an exception thrown
        // from the stream's onClose hook must not escape and kill the
        // watchdog thread or leak into the REPL.
        assertDoesNotThrow(() -> LlmClient.closeActiveStream(ref));
        assertNull(ref.get(), "ref must still be cleared even when close() threw");
    }

    @Test
    void concurrent_close_and_compareAndSet_does_not_double_close() throws Exception {
        // Simulates the race between:
        //   - watchdog thread: closeActiveStream(ref)  [getAndSet(null) + close]
        //   - worker thread:   ref.compareAndSet(stream, null)  [on normal exit]
        AtomicInteger closes = new AtomicInteger();
        AutoCloseable stream = closes::incrementAndGet;
        AtomicReference<AutoCloseable> ref = new AtomicReference<>(stream);

        // Worker-side cleanup fires first (normal-exit path):
        ref.compareAndSet(stream, null);
        // Watchdog tick arrives late:
        LlmClient.closeActiveStream(ref);

        assertEquals(0, closes.get(),
                "when worker cleared the ref first, late watchdog must not close a phantom handle");
        assertNull(ref.get());
    }
}

