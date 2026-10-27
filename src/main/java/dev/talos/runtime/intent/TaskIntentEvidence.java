package dev.talos.runtime.intent;

import dev.talos.runtime.task.TaskContract;

/** Fail-closed invariants for canonical request evidence carried by task intent. */
public final class TaskIntentEvidence {

    private TaskIntentEvidence() {}

    public static void requireCanonicalAgreement(
            TaskContract contract,
            TaskIntent intent
    ) {
        if (contract == null || intent == null) return;
        if (!contract.originalUserRequest().equals(intent.originalUserRequest())) {
            throw new IllegalArgumentException(
                    "task contract and intent must share one canonical request");
        }
        requireExactCanonicalSpans(intent);
    }

    public static void requireExactCanonicalSpans(TaskIntent intent) {
        if (intent == null) return;
        String request = intent.originalUserRequest();
        for (TargetRef target : intent.targets().targets()) {
            IntentDerivation derivation = target.derivation();
            if (derivation.startOffset() == IntentDerivation.UNKNOWN_OFFSET) {
                continue;
            }
            int start = derivation.startOffset();
            int authorityEnd = derivation.authorityEndOffset();
            int end = derivation.endOffset();
            if (end <= start
                    || end > request.length()
                    || !derivation.sourceText().equals(request.substring(start, end))) {
                throw new IllegalArgumentException(
                        "known target evidence must be an exact canonical request span: "
                                + target.path());
            }
            if (authorityEnd != IntentDerivation.UNKNOWN_OFFSET) {
                if (authorityEnd <= start
                        || authorityEnd > end
                        || !CoordinatedMutationTailPlan.containsPath(
                                request.substring(start, authorityEnd),
                                target.path())) {
                    throw new IllegalArgumentException(
                            "known target authority boundary must contain the exact target: "
                                    + target.path());
                }
            }
        }
    }

    /**
     * Validates the self-contained part of one durable roleful-target
     * evidence row. Only current-user evidence has a durable coordinate base;
     * every other source must use unknown offsets.
     */
    public static TargetSource requireDurableShape(
            String path,
            String source,
            int startOffset,
            int authorityEndOffset,
            int endOffset,
            String sourceText,
            double confidence
    ) {
        final TargetSource typedSource;
        try {
            typedSource = TargetSource.valueOf(source);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "roleful target source must be a named TargetSource",
                    e);
        }
        if (!Double.isFinite(confidence)
                || confidence < 0.0
                || confidence > 1.0) {
            throw new IllegalArgumentException(
                    "roleful target confidence must be finite and between 0.0 and 1.0");
        }
        boolean unknown = startOffset == IntentDerivation.UNKNOWN_OFFSET
                && authorityEndOffset == IntentDerivation.UNKNOWN_OFFSET
                && endOffset == IntentDerivation.UNKNOWN_OFFSET;
        if (unknown) return typedSource;
        if (startOffset < 0 || endOffset <= startOffset) {
            throw new IllegalArgumentException(
                    "roleful target source offsets must be exact or both unknown");
        }
        if (authorityEndOffset != IntentDerivation.UNKNOWN_OFFSET
                && (authorityEndOffset <= startOffset
                        || authorityEndOffset > endOffset)) {
            throw new IllegalArgumentException(
                    "roleful target authority end must be unknown or inside the exact source span");
        }
        if (typedSource != TargetSource.USER_REQUEST) {
            throw new IllegalArgumentException(
                    "only USER_REQUEST evidence may carry durable source offsets");
        }
        String exactText = sourceText == null ? "" : sourceText;
        if (endOffset - startOffset != exactText.length()) {
            throw new IllegalArgumentException(
                    "roleful target source span length must match sourceText");
        }
        if (authorityEndOffset != IntentDerivation.UNKNOWN_OFFSET
                && !CoordinatedMutationTailPlan.containsPath(
                        exactText.substring(
                                0,
                                authorityEndOffset - startOffset),
                        path)) {
            throw new IllegalArgumentException(
                    "roleful target authority end must contain the exact target path");
        }
        return typedSource;
    }

    /** Binds a known USER_REQUEST span to the exact canonical request. */
    public static void requireCanonicalDurableSpan(
            String canonicalRequest,
            String path,
            String source,
            int startOffset,
            int authorityEndOffset,
            int endOffset,
            String sourceText
    ) {
        if (startOffset == IntentDerivation.UNKNOWN_OFFSET
                && authorityEndOffset == IntentDerivation.UNKNOWN_OFFSET
                && endOffset == IntentDerivation.UNKNOWN_OFFSET) {
            return;
        }
        if (!TargetSource.USER_REQUEST.name().equals(source)) {
            throw new IllegalArgumentException(
                    "known durable coordinates require USER_REQUEST evidence");
        }
        String canonical = canonicalRequest == null
                ? ""
                : canonicalRequest.strip();
        String exactText = sourceText == null ? "" : sourceText;
        if (endOffset > canonical.length()
                || authorityEndOffset != IntentDerivation.UNKNOWN_OFFSET
                        && (authorityEndOffset <= startOffset
                                || authorityEndOffset > endOffset)
                || authorityEndOffset != IntentDerivation.UNKNOWN_OFFSET
                        && !CoordinatedMutationTailPlan.containsPath(
                                canonical.substring(
                                        startOffset,
                                        authorityEndOffset),
                                path)
                || !exactText.equals(canonical.substring(startOffset, endOffset))) {
            throw new IllegalArgumentException(
                    "stored user-request target evidence has no matching canonical coordinate span");
        }
    }

}
