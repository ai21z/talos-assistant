package dev.talos.runtime.toolcall;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ToolSpec;

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
 * <p>Every condition fails closed: unread, truncated, redacted, oversized,
 * or non-text targets keep the existing truthful stop, and so do turns whose
 * native tool surface does not expose talos.write_file (the recovery
 * instruction and the narrowed retry surface only work on the native path).
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
        if (state == null || state.emptyEditArgumentFailuresByPath.isEmpty()) return;
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
        if (!state.pathsReadThisTurn.contains(path)) return false;
        if (!hasSmallTextExtension(path)) return false;
        if (RedactedReadWritebackGuard.hasSameTurnRedactedReadForPath(state, path)) return false;
        String rawBody = state.readFileBodiesThisTurn.get(path);
        String completeBody = AppendLinePreApprovalGuard.parseCompleteReadFileBody(rawBody);
        if (completeBody == null) return false;
        if (completeBody.length() > MAX_RECOVERY_BODY_CHARS) return false;
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        return contract != null && contract.mutationAllowed();
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
