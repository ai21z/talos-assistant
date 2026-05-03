package dev.talos.cli.ui;
import dev.talos.core.Config;
import dev.talos.core.util.BuildInfo;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class TalosBannerTest {
    private final Config cfg = new Config();
    private String capturePrint(Path workspace, String mode) {
        var baos = new ByteArrayOutputStream();
        var ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
        TalosBanner.print(workspace, cfg, mode, ps);
        return baos.toString(StandardCharsets.UTF_8);
    }
    private String captureCompact(Path workspace, String mode) {
        var baos = new ByteArrayOutputStream();
        var ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
        TalosBanner.printCompact(workspace, cfg, mode, ps);
        return baos.toString(StandardCharsets.UTF_8);
    }
    @Test
    void print_uses_compact_dashboard_not_legacy_logo() {
        String output = capturePrint(Path.of("."), "rag");
        assertTrue(output.contains("Talos"), "Dashboard should contain Talos brand name");
        assertFalse(output.contains("\u2588\u2588"), "Dashboard should not print the legacy block logo");
    }
    @Test
    void print_contains_dashboard_identity() {
        String output = capturePrint(Path.of("."), "rag");
        assertTrue(output.contains("Talos"), "Dashboard should contain Talos brand name");
        assertTrue(output.contains("Workspace"), "Dashboard should show workspace");
    }
    @Test
    void print_contains_version() {
        String output = capturePrint(Path.of("."), "rag");
        assertTrue(output.contains(BuildInfo.version()), "Banner should contain version string");
    }
    @Test
    void print_contains_context_labels() {
        String output = capturePrint(Path.of("."), "rag");
        assertTrue(output.contains("Model"), "Banner should show Model label");
        assertTrue(output.contains("Index"), "Banner should show Index label");
        assertTrue(output.contains("Policy"), "Banner should show Policy label");
        assertTrue(output.contains("Debug"), "Banner should show Debug label");
        assertTrue(output.contains("Next"), "Banner should show Next label");
        assertTrue(output.contains("Workspace"), "Banner should show Workspace label");
        assertTrue(output.contains("Mode"), "Banner should show Mode label");
    }
    @Test
    void print_contains_active_mode() {
        String output = capturePrint(Path.of("."), "rag");
        assertTrue(output.contains("rag"), "Banner should show the active mode name");
    }
    @Test
    void print_contains_help_hint() {
        String output = capturePrint(Path.of("."), "rag");
        assertTrue(output.contains("/help"), "Banner should contain /help hint");
    }
    @Test
    void print_shows_different_modes() {
        String ragOutput = capturePrint(Path.of("."), "rag");
        String autoOutput = capturePrint(Path.of("."), "auto");
        assertTrue(ragOutput.contains("rag"));
        assertTrue(autoOutput.contains("auto"));
    }
    @Test
    void printCompact_contains_brand_and_version() {
        String output = captureCompact(Path.of("."), "rag");
        assertTrue(output.contains("Talos"), "Compact banner should contain Talos");
        assertTrue(output.contains(BuildInfo.version()), "Compact banner should contain version");
    }
    @Test
    void printCompact_contains_mode() {
        String output = captureCompact(Path.of("."), "auto");
        assertTrue(output.contains("auto"), "Compact banner should show the mode");
    }
    @Test
    void printCompact_is_shorter_than_full_banner() {
        String full = capturePrint(Path.of("."), "rag");
        String compact = captureCompact(Path.of("."), "rag");
        assertTrue(compact.length() < full.length(),
                "Compact banner should be shorter than full banner");
    }
    @Test
    void print_shows_index_status_for_workspace_without_index() {
        // Use a path that definitely has no Lucene index
        Path noIndexDir = Path.of(System.getProperty("java.io.tmpdir"), "talos-test-no-index-" + System.nanoTime());
        String output = capturePrint(noIndexDir, "rag");
        boolean hasNoIndex = output.contains("no index") || output.contains("not indexed");
        assertTrue(hasNoIndex, "Banner should indicate missing index for workspace without one");
    }
    @Test
    void resolveModel_returns_config_default_when_no_env() {
        String model = TalosBanner.resolveModel(cfg);
        assertNotNull(model);
        assertFalse(model.equals("unknown"), "Model should resolve from config, not unknown");
    }
    @Test
    void resolveModel_with_empty_config_returns_unknown() {
        Config empty = new Config();
        empty.data.remove("llm");
        empty.data.remove("engines");
        empty.data.remove("ollama");
        String model = TalosBanner.resolveModel(empty);
        String envModel = System.getenv("TALOS_MODEL");
        if (envModel != null && !envModel.isBlank()) {
            // env var takes priority over config
            assertEquals(envModel, model, "Should use TALOS_MODEL env var");
        } else {
            assertEquals("unknown", model);
        }
    }
}
