package dev.talos.runtime;

import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolRiskLevel;

import java.nio.file.Path;

/**
 * Session-scoped policy layer above {@link ApprovalGate}.
 *
 * <p>Classifies an about-to-execute tool call into one of three decisions:
 * auto-approve (skip the prompt), ask (show the gate), or deny (refuse
 * without prompting). This lets Talos honor session-local user preferences
 * such as "approve similar in-workspace edits for the rest of this session"
 * without weakening the per-call gate for destructive or out-of-workspace
 * operations.
 *
 * <p>Policy invariants — enforced by every implementation:
 * <ul>
 *   <li>{@link ToolRiskLevel#READ_ONLY} always returns {@link Decision#AUTO_APPROVE}.</li>
 *   <li>{@link ToolRiskLevel#DESTRUCTIVE} never returns {@link Decision#AUTO_APPROVE}.</li>
 *   <li>Writes resolved outside the session workspace never auto-approve.</li>
 * </ul>
 */
public interface ApprovalPolicy {

    /** Decision produced by {@link #decide}. */
    enum Decision {
        /** Policy permits the call without prompting. */
        AUTO_APPROVE,
        /** Policy is neutral — fall through to {@link ApprovalGate}. */
        ASK,
        /** Policy forbids the call — refuse without prompting. */
        DENY
    }

    /**
     * Classify the call against the current session policy.
     *
     * @param workspace the session workspace (used to classify in-workspace vs out-of-workspace writes)
     * @param call      the tool call about to execute
     * @param risk      the tool's declared risk level
     * @return the policy decision
     */
    Decision decide(Path workspace, ToolCall call, ToolRiskLevel risk);

    /**
     * Record the user's "yes, and remember this" choice so subsequent similar
     * calls can auto-approve. Implementations must ignore destructive calls
     * and out-of-workspace writes to honor the policy invariants above.
     */
    void rememberApproval(Path workspace, ToolCall call, ToolRiskLevel risk);

    /** A null-object policy that always asks and never remembers. Useful in tests. */
    ApprovalPolicy ALWAYS_ASK = new ApprovalPolicy() {
        @Override
        public Decision decide(Path workspace, ToolCall call, ToolRiskLevel risk) {
            if (risk == null || risk == ToolRiskLevel.READ_ONLY) return Decision.AUTO_APPROVE;
            return Decision.ASK;
        }
        @Override
        public void rememberApproval(Path workspace, ToolCall call, ToolRiskLevel risk) {
            // no-op
        }
    };
}

