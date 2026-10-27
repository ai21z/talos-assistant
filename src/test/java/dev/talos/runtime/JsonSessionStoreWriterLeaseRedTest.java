package dev.talos.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Candidate-identical T1162 reds for namespace allocation and writer exclusion. */
public final class JsonSessionStoreWriterLeaseRedTest {
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(20);

    @TempDir
    Path temp;

    @Test
    void sameWorkspaceAndSecondProduceDistinctCandidateIds() {
        Path workspace = temp.resolve("workspace");
        Instant instant = Instant.parse("2026-08-01T10:15:30Z");

        String first = JsonSessionStore.newSessionInstanceId(workspace, instant);
        String second = JsonSessionStore.newSessionInstanceId(workspace, instant);

        assertNotEquals(first, second,
                "new session identities need entropy even before atomic reservation");
    }

    @Test
    void twoProcessesInTheSameWorkspaceAndSecondProduceDistinctCandidateIds() throws Exception {
        Path workspace = temp.resolve("workspace");
        Path controls = temp.resolve("allocation-controls");
        Files.createDirectories(controls);

        Process first = start("allocate", temp.resolve("sessions"), workspace.toString(),
                controls.resolve("unused-ready-1"), controls.resolve("unused-release-1"));
        Process second = start("allocate", temp.resolve("sessions"), workspace.toString(),
                controls.resolve("unused-ready-2"), controls.resolve("unused-release-2"));

        ProcessResult firstResult = await(first);
        ProcessResult secondResult = await(second);
        assertEquals(0, firstResult.exitCode(), firstResult.output());
        assertEquals(0, secondResult.exitCode(), secondResult.output());
        assertNotEquals(firstResult.output().trim(), secondResult.output().trim(),
                "separate JVMs need distinct identities for the same workspace and instant");
    }

    @Test
    void secondProcessCannotWriteAnExplicitSessionWhileFirstOwnsIt() throws Exception {
        Path sessions = temp.resolve("sessions");
        Path controls = temp.resolve("controls");
        Files.createDirectories(controls);
        String sessionId = "explicit-shared-session";
        Path ready = controls.resolve("ready");
        Path release = controls.resolve("release");

        Process holder = start("hold", sessions, sessionId, ready, release);
        awaitFile(ready, holder);
        Process contender = start("try", sessions, sessionId,
                controls.resolve("unused-ready"), controls.resolve("unused-release"));
        ProcessResult contenderResult = await(contender);
        Files.writeString(release, "release\n", StandardCharsets.UTF_8);
        ProcessResult holderResult = await(holder);

        assertEquals(23, contenderResult.exitCode(), contenderResult.output());
        assertEquals(0, holderResult.exitCode(), holderResult.output());
        assertTrue(Files.exists(sessions.resolve(sessionId + ".json")));
    }

    private static Process start(
            String mode,
            Path sessions,
            String sessionId,
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
                JsonSessionStoreWriterLeaseProcessMain.class.getName(),
                mode,
                sessions.toString(),
                sessionId,
                ready.toString(),
                release.toString())
                .redirectErrorStream(true)
                .start();
    }

    private static void awaitFile(Path file, Process child) throws Exception {
        long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
        while (!Files.exists(file)) {
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
    }

    private static ProcessResult await(Process child) throws Exception {
        if (!child.waitFor(EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            child.destroyForcibly();
            throw new AssertionError("child exit timed out");
        }
        return new ProcessResult(
                child.exitValue(),
                new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private record ProcessResult(int exitCode, String output) { }
}
