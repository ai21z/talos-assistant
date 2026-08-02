package dev.talos.runtime;

import dev.talos.core.io.AtomicFileWriter;
import dev.talos.runtime.trace.LocalTurnTrace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Strict T1162 contract tests for allocation and mutation capabilities. */
final class JsonSessionStoreWriterLeaseContractTest {
    private static final Instant CREATED_AT = Instant.parse("2026-08-01T10:15:30Z");

    @TempDir
    Path temp;

    @Test
    void reservationUsesReadableIdentityExactEntropyAndPermanentAtomicMarker() throws Exception {
        Path sessions = temp.resolve("sessions");
        Path workspace = temp.resolve("workspace");
        byte[] entropy = entropy(0x2a);
        AtomicInteger calls = new AtomicInteger();
        JsonSessionStore store = store(sessions, () -> {
            calls.incrementAndGet();
            return entropy;
        });

        Path reservation;
        Path lock;
        String sessionId;
        try (SessionWriterLease lease = store.reserveNewSession(workspace, CREATED_AT)) {
            sessionId = lease.sessionId();
            reservation = lease.reservationPath().orElseThrow();
            lock = lease.lockPath();

            assertEquals(1, calls.get());
            assertEquals(JsonSessionStore.sessionIdFor(workspace)
                    + "-20260801101530-"
                    + "2a".repeat(16), sessionId);
            assertTrue(Files.isRegularFile(reservation, LinkOption.NOFOLLOW_LINKS));
            assertTrue(Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS));
            assertEquals(
                    "talos-session-reservation-v1\n" + sessionId + "\n",
                    Files.readString(reservation, StandardCharsets.UTF_8));
        }

