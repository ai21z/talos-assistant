package dev.talos.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real two-process T1162 proof for same-workspace/same-instant allocation. */
final class JsonSessionStoreReservationProcessTest {
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(20);
    private static final Instant CREATED_AT = Instant.parse("2026-08-01T14:15:16Z");

    @TempDir
    Path temp;

    @Test
    void twoProcessesReserveDistinctSameInstantIdsAndKeepAllArtifactsIsolated() throws Exception {
        Path sessions = temp.resolve("sessions");
        Path workspace = temp.resolve("workspace");
        Path controls = temp.resolve("controls");
        Path firstReady = controls.resolve("first.ready");
        Path secondReady = controls.resolve("second.ready");
        Path release = controls.resolve("release");
        byte[] firstEntropy = entropy(0x17);
        byte[] secondEntropy = entropy(0x29);
        Process first = start(
                sessions, workspace, List.of(firstEntropy), "first", firstReady, release);
        Process second = null;
        ProcessResult firstResult = null;
        ProcessResult secondResult = null;

        try {
            String firstId = awaitSessionId(firstReady, first);
            second = start(
                    sessions, workspace, List.of(firstEntropy, secondEntropy),
                    "second", secondReady, release);
            String secondId = awaitSessionId(secondReady, second);
            String prefix = JsonSessionStore.sessionIdFor(workspace)
                    + "-20260801141516-";
            assertEquals(prefix + HexFormat.of().formatHex(firstEntropy), firstId);
            assertEquals(prefix + HexFormat.of().formatHex(secondEntropy), secondId);
            assertNotEquals(firstId, secondId);
            assertTrue(first.isAlive(), "first writer must still own its lock");
            assertTrue(second.isAlive(), "second writer must still own its lock");

            JsonSessionStore reader = new JsonSessionStore(sessions);
            assertEquals("first-snapshot", reader.load(firstId).orElseThrow().sketch());
            assertEquals("second-snapshot", reader.load(secondId).orElseThrow().sketch());
            assertEquals("first-answer", reader.loadTurns(firstId).getFirst().assistantText());
            assertEquals("second-answer", reader.loadTurns(secondId).getFirst().assistantText());
            assertEquals("first-trace", reader.loadLatestTrace(firstId).orElseThrow().traceId());
            assertEquals("second-trace", reader.loadLatestTrace(secondId).orElseThrow().traceId());
            assertEquals(
                    List.of(firstId, secondId).stream().sorted().toList(),
                    reservationIds(sessions));

            assertOwnBytes(sessions.resolve(firstId + ".json"), "first-snapshot", "second-snapshot");
            assertOwnBytes(sessions.resolve(firstId + ".turns.jsonl"), "first-answer", "second-answer");
            assertOwnBytes(sessions.resolve(secondId + ".json"), "second-snapshot", "first-snapshot");
            assertOwnBytes(sessions.resolve(secondId + ".turns.jsonl"), "second-answer", "first-answer");
        } finally {
            Files.createDirectories(release.getParent());
            Files.writeString(release, "release\n", StandardCharsets.UTF_8);
            firstResult = terminateOrAwait(first);
            if (second != null) secondResult = terminateOrAwait(second);
        }

        assertEquals(0, firstResult.exitCode(), firstResult.output());
        assertTrue(secondResult != null, "second allocator process did not start");
        assertEquals(0, secondResult.exitCode(), secondResult.output());
    }

    private static Process start(
            Path sessions,
            Path workspace,
            List<byte[]> entropy,
            String label,
            Path ready,
            Path release
    ) throws Exception {
        String executable = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        return new ProcessBuilder(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                JsonSessionStoreReservationProcessMain.class.getName(),
                sessions.toString(),
                workspace.toString(),
                CREATED_AT.toString(),
                entropy.stream().map(HexFormat.of()::formatHex)
                        .collect(Collectors.joining(",")),
                label,
                ready.toString(),
                release.toString())
                .redirectErrorStream(true)
                .start();
    }

    private static String awaitSessionId(Path ready, Process child) throws Exception {
        long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
        while (!Files.exists(ready)) {
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
        return Files.readString(ready, StandardCharsets.UTF_8).strip();
    }

    private static ProcessResult terminateOrAwait(Process child) throws Exception {
        if (!child.waitFor(EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            child.destroyForcibly();
            if (!child.waitFor(EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("child could not be terminated");
            }
        }
        return new ProcessResult(
                child.exitValue(),
                new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static List<String> reservationIds(Path sessions) throws Exception {
        Path ownership = sessions.resolve(".ownership");
        try (var entries = Files.list(ownership)) {
            return entries
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".session.reserve"))
                    .map(name -> name.substring(
                            0,
                            name.length() - ".session.reserve".length()))
                    .sorted()
                    .toList();
        }
    }

    private static void assertOwnBytes(Path path, String own, String foreign) throws Exception {
        String contents = Files.readString(path, StandardCharsets.UTF_8);
        assertTrue(contents.contains(own), contents);
        assertFalse(contents.contains(foreign), contents);
    }

    private static byte[] entropy(int value) {
        byte[] out = new byte[16];
        java.util.Arrays.fill(out, (byte) value);
        return out;
    }

    private record ProcessResult(int exitCode, String output) { }
}
