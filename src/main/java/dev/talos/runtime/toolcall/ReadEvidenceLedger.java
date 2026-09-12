package dev.talos.runtime.toolcall;

import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolCall;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private final Path workspaceRoot;
    private final List<ReadEvidence> entries = new ArrayList<>();
    private final Map<Long, BoundToolResult> toolResults = new LinkedHashMap<>();
    private long nextSequence = 1;
    private long reusableWatermark = 1;

    ReadEvidenceLedger(Path workspace) {
        this.workspaceRoot = normalizeWorkspace(workspace);
    }

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

    /** Bind at execution time, after privacy handling and final tool formatting. */
    void bindToolResult(ToolCall call, int messageIndex, dev.talos.spi.types.ChatMessage message) {
        ReadEvidence evidence = latestIdenticalCall(call, true);
        if (evidence == null || !evidence.readFile() || message == null
                || !"tool".equals(message.role()) || message.toolCallId() == null
                || message.toolCallId().isBlank() || message.content() == null) return;
        toolResults.put(evidence.sequence(), new BoundToolResult(messageIndex, message));
    }

    BoundToolResult toolResult(ReadEvidence evidence) {
        return evidence == null ? null : toolResults.get(evidence.sequence());
    }

    record BoundToolResult(int index, dev.talos.spi.types.ChatMessage message) {}

    /** Import only the opaque handoff's authenticated typed evidence into a fresh loop. */
    void importReusable(ReadEvidence evidence, BoundToolResult result) {
        ReadEvidence imported = new ReadEvidence(
                nextSequence++, evidence.rawTool(), evidence.canonicalTool(), evidence.rawParams(),
                evidence.effectivePath(), evidence.canonicalPathKey(), evidence.readFile(),
                evidence.ranged(), evidence.truncated(), evidence.body(), evidence.contentHash(),
                evidence.redacted());
        entries.add(imported);
        toolResults.put(imported.sequence(), result);
    }

    /**
     * Newest-first read_file entries for one filesystem path. Existing
     * paths use the provider's same-file identity, so Windows casing aliases
     * share evidence while case-distinct Linux/WSL files do not. Provider
     * path equality is the conservative fallback when same-file identity
     * cannot be observed.
     */
    public List<ReadEvidence> readFileEntriesForPath(String path, boolean reusableOnly) {
        String target = ToolCallSupport.canonicalizeReadPath(path == null ? "" : path);
        if (target.isBlank()) return List.of();
        List<ReadEvidence> out = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            ReadEvidence e = entries.get(i);
            if (!e.readFile()) continue;
            if (reusableOnly && e.sequence() < reusableWatermark) continue;
            if (!sameEffectivePath(e.effectivePath(), target)) continue;
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
     * the canonical tool plus the parameter map with PATH-KEYED values
     * canonicalized, so spelling variants of one call (list_dir "." vs
     * "./") stay one redundancy identity while content-valued parameters
     * (a grep pattern of "a/b" vs "a\\b") stay distinct searches. The
     * stored rawParams keep the model's exact call for trace/debug.
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

    /** Parameter keys whose values are workspace paths, not content. */
    private static final java.util.Set<String> PATH_VALUED_KEYS = java.util.Set.of(
            "path", "file_path", "filepath", "file", "filename",
            "from", "to", "source", "source_path", "destination", "destination_path",
            "dir", "directory");

    private static Map<String, String> identityParams(Map<String, String> params) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            out.put(entry.getKey(), PATH_VALUED_KEYS.contains(entry.getKey())
                    ? ToolCallSupport.canonicalizeReadPath(entry.getValue())
                    : entry.getValue());
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

    boolean sameEffectivePath(String evidencePath, String requestedPath) {
        String leftKey = ToolCallSupport.canonicalizeReadPath(evidencePath);
        String rightKey = ToolCallSupport.canonicalizeReadPath(requestedPath);
        if (leftKey.isBlank() || rightKey.isBlank()) return false;
        if (workspaceRoot == null) {
            return leftKey.equals(rightKey);
        }
        Path left = resolveInsideWorkspace(leftKey);
        Path right = resolveInsideWorkspace(rightKey);
        if (left == null || right == null) return false;
        try {
            if (Files.exists(left) && Files.exists(right)) {
                return Files.isSameFile(left, right);
            }
        } catch (Exception ignored) {
            // Fall through to provider-native path equality.
        }
        return left.equals(right);
    }

    private Path resolveInsideWorkspace(String path) {
        try {
            Path resolved = workspaceRoot.resolve(path).normalize();
            return resolved.startsWith(workspaceRoot) ? resolved : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Path normalizeWorkspace(Path workspace) {
        if (workspace == null) return null;
        try {
            return workspace.toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            return null;
        }
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
