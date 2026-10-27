package dev.talos.qualification;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationPacketPublisherProcessTest {
    private static final Duration CHILD_READY_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CHILD_EXIT_TIMEOUT = Duration.ofSeconds(45);
    private static final long PUBLICATION_TIMEOUT_MILLIS = 15_000L;

    @TempDir
    Path temp;

    @Test
    void abruptChildDeathAcrossEveryDurabilityPhaseRecoversInANewJvm()
            throws Exception {
        List<CrashCase> cases = List.of(
                crash("intent-stage-create",
                        QualificationPacketPublisher.FaultStage.AFTER_METADATA_STAGE_CREATE,
                        -2),
                crash("intent-partial-write",
                        QualificationPacketPublisher.FaultStage.AFTER_METADATA_PARTIAL_WRITE,
                        -2),
                crash("intent-final-write",
                        QualificationPacketPublisher.FaultStage.AFTER_METADATA_FINAL_WRITE,
                        -2),
                crash("intent-flush",
                        QualificationPacketPublisher.FaultStage.AFTER_INTENT_FLUSH,
                        -2),
                crash("intent-promotion",
                        QualificationPacketPublisher.FaultStage.AFTER_TARGET_CREATE,
                        -2),
                crash("member-zero-partial",
                        QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PARTIAL_WRITE,
                        0),
                crash("member-zero-promotion",
                        QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PROMOTION,
                        0),
                crash("member-one-partial",
                        QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PARTIAL_WRITE,
                        1),
                crash("member-one-promotion",
                        QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PROMOTION,
                        1),
                crash("commit-stage-create",
                        QualificationPacketPublisher.FaultStage.AFTER_METADATA_STAGE_CREATE,
                        -1),
                crash("commit-partial-write",
                        QualificationPacketPublisher.FaultStage.AFTER_METADATA_PARTIAL_WRITE,
                        -1),
                crash("commit-final-write",
                        QualificationPacketPublisher.FaultStage.AFTER_METADATA_FINAL_WRITE,
                        -1),
                crash("commit-stage-flush",
                        QualificationPacketPublisher.FaultStage.AFTER_COMMIT_STAGE_FLUSH,
                        -1),
                crash("before-commit-promotion",
                        QualificationPacketPublisher.FaultStage.BEFORE_COMMIT_PROMOTION,
                        -1),
                crash("commit-target-created",
                        QualificationPacketPublisher.FaultStage.AFTER_TARGET_CREATE,
                        -1),
                crash("commit-promoted",
                        QualificationPacketPublisher.FaultStage.AFTER_COMMIT_PROMOTION,
                        -1),
                crash("commit-verified",
                        QualificationPacketPublisher.FaultStage.AFTER_COMMIT_VERIFICATION,
                        -1),
                crash("before-cleanup",
                        QualificationPacketPublisher.FaultStage.BEFORE_CLEANUP,
                        -1),
                crash("member-zero-stage-cleaned",
                        QualificationPacketPublisher.FaultStage.AFTER_MEMBER_STAGE_CLEANUP,
                        0),
                crash("member-one-stage-cleaned",
                        QualificationPacketPublisher.FaultStage.AFTER_MEMBER_STAGE_CLEANUP,
                        1),
                crash("intent-stage-cleaned",
                        QualificationPacketPublisher.FaultStage.AFTER_INTENT_STAGE_CLEANUP,
                        -1),
                crash("commit-stage-cleaned",
                        QualificationPacketPublisher.FaultStage.AFTER_COMMIT_STAGE_CLEANUP,
                        -1),
                crash("before-intent-cleanup",
                        QualificationPacketPublisher.FaultStage.BEFORE_INTENT_CLEANUP,
                        -1),
                settledCrash("after-intent-cleanup",
                        QualificationPacketPublisher.FaultStage.AFTER_INTENT_CLEANUP,
                        -1),
                settledCrash("after-cleanup",
                        QualificationPacketPublisher.FaultStage.AFTER_CLEANUP,
                        -1));

        List<Executable> executions = new ArrayList<>();
        for (CrashCase testCase : cases) {
            executions.add(() -> exerciseCrashBoundary(testCase));
        }
        assertAll("real-process publication crash recovery", executions);
    }

    @Test
    void readerCannotAcceptCompleteMembersWhileWriterIsPausedBeforeCommit()
            throws Exception {
        ShapeFixture fixture = fixture(
                "reader-during-precommit",
                QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE);
        String value = "reader-precommit";
        Child child = start(
                "matrix-pause",
                fixture,
                temp.resolve("reader-precommit-controls"),
                value,
                QualificationPacketPublisher.FaultStage.BEFORE_COMMIT_PROMOTION,
                -1);
        awaitFile(child.ready(), child.process());
        Files.writeString(child.release(), "go\n", StandardCharsets.UTF_8);
        awaitFile(child.stageReady(), child.process());

        try {
            assertLiveMembersWholeOrAbsent(fixture, value);
            assertFalse(Files.exists(commitPath(fixture)));
            assertReaderIncomplete(fixture);
        } finally {
            Files.writeString(child.stageRelease(), "continue\n", StandardCharsets.UTF_8);
            ProcessResult result = await(child.process());
            assertEquals(0, result.exit(), result.output());
        }

        assertCommittedBytes(fixture, value);
        assertNoRecoveryState(fixture.packet().root());
    }

    @Test
    void concurrentExactChildPublishersConvergeWithoutRewritingTheWinner()
            throws Exception {
        ShapeFixture fixture = fixture(
                "concurrent-exact",
                QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE);
        Child first = start(
                "matrix-publish",
                fixture,
                temp.resolve("concurrent-exact-first"),
                "same",
                null,
                -9);
        Child second = start(
                "matrix-publish",
                fixture,
                temp.resolve("concurrent-exact-second"),
                "same",
                null,
                -9);
        awaitFile(first.ready(), first.process());
        awaitFile(second.ready(), second.process());
        Files.writeString(first.release(), "go\n", StandardCharsets.UTF_8);
        Files.writeString(second.release(), "go\n", StandardCharsets.UTF_8);

        ProcessResult firstResult = await(first.process());
        ProcessResult secondResult = await(second.process());

        assertEquals(0, firstResult.exit(), firstResult.output());
        assertEquals(0, secondResult.exit(), secondResult.output());
        assertEquals(1, java.util.stream.Stream.of(firstResult, secondResult)
                .filter(result -> result.output().contains("CREATED=true"))
                .count());
        assertEquals(1, java.util.stream.Stream.of(firstResult, secondResult)
                .filter(result -> result.output().contains("CREATED=false"))
                .count());
        assertCommittedBytes(fixture, "same");
        assertNoRecoveryState(fixture.packet().root());
    }

    @Test
    void concurrentConflictingChildPublishersExposeOneCompleteWinnerOnly()
            throws Exception {
        ShapeFixture fixture = fixture(
                "concurrent-conflict",
                QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE);
        Child left = start(
                "matrix-publish",
                fixture,
                temp.resolve("concurrent-conflict-left"),
                "left",
                null,
                -9);
        Child right = start(
                "matrix-publish",
                fixture,
                temp.resolve("concurrent-conflict-right"),
                "right",
                null,
                -9);
        awaitFile(left.ready(), left.process());
        awaitFile(right.ready(), right.process());
        Files.writeString(left.release(), "go\n", StandardCharsets.UTF_8);
        Files.writeString(right.release(), "go\n", StandardCharsets.UTF_8);

        ProcessResult leftResult = await(left.process());
        ProcessResult rightResult = await(right.process());
        List<Integer> exits = java.util.stream.Stream.of(
                        leftResult.exit(), rightResult.exit())
                .sorted()
                .toList();

        assertEquals(List.of(0, 4), exits);
        ProcessResult rejected = leftResult.exit() == 0 ? rightResult : leftResult;
        String winner = leftResult.exit() == 0 ? "left" : "right";
        assertTrue(rejected.output().contains("CONFLICT"), rejected.output());
        assertCommittedBytes(fixture, winner);
        assertNoMixedBytes(fixture, "left", "right");
        assertNoRecoveryState(fixture.packet().root());
    }

    @Test
    void nonIdentityChildCannotWinTheBootstrapRaceBeforeIdentityCommits()
            throws Exception {
        ShapeFixture identity = uninitializedIdentityFixture("bootstrap-identity-race");
        Child bootstrap = start(
                "matrix-bootstrap-pause",
                identity,
                temp.resolve("bootstrap-identity-controls"),
                "identity",
                QualificationPacketPublisher.FaultStage.AFTER_LOCK_FILE_FLUSH,
                -1);
        awaitFile(bootstrap.ready(), bootstrap.process());
        Files.writeString(bootstrap.release(), "go\n", StandardCharsets.UTF_8);
        awaitFile(bootstrap.stageReady(), bootstrap.process());

        ShapeFixture provider = new ShapeFixture(
                identity.packet(),
                QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                providerPaths(identity.packet().runRoot()));
        ProcessResult early;
        ProcessResult initialized;
        try {
            early = invoke(
                    "matrix-publish",
                    provider,
                    temp.resolve("bootstrap-early-provider-controls"),
                    "early",
                    null,
                    -9);
        } finally {
            Files.writeString(
                    bootstrap.stageRelease(), "continue\n", StandardCharsets.UTF_8);
            initialized = await(bootstrap.process());
        }

        assertEquals(4, early.exit(), early.output());
        assertTrue(early.output().contains("INCOMPLETE"), early.output());
        assertEquals(0, initialized.exit(), initialized.output());
        assertCommittedBytes(identity, "identity");
        assertFalse(Files.exists(provider.paths().get(0)));
        assertFalse(Files.exists(provider.paths().get(1)));
        assertFalse(Files.exists(commitPath(provider)));

        ProcessResult afterIdentity = invoke(
                "matrix-publish",
                provider,
                temp.resolve("bootstrap-post-identity-provider-controls"),
                "after",
                null,
                -9);
        assertEquals(0, afterIdentity.exit(), afterIdentity.output());
        assertCommittedBytes(provider, "after");
        assertNoRecoveryState(provider.packet().root());
    }

    @Test
    void crashedIdentityBootstrapCannotAuthorizeEvidenceAndRecoversInANewJvm()
            throws Exception {
        ShapeFixture identity = uninitializedIdentityFixture("bootstrap-identity-crash");
        ProcessResult crashed = invoke(
                "matrix-bootstrap-crash",
                identity,
                temp.resolve("bootstrap-identity-crash-controls"),
                "identity",
                QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PROMOTION,
                1);
        assertEquals(73, crashed.exit(), crashed.output());

        ShapeFixture provider = new ShapeFixture(
                identity.packet(),
                QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                providerPaths(identity.packet().runRoot()));
        ProcessResult early = invoke(
                "matrix-publish",
                provider,
                temp.resolve("bootstrap-crash-early-provider-controls"),
                "early",
                null,
                -9);
        assertEquals(4, early.exit(), early.output());
        assertTrue(early.output().contains("INCOMPLETE"), early.output());

        ProcessResult recovered = invoke(
                "matrix-bootstrap-publish",
                identity,
                temp.resolve("bootstrap-identity-recovery-controls"),
                "identity",
                null,
                -9);
        assertEquals(0, recovered.exit(), recovered.output());
        assertCommittedBytes(identity, "identity");
        assertNoRecoveryState(identity.packet().root());
    }

    @Test
    void exactAndConflictingIdentityBootstrapsSerializeAcrossJvms()
            throws Exception {
        ShapeFixture exact = uninitializedIdentityFixture("bootstrap-identity-exact");
        assertConcurrentOutcome(exact, "matrix-bootstrap-publish", "same", "same", true);

        ShapeFixture conflicting = uninitializedIdentityFixture("bootstrap-identity-conflict");
        assertConcurrentOutcome(
                conflicting, "matrix-bootstrap-publish", "left", "right", false);
    }

    @Test
    void everyPostIdentityPublicationKindSerializesExactAndConflictingChildJvms()
            throws Exception {
        List<QualificationPacketPublisher.PublicationKind> kinds = List.of(
                QualificationPacketPublisher.PublicationKind.PTY_CAPTURE,
                QualificationPacketPublisher.PublicationKind.PTY_FAILURE,
                QualificationPacketPublisher.PublicationKind.APPROVAL_EVIDENCE,
                QualificationPacketPublisher.PublicationKind.MECHANICAL_REQUIREMENTS,
                QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION,
                QualificationPacketPublisher.PublicationKind.SCORECARD);
        List<Executable> executions = new ArrayList<>();
        for (QualificationPacketPublisher.PublicationKind kind : kinds) {
            executions.add(() -> assertConcurrentOutcome(
                    fixture("all-kinds-exact-" + kind, kind),
                    "matrix-publish",
                    "same",
                    "same",
                    true));
            executions.add(() -> assertConcurrentOutcome(
                    fixture("all-kinds-conflict-" + kind, kind),
                    "matrix-publish",
                    "left",
                    "right",
                    false));
        }
        executions.add(() -> assertConcurrentOutcome(
                fixture(
                        "all-kinds-exact-CAMPAIGN_COMPLETION",
                        QualificationPacketPublisher.PublicationKind.CAMPAIGN_COMPLETION),
                "matrix-publish",
                "same",
                "same",
                true));
        assertAll("all protocol-v6 publication kinds", executions);
    }

    @Test
    void everyPostIdentityPublicationKindRejectsPrecommitReaders()
            throws Exception {
        List<QualificationPacketPublisher.PublicationKind> kinds = List.of(
                QualificationPacketPublisher.PublicationKind.PTY_CAPTURE,
                QualificationPacketPublisher.PublicationKind.PTY_FAILURE,
                QualificationPacketPublisher.PublicationKind.APPROVAL_EVIDENCE,
                QualificationPacketPublisher.PublicationKind.MECHANICAL_REQUIREMENTS,
                QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION,
                QualificationPacketPublisher.PublicationKind.CAMPAIGN_COMPLETION,
                QualificationPacketPublisher.PublicationKind.SCORECARD);
        List<Executable> executions = new ArrayList<>();
        for (QualificationPacketPublisher.PublicationKind kind : kinds) {
            executions.add(() -> exercisePrecommitReader(kind));
        }
        assertAll("all protocol-v6 precommit readers", executions);
    }

    @Test
    void campaignCompletionExpectationIsIndependentOfLiveTargetCorruption()
            throws Exception {
        ShapeFixture fixture = fixture(
                "completion-independent-expectation",
                QualificationPacketPublisher.PublicationKind.CAMPAIGN_COMPLETION);
        byte[] expected = expectedBytes(fixture, "unused").getFirst();
        Path target = fixture.paths().getFirst();

        Files.writeString(target, "corrupt live completion\n", StandardCharsets.UTF_8);

        assertArrayEquals(expected, expectedBytes(fixture, "unused").getFirst());
        assertFalse(Arrays.equals(expected, Files.readAllBytes(target)));
    }

    @Test
    void oneTwoAndThreeMemberTransactionsRecoverAndRetryReadOnlyAcrossJvms()
            throws Exception {
        List<ShapeCrash> cases = List.of(
                new ShapeCrash(
                        "campaign-completion-intent",
                        QualificationPacketPublisher.PublicationKind.CAMPAIGN_COMPLETION,
                        QualificationPacketPublisher.FaultStage.AFTER_METADATA_PARTIAL_WRITE,
                        -2),
                new ShapeCrash(
                        "campaign-completion-member",
                        QualificationPacketPublisher.PublicationKind.CAMPAIGN_COMPLETION,
                        QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PROMOTION,
                        0),
                new ShapeCrash(
                        "scorecard-intent",
                        QualificationPacketPublisher.PublicationKind.SCORECARD,
                        QualificationPacketPublisher.FaultStage.AFTER_METADATA_PARTIAL_WRITE,
                        -2),
                new ShapeCrash(
                        "scorecard-member",
                        QualificationPacketPublisher.PublicationKind.SCORECARD,
                        QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PROMOTION,
                        0),
                new ShapeCrash(
                        "pty-capture-member",
                        QualificationPacketPublisher.PublicationKind.PTY_CAPTURE,
                        QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PROMOTION,
                        0),
                new ShapeCrash(
                        "pty-failure-member",
                        QualificationPacketPublisher.PublicationKind.PTY_FAILURE,
                        QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PROMOTION,
                        0),
                new ShapeCrash(
                        "approval-member",
                        QualificationPacketPublisher.PublicationKind.APPROVAL_EVIDENCE,
                        QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PROMOTION,
                        0),
                new ShapeCrash(
                        "requirements-member",
                        QualificationPacketPublisher.PublicationKind.MECHANICAL_REQUIREMENTS,
                        QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PROMOTION,
                        0),
                new ShapeCrash(
                        "provider-second-member",
                        QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                        QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PROMOTION,
                        1),
                new ShapeCrash(
                        "run-finalization-commit",
                        QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION,
                        QualificationPacketPublisher.FaultStage.BEFORE_COMMIT_PROMOTION,
                        -1),
                new ShapeCrash(
                        "run-finalization-member-zero",
                        QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION,
                        QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PROMOTION,
                        0),
                new ShapeCrash(
                        "run-finalization-member-one",
                        QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION,
                        QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PROMOTION,
                        1),
                new ShapeCrash(
                        "run-finalization-member-two",
                        QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION,
                        QualificationPacketPublisher.FaultStage.AFTER_MEMBER_PROMOTION,
                        2));

        List<Executable> executions = new ArrayList<>();
        for (ShapeCrash testCase : cases) {
            executions.add(() -> exerciseShapeRestartAndReadOnlyRetry(testCase));
        }
        assertAll("canonical transaction shapes", executions);
    }

    private void exerciseCrashBoundary(CrashCase testCase) throws Exception {
        ShapeFixture fixture = fixture(
                "crash-" + testCase.id(),
                QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE);
        String value = testCase.id();
        ProcessResult crashed = invoke(
                "matrix-crash",
                fixture,
                temp.resolve("crash-controls-" + testCase.id()),
                value,
                testCase.stage(),
                testCase.ordinal());

        assertEquals(73, crashed.exit(), testCase + ": " + crashed.output());
        assertLiveMembersWholeOrAbsent(fixture, value);
        if (testCase.settledAfterCrash()) {
            assertCommittedBytes(fixture, value);
            assertNoRecoveryState(fixture.packet().root());
        } else {
            assertReaderIncomplete(fixture);
        }

        ProcessResult recovered = invoke(
                "matrix-publish",
                fixture,
                temp.resolve("recovery-controls-" + testCase.id()),
                value,
                null,
                -9);

        assertEquals(0, recovered.exit(), testCase + ": " + recovered.output());
        assertCommittedBytes(fixture, value);
        assertNoRecoveryState(fixture.packet().root());
    }

    private void exerciseShapeRestartAndReadOnlyRetry(ShapeCrash testCase)
            throws Exception {
        ShapeFixture fixture = fixture("shape-" + testCase.id(), testCase.kind());
        String value = testCase.id();
        ProcessResult crashed = invoke(
                "matrix-crash",
                fixture,
                temp.resolve("shape-crash-controls-" + testCase.id()),
                value,
                testCase.stage(),
                testCase.ordinal());
        assertEquals(73, crashed.exit(), testCase + ": " + crashed.output());
        assertLiveMembersWholeOrAbsent(fixture, value);

        ProcessResult recovered = invoke(
                "matrix-publish",
                fixture,
                temp.resolve("shape-recovery-controls-" + testCase.id()),
                value,
                null,
                -9);
        assertEquals(0, recovered.exit(), testCase + ": " + recovered.output());
        assertCommittedBytes(fixture, value);
        assertNoRecoveryState(fixture.packet().root());
        Map<Path, FileSnapshot> beforeRetry = authoritativeSnapshot(fixture);

        ProcessResult retry = invoke(
                "matrix-publish",
                fixture,
                temp.resolve("shape-retry-controls-" + testCase.id()),
                value,
                null,
                -9);

        assertEquals(0, retry.exit(), testCase + ": " + retry.output());
        assertTrue(retry.output().contains("CREATED=false"), retry.output());
        assertSnapshotUnchanged(beforeRetry);
        assertCommittedBytes(fixture, value);
        assertNoRecoveryState(fixture.packet().root());
    }

    private void exercisePrecommitReader(
            QualificationPacketPublisher.PublicationKind kind) throws Exception {
        ShapeFixture fixture = fixture("precommit-" + kind, kind);
        Child child = start(
                "matrix-pause",
                fixture,
                temp.resolve("precommit-controls-" + kind),
                "precommit",
                QualificationPacketPublisher.FaultStage.BEFORE_COMMIT_PROMOTION,
                -1);
        awaitFile(child.ready(), child.process());
        Files.writeString(child.release(), "go\n", StandardCharsets.UTF_8);
        awaitFile(child.stageReady(), child.process());
        try {
            assertLiveMembersWholeOrAbsent(fixture, "precommit");
            assertReaderIncomplete(fixture);
        } finally {
            Files.writeString(child.stageRelease(), "continue\n", StandardCharsets.UTF_8);
            ProcessResult result = await(child.process());
            assertEquals(0, result.exit(), result.output());
        }
        assertCommittedBytes(fixture, "precommit");
        assertNoRecoveryState(fixture.packet().root());
    }

    private void assertConcurrentOutcome(
            ShapeFixture fixture,
            String mode,
            String leftValue,
            String rightValue,
            boolean exact) throws Exception {
        Path controls = temp.resolve("race-" + fixture.kind() + "-"
                + fixture.packet().root().getFileName());
        Child left = start(
                mode,
                fixture,
                controls.resolve("left"),
                leftValue,
                null,
                -9);
        Child right = start(
                mode,
                fixture,
                controls.resolve("right"),
                rightValue,
                null,
                -9);
        awaitFile(left.ready(), left.process());
        awaitFile(right.ready(), right.process());
        Files.writeString(left.release(), "go\n", StandardCharsets.UTF_8);
        Files.writeString(right.release(), "go\n", StandardCharsets.UTF_8);
        ProcessResult leftResult = await(left.process());
        ProcessResult rightResult = await(right.process());
        if (exact) {
            assertEquals(0, leftResult.exit(), leftResult.output());
            assertEquals(0, rightResult.exit(), rightResult.output());
            assertEquals(1, java.util.stream.Stream.of(leftResult, rightResult)
                    .filter(result -> result.output().contains("CREATED=true"))
                    .count());
            assertEquals(1, java.util.stream.Stream.of(leftResult, rightResult)
                    .filter(result -> result.output().contains("CREATED=false"))
                    .count());
            assertCommittedBytes(fixture, leftValue);
        } else {
            assertEquals(List.of(0, 4), java.util.stream.Stream.of(
                            leftResult.exit(), rightResult.exit()).sorted().toList());
            ProcessResult rejected = leftResult.exit() == 0 ? rightResult : leftResult;
            String winner = leftResult.exit() == 0 ? leftValue : rightValue;
            assertTrue(rejected.output().contains("CONFLICT"), rejected.output());
            assertCommittedBytes(fixture, winner);
            assertNoMixedBytes(fixture, leftValue, rightValue);
            Map<Path, FileSnapshot> beforeRetry = authoritativeSnapshot(fixture);
            ProcessResult retry = invoke(
                    mode,
                    fixture,
                    controls.resolve("rejected-retry"),
                    "third",
                    null,
                    -9);
            assertEquals(4, retry.exit(), retry.output());
            assertTrue(retry.output().contains("CONFLICT"), retry.output());
            assertSnapshotUnchanged(beforeRetry);
        }
        assertNoRecoveryState(fixture.packet().root());
    }

    private ShapeFixture fixture(
            String name,
            QualificationPacketPublisher.PublicationKind kind) throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = kind == QualificationPacketPublisher.PublicationKind.CAMPAIGN_COMPLETION
                || kind == QualificationPacketPublisher.PublicationKind.SCORECARD
                ? QualificationTestPacket.createHardStopCampaign(
                        temp.resolve(name), catalog,
                        catalog.admission().scenarios().getFirst())
                : QualificationTestPacket.create(temp.resolve(name), catalog);
        Path runRoot = packet.runRoot();
        List<Path> paths;
        List<byte[]> capturedExpectedBytes = List.of();
        switch (kind) {
            case PTY_CAPTURE -> {
                removeRunFinalization(packet, runRoot);
                paths = List.of(runRoot.resolve("transcript.raw.json"));
                packet.removePublicationForTest(kind, paths);
            }
            case PTY_FAILURE -> {
                removeRunFinalization(packet, runRoot);
                packet.removePublicationForTest(
                        QualificationPacketPublisher.PublicationKind.PTY_CAPTURE,
                        List.of(runRoot.resolve("transcript.raw.json")));
                paths = List.of(runRoot.resolve("transcript.failed.txt"));
            }
            case APPROVAL_EVIDENCE -> {
                removeRunFinalization(packet, runRoot);
                packet.removePublicationForTest(
                        QualificationPacketPublisher.PublicationKind.MECHANICAL_REQUIREMENTS,
                        List.of(runRoot.resolve(
                                QualificationRequirementFinalizer.OUTPUT_FILE)));
                paths = List.of(runRoot.resolve(QualificationApprovalFinalizer.OUTPUT_FILE));
                packet.removePublicationForTest(kind, paths);
            }
            case MECHANICAL_REQUIREMENTS -> {
                removeRunFinalization(packet, runRoot);
                paths = List.of(runRoot.resolve(QualificationRequirementFinalizer.OUTPUT_FILE));
                packet.removePublicationForTest(kind, paths);
            }
            case PROVIDER_EVIDENCE -> {
                removeRunFinalization(packet, runRoot);
                packet.removePublicationForTest(
                        QualificationPacketPublisher.PublicationKind.MECHANICAL_REQUIREMENTS,
                        List.of(runRoot.resolve(
                                QualificationRequirementFinalizer.OUTPUT_FILE)));
                packet.removePublicationForTest(
                        QualificationPacketPublisher.PublicationKind.APPROVAL_EVIDENCE,
                        List.of(runRoot.resolve(
                                QualificationApprovalFinalizer.OUTPUT_FILE)));
                paths = providerPaths(runRoot);
                packet.removePublicationForTest(kind, paths);
            }
            case RUN_FINALIZATION -> {
                paths = runFinalizationPaths(runRoot);
                packet.removePublicationForTest(kind, paths);
            }
            case CAMPAIGN_COMPLETION -> {
                QualificationCampaignCompletion.Publication completion =
                        QualificationCampaignCompletion.complete(
                                packet.root(),
                                QualificationProtocol.BankKind.ADMISSION,
                                catalog);
                paths = List.of(completion.path());
                capturedExpectedBytes = List.of(
                        QualificationJson.bytes(completion.completion()));
                packet.removePublicationForTest(kind, paths);
            }
            case SCORECARD -> {
                endCampaign(packet);
                QualificationRecords.CampaignManifest campaign = QualificationJson.read(
                        packet.root().resolve("campaign.json"),
                        QualificationRecords.CampaignManifest.class);
                paths = List.of(packet.root().resolve("scorecards").resolve(
                        campaign.candidateAlias() + "-" + campaign.configId()
                                + "-admission.json"));
            }
            default -> throw new IllegalArgumentException(
                    "unsupported process-test publication kind " + kind);
        }
        return new ShapeFixture(packet, kind, paths, capturedExpectedBytes);
    }

    private static void removeRunFinalization(
            QualificationTestPacket packet,
            Path runRoot) throws IOException {
        packet.removePublicationForTest(
                QualificationPacketPublisher.PublicationKind.RUN_FINALIZATION,
                runFinalizationPaths(runRoot));
    }

    private ShapeFixture uninitializedIdentityFixture(String name) throws Exception {
        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve(name), QualificationTestPacket.protocol6Catalog());
        List<Path> identityPaths = List.of(
                packet.root().resolve(QualificationExecutionIdentityWriter.LEDGER_FILE),
                packet.root().resolve(QualificationExecutionIdentity.HARNESS_FILE),
                packet.root().resolve(QualificationExecutionIdentity.RUNTIME_FILE));
        packet.removeRunEvidenceForTest();
        packet.removePublicationForTest(
                QualificationPacketPublisher.PublicationKind.EXECUTION_IDENTITY,
                identityPaths);
        packet.removeEmptyPublicationControlForTest();
        Files.createDirectories(packet.runRoot());
        return new ShapeFixture(
                packet,
                QualificationPacketPublisher.PublicationKind.EXECUTION_IDENTITY,
                identityPaths);
    }

    private ProcessResult invoke(
            String mode,
            ShapeFixture fixture,
            Path controls,
            String value,
            QualificationPacketPublisher.FaultStage stage,
            int ordinal) throws Exception {
        Child child = start(mode, fixture, controls, value, stage, ordinal);
        awaitFile(child.ready(), child.process());
        Files.writeString(child.release(), "go\n", StandardCharsets.UTF_8);
        return await(child.process());
    }

    private static Child start(
            String mode,
            ShapeFixture fixture,
            Path controls,
            String value,
            QualificationPacketPublisher.FaultStage stage,
            int ordinal) throws IOException {
        Files.createDirectories(controls);
        Path ready = controls.resolve("ready");
        Path release = controls.resolve("release");
        String executable = System.getProperty("os.name", "")
                .toLowerCase()
                .contains("win") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        Process process = new ProcessBuilder(
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
                Long.toString(PUBLICATION_TIMEOUT_MILLIS),
                fixture.kind().name(),
                stage == null ? "NONE" : stage.name(),
                Integer.toString(ordinal))
                .redirectErrorStream(true)
                .start();
        return new Child(
                process,
                ready,
                release,
                sibling(ready, ".stage"),
                sibling(release, ".stage"));
    }

    private static ProcessResult await(Process process) throws Exception {
        boolean exited = process.waitFor(CHILD_EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new IllegalStateException("qualification publication child did not exit");
        }
        return new ProcessResult(
                process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static void awaitFile(Path path, Process process) throws Exception {
        long deadline = System.nanoTime() + CHILD_READY_TIMEOUT.toNanos();
        while (!Files.exists(path)) {
            if (!process.isAlive()) {
                ProcessResult exited = await(process);
                throw new IllegalStateException(
                        "qualification publication child exited before " + path
                                + ": " + exited.output());
            }
            if (System.nanoTime() >= deadline) {
                process.destroyForcibly();
                throw new IllegalStateException(
                        "qualification publication child did not create " + path);
            }
            Thread.sleep(10L);
        }
    }

    private static void assertReaderIncomplete(ShapeFixture fixture) {
        QualificationPacketPublisher.PublicationException failure = assertThrows(
                QualificationPacketPublisher.PublicationException.class,
                () -> QualificationPacketPublisher.requireCommitted(
                        fixture.packet().root(), fixture.kind(), fixture.paths()));
        assertEquals(QualificationPacketPublisher.FailureReason.INCOMPLETE, failure.reason());
        assertEquals(
                QualificationPacketPublisher.CommitState.NOT_COMMITTED,
                failure.commitState());
    }

    private static void assertCommittedBytes(ShapeFixture fixture, String value)
            throws IOException {
        QualificationPacketPublisher.CommittedSet committed =
                QualificationPacketPublisher.requireCommitted(
                        fixture.packet().root(), fixture.kind(), fixture.paths());
        if (fixture.kind()
                == QualificationPacketPublisher.PublicationKind.CAMPAIGN_COMPLETION) {
            QualificationRecords.CampaignCompletion completion =
                    QualificationCampaignCompletion.requireCommitted(
                            fixture.packet().root(),
                            QualificationProtocol.BankKind.ADMISSION,
                            QualificationTestPacket.protocol6Catalog());
            assertArrayEquals(
                    expectedBytes(fixture, value).getFirst(),
                    QualificationJson.bytes(completion));
        }
        List<byte[]> expected = expectedBytes(fixture, value);
        assertEquals(fixture.paths().size(), expected.size());
        for (int index = 0; index < fixture.paths().size(); index++) {
            Path path = fixture.paths().get(index);
            assertArrayEquals(expected.get(index), committed.bytes(fixture.packet().root(), path),
                    path.toString());
            assertArrayEquals(expected.get(index), Files.readAllBytes(path), path.toString());
        }
    }

    private static void assertLiveMembersWholeOrAbsent(
            ShapeFixture fixture,
            String value) throws IOException {
        List<byte[]> expected = expectedBytes(fixture, value);
        for (int index = 0; index < fixture.paths().size(); index++) {
            Path path = fixture.paths().get(index);
            if (Files.exists(path)) {
                assertArrayEquals(expected.get(index), Files.readAllBytes(path), path.toString());
            }
        }
    }

    private static void assertNoMixedBytes(
            ShapeFixture fixture,
            String firstValue,
            String secondValue) throws IOException {
        List<byte[]> first = expectedBytes(fixture, firstValue);
        List<byte[]> second = expectedBytes(fixture, secondValue);
        boolean allFirst = true;
        boolean allSecond = true;
        for (int index = 0; index < fixture.paths().size(); index++) {
            byte[] actual = Files.readAllBytes(fixture.paths().get(index));
            allFirst &= Arrays.equals(actual, first.get(index));
            allSecond &= Arrays.equals(actual, second.get(index));
        }
        assertTrue(allFirst || allSecond, "publication contains mixed winner bytes");
    }

    private static void assertNoRecoveryState(Path packetRoot) throws IOException {
        QualificationPacketPublisher.requireSettledControlState(packetRoot);
        Path control = packetRoot.resolve(QualificationPacketPublisher.CONTROL_DIRECTORY);
        for (String directory : List.of("intents", "stages")) {
            try (var entries = Files.list(control.resolve(directory))) {
                assertTrue(entries.findAny().isEmpty(),
                        () -> "qualification recovery state remains in " + directory);
            }
        }
    }

    private static Map<Path, FileSnapshot> authoritativeSnapshot(ShapeFixture fixture)
            throws IOException {
        Map<Path, FileSnapshot> snapshots = new LinkedHashMap<>();
        for (Path member : fixture.paths()) snapshots.put(member, snapshot(member));
        Path commit = commitPath(fixture);
        snapshots.put(commit, snapshot(commit));
        return Map.copyOf(snapshots);
    }

    private static void assertSnapshotUnchanged(Map<Path, FileSnapshot> expected)
            throws IOException {
        for (Map.Entry<Path, FileSnapshot> entry : expected.entrySet()) {
            FileSnapshot actual = snapshot(entry.getKey());
            assertArrayEquals(entry.getValue().bytes(), actual.bytes(), entry.getKey().toString());
            assertEquals(entry.getValue().modified(), actual.modified(), entry.getKey().toString());
        }
    }

    private static FileSnapshot snapshot(Path path) throws IOException {
        return new FileSnapshot(
                Files.readAllBytes(path),
                Files.getLastModifiedTime(path));
    }

    private static Path commitPath(ShapeFixture fixture) throws IOException {
        return QualificationPacketPublisher.commitPathForTest(
                fixture.packet().root(), fixture.kind(), fixture.paths());
    }

    private static List<Path> providerPaths(Path runRoot) {
        return List.of(
                runRoot.resolve("prompt-debug.txt"),
                runRoot.resolve("llama.log"));
    }

    private static List<Path> runFinalizationPaths(Path runRoot) {
        return List.of(
                runRoot.resolve(QualificationCausalFinalizer.OUTPUT_FILE),
                runRoot.resolve(QualificationRunFinalizer.CANARY_FILE),
                runRoot.resolve(QualificationRunFinalizer.RESULT_FILE));
    }

    private static List<byte[]> expectedBytes(
            ShapeFixture fixture,
            String value) throws IOException {
        if (!fixture.capturedExpectedBytes().isEmpty()) {
            return fixture.capturedExpectedBytes();
        }
        List<String> labels = switch (fixture.kind()) {
            case EXECUTION_IDENTITY ->
                    List.of("identity-ledger", "identity-harness", "identity-runtime");
            case PTY_CAPTURE -> List.of("pty");
            case PTY_FAILURE -> List.of("pty-failure");
            case APPROVAL_EVIDENCE -> List.of("approvals");
            case MECHANICAL_REQUIREMENTS -> List.of("requirements");
            case PROVIDER_EVIDENCE -> List.of("prompt", "model");
            case RUN_FINALIZATION -> List.of("causal", "canary", "result");
            case CAMPAIGN_COMPLETION -> throw new AssertionError("handled above");
            case SCORECARD -> List.of("scorecard");
            default -> throw new IllegalArgumentException(
                    "unsupported process-test kind " + fixture.kind());
        };
        return labels.stream()
                .map(label -> (label + "-" + value + "\n")
                        .getBytes(StandardCharsets.UTF_8))
                .toList();
    }

    private static void endCampaign(QualificationTestPacket packet) throws IOException {
        QualificationCampaignCompletion.complete(
                packet.root(),
                QualificationProtocol.BankKind.ADMISSION,
                QualificationTestPacket.protocol6Catalog());
    }

    private static CrashCase crash(
            String id,
            QualificationPacketPublisher.FaultStage stage,
            int ordinal) {
        return new CrashCase(id, stage, ordinal, false);
    }

    private static CrashCase settledCrash(
            String id,
            QualificationPacketPublisher.FaultStage stage,
            int ordinal) {
        return new CrashCase(id, stage, ordinal, true);
    }

    private static Path sibling(Path path, String suffix) {
        return path.resolveSibling(path.getFileName() + suffix);
    }

    private record CrashCase(
            String id,
            QualificationPacketPublisher.FaultStage stage,
            int ordinal,
            boolean settledAfterCrash) {}

    private record ShapeCrash(
            String id,
            QualificationPacketPublisher.PublicationKind kind,
            QualificationPacketPublisher.FaultStage stage,
            int ordinal) {}

    private record ShapeFixture(
            QualificationTestPacket packet,
            QualificationPacketPublisher.PublicationKind kind,
            List<Path> paths,
            List<byte[]> capturedExpectedBytes) {
        private ShapeFixture(
                QualificationTestPacket packet,
                QualificationPacketPublisher.PublicationKind kind,
                List<Path> paths) {
            this(packet, kind, paths, List.of());
        }

        private ShapeFixture {
            paths = List.copyOf(paths);
            capturedExpectedBytes = capturedExpectedBytes.stream()
                    .map(byte[]::clone)
                    .toList();
        }

        @Override
        public List<byte[]> capturedExpectedBytes() {
            return capturedExpectedBytes.stream()
                    .map(byte[]::clone)
                    .toList();
        }
    }

    private record Child(
            Process process,
            Path ready,
            Path release,
            Path stageReady,
            Path stageRelease) {}

    private record ProcessResult(int exit, String output) {}

    private record FileSnapshot(byte[] bytes, FileTime modified) {
        private FileSnapshot {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
