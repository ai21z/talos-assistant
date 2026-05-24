package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.tools.ToolError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnsupportedDocumentCapabilityOutcomeTest {

    @Test
    void detectsUnsupportedReadFileThroughCanonicalAlias() {
        UnsupportedDocumentCapabilityOutcome outcome = UnsupportedDocumentCapabilityOutcome.assess(loopResult(
                new ToolCallLoop.ToolOutcome(
                        "read_file",
                        "report.docx",
                        false,
                        false,
                        false,
                        "",
                        "Unsupported binary document format: report.docx",
                        null,
                        ToolError.UNSUPPORTED_FORMAT)));

        assertTrue(outcome.limited());
    }

    @Test
    void ignoresSuccessfulReadFileAndNonReadFileUnsupportedErrors() {
        UnsupportedDocumentCapabilityOutcome outcome = UnsupportedDocumentCapabilityOutcome.assess(loopResult(
                new ToolCallLoop.ToolOutcome(
                        "talos.read_file",
                        "notes.md",
                        true,
                        false,
                        false,
                        "notes",
                        ""),
                new ToolCallLoop.ToolOutcome(
                        "talos.grep",
                        "report.docx",
                        false,
                        false,
                        false,
                        "",
                        "Unsupported binary document format: report.docx",
                        null,
                        ToolError.UNSUPPORTED_FORMAT)));

        assertFalse(outcome.limited());
    }

    @Test
    void nullOrEmptyLoopHasNoCapabilityLimit() {
        assertFalse(UnsupportedDocumentCapabilityOutcome.assess(null).limited());
        assertFalse(UnsupportedDocumentCapabilityOutcome.assess(loopResult()).limited());
    }

    private static ToolCallLoop.LoopResult loopResult(ToolCallLoop.ToolOutcome... outcomes) {
        return new ToolCallLoop.LoopResult(
                "answer",
                1,
                1,
                List.of(),
                List.of(),
                0,
                0,
                false,
                0,
                List.of(),
                0,
                0,
                0,
                0,
                List.of(outcomes));
    }
}
