package dev.talos.runtime.toolcall;

import dev.talos.runtime.policy.ProtectedPathPolicy;
import dev.talos.runtime.policy.ResourceDecision;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ToolSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * T1003: plans the bounded full-rewrite recovery for repeated empty-argument
 * talos.edit_file failures.
 *
 * <p>Weak local models encode append-shaped edits as edit_file with an empty
 * old_string. The pre-approval guard rejects those calls safely, but before
 * this planner the failure policy then ended the turn with only "copy the
 * exact old_string", which is not actionable for an append. When the failed
 * target is a small text file whose complete, unredacted content was read in
 * the same turn, the safe deterministic recovery is one bounded
 * talos.write_file with the complete corrected content: the approval card
 * shows the full diff, and verification/readback run unchanged.
 *
 * <p>Every condition fails closed. Targets are excluded when the read is
 * missing, ranged (offset), truncated, ambiguous, redacted, oversized, or
 * non-text, when the effective path is protected, escapes the workspace, or
 * cannot be classified, when the contract is read-only, and when the native
 * tool surface does not expose talos.write_file (the recovery instruction
 * and the narrowed retry surface only work on the native path).
 */
final class EditRecoveryFullRewritePlanner {

    /** Conservative cap on the reconstructed read body for a full rewrite. */
    static final int MAX_RECOVERY_BODY_CHARS = 32_768;

    private static final Set<String> SMALL_TEXT_EXTENSIONS = Set.of(
            "md", "txt", "css", "html", "htm", "js", "jsx", "ts", "tsx",
            "json", "yaml", "yml", "xml", "csv", "ini", "toml", "properties",
            "java", "py", "go", "rs", "rb", "php", "c", "cpp", "cs");

    private EditRecoveryFullRewritePlanner() {}

    /**
     * Registers eligible recovery targets. Runs after each iteration and
     * before the failure policy, so strikes spent in the same generation
     * batch as the read (the observed QA shape) can still recover.
     */
    static void plan(LoopState state) {
        if (state == null) return;
        // Re-validate previously planned, not-yet-prompted targets: read
        // evidence can be cleared between iterations (for example after a
        // successful mutation elsewhere). Refusing here restores the normal
        // truthful stop instead of prompting a rewrite without safe source.
        state.editRecoveryFullRewriteTargets.removeIf(path -> {
            boolean refuse = !state.editRecoveryPromptedPaths.contains(path)
                    && recoverySourceBody(state, path) == null;
            if (refuse) {
                LocalTurnTraceCapture.recordRepair(
                        "REFUSED",
                        "edit-recovery-full-rewrite target=" + path
                                + " reason=safe-source-no-longer-derivable");
            }
            return refuse;
        });
        if (state.emptyEditArgumentFailuresByPath.isEmpty()) return;
        for (var entry : state.emptyEditArgumentFailuresByPath.entrySet()) {
            String path = entry.getKey();
            int failures = entry.getValue() == null ? 0 : entry.getValue();
            if (failures < 2) continue;
            if (state.editRecoveryFullRewriteTargets.contains(path)) continue;
            if (state.editRecoveryPromptedPaths.contains(path)) continue;
            if (!eligible(state, path)) continue;
            state.editRecoveryFullRewriteTargets.add(path);
            LocalTurnTraceCapture.recordRepair(
                    "PLANNED",
                    "edit-recovery-full-rewrite target=" + path
                            + " reason=empty-old-string-after-complete-read");
        }
    }

    private static boolean eligible(LoopState state, String path) {
        if (path == null || path.isBlank()) return false;
        if (!writeFileOnNativeSurface(state)) return false;
        if (!wasReadThisTurn(state, path)) return false;
        if (!hasSmallTextExtension(path)) return false;
        if (recoverySourceBody(state, path) == null) return false;
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        return contract != null && contract.mutationAllowed();
    }

