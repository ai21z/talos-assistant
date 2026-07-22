package dev.talos.qualification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QualificationRunPublicationReconcilerTest {
    @TempDir
    Path temp;

    @Test
    void recoversTheMechanicallySelectedProviderPhase() throws Exception {
        Fixture fixture = fixture("provider");
        fixture.removeAfter(QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE);
        byte[] prompt = QualificationProviderEvidence.promptNotApplicableText()
                .getBytes(StandardCharsets.UTF_8);
        byte[] model = QualificationProviderEvidence.modelRuntimeNotApplicableText()
                .getBytes(StandardCharsets.UTF_8);
        interrupt(
                fixture,
                QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                List.of(
                        new QualificationPacketPublisher.Member(
                                fixture.runRoot().resolve("prompt-debug.txt"), prompt),
                        new QualificationPacketPublisher.Member(
                                fixture.runRoot().resolve("llama.log"), model)),
                1);

        assertRecovered(
                fixture, QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE);
    }

    @Test
    void recoversTheMechanicallySelectedApprovalPhase() throws Exception {
        Fixture fixture = fixture("approval");
        byte[] approval = Files.readAllBytes(
                fixture.runRoot().resolve(QualificationApprovalFinalizer.OUTPUT_FILE));
        fixture.removeAfter(QualificationPacketPublisher.PublicationKind.APPROVAL_EVIDENCE);
        interrupt(
                fixture,
                QualificationPacketPublisher.PublicationKind.APPROVAL_EVIDENCE,
                List.of(new QualificationPacketPublisher.Member(
                        fixture.runRoot().resolve(QualificationApprovalFinalizer.OUTPUT_FILE),
                        approval)),
                0);

        assertRecovered(
                fixture, QualificationPacketPublisher.PublicationKind.APPROVAL_EVIDENCE);
    }

    @Test
    void recoversTheMechanicallySelectedRequirementsPhase() throws Exception {
        Fixture fixture = fixture("requirements");
        byte[] requirements = Files.readAllBytes(
                fixture.runRoot().resolve(QualificationRequirementFinalizer.OUTPUT_FILE));
        fixture.removeAfter(QualificationPacketPublisher.PublicationKind.MECHANICAL_REQUIREMENTS);
        interrupt(
                fixture,
                QualificationPacketPublisher.PublicationKind.MECHANICAL_REQUIREMENTS,
                List.of(new QualificationPacketPublisher.Member(
                        fixture.runRoot().resolve(QualificationRequirementFinalizer.OUTPUT_FILE),
                        requirements)),
                0);

        assertRecovered(
                fixture, QualificationPacketPublisher.PublicationKind.MECHANICAL_REQUIREMENTS);
    }

    @Test
    void recoversTheMechanicallySelectedRunFinalizationPhase() throws Exception {
        Fixture fixture = fixture("run");
        List<QualificationPacketPublisher.Member> authority = List.of(
                member(fixture.runRoot().resolve(QualificationCausalFinalizer.OUTPUT_FILE)),
                member(fixture.runRoot().resolve(QualificationRunFinalizer.CANARY_FILE)),
                member(fixture.runRoot().resolve(QualificationRunFinalizer.RESULT_FILE)));
        fixture.removeAfter(QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION);
        interrupt(
                fixture,
                QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION,
                authority,
                2);

        assertRecovered(
                fixture, QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION);
    }

    @Test
    void changedInputsCannotRewriteOrCleanAnInterruptedPhase() throws Exception {
        Fixture fixture = fixture("changed-input");
        byte[] requirements = Files.readAllBytes(
                fixture.runRoot().resolve(QualificationRequirementFinalizer.OUTPUT_FILE));
        fixture.removeAfter(QualificationPacketPublisher.PublicationKind.MECHANICAL_REQUIREMENTS);
        interrupt(
                fixture,
                QualificationPacketPublisher.PublicationKind.MECHANICAL_REQUIREMENTS,
                List.of(new QualificationPacketPublisher.Member(
                        fixture.runRoot().resolve(QualificationRequirementFinalizer.OUTPUT_FILE),
                        requirements)),
                0);
        Files.writeString(
                fixture.runRoot().resolve("answer.txt"),
                "changed after interrupted publication\n",
                StandardCharsets.UTF_8);
        java.util.Map<String, String> before = treeState(fixture.packet().root());

        IOException failure = assertThrows(IOException.class, () ->
                QualificationRunPublicationReconciler.reconcile(
                        fixture.packet().root(),
                        fixture.runRoot(),
                        fixture.scenario().id(),
                        18_161,
                        fixture.catalog()));

        assertTrue(failure.getMessage().contains("conflict")
                        || failure.getMessage().contains("recovery"),
                failure::getMessage);
        assertEquals(before, treeState(fixture.packet().root()));
    }

    @Test
    void unsupportedActivePublicationCannotBeAdoptedByTheRunReconciler()
            throws Exception {
        Fixture fixture = fixture("unsupported-kind");
        Path capture = fixture.runRoot().resolve("transcript.raw.json");
        byte[] captureBytes = Files.readAllBytes(capture);
        fixture.packet().removePublicationForTest(
                QualificationPacketPublisher.PublicationKind.PTY_CAPTURE,
                List.of(capture));
        interrupt(
                fixture,
                QualificationPacketPublisher.PublicationKind.PTY_CAPTURE,
                List.of(new QualificationPacketPublisher.Member(capture, captureBytes)),
                0);
        java.util.Map<String, String> before = treeState(fixture.packet().root());

        QualificationPacketPublisher.PublicationException failure = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> QualificationRunPublicationReconciler.reconcile(
                        fixture.packet().root(),
                        fixture.runRoot(),
                        fixture.scenario().id(),
                        18_161,
                        fixture.catalog()));

        assertEquals(QualificationPacketPublisher.FailureReason.INCOMPLETE,
                failure.reason());
        assertTrue(failure.getMessage().contains("unsupported incomplete transaction"),
                failure::getMessage);
        assertEquals(before, treeState(fixture.packet().root()));
    }

    @Test
    void providerRecoveryRequiresTheOriginalEligiblePortWithoutMutation()
            throws Exception {
        Fixture fixture = fixture("provider-port");
        fixture.removeAfter(QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE);
        byte[] prompt = QualificationProviderEvidence.promptNotApplicableText()
                .getBytes(StandardCharsets.UTF_8);
        byte[] model = QualificationProviderEvidence.modelRuntimeNotApplicableText()
                .getBytes(StandardCharsets.UTF_8);
        interrupt(
                fixture,
                QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                List.of(
                        new QualificationPacketPublisher.Member(
                                fixture.runRoot().resolve("prompt-debug.txt"), prompt),
                        new QualificationPacketPublisher.Member(
                                fixture.runRoot().resolve("llama.log"), model)),
                1);
        java.util.Map<String, String> before = treeState(fixture.packet().root());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> QualificationRunPublicationReconciler.reconcile(
                        fixture.packet().root(),
                        fixture.runRoot(),
                        fixture.scenario().id(),
                        0,
                        fixture.catalog()));

        assertTrue(failure.getMessage().contains("server port"), failure::getMessage);
        assertEquals(before, treeState(fixture.packet().root()));
    }

    @Test
    void settledProviderPhaseVerifiesAsCommittedWithoutAServerPort()
            throws Exception {
        Fixture fixture = fixture("provider-committed-without-port");
        fixture.removeAfter(QualificationPacketPublisher.PublicationKind.APPROVAL_EVIDENCE);
        java.util.Map<String, String> before = treeState(fixture.packet().root());

        QualificationRunPublicationReconciler.Result result =
                QualificationRunPublicationReconciler.reconcile(
                        fixture.packet().root(),
                        fixture.runRoot(),
                        fixture.scenario().id(),
                        -1,
                        fixture.catalog());

        assertEquals(QualificationRunPublicationReconciler.State.COMMITTED, result.state());
        assertEquals(QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                result.publicationKind());
        assertEquals(before, treeState(fixture.packet().root()));
    }

    @Test
    void recoverableProviderRejectsADifferentValidPortWithoutMutation()
            throws Exception {
        Fixture fixture = fixture("provider-wrong-valid-port");
        fixture.removeAfter(QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE);
        byte[] prompt = QualificationProviderEvidence.promptNotApplicableText()
                .getBytes(StandardCharsets.UTF_8);
        byte[] model = QualificationProviderEvidence.modelRuntimeNotApplicableText()
                .getBytes(StandardCharsets.UTF_8);
        interrupt(
                fixture,
                QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                List.of(
                        new QualificationPacketPublisher.Member(
                                fixture.runRoot().resolve("prompt-debug.txt"), prompt),
                        new QualificationPacketPublisher.Member(
                                fixture.runRoot().resolve("llama.log"), model)),
                1);
        java.util.Map<String, String> before = treeState(fixture.packet().root());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> QualificationRunPublicationReconciler.reconcile(
                        fixture.packet().root(),
                        fixture.runRoot(),
                        fixture.scenario().id(),
                        18_162,
                        fixture.catalog()));

        assertTrue(failure.getMessage().contains("frozen qualification calibration"),
                failure::getMessage);
        assertEquals(before, treeState(fixture.packet().root()));
    }

    @Test
    void committedPhaseGapIsRejectedWithoutReconstructingHistory() throws Exception {
        Fixture fixture = fixture("committed-gap");
        fixture.packet().removePublicationForTest(
                QualificationPacketPublisher.PublicationKind.MECHANICAL_REQUIREMENTS,
                List.of(fixture.runRoot().resolve(
                        QualificationRequirementFinalizer.OUTPUT_FILE)));
        java.util.Map<String, String> before = treeState(fixture.packet().root());

        QualificationPacketPublisher.PublicationException failure = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> QualificationRunPublicationReconciler.reconcile(
                        fixture.packet().root(),
                        fixture.runRoot(),
                        fixture.scenario().id(),
                        18_161,
                        fixture.catalog()));

        assertEquals(QualificationPacketPublisher.FailureReason.INVALID_COMMIT,
                failure.reason());
        assertTrue(failure.getMessage().contains("predecessor"), failure::getMessage);
        assertEquals(before, treeState(fixture.packet().root()));
    }

    private Fixture fixture(String name) throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve(name), catalog, scenario);
        return new Fixture(packet, catalog, scenario);
    }

    private static void interrupt(
            Fixture fixture,
            QualificationPacketPublisher.PublicationKind kind,
            List<QualificationPacketPublisher.Member> members,
            int finalOrdinal) {
        assertThrows(IOException.class, () -> QualificationPacketPublisher.withLease(
                fixture.packet().root(),
                Duration.ofSeconds(5),
                (stage, ordinal) -> {
                    if (stage == QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PROMOTION
                            && ordinal == finalOrdinal) {
                        throw new IOException("injected canonical phase interruption");
                    }
                },
                lease -> lease.publish(kind, members)));
    }

    private static void assertRecovered(
            Fixture fixture,
            QualificationPacketPublisher.PublicationKind expected) throws Exception {
        QualificationRunPublicationReconciler.Result recovered =
                QualificationRunPublicationReconciler.reconcile(
                        fixture.packet().root(),
                        fixture.runRoot(),
                        fixture.scenario().id(),
                        18_161,
                        fixture.catalog());
        assertEquals(QualificationRunPublicationReconciler.State.RECOVERED, recovered.state());
        assertEquals(expected, recovered.publicationKind());

        QualificationRunPublicationReconciler.Result committed =
                QualificationRunPublicationReconciler.reconcile(
                        fixture.packet().root(),
                        fixture.runRoot(),
                        fixture.scenario().id(),
                        18_161,
                        fixture.catalog());
        assertEquals(QualificationRunPublicationReconciler.State.COMMITTED, committed.state());
        assertEquals(expected, committed.publicationKind());
        QualificationPacketPublisher.requireSettledControlState(fixture.packet().root());
    }

    private static QualificationPacketPublisher.Member member(Path path) throws IOException {
        return new QualificationPacketPublisher.Member(path, Files.readAllBytes(path));
    }

    private static java.util.Map<String, String> treeState(Path root) throws IOException {
        java.util.Map<String, String> state = new java.util.TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    state.put(relative, "directory");
                } else {
                    state.put(
                            relative,
                            QualificationScenarioBank.sha256(Files.readAllBytes(path))
                                    + ":" + Files.getLastModifiedTime(path).toMillis());
                }
            }
        }
        return java.util.Map.copyOf(state);
    }

    private record Fixture(
            QualificationTestPacket packet,
            QualificationScenarioBank.Catalog catalog,
            QualificationProtocol.Scenario scenario) {
        Path runRoot() {
            return packet.runRoot();
        }

        void removeAfter(QualificationPacketPublisher.PublicationKind phase) throws IOException {
            remove(
                    QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION,
                    List.of(
                            runRoot().resolve(QualificationCausalFinalizer.OUTPUT_FILE),
                            runRoot().resolve(QualificationRunFinalizer.CANARY_FILE),
                            runRoot().resolve(QualificationRunFinalizer.RESULT_FILE)));
            if (phase == QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION) return;
            remove(
                    QualificationPacketPublisher.PublicationKind.MECHANICAL_REQUIREMENTS,
                    List.of(runRoot().resolve(QualificationRequirementFinalizer.OUTPUT_FILE)));
            if (phase == QualificationPacketPublisher.PublicationKind.MECHANICAL_REQUIREMENTS) return;
            remove(
                    QualificationPacketPublisher.PublicationKind.APPROVAL_EVIDENCE,
                    List.of(runRoot().resolve(QualificationApprovalFinalizer.OUTPUT_FILE)));
            if (phase == QualificationPacketPublisher.PublicationKind.APPROVAL_EVIDENCE) return;
            remove(
                    QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                    List.of(
                            runRoot().resolve("prompt-debug.txt"),
                            runRoot().resolve("llama.log")));
        }

        private void remove(
                QualificationPacketPublisher.PublicationKind kind,
                List<Path> members) throws IOException {
            packet.removePublicationForTest(kind, members);
        }
    }
}
