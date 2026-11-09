package dev.talos.runtime.toolcall;

import dev.talos.runtime.trace.LocalTurnTraceCapture;

import java.util.List;
import java.util.Objects;

/**
 * A pending obligation of the tool loop. {@code targets} are the targets the obligation still requires.
 * {@code changedTargets} are request-bound expected targets that this turn has already changed. Only
 * {@link Kind#EXPECTED_TARGETS_REMAINING} carries them, and they never repeat a required target. An exact
 * edit of a changed target stays in scope while the obligation is pending, because an exact edit
 * replaces one occurrence and a file can need several (HC-F6).
 */
public record PendingActionObligation(
        Kind kind,
        List<String> targets,
        String failureContext,
        List<String> changedTargets
) {

    /**
     * T1003: marker that opens the failure context of a bounded invalid-edit
     * full-rewrite recovery obligation. When such an obligation is breached,
     * the invalid-mutation answer renderer recognizes this marker and lets
     * the canonical truthful invalid-mutation summary take over, because the
     * obligation was only a bounded retry for the same invalid edits.
     */
    public static final String EDIT_RECOVERY_FAILURE_MARKER = "[Edit recovery failed]";

    public enum Kind {
        EXPECTED_TARGETS_REMAINING("expected target progress"),
        STATIC_REPAIR_TARGETS_REMAINING("static repair progress"),
        OLD_STRING_MISS_TARGET_REPAIR("old-string miss repair"),
        APPEND_LINE_TARGET_REPAIR("append-line repair"),
        EXPECTED_TARGET_SCOPE_REPAIR("expected-target scope repair");

        private final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    public PendingActionObligation {
        kind = kind == null ? Kind.EXPECTED_TARGETS_REMAINING : kind;
        targets = normalizedPaths(targets);
        failureContext = failureContext == null ? "" : failureContext.strip();
        List<String> required = targets;
        changedTargets = kind == Kind.EXPECTED_TARGETS_REMAINING
                ? normalizedPaths(changedTargets).stream()
                        .filter(path -> !required.contains(path))
                        .toList()
                : List.of();
    }

    public PendingActionObligation(Kind kind, List<String> targets, String failureContext) {
        this(kind, targets, failureContext, List.of());
    }

    public PendingActionObligation(Kind kind, List<String> targets) {
        this(kind, targets, "");
    }

    public static PendingActionObligation expectedTargets(List<String> targets) {
        return new PendingActionObligation(Kind.EXPECTED_TARGETS_REMAINING, targets);
    }

    /** Expected-target progress with the remaining targets and the expected targets this turn already changed. */
    public static PendingActionObligation expectedTargetProgress(
            List<String> remainingTargets,
            List<String> changedTargets
    ) {
        return new PendingActionObligation(
                Kind.EXPECTED_TARGETS_REMAINING, remainingTargets, "", changedTargets);
    }

    public static PendingActionObligation expectedTargets(List<String> targets, String failureContext) {
        return new PendingActionObligation(Kind.EXPECTED_TARGETS_REMAINING, targets, failureContext);
    }

    public static PendingActionObligation staticRepairTargets(List<String> targets) {
        return new PendingActionObligation(Kind.STATIC_REPAIR_TARGETS_REMAINING, targets);
    }

    public static PendingActionObligation staticRepairTargets(List<String> targets, String failureContext) {
        return new PendingActionObligation(Kind.STATIC_REPAIR_TARGETS_REMAINING, targets, failureContext);
    }

    public static PendingActionObligation oldStringMissTargets(List<String> targets) {
        return new PendingActionObligation(Kind.OLD_STRING_MISS_TARGET_REPAIR, targets);
    }

    public static PendingActionObligation appendLineTargets(List<String> targets) {
        return new PendingActionObligation(Kind.APPEND_LINE_TARGET_REPAIR, targets);
    }

    public static PendingActionObligation expectedTargetScopeTargets(List<String> targets) {
        return new PendingActionObligation(Kind.EXPECTED_TARGET_SCOPE_REPAIR, targets);
    }

    public String failureReason() {
        return failureReason("The model returned no executable write/edit tool calls.");
    }

    public String failureReason(String detail) {
        String suffix = detail == null || detail.isBlank()
                ? "The model returned no executable write/edit tool calls."
                : detail.strip();
        return "Pending action obligation " + kind.name()
                + " was ignored after a " + kind.label
                + " reprompt. Remaining target(s): " + targetList()
                + ". " + suffix;
    }

    public String failureAnswer() {
        return failureAnswer(
                "The model returned prose instead of the required write/edit tool call.");
    }

    public String failureAnswer(String detail) {
        String suffix = detail == null || detail.isBlank()
                ? "The model did not provide the required write/edit tool call."
                : detail.strip();
        String prefix = failureContext.isBlank() ? "" : failureContext + "\n\n";
        return prefix
                + "[Action obligation failed: pending " + kind.label + " was not satisfied.]\n\n"
                + "Remaining target(s): " + targetList() + ".\n"
                + suffix + "\n"
                + "Talos stopped this turn deterministically.";
    }

    public void recordRaised() {
        LocalTurnTraceCapture.recordPendingActionObligation(
                "RAISED",
                kind.name(),
                targets,
                changedTargets,
                "pending " + kind.label + " requires executable write/edit tool calls");
    }

    public void recordBreached() {
        recordBreached("model response had no executable write/edit tool calls");
    }

    public void recordBreached(String detail) {
        LocalTurnTraceCapture.recordPendingActionObligation(
                "BREACHED",
                kind.name(),
                targets,
                detail == null || detail.isBlank()
                        ? "model response had no executable write/edit tool calls"
                        : detail.strip());
    }

    private String targetList() {
        return targets.isEmpty() ? "(unknown)" : String.join(", ", targets);
    }

    private static List<String> normalizedPaths(List<String> paths) {
        return paths == null
                ? List.of()
                : paths.stream()
                        .filter(Objects::nonNull)
                        .map(String::strip)
                        .filter(path -> !path.isBlank())
                        .distinct()
                        .toList();
    }
}
