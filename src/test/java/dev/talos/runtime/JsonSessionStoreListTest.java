package dev.talos.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T799: session instance ids and {@link SessionStore#listSessions}.
 * One workspace, many sessions - legacy bare-hash files, per-run
 * instance files, and orphan crash logs all list under the workspace
 * hash prefix, newest first.
 */
class JsonSessionStoreListTest {

    @TempDir Path dir;

    private JsonSessionStore store() {
        return new JsonSessionStore(dir.resolve("sessions"));
    }

    private static void withWriter(
            JsonSessionStore store,
            String sessionId,
            Consumer<SessionWriterLease> action
    ) {
        try (SessionWriterLease writer = store.acquireSessionWriter(sessionId)) {
            action.accept(writer);
        }
    }

    private static SessionData snapshot(String id, Instant createdAt, int turnCount, String model) {
        return new SessionData(id, "/ws", "", turnCount, createdAt,
                List.of(new SessionData.Turn("user", "u", ""),
                        new SessionData.Turn("assistant", "a", "ok")),
                model);
    }

    @Test
    void instanceIdIsWorkspaceHashPlusUtcTimestampAndEntropy() {
        Path ws = Path.of("/project/demo");
        Instant clock = Instant.parse("2026-06-12T08:30:05Z");

        String id = JsonSessionStore.newSessionInstanceId(ws, clock);

        String prefix = JsonSessionStore.sessionIdFor(ws) + "-20260612083005-";
        assertTrue(id.startsWith(prefix), id);
        assertTrue(id.substring(prefix.length()).matches("[0-9a-f]{32}"), id);
        assertEquals(40 + 1 + 14 + 1 + 32, id.length(),
                "40-hex hash, UTC stamp, and 128-bit entropy");
    }

    @Test
    void listSessionsReturnsLegacyAndInstanceFilesNewestFirst() {
        JsonSessionStore store = store();
        Path ws = dir.resolve("demo-ws");
        String workspaceId = JsonSessionStore.sessionIdFor(ws);
        String older = JsonSessionStore.newSessionInstanceId(ws, Instant.parse("2026-06-10T10:00:00Z"));
        String newer = JsonSessionStore.newSessionInstanceId(ws, Instant.parse("2026-06-11T10:00:00Z"));

        withWriter(store, workspaceId, writer -> writer.save(snapshot(
                workspaceId, Instant.parse("2026-06-09T10:00:00Z"), 1, "legacy-model")));
        withWriter(store, older, writer -> writer.save(snapshot(
                older, Instant.parse("2026-06-10T10:00:00Z"), 2, "older-model")));
        withWriter(store, newer, writer -> writer.save(snapshot(
                newer, Instant.parse("2026-06-11T10:00:00Z"), 3, "newer-model")));

        List<SessionSummary> sessions = store.listSessions(workspaceId);

        assertEquals(List.of(newer, older, workspaceId),
                sessions.stream().map(SessionSummary::sessionId).toList(),
                "newest first by createdAt");
        SessionSummary head = sessions.get(0);
        assertEquals(3, head.turnCount());
        assertEquals("newer-model", head.model());
        assertTrue(head.hasSnapshot());
        assertFalse(head.hasTurnLog());
        assertFalse(head.legacy());
        assertTrue(sessions.get(2).legacy(), "bare-hash file is marked legacy");
    }

    @Test
    void orphanTurnLogIsSynthesizedFromItsRows() {
        JsonSessionStore store = store();
        Path ws = dir.resolve("crash-ws");
        String workspaceId = JsonSessionStore.sessionIdFor(ws);
        String crashed = JsonSessionStore.newSessionInstanceId(ws, Instant.parse("2026-06-12T09:00:00Z"));
        Instant first = Instant.parse("2026-06-12T09:01:00Z");

        withWriter(store, crashed, writer -> {
            writer.appendTurn(crashed, new TurnRecord(1, first, 0L,
                    "q1", "a1", List.of(), 0, 0, 0, ""));
            writer.appendTurn(crashed, new TurnRecord(2, first.plusSeconds(60), 0L,
                    "q2", "a2", List.of(), 0, 0, 0, ""));
        });

        List<SessionSummary> sessions = store.listSessions(workspaceId);

        assertEquals(1, sessions.size());
        SessionSummary summary = sessions.get(0);
        assertEquals(crashed, summary.sessionId());
        assertFalse(summary.hasSnapshot(), "crash log only - no snapshot");
        assertTrue(summary.hasTurnLog());
        assertEquals(2, summary.turnCount());
        assertEquals(first, summary.createdAt(), "createdAt synthesized from the first row");
    }

    @Test
    void companionTurnLogIsReportedOnTheSnapshotSummary() {
        JsonSessionStore store = store();
        Path ws = dir.resolve("both-ws");
        String workspaceId = JsonSessionStore.sessionIdFor(ws);
        String id = JsonSessionStore.newSessionInstanceId(ws, Instant.parse("2026-06-12T09:00:00Z"));

        withWriter(store, id, writer -> {
            writer.save(snapshot(id, Instant.parse("2026-06-12T09:00:00Z"), 1, "m"));
            writer.appendTurn(id, new TurnRecord(1, Instant.parse("2026-06-12T09:01:00Z"), 0L,
                    "q", "a", List.of(), 0, 0, 0, ""));
        });

        List<SessionSummary> sessions = store.listSessions(workspaceId);

        assertEquals(1, sessions.size(), "snapshot and its companion log are one session");
        assertTrue(sessions.get(0).hasSnapshot());
        assertTrue(sessions.get(0).hasTurnLog());
    }

    @Test
    void otherWorkspacesAreInvisible() {
        JsonSessionStore store = store();
        Path mine = dir.resolve("mine");
        Path theirs = dir.resolve("theirs");

        String legacyId = JsonSessionStore.sessionIdFor(theirs);
        String instanceId = JsonSessionStore.newSessionInstanceId(
                theirs, Instant.parse("2026-06-12T09:00:00Z"));
        withWriter(store, legacyId, writer -> writer.save(snapshot(
                legacyId, Instant.parse("2026-06-12T09:00:00Z"), 1, "m")));
        withWriter(store, instanceId, writer -> writer.save(snapshot(
                instanceId, Instant.parse("2026-06-12T09:00:00Z"), 1, "m")));

        assertTrue(store.listSessions(JsonSessionStore.sessionIdFor(mine)).isEmpty());
    }

    @Test
    void corruptSnapshotStillListsButSortsOldest() throws Exception {
        JsonSessionStore store = store();
        Path ws = dir.resolve("corrupt-ws");
        String workspaceId = JsonSessionStore.sessionIdFor(ws);
        String good = JsonSessionStore.newSessionInstanceId(ws, Instant.parse("2026-06-12T09:00:00Z"));
        String corrupt = JsonSessionStore.newSessionInstanceId(ws, Instant.parse("2026-06-12T10:00:00Z"));

        withWriter(store, good, writer -> writer.save(snapshot(
                good, Instant.parse("2026-06-12T09:00:00Z"), 1, "m")));
        Files.writeString(store.sessionsDir().resolve(corrupt + ".json"), "{not json");

        List<SessionSummary> sessions = store.listSessions(workspaceId);

        assertEquals(2, sessions.size(), "a corrupt snapshot is listed, not hidden");
        assertEquals(good, sessions.get(0).sessionId());
        assertEquals(Instant.EPOCH, sessions.get(1).createdAt(),
                "unreadable metadata sorts oldest, never newest");
    }

    @Test
    void unsafeOrdinaryFilenameStemsCannotBecomePhantomSessions() throws Exception {
        JsonSessionStore store = store();
        Path ws = dir.resolve("unsafe-name-ws");
        String workspaceId = JsonSessionStore.sessionIdFor(ws);
        String legitimate = JsonSessionStore.newSessionInstanceId(
                ws, Instant.parse("2026-06-12T09:00:00Z"));
        withWriter(store, legitimate, writer -> writer.save(snapshot(
                legitimate, Instant.parse("2026-06-12T09:00:00Z"), 1, "m")));

        String punctuation = workspaceId + "-newer!";
        String overlong = workspaceId + "-" + "a".repeat(201);
        Files.writeString(store.sessionsDir().resolve(punctuation + ".json"), "{}");
        Files.writeString(store.sessionsDir().resolve(overlong + ".turns.jsonl"), "");

        assertEquals(
                List.of(legitimate),
                store.listSessions(workspaceId).stream()
                        .map(SessionSummary::sessionId)
                        .toList(),
                "ordinary files outside the loadable session-id grammar must not enter startup selection");
    }

    @Test
    void instanceFilesLoadAndDeleteThroughTheExistingContract() {
        JsonSessionStore store = store();
        Path ws = dir.resolve("roundtrip-ws");
        String id = JsonSessionStore.newSessionInstanceId(ws, Instant.parse("2026-06-12T09:00:00Z"));

        withWriter(store, id, writer -> writer.save(snapshot(
                id, Instant.parse("2026-06-12T09:00:00Z"), 1, "m")));

        assertEquals(id, store.load(id).orElseThrow().sessionId());
        withWriter(store, id, writer -> assertTrue(writer.delete(id)));
        assertTrue(store.listSessions(JsonSessionStore.sessionIdFor(ws)).isEmpty());
    }

    @Test
    void blankWorkspaceIdAndNoOpStoreListNothing() {
        assertTrue(store().listSessions(" ").isEmpty());
        assertTrue(new NoOpSessionStore().listSessions("anything").isEmpty(),
                "the SessionStore default stays a no-op");
    }
}