    /**
     * The safe recovery source for a target, or null to refuse. This is the
     * eligibility gate for the recovery: the effective path must classify
     * inside the workspace and non-protected, the same-turn read must be
     * free of redaction sentinels, the content must come from a complete
     * (unranged, untruncated, unambiguous) read under the size cap, and the
     * current disk content must still agree with that read (workspace
     * commands and external editors are not tracked by the read-cache
     * accounting). The returned exact disk bytes are consumed as a non-null
     * signal only; the model-visible content carrier is the line-numbered
     * read_file tool result the overlay references, which is LF-normalized
     * with a forced terminal newline. The write pipeline's format
     * preservation (WriteFormatPolicy, T1012) restores the target's real
     * line endings, BOM, and terminal-newline state before approval, so a
     * recovery rewrite echoed from the normalized carrier still lands
     * byte-faithfully.
     */
    static String recoverySourceBody(LoopState state, String path) {
        if (state == null || path == null || path.isBlank()) return null;
        if (!pathClassifiesSafeForRecovery(state, path)) return null;
        if (RedactedReadWritebackGuard.hasSameTurnRedactedReadForPath(state, path)) return null;
        String body = AppendLinePreApprovalGuard.completeReadBodyForPath(state, path);
        if (body == null || body.length() > MAX_RECOVERY_BODY_CHARS) return null;
        String disk = currentDiskContent(state, path);
        if (disk == null || disk.length() > MAX_RECOVERY_BODY_CHARS) return null;
        if (!normalizedForComparison(disk).equals(normalizedForComparison(body))) return null;
        return disk;
    }

    private static String currentDiskContent(LoopState state, String path) {
        try {
            Path root = state.workspace.toAbsolutePath().normalize();
            Path resolved = root.resolve(ToolCallSupport.canonicalizeReadPath(path))
                    .toAbsolutePath().normalize();
            if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) return null;
            if (Files.size(resolved) > MAX_RECOVERY_BODY_CHARS) return null;
            return Files.readString(resolved);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Comparison basis for disk-vs-read agreement: the numbered read output
     * is LF-normalized with a forced trailing newline, so those two
     * differences (and only those) are tolerated.
     */
    private static String normalizedForComparison(String value) {
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        return normalized.endsWith("\n") ? normalized : normalized + "\n";
    }

    /**
     * Protected-path classification is its own gate, independent of the
     * redaction-sentinel check: in default developer mode an approved
     * protected read can reach the model unredacted, and the later write
     * approval is the last gate, not the boundary that keeps protected
     * files out of broad full-rewrite recovery.
     */
    private static boolean pathClassifiesSafeForRecovery(LoopState state, String path) {
        ResourceDecision decision = ProtectedPathPolicy.classify(state.workspace, path);
        return decision.hasPath()
                && decision.insideWorkspace()
                && !decision.workspaceEscape()
                && !decision.protectedPath();
    }

    private static boolean wasReadThisTurn(LoopState state, String path) {
        if (state.pathsReadThisTurn.contains(path)) return true;
        String canonical = ToolCallSupport.canonicalizeReadPath(path);
        for (String read : state.pathsReadThisTurn) {
            if (ToolCallSupport.canonicalizeReadPath(read).equalsIgnoreCase(canonical)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The recovery contract is native-path only: the overlay instruction,
     * the write_file-narrowed retry surface, and REQUIRED tool choice all
     * act on the native tool specs. Without a visible talos.write_file the
     * instruction could never be satisfied, so fail closed to the existing
     * truthful stop (this also keeps the text-protocol path unchanged).
     */
    private static boolean writeFileOnNativeSurface(LoopState state) {
        List<ToolSpec> specs = ToolRepromptRequestBuilder.currentNativeToolSpecs(state);
        if (specs == null || specs.isEmpty()) return false;
        for (ToolSpec spec : specs) {
            if (spec != null && "talos.write_file".equals(spec.name())) return true;
        }
        return false;
    }

    private static boolean hasSmallTextExtension(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) return false;
        return SMALL_TEXT_EXTENSIONS.contains(lower.substring(dot + 1));
    }
}
