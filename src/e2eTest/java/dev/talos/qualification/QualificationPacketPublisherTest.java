package dev.talos.qualification;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationPacketPublisherTest {
    @TempDir
    Path temp;

    @Test
    void exactProviderRetryPreservesEveryMemberAndCommitMtime() throws Exception {
        Fixture fixture = fixture("exact-retry");
        QualificationPacketPublisher.CommittedSet first = publish(fixture);
        var promptMtime = Files.getLastModifiedTime(fixture.prompt());
        var modelMtime = Files.getLastModifiedTime(fixture.model());
        var commitMtime = Files.getLastModifiedTime(first.commitPath());

        QualificationPacketPublisher.CommittedSet second = publish(fixture);

        assertFalse(second.created());
        assertEquals(promptMtime, Files.getLastModifiedTime(fixture.prompt()));
        assertEquals(modelMtime, Files.getLastModifiedTime(fixture.model()));
        assertEquals(commitMtime, Files.getLastModifiedTime(first.commitPath()));
        assertArrayEquals(fixture.promptBytes(), Files.readAllBytes(fixture.prompt()));
        assertArrayEquals(fixture.modelBytes(), Files.readAllBytes(fixture.model()));
    }

    @Test
    void exactCommittedEvidenceRetryRemainsReadOnlyAfterCampaignEnd() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.createHardStopCampaign(
                temp.resolve("ended-exact-retry"), catalog,
                catalog.admission().scenarios().getFirst());
        Path prompt = packet.runRoot().resolve("prompt-debug.txt");
        Path model = packet.runRoot().resolve("llama.log");
        QualificationPacketPublisher.CommittedSet first =
                QualificationPacketPublisher.requireCommitted(
                        packet.root(),
                        QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                        List.of(prompt, model));
        endCampaign(packet);
        java.util.Map<String, String> before = treeState(packet.root());

        QualificationPacketPublisher.CommittedSet second =
                QualificationPacketPublisher.withLease(
                        packet.root(),
                        lease -> lease.publish(
                                QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                                List.of(
                                        new QualificationPacketPublisher.Member(
                                                prompt, Files.readAllBytes(prompt)),
                                        new QualificationPacketPublisher.Member(
                                                model, Files.readAllBytes(model)))));

        assertFalse(second.created());
        assertEquals(first.commitPath(), second.commitPath());
        assertEquals(before, treeState(packet.root()));
    }

    @Test
    void endedCampaignRejectsNewEvidenceWithoutChangingPacketState() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.createHardStopCampaign(
                temp.resolve("ended-new-evidence"), catalog,
                catalog.admission().scenarios().getFirst());
        endCampaign(packet);
        Path run = packet.root().resolve("runs/qwen14/reference/standard")
                .resolve(catalog.admission().scenarios().get(1).id())
                .resolve("trial-01");
        Path prompt = run.resolve("prompt-debug.txt");
        Path model = run.resolve("llama.log");
        java.util.Map<String, String> before = treeState(packet.root());

        QualificationPacketPublisher.PublicationException failure = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> QualificationPacketPublisher.withLease(
                        packet.root(),
                        lease -> lease.publish(
                                QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                                List.of(
                                        new QualificationPacketPublisher.Member(
                                                prompt, "new prompt\n".getBytes(
                                                        StandardCharsets.UTF_8)),
                                        new QualificationPacketPublisher.Member(
                                                model, "new model\n".getBytes(
                                                        StandardCharsets.UTF_8))))));

        assertEquals(QualificationPacketPublisher.FailureReason.INVALID_REQUEST,
                failure.reason());
        assertEquals(before, treeState(packet.root()));
    }

    @Test
    void activeCampaignRejectsScorePublicationWithoutCreatingAnyState() throws Exception {
        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve("active-score"), QualificationTestPacket.protocol6Catalog());
        Path target = packet.root().resolve("scorecards/qwen14-standard-admission.json");
        java.util.Map<String, String> before = treeState(packet.root());

        QualificationPacketPublisher.PublicationException failure = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> QualificationPacketPublisher.withLease(packet.root(), lease ->
                        lease.publish(
                                QualificationPacketPublisher.PublicationKind.SCORECARD,
                                List.of(new QualificationPacketPublisher.Member(
                                        target,
                                        "{}\n".getBytes(StandardCharsets.UTF_8))))));

        assertEquals(QualificationPacketPublisher.FailureReason.INVALID_REQUEST,
                failure.reason());
        assertEquals(before, treeState(packet.root()));
        assertFalse(Files.exists(target.getParent()));
    }

    @Test
    void malformedCommittedCompletionCannotAuthorizeScorePublication() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.createHardStopCampaign(
                temp.resolve("malformed-completion"), catalog,
                catalog.admission().scenarios().getFirst());
        endCampaign(packet);
        Path completion = packet.root().resolve(QualificationCampaignCompletion.OUTPUT_FILE);
        Files.writeString(
                completion,
                Files.readString(completion, StandardCharsets.UTF_8) + "trailing",
                StandardCharsets.UTF_8);
        Path target = packet.root().resolve("scorecards/qwen14-standard-admission.json");
        java.util.Map<String, String> before = treeState(packet.root());

        QualificationPacketPublisher.PublicationException failure = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> QualificationPacketPublisher.withLease(
                        packet.root(),
                        lease -> lease.publish(
                                QualificationPacketPublisher.PublicationKind.SCORECARD,
                                List.of(new QualificationPacketPublisher.Member(
                                        target,
                                        "{}\n".getBytes(StandardCharsets.UTF_8))))));

        assertEquals(QualificationPacketPublisher.FailureReason.INVALID_COMMIT,
                failure.reason());
        assertEquals(before, treeState(packet.root()));
        assertFalse(Files.exists(target));
    }

    @Test
    void runPhasePublicationRequiresAndBindsItsCommittedPredecessor() throws Exception {
        Fixture fixture = fixture("run-phase-order");
        Path approvals = fixture.packet().runRoot().resolve(
                QualificationApprovalFinalizer.OUTPUT_FILE);
        byte[] approvalBytes = QualificationJson.bytes(
                new QualificationApprovalFinalizer.Document(List.of()));
        java.util.Map<String, String> before = treeState(fixture.packet().root());

        QualificationPacketPublisher.PublicationException outOfOrder = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> QualificationPacketPublisher.withLease(
                        fixture.packet().root(),
                        lease -> lease.publish(
                                QualificationPacketPublisher.PublicationKind.APPROVAL_EVIDENCE,
                                List.of(new QualificationPacketPublisher.Member(
                                        approvals, approvalBytes)))));

        assertEquals(QualificationPacketPublisher.FailureReason.INVALID_REQUEST,
                outOfOrder.reason());
        assertTrue(outOfOrder.getMessage().contains("committed predecessor"),
                outOfOrder::getMessage);
        assertEquals(before, treeState(fixture.packet().root()));

        QualificationPacketPublisher.CommittedSet provider = publish(fixture);
        QualificationPacketPublisher.CommittedSet approval =
                QualificationPacketPublisher.withLease(
                        fixture.packet().root(),
                        lease -> lease.publish(
                                QualificationPacketPublisher.PublicationKind.APPROVAL_EVIDENCE,
                                List.of(new QualificationPacketPublisher.Member(
                                        approvals, approvalBytes))));

        assertEquals(2, approval.commit().runPhaseOrdinal());
        assertEquals(provider.commit().transactionId(),
                approval.commit().predecessorTransactionId());
        assertEquals(
                QualificationScenarioBank.sha256(Files.readAllBytes(provider.commitPath())),
                approval.commit().predecessorCommitSha256());
    }

    @Test
    void incompleteRunTransactionCannotBeMisrepresentedAsCampaignCompletion() throws Exception {
        Fixture fixture = fixture("active-intent-after-end");
        assertThrows(
                IOException.class,
                () -> publishWithFault(
                        fixture,
                        QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PARTIAL_WRITE,
                        0));
        IOException blocked = assertThrows(
                IOException.class,
                () -> endCampaign(fixture.packet()));
        assertTrue(blocked.getMessage().contains("committed run results"),
                blocked::getMessage);
        assertFalse(Files.exists(fixture.packet().root().resolve(
                QualificationCampaignCompletion.OUTPUT_FILE)));

        QualificationPacketPublisher.CommittedSet recovered = publish(fixture);

        assertTrue(recovered.created());
        assertArrayEquals(fixture.promptBytes(), Files.readAllBytes(fixture.prompt()));
        assertArrayEquals(fixture.modelBytes(), Files.readAllBytes(fixture.model()));
        assertNoOpenIntent(fixture.packet().root());
    }

    @Test
    void malformedIntentCampaignPhaseFailsClosedWithoutChangingRecoveryBytes()
            throws Exception {
        for (String corruption : List.of(
                "null", "unknown", "number", "extra-field", "duplicate")) {
            Fixture fixture = fixture("intent-phase-" + corruption);
            assertThrows(
                    IOException.class,
                    () -> publishWithFault(
                            fixture,
                            QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PARTIAL_WRITE,
                            0));
            Path intent = onlyEntry(fixture.packet().root(), "intents");
            byte[] original = Files.readAllBytes(intent);
            ObjectNode json = (ObjectNode) QualificationJson.readTree(original);
            switch (corruption) {
                case "null" -> json.putNull("campaignPhase");
                case "unknown" -> json.put("campaignPhase", "PAUSED");
                case "number" -> json.put("campaignPhase", 1);
                case "extra-field" -> json.put("unexpected", true);
                case "duplicate" -> {
                    String text = new String(original, StandardCharsets.UTF_8);
                    Files.writeString(
                            intent,
                            text.replaceFirst(
                                    "\\{",
                                    "{\n  \"campaignPhase\" : \"ACTIVE\","),
                            StandardCharsets.UTF_8);
                }
                default -> throw new IllegalStateException(corruption);
            }
            if (!corruption.equals("duplicate")) {
                Files.write(intent, QualificationJson.bytes(json));
            }
            byte[] corrupted = Files.readAllBytes(intent);

            QualificationPacketPublisher.PublicationException failure = assertThrows(
                    QualificationPacketPublisher.PublicationException.class,
                    () -> publish(fixture));

            assertEquals(QualificationPacketPublisher.FailureReason.UNSAFE_RECOVERY,
                    failure.reason(), corruption);
            assertArrayEquals(corrupted, Files.readAllBytes(intent), corruption);
            assertFalse(Files.exists(fixture.prompt()), corruption);
            assertFalse(Files.exists(fixture.model()), corruption);
        }
    }

    @Test
    void conflictAtSecondMemberChangesNoMissingFirstMember() throws Exception {
        Fixture fixture = fixture("preflight-conflict");
        Files.writeString(fixture.model(), "conflicting model\n", StandardCharsets.UTF_8);

        QualificationPacketPublisher.PublicationException failure = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> publish(fixture));

        assertEquals(QualificationPacketPublisher.FailureReason.CONFLICT, failure.reason());
        assertFalse(Files.exists(fixture.prompt()));
        assertEquals("conflicting model\n", Files.readString(fixture.model()));
        assertFalse(Files.exists(commitPath(fixture)));
    }

    @Test
    void completeMembersWithoutCommitAreNeverAcceptedOrAdopted() throws Exception {
        Fixture fixture = fixture("unowned-complete");
        Files.write(fixture.prompt(), fixture.promptBytes());
        Files.write(fixture.model(), fixture.modelBytes());

        QualificationPacketPublisher.PublicationException publishFailure = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> publish(fixture));
        QualificationPacketPublisher.PublicationException readFailure = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> read(fixture));

        assertEquals(QualificationPacketPublisher.FailureReason.INCOMPLETE,
                publishFailure.reason());
        assertEquals(QualificationPacketPublisher.FailureReason.INCOMPLETE,
                readFailure.reason());
        assertFalse(Files.exists(commitPath(fixture)));
    }

    @Test
    void transactionOwnedPartialStageAndPromotedMemberRecoverExactly() throws Exception {
        for (QualificationPacketPublisher.FaultStage stage : List.of(
                QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PARTIAL_WRITE,
                QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PROMOTION,
                QualificationPacketPublisher.FaultStage.BEFORE_COMMIT_PROMOTION)) {
            Fixture fixture = fixture("recovery-" + stage.name().toLowerCase());
            int ordinal = stage == QualificationPacketPublisher.FaultStage.BEFORE_COMMIT_PROMOTION
                    ? -1 : 0;
            assertThrows(IOException.class, () -> publishWithFault(fixture, stage, ordinal));
            assertThrows(
                    QualificationPacketPublisher.PublicationException.class,
                    () -> read(fixture));

            QualificationPacketPublisher.CommittedSet recovered = publish(fixture);

            assertTrue(Files.isRegularFile(recovered.commitPath()));
            assertArrayEquals(fixture.promptBytes(), Files.readAllBytes(fixture.prompt()));
            assertArrayEquals(fixture.modelBytes(), Files.readAllBytes(fixture.model()));
            assertNoOpenIntent(fixture.packet().root());
        }
    }

    @Test
    void crashAfterCommitPromotionIsCommittedAndRetryOnlyCleansOwnedState() throws Exception {
        Fixture fixture = fixture("after-commit");
        QualificationPacketPublisher.PublicationException failure = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> publishWithFault(
                        fixture,
                        QualificationPacketPublisher.FaultStage.AFTER_COMMIT_PROMOTION,
                        -1));
        assertEquals(QualificationPacketPublisher.CommitState.COMMITTED,
                failure.commitState());
        QualificationPacketPublisher.PublicationException incomplete = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> read(fixture));
        assertEquals(QualificationPacketPublisher.FailureReason.INCOMPLETE,
                incomplete.reason());
        Path commit = commitPath(fixture);
        var commitMtime = Files.getLastModifiedTime(commit);

        QualificationPacketPublisher.CommittedSet retry = publish(fixture);

        assertFalse(retry.created());
        assertEquals(commitMtime, Files.getLastModifiedTime(commit));
        read(fixture);
        assertNoOpenIntent(fixture.packet().root());
    }

    @Test
    void exactRetrySettlesAnExactOrphanIntentStageAfterCommit() throws Exception {
        Fixture fixture = fixture("committed-orphan-intent-stage-exact");
        Path orphan = retainOnlyIntentStageAfterCommittedInterruption(fixture);
        var promptMtime = Files.getLastModifiedTime(fixture.prompt());
        var modelMtime = Files.getLastModifiedTime(fixture.model());
        var commitMtime = Files.getLastModifiedTime(commitPath(fixture));

        QualificationPacketPublisher.CommittedSet retry = publish(fixture);

        assertFalse(retry.created());
        assertFalse(Files.exists(orphan));
        assertEquals(promptMtime, Files.getLastModifiedTime(fixture.prompt()));
        assertEquals(modelMtime, Files.getLastModifiedTime(fixture.model()));
        assertEquals(commitMtime, Files.getLastModifiedTime(commitPath(fixture)));
        read(fixture);
        QualificationPacketPublisher.requireSettledControlState(fixture.packet().root());
    }

    @Test
    void corruptOrphanIntentStageAfterCommitFailsClosedWithoutClaimingSuccess()
            throws Exception {
        Fixture fixture = fixture("committed-orphan-intent-stage-corrupt");
        Path orphan = retainOnlyIntentStageAfterCommittedInterruption(fixture);
        Files.writeString(orphan, "corrupt orphan intent stage\n", StandardCharsets.UTF_8);
        java.util.Map<String, String> before = treeState(fixture.packet().root());

        QualificationPacketPublisher.PublicationException failure = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> publish(fixture));

        assertEquals(QualificationPacketPublisher.FailureReason.CLEANUP_FAILED,
                failure.reason());
        assertEquals(QualificationPacketPublisher.CommitState.COMMITTED,
                failure.commitState());
        assertEquals(before, treeState(fixture.packet().root()));
        assertThrows(QualificationPacketPublisher.PublicationException.class,
                () -> read(fixture));
    }

    @Test
    void partialManifestBoundIntentStageIsRecoveredByTheExactTransaction() throws Exception {
        Fixture fixture = fixture("partial-intent-stage");
        assertThrows(
                IOException.class,
                () -> publishWithFault(
                        fixture,
                        QualificationPacketPublisher.FaultStage.AFTER_INTENT_FLUSH,
                        -2));
        Path stages = fixture.packet().root()
                .resolve(QualificationPacketPublisher.CONTROL_DIRECTORY)
                .resolve("stages");
        Path stage;
        try (var entries = Files.list(stages)) {
            stage = entries.filter(path -> path.getFileName().toString().startsWith("intent-"))
                    .findFirst()
                    .orElseThrow();
        }
        Files.writeString(stage, "partial", StandardCharsets.UTF_8);

        QualificationPacketPublisher.CommittedSet recovered = publish(fixture);

        assertTrue(Files.isRegularFile(recovered.commitPath()));
        assertArrayEquals(fixture.promptBytes(), Files.readAllBytes(fixture.prompt()));
        assertArrayEquals(fixture.modelBytes(), Files.readAllBytes(fixture.model()));
        assertNoOpenIntent(fixture.packet().root());
    }

    @Test
    void foreignIncompleteTransactionBlocksCompletionAndLaterScorePublication() throws Exception {
        Fixture fixture = fixture("foreign-incomplete");
        assertThrows(
                IOException.class,
                () -> publishWithFault(
                        fixture,
                        QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PARTIAL_WRITE,
                        0));
        IOException completionFailure = assertThrows(
                IOException.class,
                () -> endCampaign(fixture.packet()));
        assertTrue(completionFailure.getMessage().contains("committed run results"),
                completionFailure::getMessage);
        Path scorecard = fixture.packet().root()
                .resolve("scorecards/qwen14-standard-admission.json");

        QualificationPacketPublisher.PublicationException failure = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> QualificationPacketPublisher.withLease(
                        fixture.packet().root(),
                        lease -> lease.publish(
                                QualificationPacketPublisher.PublicationKind.SCORECARD,
                                List.of(new QualificationPacketPublisher.Member(
                                        scorecard,
                                        "{}\n".getBytes(StandardCharsets.UTF_8))))));

        assertEquals(
                QualificationPacketPublisher.FailureReason.INVALID_REQUEST,
                failure.reason());
        assertFalse(Files.exists(scorecard));
    }

    @Test
    void committedSetFromAnotherCampaignIdentityIsRejected() throws Exception {
        Fixture fixture = fixture("campaign-identity");
        publish(fixture);
        Path campaign = fixture.packet().root().resolve("campaign.json");
        ObjectNode json = (ObjectNode) QualificationJson.readTree(Files.readAllBytes(campaign));
        json.put("campaignId", "different-campaign");
        Files.write(campaign, QualificationJson.bytes(json));

        QualificationPacketPublisher.PublicationException failure = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> read(fixture));

        assertEquals(QualificationPacketPublisher.FailureReason.INVALID_COMMIT, failure.reason());
    }

    @Test
    void strictCommitParsingRejectsUnknownAndDuplicateFields() throws Exception {
        for (String corruption : List.of("unknown", "duplicate")) {
            Fixture fixture = fixture("strict-" + corruption);
            QualificationPacketPublisher.CommittedSet committed = publish(fixture);
            byte[] original = Files.readAllBytes(committed.commitPath());
            String text = new String(original, StandardCharsets.UTF_8);
            if (corruption.equals("unknown")) {
                ObjectNode json = (ObjectNode) QualificationJson.readTree(original);
                json.put("unexpected", true);
                Files.write(committed.commitPath(), QualificationJson.bytes(json));
            } else {
                Files.writeString(
                        committed.commitPath(),
                        text.replaceFirst(
                                "\\{",
                                "{\n  \\\"schemaName\\\" : \\\""
                                        + QualificationPacketPublisher.COMMIT_SCHEMA
                                        + "\\\","),
                        StandardCharsets.UTF_8);
            }

            QualificationPacketPublisher.PublicationException failure = assertThrows(
                    QualificationPacketPublisher.PublicationException.class,
                    () -> read(fixture));
            assertEquals(QualificationPacketPublisher.FailureReason.INVALID_COMMIT,
                    failure.reason());
        }
    }

    @Test
    void alteredRunPhasePredecessorChainInvalidatesTheSettledPacket()
            throws Exception {
        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve("phase-chain-tamper"),
                QualificationTestPacket.protocol6Catalog());
        Path approvals = packet.runRoot().resolve(
                QualificationApprovalFinalizer.OUTPUT_FILE);
        Path commit = QualificationPacketPublisher.commitPathForTest(
                packet.root(),
                QualificationPacketPublisher.PublicationKind.APPROVAL_EVIDENCE,
                List.of(approvals));
        ObjectNode json = (ObjectNode) QualificationJson.readTree(commit);
        String original = json.path("predecessorCommitSha256").textValue();
        json.put(
                "predecessorCommitSha256",
                (original.charAt(0) == '0' ? "1" : "0") + original.substring(1));
        QualificationJson.write(commit, json);
        java.util.Map<String, String> before = treeState(packet.root());

        QualificationPacketPublisher.PublicationException failure = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> QualificationPacketPublisher.requireSettledControlState(
                        packet.root()));

        assertEquals(QualificationPacketPublisher.FailureReason.INVALID_COMMIT,
                failure.reason());
        assertEquals(before, treeState(packet.root()));
    }

    @Test
    void linkedTargetAndControlAncestorsFailBeforeExternalBytesChange() throws Exception {
        Fixture fixture = fixture("linked-containment");
        Path external = Files.createDirectory(temp.resolve("external-control"));
        Path canary = Files.writeString(
                external.resolve("canary.txt"), "EXTERNAL_CANARY\n", StandardCharsets.UTF_8);
        Path control = fixture.packet().root()
                .resolve(QualificationPacketPublisher.CONTROL_DIRECTORY);
        deleteTree(control);
        try {
            Files.createSymbolicLink(control, external);
        } catch (IOException | UnsupportedOperationException unavailable) {
            org.junit.jupiter.api.Assumptions.abort(
                    "symbolic-link fixture unavailable: "
                            + unavailable.getClass().getSimpleName());
        }

        assertThrows(IOException.class, () -> publish(fixture));
        assertEquals("EXTERNAL_CANARY\n", Files.readString(canary));
        assertFalse(Files.exists(fixture.prompt()));
        assertFalse(Files.exists(fixture.model()));
    }

    @Test
    void protocolV5FixtureCreatesNoPublicationMetadata() throws Exception {
        Path root = temp.resolve("legacy-v5");
        QualificationTestPacket.create(root, QualificationScenarioBank.load(5));
        assertFalse(Files.exists(root.resolve(QualificationPacketPublisher.CONTROL_DIRECTORY)));
    }

    @Test
    void everyCommitBoundaryReportsTheObservedTransactionState() throws Exception {
        java.util.Map<QualificationPacketPublisher.FaultStage,
                QualificationPacketPublisher.CommitState> expected = java.util.Map.of(
                QualificationPacketPublisher.FaultStage.AFTER_COMMIT_STAGE_FLUSH,
                QualificationPacketPublisher.CommitState.NOT_COMMITTED,
                QualificationPacketPublisher.FaultStage.BEFORE_COMMIT_PROMOTION,
                QualificationPacketPublisher.CommitState.NOT_COMMITTED,
                QualificationPacketPublisher.FaultStage.AFTER_TARGET_CREATE,
                QualificationPacketPublisher.CommitState.COMMITTED,
                QualificationPacketPublisher.FaultStage.AFTER_COMMIT_PROMOTION,
                QualificationPacketPublisher.CommitState.COMMITTED,
                QualificationPacketPublisher.FaultStage.AFTER_COMMIT_VERIFICATION,
                QualificationPacketPublisher.CommitState.COMMITTED,
                QualificationPacketPublisher.FaultStage.BEFORE_CLEANUP,
                QualificationPacketPublisher.CommitState.COMMITTED,
                QualificationPacketPublisher.FaultStage.AFTER_CLEANUP,
                QualificationPacketPublisher.CommitState.COMMITTED);
        for (var testCase : expected.entrySet()) {
            Fixture fixture = fixture("commit-state-" + testCase.getKey().name().toLowerCase());
            QualificationPacketPublisher.PublicationException failure = assertThrows(
                    QualificationPacketPublisher.PublicationException.class,
                    () -> publishWithFault(fixture, testCase.getKey(), -1));
            assertEquals(testCase.getValue(), failure.commitState(), testCase.getKey().name());

            QualificationPacketPublisher.CommittedSet recovered = publish(fixture);

            assertArrayEquals(fixture.promptBytes(), Files.readAllBytes(fixture.prompt()));
            assertArrayEquals(fixture.modelBytes(), Files.readAllBytes(fixture.model()));
            assertTrue(Files.isRegularFile(recovered.commitPath()));
            assertNoOpenIntent(fixture.packet().root());
        }
    }

    @Test
    void memberStageWithoutItsCanonicalIntentIsNeverAdoptedOrDeleted() throws Exception {
        for (boolean conflict : List.of(false, true)) {
            Fixture fixture = fixture("unowned-stage-" + conflict);
            assertThrows(
                    IOException.class,
                    () -> publishWithFault(
                            fixture,
                            QualificationPacketPublisher.FaultStage.AFTER_MEMBER_FINAL_WRITE,
                            0));
            Path stage = onlyStageWithPrefix(fixture.packet().root(), "member-");
            try (var intents = Files.list(fixture.packet().root()
                    .resolve(QualificationPacketPublisher.CONTROL_DIRECTORY)
                    .resolve("intents"))) {
                Files.delete(intents.findFirst().orElseThrow());
            }
            if (conflict) Files.writeString(stage, "foreign-stage\n", StandardCharsets.UTF_8);
            byte[] before = Files.readAllBytes(stage);
            var mtime = Files.getLastModifiedTime(stage);

            QualificationPacketPublisher.PublicationException failure = assertThrows(
                    QualificationPacketPublisher.PublicationException.class,
                    () -> publish(fixture));

            assertEquals(
                    QualificationPacketPublisher.FailureReason.UNSAFE_RECOVERY,
                    failure.reason());
            assertArrayEquals(before, Files.readAllBytes(stage));
            assertEquals(mtime, Files.getLastModifiedTime(stage));
            assertFalse(Files.exists(fixture.prompt()));
            assertFalse(Files.exists(fixture.model()));
        }
    }

    @Test
    void exactOrdinaryTargetWithoutItsPromotionLinkIsNeverAdopted() throws Exception {
        for (boolean retainSeparateStage : List.of(false, true)) {
            Fixture fixture = fixture("unowned-target-" + retainSeparateStage);
            assertThrows(
                    IOException.class,
                    () -> publishWithFault(
                            fixture,
                            QualificationPacketPublisher.FaultStage.AFTER_MEMBER_FINAL_WRITE,
                            0));
            Path stage = onlyStageWithPrefix(fixture.packet().root(), "member-");
            if (!retainSeparateStage) Files.delete(stage);
            Files.write(fixture.prompt(), fixture.promptBytes());
            byte[] targetBefore = Files.readAllBytes(fixture.prompt());
            var targetMtime = Files.getLastModifiedTime(fixture.prompt());
            byte[] stageBefore = retainSeparateStage ? Files.readAllBytes(stage) : null;
            var stageMtime = retainSeparateStage ? Files.getLastModifiedTime(stage) : null;

            QualificationPacketPublisher.PublicationException failure = assertThrows(
                    QualificationPacketPublisher.PublicationException.class,
                    () -> publish(fixture));

            assertEquals(
                    QualificationPacketPublisher.FailureReason.UNSAFE_RECOVERY,
                    failure.reason(),
                    Boolean.toString(retainSeparateStage));
            assertArrayEquals(targetBefore, Files.readAllBytes(fixture.prompt()));
            assertEquals(targetMtime, Files.getLastModifiedTime(fixture.prompt()));
            assertFalse(Files.exists(fixture.model()));
            assertFalse(Files.exists(commitPath(fixture)));
            if (retainSeparateStage) {
                assertArrayEquals(stageBefore, Files.readAllBytes(stage));
                assertEquals(stageMtime, Files.getLastModifiedTime(stage));
            }
        }
    }

    @Test
    void canonicalIntentWithoutItsPromotionLinkCannotAuthorizeRecovery() throws Exception {
        Fixture fixture = fixture("unowned-intent");
        assertThrows(
                IOException.class,
                () -> publishWithFault(
                        fixture,
                        QualificationPacketPublisher.FaultStage.AFTER_TARGET_CREATE,
                        -2));
        Path intentStage = onlyStageWithPrefix(fixture.packet().root(), "intent-");
        Files.delete(intentStage);
        Path intent = onlyEntry(fixture.packet().root(), "intents");
        byte[] intentBefore = Files.readAllBytes(intent);
        var intentMtime = Files.getLastModifiedTime(intent);

        QualificationPacketPublisher.PublicationException failure = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> publish(fixture));

        assertEquals(
                QualificationPacketPublisher.FailureReason.UNSAFE_RECOVERY,
                failure.reason());
        assertArrayEquals(intentBefore, Files.readAllBytes(intent));
        assertEquals(intentMtime, Files.getLastModifiedTime(intent));
        assertFalse(Files.exists(fixture.prompt()));
        assertFalse(Files.exists(fixture.model()));
        assertFalse(Files.exists(commitPath(fixture)));
    }

    @Test
    void malformedAndMisnamedCommitInventoryBlocksReadersAndLaterWriters() throws Exception {
        for (String name : List.of("junk.json", "b".repeat(64) + ".json")) {
            Fixture fixture = fixture("commit-inventory-" + name.length());
            QualificationPacketPublisher.CommittedSet published = publish(fixture);
            Path commits = fixture.packet().root()
                    .resolve(QualificationPacketPublisher.CONTROL_DIRECTORY)
                    .resolve("commits");
            Files.copy(published.commitPath(), commits.resolve(name));

            QualificationPacketPublisher.PublicationException failure = assertThrows(
                    QualificationPacketPublisher.PublicationException.class,
                    () -> QualificationPacketPublisher.requireSettledControlState(
                            fixture.packet().root()));

            assertEquals(QualificationPacketPublisher.FailureReason.INVALID_COMMIT,
                    failure.reason());
        }
    }

    @Test
    void scorecardPublicationCreatesOnlyItsCanonicalParent() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.createHardStopCampaign(
                temp.resolve("scorecard-parent"), catalog,
                catalog.admission().scenarios().getFirst());
        Path target = packet.root().resolve("scorecards/qwen14-standard-admission.json");
        assertFalse(Files.exists(target.getParent()));
        endCampaign(packet);

        QualificationPacketPublisher.withLease(packet.root(), lease -> {
            lease.publish(
                    QualificationPacketPublisher.PublicationKind.SCORECARD,
                    List.of(new QualificationPacketPublisher.Member(
                            target, "{}\n".getBytes(StandardCharsets.UTF_8))));
            return null;
        });

        assertEquals("{}\n", Files.readString(target));
        QualificationPacketPublisher.requireCommitted(
                packet.root(),
                QualificationPacketPublisher.PublicationKind.SCORECARD,
                List.of(target));
        QualificationEvidenceValidator.Validation validation =
                QualificationEvidenceValidator.validate(
                        packet.root(), QualificationTestPacket.protocol6Catalog());
        assertTrue(validation.findings().stream().anyMatch(
                finding -> finding.contains("scorecard")), validation.findings()::toString);
    }

    @Test
    void scoreValidationCanRecomputeOnlyItsOwnInterruptedTransaction() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.createHardStopCampaign(
                temp.resolve("scorecard-recovery"), catalog,
                catalog.admission().scenarios().getFirst());
        Path target = packet.root().resolve("scorecards/qwen14-standard-admission.json");
        byte[] bytes = "{}\n".getBytes(StandardCharsets.UTF_8);
        endCampaign(packet);
        assertThrows(IOException.class, () -> QualificationPacketPublisher.withLease(
                packet.root(),
                Duration.ofSeconds(5),
                (stage, ordinal) -> {
                    if (stage == QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PARTIAL_WRITE
                            && ordinal == 0) {
                        throw new IOException("injected score publication fault");
                    }
                },
                lease -> lease.publish(
                        QualificationPacketPublisher.PublicationKind.SCORECARD,
                        List.of(new QualificationPacketPublisher.Member(target, bytes)))));
        String transactionId = QualificationPacketPublisher.transactionIdFor(
                packet.root(),
                QualificationPacketPublisher.PublicationKind.SCORECARD,
                List.of(target));

        QualificationEvidenceValidator.Validation ordinary =
                QualificationEvidenceValidator.validate(packet.root(), catalog);
        QualificationEvidenceValidator.Validation recovery =
                QualificationEvidenceValidator.validateForTransactionRecovery(
                        packet.root(), catalog, transactionId);

        assertTrue(ordinary.findings().stream().anyMatch(
                finding -> finding.contains("publication control state is invalid")));
        assertFalse(recovery.findings().stream().anyMatch(
                finding -> finding.contains("publication control state is invalid")),
                recovery.findings()::toString);
        QualificationPacketPublisher.withLease(packet.root(), lease -> {
            lease.publish(
                    QualificationPacketPublisher.PublicationKind.SCORECARD,
                    List.of(new QualificationPacketPublisher.Member(target, bytes)));
            return null;
        });
        QualificationPacketPublisher.requireSettledControlState(packet.root());
    }

    @Test
    void historicalPacketWithoutControlStateIsByteAndMtimeImmutableOnRejectedUse()
            throws Exception {
        for (boolean bootstrap : List.of(false, true)) {
            QualificationTestPacket packet = QualificationTestPacket.create(
                    temp.resolve("historical-no-control-" + bootstrap),
                    QualificationTestPacket.protocol6Catalog());
            deleteTree(packet.root().resolve(QualificationPacketPublisher.CONTROL_DIRECTORY));
            java.util.Map<String, String> before = treeState(packet.root());

            assertThrows(IOException.class, () -> {
                if (bootstrap) {
                    QualificationPacketPublisher.withBootstrapLease(packet.root(), lease -> null);
                } else {
                    QualificationPacketPublisher.withLease(packet.root(), lease -> null);
                }
            });

            assertEquals(before, treeState(packet.root()));
            assertFalse(Files.exists(packet.root()
                    .resolve(QualificationPacketPublisher.CONTROL_DIRECTORY)));
        }
    }

    @Test
    void twoChildJvmsPublishingExactBytesConvergeOnOneImmutableSet() throws Exception {
        Fixture fixture = fixture("two-process-exact");
        Path controls = temp.resolve("two-process-exact-controls");
        Path release = controls.resolve("release");
        Process first = startProcess(
                "publish", fixture, controls.resolve("first.ready"), release, "same", 10_000);
        Process second = startProcess(
                "publish", fixture, controls.resolve("second.ready"), release, "same", 10_000);
        awaitFile(controls.resolve("first.ready"));
        awaitFile(controls.resolve("second.ready"));
        Files.writeString(release, "go\n", StandardCharsets.UTF_8);

        ProcessResult firstResult = await(first);
        ProcessResult secondResult = await(second);

        assertEquals(0, firstResult.exit(), firstResult.output());
        assertEquals(0, secondResult.exit(), secondResult.output());
        read(fixture);
        assertEquals("prompt-same\n", Files.readString(fixture.prompt()));
        assertEquals("model-same\n", Files.readString(fixture.model()));
    }

    @Test
    void twoChildJvmsPublishingDifferentBytesProduceOneCompleteWinner() throws Exception {
        Fixture fixture = fixture("two-process-conflict");
        Path controls = temp.resolve("two-process-conflict-controls");
        Path release = controls.resolve("release");
        Process first = startProcess(
                "publish", fixture, controls.resolve("first.ready"), release, "left", 10_000);
        Process second = startProcess(
                "publish", fixture, controls.resolve("second.ready"), release, "right", 10_000);
        awaitFile(controls.resolve("first.ready"));
        awaitFile(controls.resolve("second.ready"));
        Files.writeString(release, "go\n", StandardCharsets.UTF_8);

        ProcessResult firstResult = await(first);
        ProcessResult secondResult = await(second);

        assertEquals(List.of(0, 4), java.util.stream.Stream.of(
                        firstResult.exit(), secondResult.exit()).sorted().toList());
        String prompt = Files.readString(fixture.prompt());
        String model = Files.readString(fixture.model());
        assertTrue(prompt.equals("prompt-left\n") || prompt.equals("prompt-right\n"));
        assertEquals(prompt.replace("prompt-", "model-"), model);
        QualificationPacketPublisher.requireCommitted(
                fixture.packet().root(),
                QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                List.of(fixture.prompt(), fixture.model()));
    }

    @Test
    void childJvmDeathAfterMemberPromotionRecoversWithoutDeletingThePromotedMember()
            throws Exception {
        Fixture fixture = fixture("process-crash-recovery");
        String value = QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PROMOTION.name();
        Path controls = temp.resolve("process-crash-controls");
        Path release = controls.resolve("release");
        Process child = startProcess(
                "crash", fixture, controls.resolve("ready"), release, value, 10_000);
        awaitFile(controls.resolve("ready"));
        Files.writeString(release, "go\n", StandardCharsets.UTF_8);
        ProcessResult crashed = await(child);
        assertEquals(73, crashed.exit(), crashed.output());
        assertFalse(Files.exists(commitPath(fixture)));

        byte[] prompt = ("prompt-" + value + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] model = ("model-" + value + "\n").getBytes(StandardCharsets.UTF_8);
        QualificationPacketPublisher.withLease(fixture.packet().root(), lease -> {
            lease.publish(
                    QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                    List.of(
                            new QualificationPacketPublisher.Member(fixture.prompt(), prompt),
                            new QualificationPacketPublisher.Member(fixture.model(), model)));
            return null;
        });

        assertArrayEquals(prompt, Files.readAllBytes(fixture.prompt()));
        assertArrayEquals(model, Files.readAllBytes(fixture.model()));
        assertNoOpenIntent(fixture.packet().root());
    }

    @Test
    void heldChildJvmLeaseProducesABoundedTypedTimeout() throws Exception {
        Fixture fixture = fixture("process-lock-timeout");
        Path controls = temp.resolve("process-lock-controls");
        Path holderRelease = controls.resolve("holder.release");
        Process holder = startProcess(
                "hold", fixture, controls.resolve("holder.ready"), holderRelease,
                "unused", 10_000);
        awaitFile(controls.resolve("holder.ready"));
        Path contenderRelease = controls.resolve("contender.release");
        Files.writeString(contenderRelease, "go\n", StandardCharsets.UTF_8);
        Process contender = startProcess(
                "publish", fixture, controls.resolve("contender.ready"), contenderRelease,
                "blocked", 250);
        awaitFile(controls.resolve("contender.ready"));
        ProcessResult blocked = await(contender);
        assertEquals(5, blocked.exit(), blocked.output());
        assertTrue(blocked.output().contains("LOCK_TIMEOUT"), blocked.output());

        Files.writeString(holderRelease, "release\n", StandardCharsets.UTF_8);
        ProcessResult held = await(holder);
        assertEquals(0, held.exit(), held.output());
        assertFalse(Files.exists(fixture.prompt()));
        assertFalse(Files.exists(fixture.model()));
    }

    @Test
    void validatorWaitsForTheSameCrossProcessPacketLeaseBeforeReading()
            throws Exception {
        Fixture fixture = fixture("validator-process-serialization");
        Path controls = temp.resolve("validator-process-controls");
        Path holderRelease = controls.resolve("holder.release");
        Process holder = startProcess(
                "hold", fixture, controls.resolve("holder.ready"), holderRelease,
                "unused", 10_000);
        awaitFile(controls.resolve("holder.ready"));
        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<QualificationEvidenceValidator.Validation> validation =
                    executor.submit(() -> QualificationEvidenceValidator.validate(
                            fixture.packet().root(),
                            QualificationTestPacket.protocol6Catalog()));

            assertThrows(
                    java.util.concurrent.TimeoutException.class,
                    () -> validation.get(300, java.util.concurrent.TimeUnit.MILLISECONDS));
            Files.writeString(holderRelease, "release\n", StandardCharsets.UTF_8);
            ProcessResult held = await(holder);
            assertEquals(0, held.exit(), held.output());

            QualificationEvidenceValidator.Validation completed = validation.get(
                    20, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(completed.findings().stream().anyMatch(finding ->
                    finding.contains("publication lease is invalid")
                            && finding.contains("LOCK_TIMEOUT")),
                    completed.findings()::toString);
        } finally {
            if (holder.isAlive()) {
                Files.writeString(holderRelease, "release\n", StandardCharsets.UTF_8);
                holder.destroyForcibly();
                holder.waitFor();
            }
            executor.shutdownNow();
        }
    }

    private Fixture fixture(String name) throws Exception {
        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve(name), QualificationTestPacket.protocol6Catalog());
        packet.removePublicationForTest(
                QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION,
                List.of(
                        packet.runRoot().resolve(QualificationCausalFinalizer.OUTPUT_FILE),
                        packet.runRoot().resolve(QualificationRunFinalizer.CANARY_FILE),
                        packet.runRoot().resolve(QualificationRunFinalizer.RESULT_FILE)));
        packet.removePublicationForTest(
                QualificationPacketPublisher.PublicationKind.MECHANICAL_REQUIREMENTS,
                List.of(packet.runRoot().resolve(
                        QualificationRequirementFinalizer.OUTPUT_FILE)));
        packet.removePublicationForTest(
                QualificationPacketPublisher.PublicationKind.APPROVAL_EVIDENCE,
                List.of(packet.runRoot().resolve(
                        QualificationApprovalFinalizer.OUTPUT_FILE)));
        Path prompt = packet.runRoot().resolve("prompt-debug.txt");
        Path model = packet.runRoot().resolve("llama.log");
        packet.removePublicationForTest(
                QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                List.of(prompt, model));
        return new Fixture(
                packet,
                prompt,
                model,
                "provider request\n".getBytes(StandardCharsets.UTF_8),
                "model log\n".getBytes(StandardCharsets.UTF_8));
    }

    private static QualificationPacketPublisher.CommittedSet publish(Fixture fixture)
            throws IOException {
        return QualificationPacketPublisher.withLease(
                fixture.packet().root(),
                lease -> lease.publish(
                        QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                        members(fixture)));
    }

    private static QualificationPacketPublisher.CommittedSet publishWithFault(
            Fixture fixture,
            QualificationPacketPublisher.FaultStage stage,
            int ordinal) throws IOException {
        return QualificationPacketPublisher.withLease(
                fixture.packet().root(),
                Duration.ofSeconds(5),
                (actual, member) -> {
                    if (actual == stage && member == ordinal) {
                        throw new IOException("injected publication fault");
                    }
                },
                lease -> lease.publish(
                        QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                        members(fixture)));
    }

    private static QualificationPacketPublisher.CommittedSet read(Fixture fixture)
            throws IOException {
        return QualificationPacketPublisher.requireCommitted(
                fixture.packet().root(),
                QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                List.of(fixture.prompt(), fixture.model()));
    }

    private static Path commitPath(Fixture fixture) throws IOException {
        return QualificationPacketPublisher.commitPathForTest(
                fixture.packet().root(),
                QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                List.of(fixture.prompt(), fixture.model()));
    }

    private static List<QualificationPacketPublisher.Member> members(Fixture fixture) {
        return List.of(
                new QualificationPacketPublisher.Member(
                        fixture.prompt(), fixture.promptBytes()),
                new QualificationPacketPublisher.Member(
                        fixture.model(), fixture.modelBytes()));
    }

    private static Path retainOnlyIntentStageAfterCommittedInterruption(Fixture fixture)
            throws IOException {
        QualificationPacketPublisher.PublicationException interrupted = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> publishWithFault(
                        fixture,
                        QualificationPacketPublisher.FaultStage.BEFORE_CLEANUP,
                        -1));
        assertEquals(QualificationPacketPublisher.CommitState.COMMITTED,
                interrupted.commitState());
        Path intentStage = onlyStageWithPrefix(fixture.packet().root(), "intent-");
        Path stages = fixture.packet().root()
                .resolve(QualificationPacketPublisher.CONTROL_DIRECTORY)
                .resolve("stages");
        try (var entries = Files.list(stages)) {
            for (Path stage : entries.toList()) {
                if (!stage.equals(intentStage)) Files.delete(stage);
            }
        }
        Files.delete(onlyEntry(fixture.packet().root(), "intents"));
        return intentStage;
    }

    private static void assertNoOpenIntent(Path packetRoot) throws IOException {
        Path intents = packetRoot.resolve(QualificationPacketPublisher.CONTROL_DIRECTORY)
                .resolve("intents");
        try (var entries = Files.list(intents)) {
            assertTrue(entries.findAny().isEmpty());
        }
    }

    private static void endCampaign(QualificationTestPacket packet) throws IOException {
        QualificationCampaignCompletion.complete(
                packet.root(),
                QualificationProtocol.BankKind.ADMISSION,
                QualificationTestPacket.protocol6Catalog());
    }

    private static Path onlyStageWithPrefix(Path packetRoot, String prefix) throws IOException {
        Path stages = packetRoot.resolve(QualificationPacketPublisher.CONTROL_DIRECTORY)
                .resolve("stages");
        try (var entries = Files.list(stages)) {
            return entries.filter(path -> path.getFileName().toString().startsWith(prefix))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private static Path onlyEntry(Path packetRoot, String directory) throws IOException {
        Path root = packetRoot.resolve(QualificationPacketPublisher.CONTROL_DIRECTORY)
                .resolve(directory);
        try (var entries = Files.list(root)) {
            return entries.findFirst().orElseThrow();
        }
    }

    private static java.util.Map<String, String> treeState(Path root) throws IOException {
        java.util.Map<String, String> state = new java.util.TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                String type = Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        ? "directory" : "file";
                String content = type.equals("file")
                        ? QualificationScenarioBank.sha256(Files.readAllBytes(path)) : "";
                state.put(relative, type + ":" + Files.getLastModifiedTime(path).toMillis()
                        + ":" + content);
            }
        }
        return java.util.Map.copyOf(state);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static Process startProcess(
            String mode,
            Fixture fixture,
            Path ready,
            Path release,
            String value,
            long timeoutMillis) throws IOException {
        Files.createDirectories(ready.getParent());
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        return new ProcessBuilder(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                QualificationPacketPublicationProcessMain.class.getName(),
                mode,
                fixture.packet().root().toString(),
                fixture.packet().runRoot().toString(),
                ready.toString(),
                release.toString(),
                value,
                Long.toString(timeoutMillis))
                .redirectErrorStream(true)
                .start();
    }

    private static void awaitFile(Path path) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!Files.exists(path)) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("child process did not become ready");
            }
            Thread.sleep(10L);
        }
    }

    private static ProcessResult await(Process process) throws Exception {
        boolean exited = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            throw new IllegalStateException("child process did not exit");
        }
        return new ProcessResult(
                process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private record ProcessResult(int exit, String output) {}

    private record Fixture(
            QualificationTestPacket packet,
            Path prompt,
            Path model,
            byte[] promptBytes,
            byte[] modelBytes) {
        Fixture {
            promptBytes = promptBytes.clone();
            modelBytes = modelBytes.clone();
        }

        @Override
        public byte[] promptBytes() {
            return promptBytes.clone();
        }

        @Override
        public byte[] modelBytes() {
            return modelBytes.clone();
        }
    }
}
