package dev.talos.runtime;

import dev.talos.runtime.expectation.LiteralContentExpectation;
import dev.talos.runtime.expectation.TaskExpectationResolver;
import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.runtime.policy.ProtectedPathPolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.tools.ToolCall;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Single owner of existing-file text-format detection and byte-faithful
 * write preservation (T1012).
 *
 * <p>The read surface is line-numbered and LF-normalized, so a model that
 * derives a whole-file {@code write_file} from a read naturally proposes
 * LF content without the original terminal-newline or BOM state. This
 * policy re-applies the existing file's format to the proposed content
 * BEFORE pre-approval validation, the approval preview, checkpoint
 * capture, and execution, so the exact bytes approved are the exact bytes
 * written.
 *
 * <p>Deliberately untransformed (pass-through, with the remaining change
 * rendered in the approval window instead):
 * <ul>
 *   <li>mixed line endings - a dominant style is never chosen silently;</li>
 *   <li>malformed UTF-8 or binary existing content;</li>
 *   <li>an explicit user request for line-ending/encoding normalization;</li>
 *   <li>exact-literal write contracts - exactness wins over format.</li>
 * </ul>
 */
public final class WriteFormatPolicy {

    /**
     * Matches the diffable-file cap in {@code ApprovalDiffPreview} and
     * {@code FileEditTool} (2 MiB). Existing files above it skip
     * preservation and disclosure entirely, like the diff preview does.
     */
    private static final long MAX_EXISTING_FILE_BYTES = 2 * 1024 * 1024L;
    private static final int MAX_CHANGE_LINES = 4;
    private static final char BOM = '\uFEFF';

    private static final List<String> PATH_KEYS =
            List.of("path", "file_path", "filepath", "file", "filename");
    private static final List<String> CONTENT_KEYS =
            List.of("content", "text", "body", "data", "file_content");

    /**
     * An explicit format directive in the user's own words. Kept narrow so
     * a casual mention ("update the encoding section heading") never
     * disables preservation: a strongly format-directive verb
     * (normalize/convert) near a format noun, a generic change verb whose
     * DIRECT OBJECT is a format token, or the participle form.
     */
    private static final Pattern EXPLICIT_FORMAT_DIRECTIVE = Pattern.compile(
            "(?is)\\b(?:normali[sz]e|convert)\\b[^.!?]{0,40}?"
                    + "\\b(?:line[- ]?endings?|line[- ]?terminators?|eol|crlf|lf|cr|"
                    + "byte[- ]?order[- ]?marks?|boms?|encoding)\\b"
                    + "|\\b(?:change|switch|set|strip|remove|drop|add|keep|use)\\b"
                    + "\\s+(?:(?:the|all|any|every)\\s+)*"
                    + "(?:line[- ]?endings?|line[- ]?terminators?|eol|crlf|"
                    + "byte[- ]?order[- ]?marks?|boms?)\\b"
                    + "|\\b(?:line[- ]?endings?|line[- ]?terminators?|eol|crlf|"
                    + "byte[- ]?order[- ]?marks?|boms?)\\b[^.!?]{0,30}?"
                    + "\\b(?:normali[sz]ed|converted|stripped|removed|changed)\\b");

    private WriteFormatPolicy() {}

    /** Uniform text line-ending style of an existing file. */
    public enum Eol { LF, CRLF, CR, MIXED, NONE }

    /** Detected text format of an existing file's bytes. */
    public record TextFormat(
            boolean exists,
            boolean binary,
            boolean validUtf8,
            boolean bom,
            Eol eol,
            boolean terminalNewline,
            int crlfCount,
            int lfCount,
            int crCount,
            boolean textHasContent
    ) {
        public boolean supportedUniformText() {
            return exists && !binary && validUtf8 && eol != Eol.MIXED;
        }
    }

    /** Result of the deterministic preservation transform. */
    public record Preservation(ToolCall call, boolean changed, String description) {
        static Preservation unchanged(ToolCall call) {
            return new Preservation(call, false, "");
        }
    }

