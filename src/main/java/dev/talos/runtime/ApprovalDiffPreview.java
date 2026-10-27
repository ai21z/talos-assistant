package dev.talos.runtime;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import dev.talos.core.util.TextReplacement;
import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.runtime.policy.ProtectedPathPolicy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the capped, redacted unified-diff block shown inside the approval
 * window for write/edit mutations (T756).
 *
 * <p>CLI-neutral by design: output is plain ASCII ({@code +}/{@code -}/
 * {@code @@} prefixes); coloring is renderer-owned
 * ({@code dev.talos.cli.ui.ApprovalPromptRenderer}).
 *
 * <p>Fail-closed: any condition that would require showing content the
 * approval window must not leak (protected paths), or that the builder
 * cannot afford (oversized/binary/unreadable files), yields a skipped
 * preview with a machine-readable reason - never an exception into the
 * approval flow, and never a partial diff.
 */
public final class ApprovalDiffPreview {

    /** Maximum diff body lines shown; the remainder collapses to a marker. */
    public static final int MAX_DIFF_LINES = 60;
    /**
     * Maximum diff body characters shown. Lines are kept whole so a reviewer
     * reads what will be written, and the approval renderer wraps them. The
     * budget keeps a few very long lines from flooding the box.
     */
    public static final int MAX_DIFF_CHARS = 6_000;
    /** The least part of a long line worth showing before the budget stops the body. */
    public static final int MIN_CUT_CHARS = 200;
    /** Matches FileEditTool's file-size cap. */
    private static final long MAX_FILE_SIZE = 2 * 1024 * 1024L;
    private static final int CONTEXT_LINES = 3;

    private ApprovalDiffPreview() {}

    /**
     * A diff preview. {@code text} holds newline-joined, redacted, bounded
     * diff body lines without indentation (empty when skipped, when nothing
     * changed, or when a new file is empty); {@code note} is a short annotation
     * such as "new file" or "no changes"; a non-empty {@code skippedReason}
     * means no diff block should be rendered at all.
     */
    public record Preview(
            String text,
            int added,
            int removed,
            int diffLineCount,
            boolean truncated,
            String skippedReason,
            String note
    ) {
        public boolean skipped() {
            return !skippedReason.isEmpty();
        }

        static Preview skippedBecause(String reason) {
            return new Preview("", 0, 0, 0, false, reason, "");
        }
    }

    /** Diff for a complete-file write: existing content vs new content. */
    public static Preview forWrite(Path workspace, String relPath, String newContent) {
        try {
            Path target = guardedTarget(workspace, relPath);
            if (target == null) return guardSkip(workspace, relPath);
            if (!Files.exists(target)) {
                // A new file has no hunk, every line is an addition and all of it is reviewable.
                if (newContent != null && newContent.length() > MAX_FILE_SIZE) throw new SkipException("file-too-large");
                List<String> body = new ArrayList<>();
                for (String line : splitLines(newContent)) body.add("+" + line);
                Bounded bounded = bound(body);
                return new Preview(bounded.text(), body.size(), 0, body.size(), bounded.truncated(), "", "new file");
            }
            String existing = readDiffableContent(target);
            String proposed = newContent == null ? "" : newContent;
            Preview preview = render(existing, proposed);
            // T1012: identical visible lines with different bytes means the
            // change is format metadata (BOM, line endings, terminal
            // newline). "no changes" would be untrue for such a write.
            if ("no changes".equals(preview.note()) && !existing.equals(proposed)) {
                return new Preview("", 0, 0, 0, false, "",
                        "no visible line changes; format metadata differs (see Format changes)");
            }
            return preview;
        } catch (SkipException e) {
            return Preview.skippedBecause(e.reason);
        } catch (Exception e) {
            return Preview.skippedBecause("diff-error");
        }
    }

    /**
     * Diff for a checkpoint restore (T794): CURRENT file content vs the
     * captured blob that a restore would write back. All write/edit guards
     * apply - protected paths (including {@code .talos} since T788), binary
     * and oversized content fail closed to a skipped preview.
     */
    public static Preview forRestore(Path workspace, String relPath, byte[] blobBytes) {
        try {
            Path target = guardedTarget(workspace, relPath);
            if (target == null) return guardSkip(workspace, relPath);
            if (blobBytes == null) return Preview.skippedBecause("missing-blob");
            if (blobBytes.length > MAX_FILE_SIZE) throw new SkipException("file-too-large");
            for (byte b : blobBytes) {
                if (b == 0) throw new SkipException("binary-content");
            }
            String restored = new String(blobBytes, StandardCharsets.UTF_8);
            if (!Files.exists(target)) {
                Preview diff = render("", restored);
                return new Preview(diff.text(), diff.added(), diff.removed(),
                        diff.diffLineCount(), diff.truncated(), "", "recreates missing file");
            }
            return render(readDiffableContent(target), restored);
        } catch (SkipException e) {
            return Preview.skippedBecause(e.reason);
        } catch (Exception e) {
            return Preview.skippedBecause("diff-error");
        }
    }

