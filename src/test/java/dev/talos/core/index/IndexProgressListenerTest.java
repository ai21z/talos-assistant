package dev.talos.core.index;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link IndexProgressListener} contract.
 */
class IndexProgressListenerTest {

    @Nested class NoopListener {

        @Test void noop_doesNotThrow() {
            assertDoesNotThrow(() ->
                IndexProgressListener.NOOP.onFileComplete(1, 10, "foo.java"));
        }

        @Test void noop_acceptsZeroes() {
            assertDoesNotThrow(() ->
                IndexProgressListener.NOOP.onFileComplete(0, 0, ""));
        }
    }

    @Nested class CustomListener {

        @Test void receives_allCallbacks() {
            record Call(int completed, int total, String file) {}
            List<Call> calls = new ArrayList<>();

            IndexProgressListener listener = (completed, total, file) ->
                    calls.add(new Call(completed, total, file));

            listener.onFileComplete(1, 5, "a.java");
            listener.onFileComplete(2, 5, "b.java");
            listener.onFileComplete(3, 5, "c.java");

            assertEquals(3, calls.size());
            assertEquals(new Call(1, 5, "a.java"), calls.getFirst());
            assertEquals(new Call(3, 5, "c.java"), calls.getLast());
        }

        @Test void receives_correctProgressValues() {
            AtomicInteger lastCompleted = new AtomicInteger(-1);
            AtomicInteger lastTotal = new AtomicInteger(-1);

            IndexProgressListener listener = (completed, total, file) -> {
                lastCompleted.set(completed);
                lastTotal.set(total);
            };

            listener.onFileComplete(42, 150, "src/main/Foo.java");

            assertEquals(42, lastCompleted.get());
            assertEquals(150, lastTotal.get());
        }
    }

    @Nested class ThreadSafety {

        @Test void concurrent_invocations_doNotLoseCallbacks() throws Exception {
            int threads = 20;
            AtomicInteger callCount = new AtomicInteger();
            List<String> files = Collections.synchronizedList(new ArrayList<>());

            IndexProgressListener listener = (completed, total, file) -> {
                callCount.incrementAndGet();
                files.add(file);
            };

            CountDownLatch latch = new CountDownLatch(threads);
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                Thread.ofVirtual().start(() -> {
                    listener.onFileComplete(idx + 1, threads, "file" + idx + ".java");
                    latch.countDown();
                });
            }
            latch.await();

            assertEquals(threads, callCount.get(), "All callbacks should be received");
            assertEquals(threads, files.size(), "All file names should be recorded");
        }
    }

    @Nested class PercentageCalculation {

        @Test void progressPercentage_isComputableFromArgs() {
            AtomicInteger lastPct = new AtomicInteger(-1);

            IndexProgressListener listener = (completed, total, file) -> {
                int pct = total > 0 ? (completed * 100) / total : 0;
                lastPct.set(pct);
            };

            listener.onFileComplete(50, 200, "half.java");
            assertEquals(25, lastPct.get());

            listener.onFileComplete(200, 200, "done.java");
            assertEquals(100, lastPct.get());

            listener.onFileComplete(1, 3, "third.java");
            assertEquals(33, lastPct.get());
        }

        @Test void zeroTotal_yieldsZeroPercent() {
            AtomicInteger lastPct = new AtomicInteger(-1);

            IndexProgressListener listener = (completed, total, file) -> {
                int pct = total > 0 ? (completed * 100) / total : 0;
                lastPct.set(pct);
            };

            listener.onFileComplete(0, 0, "empty.java");
            assertEquals(0, lastPct.get());
        }
    }
}

