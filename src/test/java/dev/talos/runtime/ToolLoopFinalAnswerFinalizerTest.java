package dev.talos.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

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
    void toolCallLoopDelegatesFinalAnswerFinalizationToOwner() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/talos/runtime/ToolCallLoop.java"));

        assertTrue(source.contains("ToolLoopFinalAnswerFinalizer.withIterationLimitNotice"), source);
        assertTrue(source.contains("ToolLoopFinalAnswerFinalizer.finalizeAnswer"), source);
        assertFalse(source.contains("private static String finalizeAnswer"), source);
        assertFalse(source.contains("ProtectedContentPolicy.sanitizeText"), source);
        assertFalse(source.contains("Sanitize.stripSuspiciousHtml"), source);
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
}
