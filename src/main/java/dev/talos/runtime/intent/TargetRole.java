package dev.talos.runtime.intent;

import java.util.List;
import java.util.Objects;

public enum TargetRole {
    FORBIDDEN(800),
    MUST_MUTATE(700),
    OUTPUT_DESTINATION(600),
    MUST_READ(500),
    SOURCE_EVIDENCE(400),
    VERIFY_ONLY(300),
    MAY_MUTATE(200),
    MENTIONED_ONLY(100);

    private static final List<TargetRole> PRECEDENCE = List.of(
            FORBIDDEN,
            MUST_MUTATE,
            OUTPUT_DESTINATION,
            MUST_READ,
            SOURCE_EVIDENCE,
            VERIFY_ONLY,
            MAY_MUTATE,
            MENTIONED_ONLY);

    private final int precedence;

    TargetRole(int precedence) {
        this.precedence = precedence;
    }

    public int precedence() {
        return precedence;
    }

    public boolean strongerThan(TargetRole other) {
        return precedence > Objects.requireNonNull(other, "other role must not be null").precedence;
    }

    public static TargetRole strongest(TargetRole first, TargetRole second) {
        TargetRole left = Objects.requireNonNull(first, "first role must not be null");
        TargetRole right = Objects.requireNonNull(second, "second role must not be null");
        return left.precedence >= right.precedence ? left : right;
    }

    public static List<TargetRole> byPrecedence() {
        return PRECEDENCE;
    }
}
