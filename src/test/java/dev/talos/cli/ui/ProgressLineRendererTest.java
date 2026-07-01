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
}
