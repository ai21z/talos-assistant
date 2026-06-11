package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtectedReadAnswerGuardTest {
    @TempDir
    Path workspace;

    @Test
    void approvedProtectedReadRefusalIsReplacedWithCurrentEvidenceAndTraced() throws Exception {
        Files.writeString(workspace.resolve(".env"), "SAFE_AUDIT_SETTING=fake\n");
        ToolCallLoop.LoopResult loopResult = loopResult(
                readOutcome("talos.read_file", ".env", "1 | contains approved private configuration"));

        LocalTurnTraceCapture.begin(
                "trc-protected-read-answer-guard",
                "sid",
                1,
                "2026-05-24T12:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "Read .env and summarize it.");
        try {
            ProtectedReadAnswerGuard.PostconditionResult result =
                    ProtectedReadAnswerGuard.enforceApprovedProtectedReadPostcondition(
                            "I can't provide that.",
                            loopResult,
                            workspace);
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertTrue(result.repaired());
            assertEquals("""
                    [Approved protected read postcondition: model refusal replaced with current approved read evidence.]

                    Current approved protected read evidence:
                    - .env: contains approved private configuration""", result.answer());
            assertTrue(trace.events().stream().anyMatch(event ->
                    "PROTECTED_READ_POSTCONDITION_CHECKED".equals(event.type())
                            && "REPAIRED".equals(event.data().get("status"))));
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void approvedProtectedReadAnswerContainingCurrentEvidencePassesThrough() throws Exception {
        Files.writeString(workspace.resolve(".env"), "SAFE_AUDIT_SETTING=fake\n");
        String answer = "The approved file summary says it contains approved private configuration.";

        ProtectedReadAnswerGuard.PostconditionResult result =
                ProtectedReadAnswerGuard.enforceApprovedProtectedReadPostcondition(
                        answer,
                        loopResult(readOutcome(
                                "talos.read_file",
                                ".env",
                                "1 | contains approved private configuration")),
                        workspace);

        assertFalse(result.repaired());
        assertEquals(answer, result.answer());
    }

    @Test
    void priorProtectedHistoryContentIsSuppressedWithoutCurrentApprovedRead() {
        List<ChatMessage> messages = List.of(ChatMessage.assistant(
                "Approved file .env contained SAFE_AUDIT_TOKEN=history-token"));

        String result = ProtectedReadAnswerGuard.suppressProtectedHistoryContentIfNeeded(
                "SAFE_AUDIT_TOKEN=history-token",
                messages,
                loopResult(),
                workspace);

        assertEquals(
                "I did not show protected content from an earlier approved read because this turn "
                        + "did not request and complete a fresh protected read approval.",
                result);
    }

    @Test
    void priorProtectedHistoryContentIsAllowedWhenCurrentApprovedReadExists() throws Exception {
        Files.writeString(workspace.resolve(".env"), "SAFE_AUDIT_TOKEN=history-token\n");
        List<ChatMessage> messages = List.of(ChatMessage.assistant(
                "Approved file .env contained SAFE_AUDIT_TOKEN=history-token"));
        String answer = "SAFE_AUDIT_TOKEN=history-token";

        String result = ProtectedReadAnswerGuard.suppressProtectedHistoryContentIfNeeded(
                answer,
                messages,
                loopResult(readOutcome("talos.read_file", ".env", "SAFE_AUDIT_TOKEN=history-token")),
                workspace);

        assertEquals(answer, result);
    }

    @Test
    void protectedReadDetectionAcceptsBackendAliasAndProtectedPathHint() {
        ProtectedReadAnswerGuard.PostconditionResult result =
                ProtectedReadAnswerGuard.enforceApprovedProtectedReadPostcondition(
                        "I cannot disclose that.",
                        loopResult(readOutcome(
                                "tool_use:read_file",
                                "notes-token.txt",
                                "token details were read")),
                        workspace);

        assertTrue(result.repaired());
        assertTrue(result.answer().contains("- notes-token.txt: token details were read"));
    }

    @Test
    void deniedProtectedReadSummaryReplacesModelContentAndCanonicalizesPath() {
        String answer = ProtectedReadAnswerGuard.summarizeDeniedProtectedReadOutcomesIfNeeded(
                "The file says SECRET=original.",
                loopResult(deniedReadOutcome(" .env")));

        assertEquals("""
                [Approval blocked: protected content was not read]

                Protected content was not read because approval was denied for:
                - .env: approval denied

                No protected file content was shown. Approve the protected read if you want Talos to inspect it.""",
                answer);
    }

    @Test
    void deniedProtectedReadSummaryPassesThroughWhenNoDeniedProtectedReadExists() {
        String answer = "No protected read was requested.";

        String result = ProtectedReadAnswerGuard.summarizeDeniedProtectedReadOutcomesIfNeeded(
                answer,
                loopResult(readOutcome("talos.read_file", "README.md", "readme contents")));

        assertEquals(answer, result);
    }

    @Test
    void blankProtectedReadSummaryKeepsExistingNoAdditionalDetailFallback() throws Exception {
        Files.writeString(workspace.resolve(".env"), "SAFE_AUDIT_SETTING=fake\n");

        ProtectedReadAnswerGuard.PostconditionResult result =
                ProtectedReadAnswerGuard.enforceApprovedProtectedReadPostcondition(
                        "I cannot provide the file contents.",
                        loopResult(readOutcome("talos.read_file", ".env", "")),
                        workspace);

        assertTrue(result.repaired());
        assertTrue(result.answer().contains("- .env: no additional detail"));
    }

    // ---- T760: blank ≠ refusal; refusal markers scoped to the answer head ----

    @Test
    void blankAnswerIsRepairedWithTruthfulBlankReason() throws Exception {
        Files.writeString(workspace.resolve(".env"), "SAFE_AUDIT_SETTING=fake\n");
        ToolCallLoop.LoopResult loopResult = loopResult(
                readOutcome("talos.read_file", ".env", "1 | contains approved private configuration"));

        LocalTurnTraceCapture.begin(
                "trc-t760-blank", "sid", 1, "2026-06-11T00:00:00Z",
                "workspace-hash", "auto", "test", "model", "Read .env.");
        try {
            ProtectedReadAnswerGuard.PostconditionResult result =
                    ProtectedReadAnswerGuard.enforceApprovedProtectedReadPostcondition(
                            "",
                            loopResult,
                            workspace);
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertTrue(result.repaired(), "blank answer after a granted approval must still be repaired");
            assertTrue(result.answer().contains("- .env: contains approved private configuration"));
            assertTrue(trace.events().stream().anyMatch(event ->
                            "PROTECTED_READ_POSTCONDITION_CHECKED".equals(event.type())
                                    && "REPAIRED".equals(event.data().get("status"))
                                    && "blank model answer replaced with current approved read evidence"
                                            .equals(event.data().get("reason"))),
                    "the trace reason must say BLANK, not refusal — calling a blank answer a "
                            + "'generic model refusal' was the T760 truthfulness violation");
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void tailCaveatBeyondAnswerHeadDoesNotTriggerRefusalRepair() throws Exception {
        Files.writeString(workspace.resolve(".env"), "SAFE_AUDIT_SETTING=fake\n");
        // A long grounded answer whose TAIL carries a legitimate caveat. The
        // caveat phrase ("cannot share") sits well past the 240-char head, and
        // the answer does not literally contain the evidence-summary
        // substring — under whole-answer matching this answer was destroyed.
        String answer = ("The configuration file defines one audit setting used by the local test "
                + "harness. Its value is a fake placeholder that exists so canary scans have "
                + "something safe to detect. The file has a single line and no other entries. ")
                .repeat(2)
                + "As a general policy note, I cannot share raw secret values beyond this summary.";

        ProtectedReadAnswerGuard.PostconditionResult result =
                ProtectedReadAnswerGuard.enforceApprovedProtectedReadPostcondition(
                        answer,
                        loopResult(readOutcome(
                                "talos.read_file", ".env",
                                "1 | contains approved private configuration")),
                        workspace);

        assertFalse(result.repaired(),
                "a tail caveat must not classify a long grounded answer as a refusal");
        assertEquals(answer, result.answer());
    }

    @Test
    void headPositionedRefusalIsStillRepaired() throws Exception {
        Files.writeString(workspace.resolve(".env"), "SAFE_AUDIT_SETTING=fake\n");

        ProtectedReadAnswerGuard.PostconditionResult result =
                ProtectedReadAnswerGuard.enforceApprovedProtectedReadPostcondition(
                        "I'm sorry, but I can't share the contents of that file with you.",
                        loopResult(readOutcome(
                                "talos.read_file", ".env",
                                "1 | contains approved private configuration")),
                        workspace);

        assertTrue(result.repaired());
    }

    @Test
    void tokenizerSourceReadDoesNotEngageProtectedReadPostcondition() {
        // T759/T760 characterization: tokenizer.java is no longer classified
        // protected, so the postcondition guard does not own this answer at
        // all — even a refusal-shaped answer passes through untouched.
        String answer = "I can't provide that.";

        ProtectedReadAnswerGuard.PostconditionResult result =
                ProtectedReadAnswerGuard.enforceApprovedProtectedReadPostcondition(
                        answer,
                        loopResult(readOutcome(
                                "talos.read_file", "src/tokenizer.java",
                                "public final class Tokenizer { }")),
                        workspace);

        assertFalse(result.repaired());
        assertEquals(answer, result.answer());
    }

    private static ToolCallLoop.ToolOutcome readOutcome(String toolName, String pathHint, String summary) {
        return new ToolCallLoop.ToolOutcome(
                toolName,
                pathHint,
                true,
                false,
                false,
                summary,
                "");
    }

    private static ToolCallLoop.ToolOutcome deniedReadOutcome(String pathHint) {
        return new ToolCallLoop.ToolOutcome(
                "talos.read_file",
                pathHint,
                false,
                false,
                true,
                "",
                "User did not approve the talos.read_file call.",
                null,
                ToolError.DENIED)
                .withFailureReason(ToolFailureReason.USER_APPROVAL_DENIED);
    }

    private static ToolCallLoop.LoopResult loopResult(ToolCallLoop.ToolOutcome... outcomes) {
        return new ToolCallLoop.LoopResult(
                "model answer",
                1,
                outcomes.length,
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