    /** Diff for a targeted edit: file content with old_string spliced to new_string. */
    public static Preview forEdit(Path workspace, String relPath, String oldString, String newString) {
        try {
            Path target = guardedTarget(workspace, relPath);
            if (target == null) return guardSkip(workspace, relPath);
            if (!Files.exists(target)) return Preview.skippedBecause("missing-file");
            if (oldString == null || oldString.isEmpty()) {
                return Preview.skippedBecause("missing-old-string");
            }
            String existing = readDiffableContent(target);
            TextReplacement.Match match = TextReplacement.findUniqueMatch(existing, oldString, newString);
            if (match.count() == 0) return Preview.skippedBecause("old-string-not-found");
            if (match.count() > 1) {
                return Preview.skippedBecause("ambiguous-old-string");
            }
            String updated = existing.substring(0, match.startRaw())
                    + match.replacement()
                    + existing.substring(match.endRaw());
            return render(existing, updated);
        } catch (SkipException e) {
            return Preview.skippedBecause(e.reason);
        } catch (Exception e) {
            return Preview.skippedBecause("diff-error");
        }
    }

    private static Path guardedTarget(Path workspace, String relPath) {
        if (workspace == null || relPath == null || relPath.isBlank()) return null;
        if (ProtectedPathPolicy.classify(workspace, relPath).protectedPath()
                || ProtectedContentPolicy.looksProtectedPathString(relPath)) {
            return null;
        }
        Path root = workspace.normalize();
        Path target;
        try {
            target = root.resolve(relPath.strip()).normalize();
        } catch (RuntimeException e) {
            return null;
        }
        if (!target.startsWith(root)) return null;
        return target;
    }

    private static Preview guardSkip(Path workspace, String relPath) {
        if (workspace == null || relPath == null || relPath.isBlank()) {
            return Preview.skippedBecause("no-target-path");
        }
        if (ProtectedPathPolicy.classify(workspace, relPath).protectedPath()
                || ProtectedContentPolicy.looksProtectedPathString(relPath)) {
            return Preview.skippedBecause("protected-path");
        }
        return Preview.skippedBecause("outside-workspace");
    }

    private static String readDiffableContent(Path target) throws Exception {
        if (Files.size(target) > MAX_FILE_SIZE) throw new SkipException("file-too-large");
        byte[] bytes = Files.readAllBytes(target);
        for (byte b : bytes) {
            if (b == 0) throw new SkipException("binary-content");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Preview render(String oldContent, String newContent) {
        List<String> oldLines = splitLines(oldContent);
        List<String> newLines = splitLines(newContent);
        Patch<String> patch = DiffUtils.diff(oldLines, newLines);
        if (patch.getDeltas().isEmpty()) {
            return new Preview("", 0, 0, 0, false, "", "no changes");
        }

        List<String> unified = UnifiedDiffUtils.generateUnifiedDiff("a", "b", oldLines, patch, CONTEXT_LINES);
        // Drop the ---/+++ file headers: the approval window already names the target.
        List<String> body = unified.size() > 2 ? unified.subList(2, unified.size()) : List.of();

        int added = 0;
        int removed = 0;
        for (String line : body) {
            if (line.startsWith("+")) added++;
            else if (line.startsWith("-")) removed++;
        }

        Bounded bounded = bound(body);
        return new Preview(bounded.text(), added, removed, body.size(), bounded.truncated(), "", "");
    }

    private record Bounded(String text, boolean truncated) {}

    /** Whole redacted lines up to the line and character budgets, then a marker naming the rest. */
    private static Bounded bound(List<String> body) {
        List<String> rendered = new ArrayList<>();
        int characters = 0;
        int shown = 0;
        int cutAt = -1;
        for (String raw : body) {
            if (shown >= MAX_DIFF_LINES) break;
            String line = ProtectedContentPolicy.sanitizeText(raw == null ? "" : raw);
            int remaining = MAX_DIFF_CHARS - characters;
            if (line.length() > remaining) {
                // A line that does not fit is shown as far as the budget allows, when that is worth reading.
                if (remaining < MIN_CUT_CHARS) break;
                int end = remaining;
                if (Character.isLowSurrogate(line.charAt(end))) end--;
                rendered.add(line.substring(0, end));
                cutAt = end;
                shown++;
                break;
            }
            rendered.add(line);
            characters += line.length() + 1;
            shown++;
        }
        boolean truncated = cutAt >= 0 || shown < body.size();
        int more = body.size() - shown;
        if (cutAt >= 0) {
            rendered.add("... (line cut at " + cutAt + " characters" + (more > 0 ? ", " + more + " more diff lines" : "") + ")");
        } else if (truncated) {
            rendered.add("... (" + more + " more diff lines)");
        }
        return new Bounded(String.join("\n", rendered), truncated);
    }

    private static List<String> splitLines(String content) {
        String value = content == null ? "" : content;
        String[] parts = value.split("\n", -1);
        List<String> lines = new ArrayList<>(parts.length);
        for (String part : parts) {
            lines.add(part.endsWith("\r") ? part.substring(0, part.length() - 1) : part);
        }
        // A trailing newline produces one empty trailing element; drop it so
        // "abc\n" diffs as the single line it is.
        if (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        return lines;
    }

    private static final class SkipException extends Exception {
        final String reason;

        SkipException(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }
    }
}
