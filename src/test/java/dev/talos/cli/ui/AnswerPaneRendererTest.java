package dev.talos.cli.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnswerPaneRendererTest {
    private static final TerminalCapabilities UNICODE =
            new TerminalCapabilities(ColorPolicy.NEVER, true, false, true, false);
    private static final TerminalCapabilities ASCII =
            new TerminalCapabilities(ColorPolicy.NEVER, true, false, false, true);

    @Test
    void rendersBlockAnswerWithStablePane() {
        AnswerPaneRenderer renderer = new AnswerPaneRenderer(CliTheme.forCapabilities(UNICODE), 48);

        String rendered = renderer.renderBlock("hello\nworld", "answer");

        assertTrue(rendered.contains("  ┌─ answer "));
        assertTrue(rendered.contains("  │ hello"));
        assertTrue(rendered.contains("  │ world"));
        assertTrue(rendered.contains("  └─ answer"));
    }

    @Test
    void streamingChunksReceiveRailEvenWhenNewlineSplitsAcrossChunks() {
        AnswerPaneRenderer renderer = new AnswerPaneRenderer(CliTheme.forCapabilities(UNICODE), 48);
        AnswerPaneRenderer.Stream stream = renderer.openStream("answer");

        String rendered = stream.accept("hel")
                + stream.accept("lo\nwor")
                + stream.accept("ld")
                + stream.close("answer");

        assertTrue(rendered.contains("  ┌─ answer "));
        assertTrue(rendered.contains("  │ hello\n"));
        assertTrue(rendered.contains("  │ world"));
        assertTrue(rendered.endsWith("  └─ answer" + System.lineSeparator()));
    }

    @Test
    void asciiFallbackNeverEmitsQuestionMarks() {
        AnswerPaneRenderer renderer = new AnswerPaneRenderer(CliTheme.forCapabilities(ASCII), 48);

        String rendered = renderer.renderBlock("hello", "answer");

        assertFalse(rendered.contains("?"));
        assertTrue(rendered.codePoints().allMatch(cp -> cp == '\n' || cp == '\r' || (cp >= 0x20 && cp <= 0x7E)),
                "ASCII answer pane must be terminal-safe: " + rendered);
    }
}