    /** Detect the text format of raw file bytes. */
    public static TextFormat detect(byte[] bytes) {
        if (bytes == null) {
            return new TextFormat(false, false, false, false, Eol.NONE, false, 0, 0, 0, false);
        }
        boolean bom = bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF;
        for (byte b : bytes) {
            if (b == 0) {
                return new TextFormat(true, true, false, bom, Eol.NONE, false, 0, 0, 0, false);
            }
        }
        boolean validUtf8 = isValidUtf8(bytes);
        if (!validUtf8) {
            return new TextFormat(true, false, false, bom, Eol.NONE, false, 0, 0, 0, false);
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (bom && !text.isEmpty() && text.charAt(0) == BOM) {
            text = text.substring(1);
        }
        int crlf = 0;
        int lf = 0;
        int cr = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    crlf++;
                    i++;
                } else {
                    cr++;
                }
            } else if (c == '\n') {
                lf++;
            }
        }
        Eol eol;
        int styles = (crlf > 0 ? 1 : 0) + (lf > 0 ? 1 : 0) + (cr > 0 ? 1 : 0);
        if (styles == 0) {
            eol = Eol.NONE;
        } else if (styles > 1) {
            eol = Eol.MIXED;
        } else if (crlf > 0) {
            eol = Eol.CRLF;
        } else if (lf > 0) {
            eol = Eol.LF;
        } else {
            eol = Eol.CR;
        }
        boolean terminal = !text.isEmpty()
                && (text.charAt(text.length() - 1) == '\n' || text.charAt(text.length() - 1) == '\r');
        return new TextFormat(true, false, true, bom, eol, terminal, crlf, lf, cr, !text.isEmpty());
    }

    /**
     * Rewrites the {@code write_file} content parameter so an existing
     * supported text file keeps its BOM, uniform line-ending style, and
     * terminal-newline state. Idempotent and deterministic: the transform
     * is a pure function of the existing file bytes and the proposed
     * content, so running it again after another normalization stage
     * converges on the same call.
     */
    public static Preservation preserveWriteCall(
            Path workspace,
            ToolCall call,
            TaskContract contract,
            String userRequest
    ) {
        if (workspace == null || call == null
                || !"talos.write_file".equals(call.canonicalToolName())) {
            return Preservation.unchanged(call);
        }
        String relPath = firstParam(call, PATH_KEYS);
        String contentKey = firstPresentKey(call, CONTENT_KEYS);
        if (relPath == null || relPath.isBlank() || contentKey == null) {
            return Preservation.unchanged(call);
        }
        String proposed = call.parameters().get(contentKey);
        if (proposed == null || proposed.isEmpty()) {
            return Preservation.unchanged(call);
        }
        if (normalizationExplicitlyRequested(userRequest)
                || hasExactLiteralContractForPath(contract, relPath)) {
            return Preservation.unchanged(call);
        }
        byte[] existing = existingFileBytes(workspace, relPath);
        TextFormat format = detect(existing);
        if (!format.supportedUniformText()) {
            return Preservation.unchanged(call);
        }
        String preserved = preserveContent(format, proposed);
        if (preserved.equals(proposed)) {
            return Preservation.unchanged(call);
        }
        Map<String, String> params = new LinkedHashMap<>(call.parameters());
        params.put(contentKey, preserved);
        return new Preservation(
                new ToolCall(call.toolName(), params),
                true,
                "write content re-joined to the existing file format for " + relPath
                        + " (" + describeFormat(format) + ")");
    }

    /**
     * Describes format changes that remain between the existing file and
     * the final (post-preservation) content, for the approval window.
     * Empty for new files, protected paths, and format-identical writes.
     */
    public static List<String> remainingFormatChanges(
            Path workspace,
            String relPath,
            String finalContent
    ) {
        if (workspace == null || relPath == null || relPath.isBlank() || finalContent == null) {
            return List.of();
        }
        if (ProtectedPathPolicy.classify(workspace, relPath).protectedPath()
                || ProtectedContentPolicy.looksProtectedPathString(relPath)) {
            return List.of();
        }
        byte[] existing = existingFileBytes(workspace, relPath);
        TextFormat existingFormat = detect(existing);
        if (!existingFormat.exists()) {
            return List.of();
        }
        List<String> changes = new ArrayList<>();
        if (existingFormat.binary()) {
            changes.add("existing file appears binary; this write replaces it with text");
            return List.copyOf(changes);
        }
        if (!existingFormat.validUtf8()) {
            changes.add("existing file is not valid UTF-8; this write replaces it with UTF-8 text");
            return List.copyOf(changes);
        }
        TextFormat proposedFormat = detect(finalContent.getBytes(StandardCharsets.UTF_8));
        if (existingFormat.bom() != proposedFormat.bom()) {
            changes.add(proposedFormat.bom() ? "UTF-8 BOM: added" : "UTF-8 BOM: removed");
        }
        if (existingFormat.eol() != proposedFormat.eol()
                && !(existingFormat.eol() == Eol.NONE || proposedFormat.eol() == Eol.NONE)) {
            changes.add("line endings: " + describeEol(existingFormat) + " -> " + describeEol(proposedFormat));
        } else if (existingFormat.eol() == Eol.MIXED && proposedFormat.eol() == Eol.MIXED
                && (existingFormat.crlfCount() != proposedFormat.crlfCount()
                        || existingFormat.lfCount() != proposedFormat.lfCount()
                        || existingFormat.crCount() != proposedFormat.crCount())) {
            changes.add("line endings: " + describeEol(existingFormat)
                    + " -> " + describeEol(proposedFormat) + " (redistributed)");
        }
        boolean existingEmpty = existing != null && existing.length == (existingFormat.bom() ? 3 : 0);
        if (!existingEmpty && !finalContent.isEmpty()
                && existingFormat.terminalNewline() != proposedFormat.terminalNewline()) {
            changes.add("terminal newline: " + (proposedFormat.terminalNewline() ? "added" : "removed"));
        }
        if (changes.size() > MAX_CHANGE_LINES) {
            changes = new ArrayList<>(changes.subList(0, MAX_CHANGE_LINES));
        }
        return List.copyOf(changes);
    }

    /** True when the user's own request asks for a line-ending/encoding change. */
    public static boolean normalizationExplicitlyRequested(String userRequest) {
        return userRequest != null && EXPLICIT_FORMAT_DIRECTIVE.matcher(userRequest).find();
    }

    // ── internals ────────────────────────────────────────────────────────

    private static String preserveContent(TextFormat format, String proposed) {
        String body = proposed;
        if (!body.isEmpty() && body.charAt(0) == BOM) {
            body = body.substring(1);
        }
        // A file with no line terminators expressed no line-ending
        // preference, so the proposal's own endings pass through; an empty
        // or BOM-only file expressed no terminal-newline preference either
        // (only the BOM itself is format evidence). Never fabricate a
        // style from absent evidence.
        if (format.eol() == Eol.NONE) {
            if (!format.textHasContent()) {
                return format.bom() ? BOM + body : body;
            }
            // Single-line-no-newline evidence: keep the proposal's own
            // endings, preserve only the absent terminal newline.
            String kept = body;
            if (endsWithNewline(kept)) {
                kept = kept.substring(0, kept.length() - (kept.endsWith("\r\n") ? 2 : 1));
            }
            return format.bom() ? BOM + kept : kept;
        }
        String eol = switch (format.eol()) {
            case CRLF -> "\r\n";
            case CR -> "\r";
            default -> "\n";
        };
        String joined = String.join(eol, splitLogicalLines(body));
        // Terminal-newline state follows the existing file; a proposed
        // terminal newline on a no-terminal file is dropped, a missing one
        // on a terminated file is restored. A proposal made only of
        // newlines keeps one terminal ending instead of collapsing to an
        // empty write; empty content stays empty.
        if (format.terminalNewline() && (!joined.isEmpty() || !body.isEmpty())) {
            joined = joined + eol;
        }
        return format.bom() ? BOM + joined : joined;
    }

    private static boolean endsWithNewline(String text) {
        if (text.isEmpty()) return false;
        char last = text.charAt(text.length() - 1);
        return last == '\n' || last == '\r';
    }

    private static List<String> splitLogicalLines(String text) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                lines.add(current.toString());
                current.setLength(0);
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
            } else if (c == '\n') {
                lines.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    private static boolean hasExactLiteralContractForPath(TaskContract contract, String relPath) {
        if (contract == null) return false;
        String target = normalizePath(relPath);
        for (var expectation : TaskExpectationResolver.resolve(contract)) {
            if (expectation instanceof LiteralContentExpectation literal
                    && normalizePath(literal.targetPath()).equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] existingFileBytes(Path workspace, String relPath) {
        try {
            Path root = workspace.normalize();
            Path target = root.resolve(relPath.strip()).normalize();
            if (!target.startsWith(root)) return null;
            if (!Files.isRegularFile(target)) return null;
            if (Files.size(target) > MAX_EXISTING_FILE_BYTES) return null;
            return Files.readAllBytes(target);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isValidUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static String describeFormat(TextFormat format) {
        StringBuilder sb = new StringBuilder();
        sb.append(describeEol(format));
        sb.append(format.terminalNewline() ? ", terminal newline" : ", no terminal newline");
        if (format.bom()) sb.append(", BOM");
        return sb.toString();
    }

    private static String describeEol(TextFormat format) {
        return switch (format.eol()) {
            case LF -> "LF";
            case CRLF -> "CRLF";
            case CR -> "CR";
            case NONE -> "none";
            case MIXED -> "mixed (CRLF x" + format.crlfCount()
                    + ", LF x" + format.lfCount()
                    + (format.crCount() > 0 ? ", CR x" + format.crCount() : "") + ")";
        };
    }

    private static String firstParam(ToolCall call, List<String> keys) {
        for (String key : keys) {
            String value = call.param(key);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String firstPresentKey(ToolCall call, List<String> keys) {
        if (call.parameters() == null) return null;
        for (String key : keys) {
            if (call.parameters().containsKey(key)) return key;
        }
        return null;
    }

    private static String normalizePath(String path) {
        String normalized = path == null ? "" : path.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }
}
