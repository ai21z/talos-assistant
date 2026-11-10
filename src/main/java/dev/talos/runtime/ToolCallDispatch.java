package dev.talos.runtime;

import java.util.ArrayList;
import java.util.List;

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
 * @param proposedChange for a call that never ran, what it would have changed, else {@link ProposedChange#none()}
 */
public record ToolCallDispatch(
        TurnRecord.ToolCallDisposition disposition,
        String proposedTarget,
        ProposedChange proposedChange
) {
    private static final int PROPOSED_TARGET_LIMIT = 240;
    private static final ToolCallDispatch UNKNOWN =
            new ToolCallDispatch(TurnRecord.ToolCallDisposition.UNKNOWN, "", ProposedChange.none());
    private static final ToolCallDispatch EXECUTED =
            new ToolCallDispatch(TurnRecord.ToolCallDisposition.EXECUTED, "", ProposedChange.none());

    /** The kind of change a call that never ran proposed, as its producer resolved it from the tool name. */
    public enum OperationKind {
        /** A file tool, which changes its path argument. */
        FILE,
        /** A copy, which changes its destination. */
        COPY,
        /** A move, which changes its source and its destination. */
        MOVE,
        /** A rename, which changes its source and its destination. */
        RENAME,
        /** A delete, which changes its target. */
        DELETE,
        /** A mkdir, which creates its target and changes no file below it. */
        MKDIR,
        /** A batch, whose operands are not described here. */
        BATCH,
        /** No change was described. */
        NONE;

        /** True for an operation that changes every file below a folder it names. */
        public boolean folderScoped() {
            return this == COPY || this == MOVE || this == RENAME || this == DELETE;
        }
    }

    /**
     * What a call that never ran would have changed, by the typed role of its operands (D25 option B).
     *
     * @param kind         the operation kind
     * @param changedPaths the named operands whose role the operation changes, with forward slashes
     * @param namedPaths   every named operand in role order (source, destination, target), for display
     * @param complete     true when every operand that the kind changes is named and every named operand could be
     *                     kept, so that changedPaths are all the files the call would change
     */
    public record ProposedChange(
            OperationKind kind,
            List<String> changedPaths,
            List<String> namedPaths,
            boolean complete
    ) {
        public ProposedChange {
            kind = kind == null ? OperationKind.NONE : kind;
            Kept changed = kept(changedPaths);
            Kept named = kept(namedPaths);
            changedPaths = changed.paths();
            namedPaths = named.paths();
            complete = complete && !changed.dropped() && !named.dropped() && !changedPaths.isEmpty()
                    && kind != OperationKind.NONE && kind != OperationKind.BATCH;
        }

        public static ProposedChange none() {
            return new ProposedChange(OperationKind.NONE, List.of(), List.of(), false);
        }

        /** A file tool's call, which changes only its path argument. */
        public static ProposedChange file(String path) {
            List<String> paths = path == null || path.isBlank() ? List.of() : List.of(path);
            return new ProposedChange(OperationKind.FILE, paths, paths, !paths.isEmpty());
        }
    }

    private record Kept(List<String> paths, boolean dropped) {}

    public ToolCallDispatch {
        disposition = disposition == null ? TurnRecord.ToolCallDisposition.UNKNOWN : disposition;
        boolean blocked = disposition == TurnRecord.ToolCallDisposition.BLOCKED;
        proposedTarget = blocked ? displayableTarget(proposedTarget) : "";
        proposedChange = blocked && proposedChange != null ? proposedChange : ProposedChange.none();
    }

    /** A witness of a file tool's call, whose proposed change is its path argument. */
    public ToolCallDispatch(TurnRecord.ToolCallDisposition disposition, String proposedTarget) {
        this(disposition, proposedTarget, ProposedChange.file(proposedTarget));
    }

    public static ToolCallDispatch unknown() {
        return UNKNOWN;
    }

    public static ToolCallDispatch executed() {
        return EXECUTED;
    }

    /** A file tool's call that never ran, whose only changed path is its path argument. */
    public static ToolCallDispatch blocked(String proposedTarget) {
        return new ToolCallDispatch(TurnRecord.ToolCallDisposition.BLOCKED, proposedTarget);
    }

    /** A call that never ran, with the change its producer derived from the typed operand roles. */
    public static ToolCallDispatch blocked(String proposedTarget, ProposedChange proposedChange) {
        return new ToolCallDispatch(TurnRecord.ToolCallDisposition.BLOCKED, proposedTarget, proposedChange);
    }

    /** The witness for a gate disposition. Only a call that never ran keeps its proposed target and change. */
    public static ToolCallDispatch of(
            TurnRecord.ToolCallDisposition disposition,
            String proposedTarget,
            ProposedChange proposedChange
    ) {
        if (disposition == TurnRecord.ToolCallDisposition.BLOCKED) return blocked(proposedTarget, proposedChange);
        if (disposition == TurnRecord.ToolCallDisposition.EXECUTED) return EXECUTED;
        return UNKNOWN;
    }

    /** The witness for a gate disposition of a file tool's call. */
    public static ToolCallDispatch of(TurnRecord.ToolCallDisposition disposition, String proposedTarget) {
        return of(disposition, proposedTarget, ProposedChange.file(proposedTarget));
    }

    /** The paths a call that never ran would have changed, as far as its operands name them. */
    public List<String> proposedChangedPaths() {
        return proposedChange.changedPaths();
    }

    /** True only when the producer proved that the call never reached the tool registry. */
    public boolean neverRan() {
        return disposition == TurnRecord.ToolCallDisposition.BLOCKED;
    }

    /**
     * True when a path text can be shown in a recorded-outcome row: not blank, at most 240 characters and without
     * control characters, so a model-supplied path cannot add a row of its own.
     */
    public static boolean displayable(String value) {
        if (value == null || value.isBlank() || value.strip().length() > PROPOSED_TARGET_LIMIT) return false;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return false;
        }
        return true;
    }

    /** A path argument is kept for display only when it is displayable. */
    private static String displayableTarget(String value) {
        return displayable(value) ? value.strip().replace('\\', '/') : "";
    }

    /**
     * The displayable paths, once each, with whether a non-blank path was dropped. A dropped path makes the change
     * incomplete, so the remaining paths never stand for the whole proposal and a model-supplied path cannot name a
     * file of its own.
     */
    private static Kept kept(List<String> values) {
        if (values == null || values.isEmpty()) return new Kept(List.of(), false);
        List<String> paths = new ArrayList<>();
        boolean dropped = false;
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            String path = displayableTarget(value);
            if (path.isEmpty()) {
                dropped = true;
            } else if (!paths.contains(path)) {
                paths.add(path);
            }
        }
        return new Kept(List.copyOf(paths), dropped);
    }
}
