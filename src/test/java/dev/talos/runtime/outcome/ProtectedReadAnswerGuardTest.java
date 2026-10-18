package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtectedReadAnswerGuardTest {
    @TempDir
    Path workspace;

    @AfterEach
    void clearTaskCapture() {
        TurnTaskContractCapture.clear();
    }

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
    void deniedProtectedReadAliasSummaryReplacesModelContent() {
        String answer = ProtectedReadAnswerGuard.summarizeDeniedProtectedReadOutcomesIfNeeded(
                "The file says SECRET=original.",
                loopResult(deniedReadOutcome("file_utils:read_file", ".env")));

        assertTrue(answer.startsWith("[Approval blocked: protected content was not read]"), answer);
        assertTrue(answer.contains("- .env: approval denied"), answer);
        assertFalse(answer.contains("SECRET=original"), answer);
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
                    "the trace reason must say BLANK, not refusal - calling a blank answer a "
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
        // substring - under whole-answer matching this answer was destroyed.
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
        // all - even a refusal-shaped answer passes through untouched.
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

    @Test
    void protectedContainmentUsesCapturedOriginalInsteadOfSyntheticTail() {
        String original =
                "Does the document contain ALPHA MARKER?";
        TurnTaskContractCapture.set(
                TaskContractResolver.fromUserRequest(original));
        List<ChatMessage> messages = List.of(
                ChatMessage.user(original),
                ChatMessage.user(
                        "Synthesis retry: Does the approved document contain "
                                + "BETA SECRET?"));

        ProtectedReadAnswerGuard.PostconditionResult result =
                ProtectedReadAnswerGuard.enforceApprovedProtectedReadPostcondition(
                        "I cannot provide that.",
                        loopResultWithBody(
                                readOutcome(
                                        "talos.read_file",
                                        ".env",
                                        "approved private document was read"),
                                ".env",
                                "BETA SECRET\n"),
                        workspace,
                        messages);

        assertFalse(result.answer().contains("Yes. BETA SECRET"), result.answer());
        assertFalse(
                result.answer().contains(
                        "contains the requested phrase `BETA SECRET`"),
                result.answer());
    }

    @Test
    void protectedContainmentIgnoresSyntheticContradictionWhenOriginalMatches() {
        String original =
                "Does the document contain BETA SECRET?";
        TurnTaskContractCapture.set(
                TaskContractResolver.fromUserRequest(original));
        List<ChatMessage> messages = List.of(
                ChatMessage.user(original),
                ChatMessage.user(
                        "Synthesis retry: Does the approved document contain "
                                + "ALPHA MARKER?"));

        ProtectedReadAnswerGuard.PostconditionResult result =
                ProtectedReadAnswerGuard.enforceApprovedProtectedReadPostcondition(
                        "I cannot provide that.",
                        loopResultWithBody(
                                readOutcome(
                                        "talos.read_file",
                                        ".env",
                                        "approved private document was read"),
                                ".env",
                                "BETA SECRET\n"),
                        workspace,
                        messages);

        assertTrue(
                result.answer().contains(
                        "Yes. The document contains BETA SECRET"),
                result.answer());
        assertFalse(result.answer().contains("ALPHA MARKER"), result.answer());
    }

    @Test
    void scatteredWordsOfTheAskedPhraseDoNotConfirmContainment() {
        ProtectedReadAnswerGuard.PostconditionResult probe = containmentProbe(
                "Does the document contain the production database password?",
                "# production settings\n"
                        + "database_host=db.internal\n"
                        + "admin_password_label=see vault\n");

        assertFalse(probe.answer().startsWith("Yes."), probe.answer());
        assertTrue(probe.answer().contains("Current approved protected read evidence"), probe.answer());
    }

    @Test
    void askedWordInsideALongerWordDoesNotConfirmContainment() {
        ProtectedReadAnswerGuard.PostconditionResult probe = containmentProbe(
                "Does the document contain a key?",
                "monkey=banana\n");

        assertFalse(probe.answer().startsWith("Yes."), probe.answer());
    }

    @Test
    void askedWordsInAnotherOrderDoNotConfirmContainment() {
        ProtectedReadAnswerGuard.PostconditionResult probe = containmentProbe(
                "Does it contain a password database?",
                "database password=fake-value\n");

        assertFalse(probe.answer().startsWith("Yes."), probe.answer());
    }

    @Test
    void consecutiveAskedWordsAcrossSeparatorsStillConfirmContainment() {
        ProtectedReadAnswerGuard.PostconditionResult probe = containmentProbe(
                "Does the document contain the production database password?",
                "PRODUCTION_DATABASE_PASSWORD=fake-value\n");

        assertEquals(
                "Yes. The document contains the production database password, but the raw value is not printed.",
                probe.answer());
    }

    @Test
    void articlesBetweenAskedWordsInTheBodyStillConfirmContainment() {
        ProtectedReadAnswerGuard.PostconditionResult probe = containmentProbe(
                "Does it contain a password for the database?",
                "Password for the database: fake-value\n");

        assertEquals(
                "Yes. The document contains a password for the database, but the raw value is not printed.",
                probe.answer());
    }

    @Test
    void extractionHeaderWordsDoNotConfirmContainment() {
        ProtectedReadAnswerGuard.PostconditionResult probe = containmentProbe(
                "Read report.docx and tell me whether it contains tracked changes.",
                "report.docx",
                DOCX_HEADER + "Patient name: [redacted-private-document-canary]\n");

        assertFalse(probe.answer().startsWith("Yes."), probe.answer());
    }

    @Test
    void extractedFileNameWordsDoNotConfirmContainment() {
        ProtectedReadAnswerGuard.PostconditionResult probe = containmentProbe(
                "Does the document contain medical notes?",
                "report.docx",
                DOCX_HEADER.replace("report.docx", "medical-notes.docx")
                        + "Patient name: [redacted-private-document-canary]\n");

        assertFalse(probe.answer().startsWith("Yes."), probe.answer());
    }

    @Test
    void extractedDocumentContentStillConfirmsContainment() {
        ProtectedReadAnswerGuard.PostconditionResult probe = containmentProbe(
                "Read report.docx and tell me whether it contains a patient name.",
                "report.docx",
                DOCX_HEADER + "Patient name: [redacted-private-document-canary]\n");

        assertEquals("Yes. The document contains a patient name, but the name is not printed.", probe.answer());
    }

    @Test
    void readLineNumbersAreNotDocumentWords() {
        ProtectedReadAnswerGuard.PostconditionResult probe = containmentProbe(
                "Does the document contain type 12 diabetes?",
                "11 | type\n12 | diabetes\n");

        assertFalse(probe.answer().startsWith("Yes."), probe.answer());
    }

    @Test
    void lineNumberedReadStillConfirmsContainmentAcrossLines() {
        ProtectedReadAnswerGuard.PostconditionResult probe = containmentProbe(
                "Does the document contain the production database password?",
                "1 | # production\n2 | database password=fake-value\n");

        assertEquals(
                "Yes. The document contains the production database password, but the raw value is not printed.",
                probe.answer());
    }

    @Test
    void singleCharactersAndDigitsDistinguishTheAskedPhrase() {
        assertFalse(containmentProbe(
                "Does the document contain type 1 diabetes?",
                "Diagnosis: type 2 diabetes\n").answer().startsWith("Yes."));
        assertFalse(containmentProbe(
                "Does the document contain a Form W-2?",
                "Attached: Form W-4\n").answer().startsWith("Yes."));
    }

    @Test
    void nonAsciiLettersDistinguishTheAskedPhrase() {
        assertFalse(containmentProbe(
                "Does the document contain Müller?",
                "Contact: Möller\n").answer().startsWith("Yes."));
        assertEquals(
                "Yes. The document contains Müller, but the raw value is not printed.",
                containmentProbe("Does the document contain Müller?", "Contact: Müller\n").answer());
    }

    @Test
    void framingWordsInsideTheAskedPhraseAreNotDropped() {
        ProtectedReadAnswerGuard.PostconditionResult probe = containmentProbe(
                "Does the document contain a document number?",
                "Phone number: [redacted]\n");

        assertFalse(probe.answer().startsWith("Yes."), probe.answer());
    }

    @Test
    void withheldPrivateContentNoticeIsNotDocumentContent() {
        ProtectedReadAnswerGuard.PostconditionResult probe = containmentProbe(
                "Does the document contain a privacy policy?",
                "Private document content was read locally but withheld from model context by privacy policy.");

        assertFalse(probe.answer().startsWith("Yes."), probe.answer());
    }

    @Test
    void ordinaryFilesReadInTheSameTurnDoNotConfirmContainment() {
        String original = "Does the document contain a Stripe key?";
        TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(original));
        ToolCallLoop.LoopResult loopResult = new ToolCallLoop.LoopResult(
                "model answer",
                1,
                2,
                List.of("talos.read_file", "talos.read_file"),
                List.of(),
                0,
                0,
                false,
                0,
                List.of("README.md", ".env"),
                0,
                0,
                0,
                0,
                FailureDecision.continueLoop(),
                List.of(
                        readOutcome("talos.read_file", "README.md", "readme was read"),
                        readOutcome("talos.read_file", ".env", "approved private document was read")),
                Map.of("README.md", "1 | Set the Stripe key in .env\n", ".env", "1 | OTHER_SETTING=fake\n"));

        ProtectedReadAnswerGuard.PostconditionResult result =
                ProtectedReadAnswerGuard.enforceApprovedProtectedReadPostcondition(
                        "I cannot provide that.", loopResult, workspace, List.of(ChatMessage.user(original)));

        assertTrue(result.repaired(), result.answer());
        assertFalse(result.answer().startsWith("Yes."), result.answer());
    }

    @Test
    void protectedReadWithheldNoticeIsNotDocumentContent() {
        ProtectedReadAnswerGuard.PostconditionResult probe = containmentProbe(
                "Read .env and tell me whether it contains a privacy policy.",
                "Protected file content was read after approval but withheld from model context by privacy policy. "
                        + "Target: [protected-path]. Approval scope: LOCAL_DISPLAY_ONLY. The protected file contents "
                        + "will be read locally but withheld from model context and persisted artifacts.");

        assertFalse(probe.answer().startsWith("Yes."), probe.answer());
    }

    @Test
    void periodInsideTheAskedNameIsPartOfThePhrase() {
        assertFalse(containmentProbe(
                "Does it contain the API key for api.stripe.com?",
                "API key for api.github.com: [redacted]\n").answer().startsWith("Yes."));
        assertEquals(
                "Yes. The document contains the API key for api.stripe.com, but the raw value is not printed.",
                containmentProbe(
                        "Does it contain the API key for api.stripe.com?",
                        "API key for api.stripe.com: [redacted]\n").answer());
    }

    @Test
    void dotSlashExtractionReadIsStillAnApprovedProtectedRead() {
        String original = "Read ./report.docx and tell me whether it contains a patient name.";
        TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(original));

        ProtectedReadAnswerGuard.PostconditionResult result =
                ProtectedReadAnswerGuard.enforceApprovedProtectedReadPostcondition(
                        "I cannot provide that.",
                        loopResultWithBody(
                                readOutcome("talos.read_file", "./report.docx", "approved document was read"),
                                "report.docx",
                                DOCX_HEADER + "Patient name: [redacted-private-document-canary]\n"),
                        workspace,
                        List.of(ChatMessage.user(original)));

        assertTrue(result.repaired(), result.answer());
        assertEquals("Yes. The document contains a patient name, but the name is not printed.", result.answer());
    }

    @Test
    void longPastedPromptNeitherOverflowsNorConfirms() {
        String trace = "at com.example.Foo.bar(Foo.java:12) ".repeat(5000);
        ProtectedReadAnswerGuard.PostconditionResult probe = containmentProbe(
                "Does the document contain " + trace + "?",
                "1 | " + trace + "\n");

        assertFalse(probe.answer().startsWith("Yes."), probe.answer().lines().findFirst().orElse(""));
    }

    @Test
    void overlongAskedPhraseIsNotConfirmedFromAPrefix() {
        String phrase = "x.y ".repeat(60).strip();
        ProtectedReadAnswerGuard.PostconditionResult probe = containmentProbe(
                "Does the document contain " + phrase + "?",
                "1 | " + phrase.substring(0, 190) + "\n");

        assertFalse(probe.answer().startsWith("Yes."), probe.answer());
    }

    private static final String DOCX_HEADER = """
            Extracted document text from report.docx (status: SUCCESS)
            Warning: DOCX extraction is text-oriented; layout, comments, tracked changes, and embedded objects may be partial or omitted.
            Extractor: poi-docx 5.5.1

            """;

    private ProtectedReadAnswerGuard.PostconditionResult containmentProbe(String original, String body) {
        return containmentProbe(original, ".env", body);
    }

    private ProtectedReadAnswerGuard.PostconditionResult containmentProbe(
            String original,
            String path,
            String body
    ) {
        TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(original));
        ProtectedReadAnswerGuard.PostconditionResult result =
                ProtectedReadAnswerGuard.enforceApprovedProtectedReadPostcondition(
                        "I cannot provide that.",
                        loopResultWithBody(
                                readOutcome(
                                        "talos.read_file",
                                        path,
                                        "approved private document was read"),
                                path,
                                body),
                        workspace,
                        List.of(ChatMessage.user(original)));
        assertTrue(result.repaired(), result.answer());
        return result;
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
        return deniedReadOutcome("talos.read_file", pathHint);
    }

    private static ToolCallLoop.ToolOutcome deniedReadOutcome(String toolName, String pathHint) {
        return new ToolCallLoop.ToolOutcome(
                toolName,
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

    private static ToolCallLoop.LoopResult loopResultWithBody(
            ToolCallLoop.ToolOutcome outcome,
            String path,
            String body
    ) {
        return new ToolCallLoop.LoopResult(
                "model answer",
                1,
                1,
                List.of("talos.read_file"),
                List.of(),
                0,
                0,
                false,
                0,
                List.of(path),
                0,
                0,
                0,
                0,
                FailureDecision.continueLoop(),
                List.of(outcome),
                Map.of(path, body));
    }
}
