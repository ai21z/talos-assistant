package dev.talos.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** T756: capped, redacted unified-diff preview for the approval window. */
class ApprovalDiffPreviewTest {

    @TempDir
    Path workspace;

    // ── write diffs ─────────────────────────────────────────────────

    @Test
    void writeDiffShowsRemovedAndAddedLinesWithContext() throws Exception {
        Files.writeString(workspace.resolve("app.css"),
                "a { x: 1; }\nb { x: 2; }\nc { x: 3; }\n");

        var p = ApprovalDiffPreview.forWrite(workspace, "app.css",
                "a { x: 1; }\nb { x: 9; }\nc { x: 3; }\n");

        assertFalse(p.skipped());
        assertEquals(1, p.added());
        assertEquals(1, p.removed());
        assertTrue(p.text().contains("-b { x: 2; }"), p.text());
        assertTrue(p.text().contains("+b { x: 9; }"), p.text());
        assertTrue(p.text().contains(" a { x: 1; }"), "context line expected: " + p.text());
        assertTrue(p.text().startsWith("@@"), p.text());
    }

    @Test
    void writeDiffIsDeterministic() throws Exception {
        Files.writeString(workspace.resolve("f.txt"), "one\ntwo\n");

        var p1 = ApprovalDiffPreview.forWrite(workspace, "f.txt", "one\nTWO\n");
        var p2 = ApprovalDiffPreview.forWrite(workspace, "f.txt", "one\nTWO\n");

        assertEquals(p1, p2);
    }

    @Test
    void newFileShowsItsWholeContentAsAddedLines() {
        var p = ApprovalDiffPreview.forWrite(workspace, "fresh.txt", "l1\nl2\nl3\n");

        assertFalse(p.skipped());
        assertEquals("new file", p.note());
        assertEquals(3, p.added());
        assertEquals(0, p.removed());
        assertEquals("+l1\n+l2\n+l3", p.text());
        assertFalse(p.text().contains("@@"), p.text());
    }

    @Test
    void newFileBodyIsCappedLikeAnyDiff() {
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= ApprovalDiffPreview.MAX_DIFF_LINES + 7; i++) content.append("line ").append(i).append('\n');

        var p = ApprovalDiffPreview.forWrite(workspace, "long.txt", content.toString());

