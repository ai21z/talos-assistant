package dev.talos.runtime;

import dev.talos.core.io.AtomicFileWriter;
import dev.talos.runtime.trace.LocalTurnTrace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Stable-link negatives for every file boundary introduced by T1162. */
final class JsonSessionStoreWriterLeaseLinkTest {
    private static final Instant CREATED_AT = Instant.parse("2026-08-01T16:00:00Z");

    @TempDir
    Path temp;

    @Test
    void linkedOwnershipDirectoryBlocksReservationWithoutExternalWrites() throws Exception {
        Path sessions = temp.resolve("sessions");
        Path external = temp.resolve("external-ownership");
        Files.createDirectories(sessions);
        Files.createDirectories(external);
        createSymbolicLinkOrAbort(sessions.resolve(".ownership"), external);

        JsonSessionStore store = new JsonSessionStore(sessions);
        SessionWriterUnavailableException failure = assertThrows(
                SessionWriterUnavailableException.class,
                () -> store.reserveNewSession(temp.resolve("workspace"), CREATED_AT));

        assertEquals(SessionWriterUnavailableException.Reason.RESERVATION_IO, failure.reason());
        try (var entries = Files.list(external)) {
            assertEquals(0L, entries.count());
        }
    }

    @Test
    void linkedReservationAndLockSidecarsNeverReceiveBytes() throws Exception {
        assertLinkedReservationRejected();
        assertLinkedLockRejected();
    }

    @Test
    void linkedSnapshotTurnAndTraceTargetsNeverReceiveSessionBytes() throws Exception {
        assertLinkedSnapshotRejected();
        assertLinkedTurnLogRejected();
        assertLinkedTraceRejected();
    }

    @Test
    void linkedTraceDescendantRejectsDeleteBeforeSnapshotOrLogRemoval() throws Exception {
        Path sessions = temp.resolve("delete-sessions");
        JsonSessionStore store = new JsonSessionStore(sessions);
        String sessionId = "delete-linked-trace";
        Path external = temp.resolve("delete-external.txt");
        Files.writeString(external, "external-delete-canary", StandardCharsets.UTF_8);

        try (SessionWriterLease lease = store.acquireSessionWriter(sessionId)) {
            lease.save(data(sessionId));
            lease.appendTurn(sessionId, record());
            lease.saveTrace(sessionId, trace(sessionId));
            Path traceFile = sessions.resolve("traces").resolve(sessionId)
                    .resolve("000001-trace.json");
            Files.delete(traceFile);
            createSymbolicLinkOrAbort(traceFile, external);

            assertFalse(lease.delete(sessionId));
            assertTrue(Files.isRegularFile(
                    sessions.resolve(sessionId + ".json"), LinkOption.NOFOLLOW_LINKS));
            assertTrue(Files.isRegularFile(
                    sessions.resolve(sessionId + ".turns.jsonl"), LinkOption.NOFOLLOW_LINKS));
            assertEquals("external-delete-canary",
                    Files.readString(external, StandardCharsets.UTF_8));
        }
    }

