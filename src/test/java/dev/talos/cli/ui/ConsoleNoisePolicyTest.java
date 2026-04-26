package dev.talos.cli.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleNoisePolicyTest {

    @Test
    void julDiagnosticsUseLocalTalosLogPath() {
        String path = ConsoleNoisePolicy.defaultJulLogPath().toString().replace('\\', '/');

        assertTrue(path.endsWith(".talos/logs/talos-jul.log"),
                "JUL diagnostics should go to the local Talos log directory, not the normal transcript.");
    }
}
