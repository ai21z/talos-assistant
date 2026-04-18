package dev.talos.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * Thread-local collector for the current turn's tool/approval activity.
 *
 * <p>Started by {@link TurnProcessor#process} at the top of each turn,
 * updated by {@link TurnProcessor#executeTool} as tool calls execute and
 * approvals are resolved, and finalized at the end of the turn into an
 * immutable {@link TurnAudit} embedded in the returned {@link TurnResult}.
 *
 * <p>Following the precedent of {@link TurnTraceCapture} and
 * {@link TurnUserRequestCapture}: a narrow per-thread bag that keeps the
 * public runtime API surface stable.
 *
 * <p>All methods are null-safe. {@link #isActive()} reports whether a
 * turn is currently being audited on this thread; {@link #recordToolCall}
 * and the approval counters are no-ops outside an active turn.
 */
public final class TurnAuditCapture {

    private TurnAuditCapture() {}

    /** Mutable per-turn bag; finalized into {@link TurnAudit}. */
    static final class Bag {
        final List<TurnRecord.ToolCallSummary> toolCalls = new ArrayList<>();
        int approvalsRequired;
        int approvalsGranted;
        int approvalsDenied;
    }

    private static final ThreadLocal<Bag> HOLDER = new ThreadLocal<>();

    /** Start a new per-turn audit on the current thread. Replaces any prior bag. */
    public static void begin() {
        HOLDER.set(new Bag());
    }

    /** @return true if an audit is active on this thread. */
    public static boolean isActive() {
        return HOLDER.get() != null;
    }

    /** Append a tool-call summary to the current audit (no-op if none active). */
    public static void recordToolCall(String name, String pathHint, boolean success) {
        Bag b = HOLDER.get();
        if (b != null) {
            b.toolCalls.add(new TurnRecord.ToolCallSummary(name, pathHint, success));
        }
    }

    /** Increment the required-approvals counter (no-op if no audit active). */
    public static void recordApprovalRequired() {
        Bag b = HOLDER.get();
        if (b != null) b.approvalsRequired++;
    }

    /** Increment the granted-approvals counter (no-op if no audit active). */
    public static void recordApprovalGranted() {
        Bag b = HOLDER.get();
        if (b != null) b.approvalsGranted++;
    }

    /** Increment the denied-approvals counter (no-op if no audit active). */
    public static void recordApprovalDenied() {
        Bag b = HOLDER.get();
        if (b != null) b.approvalsDenied++;
    }

    /**
     * Finalize and remove the current audit, returning an immutable snapshot.
     * Returns {@link TurnAudit#empty()} if no audit was active.
     */
    public static TurnAudit end() {
        Bag b = HOLDER.get();
        HOLDER.remove();
        if (b == null) return TurnAudit.empty();
        return new TurnAudit(
                List.copyOf(b.toolCalls),
                b.approvalsRequired,
                b.approvalsGranted,
                b.approvalsDenied
        );
    }
}

