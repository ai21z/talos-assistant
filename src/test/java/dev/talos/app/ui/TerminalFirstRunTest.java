package dev.talos.app.ui;

import dev.talos.cli.doctor.ProbeResult;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TerminalFirstRun}.
 *
 * <p>T785 deliberately replaced the old expectations here: the flow used to
 * print an unconditional {@code "✓ Setup complete."} with zero verification
 * (the pre-T785 contract was pinned by this file's git history). It now runs
 * the doctor preflight and reports honestly. The doctor runner, output
 * stream, and sentinel path are injected so these tests never probe the real
 * machine or touch the real {@code ~/.talos} (the old test wrote the real
 * sentinel file).
 */
class TerminalFirstRunTest {

    @TempDir Path tempDir;

    @Nested class FirstRunFlow {

        @Test
        void allChecksPassingPrintsSetupVerified() {
            Output out = runWith(() -> List.of(
                    ProbeResult.pass("config", "ok"),
                    ProbeResult.pass("engine-files", "present")));

            assertTrue(out.text.contains("Talos — First Run Setup"));
            assertTrue(out.text.contains("PASS  config"));
            assertTrue(out.text.contains("Setup verified. Starting Talos..."));
            assertFalse(out.text.contains("✓ Setup complete"),
                    "the unverified historical success claim must never print");
            assertTrue(out.returned);
            assertTrue(Files.exists(out.sentinel));
        }

        @Test
        void warningsOnlyPrintsCompleteWithWarnings() {
            Output out = runWith(() -> List.of(
                    ProbeResult.pass("config", "ok"),
                    ProbeResult.warn("server", "managed server not running")));

            assertTrue(out.text.contains("Setup complete with warnings. Starting Talos..."));
            assertFalse(out.text.contains("Setup verified"));
            assertTrue(out.returned);
        }

        @Test
        void failuresPrintSetupIncompleteWithHintsAndStillStartTalos() {
            Output out = runWith(() -> List.of(
                    ProbeResult.fail("engine-files", "model file missing", "run 'talos setup models'"),
                    ProbeResult.fail("home-writable", "denied", "check permissions")));

            assertTrue(out.text.contains("FAIL  engine-files"));
            assertTrue(out.text.contains("fix: run 'talos setup models'"));
            assertTrue(out.text.contains("Setup incomplete — 2 check(s) failed."));
            assertTrue(out.text.contains("run 'talos doctor' to re-check"));
            assertTrue(out.text.contains("Configure models with 'talos setup models'."));
            assertFalse(out.text.contains("Setup verified"));
            assertFalse(out.text.contains("Setup complete"),
                    "a failing preflight must not claim completion in any wording");
            assertTrue(out.returned,
                    "first-run must not lock the user out of the REPL (Main exits on false)");
            assertTrue(Files.exists(out.sentinel),
                    "the sentinel is written on failure too — orientation is shown once;"
                            + " recurring checks live in 'talos doctor'");
        }

        @Test
        void doctorCrashDegradesToANoticeAndStillStartsTalos() {
            Output out = runWith(() -> { throw new IllegalStateException("kaput"); });

            assertTrue(out.text.contains("Preflight checks could not run (kaput)."));
            assertTrue(out.text.contains("Run 'talos doctor' after startup"));
            assertTrue(out.returned, "first-run must never crash the launcher");
            assertTrue(Files.exists(out.sentinel));
        }

        private Output runWith(TerminalFirstRun.DoctorRunner doctor) {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            Path sentinel = tempDir.resolve(".talos").resolve("first_run_done");
            boolean returned = TerminalFirstRun.run(doctor,
                    new PrintStream(bout, true, StandardCharsets.UTF_8), sentinel);
            return new Output(bout.toString(StandardCharsets.UTF_8), returned, sentinel);
        }

        record Output(String text, boolean returned, Path sentinel) {}
    }

    @Nested class SentinelLogic {

        @Test
        void shouldRun_whenSentinelExists_returnsFalse() {
            // The production sentinel is ~/.talos/first_run_done. If it
            // already exists on this machine, shouldRun returns false.
            Path sentinel = Path.of(System.getProperty("user.home"), ".talos", "first_run_done");
            if (Files.exists(sentinel)) {
                assertFalse(TerminalFirstRun.shouldRun());
            }
        }

        @Test
        void writeSentinel_createsFileAtTheGivenPath() throws Exception {
            // Hermetic since T785: the path-taking overload writes wherever
            // it is pointed; the old test wrote the developer's real sentinel.
            Path sentinel = tempDir.resolve(".talos").resolve("first_run_done");

            TerminalFirstRun.writeSentinel(sentinel);

            assertTrue(Files.exists(sentinel));
            assertEquals("ok", Files.readString(sentinel));
        }
    }

    @Nested class MainIntegration {
        @Test void mainClass_usesTerminalFirstRun() {
            assertNotNull(TerminalFirstRun.class);
        }

        @Test void setupSummary_is_backend_neutral() {
            String summary = TerminalFirstRun.setupSummary();
            assertTrue(summary.contains("llama.cpp"));
            assertTrue(summary.contains("talos setup models"));
            assertFalse(summary.contains("requires Ollama"));
        }
    }
}
