package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolError;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Guards final answers after unsupported binary-document reads. */
public final class UnsupportedDocumentAnswerGuard {
    private UnsupportedDocumentAnswerGuard() {}

    public static String overrideUnsupportedDocumentClaimsIfNeeded(
            String answer,
            ToolCallLoop.LoopResult loopResult
    ) {
        if (loopResult == null || loopResult.toolOutcomes() == null) return answer;
        List<String> unsupportedPaths = unsupportedDocumentReadPaths(loopResult);
        String current = answer == null ? "" : answer;
        String searchNote = unsupportedSearchNoteIfNeeded(current, loopResult);
        if (!searchNote.isBlank() && !current.toLowerCase(Locale.ROOT).contains("unsupported")) {
            current = searchNote + "\n\n" + current.strip();
        }
        if (unsupportedPaths.isEmpty()) return current;

        String cleaned = removeUnsupportedDocumentContentClaims(
                current,
                unsupportedPaths,
                successfulReadPaths(loopResult)).strip();
        String note = unsupportedDocumentCapabilityNote(unsupportedPaths);
        if (cleaned.isBlank()) {
            cleaned = "Talos inspected the supported text files it could read, but it did not inspect the "
                    + "unsupported binary document contents.";
        }
        if (cleaned.startsWith(note)) return cleaned;
        return note + "\n\n" + cleaned;
    }

