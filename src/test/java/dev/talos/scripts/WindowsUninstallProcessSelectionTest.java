package dev.talos.scripts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WindowsUninstallProcessSelectionTest {
    @TempDir
    Path tempDir;

    @Test
    void selectsOnlyBoundInstallProcessesWithoutInvokingEitherUninstaller() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"),
                "Windows path/process selection contract");
        List<Path> candidates = new ArrayList<>();
        String programFiles = System.getenv("ProgramFiles");
        String windowsRoot = System.getenv("SystemRoot");
        if (programFiles != null) candidates.add(Path.of(programFiles, "PowerShell", "7", "pwsh.exe"));
        if (windowsRoot != null) {
            candidates.add(Path.of(windowsRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe"));
        }
        List<Path> available = candidates.stream().filter(Files::isRegularFile).toList();
        assumeTrue(!available.isEmpty(), "PowerShell is unavailable; pure selector contract not executed");

        Path root = Path.of("").toAbsolutePath().normalize();
        for (int index = 0; index < available.size(); index++) {
            runSelectorContract(available.get(index), root, tempDir.resolve("selector-contract-" + index + ".txt"));
        }
    }

    private void runSelectorContract(Path powershell, Path root, Path output) throws Exception {
        // The test resource parses both scripts but loads only the enumerated pure
        // helper definitions. It supplies synthetic process records, never live CIM.
        Process fixture = new ProcessBuilder(
                powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-File",
                root.resolve("src/test/resources/scripts/uninstall-process-selection-contract.ps1").toString(),
                "-PublicScript", root.resolve("tools/uninstall-talos.ps1").toString(),
                "-LegacyScript", root.resolve("tools/uninstall-windows.ps1").toString())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        try {
            assertTrue(fixture.waitFor(30, TimeUnit.SECONDS), "Pure selector fixture exceeded 30 seconds");
            assertTrue(Files.size(output) <= 65536, "Unexpectedly large selector diagnostic");
            String result = Files.readString(output, StandardCharsets.UTF_8);
            assertEquals(0, fixture.exitValue(), result);
            assertEquals("TALOS_UNINSTALL_SELECTOR_CONTRACT_PASS", result.strip());
        } finally {
            if (fixture.isAlive()) {
                fixture.destroyForcibly();
                assertTrue(fixture.waitFor(5, TimeUnit.SECONDS), "Pure fixture process did not terminate");
            }
        }
    }
}
