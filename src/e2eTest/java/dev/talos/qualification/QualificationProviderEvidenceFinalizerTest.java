package dev.talos.qualification;

import dev.talos.core.security.NoFollowPathPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
        fixture.packet().publishForTest(
                QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                java.util.List.of(
                        new QualificationPacketPublisher.Member(
                                fixture.runRoot().resolve("prompt-debug.txt"), prompt),
                        new QualificationPacketPublisher.Member(
                                fixture.runRoot().resolve("llama.log"), runtime)));
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

        QualificationPacketPublisher.PublicationException failure = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> finalizeEvidence(fixture));
        assertEquals(QualificationPacketPublisher.FailureReason.INCOMPLETE, failure.reason());
    }

    @Test
    void malformedNoProviderPairFailsClosed() throws Exception {
        Fixture fixture = fixture("malformed");
        Files.writeString(fixture.runRoot().resolve("prompt-debug.txt"),
                QualificationProviderEvidence.promptNotApplicableText(), StandardCharsets.UTF_8);
        Files.writeString(fixture.runRoot().resolve("llama.log"),
                QualificationProviderEvidence.modelRuntimeNotApplicableText() + "forged\n",
                StandardCharsets.UTF_8);

        QualificationPacketPublisher.PublicationException mixed = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> finalizeEvidence(fixture));
        assertEquals(QualificationPacketPublisher.FailureReason.INCOMPLETE, mixed.reason());
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

        QualificationPacketPublisher.PublicationException mixed = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> finalizeEvidence(fixture));
        assertEquals(QualificationPacketPublisher.FailureReason.INCOMPLETE, mixed.reason());
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
        Fixture fixture = fixture("preserved-first-member");
        Path prompt = fixture.runRoot().resolve("prompt-debug.txt");
        Path model = fixture.runRoot().resolve("llama.log");
        byte[] promptBytes = "# Provider request\n".getBytes(StandardCharsets.UTF_8);
        byte[] modelBytes = "prompt eval time\n".getBytes(StandardCharsets.UTF_8);
        Files.write(prompt, promptBytes);
        Files.writeString(model, "frozen conflict\n", StandardCharsets.UTF_8);

        QualificationPacketPublisher.PublicationException failure = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> QualificationProviderEvidenceFinalizer.writePair(
                        fixture.packetRoot(),
                        prompt, promptBytes, model, modelBytes));
        assertEquals(QualificationPacketPublisher.FailureReason.CONFLICT, failure.reason());

        assertArrayEquals(promptBytes, Files.readAllBytes(prompt));
        assertTrue(Files.readString(model, StandardCharsets.UTF_8)
                .equals("frozen conflict\n"));
    }

    @Test
    void secondMemberConflictIsDetectedBeforePublishingAMissingFirstMember() throws Exception {
        Fixture fixture = fixture("preflight-member-conflict");
        Path prompt = fixture.runRoot().resolve("prompt-debug.txt");
        Path model = fixture.runRoot().resolve("llama.log");
        byte[] promptBytes = "# Provider request\n".getBytes(StandardCharsets.UTF_8);
        byte[] modelBytes = "prompt eval time\n".getBytes(StandardCharsets.UTF_8);
        Files.writeString(model, "frozen conflict\n", StandardCharsets.UTF_8);

        QualificationPacketPublisher.PublicationException failure = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> QualificationProviderEvidenceFinalizer.writePair(
                        fixture.packetRoot(),
                        prompt, promptBytes, model, modelBytes));
        assertEquals(QualificationPacketPublisher.FailureReason.CONFLICT, failure.reason());

        assertFalse(Files.exists(prompt),
                "a known set conflict must be found before any missing member is published");
        assertEquals("frozen conflict\n", Files.readString(model, StandardCharsets.UTF_8));
    }

    @Test
    void canonicalProviderRetryRecoversPrecommitAndPostcommitInterruptions()
            throws Exception {
        for (QualificationPacketPublisher.FaultStage stage : java.util.List.of(
                QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PROMOTION,
                QualificationPacketPublisher.FaultStage.AFTER_COMMIT_VERIFICATION)) {
            Fixture fixture = fixture("provider-recovery-" + stage.name().toLowerCase());
            Path render = fixture.runRoot().resolve("prompt-debug-recovery.md");
            byte[] prompt = "# Provider recovery request\n".getBytes(StandardCharsets.UTF_8);
            byte[] model = "prompt eval time\neval time\n".getBytes(StandardCharsets.UTF_8);
            Files.write(render, prompt);
            Files.write(fixture.sourceLog(), model);
            int ordinal = stage == QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PROMOTION
                    ? 0 : -1;

            IOException failure = assertThrows(
                    IOException.class,
                    () -> QualificationPacketPublisher.withLease(
                            fixture.packetRoot(),
                            java.time.Duration.ofSeconds(5),
                            (actual, member) -> {
                                if (actual == stage && member == ordinal) {
                                    throw new IOException("injected provider publication fault");
                                }
                            },
                            lease -> lease.publish(
                                    QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                                    java.util.List.of(
                                            new QualificationPacketPublisher.Member(
                                                    fixture.runRoot().resolve("prompt-debug.txt"),
                                                    prompt),
                                            new QualificationPacketPublisher.Member(
                                                    fixture.runRoot().resolve("llama.log"),
                                                    model)))));
            if (stage == QualificationPacketPublisher.FaultStage.AFTER_COMMIT_VERIFICATION) {
                QualificationPacketPublisher.PublicationException publicationFailure =
                        (QualificationPacketPublisher.PublicationException) failure;
                assertEquals(
                        QualificationPacketPublisher.CommitState.COMMITTED,
                        publicationFailure.commitState());
            }

            QualificationProviderEvidenceFinalizer.Result recovered = finalizeEvidence(fixture);

            assertTrue(recovered.providerPromptSent());
            assertArrayEquals(prompt, Files.readAllBytes(recovered.promptDebug()));
            assertArrayEquals(model, Files.readAllBytes(recovered.modelLog()));
            QualificationPacketPublisher.requireSettledControlState(fixture.packetRoot());
        }
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
        packet.removePublicationForTest(
                QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION,
                java.util.List.of(
                        packet.runRoot().resolve(QualificationCausalFinalizer.OUTPUT_FILE),
                        packet.runRoot().resolve(QualificationRunFinalizer.CANARY_FILE),
                        packet.runRoot().resolve(QualificationRunFinalizer.RESULT_FILE)));
        packet.removePublicationForTest(
                QualificationPacketPublisher.PublicationKind.MECHANICAL_REQUIREMENTS,
                java.util.List.of(packet.runRoot().resolve(
                        QualificationRequirementFinalizer.OUTPUT_FILE)));
        packet.removePublicationForTest(
                QualificationPacketPublisher.PublicationKind.APPROVAL_EVIDENCE,
                java.util.List.of(packet.runRoot().resolve(
                        QualificationApprovalFinalizer.OUTPUT_FILE)));
        packet.removePublicationForTest(
                QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                java.util.List.of(
                        packet.runRoot().resolve("prompt-debug.txt"),
                        packet.runRoot().resolve("llama.log")));
        return new Fixture(packet, root, packet.runRoot(), source, catalog);
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
            QualificationTestPacket packet,
            Path packetRoot,
            Path runRoot,
            Path sourceLog,
            QualificationScenarioBank.Catalog catalog) {}
}
