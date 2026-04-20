package dev.talos.core.llm;

import dev.talos.spi.EngineException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct unit coverage for {@link LlmRetryExecutor} (CCR-017).
 *
 * <p>Keeps retry counts at 0 or 1 and avoids {@code Thread.sleep} amplification
 * by triggering backoff only in the exhaustion cases where a short
 * {@code tryNumber * 400ms} sleep is acceptable for test runtime.
 */
class LlmRetryExecutorTest {

    @Test
    void returns_value_on_first_success_without_retry() {
        AtomicInteger calls = new AtomicInteger();
        String result = LlmRetryExecutor.execute(3, () -> {
            calls.incrementAndGet();
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(1, calls.get(), "successful attempt should not retry");
    }

    @Test
    void retries_transient_then_succeeds() {
        AtomicInteger calls = new AtomicInteger();
        String result = LlmRetryExecutor.execute(2, () -> {
            if (calls.incrementAndGet() == 1) {
                throw new EngineException.Transient("temporary", 503);
            }
            return "recovered";
        });
        assertEquals("recovered", result);
        assertEquals(2, calls.get(), "should retry exactly once before success");
    }

    @Test
    void throws_last_transient_after_exhausting_retries() {
        AtomicInteger calls = new AtomicInteger();
        EngineException.Transient thrown = assertThrows(
                EngineException.Transient.class,
                () -> LlmRetryExecutor.execute(1, () -> {
                    calls.incrementAndGet();
                    throw new EngineException.Transient("still down " + calls.get(), 503);
                })
        );
        // maxRetries=1 means initial attempt + 1 retry = 2 invocations total.
        assertEquals(2, calls.get());
        assertTrue(thrown.getMessage().contains("still down"));
    }

    @Test
    void zero_max_retries_executes_once_and_rethrows_transient() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(EngineException.Transient.class,
                () -> LlmRetryExecutor.execute(0, () -> {
                    calls.incrementAndGet();
                    throw new EngineException.Transient("nope", 503);
                }));
        assertEquals(1, calls.get(), "maxRetries=0 must not retry");
    }

    @Test
    void non_transient_engine_exception_is_thrown_immediately() {
        AtomicInteger calls = new AtomicInteger();
        EngineException.ModelNotFound thrown = assertThrows(
                EngineException.ModelNotFound.class,
                () -> LlmRetryExecutor.execute(3, () -> {
                    calls.incrementAndGet();
                    throw new EngineException.ModelNotFound("missing-model");
                })
        );
        assertEquals(1, calls.get(), "non-transient engine exception must not retry");
        assertEquals("missing-model", thrown.model());
    }

    @Test
    void generic_exception_is_wrapped_as_response_error() {
        AtomicInteger calls = new AtomicInteger();
        EngineException.ResponseError thrown = assertThrows(
                EngineException.ResponseError.class,
                () -> LlmRetryExecutor.execute(3, () -> {
                    calls.incrementAndGet();
                    throw new IOException("boom");
                })
        );
        assertEquals(1, calls.get(), "wrapped generic exception must not retry");
        assertNotNull(thrown.getCause());
        assertTrue(thrown.getCause() instanceof IOException);
        assertTrue(thrown.getMessage().contains("boom"));
    }

    @Test
    void runtime_exception_is_wrapped_not_propagated_raw() {
        // LlmRetryExecutor catches `Exception` (not `RuntimeException` separately),
        // so a plain RuntimeException must be wrapped as ResponseError.
        EngineException.ResponseError thrown = assertThrows(
                EngineException.ResponseError.class,
                () -> LlmRetryExecutor.execute(0, () -> {
                    throw new IllegalStateException("bug");
                })
        );
        assertNotNull(thrown.getCause());
        assertTrue(thrown.getCause() instanceof IllegalStateException);
    }
}

