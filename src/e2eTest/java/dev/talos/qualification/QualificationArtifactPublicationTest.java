package dev.talos.qualification;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationArtifactPublicationTest {
    @TempDir
    Path temp;

    @Test
    void onlyCurrentProtocolIsWritable() {
        for (int protocol = 1; protocol <= 5; protocol++) {
            int candidate = protocol;
            assertThrows(IllegalArgumentException.class,
                    () -> QualificationArtifactPublication.requireWritableProtocol(candidate));
        }
        assertThrows(IllegalArgumentException.class,
                () -> QualificationArtifactPublication.requireWritableProtocol(7));
        QualificationArtifactPublication.requireWritableProtocol(QualificationProtocol.VERSION);
    }

    @Test
    void exactExistingPublicationIsAByteAndMtimePreservingNoOp() throws Exception {
        Path target = temp.resolve("scorecard.json");
        byte[] expected = "canonical\n".getBytes(StandardCharsets.UTF_8);
        Files.write(target, expected);
        FileTime frozen = FileTime.fromMillis(1_700_000_000_000L);
        Files.setLastModifiedTime(target, frozen);

        boolean created = QualificationArtifactPublication.publishNewOrVerifyExact(
                target, expected, "scorecard");

        assertFalse(created);
        assertArrayEquals(expected, Files.readAllBytes(target));
        assertEquals(frozen, Files.getLastModifiedTime(target));
    }

    @Test
    void conflictingExistingPublicationFailsWithoutChangingBytesOrMtime() throws Exception {
        Path target = temp.resolve("scorecard.json");
        byte[] existing = "frozen\n".getBytes(StandardCharsets.UTF_8);
        Files.write(target, existing);
        FileTime frozen = FileTime.fromMillis(1_700_000_000_000L);
        Files.setLastModifiedTime(target, frozen);

        assertThrows(IllegalArgumentException.class,
                () -> QualificationArtifactPublication.publishNewOrVerifyExact(
                        target,
                        "different\n".getBytes(StandardCharsets.UTF_8),
                        "scorecard"));
        assertArrayEquals(existing, Files.readAllBytes(target));
        assertEquals(frozen, Files.getLastModifiedTime(target));
    }

    @Test
    void concurrentExactPublishersConvergeWithoutReplacement() throws Exception {
        Path target = temp.resolve("exact-race.json");
        byte[] expected = "same\n".getBytes(StandardCharsets.UTF_8);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return QualificationArtifactPublication.publishNewOrVerifyExact(
                            target, expected, "race artifact");
                }));
            }
            start.countDown();
            int creators = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) creators++;
            }
            assertEquals(1, creators);
        }
        assertArrayEquals(expected, Files.readAllBytes(target));
    }

    @Test
    void concurrentConflictingPublishersChooseOneImmutableWinner() throws Exception {
        Path target = temp.resolve("conflict-race.json");
        byte[] left = "left\n".getBytes(StandardCharsets.UTF_8);
        byte[] right = "right\n".getBytes(StandardCharsets.UTF_8);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> publishAfter(start, target, left));
            Future<Boolean> second = executor.submit(() -> publishAfter(start, target, right));
            start.countDown();
            int successes = 0;
            int conflicts = 0;
            for (Future<Boolean> future : List.of(first, second)) {
                try {
                    if (future.get()) successes++;
                } catch (java.util.concurrent.ExecutionException failure) {
                    assertTrue(failure.getCause() instanceof IllegalArgumentException,
                            String.valueOf(failure.getCause()));
                    conflicts++;
                }
            }
            assertEquals(1, successes);
            assertEquals(1, conflicts);
        }
        byte[] winner = Files.readAllBytes(target);
        assertTrue(java.util.Arrays.equals(winner, left)
                || java.util.Arrays.equals(winner, right));
    }

    @Test
    void declaredRunGuardAcceptsOnlyTheCampaignDerivedTrialPath() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp.resolve("packet"), catalog);
        String scenario = catalog.admission().scenarios().getFirst().id();

        QualificationArtifactPublication.DeclaredRun declared =
                QualificationArtifactPublication.requireWritableDeclaredRun(
                        packet.root(), packet.runRoot(), scenario, catalog);
        assertEquals(1, declared.trialNumber());
        assertEquals("r1", declared.repetitionId());

        Path undeclared = packet.root().resolve(
                "runs/qwen14/reference/standard/" + scenario + "/trial-99");
        Files.createDirectories(undeclared);
        assertThrows(IllegalArgumentException.class,
                () -> QualificationArtifactPublication.requireWritableDeclaredRun(
                        packet.root(), undeclared, scenario, catalog));
    }

    @Test
    void missingOrBankInconsistentCampaignCannotCreateAuthorityNamespace() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        Path missing = Files.createDirectories(temp.resolve("missing"));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationArtifactPublication.requireWritableCampaign(missing, catalog));

        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve("mismatch"), catalog);
        Path campaignPath = packet.root().resolve("campaign.json");
        ObjectNode campaign = (ObjectNode) QualificationJson.readTree(campaignPath);
        campaign.put("scenarioBankSha256", "0".repeat(64));
        QualificationJson.write(campaignPath, campaign);
        assertThrows(IllegalArgumentException.class,
                () -> QualificationArtifactPublication.requireWritableCampaign(
                        packet.root(), catalog));
    }

    @Test
    void campaignCoordinatesRejectDriveRelativeAndNonCanonicalSegments() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        List<String> invalid = List.of(
                "C:escape", "../escape", "name.", "AUX", " leading",
                "two words", "/root", "\\\\server", "a".repeat(129));
        for (int index = 0; index < invalid.size(); index++) {
            QualificationTestPacket packet = QualificationTestPacket.create(
                    temp.resolve("unsafe-" + index), catalog);
            Path campaignPath = packet.root().resolve("campaign.json");
            ObjectNode campaign = (ObjectNode) QualificationJson.readTree(campaignPath);
            campaign.put("candidateAlias", invalid.get(index));
            QualificationJson.write(campaignPath, campaign);

            assertThrows(IllegalArgumentException.class,
                    () -> QualificationArtifactPublication.requireWritableCampaign(
                            packet.root(), catalog),
                    "coordinate=" + invalid.get(index));
        }
    }

    private static boolean publishAfter(
            CountDownLatch start, Path target, byte[] bytes) throws Exception {
        start.await();
        return QualificationArtifactPublication.publishNewOrVerifyExact(
                target, bytes, "racing artifact");
    }
}