    @Test
    void linkedSnapshotAndTurnLogEntriesNeverBecomePhantomSessions() throws Exception {
        Path sessions = temp.resolve("list-sessions");
        Path workspace = temp.resolve("list-workspace");
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        String realId = workspaceId + "-20260801160000";
        JsonSessionStore store = new JsonSessionStore(sessions);
        try (SessionWriterLease lease = store.acquireSessionWriter(realId)) {
            lease.save(data(realId));
        }

        Path externalSnapshot = temp.resolve("external-snapshot.json");
        Path externalLog = temp.resolve("external-turns.jsonl");
        Files.writeString(externalSnapshot, "{}", StandardCharsets.UTF_8);
        Files.writeString(externalLog, "{}\n", StandardCharsets.UTF_8);
        createSymbolicLinkOrAbort(
                sessions.resolve(workspaceId + "-99999999999998.json"),
                externalSnapshot);
        createSymbolicLinkOrAbort(
                sessions.resolve(workspaceId + "-99999999999999.turns.jsonl"),
                externalLog);

        assertEquals(
                List.of(realId),
                store.listSessions(workspaceId).stream()
                        .map(SessionSummary::sessionId)
                        .toList());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsOwnershipJunctionBlocksReservationWithoutExternalWrites() throws Exception {
        Path sessions = temp.resolve("junction-sessions");
        Path external = temp.resolve("junction-external");
        Files.createDirectories(sessions);
        Files.createDirectories(external);
        createJunctionOrAbort(sessions.resolve(".ownership"), external);

        JsonSessionStore store = new JsonSessionStore(sessions);
        SessionWriterUnavailableException failure = assertThrows(
                SessionWriterUnavailableException.class,
                () -> store.reserveNewSession(
                        temp.resolve("junction-workspace"), CREATED_AT));

        assertEquals(SessionWriterUnavailableException.Reason.RESERVATION_IO, failure.reason());
        try (var entries = Files.list(external)) {
            assertEquals(0L, entries.count());
        }
    }

    private void assertLinkedReservationRejected() throws Exception {
        Path sessions = temp.resolve("reservation-sessions");
        Path workspace = temp.resolve("reservation-workspace");
        Path ownership = sessions.resolve(".ownership");
        Files.createDirectories(ownership);
        String sessionId = JsonSessionStore.sessionIdFor(workspace)
                + "-20260801160000-" + "11".repeat(16);
        Path external = temp.resolve("external-reservation.txt");
        Files.writeString(external, "reservation-canary", StandardCharsets.UTF_8);
        createSymbolicLinkOrAbort(
                ownership.resolve(sessionId + ".session.reserve"), external);
        JsonSessionStore store = deterministicStore(sessions, 0x11);

        SessionWriterUnavailableException failure = assertThrows(
                SessionWriterUnavailableException.class,
                () -> store.reserveNewSession(workspace, CREATED_AT));

        assertEquals(SessionWriterUnavailableException.Reason.RESERVATION_IO, failure.reason());
        assertEquals("reservation-canary", Files.readString(external, StandardCharsets.UTF_8));
    }

    private void assertLinkedLockRejected() throws Exception {
        Path sessions = temp.resolve("lock-sessions");
        Path workspace = temp.resolve("lock-workspace");
        Path ownership = sessions.resolve(".ownership");
        Files.createDirectories(ownership);
        String sessionId = JsonSessionStore.sessionIdFor(workspace)
                + "-20260801160000-" + "22".repeat(16);
        Path external = temp.resolve("external-lock.txt");
        Files.writeString(external, "lock-canary", StandardCharsets.UTF_8);
        createSymbolicLinkOrAbort(
                ownership.resolve(sessionId + ".session.writer.lock"), external);
        JsonSessionStore store = deterministicStore(sessions, 0x22);

        SessionWriterUnavailableException failure = assertThrows(
                SessionWriterUnavailableException.class,
                () -> store.reserveNewSession(workspace, CREATED_AT));

        assertEquals(SessionWriterUnavailableException.Reason.LOCK_IO, failure.reason());
        assertEquals("lock-canary", Files.readString(external, StandardCharsets.UTF_8));
    }

    private void assertLinkedSnapshotRejected() throws Exception {
        Path sessions = temp.resolve("snapshot-sessions");
        JsonSessionStore store = new JsonSessionStore(sessions);
        String sessionId = "linked-snapshot";
        Path external = temp.resolve("external-snapshot.txt");
        Files.writeString(external, "snapshot-canary", StandardCharsets.UTF_8);
        try (SessionWriterLease lease = store.acquireSessionWriter(sessionId)) {
            createSymbolicLinkOrAbort(sessions.resolve(sessionId + ".json"), external);
            lease.save(data(sessionId));
        }
        assertEquals("snapshot-canary", Files.readString(external, StandardCharsets.UTF_8));
    }

    private void assertLinkedTurnLogRejected() throws Exception {
        Path sessions = temp.resolve("turn-sessions");
        JsonSessionStore store = new JsonSessionStore(sessions);
        String sessionId = "linked-turn";
        Path external = temp.resolve("external-turn.txt");
        Files.writeString(external, "turn-canary", StandardCharsets.UTF_8);
        try (SessionWriterLease lease = store.acquireSessionWriter(sessionId)) {
            createSymbolicLinkOrAbort(
                    sessions.resolve(sessionId + ".turns.jsonl"), external);
            SessionWriterUnavailableException failure = assertThrows(
                    SessionWriterUnavailableException.class,
                    () -> lease.appendTurn(sessionId, record()));
            assertEquals(SessionWriterUnavailableException.Reason.PERSISTENCE_IO, failure.reason());
        }
        assertEquals("turn-canary", Files.readString(external, StandardCharsets.UTF_8));
    }

    private void assertLinkedTraceRejected() throws Exception {
        Path sessions = temp.resolve("trace-sessions");
        JsonSessionStore store = new JsonSessionStore(sessions);
        String sessionId = "linked-trace";
        Path external = temp.resolve("external-trace.txt");
        Files.writeString(external, "trace-canary", StandardCharsets.UTF_8);
        try (SessionWriterLease lease = store.acquireSessionWriter(sessionId)) {
            Path traceDir = sessions.resolve("traces").resolve(sessionId);
            Files.createDirectories(traceDir);
            createSymbolicLinkOrAbort(traceDir.resolve("000001-trace.json"), external);
            lease.saveTrace(sessionId, trace(sessionId));
        }
        assertEquals("trace-canary", Files.readString(external, StandardCharsets.UTF_8));
    }

    private static JsonSessionStore deterministicStore(Path sessions, int entropyByte) {
        byte[] entropy = new byte[16];
        java.util.Arrays.fill(entropy, (byte) entropyByte);
        return new JsonSessionStore(sessions, new AtomicFileWriter(), () -> entropy);
    }

    private static SessionData data(String sessionId) {
        return new SessionData(
                sessionId,
                "/workspace",
                "snapshot",
                1,
                CREATED_AT,
                List.of(new SessionData.Turn("user", "hello", "ok")),
                "test-model");
    }

    private static TurnRecord record() {
        return new TurnRecord(
                1,
                CREATED_AT,
                1L,
                "inspect",
                "done",
                List.of(),
                0,
                0,
                0,
                "",
                "ok",
                TurnPolicyTrace.empty(),
                "");
    }

    private static LocalTurnTrace trace(String sessionId) {
        return LocalTurnTrace.builder(
                        "trace", sessionId, 1, CREATED_AT.toString())
                .workspaceHash("workspace-hash")
                .mode("ask")
                .model("llama.cpp", "test-model")
                .build();
    }

    private static void createSymbolicLinkOrAbort(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath().normalize());
        } catch (UnsupportedOperationException | SecurityException | IOException failure) {
            assumeTrue(false, "symbolic links are unavailable on this host: "
                    + failure.getMessage());
        }
    }

    private static void createJunctionOrAbort(Path junction, Path target) throws Exception {
        Process process = new ProcessBuilder(
                "cmd.exe", "/d", "/c", "mklink", "/J",
                junction.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        assumeTrue(exit == 0 && Files.exists(junction, LinkOption.NOFOLLOW_LINKS),
                "Windows junction fixture unavailable: " + output);
    }
}
