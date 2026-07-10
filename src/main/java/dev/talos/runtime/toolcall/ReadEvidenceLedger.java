package dev.talos.runtime.toolcall;

import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolCall;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ordered, typed read-evidence store for one tool-call turn (T1013).
 *
 * <p>Replaces the flattened {@code tool:key=value;} signature maps whose
 * unescaped substring identity let hedged alias keys and legal filename
 * characters bind one file's content to another path, and whose HashMap
 * storage made "latest" hash-order dependent.
 *
 * <p>Ordering: entries carry a monotonically increasing sequence; every
 * "latest" query is deterministic recency. Recording an identical call
 * (same canonical tool, equal raw parameters) replaces the older entry,
 * preserving the previous map-overwrite semantics.
 *
 * <p>Reusability: a successful mutation (and the qualifying mutating-tool
 * failures) invalidates prior entries as reusable mutation evidence via a
 * watermark instead of deleting them, because path-keyed consumers
 * (redaction writeback, read-display containment, the end-of-turn export)
 * intentionally see the whole turn.
 */
public final class ReadEvidenceLedger {

    private final List<ReadEvidence> entries = new ArrayList<>();
    private long nextSequence = 1;
    private long reusableWatermark = 1;

    /** Records one successful read-only tool result. Sole writer: ReadEvidenceStateAccounting. */
    ReadEvidence record(ToolCall call, String pathHint, String body) {
        if (call == null) return null;
        String canonicalTool = ToolAliasPolicy.localCanonicalName(call.toolName());
        Map<String, String> rawParams = call.parameters() == null
                ? Map.of()
                : new LinkedHashMap<>(call.parameters());
        Map<String, String> identity = identityParams(rawParams);
        entries.removeIf(e -> e.canonicalTool().equals(canonicalTool)
                && identityParams(e.rawParams()).equals(identity));
        String safeBody = body == null ? "" : body;
        ReadEvidence evidence = new ReadEvidence(
                nextSequence++,
                call.toolName(),
                canonicalTool,
                rawParams,
                pathHint,
                pathHint == null ? "" : ToolCallSupport.canonicalizeReadPath(pathHint),
                "read_file".equals(canonicalTool),
                call.param("offset") != null,
                looksTruncated(safeBody),
                safeBody,
                sha256(safeBody),
                RedactedReadWritebackGuard.containsRedactionSentinel(safeBody));
        entries.add(evidence);
        return evidence;
    }

    /** Prior entries stop being reusable as mutation evidence (mutation/failure accounting). */
    void invalidateReusableEvidence() {
        reusableWatermark = nextSequence;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Newest-first read_file entries for a canonical path. */
    public List<ReadEvidence> readFileEntriesForPath(String path, boolean reusableOnly) {
        String target = ToolCallSupport.canonicalizeReadPath(path == null ? "" : path);
        if (target.isBlank()) return List.of();
        List<ReadEvidence> out = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            ReadEvidence e = entries.get(i);
            if (!e.readFile()) continue;
            if (reusableOnly && e.sequence() < reusableWatermark) continue;
            if (!e.canonicalPathKey().equalsIgnoreCase(target)) continue;
            out.add(e);
        }
        return List.copyOf(out);
    }

    /** Latest read_file entry for a path (any range), or null. */
    public ReadEvidence latestReadFileForPath(String path, boolean reusableOnly) {
        List<ReadEvidence> matches = readFileEntriesForPath(path, reusableOnly);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    /**
     * Latest complete (unranged) read_file entry for a path, or null. A
     * later fresh complete read supersedes an older one deterministically;
     * a later ranged read never displaces complete evidence. Consumers
     * that require exact source agreement keep their disk-agreement gate
     * as the fail-closed arbiter on top of this recency rule.
     */
    public ReadEvidence latestCompleteReadFileForPath(String path, boolean reusableOnly) {
        for (ReadEvidence e : readFileEntriesForPath(path, reusableOnly)) {
            if (!e.ranged()) return e;
        }
        return null;
    }

    /**
     * Latest entry recorded for an identical call, or null. Identity is
     * the canonical tool plus the parameter map with path-canonicalized
     * values, so spelling variants of one call (list_dir "." vs "./")
     * stay one redundancy identity exactly as before, while the stored
     * rawParams keep the model's exact call for trace/debug.
     */
    public ReadEvidence latestIdenticalCall(ToolCall call, boolean reusableOnly) {
        if (call == null) return null;
        String canonicalTool = ToolAliasPolicy.localCanonicalName(call.toolName());
        Map<String, String> identity = identityParams(
                call.parameters() == null ? Map.of() : call.parameters());
        for (int i = entries.size() - 1; i >= 0; i--) {
            ReadEvidence e = entries.get(i);
            if (reusableOnly && e.sequence() < reusableWatermark) continue;
            if (e.canonicalTool().equals(canonicalTool)
                    && identityParams(e.rawParams()).equals(identity)) {
                return e;
            }
        }
        return null;
    }

    private static Map<String, String> identityParams(Map<String, String> params) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            out.put(entry.getKey(), ToolCallSupport.canonicalizeReadPath(entry.getValue()));
        }
        return out;
    }

    /**
     * Latest read_file body per normalized effective path (whole turn) -
     * the end-of-turn export previously served by readFileBodiesThisTurn.
     */
    public Map<String, String> latestReadFileBodiesByNormalizedPath() {
        Map<String, String> out = new LinkedHashMap<>();
        for (ReadEvidence e : entries) {
            if (!e.readFile() || e.effectivePath().isBlank()) continue;
            out.put(ToolCallSupport.normalizePath(e.effectivePath()), e.body());
        }
        return Map.copyOf(out);
    }

    private static boolean looksTruncated(String body) {
        return body.contains("... (") || body.contains("output truncated") || body.startsWith("(file has");
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }
}
