package dev.talos.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step-2 tests: per-turn structured durability.
 *
 * <p>Verifies:
 * <ul>
 *   <li>{@code appendTurn} + {@code loadTurns} round-trip multiple turns</li>
 *   <li>Snapshot {@code save/load} (existing behavior) still works unchanged</li>
 *   <li>Snapshot and per-turn log are independent companion files</li>
 *   <li>Malformed JSONL lines are skipped (not fatal)</li>
 *   <li>Deleting a session removes both companion files</li>
 * </ul>
 */
class JsonSessionStoreTurnsTest {

    @Test
    void appendAndLoadTurnsRoundTrip(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "session-abc";

        store.appendTurn(sid, new TurnRecord(
                1, Instant.parse("2026-04-18T10:00:00Z"), 250,
                "hello", "hi there",
                List.of(new TurnRecord.ToolCallSummary("talos.read_file", "index.html", true)),
                0, 0, 0, ""));
        store.appendTurn(sid, new TurnRecord(
                2, Instant.parse("2026-04-18T10:00:05Z"), 4800,
                "edit title", "done",
                List.of(new TurnRecord.ToolCallSummary("talos.edit_file", "index.html", true)),
                1, 1, 0, "3 stages, 42.1ms, final=4"));

        List<TurnRecord> loaded = store.loadTurns(sid);
        assertEquals(2, loaded.size(), "both turns persisted");
        assertEquals(1, loaded.get(0).turnNumber());
        assertEquals("hello", loaded.get(0).userInput());
        assertEquals("hi there", loaded.get(0).assistantText());
        assertEquals("talos.read_file", loaded.get(0).toolCalls().get(0).name());
        assertTrue(loaded.get(0).toolCalls().get(0).success());

        assertEquals(2, loaded.get(1).turnNumber());
        assertEquals(1, loaded.get(1).approvalsRequired());
        assertEquals(4800, loaded.get(1).durationMs());
        assertEquals("3 stages, 42.1ms, final=4", loaded.get(1).retrievalTraceSummary());
    }

    @Test
    void snapshotPathUnchangedByTurnsLog(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "session-snapshot-compat";

        SessionData data = new SessionData(sid, dir.toString(),
                "my sketch", 2, Instant.now(),
                List.of(new SessionData.Turn("user", "q"),
                        new SessionData.Turn("assistant", "a")));
        store.save(data);

        // Independently append a per-turn record.
        store.appendTurn(sid, new TurnRecord(
                1, Instant.now(), 100, "q", "a",
                List.of(), 0, 0, 0, ""));

        Optional<SessionData> reloaded = store.load(sid);
        assertTrue(reloaded.isPresent(), "snapshot still loads");
        assertEquals("my sketch", reloaded.get().sketch());
        assertEquals(2, reloaded.get().turns().size());
        assertEquals(1, store.loadTurns(sid).size());
    }

    @Test
    void oldSnapshotOnlySessionLoadsEvenWithoutTurnsLog(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "old-session";
        SessionData data = new SessionData(sid, dir.toString(),
                "", 0, Instant.now(), List.of());
        store.save(data);

        assertTrue(store.load(sid).isPresent(), "old snapshot still loads");
        assertTrue(store.loadTurns(sid).isEmpty(),
                "no jsonl file → empty turn log (no error)");
    }

    @Test
    void loadTurnsIsEmptyForMissingSession(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        assertTrue(store.loadTurns("nonexistent").isEmpty());
    }

    @Test
    void deleteRemovesBothSnapshotAndTurnsLog(@TempDir Path dir) throws Exception {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "to-delete";
        store.save(new SessionData(sid, dir.toString(), "", 0, Instant.now(), List.of()));
        store.appendTurn(sid, new TurnRecord(
                1, Instant.now(), 10, "q", "a", List.of(), 0, 0, 0, ""));

        assertTrue(java.nio.file.Files.exists(dir.resolve(sid + ".json")));
        assertTrue(java.nio.file.Files.exists(dir.resolve(sid + ".turns.jsonl")));

        assertTrue(store.delete(sid));
        assertFalse(java.nio.file.Files.exists(dir.resolve(sid + ".json")));
        assertFalse(java.nio.file.Files.exists(dir.resolve(sid + ".turns.jsonl")));
    }

    @Test
    void malformedLineIsSkipped(@TempDir Path dir) throws Exception {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "partial";
        store.appendTurn(sid, new TurnRecord(
                1, Instant.now(), 10, "q", "a", List.of(), 0, 0, 0, ""));

        Path f = dir.resolve(sid + ".turns.jsonl");
        java.nio.file.Files.writeString(f,
                java.nio.file.Files.readString(f) + "not-json-at-all\n",
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);

        // Append another valid line after the corrupt one.
        store.appendTurn(sid, new TurnRecord(
                2, Instant.now(), 20, "q2", "a2", List.of(), 0, 0, 0, ""));

        List<TurnRecord> loaded = store.loadTurns(sid);
        assertEquals(2, loaded.size(), "valid lines survive a corrupt middle line");
    }
}

