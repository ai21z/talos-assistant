package dev.talos.qualification;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.runtime.policy.ArtifactCanaryScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationRunFinalizerTest {
    @TempDir
    Path temp;

    @Test
    void javaAlonePublishesBoundCausalCanaryAndSchemaV2ResultLast() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = unfinalizedPacket(temp.resolve("publish"), catalog);

        Path resultPath = QualificationRunFinalizer.finalizeRun(
                packet.root(), packet.runRoot(), scenarioId(catalog), catalog);

        Path causalPath = packet.runRoot().resolve(QualificationCausalFinalizer.OUTPUT_FILE);
        Path canaryPath = packet.runRoot().resolve(QualificationRunFinalizer.CANARY_FILE);
        assertTrue(Files.isRegularFile(causalPath));
        assertTrue(Files.isRegularFile(canaryPath));
        assertTrue(Files.isRegularFile(resultPath));
        QualificationCausalFinalizer.Document causal = QualificationJson.read(
                causalPath, QualificationCausalFinalizer.Document.class);
        QualificationRunFinalizer.CanaryDocument canary = QualificationJson.read(
                canaryPath, QualificationRunFinalizer.CanaryDocument.class);
        QualificationRecords.RunResult result = QualificationJson.read(
                resultPath, QualificationRecords.RunResult.class);
        assertEquals(QualificationProtocol.VERSION,
                causal.evidenceIdentity().protocolVersion());
        assertFalse(causal.evidenceIdentity().artifactSha256().isEmpty());
        assertEquals(QualificationRecords.RUN_SCHEMA_VERSION, result.schemaVersion());
        assertEquals(causal.evidenceIdentity(), canary.evidenceIdentity());
        assertEquals(canary.passed(), causal.canaryPassed());
        assertEquals(ArtifactCanaryScanner.CAPTURED_SCAN_POLICY,
                canary.scanPolicyName());
        assertEquals(ArtifactCanaryScanner.CAPTURED_SCAN_POLICY_VERSION,
                canary.scanPolicyVersion());
        assertEquals(canary.capturedArtifactCount(),
                canary.allowlistedArtifactCount()
                        + canary.scannedArtifactCount()
                        + canary.coverageGapCount());
        assertEquals(canary.findings().size(), canary.findingCount());
        assertEquals(canary.coverageGaps().size(), canary.coverageGapCount());
        assertTrue(QualificationRunFinalizer.resultMatchesCausal(result, causal));
    }

    @Test
    void canaryAuthorityPersistsOnlySafeRelativeRecordsAndNoRawContent() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = unfinalizedPacket(
                temp.resolve("safe-canary-record"), catalog);
        String rawCanary = "t275-token-should-not-appear";
        Files.writeString(packet.runRoot().resolve("opaque.bin"), rawCanary,
                StandardCharsets.UTF_8);

        QualificationRunFinalizer.finalizeRun(
                packet.root(), packet.runRoot(), scenarioId(catalog), catalog);

        Path canaryPath = packet.runRoot().resolve(QualificationRunFinalizer.CANARY_FILE);
        String published = Files.readString(canaryPath, StandardCharsets.UTF_8);
        QualificationRunFinalizer.CanaryDocument canary = QualificationJson.read(
                canaryPath, QualificationRunFinalizer.CanaryDocument.class);
        assertFalse(canary.passed());
        assertEquals(1, canary.findingCount());
        assertEquals("opaque.bin", canary.findings().getFirst().relativePath());
        assertEquals(ArtifactCanaryScanner.CapturedFindingReason
                        .KNOWN_RAW_CANARY_ASCII_OR_UTF8_BYTES,
                canary.findings().getFirst().reason());
        assertFalse(published.contains(rawCanary), published);
        assertFalse(published.contains(packet.runRoot().toString()), published);
        assertFalse(published.contains("snippet"), published);
    }

    @Test
    void canaryAuthoritySchemaRejectsUnsafeMissingExtraAndInconsistentFields()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = unfinalizedPacket(
                temp.resolve("strict-canary-schema"), catalog);
        Files.writeString(packet.runRoot().resolve("opaque.bin"),
                "t275-token-should-not-appear", StandardCharsets.UTF_8);
        QualificationRunFinalizer.finalizeRun(
                packet.root(), packet.runRoot(), scenarioId(catalog), catalog);
        ObjectNode canonical = (ObjectNode) QualificationJson.readTree(
                packet.runRoot().resolve(QualificationRunFinalizer.CANARY_FILE));
        List<Consumer<ObjectNode>> corruptions = List.of(
                json -> json.put("scanPolicyVersion", 99),
                json -> json.put("capturedArtifactCount",
                        json.path("capturedArtifactCount").asInt() + 1),
                json -> json.put("findingCount", 0),
                json -> json.remove("scanScope"),
                json -> ((ObjectNode) ((ArrayNode) json.path("findings")).get(0))
                        .put("relativePath", "../outside.bin"),
                json -> ((ObjectNode) ((ArrayNode) json.path("findings")).get(0))
                        .put("relativePath", "C:/outside.bin"),
                json -> ((ObjectNode) ((ArrayNode) json.path("findings")).get(0))
                        .put("snippet", "forbidden"));

        for (int index = 0; index < corruptions.size(); index++) {
            ObjectNode corrupted = canonical.deepCopy();
            corruptions.get(index).accept(corrupted);
            assertThrows(Exception.class,
                    () -> QualificationJson.read(
                            QualificationJson.bytes(corrupted),
                            QualificationRunFinalizer.CanaryDocument.class),
                    "corruption " + index);
        }
    }

    @Test
    void legacyAndFutureProtocolsRefuseBeforePublishingAnyAuthorityArtifact() throws Exception {
        for (int protocol = 1; protocol <= 5; protocol++) {
            QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(protocol);
            QualificationTestPacket packet = unfinalizedPacket(
                    temp.resolve("protocol-" + protocol), catalog);
            assertThrows(IllegalArgumentException.class,
                    () -> QualificationRunFinalizer.finalizeRun(
                            packet.root(), packet.runRoot(), scenarioId(catalog), catalog));
            assertNoAuthorityArtifacts(packet.runRoot());
        }

        QualificationScenarioBank.Catalog v6 = QualificationTestPacket.protocol6Catalog();
        QualificationScenarioBank.Catalog future = new QualificationScenarioBank.Catalog(
                7, v6.admission(), v6.full(), v6.fixtures(), v6.sha256());
        QualificationTestPacket packet = unfinalizedPacket(temp.resolve("protocol-7"), v6);
        assertThrows(IllegalArgumentException.class,
                () -> QualificationRunFinalizer.finalizeRun(
                        packet.root(), packet.runRoot(), scenarioId(v6), future));
        assertNoAuthorityArtifacts(packet.runRoot());
    }

    @Test
    void publishedResultIsImmutableAndSecondFinalizationChangesNoByte() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = unfinalizedPacket(temp.resolve("immutable"), catalog);
        QualificationRunFinalizer.finalizeRun(
                packet.root(), packet.runRoot(), scenarioId(catalog), catalog);
        byte[] causal = Files.readAllBytes(
                packet.runRoot().resolve(QualificationCausalFinalizer.OUTPUT_FILE));
        byte[] canary = Files.readAllBytes(
                packet.runRoot().resolve(QualificationRunFinalizer.CANARY_FILE));
        byte[] result = Files.readAllBytes(
                packet.runRoot().resolve(QualificationRunFinalizer.RESULT_FILE));
        var causalMtime = Files.getLastModifiedTime(
                packet.runRoot().resolve(QualificationCausalFinalizer.OUTPUT_FILE));
        var canaryMtime = Files.getLastModifiedTime(
                packet.runRoot().resolve(QualificationRunFinalizer.CANARY_FILE));
        var resultMtime = Files.getLastModifiedTime(
                packet.runRoot().resolve(QualificationRunFinalizer.RESULT_FILE));

        QualificationRunFinalizer.finalizeRun(
                packet.root(), packet.runRoot(), scenarioId(catalog), catalog);
        assertArrayEquals(causal, Files.readAllBytes(
                packet.runRoot().resolve(QualificationCausalFinalizer.OUTPUT_FILE)));
        assertArrayEquals(canary, Files.readAllBytes(
                packet.runRoot().resolve(QualificationRunFinalizer.CANARY_FILE)));
        assertArrayEquals(result, Files.readAllBytes(
                packet.runRoot().resolve(QualificationRunFinalizer.RESULT_FILE)));
        assertEquals(causalMtime, Files.getLastModifiedTime(
                packet.runRoot().resolve(QualificationCausalFinalizer.OUTPUT_FILE)));
        assertEquals(canaryMtime, Files.getLastModifiedTime(
                packet.runRoot().resolve(QualificationRunFinalizer.CANARY_FILE)));
        assertEquals(resultMtime, Files.getLastModifiedTime(
                packet.runRoot().resolve(QualificationRunFinalizer.RESULT_FILE)));
    }

    @Test
    void semanticallyEquivalentAuthorityByteEditsAreRejected() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        for (String authority : java.util.List.of(
                QualificationCausalFinalizer.OUTPUT_FILE,
                QualificationRunFinalizer.CANARY_FILE,
                QualificationRunFinalizer.RESULT_FILE)) {
            QualificationTestPacket packet = unfinalizedPacket(
                    temp.resolve("noncanonical-" + authority), catalog);
            Path resultPath = QualificationRunFinalizer.finalizeRun(
                    packet.root(), packet.runRoot(), scenarioId(catalog), catalog);
            Files.writeString(
                    packet.runRoot().resolve(authority),
                    "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);

            QualificationPacketPublisher.PublicationException rejection = assertThrows(
                    QualificationPacketPublisher.PublicationException.class,
                    () -> QualificationRunFinalizer.verifyPublished(
                            packet.root(), resultPath, catalog));
            assertEquals(
                    QualificationPacketPublisher.FailureReason.INVALID_COMMIT,
                    rejection.reason(),
                    authority);
        }
    }

    @Test
    void conflictingOrphanAndMidFinalizationMutationCannotPublishResult() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket orphan = unfinalizedPacket(temp.resolve("orphan"), catalog);
        Files.writeString(
                orphan.runRoot().resolve(QualificationCausalFinalizer.OUTPUT_FILE),
                "{}\n",
                StandardCharsets.UTF_8);
        QualificationPacketPublisher.PublicationException orphanFailure = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> QualificationRunFinalizer.finalizeRun(
                        orphan.root(), orphan.runRoot(), scenarioId(catalog), catalog));
        assertEquals(
                QualificationPacketPublisher.FailureReason.CONFLICT,
                orphanFailure.reason());
        assertFalse(Files.exists(orphan.runRoot().resolve(QualificationRunFinalizer.RESULT_FILE)));

        QualificationTestPacket mutated = unfinalizedPacket(temp.resolve("mutated"), catalog);
        assertThrows(IllegalStateException.class,
                () -> QualificationRunFinalizer.finalizeRun(
                        mutated.root(), mutated.runRoot(), scenarioId(catalog), catalog,
                        new QualificationRunFinalizer.PublicationObserver() {
                            @Override
                            public void afterInitialCapture(
                                    QualificationPreResultEvidence.Snapshot ignored)
                                    throws java.io.IOException {
                                Files.writeString(
                                        mutated.runRoot().resolve("request.json"),
                                        " ",
                                        StandardCharsets.UTF_8,
                                        java.nio.file.StandardOpenOption.APPEND);
                            }
                        }));
        assertNoAuthorityArtifacts(mutated.runRoot());
    }

    @Test
    void resultNeverPublishesWhenEvidenceChangesAfterCompanions() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = unfinalizedPacket(temp.resolve("late-mutation"), catalog);
        assertThrows(IllegalStateException.class,
                () -> QualificationRunFinalizer.finalizeRun(
                        packet.root(), packet.runRoot(), scenarioId(catalog), catalog,
                        new QualificationRunFinalizer.PublicationObserver() {
                            @Override
                            public void beforeResultPublication(
                                    QualificationPreResultEvidence.Snapshot ignored)
                                    throws java.io.IOException {
                                Files.writeString(
                                        packet.runRoot().resolve("timings.json"),
                                        " ",
                                        StandardCharsets.UTF_8,
                                        java.nio.file.StandardOpenOption.APPEND);
                            }
                        }));
        assertNoAuthorityArtifacts(packet.runRoot());
    }

    @Test
    void canonicalRetryRecoversRunPublicationAcrossEveryMemberStageBoundary()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        for (QualificationPacketPublisher.FaultStage stage : java.util.List.of(
                QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PARTIAL_WRITE,
                QualificationPacketPublisher.FaultStage.AFTER_MEMBER_FINAL_WRITE,
                QualificationPacketPublisher.FaultStage.AFTER_MEMBER_FLUSH,
                QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PROMOTION)) {
            QualificationTestPacket packet = unfinalizedPacket(
                    temp.resolve("run-recovery-" + stage.name().toLowerCase()), catalog);
            QualificationPreResultEvidence.Snapshot snapshot =
                    QualificationPreResultEvidence.capture(
                            packet.root(), packet.runRoot(), scenarioId(catalog), catalog);
            QualificationRunFinalizer.Publication publication =
                    QualificationRunFinalizer.derive(snapshot);

            assertThrows(IOException.class, () -> QualificationPacketPublisher.withLease(
                    packet.root(),
                    java.time.Duration.ofSeconds(5),
                    (actual, ordinal) -> {
                        if (actual == stage && ordinal == 0) {
                            throw new IOException("injected run publication fault");
                        }
                    },
                    lease -> lease.publish(
                            QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION,
                            java.util.List.of(
                                    new QualificationPacketPublisher.Member(
                                            packet.runRoot().resolve(
                                                    QualificationCausalFinalizer.OUTPUT_FILE),
                                            QualificationJson.bytes(publication.causal())),
                                    new QualificationPacketPublisher.Member(
                                            packet.runRoot().resolve(
                                                    QualificationRunFinalizer.CANARY_FILE),
                                            QualificationJson.bytes(publication.canary())),
                                    new QualificationPacketPublisher.Member(
                                            packet.runRoot().resolve(
                                                    QualificationRunFinalizer.RESULT_FILE),
                                            QualificationJson.bytes(publication.result()))))));

            Path result = QualificationRunFinalizer.finalizeRun(
                    packet.root(), packet.runRoot(), scenarioId(catalog), catalog);

            assertEquals(
                    publication,
                    QualificationRunFinalizer.verifyPublished(packet.root(), result, catalog),
                    stage.name());
            QualificationPacketPublisher.requireSettledControlState(packet.root());
            try (var runEntries = Files.list(packet.runRoot())) {
                assertTrue(runEntries.noneMatch(path ->
                        path.getFileName().toString().endsWith(".stage")), stage.name());
            }
        }
    }

    @Test
    void protocolV6ReaderRejectsACompleteSetWithoutItsCommitRecord() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = unfinalizedPacket(
                temp.resolve("missing-commit-record"), catalog);
        Path result = QualificationRunFinalizer.finalizeRun(
                packet.root(), packet.runRoot(), scenarioId(catalog), catalog);
        Path commit = QualificationPacketPublisher.commitPathForTest(
                packet.root(),
                QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION,
                java.util.List.of(
                        packet.runRoot().resolve(QualificationCausalFinalizer.OUTPUT_FILE),
                        packet.runRoot().resolve(QualificationRunFinalizer.CANARY_FILE),
                        result));
        Files.delete(commit);

        QualificationPacketPublisher.PublicationException failure = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> QualificationRunFinalizer.verifyPublished(
                        packet.root(), result, catalog));
        assertEquals(QualificationPacketPublisher.FailureReason.INCOMPLETE, failure.reason());
    }

    @Test
    void resultNeverPublishesWhenACompanionChangesBeforeTheFinalWrite() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        for (String companion : java.util.List.of(
                QualificationCausalFinalizer.OUTPUT_FILE,
                QualificationRunFinalizer.CANARY_FILE)) {
            QualificationTestPacket packet = unfinalizedPacket(
                    temp.resolve("changed-companion-" + companion), catalog);
            Path conflict = packet.runRoot().resolve(companion);
            Files.writeString(conflict, "conflicting orphan\n", StandardCharsets.UTF_8);

            QualificationPacketPublisher.PublicationException failure = assertThrows(
                    QualificationPacketPublisher.PublicationException.class,
                    () -> QualificationRunFinalizer.finalizeRun(
                            packet.root(), packet.runRoot(), scenarioId(catalog), catalog));
            assertEquals(QualificationPacketPublisher.FailureReason.CONFLICT, failure.reason());
            assertFalse(Files.exists(
                    packet.runRoot().resolve(QualificationRunFinalizer.RESULT_FILE)),
                    companion);
            assertEquals("conflicting orphan\n", Files.readString(conflict));
        }
    }

    @Test
    void trackedPowerShellTemplateOnlyInvokesJavaAuthority() throws Exception {
        Path template = Path.of("work-cycle-docs", "templates",
                "finalize-model-qualification-result-v6.ps1").toAbsolutePath().normalize();
        String script = Files.readString(template, StandardCharsets.UTF_8);
        String canonical = script.replace("\r\n", "\n").replace('\r', '\n');
        assertTrue(script.contains("finalizeModelQualificationResult"));
        assertEquals(script.indexOf("finalizeModelQualificationResult"),
                script.lastIndexOf("finalizeModelQualificationResult"));
        assertTrue(script.contains("-PqualificationRoot="));
        assertTrue(script.contains("-PqualificationRunRoot="));
        assertTrue(script.contains("-PqualificationScenario="));
        assertTrue(script.contains("reconcileModelQualificationRunPublication"));
        assertEquals(script.indexOf("reconcileModelQualificationRunPublication"),
                script.lastIndexOf("reconcileModelQualificationRunPublication"));
        assertTrue(script.indexOf("assertWritableModelQualificationCampaign")
                < script.indexOf("reconcileModelQualificationRunPublication"));
        assertTrue(script.indexOf("reconcileModelQualificationRunPublication")
                < script.indexOf("finalizeModelQualificationResult"));
        assertTrue(script.contains("-PqualificationServerPort="));
        assertTrue(script.contains(
                "Qualification publication reconciliation failed with exit code"));
        assertTrue(script.contains("$GradleExitCode = $LASTEXITCODE"));
        assertTrue(script.contains("if ($GradleExitCode -ne 0)"));
        assertTrue(script.contains("throw \"Java qualification result finalization failed"));
        assertEquals("048545b159177ae6833babc3fcc2386b88cc2c354c95fb462756387d70b40778",
                QualificationScenarioBank.sha256(canonical.getBytes(StandardCharsets.UTF_8)));
        for (String forbidden : Arrays.asList(
                "result.json", "causal-finalization.json", "canary.json",
                "convertto-json", "convertfrom-json", "writealltext", "readalltext",
                "set-content", "out-file", "get-content", "invoke-expression",
                "start-process", "causalowner", "causalreason", "answerorigin",
                "failureclass", "trustgate", "utilitypass", "comparativescore",
                "scoreeligible", "protocolversion")) {
            assertFalse(script.toLowerCase(java.util.Locale.ROOT).contains(forbidden), forbidden);
        }
    }

    private static QualificationTestPacket unfinalizedPacket(
            Path root,
            QualificationScenarioBank.Catalog catalog) throws Exception {
        QualificationTestPacket packet = QualificationTestPacket.create(root, catalog);
        if (catalog.protocolVersion() == QualificationProtocol.VERSION) {
            Path commit = QualificationPacketPublisher.commitPathForTest(
                    packet.root(),
                    QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION,
                    java.util.List.of(
                            packet.runRoot().resolve(QualificationCausalFinalizer.OUTPUT_FILE),
                            packet.runRoot().resolve(QualificationRunFinalizer.CANARY_FILE),
                            packet.runRoot().resolve(QualificationRunFinalizer.RESULT_FILE)));
            Files.deleteIfExists(commit);
        }
        Files.deleteIfExists(packet.runRoot().resolve(QualificationRunFinalizer.RESULT_FILE));
        Files.deleteIfExists(packet.runRoot().resolve(QualificationCausalFinalizer.OUTPUT_FILE));
        Files.deleteIfExists(packet.runRoot().resolve(QualificationRunFinalizer.CANARY_FILE));
        return packet;
    }

    private static String scenarioId(QualificationScenarioBank.Catalog catalog) {
        return catalog.admission().scenarios().getFirst().id();
    }

    private static void assertNoAuthorityArtifacts(Path runRoot) {
        assertFalse(Files.exists(runRoot.resolve(QualificationCausalFinalizer.OUTPUT_FILE)));
        assertFalse(Files.exists(runRoot.resolve(QualificationRunFinalizer.CANARY_FILE)));
        assertFalse(Files.exists(runRoot.resolve(QualificationRunFinalizer.RESULT_FILE)));
    }
}
