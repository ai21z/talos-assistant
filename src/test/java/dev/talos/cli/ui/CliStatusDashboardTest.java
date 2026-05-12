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
                new Config(null),
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
                new Config(null),
                "auto",
                "model",
                "off",
                "next"));

        assertTrue(output.contains("not indexed"));
    }

    @Test
    void snapshot_summarizes_local_engine_policy() {
        Config cfg = new Config(null);
        cfg.data.put("net", java.util.Map.of("enabled", false));

        String output = CliStatusDashboard.render(CliStatusDashboard.snapshot(
                workspace,
                cfg,
                "auto",
                CliStatusDashboard.resolveModel(cfg),
                "off",
                "next"));

        assertTrue(output.contains("network off"));
        assertTrue(output.contains("local engine only"));
        assertTrue(output.contains("llama_cpp"));
        assertTrue(output.contains("talos-agent"));
        assertTrue(!output.contains("Ollama"));
    }

    @Test
    void snapshot_summarizes_explicit_ollama_policy() {
        Config cfg = new Config(null);
        cfg.data.put("llm", java.util.Map.of("default_backend", "ollama"));

        String output = CliStatusDashboard.render(CliStatusDashboard.snapshot(
                workspace,
                cfg,
                "auto",
                CliStatusDashboard.resolveModel(cfg),
                "off",
                "next"));

        assertTrue(output.contains("local Ollama only"));
    }
}
