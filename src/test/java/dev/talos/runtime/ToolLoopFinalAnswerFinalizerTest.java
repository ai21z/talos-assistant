package dev.talos.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolLoopFinalAnswerFinalizerTest {
    private static final String UNRESOLVED_CONTINUATION =
            "[Tool-call continuation could not be completed. No further tool calls were executed.]";
    private static final String ITERATION_LIMIT =
            "[Tool-call limit reached. Some tool calls were not executed.]";

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
