package dev.talos.cli.ui;

import dev.talos.core.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CliStatusDashboardTest {

    @TempDir
    Path workspace;

    @Test
    void render_includes_required_dashboard_rows() {
        String output = CliStatusDashboard.render(CliStatusDashboard.snapshot(
                workspace,
                new Config(),
                "auto",
                "qwen2.5-coder:14b",
                "off",
                "/status --verbose for diagnostics"));

        assertTrue(output.contains("Talos v"));
        assertTrue(output.contains("Workspace"));
        assertTrue(output.contains("Mode"));
        assertTrue(output.contains("Model"));
        assertTrue(output.contains("Index"));
        assertTrue(output.contains("Policy"));
        assertTrue(output.contains("Debug"));
        assertTrue(output.contains("Next"));
    }

    @Test
    void snapshot_reports_missing_index_without_stack_details() {
        String output = CliStatusDashboard.render(CliStatusDashboard.snapshot(
                workspace,
                new Config(),
                "auto",
                "model",
                "off",
                "next"));

        assertTrue(output.contains("not indexed"));
    }

    @Test
    void snapshot_summarizes_local_policy() {
        Config cfg = new Config();
        cfg.data.put("net", java.util.Map.of("enabled", false));

        String output = CliStatusDashboard.render(CliStatusDashboard.snapshot(
                workspace,
                cfg,
                "auto",
                "model",
                "off",
                "next"));

        assertTrue(output.contains("network off"));
        assertTrue(output.contains("local Ollama only"));
    }
}
