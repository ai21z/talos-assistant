package dev.talos.runtime.intent;

public record IntentDerivation(
        TargetSource source,
        String reason,
        int startOffset,
        int endOffset,
        String sourceText,
        double confidence
) {
    public static final int UNKNOWN_OFFSET = -1;

    public IntentDerivation {
        source = source == null ? TargetSource.USER_REQUEST : source;
        reason = reason == null ? "" : reason.strip();
        sourceText = sourceText == null ? "" : sourceText;
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0.0 and 1.0");
        }
        boolean startKnown = startOffset >= 0;
        boolean endKnown = endOffset >= 0;
        if (startOffset < UNKNOWN_OFFSET || endOffset < UNKNOWN_OFFSET) {
            throw new IllegalArgumentException("source offsets must be non-negative or UNKNOWN_OFFSET");
        }
        if (startKnown != endKnown) {
            throw new IllegalArgumentException("source offsets must both be known or both be unknown");
        }
        if (startKnown && endOffset < startOffset) {
            throw new IllegalArgumentException("endOffset must be greater than or equal to startOffset");
        }
    }

    public static IntentDerivation unknown() {
        return new IntentDerivation(
                TargetSource.RUNTIME_DEFAULT,
                "",
                UNKNOWN_OFFSET,
                UNKNOWN_OFFSET,
                "",
                1.0);
    }
}
