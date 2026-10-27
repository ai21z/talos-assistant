package dev.talos.cli.modes;

/** Output-limit ownership at an existing answer-selection boundary, not a truthfulness decision. */
enum AnswerOutputLimit {
    KEEP_ORIGINAL,
    CLEAR,
    REACHED;

    record Presentation(String answer, AnswerOutputLimit outputLimit) {
        Presentation(String answer, boolean outputLimitRelevant) {
            this(answer, outputLimitRelevant ? KEEP_ORIGINAL : CLEAR);
        }
    }

    static AnswerOutputLimit selected(boolean reached) {
        return reached ? REACHED : CLEAR;
    }

    boolean apply(boolean original) {
        return this == KEEP_ORIGINAL ? original : this == REACHED;
    }
}
