package dev.talos.cli.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProgressLineRendererTest {
    private static final TerminalCapabilities UNICODE =
            new TerminalCapabilities(ColorPolicy.NEVER, true, false, true, false);
    private static final TerminalCapabilities ASCII =
            new TerminalCapabilities(ColorPolicy.NEVER, true, false, false, true);

    @Test
    void rendersQuietSemanticProgressLines() {
        ProgressLineRenderer renderer = new ProgressLineRenderer(CliTheme.forCapabilities(UNICODE));

        assertEquals("  • route edit · workspace bounded", renderer.route("edit", "workspace bounded"));
        assertEquals("  → read src/App.java", renderer.tool("talos.read_file", "executing", "src/App.java"));
        assertEquals("  ✓ read_file done", renderer.tool("talos.read_file", "completed", null));
        assertEquals("  ! verification warning no focused test", renderer.tool("talos.write_file", "warning", "no focused test"));
        assertEquals("  x run_command failed command rejected", renderer.tool("talos.run_command", "error", "command rejected"));
    }

    @Test
    void asciiFallbackDoesNotEmitQuestionMarks() {
        ProgressLineRenderer renderer = new ProgressLineRenderer(CliTheme.forCapabilities(ASCII));

        String rendered = String.join("\n",
                renderer.route("edit", "workspace bounded"),
                renderer.tool("talos.read_file", "executing", "src/App.java"),
                renderer.tool("talos.read_file", "completed", null));

        assertFalse(rendered.contains("?"));
        assertTrue(rendered.codePoints().allMatch(cp -> cp == '\n' || (cp >= 0x20 && cp <= 0x7E)),
                "ASCII progress output must be terminal-safe: " + rendered);
    }

    @Test
    void blockedAndUnverifiedOutcomesNeverUseASuccessGlyph() {
        for (var capabilities : java.util.List.of(UNICODE, ASCII)) {
            var renderer = new ProgressLineRenderer(CliTheme.forCapabilities(capabilities));
            for (var outcome : ProgressLineRenderer.TurnOutcome.values()) {
                String footer = renderer.turnStats(7, 12836, 215, outcome);
                assertTrue(footer.contains(outcome.name()), footer);
                assertEquals(outcome == ProgressLineRenderer.TurnOutcome.COMPLETED_VERIFIED,
                        footer.startsWith(capabilities.unicodeSafe() ? "  ✓ " : "  ok "), footer);
            }
        }
    }

    @Test
    void terminalFailureAndBlockStatusOverrideAnInconsistentCompletionLabel() {
        assertEquals(ProgressLineRenderer.TurnOutcome.FAILED,
                ProgressLineRenderer.TurnOutcome.from("ERROR", "COMPLETED_VERIFIED"));
        assertEquals(ProgressLineRenderer.TurnOutcome.CANCELLED,
                ProgressLineRenderer.TurnOutcome.from("ABORTED", "COMPLETED_VERIFIED"));
        assertEquals(ProgressLineRenderer.TurnOutcome.BLOCKED_BY_POLICY,
                ProgressLineRenderer.TurnOutcome.from("BLOCKED", "COMPLETED_VERIFIED"));
        assertEquals(ProgressLineRenderer.TurnOutcome.BLOCKED_BY_APPROVAL,
                ProgressLineRenderer.TurnOutcome.from("BLOCKED", "BLOCKED_BY_APPROVAL"));
        assertEquals(ProgressLineRenderer.TurnOutcome.UNKNOWN,
                ProgressLineRenderer.TurnOutcome.from("", "arbitrary provider prose"));
    }
}