    private static String unsupportedSearchNoteIfNeeded(String answer, ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null || loopResult.messages() == null) return "";
        String current = answer == null ? "" : answer.strip().toLowerCase(Locale.ROOT);
        if (!current.contains("no matches")) return "";
        for (ChatMessage message : loopResult.messages()) {
            if (message == null || message.content() == null) continue;
            String content = message.content();
            String lower = content.toLowerCase(Locale.ROOT);
            if (!lower.contains("[tool_result: talos.grep]")) continue;
            if (!lower.contains("skipped unsupported") && !lower.contains("search was limited")) continue;
            return "Search was limited to searchable text files. Unsupported/binary files were skipped, "
                    + "so Talos cannot truthfully claim there were no matches in those skipped files.";
        }
        return "";
    }

    private static List<String> unsupportedDocumentReadPaths(ToolCallLoop.LoopResult loopResult) {
        List<String> paths = new ArrayList<>();
        for (ToolCallLoop.ToolOutcome outcome : loopResult.toolOutcomes()) {
            if (outcome == null) continue;
            if (!"talos.read_file".equals(outcome.canonicalToolName())) continue;
            if (outcome.success()) continue;
            if (!ToolError.UNSUPPORTED_FORMAT.equals(outcome.errorCode())) continue;
            String path = outcome.pathHint();
            if (path == null || path.isBlank()) continue;
            if (!paths.contains(path)) paths.add(path);
        }
        return List.copyOf(paths);
    }

    private static String unsupportedDocumentCapabilityNote(List<String> unsupportedPaths) {
        return "[Document capability note: Talos could not inspect unsupported binary document contents with "
                + "the current local text-tool surface: "
                + String.join(", ", unsupportedPaths)
                + ". It cannot confirm whether those files are empty or what they contain.]";
    }

    private static String removeUnsupportedDocumentContentClaims(
            String answer,
            List<String> unsupportedPaths,
            List<String> successfulReadPaths
    ) {
        if (answer == null || answer.isBlank()) return "";
        StringBuilder kept = new StringBuilder();
        String[] lines = answer.split("\\R", -1);
        for (String line : lines) {
            if (isUnsupportedDocumentContentClaim(line, unsupportedPaths, successfulReadPaths)) {
                StringBuilder sentenceKept = new StringBuilder();
                for (String sentence : line.split("(?<=[.!?])\\s+")) {
                    if (isUnsupportedDocumentContentClaim(sentence, unsupportedPaths, successfulReadPaths)) continue;
                    if (!sentence.isBlank()) {
                        if (!sentenceKept.isEmpty()) sentenceKept.append(' ');
                        sentenceKept.append(sentence.strip());
                    }
                }
                if (!sentenceKept.isEmpty()) {
                    kept.append(sentenceKept).append('\n');
                }
                continue;
            }
            kept.append(line).append('\n');
        }
        return kept.toString();
    }

    private static boolean isUnsupportedDocumentContentClaim(
            String line,
            List<String> unsupportedPaths,
            List<String> successfulReadPaths
    ) {
        if (line == null || line.isBlank()) return false;
        String lower = line.toLowerCase(Locale.ROOT);
        boolean mentionsSuccessfulRead = mentionsSuccessfulReadPath(lower, successfulReadPaths);
        boolean mentionsGenericUnsupported = lower.contains("these files")
                || lower.contains("binary files")
                || lower.contains("document files");
        boolean mentionsUnsupportedExact = false;
        boolean mentionsUnsupportedStem = false;
        boolean mentionsUnsupportedFamily = false;
        for (String path : unsupportedPaths) {
            if (path == null || path.isBlank()) continue;
            String lowerPath = path.toLowerCase(Locale.ROOT);
            String filename = filenameOf(path);
            if (lower.contains(lowerPath) || (!filename.isBlank() && lower.contains(filename))) {
                mentionsUnsupportedExact = true;
            }
            String stem = filenameStemOf(path);
            if (!stem.isBlank() && lower.contains(stem)) {
                mentionsUnsupportedStem = true;
            }
            String extension = extensionOf(path);
            if (!extension.isBlank() && lower.contains("." + extension)) {
                mentionsUnsupportedExact = true;
            }
            if (mentionsUnsupportedFamilyTerm(lower, extension)) {
                mentionsUnsupportedFamily = true;
            }
        }
        boolean mentionsUnsupported = mentionsGenericUnsupported
                || mentionsUnsupportedExact
                || mentionsUnsupportedStem
                || mentionsUnsupportedFamily;
        if (!mentionsUnsupported) return false;
        boolean claimsContent = lower.contains("no extractable text")
                || lower.contains("no readable text")
                || lower.contains("do not contain any")
                || lower.contains("does not contain any")
                || lower.contains("are empty")
                || lower.contains("is empty")
                || lower.contains("no content")
                || lower.contains("nothing to extract")
                || lower.contains("says")
                || lower.contains("shows")
                || lower.contains("showed")
                || lower.contains("states")
                || lower.contains("contains")
                || lower.contains("includes")
                || lower.contains("describes")
                || lower.contains("compared")
                || lower.contains("compare")
                || lower.contains("summar");
        if (!claimsContent) return false;
        return !mentionsSuccessfulRead
                || mentionsUnsupportedExact
                || mentionsGenericUnsupported
                || mentionsUnsupportedFamily;
    }

    private static boolean mentionsUnsupportedFamilyTerm(String lowerLine, String extension) {
        if (lowerLine == null || lowerLine.isBlank() || extension == null || extension.isBlank()) return false;
        return switch (extension) {
            case "xls", "xlsx" -> lowerLine.contains("spreadsheet")
                    || lowerLine.contains("workbook")
                    || lowerLine.contains("excel");
            case "doc", "docx" -> lowerLine.contains("word document")
                    || lowerLine.contains("document");
            case "ppt", "pptx" -> lowerLine.contains("powerpoint")
                    || lowerLine.contains("presentation")
                    || lowerLine.contains("deck");
            case "png", "jpg", "jpeg", "gif", "bmp", "webp", "tif", "tiff" -> lowerLine.contains("image")
                    || lowerLine.contains("scan")
                    || lowerLine.contains("picture");
            case "zip", "tar", "gz", "tgz", "7z", "rar" -> lowerLine.contains("archive")
                    || lowerLine.contains("zip")
                    || lowerLine.contains("compressed");
            case "pdf" -> lowerLine.contains("pdf") || lowerLine.contains("document");
            default -> lowerLine.contains("binary file") || lowerLine.contains("unsupported file");
        };
    }

    private static List<String> successfulReadPaths(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null || loopResult.toolOutcomes() == null) return List.of();
        List<String> paths = new ArrayList<>();
        for (ToolCallLoop.ToolOutcome outcome : loopResult.toolOutcomes()) {
            if (outcome == null) continue;
            if (!"talos.read_file".equals(outcome.canonicalToolName())) continue;
            if (!outcome.success()) continue;
            String path = outcome.pathHint();
            if (path == null || path.isBlank()) continue;
            if (!paths.contains(path)) paths.add(path);
        }
        return List.copyOf(paths);
    }

    private static boolean mentionsSuccessfulReadPath(String lowerLine, List<String> successfulReadPaths) {
        if (lowerLine == null || lowerLine.isBlank()
                || successfulReadPaths == null
                || successfulReadPaths.isEmpty()) return false;
        for (String path : successfulReadPaths) {
            if (path == null || path.isBlank()) continue;
            String lowerPath = path.toLowerCase(Locale.ROOT);
            if (lowerLine.contains(lowerPath)) return true;
            String filename = filenameOf(path);
            if (!filename.isBlank() && lowerLine.contains(filename)) return true;
        }
        return false;
    }

    private static String filenameOf(String path) {
        if (path == null || path.isBlank()) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return (slash >= 0 ? path.substring(slash + 1) : path).toLowerCase(Locale.ROOT);
    }

    private static String filenameStemOf(String path) {
        String name = filenameOf(path);
        if (name.isBlank()) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String extensionOf(String path) {
        if (path == null || path.isBlank()) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
