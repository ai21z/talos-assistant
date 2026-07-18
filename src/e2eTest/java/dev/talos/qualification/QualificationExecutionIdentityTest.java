package dev.talos.qualification;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationExecutionIdentityTest {
    @TempDir
    Path temp;

    @Test
    void differentExecutingFinalizerIdentityRefusesPublicationBeforeAuthorityWrites()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        removeAuthority(packet);

        Path harnessPath = packet.root().resolve(QualificationExecutionIdentity.HARNESS_FILE);
        ObjectNode harness = (ObjectNode) QualificationJson.readTree(harnessPath);
        harness.put("finalizerImplementationSha256", "f".repeat(64));
        Path ledgerPath = packet.root().resolve(QualificationExecutionIdentityWriter.LEDGER_FILE);
        ObjectNode ledger = (ObjectNode) QualificationJson.readTree(ledgerPath);
        ledger.put("finalizerImplementationSha256", "f".repeat(64));
        QualificationJson.write(ledgerPath, ledger);
        ((ObjectNode) harness.path("files")).put(
                QualificationExecutionIdentityWriter.LEDGER_FILE,
                QualificationScenarioBank.sha256(Files.readAllBytes(ledgerPath)));
        QualificationJson.write(harnessPath, harness);
        Path runtimePath = packet.root().resolve(QualificationExecutionIdentity.RUNTIME_FILE);
        ObjectNode runtime = (ObjectNode) QualificationJson.readTree(runtimePath);
        runtime.put("harnessManifestSha256",
                QualificationScenarioBank.sha256(Files.readAllBytes(harnessPath)));
        QualificationJson.write(runtimePath, runtime);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                QualificationRunFinalizer.finalizeRun(
                        packet.root(), packet.runRoot(),
                        catalog.admission().scenarios().getFirst().id(), catalog));

        assertTrue(failure.getMessage().contains("executing finalizer"), failure::getMessage);
        assertAuthorityAbsent(packet);
    }

    @Test
    void differentExecutingFinalizerClasspathRefusesPublicationBeforeAuthorityWrites()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        removeAuthority(packet);

        Path harnessPath = packet.root().resolve(QualificationExecutionIdentity.HARNESS_FILE);
        ObjectNode harness = (ObjectNode) QualificationJson.readTree(harnessPath);
        harness.put("finalizerClasspathSha256", "f".repeat(64));
        Path ledgerPath = packet.root().resolve(QualificationExecutionIdentityWriter.LEDGER_FILE);
        ObjectNode ledger = (ObjectNode) QualificationJson.readTree(ledgerPath);
        ((ObjectNode) ledger.path("finalizerClasspath")).put("sha256", "f".repeat(64));
        QualificationJson.write(ledgerPath, ledger);
        ((ObjectNode) harness.path("files")).put(
                QualificationExecutionIdentityWriter.LEDGER_FILE,
                QualificationScenarioBank.sha256(Files.readAllBytes(ledgerPath)));
        QualificationJson.write(harnessPath, harness);
        Path runtimePath = packet.root().resolve(QualificationExecutionIdentity.RUNTIME_FILE);
        ObjectNode runtime = (ObjectNode) QualificationJson.readTree(runtimePath);
        runtime.put("harnessManifestSha256",
                QualificationScenarioBank.sha256(Files.readAllBytes(harnessPath)));
        QualificationJson.write(runtimePath, runtime);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                QualificationRunFinalizer.finalizeRun(
                        packet.root(), packet.runRoot(),
                        catalog.admission().scenarios().getFirst().id(), catalog));

        assertTrue(failure.getMessage().contains("finalizer classpath"),
                failure::getMessage);
        assertAuthorityAbsent(packet);
    }

    @Test
    void runtimeArtifactDeclaredByManifestMustEqualTheArtifactThatEmittedTheTrace()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        removeAuthority(packet);
        mutateTraceStarted(packet, "runtimeArtifactSha256", "f".repeat(64));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                QualificationRunFinalizer.finalizeRun(
                        packet.root(), packet.runRoot(),
                        catalog.admission().scenarios().getFirst().id(), catalog));

        assertTrue(failure.getMessage().contains("trace runtime artifact hash"),
                failure::getMessage);
        assertAuthorityAbsent(packet);
    }

    @Test
    void traceRuntimeIdentityRejectsTextualNumericFieldsBeforeAuthorityPublication()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        removeAuthority(packet);
        Path tracePath = packet.runRoot().resolve("trace.json");
        ObjectNode trace = (ObjectNode) QualificationJson.readTree(tracePath);
        ObjectNode traceStarted = (ObjectNode) trace.withArray("events").get(0).path("data");
        long exactSize = traceStarted.path("runtimeArtifactSizeBytes").longValue();
        traceStarted.put("runtimeArtifactSizeBytes", Long.toString(exactSize));
        QualificationJson.write(tracePath, trace);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> QualificationRunFinalizer.finalizeRun(
                        packet.root(), packet.runRoot(),
                        catalog.admission().scenarios().getFirst().id(), catalog));

        assertTrue(failure.getMessage().contains("exact JSON integer"),
                failure::getMessage);
        assertAuthorityAbsent(packet);
    }

    @Test
    void runtimeClasspathDeclaredByManifestMustEqualTheClasspathThatEmittedTheTrace()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        removeAuthority(packet);
        mutateTraceStarted(packet, "runtimeClasspathSha256", "f".repeat(64));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                QualificationRunFinalizer.finalizeRun(
                        packet.root(), packet.runRoot(),
                        catalog.admission().scenarios().getFirst().id(), catalog));

        assertTrue(failure.getMessage().contains("trace runtime classpath hash"),
                failure::getMessage);
        assertAuthorityAbsent(packet);
    }

    @Test
    void liveModelBytesMustStillMatchTheCandidateManifestAtFinalization()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        removeAuthority(packet);
        mutateOneByteWithoutChangingSize(packet.root().resolve("artifacts/model.gguf"));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                QualificationRunFinalizer.finalizeRun(
                        packet.root(), packet.runRoot(),
                        catalog.admission().scenarios().getFirst().id(), catalog));

        assertTrue(failure.getMessage().contains("MODEL_ARTIFACT stable metadata changed"),
                failure::getMessage);
        assertAuthorityAbsent(packet);
    }

    @Test
    void liveEngineBytesMustStillMatchTheEngineManifestAtFinalization()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        removeAuthority(packet);
        mutateOneByteWithoutChangingSize(packet.root().resolve("artifacts/llama-server.exe"));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                QualificationRunFinalizer.finalizeRun(
                        packet.root(), packet.runRoot(),
                        catalog.admission().scenarios().getFirst().id(), catalog));

        assertTrue(failure.getMessage().contains("ENGINE_EXECUTABLE stable metadata changed"),
                failure::getMessage);
        assertAuthorityAbsent(packet);
    }

    @Test
    void everyPublishedExecutionManifestIsByteBound() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        for (String name : new String[] {
                QualificationExecutionIdentityWriter.LEDGER_FILE,
                QualificationExecutionIdentity.RUNTIME_FILE,
                QualificationExecutionIdentity.HARNESS_FILE}) {
            Path caseRoot = temp.resolve(name.replace('.', '-'));
            QualificationTestPacket packet = QualificationTestPacket.create(caseRoot, catalog);
            Files.writeString(packet.root().resolve(name), "{}\n");

            QualificationEvidenceValidator.Validation validation =
                    QualificationEvidenceValidator.validate(packet.root(), catalog);

            assertFalse(validation.valid(), name);
            assertTrue(validation.findings().stream().anyMatch(finding ->
                            finding.contains("publication verification failed")),
                    () -> name + "\n" + String.join("\n", validation.findings()));
        }
    }

    @Test
    void identityBoundCalibrationAndMachineMutationsRefuseAuthorityPublication()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        List<IdentityMutation> cases = List.of(
                new IdentityMutation("calibration", packet -> {
                    Path rules = packet.root().resolve(
                            "candidates/qwen14/calibration-rules.json");
                    ObjectNode json = (ObjectNode) QualificationJson.readTree(rules);
                    json.put("seedPolicy", "changed-after-freeze");
                    QualificationJson.write(rules, json);
                }, "runtime calibration hash mismatch"),
                new IdentityMutation("machine", packet -> {
                    Path machine = packet.root().resolve("machines/reference.json");
                    ObjectNode json = (ObjectNode) QualificationJson.readTree(machine);
                    json.put("osVersion", "changed-after-freeze");
                    QualificationJson.write(machine, json);
                }, "runtime machine manifest hash mismatch"));

        for (IdentityMutation testCase : cases) {
            QualificationTestPacket packet = QualificationTestPacket.create(
                    temp.resolve(testCase.id()), catalog);
            removeAuthority(packet);
            testCase.mutation().apply(packet);

            IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                    QualificationRunFinalizer.finalizeRun(
                            packet.root(), packet.runRoot(),
                            catalog.admission().scenarios().getFirst().id(), catalog));

            assertTrue(failure.getMessage().contains(testCase.message()), failure::getMessage);
            assertAuthorityAbsent(packet);
        }
    }

    private static void removeAuthority(QualificationTestPacket packet) throws Exception {
        Files.delete(packet.runRoot().resolve(QualificationRunFinalizer.RESULT_FILE));
        Files.delete(packet.runRoot().resolve(QualificationCausalFinalizer.OUTPUT_FILE));
        Files.delete(packet.runRoot().resolve(QualificationRunFinalizer.CANARY_FILE));
    }

    private static void mutateTraceStarted(
            QualificationTestPacket packet,
            String field,
            String value) throws Exception {
        Path tracePath = packet.runRoot().resolve("trace.json");
        ObjectNode trace = (ObjectNode) QualificationJson.readTree(tracePath);
        ObjectNode first = (ObjectNode) trace.withArray("events").get(0).path("data");
        first.put(field, value);
        QualificationJson.write(tracePath, trace);
    }

    private static void assertAuthorityAbsent(QualificationTestPacket packet) {
        assertFalse(Files.exists(packet.runRoot().resolve(QualificationRunFinalizer.RESULT_FILE)));
        assertFalse(Files.exists(packet.runRoot().resolve(QualificationCausalFinalizer.OUTPUT_FILE)));
        assertFalse(Files.exists(packet.runRoot().resolve(QualificationRunFinalizer.CANARY_FILE)));
    }

    private static void mutateOneByteWithoutChangingSize(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        bytes[0] ^= 1;
        Files.write(path, bytes);
    }

    private record IdentityMutation(
            String id,
            ThrowingMutation mutation,
            String message) {}

    @FunctionalInterface
    private interface ThrowingMutation {
        void apply(QualificationTestPacket packet) throws Exception;
    }
}
