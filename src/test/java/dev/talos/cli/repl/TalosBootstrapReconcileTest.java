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
        var snap = TalosBootstrap.replaySnapshot(store, sid, mem, cm(mem));
        assertEquals(1, snap.pairsReplayed(), "snapshot replay count");
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
        var snap = TalosBootstrap.replaySnapshot(store, sid, mem, cm(mem));
        assertEquals(0, snap.pairsReplayed(), "no snapshot, no pairs");

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
        var snap = TalosBootstrap.replaySnapshot(store, sid, mem, cm(mem));
        assertEquals(0, snap.pairsReplayed());

        int replayed = TalosBootstrap.replayTurnLog(store, sid, mem);
        assertEquals(1, replayed);
        assertTrue(mem.get().contains("only-in-jsonl-u"));
        assertTrue(mem.get().contains("only-in-jsonl-a"));
    }

    @Test
    void nothingToReplayWhenBothAbsent(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        SessionMemory mem = new SessionMemory();
        var snap = TalosBootstrap.replaySnapshot(store, "ws-4", mem, cm(mem));
        int tlog = TalosBootstrap.replayTurnLog(store, "ws-4", mem);
        assertEquals(0, snap.pairsReplayed());
        assertEquals(0, tlog);
        assertFalse(mem.hasContent());
    }

    @Test
    void snapshotSkipsNonOkAssistantTurns(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "ws-9";

        store.save(new SessionData(sid, "/ws", "", 2, Instant.now(),
                List.of(
                        new SessionData.Turn("user", "u1", ""),
                        new SessionData.Turn("assistant", "poison", "error"),
                        new SessionData.Turn("user", "u2", ""),
                        new SessionData.Turn("assistant", "clean", "ok")
                ),
                "ollama/qwen2.5-coder:14b"));

        SessionMemory mem = new SessionMemory();
        var snap = TalosBootstrap.replaySnapshot(store, sid, mem, cm(mem));
        assertEquals(1, snap.pairsReplayed());
        assertEquals("ollama/qwen2.5-coder:14b", snap.model());
        assertTrue(mem.get().contains("u2"));
        assertFalse(mem.get().contains("poison"));
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

    /**
     * Cross-session hallucination guard: an "aborted" turn (wall-clock
     * timeout, idle watchdog, or interrupt) must not re-enter SessionMemory
     * on the next session. Real incident: gemma4:26b fell into a repetition
     * attractor, the turn timed out at 300s, and on the next REPL start the
     * 200-line confabulated body was replayed as authoritative history.
     */
    @Test
    void abortedTurnIsSkippedOnReplay(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "ws-6";

        // A turn that timed out — persisted by JsonTurnLogAppender with
        // status="aborted" (the abortedText below mirrors what LlmClient
        // emits on wall-clock expiry). The garbage prose that streamed
        // before the timeout is captured in assistantText.
        store.appendTurn(sid, new TurnRecord(1, Instant.now(), 387_800L,
                "user turn 1",
                "The user's prompt is 'The user's prompt is 'The user's prompt is",
                List.of(), 0, 0, 0, "", "aborted"));
        // A legitimate turn afterwards — must still replay.
        store.appendTurn(sid, new TurnRecord(2, Instant.now(), 0L,
                "user turn 2", "clean reply", List.of(), 0, 0, 0, "", "ok"));

        SessionMemory mem = new SessionMemory();
        int replayed = TalosBootstrap.replayTurnLog(store, sid, mem);
        assertEquals(1, replayed, "only the ok turn is replayed");
        String buf = mem.get();
        assertTrue(buf.contains("user turn 2") && buf.contains("clean reply"));
        assertFalse(buf.contains("The user's prompt is"),
                "aborted turn's confabulated body must not enter memory");
    }

    /**
     * Non-ok statuses other than "aborted" are also non-conversational
     * (error, info, stream-lifecycle) and must be filtered out on replay.
     */
    @Test
    void errorAndInfoTurnsAreSkippedOnReplay(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "ws-7";

        store.appendTurn(sid, new TurnRecord(1, Instant.now(), 0L,
                "u-err", "tool crashed", List.of(), 0, 0, 0, "", "error"));
        store.appendTurn(sid, new TurnRecord(2, Instant.now(), 0L,
                "u-info", "some info line", List.of(), 0, 0, 0, "", "info"));
        store.appendTurn(sid, new TurnRecord(3, Instant.now(), 0L,
                "u-ok", "real answer", List.of(), 0, 0, 0, "", "ok"));

        SessionMemory mem = new SessionMemory();
        int replayed = TalosBootstrap.replayTurnLog(store, sid, mem);
        assertEquals(1, replayed);
        String buf = mem.get();
        assertTrue(buf.contains("u-ok") && buf.contains("real answer"));
        assertFalse(buf.contains("tool crashed"));
        assertFalse(buf.contains("some info line"));
    }

    /**
     * Back-compat: legacy JSONL records written before the status field
     * existed serialize status="" on read. These must still replay, or we
     * break session restoration for anyone upgrading from a pre-status
     * build.
     */
    @Test
    void legacyBlankStatusRecordsStillReplay(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "ws-8";
        store.appendTurn(sid, new TurnRecord(1, Instant.now(), 0L,
                "legacy-u", "legacy-a", List.of(), 0, 0, 0, "", ""));

        SessionMemory mem = new SessionMemory();
        int replayed = TalosBootstrap.replayTurnLog(store, sid, mem);
        assertEquals(1, replayed);
        assertTrue(mem.get().contains("legacy-u"));
    }
}