        assertTrue(Files.isRegularFile(reservation, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS));
        assertEquals(
                "talos-session-reservation-v1\n" + sessionId + "\n",
                Files.readString(reservation, StandardCharsets.UTF_8));
    }

    @Test
    void repeatedInjectedEntropyExhaustsReservationWithoutFallbackIdentity() {
        Path sessions = temp.resolve("sessions");
        Path workspace = temp.resolve("workspace");
        AtomicInteger calls = new AtomicInteger();
        JsonSessionStore store = store(sessions, () -> {
            calls.incrementAndGet();
            return entropy(0x5c);
        });

        String reserved;
        try (SessionWriterLease lease = store.reserveNewSession(workspace, CREATED_AT)) {
            reserved = lease.sessionId();
        }

        SessionWriterUnavailableException failure = assertThrows(
                SessionWriterUnavailableException.class,
                () -> store.reserveNewSession(workspace, CREATED_AT));

        assertEquals(
                SessionWriterUnavailableException.Reason.RESERVATION_EXHAUSTED,
                failure.reason());
        assertEquals(33, calls.get(), "one successful attempt plus the fixed 32 collision attempts");
        assertEquals(1, reservationFiles(sessions).size());
        assertTrue(reservationFiles(sessions).getFirst().getFileName().toString()
                .startsWith(reserved));
    }

    @Test
    void invalidEntropyFailsBeforePublishingAnyOwnershipArtifact() throws Exception {
        Path sessions = temp.resolve("sessions");
        JsonSessionStore store = store(sessions, () -> new byte[15]);

        SessionWriterUnavailableException failure = assertThrows(
                SessionWriterUnavailableException.class,
                () -> store.reserveNewSession(temp.resolve("workspace"), CREATED_AT));

        assertEquals(SessionWriterUnavailableException.Reason.INVALID_ENTROPY, failure.reason());
        assertTrue(reservationFiles(sessions).isEmpty());
        Path ownership = sessions.resolve(".ownership");
        if (Files.isDirectory(ownership, LinkOption.NOFOLLOW_LINKS)) {
            try (var entries = Files.list(ownership)) {
                assertEquals(0L, entries.count());
            }
        }
    }

    @Test
    void nonPortableGeneratedTimestampFailsBeforePublishingAnyOwnershipArtifact() throws Exception {
        Path sessions = temp.resolve("sessions");
        JsonSessionStore store = store(sessions, () -> entropy(0x6d));
        Instant fiveDigitYear = Instant.parse("+10000-01-01T00:00:00Z");

        SessionWriterUnavailableException failure = assertThrows(
                SessionWriterUnavailableException.class,
                () -> store.reserveNewSession(temp.resolve("workspace"), fiveDigitYear));

        assertEquals(SessionWriterUnavailableException.Reason.INVALID_SESSION_ID, failure.reason());
        assertTrue(reservationFiles(sessions).isEmpty());
        Path ownership = sessions.resolve(".ownership");
        if (Files.isDirectory(ownership, LinkOption.NOFOLLOW_LINKS)) {
            try (var entries = Files.list(ownership)) {
                assertEquals(0L, entries.count());
            }
        }
    }

    @ParameterizedTest(name = "{0} direct mutation requires a lease")
    @EnumSource(Mutation.class)
    void directUnleasedMutationIsRejectedForEveryMutator(Mutation mutation) {
        JsonSessionStore store = new JsonSessionStore(temp.resolve("sessions"));
        assertReason(
                SessionWriterUnavailableException.Reason.LEASE_REQUIRED,
                () -> mutation.direct(store, "session-unleased"),
                mutation + " accepted an unleased mutation");
        assertPayloadAbsent(store.sessionsDir(), "session-unleased");
    }

    @ParameterizedTest(name = "{0} rejects a wrong-session lease")
    @EnumSource(Mutation.class)
    void wrongSessionMutationIsRejectedForEveryMutator(Mutation mutation) {
        JsonSessionStore store = new JsonSessionStore(temp.resolve("sessions"));
        try (SessionWriterLease lease = store.acquireSessionWriter("owned-session")) {
            assertReason(
                    SessionWriterUnavailableException.Reason.WRONG_SESSION,
                    () -> mutation.through(lease, "different-session"),
                    mutation + " accepted a wrong-session lease");
            assertPayloadAbsent(store.sessionsDir(), "different-session");
        }
    }

    @ParameterizedTest(name = "{0} rejects a closed lease")
    @EnumSource(Mutation.class)
    void closedLeaseMutationIsRejectedForEveryMutator(Mutation mutation) {
        JsonSessionStore store = new JsonSessionStore(temp.resolve("sessions"));
        SessionWriterLease lease = store.acquireSessionWriter("closed-session");
        lease.close();

        assertReason(
                SessionWriterUnavailableException.Reason.LEASE_CLOSED,
                () -> mutation.through(lease, "closed-session"),
                mutation + " accepted a closed lease");
        assertPayloadAbsent(store.sessionsDir(), "closed-session");
    }

    @ParameterizedTest(name = "{0} rejects a foreign-store lease")
    @EnumSource(Mutation.class)
    void foreignLeaseMutationIsRejectedForEveryMutator(Mutation mutation) {
        String sessionId = "foreign-session-" + mutation.name().toLowerCase(java.util.Locale.ROOT);
        JsonSessionStore owner = new JsonSessionStore(temp.resolve("owner"));
        JsonSessionStore foreign = new JsonSessionStore(temp.resolve("foreign"));
        try (SessionWriterLease foreignLease = foreign.acquireSessionWriter(sessionId)) {
            assertReason(
                    SessionWriterUnavailableException.Reason.FOREIGN_LEASE,
                    () -> mutation.foreign(owner, foreignLease, sessionId),
                    mutation + " accepted a lease issued by another store");
            assertPayloadAbsent(owner.sessionsDir(), sessionId);
        }
    }

    @Test
    void oneJvmCannotAcquireTheSameSessionTwiceAndCanReopenAfterClose() {
        JsonSessionStore store = new JsonSessionStore(temp.resolve("sessions"));
        SessionWriterLease first = store.acquireSessionWriter("same-jvm");
        try {
            SessionWriterUnavailableException busy = assertThrows(
                    SessionWriterUnavailableException.class,
                    () -> store.acquireSessionWriter("same-jvm"));
            assertEquals(SessionWriterUnavailableException.Reason.WRITER_BUSY, busy.reason());
        } finally {
            first.close();
        }

        try (SessionWriterLease reopened = store.acquireSessionWriter("same-jvm")) {
            assertTrue(reopened.isOpen());
            assertNotEquals(first, reopened);
        }
    }

    @Test
    void reservationAndLockSidecarsNeverBecomeListedSessions() {
        Path sessions = temp.resolve("sessions");
        Path workspace = temp.resolve("workspace");
        JsonSessionStore store = store(sessions, () -> entropy(0x31));
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);

        try (SessionWriterLease lease = store.reserveNewSession(workspace, CREATED_AT)) {
            assertTrue(Files.exists(lease.reservationPath().orElseThrow(), LinkOption.NOFOLLOW_LINKS));
            assertTrue(Files.exists(lease.lockPath(), LinkOption.NOFOLLOW_LINKS));
            assertTrue(store.listSessions(workspaceId).isEmpty());

            lease.save(data(lease.sessionId()));
            assertEquals(
                    List.of(lease.sessionId()),
                    store.listSessions(workspaceId).stream()
                            .map(SessionSummary::sessionId)
                            .toList());
        }
    }

    @Test
    void explicitOpenAtomicallyReservesAndAuthenticatesItsIdentity() throws Exception {
        Path sessions = temp.resolve("sessions");
        JsonSessionStore store = new JsonSessionStore(sessions);
        String sessionId = "explicit-session";
        Path reservation;

        try (SessionWriterLease lease = store.acquireSessionWriter(sessionId)) {
            reservation = lease.reservationPath().orElseThrow();
            assertEquals(
                    "talos-session-reservation-v1\n" + sessionId + "\n",
                    Files.readString(reservation, StandardCharsets.UTF_8));
        }

        Files.writeString(reservation, "wrong-owner\n", StandardCharsets.UTF_8);
        assertReason(
                SessionWriterUnavailableException.Reason.RESERVATION_IO,
                () -> store.acquireSessionWriter(sessionId),
                "an explicit writer trusted a mismatched retained reservation");
    }

    @Test
    void appendIoFailureIsVisibleAndPoisonsTheLease() throws Exception {
        Path sessions = temp.resolve("sessions");
        JsonSessionStore store = new JsonSessionStore(sessions);
        String sessionId = "append-io";
        try (SessionWriterLease lease = store.acquireSessionWriter(sessionId)) {
            Files.createDirectory(sessions.resolve(sessionId + ".turns.jsonl"));

            assertReason(
                    SessionWriterUnavailableException.Reason.PERSISTENCE_IO,
                    () -> lease.appendTurn(sessionId, record()),
                    "a failed direct append returned as successful");
            assertReason(
                    SessionWriterUnavailableException.Reason.LEASE_POISONED,
                    () -> lease.save(data(sessionId)),
                    "a lease remained writable after an uncertain append");
        }
    }

    @Test
    void closeIsTerminalAndDrainsItsActiveLeaseBeforeReturning() {
        Path sessions = temp.resolve("sessions");
        JsonSessionStore store = new JsonSessionStore(sessions);
        SessionWriterLease lease = store.acquireSessionWriter("store-close");

        store.close();

        assertFalse(lease.isOpen());
        assertReason(
                SessionWriterUnavailableException.Reason.STORE_CLOSED,
                () -> store.acquireSessionWriter("after-close"),
                "a closed store acquired another explicit writer");
        assertReason(
                SessionWriterUnavailableException.Reason.STORE_CLOSED,
                () -> store.reserveNewSession(temp.resolve("workspace"), CREATED_AT),
                "a closed store published another generated reservation");
    }

    @Test
    void platformUnsafeSessionIdsFailWithOneTypedReason() {
        JsonSessionStore store = new JsonSessionStore(temp.resolve("sessions"));
        for (String invalid : List.of(
                "CON", "con.txt", "NUL", "COM1.log", "bad:name",
                "bad*name", "bad?name", "bad name", "trailing.")) {
            assertReason(
                    SessionWriterUnavailableException.Reason.INVALID_SESSION_ID,
                    () -> store.acquireSessionWriter(invalid),
                    "unsafe session id was accepted: " + invalid);
        }
        String tooLong = "a".repeat(201);
        assertReason(
                SessionWriterUnavailableException.Reason.INVALID_SESSION_ID,
                () -> store.acquireSessionWriter(tooLong),
                "an overlong session id reached platform path construction");
    }

    private static JsonSessionStore store(
            Path sessions,
            java.util.function.Supplier<byte[]> entropy
    ) {
        return new JsonSessionStore(sessions, new AtomicFileWriter(), entropy);
    }

    private static List<Path> reservationFiles(Path sessions) {
        Path ownership = sessions.resolve(".ownership");
        if (!Files.isDirectory(ownership, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var entries = Files.list(ownership)) {
            return entries
                    .filter(path -> path.getFileName().toString().endsWith(".session.reserve"))
                    .sorted()
                    .toList();
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static byte[] entropy(int value) {
        byte[] out = new byte[16];
        java.util.Arrays.fill(out, (byte) value);
        return out;
    }

    private static void assertPayloadAbsent(Path sessions, String sessionId) {
        assertFalse(Files.exists(
                sessions.resolve(sessionId + ".json"),
                LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(
                sessions.resolve(sessionId + ".turns.jsonl"),
                LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(
                sessions.resolve("traces").resolve(sessionId),
                LinkOption.NOFOLLOW_LINKS));
    }

    private static SessionData data(String sessionId) {
        return new SessionData(
                sessionId,
                "/workspace",
                "session sketch",
                1,
                CREATED_AT,
                List.of(new SessionData.Turn("user", "hello", "ok")),
                "test-model");
    }

    private static TurnRecord record() {
        return new TurnRecord(
                1,
                CREATED_AT,
                5L,
                "inspect the workspace",
                "inspection complete",
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
                        "lease-contract-trace",
                        sessionId,
                        1,
                        CREATED_AT.toString())
                .workspaceHash("workspace-hash")
                .mode("ask")
                .model("llama.cpp", "test-model")
                .build();
    }

    private static void assertReason(
            SessionWriterUnavailableException.Reason expected,
            org.junit.jupiter.api.function.Executable action,
            String message
    ) {
        SessionWriterUnavailableException failure = assertThrows(
                SessionWriterUnavailableException.class,
                action,
                message);
        assertEquals(expected, failure.reason(), message);
    }

    private enum Mutation {
        SNAPSHOT {
            @Override void direct(JsonSessionStore store, String sessionId) {
                store.save(data(sessionId));
            }

            @Override void through(SessionWriterLease lease, String sessionId) {
                lease.save(data(sessionId));
            }

            @Override void foreign(JsonSessionStore store, SessionWriterLease lease, String sessionId) {
                store.save(lease, data(sessionId));
            }
        },
        TURN_LOG {
            @Override void direct(JsonSessionStore store, String sessionId) {
                store.appendTurn(sessionId, record());
            }

            @Override void through(SessionWriterLease lease, String sessionId) {
                lease.appendTurn(sessionId, record());
            }

            @Override void foreign(JsonSessionStore store, SessionWriterLease lease, String sessionId) {
                store.appendTurn(lease, sessionId, record());
            }
        },
        TRACE {
            @Override void direct(JsonSessionStore store, String sessionId) {
                store.saveTrace(sessionId, trace(sessionId));
            }

            @Override void through(SessionWriterLease lease, String sessionId) {
                lease.saveTrace(sessionId, trace(sessionId));
            }

            @Override void foreign(JsonSessionStore store, SessionWriterLease lease, String sessionId) {
                store.saveTrace(lease, sessionId, trace(sessionId));
            }
        },
        TURN_ENVELOPE {
            @Override void direct(JsonSessionStore store, String sessionId) {
                store.persistTurnEnvelope(sessionId, record(), null);
            }

            @Override void through(SessionWriterLease lease, String sessionId) {
                lease.persistTurnEnvelope(sessionId, record(), null);
            }

            @Override void foreign(JsonSessionStore store, SessionWriterLease lease, String sessionId) {
                store.persistTurnEnvelope(lease, sessionId, record(), null);
            }
        },
        DELETE {
            @Override void direct(JsonSessionStore store, String sessionId) {
                store.delete(sessionId);
            }

            @Override void through(SessionWriterLease lease, String sessionId) {
                lease.delete(sessionId);
            }

            @Override void foreign(JsonSessionStore store, SessionWriterLease lease, String sessionId) {
                store.delete(lease, sessionId);
            }
        };

        abstract void direct(JsonSessionStore store, String sessionId);

        abstract void through(SessionWriterLease lease, String sessionId);

        abstract void foreign(JsonSessionStore store, SessionWriterLease lease, String sessionId);
    }
}
