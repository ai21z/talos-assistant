package dev.talos.runtime.outcome;

import java.util.Objects;

public record TruthWarning(TruthWarningType type, String message) {
    public TruthWarning {
        type = Objects.requireNonNull(type, "type");
        message = message == null ? "" : message;
    }

    public static TruthWarning of(TruthWarningType type, String message) {
        return new TruthWarning(type, message);
    }
}
