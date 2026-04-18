package dev.talos.cli.repl;

import dev.talos.core.context.ConversationManager;
import dev.talos.core.context.TokenBudget;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.SessionData;
import dev.talos.runtime.TurnRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prompt 1 — snapshot + JSONL reconciliation.
 *
 * <p>Verifies the bootstrap load path:
 * <ul>
 *   <li>When a snapshot with turns exists, snapshot wins (JSONL ignored).</li>
 *   <li>When no snapshot exists but JSONL does (crash path), JSONL is
 *       replayed into memory.</li>
 *   <li>When a snapshot exists but has zero turns and JSONL has turns,
 *       JSONL is replayed as the fallback.</li>
 *   <li>When neither exists, memory stays empty.</li>
 * </ul>
 */
class TalosBootstrapReconcileTest {

    private static ConversationManager cm(SessionMemory mem) {
        return new ConversationManager(mem, new TokenBudget());
    }

    @Test
    void snapshotWinsWhenPresentWithTurns(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "ws-1";

        // Snapshot has one paired turn.
        store.save(new SessionData(sid, "/ws", "", 1, Instant.now(),
                List.of(new SessionData.Turn("user", "from-snap-u"),
                        new SessionData.Turn("assistant", "from-snap-a"))));

        // JSONL has a *different* turn — must be ignored when snapshot wins.
        store.appendTurn(sid, new TurnRecord(1, Instant.now(), 0L,
                "from-jsonl-u", "from-jsonl-a", List.of(), 0, 0, 0, ""));

        SessionMemory mem = new SessionMemory();
        int snap = TalosBootstrap.replaySnapshot(store, sid, mem, cm(mem));
        assertEquals(1, snap, "snapshot replay count");
        // Fallback must NOT run because snap > 0.
        String buf = mem.get();
        assertNotNull(buf);
        assertTrue(buf.contains("from-snap-u"));
        assertTrue(buf.contains("from-snap-a"));
        assertFalse(buf.contains("from-jsonl-u"),
                "JSONL content must not leak in when snapshot has turns");
    }

    @Test
    void jsonlFallbackUsedWhenSnapshotMissing(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "ws-2";

        // No snapshot — simulate crash before onSessionEnd fired.
        store.appendTurn(sid, new TurnRecord(1, Instant.now(), 0L,
                "q1", "a1", List.of(), 0, 0, 0, ""));
        store.appendTurn(sid, new TurnRecord(2, Instant.now(), 0L,
                "q2", "a2", List.of(), 0, 0, 0, ""));

        SessionMemory mem = new SessionMemory();
        int snap = TalosBootstrap.replaySnapshot(store, sid, mem, cm(mem));
        assertEquals(0, snap, "no snapshot, no pairs");

        int replayed = TalosBootstrap.replayTurnLog(store, sid, mem);
        assertEquals(2, replayed);
        String buf = mem.get();
        assertTrue(buf.contains("q1") && buf.contains("a1"));
        assertTrue(buf.contains("q2") && buf.contains("a2"));
    }

    @Test
    void jsonlFallbackUsedWhenSnapshotHasZeroTurns(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "ws-3";

        // Snapshot exists but empty (e.g., save fired with a session that
        // had no turns yet — defensive case).
        store.save(new SessionData(sid, "/ws", "", 0, Instant.now(), List.of()));
        store.appendTurn(sid, new TurnRecord(1, Instant.now(), 0L,
                "only-in-jsonl-u", "only-in-jsonl-a", List.of(), 0, 0, 0, ""));

        SessionMemory mem = new SessionMemory();
        int snap = TalosBootstrap.replaySnapshot(store, sid, mem, cm(mem));
        assertEquals(0, snap);

        int replayed = TalosBootstrap.replayTurnLog(store, sid, mem);
        assertEquals(1, replayed);
        assertTrue(mem.get().contains("only-in-jsonl-u"));
        assertTrue(mem.get().contains("only-in-jsonl-a"));
    }

    @Test
    void nothingToReplayWhenBothAbsent(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        SessionMemory mem = new SessionMemory();
        int snap = TalosBootstrap.replaySnapshot(store, "ws-4", mem, cm(mem));
        int tlog = TalosBootstrap.replayTurnLog(store, "ws-4", mem);
        assertEquals(0, snap);
        assertEquals(0, tlog);
        assertFalse(mem.hasContent());
    }

    @Test
    void turnRecordsWithBlankTextAreSkipped(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "ws-5";
        store.appendTurn(sid, new TurnRecord(1, Instant.now(), 0L,
                "", "", List.of(), 0, 0, 0, ""));
        store.appendTurn(sid, new TurnRecord(2, Instant.now(), 0L,
                "real-u", "real-a", List.of(), 0, 0, 0, ""));

        SessionMemory mem = new SessionMemory();
        int replayed = TalosBootstrap.replayTurnLog(store, sid, mem);
        assertEquals(1, replayed, "blank-pair records are skipped");
        assertTrue(mem.get().contains("real-u"));
    }
}

