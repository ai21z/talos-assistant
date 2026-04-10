package dev.talos.core.embed;

import dev.talos.core.spi.Embeddings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InstructionEmbeddings} — prefix injection, delegation, batch path.
 */
class InstructionEmbeddingsTest {

    // ── Prefix injection ────────────────────────────────────────────────

    @Test
    void embedPrependsInstructionPrefix() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        Embeddings inner = new StubEmbeddings() {
            @Override public float[] embed(String text) { captured.set(text); return new float[]{1f}; }
        };

        InstructionEmbeddings wrapped = new InstructionEmbeddings(inner, "search_query: ");
        wrapped.embed("what is Java?");

        assertEquals("search_query: what is Java?", captured.get());
    }

    @Test
    void embedBatchPrependsInstructionPrefixViaBatchDelegate() throws Exception {
        AtomicReference<List<String>> captured = new AtomicReference<>();

        // Delegate that implements BatchEmbeddings so the batch path is used
        BatchEmbeddings batchInner = new BatchEmbeddings() {
            @Override public int dimension() { return 1; }
            @Override public float[] embed(String text) { return new float[]{1f}; }
            @Override public List<float[]> embedBatch(List<String> texts) {
                captured.set(new ArrayList<>(texts));
                return texts.stream().map(t -> new float[]{1f}).toList();
            }
        };

        InstructionEmbeddings wrapped = new InstructionEmbeddings(batchInner, "Instruct: Retrieve\nQuery: ");
        wrapped.embedBatch(List.of("alpha", "beta"));

        List<String> result = captured.get();
        assertNotNull(result);
        assertEquals(2, result.size());
        assertTrue(result.get(0).startsWith("Instruct: Retrieve\nQuery: "));
        assertTrue(result.get(1).startsWith("Instruct: Retrieve\nQuery: "));
        assertTrue(result.get(0).endsWith("alpha"));
        assertTrue(result.get(1).endsWith("beta"));
    }

    @Test
    void embedBatchFallsBackToSingleEmbedForNonBatchDelegate() throws Exception {
        List<String> captured = new ArrayList<>();
        Embeddings inner = new StubEmbeddings() {
            @Override public float[] embed(String text) { captured.add(text); return new float[]{1f}; }
        };

        InstructionEmbeddings wrapped = new InstructionEmbeddings(inner, "q: ");
        List<float[]> results = wrapped.embedBatch(List.of("a", "b"));

        assertEquals(2, results.size());
        assertEquals("q: a", captured.get(0));
        assertEquals("q: b", captured.get(1));
    }

    @Test
    void emptyPrefixPassesTextUnchanged() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        Embeddings inner = new StubEmbeddings() {
            @Override public float[] embed(String text) { captured.set(text); return new float[]{1f}; }
        };

        InstructionEmbeddings wrapped = new InstructionEmbeddings(inner, "");
        wrapped.embed("hello");

        assertEquals("hello", captured.get());
    }

    @Test
    void nullTextTreatedAsEmptyString() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        Embeddings inner = new StubEmbeddings() {
            @Override public float[] embed(String text) { captured.set(text); return new float[]{1f}; }
        };

        InstructionEmbeddings wrapped = new InstructionEmbeddings(inner, "q: ");
        wrapped.embed(null);

        assertEquals("q: ", captured.get(), "null text should be coerced to empty string");
    }

    // ── Delegation ──────────────────────────────────────────────────────

    @Test
    void returnValuePassesThroughUnmodified() throws Exception {
        float[] expected = {0.1f, 0.2f, 0.3f};
        Embeddings inner = new StubEmbeddings() {
            @Override public float[] embed(String text) { return expected; }
        };

        InstructionEmbeddings wrapped = new InstructionEmbeddings(inner, "prefix: ");
        float[] result = wrapped.embed("test");

        assertSame(expected, result, "Must return the delegate's exact array, not a copy");
    }

    @Test
    void dimensionDelegatesToInner() throws Exception {
        Embeddings inner = new StubEmbeddings() {
            @Override public int dimension() { return 768; }
        };

        InstructionEmbeddings wrapped = new InstructionEmbeddings(inner, "prefix: ");
        assertEquals(768, wrapped.dimension());
    }

    // ── Accessors ───────────────────────────────────────────────────────

    @Test
    void prefixAccessorReturnsConfiguredPrefix() {
        Embeddings inner = new StubEmbeddings();
        InstructionEmbeddings wrapped = new InstructionEmbeddings(inner, "search_query: ");
        assertEquals("search_query: ", wrapped.prefix());
    }

    @Test
    void delegateAccessorReturnsInner() {
        Embeddings inner = new StubEmbeddings();
        InstructionEmbeddings wrapped = new InstructionEmbeddings(inner, "prefix: ");
        assertSame(inner, wrapped.delegate());
    }

    // ── Constructor validation ──────────────────────────────────────────

    @Test
    void nullDelegateThrows() {
        assertThrows(NullPointerException.class,
                () -> new InstructionEmbeddings(null, "prefix: "));
    }

    @Test
    void nullPrefixThrows() {
        Embeddings inner = new StubEmbeddings();
        assertThrows(NullPointerException.class,
                () -> new InstructionEmbeddings(inner, null));
    }

    // ── Stub ────────────────────────────────────────────────────────────

    /** Minimal stub satisfying the Embeddings interface. */
    private static class StubEmbeddings implements Embeddings {
        @Override public int dimension() { return 1; }
        @Override public float[] embed(String text) { return new float[]{0f}; }
    }
}


