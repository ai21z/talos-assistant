package dev.talos.runtime.toolcall;

import dev.talos.runtime.trace.LocalTurnTraceCapture;

import java.util.List;
import java.util.Objects;

public record PendingActionObligation(Kind kind, List<String> targets, String failureContext) {

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
        targets = targets == null
                ? List.of()
                : targets.stream()
                        .filter(Objects::nonNull)
                        .map(String::strip)
                        .filter(path -> !path.isBlank())
                        .distinct()
                        .toList();
        failureContext = failureContext == null ? "" : failureContext.strip();
    }

    public PendingActionObligation(Kind kind, List<String> targets) {
        this(kind, targets, "");
    }

    public static PendingActionObligation expectedTargets(List<String> targets) {
        return new PendingActionObligation(Kind.EXPECTED_TARGETS_REMAINING, targets);
    }

    public static PendingActionObligation expectedTargets(List<String> targets, String failureContext) {
        return new PendingActionObligation(Kind.EXPECTED_TARGETS_REMAINING, targets, failureContext);
    }

    public static PendingActionObligation staticRepairTargets(List<String> targets) {
        return new PendingActionObligation(Kind.STATIC_REPAIR_TARGETS_REMAINING, targets);
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
}
