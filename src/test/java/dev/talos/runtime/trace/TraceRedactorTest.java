package dev.talos.runtime.trace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceRedactorTest {
    @Test
    void redactsSecretLikeKeyValueAssignments() {
        String input = "SECRET=changed TOKEN=abc API_KEY=key PASSWORD=pw CREDENTIAL=cred";

        String redacted = TraceRedactor.redactSecretLikeAssignments(input);

        assertEquals(
                "SECRET=[redacted] TOKEN=[redacted] API_KEY=[redacted] PASSWORD=[redacted] CREDENTIAL=[redacted]",
                redacted);
    }

    @Test
    void preservesNonSecretPromptContext() {
        String input = "Overwrite .env with SECRET=changed. Use talos.write_file.";

        String redacted = TraceRedactor.redactSecretLikeAssignments(input);

        assertEquals("Overwrite .env with SECRET=[redacted]. Use talos.write_file.", redacted);
    }

    @Test
    void redactsSameMessageEchoOfSecretValue() {
        String input = """
                The .env file contains:
                TALOS_T61E_LLAMA_CPP_SECRET=must-not-leak

                This indicates the variable is set to `must-not-leak`.
                """;

        String redacted = TraceRedactor.redactSecretLikeAssignments(input);

        assertEquals("""
                The .env file contains:
                TALOS_T61E_LLAMA_CPP_SECRET=[redacted]

                This indicates the variable is set to `[redacted]`.
                """, redacted);
    }

    @Test
    void redactsT267CanariesAndPrivateMarkers() {
        String input = """
                PRIVATE_MARKER = DO_NOT_LEAK_T267_PRIVATE_MARKER
                raw marker: DO_NOT_LEAK_T267_TRACE
                provider canary: TALOS_CANARY_T267_PROVIDER_BODY
                ordinary value: Aster-7
                """;

        String redacted = TraceRedactor.redactSecretLikeAssignments(input);

        assertFalse(redacted.contains("DO_NOT_LEAK_T267_PRIVATE_MARKER"));
        assertFalse(redacted.contains("DO_NOT_LEAK_T267_TRACE"));
        assertFalse(redacted.contains("TALOS_CANARY_T267_PROVIDER_BODY"));
        assertTrue(redacted.contains("PRIVATE_MARKER=[redacted]"));
        assertTrue(redacted.contains("Aster-7"));
    }

    @Test
    void redactsBareSecretShapes() {
        String token = "sk-ant-abcdefghijklmnopqrstuvwxyz1234567890";

        String redacted = TraceRedactor.redactSecretLikeAssignments("tool output contained " + token);

        assertFalse(redacted.contains(token), redacted);
        assertTrue(redacted.contains("[redacted]"), redacted);
    }

    @Test
    void redactsPrivateDocumentFactCanaries() {
        String input = """
                Patient Name: Eleni Nikolaou
                Address: 42 Fictional Street, Athens
                Invoice Total: 1837.42 EUR
                ordinary workspace fact: Aster-7
                """;

        String redacted = TraceRedactor.redactSecretLikeAssignments(input);

        assertFalse(redacted.contains("Eleni Nikolaou"), redacted);
        assertFalse(redacted.contains("42 Fictional Street"), redacted);
        assertFalse(redacted.contains("1837.42 EUR"), redacted);
        assertTrue(redacted.contains("[redacted-private-document-canary]"), redacted);
        assertTrue(redacted.contains("Aster-7"), redacted);
    }

    @Test
    void detectsDotEnvProtectedReadRequests() {
        assertTrue(TraceRedactor.looksLikeProtectedReadRequest(
                "Read .env and tell me the value inside."));
    }

    @Test
    void detectsPolicyProtectedPathReadRequests() {
        assertTrue(TraceRedactor.looksLikeProtectedReadRequest(
                "Read protected/private-notes.md and summarize it."));
        assertTrue(TraceRedactor.looksLikeProtectedReadRequest(
                "Show .github/workflows/deploy.yml."));
    }

    @Test
    void normalReadmeQuestionsAboutSecretsAreNotProtectedReads() {
        assertFalse(TraceRedactor.looksLikeProtectedReadRequest(
                "Read README.md and tell me how it describes secret handling."));
    }
}
