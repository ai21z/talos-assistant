package dev.talos.cli.repl;

import java.util.Objects;
import java.util.function.Predicate;

/** Admission is checked without output, then a separate answer boundary is opened. */
public record ExactAnswerPresentation(Predicate<String> preserves, Runnable begin) {
    public ExactAnswerPresentation {
        Objects.requireNonNull(preserves);
        Objects.requireNonNull(begin);
    }
}
