package dev.talos.core.llm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Synthetic-workspace model answer retained for presentation fidelity, not semantic correctness. */
public final class RecordedMixedCodeAnswer {
    public static final String SHA256 = "6bf4bab493ad6c77e3a0ac0bf4b5f56ac0591e5bf58830b51fcfc7ab9269ad71";

    private RecordedMixedCodeAnswer() {}

    public static String text() {
        try (var stream = RecordedMixedCodeAnswer.class.getResourceAsStream("recorded-mixed-code-answer.txt")) {
            if (stream == null) throw new IllegalStateException("Missing recorded mixed-code fixture");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
