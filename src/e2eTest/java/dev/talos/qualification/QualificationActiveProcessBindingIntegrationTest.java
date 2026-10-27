package dev.talos.qualification;

import dev.talos.spi.types.ProviderProcessLaunchReceipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationActiveProcessBindingIntegrationTest {

    @TempDir
    Path temp;

    @Test
    void processBoundTraceProvesActiveBindingAndModelAgreementAcrossBothReaders()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        QualificationTestPacket packet = QualificationTestPacket.createProcessBound(
                temp.resolve("process-bound"), catalog, scenario);

        byte[] traceBytes = Files.readAllBytes(packet.runRoot().resolve("trace.json"));
        assertEquals(
                QualificationProviderAttemptLedger.PROCESS_TRACE_SCHEMA_VERSION,
                QualificationJson.readTree(traceBytes).path("schemaVersion").intValue());
        assertTrue(QualificationTraceEvidencePublication.requireCommittedForSchema(
                packet.root(),
                packet.runRoot(),
                QualificationProviderAttemptLedger.PROCESS_TRACE_SCHEMA_VERSION,
                traceBytes).isPresent());

        QualificationPreResultEvidence.Snapshot snapshot =
                QualificationPreResultEvidence.capture(
                        packet.root(), packet.runRoot(), scenario.id(), catalog);
        assertTrue(snapshot.trace().providerAttempts().activeProcessBindingComplete());
        ProviderProcessLaunchReceipt receipt = snapshot.trace().providerAttempts()
                .processReceipts().receiptOptional().orElseThrow();
        assertEquals("qwen14", snapshot.campaign().candidateAlias());
        assertEquals("qwen14", QualificationEvidenceValidator.expectedRuntimeModel(
                snapshot.calibration()));
        assertEquals("qwen14", snapshot.trace().modelId());
        assertEquals("qwen14", receipt.resolvedProfile());
        assertTrue(snapshot.trustGates().modelIdentityAgreement(), snapshot.findings()::toString);
        assertTrue(snapshot.trustGates().mandatoryEvidencePresent(), snapshot.findings()::toString);
        assertTrue(snapshot.complete(), snapshot.findings()::toString);

        QualificationEvidenceValidator.Validation validation =
                QualificationEvidenceValidator.validate(packet.root(), catalog);
        assertEquals(1, validation.runs().size(), validation.findings()::toString);
        QualificationRecords.RunResult run = validation.runs().getFirst();
        assertTrue(run.trustGates().modelIdentityAgreement(), validation.findings()::toString);
        assertTrue(run.trustGates().mandatoryEvidencePresent(), validation.findings()::toString);
        assertTrue(validation.findings().stream().noneMatch(finding ->
                        finding.contains("Run model identity differs from evidence")),
                validation.findings()::toString);
        assertTrue(validation.findings().stream().noneMatch(finding ->
                        finding.contains("ACTIVE_PROCESS_")),
                validation.findings()::toString);
    }

    @Test
    void defaultProtocolSixFixtureRemainsAnExplicitSchemaFourNegativeControl()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve("schema-four-negative"), catalog, scenario);

        QualificationPreResultEvidence.Snapshot snapshot =
                QualificationPreResultEvidence.capture(
                        packet.root(), packet.runRoot(), scenario.id(), catalog);

        assertEquals(
                QualificationProviderAttemptLedger.TRACE_SCHEMA_VERSION,
                snapshot.trace().providerAttempts().traceSchemaVersion());
        assertFalse(snapshot.trace().providerAttempts().activeProcessBindingComplete());
        assertTrue(snapshot.findings().contains(
                "ACTIVE_PROCESS_LAUNCH_RECEIPT_UNAVAILABLE"), snapshot.findings()::toString);
        assertFalse(snapshot.trustGates().modelIdentityAgreement());
        assertFalse(snapshot.trustGates().mandatoryEvidencePresent());
    }

    @Test
    void sensitivePacketInputsNeverEnterProcessIdentityProjections() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        QualificationTestPacket packet = QualificationTestPacket.createProcessBound(
                temp.resolve("sensitive-boundary"), catalog, scenario);
        String sensitiveRoot = packet.root().toAbsolutePath().normalize().toString();
        String sensitiveRootForward = sensitiveRoot.replace('\\', '/');
        String engineManifest = Files.readString(
                packet.root().resolve("engines/cuda.json"), StandardCharsets.UTF_8)
                .replace("\\\\", "\\");
        String providerLog = Files.readString(
                packet.runRoot().resolve("llama.log"), StandardCharsets.UTF_8).strip();

        assertTrue(engineManifest.contains(sensitiveRoot));
        assertFalse(providerLog.isBlank());
        for (Path projection : List.of(
                packet.runRoot().resolve("trace.json"),
                packet.runRoot().resolve(QualificationCausalFinalizer.OUTPUT_FILE),
                packet.runRoot().resolve(QualificationRunFinalizer.RESULT_FILE))) {
            String body = Files.readString(projection, StandardCharsets.UTF_8)
                    .replace("\\\\", "\\");
            assertFalse(body.contains(sensitiveRoot), projection::toString);
            assertFalse(body.contains(sensitiveRootForward), projection::toString);
            assertFalse(body.contains(providerLog), projection::toString);
        }
    }

    @ParameterizedTest(name = "internally consistent receipt with changed {0} fails packet identity")
    @ValueSource(strings = {
            "candidateDeclarationSha256",
            "engineExecutableSha256",
            "modelArtifactSha256",
            "launchCommandSha256",
            "effectiveEnvironmentSha256",
            "endpointObserverKind",
            "endpointObserverIdentitySha256",
            "resolvedProfile",
            "managedPort",
            "launchArgumentSet"
    })
    void packetIdentityRejectsInternallyConsistentReceiptTampering(String field)
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        QualificationTestPacket packet = QualificationTestPacket.createProcessBound(
                temp.resolve("tamper-" + field),
                catalog,
                scenario,
                receipt -> mutate(receipt, field));

        QualificationPreResultEvidence.Snapshot snapshot =
                QualificationPreResultEvidence.capture(
                        packet.root(), packet.runRoot(), scenario.id(), catalog);
        assertTrue(snapshot.trace().providerAttempts().activeProcessBindingComplete(), field);
        assertTrue(snapshot.findings().contains(
                        "ACTIVE_PROCESS_LAUNCH_RECEIPT_IDENTITY_MISMATCH"),
                () -> field + ": " + snapshot.findings());
        assertFalse(snapshot.trustGates().modelIdentityAgreement(), field);
        assertFalse(snapshot.trustGates().mandatoryEvidencePresent(), field);

        QualificationEvidenceValidator.Validation validation =
                QualificationEvidenceValidator.validate(packet.root(), catalog);
        assertEquals(1, validation.runs().size(),
                () -> field + ": " + validation.findings());
        assertFalse(validation.runs().getFirst().trustGates().modelIdentityAgreement(), field);
        assertTrue(validation.findings().stream().noneMatch(finding ->
                        finding.contains("Run model identity differs from evidence")),
                () -> field + ": " + validation.findings());
    }

    private static ProviderProcessLaunchReceipt mutate(
            ProviderProcessLaunchReceipt receipt,
            String field) {
        String changedHash = "f".repeat(64);
        ProviderProcessLaunchReceipt.EndpointObserverKind observerKind =
                field.equals("endpointObserverKind")
                        ? ProviderProcessLaunchReceipt.EndpointObserverKind
                                .LINUX_PROCFS_MANAGED_PROCESS_TREE_V1
                        : receipt.endpointObserverKind();
        return new ProviderProcessLaunchReceipt(
                receipt.processId(),
                receipt.processStartEpochMilli(),
                receipt.launchObservedEpochMilli(),
                receipt.readinessObservedEpochMilli(),
                receipt.launchNonce(),
                field.equals("candidateDeclarationSha256")
                        ? changedHash : receipt.candidateDeclarationSha256(),
                field.equals("engineExecutableSha256")
                        ? changedHash : receipt.engineExecutableSha256(),
                field.equals("modelArtifactSha256")
                        ? changedHash : receipt.modelArtifactSha256(),
                field.equals("launchCommandSha256")
                        ? changedHash : receipt.launchCommandSha256(),
                field.equals("launchArgumentSet")
                        ? ProviderProcessLaunchReceipt.LaunchArgumentSet.VERIFICATION
                        : receipt.launchArgumentSet(),
                field.equals("effectiveEnvironmentSha256")
                        ? changedHash : receipt.effectiveEnvironmentSha256(),
                receipt.environmentContract(),
                receipt.managedHost(),
                field.equals("managedPort") ? receipt.managedPort() + 1 : receipt.managedPort(),
                receipt.prelaunchNoListener(),
                receipt.propsResponseSha256(),
                field.equals("resolvedProfile") ? "qwen14-tampered" : receipt.resolvedProfile(),
                receipt.servedIdentity(),
                observerKind,
                field.equals("endpointObserverIdentitySha256")
                        ? changedHash : receipt.endpointObserverIdentitySha256(),
                observerKind.ownershipBoundary(),
                observerKind.stableAssumption(),
                receipt.contentVerificationBoundary(),
                receipt.stableFilesystemAssumption());
    }
}
