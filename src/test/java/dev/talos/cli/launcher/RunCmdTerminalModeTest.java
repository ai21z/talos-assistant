package dev.talos.cli.launcher;

import org.junit.jupiter.api.Test;

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
}
