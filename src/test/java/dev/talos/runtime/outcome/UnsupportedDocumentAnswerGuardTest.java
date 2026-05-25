package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnsupportedDocumentAnswerGuardTest {

    @Test
    void unsupportedDocumentReadRemovesContentClaimsAndKeepsSupportedTextEvidence() {
        ToolCallLoop.LoopResult loopResult = loopResult(
                List.of(),
                readOutcome("notes.txt", true, "notes read", "", null),
                readOutcome(
                        "sample.pdf",
                        false,
                        "",
                        "Unsupported binary document format: sample.pdf (PDF). "
                                + "Talos cannot extract PDF contents with the current local text-tool surface.",
                        ToolError.UNSUPPORTED_FORMAT),
                readOutcome(
                        "sample.xlsx",
                        false,
                        "",
                        "Unsupported binary document format: sample.xlsx (Microsoft Excel .xlsx). "
                                + "Talos cannot extract Excel workbook contents with the current local text-tool surface.",
                        ToolError.UNSUPPORTED_FORMAT));

        String answer = UnsupportedDocumentAnswerGuard.overrideUnsupportedDocumentClaimsIfNeeded(
                "notes.txt says Talos should summarize supported text files. "
                        + "sample.pdf and sample.xlsx do not contain any extractable text. "
                        + "These files are empty or do not contain readable text.",
                loopResult);

        assertTrue(answer.startsWith("[Document capability note:"), answer);
        assertTrue(answer.contains("sample.pdf"), answer);
        assertTrue(answer.contains("sample.xlsx"), answer);
        assertTrue(answer.contains("notes.txt says Talos should summarize supported text files."), answer);
        assertFalse(answer.contains("do not contain any extractable text"), answer);
        assertFalse(answer.contains("These files are empty"), answer);
    }

    @Test
    void unsupportedSearchNoMatchesClaimGetsCapabilityNote() {
        ToolCallLoop.LoopResult loopResult = loopResult(
                List.of(ChatMessage.assistant("[tool_result: talos.grep]\nSearch was limited: skipped unsupported files.")),
                grepOutcome());

        String answer = UnsupportedDocumentAnswerGuard.overrideUnsupportedDocumentClaimsIfNeeded(
                "No matches were found.",
                loopResult);

        assertTrue(answer.startsWith(
                "Search was limited to searchable text files. Unsupported/binary files were skipped"), answer);
        assertTrue(answer.contains("No matches were found."), answer);
    }

    private static ToolCallLoop.ToolOutcome readOutcome(
            String path,
            boolean success,
            String summary,
            String errorMessage,
            String errorCode
    ) {
        return new ToolCallLoop.ToolOutcome(
                "talos.read_file", path, success, false, false,
                summary, errorMessage, null, errorCode);
    }

    private static ToolCallLoop.ToolOutcome grepOutcome() {
        return new ToolCallLoop.ToolOutcome(
                "talos.grep", ".", true, false, false,
                "Search was limited: skipped unsupported files.", "");
    }

    private static ToolCallLoop.LoopResult loopResult(
            List<ChatMessage> messages,
            ToolCallLoop.ToolOutcome... outcomes
    ) {
        return new ToolCallLoop.LoopResult(
                "final", outcomes.length, outcomes.length,
                List.of(), messages,
                outcomes.length, 0, false, 0, List.of("notes.txt"),
                0, 0, 0, 0,
                List.of(outcomes));
    }
}
