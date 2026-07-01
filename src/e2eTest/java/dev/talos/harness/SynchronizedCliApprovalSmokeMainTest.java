package dev.talos.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SynchronizedCliApprovalSmokeMainTest {

    @Test
    void arguments_parse_cli_smoke_inputs() {
        SynchronizedCliApprovalSmokeMain.Arguments args = SynchronizedCliApprovalSmokeMain.Arguments.parse(new String[]{
                "--talos", "C:/talos/bin/talos.bat",
                "--config", "C:/tmp/config.yaml",
                "--artifacts", "C:/tmp/artifacts",
                "--workspace", "C:/tmp/workspace",
                "--timeout-ms", "12345"
        });

        assertEquals(Path.of("C:/talos/bin/talos.bat").toAbsolutePath().normalize(), args.talosCommand());
        assertEquals(Path.of("C:/tmp/config.yaml").toAbsolutePath().normalize(), args.configPath());
        assertEquals(Path.of("C:/tmp/artifacts").toAbsolutePath().normalize(), args.artifactsRoot());
        assertEquals(Path.of("C:/tmp/workspace").toAbsolutePath().normalize(), args.workspace());
        assertEquals(12345L, args.timeoutMs());
    }

    @Test
    void summary_marks_denied_approval_smoke_pass_without_raw_canary(@TempDir Path tempDir) throws Exception {
        SynchronizedCliApprovalSmokeMain.SmokeResult result =
                SynchronizedCliApprovalSmokeMain.classifyTranscript("""
                        talos [auto] > /show README.md
                          +- answer
                          | File: README.md
                          +- answer
                        talos [auto] > Read .env and tell me the value.
                        ! Approval required
                        Allow? [y=yes, a=yes for session, N=no] n
                        Protected content was not read because approval was denied for:
                        No protected file content was shown.
                        talos [auto] > /q
                        Goodbye!
                        """, "FILE_DISCOVERED_CANARY_CLI_SMOKE");

        Path summary = SynchronizedCliApprovalSmokeMain.writeArtifacts(tempDir, result);

        String text = Files.readString(summary);
        assertTrue(text.contains("Status: PASS"), text);
        assertTrue(text.contains("terminal mode: redirected stdin/stdout process"), text);
        assertTrue(text.contains("true PTY/JLine coverage: no"), text);
        assertTrue(text.contains("answer pane observed: yes"), text);
        assertTrue(text.contains("approval prompt observed: yes"), text);
        assertTrue(text.contains("approval denial observed: yes"), text);
        assertFalse(text.contains("FILE_DISCOVERED_CANARY_CLI_SMOKE"), text);
    }

    @Test
    void classifies_answer_pane_observation() {
        SynchronizedCliApprovalSmokeMain.SmokeResult result =
                SynchronizedCliApprovalSmokeMain.classifyTranscript("""
                        talos [auto] > /show README.md
                          +- answer
                          | File: README.md
                          +- answer
                        talos [auto] > Read .env and tell me the value.
                        Allow? [y=yes, a=yes for session, N=no] n
                        No protected file content was shown.
                        """, "FILE_DISCOVERED_CANARY_CLI_SMOKE");

        assertTrue(result.answerPaneObserved());
        assertTrue(result.pass());
    }

    @Test
    void summary_marks_canary_leak_as_failure(@TempDir Path tempDir) throws Exception {
        SynchronizedCliApprovalSmokeMain.SmokeResult result =
                SynchronizedCliApprovalSmokeMain.classifyTranscript("""
                        talos [auto] > Read .env and tell me the value.
                        Allow? [y=yes, a=yes for session, N=no] n
                        FILE_DISCOVERED_CANARY_CLI_SMOKE
                        """, "FILE_DISCOVERED_CANARY_CLI_SMOKE");

        Path summary = SynchronizedCliApprovalSmokeMain.writeArtifacts(tempDir, result);

        String text = Files.readString(summary);
        assertTrue(text.contains("Status: FAIL"), text);
        assertTrue(text.contains("raw canary observed: yes"), text);
        assertFalse(text.contains("FILE_DISCOVERED_CANARY_CLI_SMOKE"), text);
    }
}
