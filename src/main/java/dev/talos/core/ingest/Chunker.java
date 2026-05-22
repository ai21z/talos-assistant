package dev.talos.core.ingest;

import dev.talos.core.util.Hash;
import dev.talos.spi.types.ChunkMetadata;
import dev.talos.spi.types.SourceIdentity;
import dev.talos.spi.types.SourceType;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Markdown/code-aware chunker with overlap; records fileHash, chunkId, and structured metadata. */
public class Chunker {

    private static final Pattern MD_HEAD    = Pattern.compile("^#{1,6}\\s+.*$", Pattern.MULTILINE);
    private static final Pattern CODE_FENCE = Pattern.compile("(?ms)```.*?```");

    public static List<ParsedChunk> chunk(String relPath, String content, int chunkChars, int overlap) {
        List<ParsedChunk> out = new ArrayList<>();
        if (content == null || content.isBlank()) return out;

        if (chunkChars <= 0) chunkChars = 800;
        if (overlap < 0) overlap = 0;
        if (overlap >= chunkChars) overlap = Math.max(0, chunkChars - 1);

        String fileHash = Hash.sha1Hex(content);
        String language = inferLanguage(relPath);
        SourceIdentity sourceId = SourceClassifier.classify(relPath);

        // Pre-compute line-start offsets (index i → char offset where line i+1 begins)
        int[] lineOffsets = buildLineOffsets(content);

        // Split into blocks that respect structural boundaries
        List<String> blocks = splitBlocks(content, sourceId);

        int cid = 0;
        String lastHeading = null; // most recent Markdown heading seen
        StringBuilder buf = new StringBuilder();
        int bufStartChar = 0;     // charPos at the start of the current buffer

        for (String b : blocks) {
            // If adding this block exceeds budget, emit current buffer (with overlap)
            // BEFORE updating heading context — the buffered content was accumulated
            // under the previous heading, not the heading from block b.
            if (buf.length() > 0 && buf.length() + b.length() > chunkChars) {
                emit(relPath, fileHash, cid++, buf.toString(), language, lastHeading,
                        bufStartChar, bufStartChar + buf.length(), lineOffsets, sourceId, out);
                // keep overlap chars at end of buffer
                int keep = Math.min(overlap, buf.length());
                int consumed = buf.length() - keep;
                bufStartChar += consumed;
                String tail = buf.substring(buf.length() - keep);
                buf.setLength(0);
                buf.append(tail);
            }

            // Update heading context from the new block — takes effect for
            // subsequent emits (including the while-loop below and future iterations).
            Matcher hm = MD_HEAD.matcher(b);
            if (hm.find()) {
                lastHeading = hm.group().trim();
            }

            buf.append(b);
            // If buffer is now big, emit again
            while (buf.length() >= chunkChars) {
                emit(relPath, fileHash, cid++, buf.substring(0, chunkChars), language, lastHeading,
                        bufStartChar, bufStartChar + chunkChars, lineOffsets, sourceId, out);
                int keep = Math.min(overlap, chunkChars);
                String tail = buf.substring(chunkChars - keep, Math.min(buf.length(), chunkChars));
                int consumed = chunkChars - keep;
                bufStartChar += consumed;
                buf.delete(0, chunkChars - keep);
                // ensure progress
                if (buf.length() == 0) break;
            }
        }
        if (!buf.isEmpty()) {
            emit(relPath, fileHash, cid, buf.toString(), language, lastHeading,
                    bufStartChar, bufStartChar + buf.length(), lineOffsets, sourceId, out);
        }

        return out;
    }

    private static void emit(String relPath, String fileHash, int chunkId, String text,
                             String language, String headingContext,
                             int startChar, int endChar, int[] lineOffsets,
                             SourceIdentity sourceId,
                             List<ParsedChunk> out) {
        String id = relPath + "#" + chunkId;
        String slice = text.trim();
        if (slice.isBlank()) return;

        int lineStart = charOffsetToLine(startChar, lineOffsets);
        int lineEnd   = charOffsetToLine(Math.max(startChar, endChar - 1), lineOffsets);

        var meta = new ChunkMetadata(language, lineStart, lineEnd, headingContext, sourceId);
        out.add(new ParsedChunk(id, relPath, slice, fileHash, chunkId, meta));
    }

    // ───── line-offset helpers ─────

    /** Builds an array where index i is the character offset where line (i+1) starts. Index 0 = 0. */
    static int[] buildLineOffsets(String content) {
        List<Integer> offsets = new ArrayList<>();
        offsets.add(0);
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                offsets.add(i + 1);
            }
        }
        return offsets.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Returns the 1-based line number for a given character offset using binary search. */
    static int charOffsetToLine(int charOffset, int[] lineOffsets) {
        if (lineOffsets.length == 0 || charOffset < 0) return 1;
        int lo = 0, hi = lineOffsets.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lineOffsets[mid] <= charOffset) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return lo; // 1-based because offsets[0] = line 1
    }

    // ───── language inference ─────

    /** Infers language from file extension. Returns lowercase extension or null. */
    static String inferLanguage(String relPath) {
        if (relPath == null) return null;
        int dot = relPath.lastIndexOf('.');
        if (dot < 0 || dot == relPath.length() - 1) return null;
        // Ignore chunk suffixes like "file.java#0"
        String afterDot = relPath.substring(dot + 1);
        int hash = afterDot.indexOf('#');
        if (hash >= 0) afterDot = afterDot.substring(0, hash);
        return afterDot.isEmpty() ? null : afterDot.toLowerCase();
    }

    // ───── block splitting ─────

    /**
     * Splits content into structural blocks.
     * <ul>
     *   <li>{@code CODE_FILE} → delegates to {@link CodeBlockSplitter} for
     *       language-aware structural boundaries (brace-depth, indent-level).</li>
     *   <li>{@code DOCUMENT} and others → existing markdown-fence + heading logic.</li>
     * </ul>
     */
    private static List<String> splitBlocks(String s, SourceIdentity sourceId) {
        if (sourceId != null && sourceId.type() == SourceType.CODE_FILE) {
            return CodeBlockSplitter.split(s, sourceId.format());
        }
        return splitMarkdownBlocks(s);
    }

    /** Original markdown-aware block splitting: respects code fences and headings. */
    private static List<String> splitMarkdownBlocks(String s) {
        var blocks = new ArrayList<String>();
        var m = CODE_FENCE.matcher(s);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) blocks.add(s.substring(last, m.start()));
            blocks.add(s.substring(m.start(), m.end())); // keep code blocks intact
            last = m.end();
        }
        if (last < s.length()) blocks.add(s.substring(last));

        // Further split prose on markdown headings
        var refined = new ArrayList<String>();
        for (String part : blocks) {
            if (part.startsWith("```")) { refined.add(part); continue; }
            var head = MD_HEAD.split(part);
            if (head.length <= 1) { refined.add(part); }
            else {
                int idx = 0; var hm = MD_HEAD.matcher(part);
                while (hm.find()) {
                    if (hm.start() > idx) refined.add(part.substring(idx, hm.start()));
                    refined.add(part.substring(hm.start(), hm.end()));
                    idx = hm.end();
                }
                if (idx < part.length()) refined.add(part.substring(idx));
            }
        }
        return refined;
    }
}
