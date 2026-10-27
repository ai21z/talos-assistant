package dev.talos.runtime;

import dev.talos.core.io.AtomicFileWriter;
import dev.talos.runtime.trace.LocalTurnTrace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T1162 namespace isolation, compatibility, deletion, and serialization tests. */
final class JsonSessionStoreWriterLeaseIsolationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-08-01T11:22:33Z");

    @TempDir
    Path temp;

    @Test
    void distinctReservationsKeepSnapshotTurnLogAndTraceNamespacesDisjoint() throws Exception {
        Path sessions = temp.resolve("sessions");
        Path workspace = temp.resolve("workspace");
        Queue<byte[]> entropy = new ArrayDeque<>();
        entropy.add(entropy(0x11));
        entropy.add(entropy(0x22));
        JsonSessionStore store = new JsonSessionStore(
                sessions,
                new AtomicFileWriter(),
                () -> entropy.remove());

        try (SessionWriterLease first = store.reserveNewSession(workspace, CREATED_AT);
             SessionWriterLease second = store.reserveNewSession(workspace, CREATED_AT)) {
            assertNotEquals(first.sessionId(), second.sessionId());

            writeNamespace(first, "first-session", "first-answer", "first-trace");
            writeNamespace(second, "second-session", "second-answer", "second-trace");

            assertEquals("first-session", store.load(first.sessionId()).orElseThrow().sketch());
            assertEquals("second-session", store.load(second.sessionId()).orElseThrow().sketch());
            assertEquals("first-answer", store.loadTurns(first.sessionId()).getFirst().assistantText());
            assertEquals("second-answer", store.loadTurns(second.sessionId()).getFirst().assistantText());
            assertEquals("first-trace", store.loadLatestTrace(first.sessionId()).orElseThrow().traceId());
            assertEquals("second-trace", store.loadLatestTrace(second.sessionId()).orElseThrow().traceId());

            assertContainsOnlyOwnBytes(sessions.resolve(first.sessionId() + ".json"),
                    "first-session", "second-session");
            assertContainsOnlyOwnBytes(sessions.resolve(first.sessionId() + ".turns.jsonl"),
                    "first-answer", "second-answer");
            assertContainsOnlyOwnBytes(sessions.resolve(second.sessionId() + ".json"),
                    "second-session", "first-session");
            assertContainsOnlyOwnBytes(sessions.resolve(second.sessionId() + ".turns.jsonl"),
                    "second-answer", "first-answer");

            assertEquals(
                    List.of("000001-first-trace.json"),
                    childNames(sessions.resolve("traces").resolve(first.sessionId())));
            assertEquals(
                    List.of("000001-second-trace.json"),
                    childNames(sessions.resolve("traces").resolve(second.sessionId())));
        }
    }

    @Test
    void deleteRemovesOnlySessionDataAndRetainsStableOwnershipSidecars() throws Exception {
        Path sessions = temp.resolve("sessions");
        Path workspace = temp.resolve("workspace");
        JsonSessionStore store = new JsonSessionStore(
                sessions,
                new AtomicFileWriter(),
                () -> entropy(0x44));
        Path reservation;
        Path lock;
        String sessionId;

        SessionWriterLease lease = store.reserveNewSession(workspace, CREATED_AT);
        try {
            sessionId = lease.sessionId();
            reservation = lease.reservationPath().orElseThrow();
            lock = lease.lockPath();
            writeNamespace(lease, "delete-session", "delete-answer", "delete-trace");

            assertTrue(lease.delete(sessionId));
            assertFalse(Files.exists(sessions.resolve(sessionId + ".json"), LinkOption.NOFOLLOW_LINKS));
            assertFalse(Files.exists(sessions.resolve(sessionId + ".turns.jsonl"), LinkOption.NOFOLLOW_LINKS));
            assertFalse(Files.exists(sessions.resolve("traces").resolve(sessionId), LinkOption.NOFOLLOW_LINKS));
            assertTrue(Files.isRegularFile(reservation, LinkOption.NOFOLLOW_LINKS));
            assertTrue(Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS));
            assertTrue(store.listSessions(JsonSessionStore.sessionIdFor(workspace)).isEmpty());
        } finally {
            lease.close();
        }

        assertTrue(Files.isRegularFile(reservation, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS));
        try (SessionWriterLease reopened = store.acquireSessionWriter(sessionId)) {
            assertTrue(reopened.isOpen());
        }
        assertTrue(Files.isRegularFile(reservation, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void legacyCompatibilityReadsAreByteStableAndDoNotBypassWriterExclusion() throws Exception {
        Path sessions = temp.resolve("sessions");
        Files.createDirectories(sessions);
        Path workspace = temp.resolve("legacy-workspace");
        String sessionId = JsonSessionStore.sessionIdFor(workspace);
        Path snapshot = sessions.resolve(sessionId + ".json");
        String json = """
                {
                  "sessionId" : "%s",
                  "workspace" : "%s",
                  "sketch" : "legacy sketch",
                  "turnCount" : 0,
                  "createdAt" : "2026-08-01T11:22:33Z",
                  "model" : "legacy-model",
                  "turns" : [ ]
                }
                """.formatted(sessionId, escaped(workspace.toString()));
        Files.writeString(snapshot, json, StandardCharsets.UTF_8);
        byte[] original = Files.readAllBytes(snapshot);

        JsonSessionStore first = new JsonSessionStore(sessions);
        JsonSessionStore second = new JsonSessionStore(sessions);
        assertEquals("legacy sketch", first.load(sessionId).orElseThrow().sketch());
        assertEquals(List.of(sessionId), first.listSessions(sessionId).stream()
                .map(SessionSummary::sessionId)
                .toList());
        assertArrayEquals(original, Files.readAllBytes(snapshot),
                "compatibility reads must never rewrite a legacy snapshot");

        try (SessionWriterLease lease = first.acquireSessionWriter(sessionId)) {
            SessionWriterUnavailableException busy = assertThrows(
                    SessionWriterUnavailableException.class,
                    () -> second.acquireSessionWriter(sessionId));
            assertEquals(SessionWriterUnavailableException.Reason.WRITER_BUSY, busy.reason());
            assertArrayEquals(original, Files.readAllBytes(snapshot));
        }

        try (SessionWriterLease reopened = second.acquireSessionWriter(sessionId)) {
            assertTrue(reopened.isOpen());
            assertEquals("legacy sketch", reopened.load(sessionId).orElseThrow().sketch());
        }
        assertArrayEquals(original, Files.readAllBytes(snapshot));
    }

    @Test
    void allOperationsOnOneLeaseAreSerializedBehindOneMutex() throws Exception {
        Path sessions = temp.resolve("sessions");
        CountDownLatch snapshotReachedPromotion = new CountDownLatch(1);
        CountDownLatch allowSnapshotPromotion = new CountDownLatch(1);
        AtomicFileWriter writer = new AtomicFileWriter((stage, target, ignored) -> {
            if (stage == AtomicFileWriter.FaultStage.BEFORE_PROMOTION
                    && target.getFileName().toString().equals("serialized.json")) {
                snapshotReachedPromotion.countDown();
                try {
                    if (!allowSnapshotPromotion.await(10, TimeUnit.SECONDS)) {
                        throw new IOException("snapshot release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("snapshot wait interrupted", interrupted);
                }
            }
        });
        JsonSessionStore store = new JsonSessionStore(
                sessions,
                writer,
                () -> entropy(0x55));
        CountDownLatch appendStarted = new CountDownLatch(1);
        CountDownLatch appendCompleted = new CountDownLatch(1);

        try (SessionWriterLease lease = store.acquireSessionWriter("serialized")) {
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<?> snapshot = workers.submit(() -> lease.save(data(
                        lease.sessionId(), "serialized-sketch")));
                assertTrue(snapshotReachedPromotion.await(10, TimeUnit.SECONDS));

                Future<?> append = workers.submit(() -> {
                    appendStarted.countDown();
                    lease.appendTurn(lease.sessionId(), record("serialized-answer"));
                    appendCompleted.countDown();
                });
                assertTrue(appendStarted.await(10, TimeUnit.SECONDS));
                assertFalse(appendCompleted.await(250, TimeUnit.MILLISECONDS),
                        "append escaped while snapshot still held the lease operation mutex");

                allowSnapshotPromotion.countDown();
                snapshot.get(10, TimeUnit.SECONDS);
                append.get(10, TimeUnit.SECONDS);
                assertTrue(appendCompleted.await(1, TimeUnit.SECONDS));
            } finally {
                allowSnapshotPromotion.countDown();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
            }

            assertEquals("serialized-sketch", store.load(lease.sessionId()).orElseThrow().sketch());
            assertEquals("serialized-answer", store.loadTurns(lease.sessionId()).getFirst().assistantText());
        }
    }

    private static void writeNamespace(
            SessionWriterLease lease,
            String sketch,
            String answer,
            String traceId
    ) {
        lease.save(data(lease.sessionId(), sketch));
        lease.appendTurn(lease.sessionId(), record(answer));
        lease.saveTrace(lease.sessionId(), trace(lease.sessionId(), traceId));
    }

    private static SessionData data(String sessionId, String sketch) {
        return new SessionData(
                sessionId,
                "/workspace",
                sketch,
                1,
                CREATED_AT,
                List.of(new SessionData.Turn("user", "hello", "ok")),
                "test-model");
    }

    private static TurnRecord record(String answer) {
        return new TurnRecord(
                1,
                CREATED_AT,
                1L,
                "inspect the workspace",
                answer,
                List.of(),
                0,
                0,
                0,
                "",
                "ok",
                TurnPolicyTrace.empty(),
                "");
    }

    private static LocalTurnTrace trace(String sessionId, String traceId) {
        return LocalTurnTrace.builder(traceId, sessionId, 1, CREATED_AT.toString())
                .workspaceHash("workspace-hash")
                .mode("ask")
                .model("llama.cpp", "test-model")
                .build();
    }

    private static void assertContainsOnlyOwnBytes(Path path, String own, String foreign)
            throws Exception {
        String contents = Files.readString(path, StandardCharsets.UTF_8);
        assertTrue(contents.contains(own), contents);
        assertFalse(contents.contains(foreign), contents);
    }

    private static List<String> childNames(Path directory) throws Exception {
        try (var entries = Files.list(directory)) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static byte[] entropy(int value) {
        byte[] out = new byte[16];
        java.util.Arrays.fill(out, (byte) value);
        return out;
    }

    private static String escaped(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
