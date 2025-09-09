package dev.loqj.core.ingest;

import dev.loqj.core.util.Hash;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Markdown/code-aware chunker with overlap; records fileHash + chunkId. */
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

        // Split into blocks that try to respect code fences and headings
        List<String> blocks = splitBlocks(content);

        int cid = 0;
        StringBuilder buf = new StringBuilder();
        for (String b : blocks) {
            // If adding this block exceeds budget, emit current buffer (with overlap)
            if (buf.length() > 0 && buf.length() + b.length() > chunkChars) {
                emit(relPath, fileHash, cid++, buf.toString(), out);
                // keep overlap chars at end of buffer
                int keep = Math.min(overlap, buf.length());
                String tail = buf.substring(buf.length() - keep);
                buf.setLength(0);
                buf.append(tail);
            }
            buf.append(b);
            // If buffer is now big, emit again
            while (buf.length() >= chunkChars) {
                emit(relPath, fileHash, cid++, buf.substring(0, chunkChars), out);
                int keep = Math.min(overlap, chunkChars);
                String tail = buf.substring(chunkChars - keep, Math.min(buf.length(), chunkChars) );
                buf.delete(0, chunkChars - keep);
                // ensure progress
                if (buf.length() == 0) break;
            }
        }
        if (buf.length() > 0) emit(relPath, fileHash, cid++, buf.toString(), out);

        return out;
    }

    private static void emit(String relPath, String fileHash, int chunkId, String text, List<ParsedChunk> out) {
        String id = relPath + "#" + chunkId;
        String slice = text.trim();
        if (!slice.isBlank()) out.add(new ParsedChunk(id, relPath, slice, fileHash, chunkId));
    }

    private static List<String> splitBlocks(String s) {
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
