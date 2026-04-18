package dev.talos.runtime;

import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolRiskLevel;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal session-scoped approval policy.
 *
 * <p>Default posture matches the current Talos behavior: every mutating call
 * goes through the approval gate. The optional "remember for session" choice
 * flips a single flag that auto-approves subsequent {@link ToolRiskLevel#WRITE}
 * calls whose target path is <em>inside the session workspace</em>. The
 * session-local flag is the entire memory surface — intentionally the
 * smallest useful policy, not a DSL.
 *
 * <p>Invariants enforced here:
 * <ul>
 *   <li>{@link ToolRiskLevel#READ_ONLY} → always {@link Decision#AUTO_APPROVE}.</li>
 *   <li>{@link ToolRiskLevel#DESTRUCTIVE} → always {@link Decision#ASK}
 *       (even after remember).</li>
 *   <li>Writes outside the workspace → always {@link Decision#ASK}
 *       (even after remember).</li>
 *   <li>Writes to missing-path calls → always {@link Decision#ASK}
 *       (the path can't be classified, so default to asking).</li>
 *   <li>Writes to <em>sensitive workspace-internal paths</em>
 *       ({@code .git/}, {@code .github/}, {@code .ssh/}, {@code .gnupg/}, or any
 *       {@code .env} / {@code .env.*} file) → always {@link Decision#ASK},
 *       even after remember. These are well-known backdoor paths (VCS
 *       internals, CI workflows, credentials, secrets) where a silent
 *       auto-approve is unsafe regardless of workspace containment.</li>
 * </ul>
 *
 * <p>Thread-safe: the single remember flag is an {@link AtomicBoolean}.
 */
public final class SessionApprovalPolicy implements ApprovalPolicy {

    /** Parameter name variants tools use for target paths. */
    private static final List<String> PATH_KEYS =
            List.of("path", "file_path", "filepath", "file", "filename");

    /**
     * Sensitive in-workspace directory segments that never auto-approve,
     * even when the session's remember flag is on. Matched exactly against
     * any segment of the normalized relative path (case-sensitive — these
     * are POSIX-canonical names).
     */
    private static final List<String> SENSITIVE_DIR_SEGMENTS =
            List.of(".git", ".github", ".ssh", ".gnupg");

    /** Session-wide remember flag for in-workspace writes. */
    private final AtomicBoolean rememberInWorkspaceWrites = new AtomicBoolean(false);

    @Override
    public Decision decide(Path workspace, ToolCall call, ToolRiskLevel risk) {
        if (risk == null || risk == ToolRiskLevel.READ_ONLY) {
            return Decision.AUTO_APPROVE;
        }
        if (risk == ToolRiskLevel.DESTRUCTIVE) {
            return Decision.ASK; // never auto — invariant
        }
        // WRITE — consider remember flag only for in-workspace, non-sensitive targets.
        if (rememberInWorkspaceWrites.get()
                && isInWorkspace(workspace, call)
                && !isSensitiveTarget(workspace, call)) {
            return Decision.AUTO_APPROVE;
        }
        return Decision.ASK;
    }

    @Override
    public void rememberApproval(Path workspace, ToolCall call, ToolRiskLevel risk) {
        // Honor invariants even on the remember path — a user who approves
        // a sensitive write once must not silently opt in to future sensitive
        // writes for the whole session.
        if (risk == null || risk == ToolRiskLevel.READ_ONLY) return;
        if (risk == ToolRiskLevel.DESTRUCTIVE) return;
        if (!isInWorkspace(workspace, call)) return;
        if (isSensitiveTarget(workspace, call)) return;
        rememberInWorkspaceWrites.set(true);
    }

    /** @return true if the call's target path is non-blank and resolves inside {@code workspace}. */
    public static boolean isInWorkspace(Path workspace, ToolCall call) {
        Path resolved = resolveAgainst(workspace, call);
        if (resolved == null || workspace == null) return false;
        try {
            return resolved.startsWith(workspace.toAbsolutePath().normalize());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @return true if the call's resolved target lives under a well-known
     *         sensitive directory ({@code .git}, {@code .github}, {@code .ssh},
     *         {@code .gnupg}) relative to {@code workspace}, OR its filename
     *         is {@code .env} or starts with {@code .env.}.
     *         Blank / unresolvable / out-of-workspace paths return false
     *         (classification is the {@link #isInWorkspace} job).
     */
    public static boolean isSensitiveTarget(Path workspace, ToolCall call) {
        Path resolved = resolveAgainst(workspace, call);
        if (resolved == null || workspace == null) return false;
        try {
            Path ws = workspace.toAbsolutePath().normalize();
            if (!resolved.startsWith(ws)) return false;
            Path relative = ws.relativize(resolved);
            for (Path seg : relative) {
                String name = seg.toString();
                if (SENSITIVE_DIR_SEGMENTS.contains(name)) return true;
                if (".env".equals(name) || name.startsWith(".env.")) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Resolve the call's target path against the workspace root (relative paths
     * resolve under ws; absolute paths are used as-is) and normalize. Returns
     * null if the call carries no recognized path parameter or the path is
     * malformed.
     */
    private static Path resolveAgainst(Path workspace, ToolCall call) {
        if (call == null) return null;
        String raw = resolvePath(call);
        if (raw == null || raw.isBlank()) return null;
        try {
            Path ws = workspace == null ? null : workspace.toAbsolutePath().normalize();
            Path candidate = Path.of(raw);
            if (!candidate.isAbsolute()) {
                if (ws == null) return null;
                candidate = ws.resolve(candidate);
            }
            return candidate.normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static String resolvePath(ToolCall call) {
        for (String k : PATH_KEYS) {
            String v = call.param(k);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /** Test hook — true if the session-wide remember flag has been set. */
    public boolean rememberInWorkspaceWritesEnabled() {
        return rememberInWorkspaceWrites.get();
    }
}

