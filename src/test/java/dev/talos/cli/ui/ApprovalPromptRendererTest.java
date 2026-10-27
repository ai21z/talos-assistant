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
    void diffLinesAtTheWrapThresholdSurviveIntact() {
        // 70 characters plus the 4-space indent is 74, the wrap threshold at
        // width 80, so such a diff line must pass through wrap() verbatim.
        ApprovalPromptRenderer renderer = new ApprovalPromptRenderer(CliTheme.forCapabilities(UNICODE), 80);
        String longDiffLine = "+" + "x".repeat(69);
        String detail = "target: f.txt\n    diff (+1 -0):\n    " + longDiffLine;

        String rendered = renderer.render("write file", detail, "write");

        assertTrue(rendered.contains("    " + longDiffLine), rendered);
    }

    @Test
    void longDiffLinesAreWrappedWholeRatherThanCut() {
        // Diff lines are no longer cut by the builder, so a prose line is wrapped and every word stays visible.
        ApprovalPromptRenderer renderer = new ApprovalPromptRenderer(CliTheme.forCapabilities(UNICODE), 80);
        String prose = "+On the edge of the world, where the sea meets the sky in a never-ending dance, there stood"
                + " a lonely lighthouse on a small island, and its keeper Nera kept the lamp.";
        String detail = "target: story.txt\n    diff (+1 -0): new file\n    " + prose;

        String rendered = renderer.render("write file", detail, "write");

        assertFalse(rendered.contains("..."), rendered);
        assertEquals(prose, rejoinDiffLine(rendered, "    +On the edge"), rendered);
    }

    @Test
    void wrappedDiffLinesKeepIndentationAndMarkContinuation() {
        ApprovalPromptRenderer renderer = new ApprovalPromptRenderer(CliTheme.forCapabilities(UNICODE), 80);
        String code = "+        if user.is_admin and not dry_run: shutil.rmtree(target_dir)  # remove everything under the target directory";
        String detail = "target: job.py\n    diff (+1 -0): new file\n    " + code;

        String rendered = renderer.render("write file", detail, "write");

        // Every character survives, the diff indent is kept and continuation rows hang two spaces deeper.
        assertEquals(code, rejoinDiffLine(rendered, "    +        if user"), rendered);
        assertTrue(rendered.lines().anyMatch(l -> l.contains("│ " + ApprovalPromptRenderer.CONTINUATION)
                && l.contains("directory")), rendered);
    }

    @Test
    void slicingNeverSplitsASurrogatePairAndContinuationIsMarked() {
        ApprovalPromptRenderer renderer = new ApprovalPromptRenderer(CliTheme.forCapabilities(UNICODE), 80);
        String line = "+" + "b".repeat(68) + "😀" + " tail -rf /tmp/build";
        String detail = "target: notes.md\n    diff (+1 -0): new file\n    " + line;

        String rendered = renderer.render("write file", detail, "write");

        assertEquals(line, rejoinDiffLine(rendered, "    +bbb"), rendered);
        for (String row : rendered.lines().toList()) {
            for (int i = 0; i < row.length(); i++) {
                char c = row.charAt(i);
                if (Character.isHighSurrogate(c)) assertTrue(i + 1 < row.length() && Character.isLowSurrogate(row.charAt(i + 1)), row);
                else if (Character.isLowSurrogate(c)) assertTrue(i > 0 && Character.isHighSurrogate(row.charAt(i - 1)), row);
            }
        }
        assertTrue(rendered.lines().anyMatch(l -> l.contains(ApprovalPromptRenderer.CONTINUATION)
                && l.contains("-rf /tmp/build")), rendered);
    }

    /** Joins the diff row that starts with the given text with its hanging continuation rows. */
    private static String rejoinDiffLine(String rendered, String firstRowStart) {
        java.util.List<String> rows = rendered.lines().filter(l -> l.contains("│ "))
                .map(l -> l.substring(l.indexOf("│ ") + 2)).toList();
        StringBuilder joined = new StringBuilder();
        boolean inside = false;
        for (String row : rows) {
            if (row.startsWith(firstRowStart)) {
                inside = true;
                joined.append(row.substring(4));
            } else if (inside && row.startsWith(ApprovalPromptRenderer.CONTINUATION)) {
                joined.append(row.substring(ApprovalPromptRenderer.CONTINUATION.length()));
            } else if (inside) {
                break;
            }
        }
        return joined.toString();
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
