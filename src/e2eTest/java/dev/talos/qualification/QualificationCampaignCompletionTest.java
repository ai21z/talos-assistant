package dev.talos.qualification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationCampaignCompletionTest {
    @TempDir
    Path temp;

    @Test
    void hardStopCompletionKeepsCampaignIdentityImmutableAndExactRetryReadOnly()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.createHardStopCampaign(
                temp.resolve("hard-stop"), catalog,
                catalog.admission().scenarios().getFirst());
        Path campaign = packet.root().resolve("campaign.json");
        byte[] campaignBytes = Files.readAllBytes(campaign);
        var campaignMtime = Files.getLastModifiedTime(campaign);

        QualificationCampaignCompletion.Publication first =
                QualificationCampaignCompletion.complete(
                        packet.root(), QualificationProtocol.BankKind.ADMISSION, catalog);
        Path commit = QualificationPacketPublisher.commitPathForTest(
                packet.root(),
                QualificationPacketPublisher.PublicationKind.CAMPAIGN_COMPLETION,
                List.of(first.path()));
        byte[] completionBytes = Files.readAllBytes(first.path());
        var completionMtime = Files.getLastModifiedTime(first.path());
        var commitMtime = Files.getLastModifiedTime(commit);

        QualificationCampaignCompletion.Publication second =
                QualificationCampaignCompletion.complete(
                        packet.root(), QualificationProtocol.BankKind.ADMISSION, catalog);

        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(QualificationRecords.CampaignCompletionReason.HARD_STOP,
                first.completion().completionReason());
        assertEquals(first.completion(), second.completion());
        assertTrue(Arrays.equals(campaignBytes, Files.readAllBytes(campaign)));
        assertEquals(campaignMtime, Files.getLastModifiedTime(campaign));
        assertTrue(Arrays.equals(completionBytes, Files.readAllBytes(first.path())));
        assertEquals(completionMtime, Files.getLastModifiedTime(first.path()));
        assertEquals(commitMtime, Files.getLastModifiedTime(commit));
        assertEquals(first.completion(), QualificationCampaignCompletion.requireCommitted(
                packet.root(), QualificationProtocol.BankKind.ADMISSION, catalog));
    }

    @Test
    void everyCompletionPublicationBoundaryRecoversTheSameDeterministicAuthority()
            throws Exception {
        List<QualificationPacketPublisher.FaultStage> stages = List.of(
                QualificationPacketPublisher.FaultStage.AFTER_INTENT_FLUSH,
                QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PARTIAL_WRITE,
                QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PROMOTION,
                QualificationPacketPublisher.FaultStage.BEFORE_COMMIT_PROMOTION,
                QualificationPacketPublisher.FaultStage.AFTER_COMMIT_PROMOTION,
                QualificationPacketPublisher.FaultStage.BEFORE_CLEANUP);
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();

        for (QualificationPacketPublisher.FaultStage stage : stages) {
            QualificationTestPacket packet = QualificationTestPacket.createHardStopCampaign(
                    temp.resolve("recover-" + stage.name().toLowerCase()),
                    catalog,
                    catalog.admission().scenarios().getFirst());
            Path campaign = packet.root().resolve("campaign.json");
            byte[] campaignBytes = Files.readAllBytes(campaign);
            var campaignMtime = Files.getLastModifiedTime(campaign);

            assertThrows(IOException.class, () -> QualificationPacketPublisher.withLease(
                    packet.root(),
                    Duration.ofSeconds(5),
                    (observed, ordinal) -> {
                        if (observed == stage) throw new IOException("injected completion fault");
                    },
                    lease -> QualificationCampaignCompletion.completeUnderLease(
                            lease, QualificationProtocol.BankKind.ADMISSION, catalog)),
                    stage.name());

            if (stage != QualificationPacketPublisher.FaultStage.AFTER_COMMIT_PROMOTION
                    && stage != QualificationPacketPublisher.FaultStage.BEFORE_CLEANUP) {
                assertThrows(IOException.class, () ->
                        QualificationCampaignCompletion.requireCommitted(
                                packet.root(),
                                QualificationProtocol.BankKind.ADMISSION,
                                catalog), stage.name());
            }
            QualificationCampaignCompletion.Publication recovered =
                    QualificationCampaignCompletion.complete(
                            packet.root(), QualificationProtocol.BankKind.ADMISSION, catalog);

            assertEquals(QualificationRecords.CampaignCompletionReason.HARD_STOP,
                    recovered.completion().completionReason(), stage.name());
            assertTrue(Arrays.equals(campaignBytes, Files.readAllBytes(campaign)), stage.name());
            assertEquals(campaignMtime, Files.getLastModifiedTime(campaign), stage.name());
            QualificationPacketPublisher.requireSettledControlState(packet.root());
        }
    }

    @Test
    void pausedCompletionChildExposesOnlyPrecomputedCanonicalBytesBeforeCommit()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.createHardStopCampaign(
                temp.resolve("paused-completion-child"), catalog,
                catalog.admission().scenarios().getFirst());
        byte[] expected = oracleCompletionBytes(
                packet.root(), temp.resolve("paused-completion-oracle"), catalog);
        Path controls = temp.resolve("paused-completion-controls");
        Files.createDirectories(controls);
        Path ready = controls.resolve("ready");
        Path release = controls.resolve("release");
        Path stageReady = ready.resolveSibling(ready.getFileName() + ".stage");
        Path stageRelease = release.resolveSibling(release.getFileName() + ".stage");
        Process child = startJava(
                QualificationPacketPublicationProcessMain.class.getName(),
                "matrix-pause",
                packet.root().toString(),
                packet.runRoot().toString(),
                ready.toString(),
                release.toString(),
                "canonical-completion",
                "15000",
                QualificationPacketPublisher.PublicationKind.CAMPAIGN_COMPLETION.name(),
                QualificationPacketPublisher.FaultStage.BEFORE_COMMIT_PROMOTION.name(),
                "-1");
        try {
            awaitFile(ready, child);
            Files.writeString(release, "go\n", StandardCharsets.UTF_8);
            awaitFile(stageReady, child);
            Path completion = packet.root().resolve(
                    QualificationCampaignCompletion.OUTPUT_FILE);

            assertTrue(Files.exists(completion));
            assertTrue(Arrays.equals(expected, Files.readAllBytes(completion)));
            QualificationPacketPublisher.PublicationException incomplete = assertThrows(
                    QualificationPacketPublisher.PublicationException.class,
                    () -> QualificationPacketPublisher.requireCommitted(
                            packet.root(),
                            QualificationPacketPublisher.PublicationKind.CAMPAIGN_COMPLETION,
                            List.of(completion)));
            assertEquals(QualificationPacketPublisher.FailureReason.INCOMPLETE,
                    incomplete.reason());

            Files.writeString(stageRelease, "continue\n", StandardCharsets.UTF_8);
            ProcessResult result = awaitProcess(child);
            assertEquals(0, result.exit(), result.output());
            assertTrue(Arrays.equals(expected, Files.readAllBytes(completion)));
            QualificationCampaignCompletion.requireCommitted(
                    packet.root(), QualificationProtocol.BankKind.ADMISSION, catalog);
        } finally {
            if (child.isAlive()) {
                Files.writeString(stageRelease, "continue\n", StandardCharsets.UTF_8);
                child.destroyForcibly();
                child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void operationalCompleteCampaignCommandPublishesCanonicalBytesInAChildJvm()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.createHardStopCampaign(
                temp.resolve("operational-completion-child"), catalog,
                catalog.admission().scenarios().getFirst());
        byte[] expected = oracleCompletionBytes(
                packet.root(), temp.resolve("operational-completion-oracle"), catalog);
        Process child = startJava(
                ModelQualificationMain.class.getName(),
                "complete-campaign",
                "--root", packet.root().toString(),
                "--bank", "admission");

        ProcessResult result = awaitProcess(child);

        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("Completion reason: HARD_STOP"), result::output);
        Path completion = packet.root().resolve(QualificationCampaignCompletion.OUTPUT_FILE);
        assertTrue(Arrays.equals(expected, Files.readAllBytes(completion)));
        QualificationCampaignCompletion.requireCommitted(
                packet.root(), QualificationProtocol.BankKind.ADMISSION, catalog);
    }

    @Test
    void nonblankProtocolSixEndedAtCannotBeAdoptedAsCompletion() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.createHardStopCampaign(
                temp.resolve("legacy-ended-at"), catalog,
                catalog.admission().scenarios().getFirst());
        Path campaign = packet.root().resolve("campaign.json");
        var json = (com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.readTree(campaign);
        json.put("endedAt", "2026-07-10T00:00:03Z");
        QualificationJson.write(campaign, json);
        byte[] changed = Files.readAllBytes(campaign);

        assertThrows(IOException.class, () -> QualificationCampaignCompletion.complete(
                packet.root(), QualificationProtocol.BankKind.ADMISSION, catalog));

        assertTrue(Arrays.equals(changed, Files.readAllBytes(campaign)));
        assertFalse(Files.exists(packet.root().resolve(
                QualificationCampaignCompletion.OUTPUT_FILE)));
    }

    @Test
    void publisherRejectsShapeValidCompletionThatIsNotDerivedFromRunAuthority()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.createHardStopCampaign(
                temp.resolve("false-completion-authority"), catalog,
                catalog.admission().scenarios().getFirst());
        Path completion = packet.root().resolve(QualificationCampaignCompletion.OUTPUT_FILE);

        IOException failure = assertThrows(IOException.class, () ->
                QualificationPacketPublisher.withLease(
                        packet.root(),
                        lease -> lease.publish(
                                QualificationPacketPublisher.PublicationKind.CAMPAIGN_COMPLETION,
                                List.of(new QualificationPacketPublisher.Member(
                                        completion,
                                        QualificationTestPacket.campaignCompletionFixtureBytes(
                                                packet.root()))))));

        assertTrue(failure.getMessage().contains("committed run authority"),
                failure::getMessage);
        assertFalse(Files.exists(completion));
        assertEquals(QualificationPacketPublisher.PublicationStatus.ABSENT,
                QualificationPacketPublisher.withLease(
                        packet.root(),
                        lease -> lease.publicationStatus(
                                QualificationPacketPublisher.PublicationKind.CAMPAIGN_COMPLETION,
                                List.of(completion))));
    }

    @Test
    void firstCaptureCannotPredateTheDeclaredCampaignStart() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.createHardStopCampaign(
                temp.resolve("capture-before-campaign"), catalog,
                catalog.admission().scenarios().getFirst());
        List<Path> runAuthority = List.of(
                packet.runRoot().resolve(QualificationCausalFinalizer.OUTPUT_FILE),
                packet.runRoot().resolve(QualificationRunFinalizer.CANARY_FILE),
                packet.runRoot().resolve(QualificationRunFinalizer.RESULT_FILE));
        packet.removePublicationForTest(
                QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION,
                runAuthority);
        Path requirements = packet.runRoot().resolve(
                QualificationRequirementFinalizer.OUTPUT_FILE);
        packet.removePublicationForTest(
                QualificationPacketPublisher.PublicationKind.MECHANICAL_REQUIREMENTS,
                List.of(requirements));
        Path capture = packet.runRoot().resolve("transcript.raw.json");
        var json = (com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.readTree(capture);
        json.put("captureStartedAt", "2026-07-09T23:59:59Z");
        QualificationJson.write(capture, json);
        packet.refreshPublicationForTest(
                QualificationPacketPublisher.PublicationKind.PTY_CAPTURE,
                List.of(capture));
        QualificationRequirementFinalizer.evaluateAndWrite(
                packet.root(), packet.runRoot(),
                catalog.admission().scenarios().getFirst().id(), catalog);
        QualificationRunFinalizer.finalizeRun(
                packet.root(), packet.runRoot(),
                catalog.admission().scenarios().getFirst().id(), catalog);

        IOException failure = assertThrows(IOException.class, () ->
                QualificationCampaignCompletion.complete(
                        packet.root(), QualificationProtocol.BankKind.ADMISSION, catalog));

        assertTrue(failure.getMessage().contains(
                "campaign capture starts before campaign start"), failure::getMessage);
        assertFalse(Files.exists(packet.root().resolve(
                QualificationCampaignCompletion.OUTPUT_FILE)));
    }

    @Test
    void exactAdmissionAndFullGridsClassifyCompleteWithoutWeakeningTrustGates()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        List<QualificationCampaignCompletion.Coordinate> admission =
                QualificationCampaignCompletion.expectedCoordinates(
                        QualificationProtocol.BankKind.ADMISSION, catalog);
        List<QualificationCampaignCompletion.Coordinate> full =
                QualificationCampaignCompletion.expectedCoordinates(
                        QualificationProtocol.BankKind.FULL, catalog);

        QualificationCampaignCompletion.LifecycleDecision admissionDecision =
                QualificationCampaignCompletion.evaluateLifecycle(
                        QualificationProtocol.BankKind.ADMISSION,
                        catalog,
                        continuingFacts(admission));
        QualificationCampaignCompletion.LifecycleDecision fullDecision =
                QualificationCampaignCompletion.evaluateLifecycle(
                        QualificationProtocol.BankKind.FULL,
                        catalog,
                        continuingFacts(full));

        assertEquals(12, admission.size());
        assertEquals(72, full.size());
        assertEquals(QualificationRecords.CampaignCompletionReason.COMPLETE_GRID,
                admissionDecision.reason());
        assertEquals(QualificationRecords.CampaignCompletionReason.COMPLETE_GRID,
                fullDecision.reason());
        int fullScenarioCount = catalog.full().scenarios().size();
        assertEquals(1, full.get(fullScenarioCount - 1).repetition());
        assertEquals(2, full.get(fullScenarioCount).repetition());
        assertEquals(catalog.full().scenarios().getFirst().id(),
                full.get(fullScenarioCount).scenarioId());
    }

    @Test
    void exactElevenOfTwelveAndSeventyOneOfSeventyTwoRemainIncomplete() {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        List<QualificationCampaignCompletion.Coordinate> admission =
                QualificationCampaignCompletion.expectedCoordinates(
                        QualificationProtocol.BankKind.ADMISSION, catalog);
        List<QualificationCampaignCompletion.Coordinate> full =
                QualificationCampaignCompletion.expectedCoordinates(
                        QualificationProtocol.BankKind.FULL, catalog);

        IOException admissionFailure = assertThrows(IOException.class, () ->
                QualificationCampaignCompletion.evaluateLifecycle(
                        QualificationProtocol.BankKind.ADMISSION,
                        catalog,
                        continuingFacts(admission.subList(0, admission.size() - 1))));
        IOException fullFailure = assertThrows(IOException.class, () ->
                QualificationCampaignCompletion.evaluateLifecycle(
                        QualificationProtocol.BankKind.FULL,
                        catalog,
                        continuingFacts(full.subList(0, full.size() - 1))));

        assertTrue(admissionFailure.getMessage().contains("incomplete"),
                admissionFailure::getMessage);
        assertTrue(fullFailure.getMessage().contains("incomplete"),
                fullFailure::getMessage);
    }

    @Test
    void campaignCompletionJsonRejectsNullCoercionDuplicateAndUnknownFields()
            throws Exception {
        String hash = "a".repeat(64);
        QualificationRecords.CampaignCompletion canonical =
                new QualificationRecords.CampaignCompletion(
                        QualificationRecords.CAMPAIGN_COMPLETION_SCHEMA,
                        QualificationRecords.CAMPAIGN_COMPLETION_SCHEMA_VERSION,
                        QualificationProtocol.VERSION,
                        "campaign-1",
                        hash,
                        QualificationProtocol.BankKind.ADMISSION,
                        hash,
                        "2026-07-10T00:00:03Z",
                        QualificationRecords.CampaignCompletionReason.HARD_STOP,
                        12,
                        1,
                        hash,
                        List.of(new QualificationRecords.CampaignRunCommitRef(
                                "F01", 1, "r1", hash, hash)),
                        List.of("incomplete-evidence"));

        var nullTopLevel = (com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.tree(canonical);
        nullTopLevel.putNull("campaignId");
        var nullNested = (com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.tree(canonical);
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                nullNested.withArray("committedRuns").get(0)).putNull("scenarioId");
        var coercedTopLevel = (com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.tree(canonical);
        coercedTopLevel.put("expectedTrialCount", "12");
        var coercedNested = (com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.tree(canonical);
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                coercedNested.withArray("committedRuns").get(0)).put("repetition", "1");
        var unknownTopLevel = (com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.tree(canonical);
        unknownTopLevel.put("unowned", true);
        var unknownNested = (com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.tree(canonical);
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                unknownNested.withArray("committedRuns").get(0)).put("unowned", true);

        String canonicalJson = new String(
                QualificationJson.bytes(canonical), StandardCharsets.UTF_8);
        byte[] duplicateTopLevel = ("{\"schemaName\":\"duplicate\","
                + canonicalJson.substring(1)).getBytes(StandardCharsets.UTF_8);
        String nestedRepetition = "\"repetition\" : 1";
        assertTrue(canonicalJson.contains(nestedRepetition));
        byte[] duplicateNested = canonicalJson.replace(
                        nestedRepetition,
                        nestedRepetition + ", \"repetition\" : 2")
                .getBytes(StandardCharsets.UTF_8);

        record InvalidCompletionJson(String id, byte[] bytes) {}
        List<InvalidCompletionJson> invalid = List.of(
                new InvalidCompletionJson("null-top-level",
                        QualificationJson.bytes(nullTopLevel)),
                new InvalidCompletionJson("null-nested",
                        QualificationJson.bytes(nullNested)),
                new InvalidCompletionJson("coerced-top-level",
                        QualificationJson.bytes(coercedTopLevel)),
                new InvalidCompletionJson("coerced-nested",
                        QualificationJson.bytes(coercedNested)),
                new InvalidCompletionJson("duplicate-top-level", duplicateTopLevel),
                new InvalidCompletionJson("duplicate-nested", duplicateNested),
                new InvalidCompletionJson("unknown-top-level",
                        QualificationJson.bytes(unknownTopLevel)),
                new InvalidCompletionJson("unknown-nested",
                        QualificationJson.bytes(unknownNested)));

        for (InvalidCompletionJson malformed : invalid) {
            assertThrows(IOException.class, () -> QualificationJson.read(
                            malformed.bytes(),
                            QualificationRecords.CampaignCompletion.class),
                    malformed.id());
        }
    }

    @Test
    void campaignCannotContinueAfterAnEarlierMechanicalHardStop() {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        List<QualificationCampaignCompletion.Coordinate> expected =
                QualificationCampaignCompletion.expectedCoordinates(
                        QualificationProtocol.BankKind.ADMISSION, catalog);
        List<QualificationCampaignCompletion.LifecycleFact> facts =
                new ArrayList<>(continuingFacts(expected.subList(0, 2)));
        QualificationCampaignCompletion.LifecycleFact first = facts.getFirst();
        facts.set(0, new QualificationCampaignCompletion.LifecycleFact(
                first.coordinate(),
                first.captureStartedAt(),
                first.captureEndedAt(),
                new QualificationCampaignContinuationPolicy.Decision(
                        true, List.of("incomplete-evidence"))));

        IOException failure = assertThrows(IOException.class, () ->
                QualificationCampaignCompletion.evaluateLifecycle(
                        QualificationProtocol.BankKind.ADMISSION, catalog, facts));

        assertTrue(failure.getMessage().contains(
                "continued after a mechanically required hard stop"), failure::getMessage);
    }

    @Test
    void campaignCommittedRunsCannotStartAfterACoordinateHole() {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        List<QualificationCampaignCompletion.Coordinate> expected =
                QualificationCampaignCompletion.expectedCoordinates(
                        QualificationProtocol.BankKind.ADMISSION, catalog);
        List<QualificationCampaignCompletion.LifecycleFact> facts =
                continuingFacts(List.of(expected.get(1)));

        IOException failure = assertThrows(IOException.class, () ->
                QualificationCampaignCompletion.evaluateLifecycle(
                        QualificationProtocol.BankKind.ADMISSION, catalog, facts));

        assertTrue(failure.getMessage().contains("exact bank-order prefix"),
                failure::getMessage);
    }

    @Test
    void fullCampaignHardStopUsesRepetitionMajorOrderAndMonotonicCaptures() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        List<QualificationCampaignCompletion.Coordinate> expected =
                QualificationCampaignCompletion.expectedCoordinates(
                        QualificationProtocol.BankKind.FULL, catalog);
        List<QualificationCampaignCompletion.LifecycleFact> prefix =
                new ArrayList<>(continuingFacts(expected.subList(0, 2)));
        QualificationCampaignCompletion.LifecycleFact second = prefix.get(1);
        prefix.set(1, new QualificationCampaignCompletion.LifecycleFact(
                second.coordinate(),
                second.captureStartedAt(),
                second.captureEndedAt(),
                new QualificationCampaignContinuationPolicy.Decision(
                        true, List.of("incomplete-evidence"))));

        QualificationCampaignCompletion.LifecycleDecision completion =
                QualificationCampaignCompletion.evaluateLifecycle(
                        QualificationProtocol.BankKind.FULL, catalog, prefix);

        assertEquals(QualificationRecords.CampaignCompletionReason.HARD_STOP,
                completion.reason());
        assertEquals(catalog.full().scenarios().get(0).id(),
                prefix.get(0).coordinate().scenarioId());
        assertEquals(catalog.full().scenarios().get(1).id(),
                prefix.get(1).coordinate().scenarioId());
        assertEquals(1, prefix.get(0).coordinate().repetition());
        assertEquals(1, prefix.get(1).coordinate().repetition());

        QualificationCampaignCompletion.LifecycleFact overlapping =
                new QualificationCampaignCompletion.LifecycleFact(
                        second.coordinate(),
                        prefix.get(0).captureStartedAt().plusSeconds(1),
                        prefix.get(0).captureEndedAt().plusSeconds(1),
                        second.continuation());
        prefix.set(1, overlapping);
        IOException chronology = assertThrows(IOException.class, () ->
                QualificationCampaignCompletion.evaluateLifecycle(
                        QualificationProtocol.BankKind.FULL, catalog, prefix));
        assertTrue(chronology.getMessage().contains("chronology"), chronology::getMessage);
    }

    @Test
    void capturedWorkspaceResultFilenameIsNotMisclassifiedAsRunAuthority() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.createHardStopCampaign(
                temp.resolve("captured-result-name"),
                catalog,
                catalog.admission().scenarios().getFirst());
        List<Path> authority = List.of(
                packet.runRoot().resolve(QualificationCausalFinalizer.OUTPUT_FILE),
                packet.runRoot().resolve(QualificationRunFinalizer.CANARY_FILE),
                packet.runRoot().resolve(QualificationRunFinalizer.RESULT_FILE));
        packet.removePublicationForTest(
                QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION,
                authority);
        Path requirements = packet.runRoot().resolve(
                QualificationRequirementFinalizer.OUTPUT_FILE);
        packet.removePublicationForTest(
                QualificationPacketPublisher.PublicationKind.MECHANICAL_REQUIREMENTS,
                List.of(requirements));
        for (String directory : List.of("before-files", "after-files", "final-files")) {
            Files.writeString(
                    packet.runRoot().resolve(directory).resolve("result.json"),
                    "captured workspace content\n",
                    StandardCharsets.UTF_8);
        }
        QualificationJson.write(
                packet.runRoot().resolve("before-hashes.json"),
                QualificationFixtureMaterializer.snapshot(
                        packet.runRoot().resolve("before-files")).sha256());
        QualificationJson.write(
                packet.runRoot().resolve("after-hashes.json"),
                QualificationFixtureMaterializer.snapshot(
                        packet.runRoot().resolve("after-files")).sha256());
        QualificationRequirementFinalizer.evaluateAndWrite(
                packet.root(), packet.runRoot(),
                catalog.admission().scenarios().getFirst().id(), catalog);
        QualificationRunFinalizer.finalizeRun(
                packet.root(), packet.runRoot(),
                catalog.admission().scenarios().getFirst().id(), catalog);

        QualificationRecords.CampaignCompletion completion =
                QualificationCampaignCompletion.complete(
                        packet.root(), QualificationProtocol.BankKind.ADMISSION, catalog)
                        .completion();

        assertEquals(1, completion.committedRunCount());
        assertEquals(QualificationRecords.CampaignCompletionReason.HARD_STOP,
                completion.completionReason());
    }

    private static List<QualificationCampaignCompletion.LifecycleFact> continuingFacts(
            List<QualificationCampaignCompletion.Coordinate> coordinates) {
        Instant base = Instant.parse("2026-07-10T00:00:00Z");
        List<QualificationCampaignCompletion.LifecycleFact> facts = new ArrayList<>();
        for (int index = 0; index < coordinates.size(); index++) {
            Instant started = base.plusSeconds(index * 3L);
            facts.add(new QualificationCampaignCompletion.LifecycleFact(
                    coordinates.get(index),
                    started,
                    started.plusSeconds(2),
                    new QualificationCampaignContinuationPolicy.Decision(false, List.of())));
        }
        return List.copyOf(facts);
    }

    private static byte[] oracleCompletionBytes(
            Path packetRoot,
            Path oracleRoot,
            QualificationScenarioBank.Catalog catalog) throws Exception {
        copyTree(packetRoot, oracleRoot);
        QualificationCampaignCompletion.Publication oracle =
                QualificationCampaignCompletion.complete(
                        oracleRoot, QualificationProtocol.BankKind.ADMISSION, catalog);
        return Files.readAllBytes(oracle.path());
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }

    private static Process startJava(String mainClass, String... arguments) throws IOException {
        String executable = System.getProperty("os.name", "")
                .toLowerCase()
                .contains("win") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(mainClass);
        command.addAll(List.of(arguments));
        return new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
    }

    private static void awaitFile(Path path, Process process) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!Files.exists(path)) {
            if (!process.isAlive()) {
                ProcessResult result = awaitProcess(process);
                throw new IllegalStateException(
                        "completion child exited before " + path + ": " + result.output());
            }
            if (System.nanoTime() >= deadline) {
                process.destroyForcibly();
                throw new IllegalStateException(
                        "completion child did not create " + path);
            }
            Thread.sleep(10L);
        }
    }

    private static ProcessResult awaitProcess(Process process) throws Exception {
        if (!process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            throw new IllegalStateException("completion child did not exit");
        }
        return new ProcessResult(
                process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private record ProcessResult(int exit, String output) {}
}
