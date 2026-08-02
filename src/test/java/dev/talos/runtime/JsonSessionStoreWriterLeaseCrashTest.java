package dev.talos.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-process T1162 proof that OS lock ownership is recoverable after a crash. */
final class JsonSessionStoreWriterLeaseCrashTest {
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(20);
    private static final Instant CREATED_AT = Instant.parse("2026-08-01T12:34:56Z");

    @TempDir
    Path temp;

    @Test
    void forcedProcessDeathReleasesLockWithoutConsumingOrRewritingReservation() throws Exception {
        Path sessions = temp.resolve("sessions");
        Path workspace = temp.resolve("workspace");
        Path ready = temp.resolve("control").resolve("ready");
        byte[] entropy = entropy(0x6d);
        Process holder = start(sessions, workspace, entropy, ready, "clean");
        String sessionId;
        Path reservation;
        Path lock;
        byte[] reservationBeforeCrash;
        try {
            sessionId = awaitSessionId(ready, holder);
            reservation = sessions.resolve(".ownership")
                    .resolve(sessionId + ".session.reserve");
            lock = sessions.resolve(".ownership")
                    .resolve(sessionId + ".session.writer.lock");
            assertTrue(holder.isAlive());
            assertTrue(Files.isRegularFile(reservation, LinkOption.NOFOLLOW_LINKS));
            assertTrue(Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS));
            reservationBeforeCrash = Files.readAllBytes(reservation);

            holder.destroyForcibly();
            assertTrue(holder.waitFor(EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            assertFalse(holder.isAlive());
        } finally {
            if (holder.isAlive()) {
                holder.destroyForcibly();
                holder.waitFor(EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
        }

        JsonSessionStore reopenedStore = new JsonSessionStore(sessions);
        try (SessionWriterLease reopened = reopenedStore.acquireSessionWriter(sessionId)) {
            assertTrue(reopened.isOpen());
            assertEquals(
                    "crash-holder-snapshot",
                    reopened.load(sessionId).orElseThrow().sketch());
            reopened.appendTurn(sessionId, new TurnRecord(
                    1,
                    CREATED_AT.plusSeconds(1),
                    1L,
                    "resume after crash",
                    "resumed",
                    List.of(),
                    0,
                    0,
                    0,
                    "",
                    "ok",
                    TurnPolicyTrace.empty(),
                    ""));
        }

        assertArrayEquals(reservationBeforeCrash, Files.readAllBytes(reservation));
        assertEquals(
                "talos-session-reservation-v1\n" + sessionId + "\n",
                Files.readString(reservation, StandardCharsets.UTF_8));
        assertEquals("resumed", reopenedStore.loadTurns(sessionId).getFirst().assistantText());
        assertTrue(Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void incompleteTailAfterForcedDeathBlocksNextAppendWithoutChangingBytes() throws Exception {
        Path sessions = temp.resolve("partial-sessions");
        Path workspace = temp.resolve("partial-workspace");
        Path ready = temp.resolve("partial-control").resolve("ready");
        Process holder = start(
                sessions, workspace, entropy(0x7e), ready, "partial-turn");
        String sessionId;
        try {
            sessionId = awaitSessionId(ready, holder);
            holder.destroyForcibly();
            assertTrue(holder.waitFor(EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        } finally {
            if (holder.isAlive()) {
                holder.destroyForcibly();
                holder.waitFor(EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
        }

        Path turnLog = sessions.resolve(sessionId + ".turns.jsonl");
        byte[] before = Files.readAllBytes(turnLog);
        assertEquals(1, new JsonSessionStore(sessions).loadTurns(sessionId).size());

        JsonSessionStore reopenedStore = new JsonSessionStore(sessions);
        try (SessionWriterLease reopened = reopenedStore.acquireSessionWriter(sessionId)) {
            SessionWriterUnavailableException failure = assertThrows(
                    SessionWriterUnavailableException.class,
                    () -> reopened.appendTurn(sessionId, new TurnRecord(
                            2,
                            CREATED_AT.plusSeconds(1),
                            1L,
                            "new request",
                            "new answer",
                            List.of(),
                            0,
                            0,
                            0,
                            "",
                            "ok",
                            TurnPolicyTrace.empty(),
                            "")));
            assertEquals(
                    SessionWriterUnavailableException.Reason.INCOMPLETE_TURN_LOG,
                    failure.reason());
            assertFalse(reopened.isOpen(), "the incomplete-tail writer must be poisoned");
        }

        assertArrayEquals(before, Files.readAllBytes(turnLog));
        assertEquals(1, reopenedStore.loadTurns(sessionId).size());
    }

    private static Process start(
            Path sessions,
            Path workspace,
            byte[] entropy,
            Path ready,
            String mode
    ) throws Exception {
        String executable = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        return new ProcessBuilder(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                JsonSessionStoreWriterLeaseCrashProcessMain.class.getName(),
                sessions.toString(),
                workspace.toString(),
                CREATED_AT.toString(),
                HexFormat.of().formatHex(entropy),
                ready.toString(),
                mode)
                .redirectErrorStream(true)
                .start();
    }

    private static String awaitSessionId(Path ready, Process child) throws Exception {
        long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
        while (!Files.exists(ready, LinkOption.NOFOLLOW_LINKS)) {
            if (!child.isAlive()) {
                throw new AssertionError("child exited before ready: "
                        + new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            }
            if (System.nanoTime() >= deadline) {
                child.destroyForcibly();
                throw new AssertionError("child readiness timed out");
            }
            Thread.sleep(10L);
        }
        String sessionId = Files.readString(ready, StandardCharsets.UTF_8).strip();
        if (sessionId.isBlank()) {
            child.destroyForcibly();
            throw new AssertionError("child published a blank session id");
        }
        return sessionId;
    }

    private static byte[] entropy(int value) {
        byte[] out = new byte[16];
        java.util.Arrays.fill(out, (byte) value);
        return out;
    }
}