        String[] lines = p.text().split("\n", -1);
        assertEquals(ApprovalDiffPreview.MAX_DIFF_LINES + 1, lines.length);
        assertTrue(p.truncated());
        assertEquals("... (7 more diff lines)", lines[lines.length - 1]);
    }

    @Test
    void identicalWriteYieldsNoChangesNote() throws Exception {
        Files.writeString(workspace.resolve("same.txt"), "stable\n");

        var p = ApprovalDiffPreview.forWrite(workspace, "same.txt", "stable\n");

        assertFalse(p.skipped());
        assertEquals("no changes", p.note());
        assertEquals(0, p.added());
        assertEquals(0, p.removed());
    }

    @Test
    void crlfFileDiffsCleanlyAgainstLfContent() throws Exception {
        Files.writeString(workspace.resolve("dos.txt"), "one\r\ntwo\r\n");

        var p = ApprovalDiffPreview.forWrite(workspace, "dos.txt", "one\nTWO\n");

        assertFalse(p.skipped());
        assertEquals(1, p.added());
        assertEquals(1, p.removed());
        assertFalse(p.text().contains("\r"), "no carriage returns in rendered diff");
        assertFalse(p.text().contains("-one"), "unchanged line must not appear removed: " + p.text());
    }

    // ── edit diffs ──────────────────────────────────────────────────

    @Test
    void editDiffSplicesUniqueOldString() throws Exception {
        Files.writeString(workspace.resolve("hello.java"),
                "class A {\n  void m() {\n    greet();\n  }\n}\n");

        var p = ApprovalDiffPreview.forEdit(workspace, "hello.java", "greet();", "wave();");

        assertFalse(p.skipped());
        assertEquals(1, p.added());
        assertEquals(1, p.removed());
        assertTrue(p.text().contains("-    greet();"), p.text());
        assertTrue(p.text().contains("+    wave();"), p.text());
        assertTrue(p.text().contains(" class A {"), "context expected: " + p.text());
    }

    @Test
    void editDiffUsesNormalizedCrLfMatchLikeApplyPath() throws Exception {
        Files.writeString(workspace.resolve("windows.txt"), "alpha\r\nbeta\r\ngamma\r\n");

        var p = ApprovalDiffPreview.forEdit(workspace, "windows.txt", "alpha\nbeta", "alpha\nBETA");

        assertFalse(p.skipped(), p.skippedReason());
        assertEquals(1, p.added());
        assertEquals(1, p.removed());
        assertTrue(p.text().contains("-beta"), p.text());
        assertTrue(p.text().contains("+BETA"), p.text());
    }

    @Test
    void editSkipsWhenOldStringNotFoundOrAmbiguous() throws Exception {
        Files.writeString(workspace.resolve("dup.txt"), "x\nx\n");

        assertEquals("old-string-not-found",
                ApprovalDiffPreview.forEdit(workspace, "dup.txt", "missing", "y").skippedReason());
        assertEquals("ambiguous-old-string",
                ApprovalDiffPreview.forEdit(workspace, "dup.txt", "x", "y").skippedReason());
        assertEquals("missing-file",
                ApprovalDiffPreview.forEdit(workspace, "ghost.txt", "x", "y").skippedReason());
        assertEquals("missing-old-string",
                ApprovalDiffPreview.forEdit(workspace, "dup.txt", "", "y").skippedReason());
    }

    @Test
    void editSkipsMixedRawAndNormalizedEquivalentMatchesAsAmbiguous() throws Exception {
        Files.writeString(
                workspace.resolve("mixed.txt"),
                "alpha\nbeta\n---\nalpha\r\nbeta\r\n");

        var preview = ApprovalDiffPreview.forEdit(
                workspace,
                "mixed.txt",
                "alpha\nbeta",
                "alpha\nBETA");

        assertTrue(preview.skipped());
        assertEquals("ambiguous-old-string", preview.skippedReason());
        assertEquals("", preview.text());
    }

    // ── caps ────────────────────────────────────────────────────────

    @Test
    void diffBodyIsCappedWithRemainderMarker() throws Exception {
        StringBuilder oldContent = new StringBuilder();
        StringBuilder newContent = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            oldContent.append("line ").append(i).append('\n');
            newContent.append("LINE ").append(i).append('\n');
        }
        Files.writeString(workspace.resolve("big.txt"), oldContent.toString());

        var p = ApprovalDiffPreview.forWrite(workspace, "big.txt", newContent.toString());

        assertTrue(p.truncated());
        assertTrue(p.diffLineCount() > ApprovalDiffPreview.MAX_DIFF_LINES);
        String[] lines = p.text().split("\n", -1);
        assertEquals(ApprovalDiffPreview.MAX_DIFF_LINES + 1, lines.length);
        assertTrue(lines[lines.length - 1].startsWith("... ("), lines[lines.length - 1]);
        assertTrue(lines[lines.length - 1].endsWith("more diff lines)"), lines[lines.length - 1]);
    }

    @Test
    void longLinesAreKeptWholeForTheRendererToWrap() throws Exception {
        Files.writeString(workspace.resolve("wide.txt"), "short\n");
        String longLine = "word ".repeat(60).strip();

        var p = ApprovalDiffPreview.forWrite(workspace, "wide.txt", "short\n" + longLine + "\n");

        assertTrue(p.text().contains("+" + longLine), p.text());
        assertFalse(p.text().contains("..."), p.text());
    }

    @Test
    void aSingleOversizedLineIsCutAtTheBudgetAndTheRestIsNamed() {
        String line = "z".repeat(ApprovalDiffPreview.MAX_DIFF_CHARS + 1_000);
        String content = line + "\n" + "postinstall\n".repeat(30);

        var p = ApprovalDiffPreview.forWrite(workspace, "bundle.min.js", content);

        assertFalse(p.skipped());
        assertTrue(p.truncated());
        assertTrue(p.text().length() <= ApprovalDiffPreview.MAX_DIFF_CHARS + 80, String.valueOf(p.text().length()));
        assertTrue(p.text().endsWith("... (line cut at " + ApprovalDiffPreview.MAX_DIFF_CHARS
                + " characters, 30 more diff lines)"), p.text());
    }

    @Test
    void anExistingLongLineFileStillShowsThePartOfTheChangeThatFits() throws Exception {
        String old = "x".repeat(10_000);
        Files.writeString(workspace.resolve("app.min.js"), old + "\n");

        var p = ApprovalDiffPreview.forWrite(workspace, "app.min.js", old + "y\n");

        assertTrue(p.text().startsWith("@@"), p.text());
        assertTrue(p.text().contains("\n-xxxx"), p.text());
        assertTrue(p.truncated());
        assertTrue(p.text().contains("... (line cut at"), p.text());
        assertTrue(p.text().length() <= ApprovalDiffPreview.MAX_DIFF_CHARS + 80, String.valueOf(p.text().length()));
    }

    @Test
    void aBudgetCutNeverSplitsASurrogatePair() {
        String line = "w".repeat(ApprovalDiffPreview.MAX_DIFF_CHARS - 1) + "😀" + "tail";

        var p = ApprovalDiffPreview.forWrite(workspace, "emoji.txt", line + "\n");

        String shown = p.text().split("\n", -1)[0];
        assertFalse(Character.isHighSurrogate(shown.charAt(shown.length() - 1)), shown.substring(shown.length() - 4));
        assertTrue(p.text().contains("... (line cut at"), p.text());
    }

    @Test
    void anOversizedNewFileIsSkippedLikeAnOversizedTarget() {
        String content = "a".repeat(2 * 1024 * 1024 + 1);

        var p = ApprovalDiffPreview.forWrite(workspace, "huge.txt", content);

        assertTrue(p.skipped());
        assertEquals("file-too-large", p.skippedReason());
    }

    @Test
    void diffBodyIsBoundedByACharacterBudget() throws Exception {
        Files.writeString(workspace.resolve("dense.txt"), "short\n");
        String wide = "y".repeat(1000);
        StringBuilder content = new StringBuilder("short\n");
        for (int i = 0; i < 12; i++) content.append(wide).append(i).append('\n');

        var p = ApprovalDiffPreview.forWrite(workspace, "dense.txt", content.toString());

        String[] lines = p.text().split("\n", -1);
        assertTrue(p.text().length() <= ApprovalDiffPreview.MAX_DIFF_CHARS + 80, String.valueOf(p.text().length()));
        assertTrue(p.truncated());
        assertTrue(lines[lines.length - 1].matches("\\.\\.\\. \\((line cut at \\d+ characters, )?\\d+ more diff lines\\)"),
                lines[lines.length - 1]);
        assertTrue(lines.length < 14, String.valueOf(lines.length));
    }

    // ── redaction and fail-closed skips ─────────────────────────────

    @Test
    void secretLikeAssignmentsAreRedactedInDiffLines() throws Exception {
        Files.writeString(workspace.resolve("conf.txt"), "plain=1\n");
        String token = fakeDashToken();

        var p = ApprovalDiffPreview.forWrite(workspace, "conf.txt",
                "plain=1\nAPI_TOKEN=" + token + "\n");

        assertTrue(p.text().contains("[redacted]"), p.text());
        assertFalse(p.text().contains(token), p.text());
    }

    @Test
    void protectedPathIsSkipped() throws Exception {
        Files.writeString(workspace.resolve(".env"), "TALOS_FAKE_SECRET=x\n");

        var p = ApprovalDiffPreview.forWrite(workspace, ".env", "TALOS_FAKE_SECRET=y\n");

        assertTrue(p.skipped());
        assertEquals("protected-path", p.skippedReason());
        assertEquals("", p.text());
    }

    @Test
    void outsideWorkspacePathIsSkipped() {
        var p = ApprovalDiffPreview.forWrite(workspace, "../escape.txt", "x\n");

        assertTrue(p.skipped());
        assertEquals("outside-workspace", p.skippedReason());
    }

    @Test
    void binaryContentIsSkipped() throws Exception {
        Files.write(workspace.resolve("bin.dat"), new byte[] {65, 0, 66});

        var p = ApprovalDiffPreview.forWrite(workspace, "bin.dat", "text\n");

        assertEquals("binary-content", p.skippedReason());
    }

    @Test
    void oversizedFileIsSkipped() throws Exception {
        byte[] big = new byte[2 * 1024 * 1024 + 1];
        java.util.Arrays.fill(big, (byte) 'a');
        Files.write(workspace.resolve("huge.txt"), big);

        var p = ApprovalDiffPreview.forWrite(workspace, "huge.txt", "small\n");

        assertEquals("file-too-large", p.skippedReason());
    }

    @Test
    void blankPathIsSkipped() {
        assertEquals("no-target-path",
                ApprovalDiffPreview.forWrite(workspace, "  ", "x\n").skippedReason());
        assertEquals("no-target-path",
                ApprovalDiffPreview.forWrite(null, "f.txt", "x\n").skippedReason());
    }

    private static String fakeDashToken() {
        return "sk" + "-live" + "-12345";
    }
}
