package dev.talos.qualification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationProviderEvidenceTest {
    @TempDir
    Path temp;

    @Test
    void providerRenderRemainsAuthoritativeWithoutFallbacks() throws Exception {
        Files.writeString(temp.resolve("prompt-debug-20260711.md"), "provider request\n",
                StandardCharsets.UTF_8);

        QualificationProviderEvidence.capture(temp, "ordinary command output");

        assertFalse(Files.exists(temp.resolve("prompt-debug.txt")));
        assertFalse(Files.exists(temp.resolve("llama.log")));
    }

    @Test
    void exactNoProviderOutputCreatesPairedBoundedNotApplicableEvidence() throws Exception {
        QualificationProviderEvidence.capture(temp,
                "i " + QualificationProviderEvidence.NO_PROVIDER_PROMPT + " More detail.");

        String prompt = Files.readString(temp.resolve("prompt-debug.txt"), StandardCharsets.UTF_8);
        String runtime = Files.readString(temp.resolve("llama.log"), StandardCharsets.UTF_8);
        assertTrue(prompt.equals(QualificationProviderEvidence.promptNotApplicableText()));
        assertTrue(runtime.equals(QualificationProviderEvidence.modelRuntimeNotApplicableText()));
        assertTrue(prompt.length() < 512);
        assertTrue(runtime.length() < 512);
    }

    @Test
    void missingOrPartialNoProviderOutputFailsClosedWithoutFallbacks() {
        assertThrows(IllegalStateException.class,
                () -> QualificationProviderEvidence.capture(temp,
                        "No provider prompt may have been sent."));
        assertFalse(Files.exists(temp.resolve("prompt-debug.txt")));
        assertFalse(Files.exists(temp.resolve("llama.log")));
    }
}
