package dev.talos.runtime.trace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
