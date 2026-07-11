package dev.talos.qualification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationPromptDebugEvidenceTest {
    @TempDir
    Path temp;

    @Test
    void providerRenderRemainsAuthoritativeWithoutFallback() throws Exception {
        Files.writeString(temp.resolve("prompt-debug-20260711.md"), "provider request\n",
                StandardCharsets.UTF_8);

        QualificationPromptDebugEvidence.capture(temp, "ordinary command output");

        assertFalse(Files.exists(temp.resolve("prompt-debug.txt")));
    }

    @Test
    void exactNoProviderOutputCreatesBoundedNotApplicableEvidence() throws Exception {
        QualificationPromptDebugEvidence.capture(temp,
                "i " + QualificationPromptDebugEvidence.NO_PROVIDER_PROMPT + " More detail.");

        String evidence = Files.readString(temp.resolve("prompt-debug.txt"), StandardCharsets.UTF_8);
        assertTrue(evidence.startsWith(QualificationPromptDebugEvidence.NOT_APPLICABLE_HEADER));
        assertTrue(evidence.contains(QualificationPromptDebugEvidence.NO_PROVIDER_PROMPT));
        assertTrue(evidence.length() < 512);
    }

    @Test
    void missingOrPartialNoProviderOutputFailsClosed() {
        assertThrows(IllegalStateException.class,
                () -> QualificationPromptDebugEvidence.capture(temp,
                        "No provider prompt may have been sent."));
    }
}
