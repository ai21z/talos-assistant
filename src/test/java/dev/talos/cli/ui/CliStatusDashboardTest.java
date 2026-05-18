package dev.talos.cli.ui;

import dev.talos.core.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CliStatusDashboardTest {
    private static final TerminalCapabilities UNICODE_NO_COLOR =
            new TerminalCapabilities(ColorPolicy.NEVER, true, false, true, false);

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
                "/status --verbose for diagnostics"), UNICODE_NO_COLOR, 80);

        assertTrue(output.contains("TALOS"));
        assertTrue(output.contains("Workspace"));
        assertTrue(output.contains("Mode"));
        assertTrue(output.contains("Model"));
        assertTrue(output.contains("Engine"));
        assertTrue(output.contains("Index"));
        assertTrue(output.contains("Policy"));
        assertTrue(output.contains("Debug"));
    }

    @Test
    void snapshot_reports_missing_index_without_stack_details() {
        String output = CliStatusDashboard.render(CliStatusDashboard.snapshot(
                workspace,
                new Config(null),
                "auto",
                "model",
                "off",
                "next"), UNICODE_NO_COLOR, 80);

        assertTrue(output.contains("not indexed"));
    }

    @Test
    void snapshot_reports_trust_policy_not_engine_network_policy() {
        Config cfg = new Config(null);
        cfg.data.put("net", java.util.Map.of("enabled", false));

        var snapshot = CliStatusDashboard.snapshot(
                workspace,
                cfg,
                "auto",
                CliStatusDashboard.resolveModel(cfg),
                "off",
                "next");
        String output = CliStatusDashboard.render(snapshot, UNICODE_NO_COLOR, 100);

        assertTrue(snapshot.policy().contains("ask before mutation"));
        assertTrue(snapshot.model().contains("talos-agent"));
        assertTrue(output.contains("llama.cpp"));
        assertTrue(!output.contains("Ollama"));
        assertTrue(!output.contains("network off"));
        assertTrue(!output.contains("local engine only"));
    }

    @Test
    void snapshot_summarizes_explicit_ollama_policy() {
        Config cfg = new Config(null);
        cfg.data.put("llm", java.util.Map.of("default_backend", "ollama"));

        var snapshot = CliStatusDashboard.snapshot(
                workspace,
                cfg,
                "auto",
                CliStatusDashboard.resolveModel(cfg),
                "off",
                "next");
        String output = CliStatusDashboard.render(snapshot, UNICODE_NO_COLOR, 100);

        assertTrue(snapshot.policy().contains("ask before mutation"));
        assertTrue(output.contains("ollama"));
        assertTrue(!output.contains("local Ollama only"));
    }
}
