package dev.talos.qualification;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationPtyCapturePublicationTest {
    @TempDir
    Path temp;

    @Test
    void protocolV1ThroughV5PtyCaptureRefusesBeforeChangingPacketState() throws Exception {
        for (int protocol = 1; protocol <= 5; protocol++) {
            QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(protocol);
            QualificationTestPacket packet = QualificationTestPacket.create(
                    temp.resolve("legacy-" + protocol), catalog);
            Map<String, FileState> before = treeState(packet.root());

            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> QualificationPtyCaptureMain.main(new String[]{
                            packet.root().toString(),
                            catalog.admission().scenarios().getFirst().id(),
                            "1"
                    }));

            assertTrue(failure.getMessage().contains("publication writes exactly protocol v6"),
                    failure::getMessage);
            assertEquals(before, treeState(packet.root()), "protocol=" + protocol);
        }
    }

    @Test
    void undeclaredPtyRepetitionRefusesBeforeChangingPacketState() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp.resolve("v6"), catalog);
        Map<String, FileState> before = treeState(packet.root());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> QualificationPtyCaptureMain.main(new String[]{
                        packet.root().toString(),
                        catalog.admission().scenarios().getFirst().id(),
                        "4"
                }));

        assertTrue(failure.getMessage().contains("repetition must identify one trial"),
                failure::getMessage);
        assertEquals(before, treeState(packet.root()));
    }

    @Test
    void textualCampaignProtocolRefusesBeforeStartingChildrenOrPublishingTranscript()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet =
                QualificationTestPacket.create(temp.resolve("campaign-type"), catalog);
        removeExistingTranscript(packet);
        Path campaignPath = packet.root().resolve("campaign.json");
        ObjectNode campaign = (ObjectNode) QualificationJson.readTree(campaignPath);
        campaign.put("protocolVersion", "6");
        QualificationJson.write(campaignPath, campaign);

        assertPrelaunchRejection(packet, catalog, "protocolVersion");
    }

    @Test
    void fractionalCampaignProtocolRefusesBeforeStartingChildrenOrPublishingTranscript()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet =
                QualificationTestPacket.create(temp.resolve("campaign-fraction"), catalog);
        removeExistingTranscript(packet);
        Path campaignPath = packet.root().resolve("campaign.json");
        ObjectNode campaign = (ObjectNode) QualificationJson.readTree(campaignPath);
        campaign.put("protocolVersion", 6.9d);
        QualificationJson.write(campaignPath, campaign);

        assertPrelaunchRejection(packet, catalog, "protocolVersion");
    }

    @Test
    void scalarCalibrationLaunchArgumentsRefuseBeforeStartingChildrenOrPublishingTranscript()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet =
                QualificationTestPacket.create(temp.resolve("calibration-type"), catalog);
        removeExistingTranscript(packet);
        Path calibrationPath = packet.root().resolve(
                "candidates/qwen14/calibration-rules.json");
        ObjectNode calibration = (ObjectNode) QualificationJson.readTree(calibrationPath);
        calibration.put("launchArguments", "--port 8080");
        QualificationJson.write(calibrationPath, calibration);

        assertPrelaunchRejection(packet, catalog, "launchArguments");
    }

    @Test
    void numericCalibrationLaunchArgumentElementRefusesBeforeStartingChildren()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet =
                QualificationTestPacket.create(temp.resolve("calibration-element-type"), catalog);
        removeExistingTranscript(packet);
        Path calibrationPath = packet.root().resolve(
                "candidates/qwen14/calibration-rules.json");
        ObjectNode calibration = (ObjectNode) QualificationJson.readTree(calibrationPath);
        com.fasterxml.jackson.databind.node.ArrayNode launchArguments =
                (com.fasterxml.jackson.databind.node.ArrayNode)
                        calibration.path("launchArguments");
        assertTrue(!launchArguments.isEmpty(), "fixture launchArguments must not be empty");
        launchArguments.set(0, new com.fasterxml.jackson.databind.node.IntNode(8080));
        QualificationJson.write(calibrationPath, calibration);

        assertPrelaunchRejection(packet, catalog, "launchArguments");
    }

    @Test
    void numericScenarioPromptRefusesBeforeStartingChildrenOrPublishingTranscript()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet =
                QualificationTestPacket.create(temp.resolve("scenario-type"), catalog);
        removeExistingTranscript(packet);
        Path scenarioPath = packet.runRoot().resolve("scenario.json");
        ObjectNode scenario = (ObjectNode) QualificationJson.readTree(scenarioPath);
        scenario.put("prompt", 7);
        QualificationJson.write(scenarioPath, scenario);

        assertPrelaunchRejection(packet, catalog, "prompt");
    }

    @Test
    void numericScenarioApprovalBehaviorRefusesBeforeStartingChildren()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet =
                QualificationTestPacket.create(temp.resolve("scenario-approval-type"), catalog);
        removeExistingTranscript(packet);
        Path scenarioPath = packet.runRoot().resolve("scenario.json");
        ObjectNode scenario = (ObjectNode) QualificationJson.readTree(scenarioPath);
        scenario.put("approvalBehavior", 1);
        QualificationJson.write(scenarioPath, scenario);

        assertPrelaunchRejection(packet, catalog, "approvalBehavior");
    }

    @Test
    void scenarioContentMismatchRefusesBeforeStartingChildrenOrPublishingTranscript()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet =
                QualificationTestPacket.create(temp.resolve("scenario-identity"), catalog);
        removeExistingTranscript(packet);
        Path scenarioPath = packet.runRoot().resolve("scenario.json");
        ObjectNode scenario = (ObjectNode) QualificationJson.readTree(scenarioPath);
        scenario.put("prompt", scenario.get("prompt").textValue() + " altered");
        QualificationJson.write(scenarioPath, scenario);

        assertPrelaunchRejection(packet, catalog, "frozen qualification catalog");
    }

    private static void removeExistingTranscript(QualificationTestPacket packet)
            throws Exception {
        Files.delete(packet.runRoot().resolve("transcript.raw.json"));
    }

    private static void assertPrelaunchRejection(
            QualificationTestPacket packet,
            QualificationScenarioBank.Catalog catalog,
            String expectedMessage
    ) throws Exception {
        Map<String, FileState> before = treeState(packet.root());
        Exception failure = assertThrows(
                Exception.class,
                () -> QualificationPtyCaptureMain.main(new String[]{
                        packet.root().toString(),
                        catalog.admission().scenarios().getFirst().id(),
                        "1"
                }));

        assertTrue(messageChain(failure).contains(expectedMessage), failure::toString);
        assertEquals(before, treeState(packet.root()));
        assertTrue(Files.notExists(packet.runRoot().resolve("transcript.raw.json")));
    }

    private static String messageChain(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
        }
        return messages.toString();
    }

    private static Map<String, FileState> treeState(Path root) throws Exception {
        Map<String, FileState> state = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                var attributes = Files.readAttributes(
                        path,
                        java.nio.file.attribute.BasicFileAttributes.class,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS);
                String kind = attributes.isRegularFile()
                        ? "FILE"
                        : attributes.isDirectory()
                        ? "DIRECTORY"
                        : attributes.isSymbolicLink()
                        ? "SYMLINK"
                        : "OTHER";
                byte[] bytes = attributes.isRegularFile() ? Files.readAllBytes(path) : null;
                state.put(
                        root.relativize(path).toString().replace('\\', '/'),
                        new FileState(
                                kind,
                                attributes.size(),
                                attributes.lastModifiedTime(),
                                bytes == null ? "" : QualificationScenarioBank.sha256(bytes)));
            }
        }
        return Map.copyOf(state);
    }

    private record FileState(
            String kind,
            long size,
            java.nio.file.attribute.FileTime modified,
            String sha256) {}
}
