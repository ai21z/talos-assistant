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
    void policyTraceRoundTrips(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "session-policy";
        TurnPolicyTrace trace = new TurnPolicyTrace(
                "FILE_CREATE",
                true,
                true,
                List.of("index.html"),
                List.of(),
                "APPLY",
                "VERIFY",
                List.of("talos.read_file", "talos.write_file"),
                List.of("talos.read_file", "talos.write_file"),
                List.of("approval denied by user for talos.write_file"));

        store.appendTurn(sid, new TurnRecord(
                1,
                Instant.parse("2026-04-18T10:00:00Z"),
                250,
                "create site",
                "No file changed.",
                List.of(new TurnRecord.ToolCallSummary(
                        "talos.write_file",
                        "index.html",
                        false,
                        "approval denied by user for talos.write_file")),
                1,
                0,
                1,
                "",
                "ok",
                trace));

        TurnRecord loaded = store.loadTurns(sid).get(0);

        assertEquals("FILE_CREATE", loaded.policyTrace().taskType());
        assertTrue(loaded.policyTrace().mutationAllowed());
        assertEquals("APPLY", loaded.policyTrace().initialPhase());
        assertEquals("VERIFY", loaded.policyTrace().finalPhase());
        assertEquals(List.of("talos.read_file", "talos.write_file"), loaded.policyTrace().nativeTools());
        assertEquals(List.of("approval denied by user for talos.write_file"), loaded.policyTrace().blocks());
        assertEquals("approval denied by user for talos.write_file", loaded.toolCalls().get(0).reason());
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

    /**
     * Prompt 5 — lenient UTF-8 decoding on load.
     *
     * <p>A partial multi-byte-char write during a crash / power loss can leave
     * the file with an invalid UTF-8 sequence in exactly one line. Previously
     * this aborted the entire load (the strict decoder in {@code readAllLines}
     * raised {@code MalformedInputException}) and the user lost the whole
     * session transcript. The hardened loader must contain the damage to the
     * corrupt line only.
     */
    @Test
    void malformedUtf8ByteOnlyLosesAffectedLine(@TempDir Path dir) throws Exception {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "utf8-partial";
        Path f = dir.resolve(sid + ".turns.jsonl");

        // Build a file: [good line]\n[line with malformed UTF-8]\n[good line]\n
        store.appendTurn(sid, new TurnRecord(
                1, Instant.parse("2026-04-18T10:00:00Z"), 10,
                "before", "ok", List.of(), 0, 0, 0, ""));

        byte[] corrupt = new byte[] {
                // Three illegal UTF-8 lead bytes — the REPLACE decoder turns
                // them into U+FFFD each, producing a line that is not remotely
                // valid JSON and Jackson must reject.
                (byte) 0xFF, (byte) 0xFE, (byte) 0xFD,
                ' ', 'g', 'a', 'r', 'b', 'a', 'g', 'e',
                '\n'
        };
        java.nio.file.Files.write(f, corrupt,
                java.nio.file.StandardOpenOption.APPEND);

        store.appendTurn(sid, new TurnRecord(
                2, Instant.parse("2026-04-18T10:00:05Z"), 20,
                "after", "ok", List.of(), 0, 0, 0, ""));

        List<TurnRecord> loaded = store.loadTurns(sid);
        assertEquals(2, loaded.size(),
                "corrupt UTF-8 must only lose its own line; surrounding lines survive");
        assertEquals("before", loaded.get(0).userInput());
        assertEquals("after", loaded.get(1).userInput());
    }
}

