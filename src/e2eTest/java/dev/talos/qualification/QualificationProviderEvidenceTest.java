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
        QualificationTestPacket packet = packet("provider-render");
        Files.writeString(packet.runRoot().resolve("prompt-debug-20260711.md"), "provider request\n",
                StandardCharsets.UTF_8);

        QualificationProviderEvidence.capture(
                packet.root(), packet.runRoot(), "ordinary command output",
                QualificationProtocol.VERSION);

        assertFalse(Files.exists(packet.runRoot().resolve("prompt-debug.txt")));
        assertFalse(Files.exists(packet.runRoot().resolve("llama.log")));
    }

    @Test
    void exactNoProviderOutputCreatesPairedBoundedNotApplicableEvidence() throws Exception {
        QualificationTestPacket packet = packet("not-applicable");
        QualificationProviderEvidence.capture(
                packet.root(),
                packet.runRoot(),
                "i " + QualificationProviderEvidence.NO_PROVIDER_PROMPT + " More detail.",
                QualificationProtocol.VERSION);

        String prompt = Files.readString(
                packet.runRoot().resolve("prompt-debug.txt"), StandardCharsets.UTF_8);
        String runtime = Files.readString(
                packet.runRoot().resolve("llama.log"), StandardCharsets.UTF_8);
        assertTrue(prompt.equals(QualificationProviderEvidence.promptNotApplicableText()));
        assertTrue(runtime.equals(QualificationProviderEvidence.modelRuntimeNotApplicableText()));
        assertTrue(prompt.length() < 512);
        assertTrue(runtime.length() < 512);
    }

    @Test
    void missingOrPartialNoProviderOutputFailsClosedWithoutFallbacks() throws Exception {
        QualificationTestPacket packet = packet("missing-output");
        assertThrows(IllegalStateException.class,
                () -> QualificationProviderEvidence.capture(
                        packet.root(),
                        packet.runRoot(),
                        "No provider prompt may have been sent.",
                        QualificationProtocol.VERSION));
        assertFalse(Files.exists(packet.runRoot().resolve("prompt-debug.txt")));
        assertFalse(Files.exists(packet.runRoot().resolve("llama.log")));
    }

    @Test
    void secondMemberConflictIsFoundBeforeTheMissingFirstMemberIsPublished() throws Exception {
        QualificationTestPacket packet = packet("member-conflict");
        Files.writeString(
                packet.runRoot().resolve("llama.log"),
                "frozen conflict\n",
                StandardCharsets.UTF_8);

        QualificationPacketPublisher.PublicationException failure = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> QualificationProviderEvidence.capture(
                        packet.root(),
                        packet.runRoot(),
                        QualificationProviderEvidence.NO_PROVIDER_PROMPT,
                        QualificationProtocol.VERSION));

        assertTrue(failure.reason() == QualificationPacketPublisher.FailureReason.CONFLICT);
        assertFalse(Files.exists(packet.runRoot().resolve("prompt-debug.txt")));
        assertTrue(Files.readString(
                        packet.runRoot().resolve("llama.log"), StandardCharsets.UTF_8)
                .equals("frozen conflict\n"));
    }

    private QualificationTestPacket packet(String name) throws Exception {
        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve(name), QualificationTestPacket.protocol6Catalog());
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
        return packet;
    }
}
