package dev.talos.runtime;

import dev.talos.core.util.TextReplacement;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Effect-based risk for file changes (MICA operation preservation). A write or edit that leaves an
 * existing file with content empty or whitespace-only erases that file's content, which is a
 * destructive effect whatever tool produced it. The proposed content is computed the same way the
 * approval diff computes it, so the classified effect and the previewed effect cannot disagree.
 */
public final class EmptyingChangeEffect {

    /** Larger files are not read; an existing file of this size is treated as having content. */
    private static final long MAX_READ_BYTES = 2L * 1024 * 1024;

    private EmptyingChangeEffect() {
    }

    /** The write would leave an existing file with content empty or whitespace-only. */
    public static boolean emptiesByWrite(Path workspace, String relPath, String content) {
        if (content == null || !blank(content)) return false;
        return existingHasContent(target(workspace, relPath));
    }

    /** The edit would leave an existing file with content empty or whitespace-only. */
    public static boolean emptiesByEdit(Path workspace, String relPath, String oldString, String newString) {
        if (oldString == null || oldString.isEmpty() || newString == null || !blank(newString)) return false;
        Path target = target(workspace, relPath);
        String existing = readText(target);
        if (existing == null || blank(existing)) return false;
        TextReplacement.Match match = TextReplacement.findUniqueMatch(existing, oldString, newString);
        if (match.count() != 1) return false;
        String updated = existing.substring(0, match.startRaw()) + match.replacement() + existing.substring(match.endRaw());
        return blank(updated);
    }

    /**
     * Blank as a reader sees it: whitespace, a byte-order mark, and the zero-width and non-breaking
     * spaces carry no visible content. A file holding only those characters has been erased.
     */
    static boolean blank(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) continue;
            if (c == '\uFEFF' || c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\u2060') continue;
            return false;
        }
        return true;
    }

    private static Path target(Path workspace, String relPath) {
        if (workspace == null || relPath == null || relPath.isBlank()) return null;
        try {
            Path root = workspace.toAbsolutePath().normalize();
            Path target = root.resolve(relPath.strip()).normalize();
            return target.startsWith(root) ? target : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean existingHasContent(Path target) {
        if (target == null || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return false;
        long size;
        try {
            size = Files.size(target);
        } catch (Exception e) {
            return false;
        }
        if (size == 0) return false;
        if (size > MAX_READ_BYTES) return true;
        try {
            byte[] bytes = Files.readAllBytes(target);
            for (byte b : bytes) {
                if (b == 0) return true;
            }
            return !blank(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            // A non-empty file that cannot be read is treated as having content: risk is only raised.
            return true;
        }
    }

    private static String readText(Path target) {
        if (target == null || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            if (Files.size(target) > MAX_READ_BYTES) return null;
            byte[] bytes = Files.readAllBytes(target);
            for (byte b : bytes) {
                if (b == 0) return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
