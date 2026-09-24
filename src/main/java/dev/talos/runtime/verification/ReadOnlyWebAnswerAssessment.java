package dev.talos.runtime.verification;

import java.util.Objects;

/** Retained provider text is distinguished from checked, runtime-derived answer components. */
public record ReadOnlyWebAnswerAssessment(Disposition disposition, Reason reason) {
    public enum Disposition {
        NOT_APPLICABLE,
        PRESERVED,
        RUNTIME_DERIVED,
        LIMITED
    }

    public enum Reason {
        NOT_APPLICABLE,
        PRESERVED,
        RUNTIME_DERIVED,
        ANSWER_REQUIREMENTS_UNMET,
        SYNTHESIS_UNAVAILABLE,
        INVALID_INPUT,
        SOURCE_EVIDENCE_UNAVAILABLE,
        SOURCE_RELATION_UNSUPPORTED,
        SOURCE_RELATION_AMBIGUOUS,
        LINKED_SOURCE_UNOBSERVED,
        UNSUPPORTED_DIAGNOSTIC,
        UNSUPPORTED_ASSERTION,
        EXECUTION_CLAIM_WITHOUT_EVIDENCE,
        SELECTOR_CONTRADICTION,
        UNREAD_PATH,
        NO_MISSING_SELECTOR,
        SELECTOR_LIMIT_EXCEEDED,
        SELECTOR_RELATION_NOT_ESTABLISHED,
        UNOBSERVED_ID_SELECTOR,
        UNOBSERVED_CLASS_SELECTOR
    }

    public ReadOnlyWebAnswerAssessment {
        Objects.requireNonNull(disposition, "An assessment disposition is required.");
        Objects.requireNonNull(reason, "An assessment reason is required.");
        boolean consistent = switch (disposition) {
            case NOT_APPLICABLE -> reason == Reason.NOT_APPLICABLE;
            case PRESERVED -> reason == Reason.PRESERVED;
            case RUNTIME_DERIVED -> reason == Reason.RUNTIME_DERIVED;
            case LIMITED -> reason != Reason.NOT_APPLICABLE && reason != Reason.PRESERVED && reason != Reason.RUNTIME_DERIVED;
        };
        if (!consistent) throw new IllegalArgumentException("An incompatible assessment was supplied.");
    }

    public boolean preserved() {
        return disposition == Disposition.PRESERVED;
    }

    public boolean limited() {
        return disposition == Disposition.LIMITED;
    }

    public static ReadOnlyWebAnswerAssessment notApplicable() {
        return new ReadOnlyWebAnswerAssessment(Disposition.NOT_APPLICABLE, Reason.NOT_APPLICABLE);
    }

    public static ReadOnlyWebAnswerAssessment preservedAnswer() {
        return new ReadOnlyWebAnswerAssessment(Disposition.PRESERVED, Reason.PRESERVED);
    }

    public static ReadOnlyWebAnswerAssessment limited(Reason reason) {
        return new ReadOnlyWebAnswerAssessment(Disposition.LIMITED, reason);
    }
}
