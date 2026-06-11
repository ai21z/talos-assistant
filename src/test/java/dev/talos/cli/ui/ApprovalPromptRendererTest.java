package dev.talos.cli.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalPromptRendererTest {
    private static final TerminalCapabilities UNICODE =
            new TerminalCapabilities(ColorPolicy.NEVER, true, false, true, false);
    private static final TerminalCapabilities ASCII =
            new TerminalCapabilities(ColorPolicy.NEVER, true, false, false, true);

    @Test
    void rendersApprovalAsTrustWindow() {
        ApprovalPromptRenderer renderer = new ApprovalPromptRenderer(CliTheme.forCapabilities(UNICODE), 72);

        String rendered = renderer.render("write file", "target: docs/summary.md", "write");

        assertTrue(rendered.contains("┌─ approval required"));
        assertTrue(rendered.contains("│ Action  write file"));
        assertTrue(rendered.contains("│ Risk    write"));
        assertTrue(rendered.contains("│ target: docs/summary.md"));
        assertTrue(rendered.contains("│ y = approve once · a = approve for session · Enter = deny"));
        assertTrue(rendered.contains("└"));
    }

    @Test
    void rendersPerTurnApprovalWithoutSessionRememberChoice() {
        ApprovalPromptRenderer renderer = new ApprovalPromptRenderer(CliTheme.forCapabilities(UNICODE), 72);

        String rendered = renderer.renderOnce("private document model handoff",
                "target: report.docx", "sensitive read");

        assertTrue(rendered.contains("│ y = approve this turn · Enter = deny"));
        assertFalse(rendered.contains("approve for session"), rendered);
    }

    @Test
    void asciiApprovalFallbackNeverEmitsQuestionMarks() {
        ApprovalPromptRenderer renderer = new ApprovalPromptRenderer(CliTheme.forCapabilities(ASCII), 72);

        String rendered = renderer.render("write file", "target: docs/summary.md", "write");

        assertFalse(rendered.contains("?"));
        assertTrue(rendered.codePoints().allMatch(cp -> cp == '\n' || cp == '\r' || (cp >= 0x20 && cp <= 0x7E)),
                "ASCII approval prompt must be terminal-safe: " + rendered);
    }

    // ---- T756: diff-block colorization ----

    private static final TerminalCapabilities COLOR =
            new TerminalCapabilities(ColorPolicy.ALWAYS, true, true, true, false);
    private static final String DIFF_DETAIL =
            "target: app.css (12 bytes, 2 lines)\n"
                    + "    preview:\n"
                    + "      - item one\n"
                    + "    diff (+1 -1):\n"
                    + "    @@ -1 +1 @@\n"
                    + "    -a { x: 1; }\n"
                    + "    +a { x: 2; }";

    @Test
    void diffBlockLinesAreColorizedWhenColorEnabled() {
        ApprovalPromptRenderer renderer = new ApprovalPromptRenderer(CliTheme.forCapabilities(COLOR), 80);
        CliTheme theme = CliTheme.forCapabilities(COLOR);

        String rendered = renderer.render("write file", DIFF_DETAIL, "write");

        assertTrue(rendered.contains(theme.error("    -a { x: 1; }")), rendered);
        assertTrue(rendered.contains(theme.success("    +a { x: 2; }")), rendered);
        assertTrue(rendered.contains(theme.metadata("    @@ -1 +1 @@")), rendered);
        // A "- item" line BEFORE the diff marker must never be colorized.
        assertFalse(rendered.contains(theme.error("      - item one")), rendered);
    }

    @Test
    void diffBlockOutputIsByteIdenticalPlainWhenColorDisabled() {
        ApprovalPromptRenderer colorNever =
                new ApprovalPromptRenderer(CliTheme.forCapabilities(UNICODE), 80);

        String rendered = colorNever.render("write file", DIFF_DETAIL, "write");

        assertFalse(rendered.contains("\033["), "no ANSI escapes with ColorPolicy.NEVER: " + rendered);
        assertTrue(rendered.contains("│ " + "    -a { x: 1; }"), rendered);
        assertTrue(rendered.contains("│ " + "    +a { x: 2; }"), rendered);
    }

    @Test
    void diffLinesAtBuilderCapSurviveWrappingIntact() {
        // MAX_LINE_LENGTH (70) + 4-space indent = 74 = the wrap threshold at
        // width 80; capped diff lines must pass through wrap() verbatim.
        ApprovalPromptRenderer renderer = new ApprovalPromptRenderer(CliTheme.forCapabilities(UNICODE), 80);
        String longDiffLine = "+" + "x".repeat(69);
        String detail = "target: f.txt\n    diff (+1 -0):\n    " + longDiffLine;

        String rendered = renderer.render("write file", detail, "write");

        assertTrue(rendered.contains("    " + longDiffLine), rendered);
    }

    @Test
    void longUnbrokenDetailIsWrappedInsideTrustWindow() {
        ApprovalPromptRenderer renderer = new ApprovalPromptRenderer(CliTheme.forCapabilities(ASCII), 60);

        String rendered = renderer.render("write file",
                "target: C:\\Users\\example\\Documents\\Projects\\talos\\very-long-folder-name-without-spaces\\private-output.md",
                "write");

        for (String line : rendered.split("\\R")) {
            assertTrue(line.length() <= 60,
                    "approval prompt line exceeded configured width: " + line.length() + " :: " + line);
        }
    }
}
