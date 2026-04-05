package dev.loqj.cli.ui;
import dev.loqj.core.Config;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class LoqsBannerTest {
    private final Config cfg = new Config();
    private String capturePrint(Path workspace, String mode) {
        var baos = new ByteArrayOutputStream();
        var ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
        LoqsBanner.print(workspace, cfg, mode, ps);
        return baos.toString(StandardCharsets.UTF_8);
    }
    private String captureCompact(Path workspace, String mode) {
        var baos = new ByteArrayOutputStream();
        var ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
        LoqsBanner.printCompact(workspace, cfg, mode, ps);
        return baos.toString(StandardCharsets.UTF_8);
    }
    @Test
    void print_contains_logo_block_characters() {
        String output = capturePrint(Path.of("."), "rag");
        assertTrue(output.contains("\u2588\u2588"), "Banner should contain block characters from logo");
    }
    @Test
    void print_contains_tagline() {
        String output = capturePrint(Path.of("."), "rag");
        assertTrue(output.contains("Loqs"), "Banner should contain Loqs brand name");
        assertTrue(output.contains("Local Knowledge Engine"), "Banner should contain tagline");
    }
    @Test
    void print_contains_version() {
        String output = capturePrint(Path.of("."), "rag");
        assertTrue(output.contains("0.9.0-beta"), "Banner should contain version string");
    }
    @Test
    void print_contains_context_labels() {
        String output = capturePrint(Path.of("."), "rag");
        assertTrue(output.contains("Model"), "Banner should show Model label");
        assertTrue(output.contains("Embed"), "Banner should show Embed label");
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
        assertTrue(output.contains(":help"), "Banner should contain :help hint");
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
        assertTrue(output.contains("Loqs"), "Compact banner should contain Loqs");
        assertTrue(output.contains("0.9.0-beta"), "Compact banner should contain version");
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
        Path noIndexDir = Path.of(System.getProperty("java.io.tmpdir"), "loqj-test-no-index-" + System.nanoTime());
        String output = capturePrint(noIndexDir, "rag");
        boolean hasNoIndex = output.contains("no index") || output.contains("not indexed");
        assertTrue(hasNoIndex, "Banner should indicate missing index for workspace without one");
    }
    @Test
    void resolveModel_returns_config_default_when_no_env() {
        String model = LoqsBanner.resolveModel(cfg);
        assertNotNull(model);
        assertFalse(model.equals("unknown"), "Model should resolve from config, not unknown");
    }
    @Test
    void resolveModel_with_empty_config_returns_unknown() {
        Config empty = new Config();
        empty.data.remove("ollama");
        String model = LoqsBanner.resolveModel(empty);
        assertEquals("unknown", model);
    }
}
