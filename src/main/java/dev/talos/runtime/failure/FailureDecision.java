package dev.talos.runtime.failure;

import java.util.Objects;

public record FailureDecision(FailureAction action, String reason) {
    private static final FailureDecision CONTINUE =
            new FailureDecision(FailureAction.CONTINUE, "");

    public FailureDecision {
        action = action == null ? FailureAction.CONTINUE : action;
        reason = reason == null ? "" : reason.strip();
    }

    public static FailureDecision continueLoop() {
        return CONTINUE;
    }

    public static FailureDecision stop(FailureAction action, String reason) {
        Objects.requireNonNull(action, "action");
        if (action == FailureAction.CONTINUE) return continueLoop();
        return new FailureDecision(action, reason);
    }

    public boolean shouldStop() {
        return action != FailureAction.CONTINUE;
    }
}
