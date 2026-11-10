package dev.talos.runtime;

/**
 * Whether a proposed tool call reached the tool registry, as recorded by the producer of its outcome (LA-F7).
 *
 * <p>The tool processor sets {@code EXECUTED} immediately before it calls the registry and reports {@code BLOCKED}
 * for every earlier return. The tool-loop outcome factory reports {@code BLOCKED} for refusals that precede the
 * processor. An outcome built by an older constructor, or read back from JSON, carries {@code UNKNOWN}, and
 * {@code UNKNOWN} never proves that a call did not run.
 *
 * @param disposition    whether the call reached the tool registry
 * @param proposedTarget for a call that never ran, the call's own path argument with forward slashes, else empty
 */
public record ToolCallDispatch(TurnRecord.ToolCallDisposition disposition, String proposedTarget) {
    private static final int PROPOSED_TARGET_LIMIT = 240;
    private static final ToolCallDispatch UNKNOWN =
            new ToolCallDispatch(TurnRecord.ToolCallDisposition.UNKNOWN, "");
    private static final ToolCallDispatch EXECUTED =
            new ToolCallDispatch(TurnRecord.ToolCallDisposition.EXECUTED, "");

    public ToolCallDispatch {
        disposition = disposition == null ? TurnRecord.ToolCallDisposition.UNKNOWN : disposition;
        proposedTarget = disposition == TurnRecord.ToolCallDisposition.BLOCKED
                ? displayableTarget(proposedTarget)
                : "";
    }

    public static ToolCallDispatch unknown() {
        return UNKNOWN;
    }

    public static ToolCallDispatch executed() {
        return EXECUTED;
    }

    public static ToolCallDispatch blocked(String proposedTarget) {
        return new ToolCallDispatch(TurnRecord.ToolCallDisposition.BLOCKED, proposedTarget);
    }

    /** The witness for a gate disposition. Only a call that never ran keeps its proposed target. */
    public static ToolCallDispatch of(TurnRecord.ToolCallDisposition disposition, String proposedTarget) {
        if (disposition == TurnRecord.ToolCallDisposition.BLOCKED) return blocked(proposedTarget);
        if (disposition == TurnRecord.ToolCallDisposition.EXECUTED) return EXECUTED;
        return UNKNOWN;
    }

    /** True only when the producer proved that the call never reached the tool registry. */
    public boolean neverRan() {
        return disposition == TurnRecord.ToolCallDisposition.BLOCKED;
    }

    /** A path argument is kept for display only when it is short and has no control characters. */
    private static String displayableTarget(String value) {
        if (value == null) return "";
        String target = value.strip().replace('\\', '/');
        if (target.length() > PROPOSED_TARGET_LIMIT) return "";
        for (int i = 0; i < target.length(); i++) {
            if (Character.isISOControl(target.charAt(i))) return "";
        }
        return target;
    }
}
