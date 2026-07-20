package dev.talos.qualification;

import dev.talos.core.security.NoFollowPathPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
                finalizeEvidence(fixture);

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
                finalizeEvidence(fixture);

        assertTrue(result.providerPromptSent());
        assertArrayEquals(Files.readAllBytes(render), Files.readAllBytes(result.promptDebug()));
        assertArrayEquals(Files.readAllBytes(fixture.sourceLog()), Files.readAllBytes(result.modelLog()));
        QualificationProviderEvidenceFinalizer.Result repeated = finalizeEvidence(fixture);
        assertTrue(repeated.providerPromptSent());
        assertArrayEquals(Files.readAllBytes(render), Files.readAllBytes(repeated.promptDebug()));
        assertArrayEquals(Files.readAllBytes(fixture.sourceLog()), Files.readAllBytes(repeated.modelLog()));
    }

    @Test
    void partialNoProviderPairFailsClosed() throws Exception {
        Fixture fixture = fixture("partial");
        Files.writeString(fixture.runRoot().resolve("prompt-debug.txt"),
                QualificationProviderEvidence.promptNotApplicableText(), StandardCharsets.UTF_8);
        Files.writeString(fixture.sourceLog(), "model loaded\n", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class,
                () -> finalizeEvidence(fixture));
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
                () -> finalizeEvidence(fixture));
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
                () -> finalizeEvidence(fixture));
    }

    @Test
    void providerRenderWithoutSourceLogFailsClosed() throws Exception {
        Fixture fixture = fixture("missing-log");
        Files.writeString(fixture.runRoot().resolve("prompt-debug-20260713.md"),
                "# Provider request\n", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class,
                () -> finalizeEvidence(fixture));
    }

    @Test
    void linkedProviderRenderFailsBeforeEvidencePairPublication() throws Exception {
        Fixture fixture = fixture("linked-provider-render");
        Path external = Files.writeString(
                temp.resolve("T1170_EXTERNAL_PROVIDER_RENDER_CANARY.md"),
                "# External provider request\n",
                StandardCharsets.UTF_8);
        Path render = fixture.runRoot().resolve("prompt-debug-linked.md");
        createSymbolicLinkOrAbort(render, external);
        Files.writeString(
                fixture.sourceLog(), "prompt eval time\n", StandardCharsets.UTF_8);

        NoFollowPathPolicy.Violation failure = assertThrows(
                NoFollowPathPolicy.Violation.class,
                () -> finalizeEvidence(fixture));

        assertEquals(NoFollowPathPolicy.Reason.LINKED_COMPONENT, failure.reason());
        assertFalse(failure.getMessage().contains(external.toString()));
        assertFalse(Files.exists(fixture.runRoot().resolve("prompt-debug.txt")));
        assertFalse(Files.exists(fixture.runRoot().resolve("llama.log")));
    }

    @Test
    void linkedProviderSourceLogFailsBeforeEvidencePairPublication()
            throws Exception {
        Fixture fixture = fixture("linked-provider-source-log");
        Files.writeString(
                fixture.runRoot().resolve("prompt-debug-linked.md"),
                "# Provider request\n",
                StandardCharsets.UTF_8);
        Path external = Files.writeString(
                temp.resolve("T1170_EXTERNAL_PROVIDER_LOG_CANARY.log"),
                "prompt eval time\n",
                StandardCharsets.UTF_8);
        createSymbolicLinkOrAbort(fixture.sourceLog(), external);

        NoFollowPathPolicy.Violation failure = assertThrows(
                NoFollowPathPolicy.Violation.class,
                () -> finalizeEvidence(fixture));

        assertEquals(NoFollowPathPolicy.Reason.LINKED_COMPONENT, failure.reason());
        assertFalse(failure.getMessage().contains(external.toString()));
        assertFalse(Files.exists(fixture.runRoot().resolve("prompt-debug.txt")));
        assertFalse(Files.exists(fixture.runRoot().resolve("llama.log")));
    }

    @Test
    void secondMemberConflictNeverDeletesThePublishedProviderPrompt() throws Exception {
        Path prompt = temp.resolve("prompt-debug.txt");
        Path model = temp.resolve("llama.log");
        byte[] promptBytes = "# Provider request\n".getBytes(StandardCharsets.UTF_8);
        byte[] modelBytes = "prompt eval time\n".getBytes(StandardCharsets.UTF_8);
        Files.writeString(model, "frozen conflict\n", StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderEvidenceFinalizer.writePair(
                        dev.talos.core.security.NoFollowPathPolicy.bindExistingDirectory(temp),
                        prompt, promptBytes, model, modelBytes));

        assertArrayEquals(promptBytes, Files.readAllBytes(prompt));
        assertTrue(Files.readString(model, StandardCharsets.UTF_8)
                .equals("frozen conflict\n"));
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
                () -> finalizeEvidence(fixture));
    }

    @Test
    void runOutsidePacketFailsClosed() throws Exception {
        Fixture fixture = fixture("outside-base");
        Path outside = temp.resolve("outside/F07-contradictory-do-not-change-but-edit/trial-01");
        Files.createDirectories(outside);

        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderEvidenceFinalizer.finalizeEvidence(
                        fixture.packetRoot(), outside, SCENARIO, PORT, fixture.catalog()));
    }

    @Test
    void mismatchedScenarioPathFailsClosed() throws Exception {
        Fixture fixture = fixture("scenario-mismatch");

        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderEvidenceFinalizer.finalizeEvidence(
                        fixture.packetRoot(), fixture.runRoot(), "F08-wrong-scenario", PORT,
                        fixture.catalog()));
    }

    private Fixture fixture(String name) throws Exception {
        Path root = temp.resolve(name);
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(
                root, catalog, catalog.scenario(SCENARIO));
        Path source = root.resolve("home/.talos/logs/llama_cpp-" + PORT + ".log");
        Files.createDirectories(source.getParent());
        Files.deleteIfExists(packet.runRoot().resolve("prompt-debug.txt"));
        Files.deleteIfExists(packet.runRoot().resolve("llama.log"));
        return new Fixture(root, packet.runRoot(), source, catalog);
    }

    private static QualificationProviderEvidenceFinalizer.Result finalizeEvidence(Fixture fixture)
            throws Exception {
        return QualificationProviderEvidenceFinalizer.finalizeEvidence(
                fixture.packetRoot(), fixture.runRoot(), SCENARIO, PORT, fixture.catalog());
    }

    private static void createSymbolicLinkOrAbort(Path link, Path target)
            throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (java.io.IOException | UnsupportedOperationException unavailable) {
            org.junit.jupiter.api.Assumptions.abort(
                    "symbolic-link fixture unavailable: "
                            + unavailable.getClass().getSimpleName());
        }
    }

    private record Fixture(
            Path packetRoot,
            Path runRoot,
            Path sourceLog,
            QualificationScenarioBank.Catalog catalog) {}
}
