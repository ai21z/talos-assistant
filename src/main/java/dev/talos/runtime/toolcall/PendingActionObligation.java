package dev.talos.runtime.toolcall;

import dev.talos.runtime.trace.LocalTurnTraceCapture;

import java.util.List;
import java.util.Objects;

public record PendingActionObligation(Kind kind, List<String> targets) {

    public enum Kind {
        EXPECTED_TARGETS_REMAINING("expected target progress"),
        STATIC_REPAIR_TARGETS_REMAINING("static repair progress");

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
    }

    public static PendingActionObligation expectedTargets(List<String> targets) {
        return new PendingActionObligation(Kind.EXPECTED_TARGETS_REMAINING, targets);
    }

    public static PendingActionObligation staticRepairTargets(List<String> targets) {
        return new PendingActionObligation(Kind.STATIC_REPAIR_TARGETS_REMAINING, targets);
    }

    public String failureReason() {
        return "Pending action obligation " + kind.name()
                + " was ignored after a " + kind.label
                + " reprompt. Remaining target(s): " + targetList()
                + ". The model returned no executable write/edit tool calls.";
    }

    public String failureAnswer() {
        return "[Action obligation failed: pending " + kind.label + " was not satisfied.]\n\n"
                + "Remaining target(s): " + targetList() + ".\n"
                + "The model returned prose instead of the required write/edit tool call, "
                + "so Talos stopped this turn deterministically.";
    }

    public void recordRaised() {
        LocalTurnTraceCapture.recordPendingActionObligation(
                "RAISED",
                kind.name(),
                targets,
                "pending " + kind.label + " requires executable write/edit tool calls");
    }

    public void recordBreached() {
        LocalTurnTraceCapture.recordPendingActionObligation(
                "BREACHED",
                kind.name(),
                targets,
                "model response had no executable write/edit tool calls");
    }

    private String targetList() {
        return targets.isEmpty() ? "(unknown)" : String.join(", ", targets);
    }
}
