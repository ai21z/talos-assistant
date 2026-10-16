package dev.talos.runtime;

import dev.talos.core.llm.ProviderInlineHtmlLiteralProjection;
import dev.talos.core.security.Redactor;
import dev.talos.core.util.Sanitize;

import java.util.UUID;

/** A public projection is frozen before request cleanup. It is not a delivery receipt. */
public final class PreparedAnswer {
    private final UUID id = UUID.randomUUID();
    private final String selected;
    private final String projected;
    private final String policy;
    private final int turn;
    private final long limit;

    private PreparedAnswer(String selected, String projected, String policy, int turn, long limit) {
        this.selected = selected;
        this.projected = projected;
        this.policy = policy;
        this.turn = turn;
        this.limit = limit;
    }

    static PreparedAnswer freeze(String selected, RuntimeTurnContext context, int turn) {
        Redactor redactor = context == null ? null : context.redactor();
        if (redactor == null) redactor = new Redactor();
        String filtered = ToolCallStreamFilter.projectComplete(selected);
        String sanitized = ProviderInlineHtmlLiteralProjection.preserveIfInert(
                filtered, Sanitize.sanitizeForOutput(filtered));
        String projected = TurnAuditCapture.projectPublicAnswer(sanitized, redactor);
        long limit = TurnAuditCapture.answerPresentationLimit();
        if (projected.length() > limit) {
            projected = bound(projected, limit);
            dev.talos.runtime.trace.LocalTurnTraceCapture.recordAnswerDeliverySelected(selected, true, true);
            dev.talos.runtime.trace.LocalTurnTraceCapture.recordAnswerPresentationLimitation(
                    dev.talos.runtime.trace.LocalTurnTraceCapture.AnswerPresentationLimitation.OUTPUT_TRUNCATED);
        }
        return new PreparedAnswer(selected, projected, redactor.policyFingerprint(), turn, limit);
    }

    public UUID id() { return id; }
    public int turn() { return turn; }

    /** A changed value or destination policy cannot recover the runtime path exception. */
    public String project(String candidate, Redactor destination) {
        if (!selected.equals(candidate)) return "[Answer withheld: prepared value binding changed.]";
        return limitApproved(policy.equals(destination.policyFingerprint()) ? projected : destination.redactBlock(projected));
    }

    public String limitApproved(String approved) { return bound(approved, limit); }

    private static String bound(String approved, long limit) {
        if (approved.length() <= limit) return approved;
        int end = (int) Math.min(Integer.MAX_VALUE, limit);
        if (end > 0 && Character.isHighSurrogate(approved.charAt(end - 1))
                && Character.isLowSurrogate(approved.charAt(end))) end--;
        return approved.substring(0, end) + "\n\n[output truncated]";
    }

    @Override public String toString() { return "PreparedAnswer[turn=" + turn + "]"; }
}
