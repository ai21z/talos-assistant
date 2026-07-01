package dev.talos.runtime.outcome;

import java.util.Objects;

public record TruthWarning(TruthWarningType type, String message) {
    public TruthWarning(TruthWarningType type, String message) {
        this.type = Objects.requireNonNull(type, "type");
        this.message = message == null ? "" : message;
    }

    public static TruthWarning of(TruthWarningType type, String message) {
        return new TruthWarning(type, message);
    }
}
