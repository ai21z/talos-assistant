package dev.talos.qualification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationProviderEvidenceFinalizerTest {
    private static final String SCENARIO = "F07-contradictory-do-not-change-but-edit";
    private static final int PORT = 18161;

    @TempDir
    Path temp;

    @Test
    void identityOnlyManagedStartupRetainsCanonicalNoProviderEvidence() throws Exception {
        Fixture fixture = fixture("identity-only");
        byte[] prompt = QualificationProviderEvidence.promptNotApplicableText()
                .getBytes(StandardCharsets.UTF_8);
        byte[] runtime = QualificationProviderEvidence.modelRuntimeNotApplicableText()
                .getBytes(StandardCharsets.UTF_8);
        Files.write(fixture.runRoot().resolve("prompt-debug.txt"), prompt);
        Files.write(fixture.runRoot().resolve("llama.log"), runtime);
        Files.writeString(fixture.sourceLog(), "model loaded\nlistening\nstopped\n",
                StandardCharsets.UTF_8);

        QualificationProviderEvidenceFinalizer.Result result =
                QualificationProviderEvidenceFinalizer.finalizeEvidence(
                        fixture.packetRoot(), fixture.runRoot(), SCENARIO, PORT);

        assertFalse(result.providerPromptSent());
        assertArrayEquals(prompt, Files.readAllBytes(result.promptDebug()));
        assertArrayEquals(runtime, Files.readAllBytes(result.modelLog()));
        assertTrue(Files.readString(fixture.sourceLog()).contains("model loaded"));
    }

    @Test
    void providerRenderAndModelLogAreCopiedImmutably() throws Exception {
        Fixture fixture = fixture("provider");
        Path render = fixture.runRoot().resolve("prompt-debug-20260713.md");
        Files.writeString(render, "# Provider request\n", StandardCharsets.UTF_8);
        Files.writeString(fixture.sourceLog(), "prompt eval time\neval time\n",
                StandardCharsets.UTF_8);

        QualificationProviderEvidenceFinalizer.Result result =
                QualificationProviderEvidenceFinalizer.finalizeEvidence(
                        fixture.packetRoot(), fixture.runRoot(), SCENARIO, PORT);

        assertTrue(result.providerPromptSent());
        assertArrayEquals(Files.readAllBytes(render), Files.readAllBytes(result.promptDebug()));
        assertArrayEquals(Files.readAllBytes(fixture.sourceLog()), Files.readAllBytes(result.modelLog()));
        assertThrows(IllegalStateException.class,
                () -> QualificationProviderEvidenceFinalizer.finalizeEvidence(
                        fixture.packetRoot(), fixture.runRoot(), SCENARIO, PORT));
    }

    @Test
    void partialNoProviderPairFailsClosed() throws Exception {
        Fixture fixture = fixture("partial");
        Files.writeString(fixture.runRoot().resolve("prompt-debug.txt"),
                QualificationProviderEvidence.promptNotApplicableText(), StandardCharsets.UTF_8);
        Files.writeString(fixture.sourceLog(), "model loaded\n", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class,
                () -> QualificationProviderEvidenceFinalizer.finalizeEvidence(
                        fixture.packetRoot(), fixture.runRoot(), SCENARIO, PORT));
    }

    @Test
    void malformedNoProviderPairFailsClosed() throws Exception {
        Fixture fixture = fixture("malformed");
        Files.writeString(fixture.runRoot().resolve("prompt-debug.txt"),
                QualificationProviderEvidence.promptNotApplicableText(), StandardCharsets.UTF_8);
        Files.writeString(fixture.runRoot().resolve("llama.log"),
                QualificationProviderEvidence.modelRuntimeNotApplicableText() + "forged\n",
                StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class,
                () -> QualificationProviderEvidenceFinalizer.finalizeEvidence(
                        fixture.packetRoot(), fixture.runRoot(), SCENARIO, PORT));
    }

    @Test
    void markerAndProviderRenderConflictFailsClosed() throws Exception {
        Fixture fixture = fixture("mixed");
        Files.writeString(fixture.runRoot().resolve("prompt-debug.txt"),
                QualificationProviderEvidence.promptNotApplicableText(), StandardCharsets.UTF_8);
        Files.writeString(fixture.runRoot().resolve("llama.log"),
                QualificationProviderEvidence.modelRuntimeNotApplicableText(), StandardCharsets.UTF_8);
        Files.writeString(fixture.runRoot().resolve("prompt-debug-20260713.md"),
                "# Provider request\n", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class,
                () -> QualificationProviderEvidenceFinalizer.finalizeEvidence(
                        fixture.packetRoot(), fixture.runRoot(), SCENARIO, PORT));
    }

    @Test
    void providerRenderWithoutSourceLogFailsClosed() throws Exception {
        Fixture fixture = fixture("missing-log");
        Files.writeString(fixture.runRoot().resolve("prompt-debug-20260713.md"),
                "# Provider request\n", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class,
                () -> QualificationProviderEvidenceFinalizer.finalizeEvidence(
                        fixture.packetRoot(), fixture.runRoot(), SCENARIO, PORT));
    }

    @Test
    void multipleProviderRendersFailClosed() throws Exception {
        Fixture fixture = fixture("multiple-renders");
        Files.writeString(fixture.runRoot().resolve("prompt-debug-a.md"),
                "first\n", StandardCharsets.UTF_8);
        Files.writeString(fixture.runRoot().resolve("prompt-debug-b.md"),
                "second\n", StandardCharsets.UTF_8);
        Files.writeString(fixture.sourceLog(), "prompt eval time\n", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class,
                () -> QualificationProviderEvidenceFinalizer.finalizeEvidence(
                        fixture.packetRoot(), fixture.runRoot(), SCENARIO, PORT));
    }

    @Test
    void runOutsidePacketFailsClosed() throws Exception {
        Fixture fixture = fixture("outside-base");
        Path outside = temp.resolve("outside/F07-contradictory-do-not-change-but-edit/trial-01");
        Files.createDirectories(outside);

        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderEvidenceFinalizer.finalizeEvidence(
                        fixture.packetRoot(), outside, SCENARIO, PORT));
    }

    @Test
    void mismatchedScenarioPathFailsClosed() throws Exception {
        Fixture fixture = fixture("scenario-mismatch");

        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderEvidenceFinalizer.finalizeEvidence(
                        fixture.packetRoot(), fixture.runRoot(), "F08-wrong-scenario", PORT));
    }

    private Fixture fixture(String name) throws Exception {
        Path root = temp.resolve(name);
        Path run = root.resolve("runs/granite/machine/config/" + SCENARIO + "/trial-01");
        Path source = root.resolve("home/.talos/logs/llama_cpp-" + PORT + ".log");
        Files.createDirectories(run);
        Files.createDirectories(source.getParent());
        return new Fixture(root, run, source);
    }

    private record Fixture(Path packetRoot, Path runRoot, Path sourceLog) {}
}
