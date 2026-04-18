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
 * </ul>
 *
 * <p>Thread-safe: the single remember flag is an {@link AtomicBoolean}.
 */
public final class SessionApprovalPolicy implements ApprovalPolicy {

    /** Parameter name variants tools use for target paths. */
    private static final List<String> PATH_KEYS =
            List.of("path", "file_path", "filepath", "file", "filename");

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
        // WRITE — consider remember flag only for in-workspace targets.
        if (rememberInWorkspaceWrites.get() && isInWorkspace(workspace, call)) {
            return Decision.AUTO_APPROVE;
        }
        return Decision.ASK;
    }

    @Override
    public void rememberApproval(Path workspace, ToolCall call, ToolRiskLevel risk) {
        // Honor invariants even on the remember path.
        if (risk == null || risk == ToolRiskLevel.READ_ONLY) return;
        if (risk == ToolRiskLevel.DESTRUCTIVE) return;
        if (!isInWorkspace(workspace, call)) return;
        rememberInWorkspaceWrites.set(true);
    }

    /** @return true if the call's target path is non-blank and resolves inside {@code workspace}. */
    public static boolean isInWorkspace(Path workspace, ToolCall call) {
        if (workspace == null || call == null) return false;
        String raw = resolvePath(call);
        if (raw == null || raw.isBlank()) return false;
        try {
            Path ws = workspace.toAbsolutePath().normalize();
            Path candidate = Path.of(raw);
            if (!candidate.isAbsolute()) {
                candidate = ws.resolve(candidate);
            }
            candidate = candidate.normalize();
            return candidate.startsWith(ws);
        } catch (Exception e) {
            // Malformed path → refuse to classify as in-workspace
            return false;
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

