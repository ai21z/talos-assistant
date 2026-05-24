package dev.talos.cli.launcher;

import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunCmdTerminalModeTest {

    @Test
    void terminalPolicyUsesSystemOnlyWhenAConsoleIsAvailable() {
        assertFalse(RunCmd.shouldUseSystemTerminal(false, true, true, 0),
                "Piped/manual transcript mode should not probe the system terminal.");
        assertFalse(RunCmd.shouldUseSystemTerminal(true, false, true, 0),
                "Redirected stdin should use the plain terminal path.");
        assertFalse(RunCmd.shouldUseSystemTerminal(true, true, false, 0),
                "Redirected stdout should use the plain terminal path.");
        assertTrue(RunCmd.shouldUseSystemTerminal(true, true, true, 0),
                "Interactive mode should keep the richer system terminal.");
        assertFalse(RunCmd.shouldUseSystemTerminal(true, true, true, 1),
                "Buffered stdin means Talos is being driven non-interactively even if a console exists.");
    }

    @Test
    void pipedModeCanBuildNonSystemTerminal() throws Exception {
        try (var terminal = RunCmd.buildTerminal(false)) {
            assertNotNull(terminal);
        }
    }

    @Test
    void terminalReaderPreservesLiteralWindowsPathBackslashes() throws Exception {
        String command = "/prompt-debug save "
                + "\"C:\\Users\\arisz\\Projects\\LOQ\\loqj-cli\\local\\manual-testing\\example\\artifacts\\prompt-debug\"";
        ByteArrayInputStream input = new ByteArrayInputStream((command + "\n").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Terminal terminal = TerminalBuilder.builder()
                .system(false)
                .dumb(true)
                .streams(input, output)
                .build()) {
            LineReader reader = RunCmd.baseLineReaderBuilder(terminal).build();

            assertEquals(command, reader.readLine(""));
        }
    }
}
