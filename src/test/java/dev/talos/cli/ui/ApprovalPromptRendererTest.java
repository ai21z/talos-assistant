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
