package dev.talos.runtime;

import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.RecordedMixedCodeAnswer;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.runtime.trace.PromptAuditRedactor;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ProviderResponseCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolLoopFinalAnswerFinalizerTest {
    @TempDir Path sessions;
    private static final String UNRESOLVED_CONTINUATION =
            "[Tool-call continuation could not be completed. No further tool calls were executed.]";
    private static final String ITERATION_LIMIT =
            "[Tool-call limit reached. Some tool calls were not executed.]";
    private static final String FENCED_ANSWER =
            "Use `#calculate`:\n```html\n<button id=\"calculate\">Calculate</button>\n```\nNo edits.";

    @BeforeEach
    void beginProviderCapture() {
        ProviderResponseCapture.begin(new ProviderResponseCapture.Listener() {
            @Override public ProviderResponseCapture.Receipt responseReturned(
                    ProviderResponseCapture.AttemptResponse event) {
                return event.receipt();
            }
        });
    }

    @AfterEach
    void clearProviderCapture() {
        ProviderResponseCapture.clear();
    }

    @Test
    void selectedExactProviderEosPreservesTheSameBoundedFencedLiteral() {
        LlmClient.StreamResult response = providerAnswer(FENCED_ANSWER, "stop");
        assertEquals(FENCED_ANSWER, response.text());
        assertTrue(response.provenance().providerTerminalEligible());
        assertFalse(response.provenance().providerToolCallEligible());
        assertFalse(response.normalized());
        String answer = ToolLoopFinalAnswerFinalizer.finalizeAnswer(
                response.text(), 4, false, List.of(), response.provenance());
        assertEquals(FENCED_ANSWER, answer);
        assertEquals(response.provenance().assistantHash(), PromptAuditRedactor.hash(answer));
        assertFalse(ToolLoopFinalAnswerFinalizer.finalizeAnswer(FENCED_ANSWER, 4, false)
                .contains("<button"), "legacy callers do not acquire a provider waiver");
    }

    @Test
    void changedTextAbsentAndDetachedReceiptsCannotPreserveFencedHtml() {
        LlmClient.StreamResult response = providerAnswer(FENCED_ANSWER, "stop");
        var selected = response.provenance();
        var detached = new ProviderResponseCapture.Receipt(selected.generationId(), selected.source(),
                selected.completion(), true, false, selected.assistantHash());
        assertFalse(detached.providerTerminalEligible());
        for (var receipt : List.of(ProviderResponseCapture.Receipt.none(), detached)) {
            assertFalse(ToolLoopFinalAnswerFinalizer.finalizeAnswer(
                    FENCED_ANSWER, 4, false, List.of(), receipt).contains("<button"));
        }
        assertFalse(ToolLoopFinalAnswerFinalizer.finalizeAnswer(
                FENCED_ANSWER, 4, false, List.of(), null).contains("<button"));
        assertFalse(ToolLoopFinalAnswerFinalizer.finalizeAnswer(
                FENCED_ANSWER + " Altered.", 4, false, List.of(), selected).contains("<button"));
    }

    @Test
    void nonEosProviderReceiptsDoNotGainFinalizerPreservation() {
        for (String reason : List.of("length", "content_filter")) {
            LlmClient.StreamResult response = providerAnswer(FENCED_ANSWER, reason);
            assertFalse(response.provenance().providerTerminalEligible());
            assertFalse(ToolLoopFinalAnswerFinalizer.finalizeAnswer(
                    FENCED_ANSWER, 4, false, List.of(), response.provenance()).contains("<button"));
        }
    }

    @Test
    void hiddenActiveAndControlNormalizationCannotBeLaunderedThroughAReceipt() {
        for (String source : List.of(
                FENCED_ANSWER + "<think>hidden</think>",
                FENCED_ANSWER + "\u0001control",
                "<button onclick=\"run()\">Run</button>\nDone.")) {
            LlmClient.StreamResult response = providerAnswer(source, "stop");
            assertTrue(response.normalized());
            assertFalse(response.provenance().providerTerminalEligible());
            String answer = ToolLoopFinalAnswerFinalizer.finalizeAnswer(
                    source, 4, false, List.of(), response.provenance());
            assertFalse(answer.contains("<button"));
            assertFalse(answer.contains("onclick"));
        }
    }

    @Test
    void completeRecordedMixedAnswerRetainsExactProviderIdentityThroughBothPersistenceForms() {
        String source = RecordedMixedCodeAnswer.text();
        LlmClient.StreamResult response = providerAnswer(source, "stop");
        assertEquals(source, response.text());
        assertFalse(response.normalized());
        assertTrue(response.provenance().providerTerminalEligible());
        assertFalse(response.provenance().providerToolCallEligible());
        String answer = ToolLoopFinalAnswerFinalizer.finalizeAnswer(
                response.text(), 4, false, List.of(), response.provenance());
        assertEquals(source, answer);
        assertEquals(response.provenance().assistantHash(), PromptAuditRedactor.hash(answer));
        try (JsonSessionStore store = new JsonSessionStore(sessions)) {
            String id = "mixed-code-fidelity";
            var row = new TurnRecord(1, Instant.EPOCH, 0L, "Explain the four files", answer,
                    List.of(), 0, 0, 0, "", "ok");
            var snapshot = new SessionData(id, sessions.toString(), "", 1, Instant.EPOCH,
                    List.of(new SessionData.Turn("assistant", answer, "ok")), "scripted-test");
            try (var writer = store.acquireSessionWriter(id)) {
                writer.appendTurn(id, row);
                writer.save(snapshot);
            }
            assertEquals(answer, store.loadTurns(id).getFirst().assistantText());
            assertEquals(answer, store.load(id).orElseThrow().turns().getFirst().content());
        }
    }

    @Test
    void toolStrippingLayoutChangesDoNotGetAnExactProviderWaiver() {
        String source = FENCED_ANSWER + "\n\n\n";
        LlmClient.StreamResult response = providerAnswer(source, "stop");
        assertEquals(source, response.text());
        assertTrue(response.provenance().providerTerminalEligible());
        assertNotEquals(source, ToolCallParser.stripToolCalls(source));
        assertFalse(ToolLoopFinalAnswerFinalizer.finalizeAnswer(
                source, 4, false, List.of(), response.provenance()).contains("<button"));
    }

    @Test
    void exactProviderPreservationStillAppliesPrivacyRedactionAndRuntimeNotices() {
        String source = FENCED_ANSWER + "\nPatient Name: Eleni Nikolaou";
        LlmClient.StreamResult response = providerAnswer(source, "stop");
        assertTrue(response.provenance().providerTerminalEligible());
        String notice = "Private content was withheld. Patient Name: Eleni Nikolaou";
        String answer = ToolLoopFinalAnswerFinalizer.finalizeAnswer(
                source, 4, true, List.of(notice, notice), response.provenance());
        assertTrue(answer.contains("<button"), "inert code remains an exact presentation fragment");
        assertFalse(answer.contains("Eleni Nikolaou"));
        assertTrue(answer.contains("[redacted-private-document-canary]"));
        assertEquals(1, countOccurrences(answer, "Private content was withheld."));
        assertNotEquals(response.provenance().assistantHash(), PromptAuditRedactor.hash(answer),
                "privacy/notice changes cannot claim the original whole-answer byte identity");
    }

    @Test
    void providerReceiptCannotBypassUnfinishedToolSuppression() {
        var receipt = providerAnswer(FENCED_ANSWER, "stop").provenance();
        assertTrue(receipt.providerTerminalEligible());
        assertEquals(UNRESOLVED_CONTINUATION, ToolLoopFinalAnswerFinalizer.finalizeAnswer(
                "{\"name\":\"talos.read_file\",\"arguments\":{", 4, false, List.of(), receipt));
    }

    @Test
    void normalTextPassesThroughUnchanged() {
        assertEquals(
                "Just a normal answer.",
                ToolLoopFinalAnswerFinalizer.finalizeAnswer("Just a normal answer.", 0, false));
    }

    @Test
    void nullTextFinalizesToEmptyText() {
        assertEquals("", ToolLoopFinalAnswerFinalizer.finalizeAnswer(null, 0, false));
    }

    @Test
    void finalAnswerStripsToolCallBlocks() {
        String answer = ToolLoopFinalAnswerFinalizer.finalizeAnswer("""
                Before.
                <tool_call>{"name":"talos.read_file","parameters":{"path":"README.md"}}</tool_call>
                After.
                """, 0, false);

        assertTrue(answer.contains("Before."));
        assertTrue(answer.contains("After."));
        assertFalse(answer.contains("tool_call"), answer);
        assertFalse(answer.contains("talos.read_file"), answer);
    }

    @Test
    void finalAnswerStripsSuspiciousHtmlFromProse() {
        String answer = ToolLoopFinalAnswerFinalizer.finalizeAnswer(
                "Safe before. <script>evil()</script> Safe after.",
                0,
                false);

        assertEquals("Safe before.  Safe after.", answer);
    }

    @Test
    void unfinishedToolPayloadAfterToolUseReturnsTruthfulFallback() {
        String answer = ToolLoopFinalAnswerFinalizer.finalizeAnswer("""
                {
                  "name": "talos.grep",
                  "arguments": {
                """, 1, false);

        assertEquals(UNRESOLVED_CONTINUATION, answer);
    }

    @Test
    void unfinishedLookingToolPayloadWithoutToolUseDoesNotUseContinuationFallback() {
        String answer = ToolLoopFinalAnswerFinalizer.finalizeAnswer("""
                {
                  "name": "talos.grep",
                  "arguments": {
                """, 0, false);

        assertNotEquals(UNRESOLVED_CONTINUATION, answer);
    }

    @Test
    void iterationLimitNoticeStripsToolCallsAndAppendsExactWarning() {
        String answer = ToolLoopFinalAnswerFinalizer.withIterationLimitNotice("""
                I am trying again.
                <tool_call>{"name":"talos.grep","parameters":{"pattern":"TODO"}}</tool_call>
                """);

        assertTrue(answer.contains("I am trying again."));
        assertFalse(answer.contains("tool_call"), answer);
        assertFalse(answer.contains("talos.grep"), answer);
        assertTrue(answer.endsWith("\n\n" + ITERATION_LIMIT), answer);
    }

    @Test
    void contentWithheldFinalAnswerRedactsPrivateDocumentCanaries() {
        String raw = privateDocumentCanary();

        String answer = ToolLoopFinalAnswerFinalizer.finalizeAnswer(raw, 0, true);

        assertFalse(answer.contains("Eleni Nikolaou"), answer);
        assertFalse(answer.contains("42 Fictional Street"), answer);
        assertFalse(answer.contains("fictional-condition-alpha"), answer);
        assertFalse(answer.contains("EL-TAX-483920"), answer);
        assertFalse(answer.contains("1837.42 EUR"), answer);
        assertTrue(answer.contains("[redacted-private-document-canary]"), answer);
    }

    @Test
    void contentWithheldFinalAnswerAddsRuntimePrivacyNoticeWhenModelParaphrases() {
        String answer = ToolLoopFinalAnswerFinalizer.finalizeAnswer(
                "No protected file content was shown.",
                1,
                true,
                List.of("Private document content was read locally but withheld from model context by privacy policy."));

        assertTrue(answer.contains(
                "Private document content was read locally but withheld from model context by privacy policy."),
                answer);
        assertTrue(answer.contains("No protected file content was shown."), answer);
    }

    @Test
    void contentWithheldFinalAnswerDoesNotDuplicateExistingRuntimePrivacyNotice() {
        String notice = "Private document content was read locally but withheld from model context by privacy policy.";

        String answer = ToolLoopFinalAnswerFinalizer.finalizeAnswer(
                notice + "\n\nNo protected file content was shown.",
                1,
                true,
                List.of(notice));

        assertEquals(1, countOccurrences(answer, notice), answer);
    }

    @Test
    void runtimePrivacyNoticeIsSanitizedBeforeRendering() {
        String answer = ToolLoopFinalAnswerFinalizer.finalizeAnswer(
                "No protected file content was shown.",
                1,
                true,
                List.of("Private document content was read locally but withheld from model context by privacy policy. "
                        + "Patient Name: Eleni Nikolaou"));

        assertTrue(answer.contains("withheld from model context"), answer);
        assertFalse(answer.contains("Eleni Nikolaou"), answer);
        assertTrue(answer.contains("[redacted-private-document-canary]"), answer);
    }

    @Test
    void contentNotWithheldDoesNotApplyProtectedContentRedactionInFinalizer() {
        String raw = privateDocumentCanary();

        String answer = ToolLoopFinalAnswerFinalizer.finalizeAnswer(raw, 0, false);

        assertTrue(answer.contains("Eleni Nikolaou"), answer);
        assertTrue(answer.contains("42 Fictional Street"), answer);
        assertTrue(answer.contains("fictional-condition-alpha"), answer);
        assertTrue(answer.contains("EL-TAX-483920"), answer);
        assertTrue(answer.contains("1837.42 EUR"), answer);
        assertFalse(answer.contains("[redacted-private-document-canary]"), answer);
    }

    @Test
    void toolCallLoopEngineDelegatesFinalAnswerFinalizationToOwner() throws Exception {
        String facadeSource = Files.readString(Path.of("src/main/java/dev/talos/runtime/ToolCallLoop.java"));
        String engineSource = Files.readString(Path.of("src/main/java/dev/talos/runtime/ToolCallLoopEngine.java"));

        assertTrue(engineSource.contains("ToolLoopFinalAnswerFinalizer.withIterationLimitNotice"), engineSource);
        assertTrue(engineSource.contains("ToolLoopFinalAnswerFinalizer.finalizeAnswer"), engineSource);
        assertFalse(facadeSource.contains("private static String finalizeAnswer"), facadeSource);
        assertFalse(engineSource.contains("private static String finalizeAnswer"), engineSource);
        assertFalse(facadeSource.contains("ProtectedContentPolicy.sanitizeText"), facadeSource);
        assertFalse(engineSource.contains("ProtectedContentPolicy.sanitizeText"), engineSource);
        assertFalse(facadeSource.contains("Sanitize.stripSuspiciousHtml"), facadeSource);
        assertFalse(engineSource.contains("Sanitize.stripSuspiciousHtml"), engineSource);
    }

    private static LlmClient.StreamResult providerAnswer(String source, String finishReason) {
        try (LlmClient client = ScriptedNativeLlmClient.of(
                List.of(new LlmClient.StreamResult(source, List.of(), finishReason)))) {
            return client.chatFull(List.of(ChatMessage.user("explain without tools")), List.of(),
                    ChatRequestControls.defaults().withSingleAttempt(true));
        }
    }

    private static String privateDocumentCanary() {
        return """
                Patient Name: Eleni Nikolaou
                Address: 42 Fictional Street, Athens
                Diagnosis: fictional-condition-alpha
                Tax ID: EL-TAX-483920
                Invoice Total: 1837.42 EUR
                """;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
