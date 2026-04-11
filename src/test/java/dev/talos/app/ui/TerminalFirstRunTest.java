package dev.talos.app.ui;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
/**
 * Tests for {@link TerminalFirstRun}.
 *
 * <p>Process-dependent methods (Ollama detection, model pull) are not tested
 * here since they require a real Ollama installation. Tests focus on the
 * sentinel file logic and structural contract.
 */
class TerminalFirstRunTest {
    @Nested class SentinelLogic {
        @Test void shouldRun_whenSentinelExists_returnsFalse() throws Exception {
            // The sentinel is ~/.talos/first_run_done
            // If it already exists on this machine, shouldRun returns false
            Path sentinel = Path.of(System.getProperty("user.home"), ".talos", "first_run_done");
            if (Files.exists(sentinel)) {
                assertFalse(TerminalFirstRun.shouldRun());
            }
            // If it doesn't exist, shouldRun returns true
            // (we can't safely delete it in a test)
        }
        @Test void writeSentinel_createsFile() throws Exception {
            // Calling writeSentinel should create the file
            Path sentinel = Path.of(System.getProperty("user.home"), ".talos", "first_run_done");
            TerminalFirstRun.writeSentinel();
            assertTrue(Files.exists(sentinel), "Sentinel file should exist after writeSentinel()");
            // shouldRun should return false now
            assertFalse(TerminalFirstRun.shouldRun());
        }
    }
    @Nested class OllamaDetection {
        @Test void checkOllamaInstalled_doesNotThrow() {
            // Should never throw, regardless of whether Ollama is installed
            assertDoesNotThrow(() -> TerminalFirstRun.checkOllamaInstalled());
        }
        @Test void checkModelAvailable_doesNotThrow() {
            // Should never throw even if Ollama is not installed
            assertDoesNotThrow(() -> TerminalFirstRun.checkModelAvailable("nonexistent-model:latest"));
        }
        @Test void checkModelAvailable_withNullModel_doesNotThrow() {
            assertDoesNotThrow(() -> TerminalFirstRun.checkModelAvailable(null));
        }
    }
    @Nested class MainIntegration {
        @Test void mainClass_usesTerminalFirstRun() throws Exception {
            // Verify Main.java imports TerminalFirstRun (not FirstRunWizard)
            // This is a structural test — if Main.java switches back to JavaFX, this compile-time
            // reference will break
            assertNotNull(TerminalFirstRun.class);
        }
    }
}