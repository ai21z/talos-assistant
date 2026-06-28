package dev.talos.runtime;

import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.context.ArtifactGoal;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionMemoryTest {

    @Test void startsEmpty() {
        var mem = new SessionMemory();
        assertNull(mem.get());
        assertFalse(mem.hasContent());
    }

    @Test void startsEmpty_getTurns_returns_empty_list() {
        var mem = new SessionMemory();
        List<ChatMessage> turns = mem.getTurns();
        assertNotNull(turns);
        assertTrue(turns.isEmpty());
    }

    @Test void updateStoresContent() {
        var mem = new SessionMemory();
        mem.update("hello", "world");
        assertTrue(mem.hasContent());
        assertNotNull(mem.get());
        assertTrue(mem.get().contains("hello"));
        assertTrue(mem.get().contains("world"));
    }

    @Test void clearResetsToEmpty() {
        var mem = new SessionMemory();
        mem.update("hello", "world");
        mem.clear();
        assertNull(mem.get());
        assertFalse(mem.hasContent());
    }

    @Test void rollingWindowTrimsOldContent() {
        var mem = new SessionMemory();
        // Fill with content that will exceed MAX_CHARS
        String longInput = "x".repeat(2500);
        String longAnswer = "y".repeat(2500);
        mem.update(longInput, longAnswer);

        // Buffer should be capped at MAX_CHARS
        assertNotNull(mem.get());
        assertTrue(mem.get().length() <= SessionMemory.MAX_CHARS,
                "Buffer length " + mem.get().length() + " exceeds MAX_CHARS " + SessionMemory.MAX_CHARS);
    }

    @Test void multipleUpdatesAppend() {
        var mem = new SessionMemory();
        mem.update("q1", "a1");
        mem.update("q2", "a2");

        String buf = mem.get();
        assertTrue(buf.contains("q1"));
        assertTrue(buf.contains("a1"));
        assertTrue(buf.contains("q2"));
        assertTrue(buf.contains("a2"));
    }

    @Test void rollingWindowDropsOldestOnOverflow() {
        var mem = new SessionMemory();
        // First update: small marker
        mem.update("MARKER_OLD", "ANSWER_OLD");
        // Fill with enough to push the marker out (MAX_CHARS = 64_000)
        for (int i = 0; i < 50; i++) {
            mem.update("q".repeat(1000), "a".repeat(1000));
        }
        // MARKER_OLD should have been trimmed away
        assertFalse(mem.get().contains("MARKER_OLD"),
                "Old content should have been trimmed from the rolling window");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Structured turns (getTurns)
    // ═══════════════════════════════════════════════════════════════════════

    @Test void getTurns_stores_user_and_assistant_messages() {
        var mem = new SessionMemory();
        mem.update("hello", "hi there");
        List<ChatMessage> turns = mem.getTurns();
        assertEquals(2, turns.size());
        assertEquals("user", turns.get(0).role());
        assertEquals("hello", turns.get(0).content());
        assertEquals("assistant", turns.get(1).role());
        assertEquals("hi there", turns.get(1).content());
    }

    @Test void getTurns_accumulates_multiple_pairs() {
        var mem = new SessionMemory();
        mem.update("q1", "a1");
        mem.update("q2", "a2");
        List<ChatMessage> turns = mem.getTurns();
        assertEquals(4, turns.size());
        assertEquals("user", turns.get(0).role());
        assertEquals("q1", turns.get(0).content());
        assertEquals("assistant", turns.get(1).role());
        assertEquals("a1", turns.get(1).content());
        assertEquals("user", turns.get(2).role());
        assertEquals("q2", turns.get(2).content());
        assertEquals("assistant", turns.get(3).role());
        assertEquals("a2", turns.get(3).content());
    }

    @Test void getTurns_returns_unmodifiable_copy() {
        var mem = new SessionMemory();
        mem.update("q", "a");
        List<ChatMessage> turns = mem.getTurns();
        assertThrows(UnsupportedOperationException.class, () -> turns.add(ChatMessage.user("x")),
                "Returned list should be unmodifiable");
        // Original should still have the correct count
        assertEquals(2, mem.getTurns().size());
    }

    @Test void clear_also_clears_structured_turns() {
        var mem = new SessionMemory();
        mem.update("q", "a");
        assertFalse(mem.getTurns().isEmpty());
        mem.clear();
        assertTrue(mem.getTurns().isEmpty(), "Structured turns should be cleared");
    }

    @Test void activeTaskContextDefaultsToNoneAndCanBeReplaced() {
        var mem = new SessionMemory();
        ActiveTaskContext context = ActiveTaskContext.proposedChanges(
                5,
                "trace-active",
                List.of("README.md"),
                "update README");
        ArtifactGoal goal = ArtifactGoal.fromActiveContext(context);

        assertEquals(ActiveTaskContext.State.NONE, mem.activeTaskContext().state());
        assertEquals(ArtifactGoal.ArtifactKind.UNKNOWN, mem.artifactGoal().artifactKind());

        mem.setActiveTaskContext(context);
        mem.setArtifactGoal(goal);

        assertSame(context, mem.activeTaskContext());
        assertSame(goal, mem.artifactGoal());
    }

    @Test void clearResetsActiveTaskContextAndArtifactGoal() {
        var mem = new SessionMemory();
        ActiveTaskContext context = ActiveTaskContext.proposedChanges(
                5,
                "trace-active",
                List.of("README.md"),
                "update README");
        mem.update("q", "a");
        mem.setActiveTaskContext(context);
        mem.setArtifactGoal(ArtifactGoal.fromActiveContext(context));

        mem.clear();

        assertNull(mem.get());
        assertTrue(mem.getTurns().isEmpty());
        assertEquals(ActiveTaskContext.State.NONE, mem.activeTaskContext().state());
        assertEquals(ArtifactGoal.ArtifactKind.UNKNOWN, mem.artifactGoal().artifactKind());
    }

    @Test void clearActiveTaskContextResetsContextAndGoal() {
        var mem = new SessionMemory();
        ActiveTaskContext context = ActiveTaskContext.proposedChanges(
                5,
                "trace-active",
                List.of("README.md"),
                "update README");
        mem.setActiveTaskContext(context);
        mem.setArtifactGoal(ArtifactGoal.fromActiveContext(context));

        mem.clearActiveTaskContext();

        assertEquals(ActiveTaskContext.State.NONE, mem.activeTaskContext().state());
        assertEquals(ArtifactGoal.ArtifactKind.UNKNOWN, mem.artifactGoal().artifactKind());
    }

    @Test void nullSettersNormalizeToNoneAndUnknown() {
        var mem = new SessionMemory();

        mem.setActiveTaskContext(null);
        mem.setArtifactGoal(null);

        assertEquals(ActiveTaskContext.State.NONE, mem.activeTaskContext().state());
        assertEquals(ArtifactGoal.ArtifactKind.UNKNOWN, mem.artifactGoal().artifactKind());
        assertEquals(ActiveTaskContext.Operation.NONE, mem.artifactGoal().operation());
    }

    @Test void getTurns_prunes_oldest_when_exceeding_max() {
        var mem = new SessionMemory();
        // MAX_TURNS is 200 - fill beyond that (110 pairs = 220 messages)
        for (int i = 0; i < 110; i++) {
            mem.update("q" + i, "a" + i);
        }
        // 110 pairs = 220 messages, but capped at MAX_TURNS=200
        List<ChatMessage> turns = mem.getTurns();
        assertTrue(turns.size() <= 200,
                "Turns should be pruned to MAX_TURNS; got " + turns.size());
        // Oldest turns should have been dropped
        assertFalse(turns.stream().anyMatch(m -> "q0".equals(m.content())),
                "Oldest turn should have been pruned");
        // Most recent should still be present
        assertTrue(turns.stream().anyMatch(m -> "q109".equals(m.content())),
                "Most recent turn should be present");
    }

    @Test void hardCapEvictionIsAccountedAsUnsummarizedRawTurnLoss() {
        var mem = new SessionMemory();

        for (int i = 0; i < 110; i++) {
            mem.update("q" + i, "a" + i);
        }

        SessionMemory.RetentionEvictionStats stats = mem.retentionEvictionStats();
        assertEquals(20, stats.rawTurnMessagesEvictedWithoutSketch());
        assertEquals(0, stats.toolEvidenceEntriesEvicted());
    }

    @Test void compactionPruneDoesNotCountAsUnsummarizedHardCapEviction() {
        var mem = new SessionMemory();
        mem.update("q1", "a1");
        mem.update("q2", "a2");

        mem.pruneOldest(2);

        assertEquals(0, mem.retentionEvictionStats().rawTurnMessagesEvictedWithoutSketch());
    }

    @Test void toolEvidenceFifoEvictionIsAccountedAndCleared() {
        var mem = new SessionMemory();

        for (int i = 0; i < 805; i++) {
            mem.recordToolEvidence(i, List.of(new TurnRecord.ToolCallSummary("talos.read_file", "file" + i + ".txt", true)));
        }

        assertEquals(800, mem.toolEvidence().size());
        assertEquals(5, mem.retentionEvictionStats().toolEvidenceEntriesEvicted());
        assertEquals(5, mem.toolEvidence().getFirst().turnNumber());

        mem.clear();

        assertEquals(0, mem.retentionEvictionStats().rawTurnMessagesEvictedWithoutSketch());
        assertEquals(0, mem.retentionEvictionStats().toolEvidenceEntriesEvicted());
    }
}

