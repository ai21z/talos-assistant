package dev.talos.core.rag;

import dev.talos.core.context.ContextPacker;
import dev.talos.core.context.ContextResult;
import dev.talos.core.context.TokenBudget;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that {@link RagService.Answer} semantics are correct:
 * - citations come from packed context (what the model saw), not from pre-packed retrieval
 * - packedContext is available on the Answer record
 * - backwards-compatible constructor still works
 */
class AnswerSemanticsTest {

    @Test
    void answer_packedContext_isAccessible() {
        var packed = packWith(List.of(
                snip("A.java#0", "content A")
        ), new TokenBudget(100_000));

        var answer = new RagService.Answer("response", packed.citations(), null, packed);

        assertNotNull(answer.packedContext());
        assertEquals(1, answer.packedContext().finalCount());
        assertEquals(List.of("A.java"), answer.packedContext().citations());
    }

    @Test
    void answer_citations_matchPackedNotRetrieved() {
        // Simulate: retrieved 3 snippets, but packing drops 1 due to budget
        var retrieved = new RagService.Prepared(
                List.of(
                        snip("A.java#0", "a".repeat(300)),
                        snip("B.java#0", "b".repeat(300)),
                        snip("C.java#0", "c".repeat(300))
                ),
                List.of("A.java", "B.java", "C.java")
        );

        // Tight budget: fits A + B but not C
        var budget = new TokenBudget(500, 0.30, 100);
        var packed = packWith(List.of(
                snip("A.java#0", "a".repeat(300)),
                snip("B.java#0", "b".repeat(300)),
                snip("C.java#0", "c".repeat(300))
        ), budget);

        // Answer should use packed citations, not retrieved citations
        var answer = new RagService.Answer("response", packed.citations(), retrieved, packed);

        // Packed citations should be subset of retrieved citations
        assertTrue(answer.citations().size() <= retrieved.citations().size());
        // Every packed citation must exist in retrieved set
        for (String c : answer.citations()) {
            assertTrue(retrieved.citations().contains(c),
                    "packed citation " + c + " should exist in retrieved set");
        }
        // Packed citations should only include files that survived packing
        for (String c : answer.citations()) {
            boolean found = answer.packedContext().snippets().stream()
                    .anyMatch(s -> stripChunk(s.path()).equals(c));
            assertTrue(found, "citation " + c + " should correspond to a packed snippet");
        }
    }

    @Test
    void answer_backwardsCompatibleConstructor_works() {
        var answer = new RagService.Answer("text", List.of("citation"));

        assertEquals("text", answer.text());
        assertEquals(List.of("citation"), answer.citations());
        assertNull(answer.prepared());
        assertNull(answer.packedContext());
    }

    // ───── helpers ─────

    private static ContextResult packWith(List<ContextResult.Snippet> regular, TokenBudget budget) {
        var packer = new ContextPacker(budget);
        return packer.pack("system prompt", "user query", List.of(), regular);
    }

    private static ContextResult.Snippet snip(String path, String text) {
        return new ContextResult.Snippet(path, text);
    }

    private static String stripChunk(String path) {
        int i = path.indexOf('#');
        return (i < 0) ? path : path.substring(0, i);
    }
}

