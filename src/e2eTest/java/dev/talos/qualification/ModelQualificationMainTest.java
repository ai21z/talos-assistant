package dev.talos.qualification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelQualificationMainTest {
    @TempDir
    Path temp;

    @Test
    void bankHashCommandPrintsOnlyFrozenHash() {
        Capture capture = run("bank-hash");

        assertEquals(0, capture.exit());
        assertTrue(capture.out().trim().matches("[0-9a-f]{64}"));
        assertTrue(capture.err().isBlank());
    }

    @Test
    void validateCommandFailsClosedForIncompletePacket() {
        Capture capture = run("validate", "--root", temp.toString());

        assertEquals(1, capture.exit());
        assertTrue(capture.err().contains("campaign.json"));
    }

    @Test
    void preflightAcceptsFrozenMetadataBeforeAnyRun() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        try (var paths = java.nio.file.Files.walk(packet.root().resolve("runs"))) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                java.nio.file.Files.delete(path);
            }
        }

        Capture capture = run("preflight", "--root", packet.root().toString());

        assertEquals(0, capture.exit(), capture.err());
        assertTrue(capture.out().contains(catalog.sha256()));
    }

    @Test
    void validateSelectsTheHistoricalV2CatalogFromCampaignMetadata() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(2);
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);

        Capture capture = run("validate", "--root", packet.root().toString());

        assertEquals(0, capture.exit(), capture.err());
        assertTrue(capture.out().contains("Validated runs: 1"));
    }

    @Test
    void validateRejectsAnUnknownCampaignProtocolBeforeUsingLatestResources() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Path campaign = packet.root().resolve("campaign.json");
        String changed = Files.readString(campaign, StandardCharsets.UTF_8)
                .replace("\"protocolVersion\" : 3", "\"protocolVersion\" : 99");
        Files.writeString(campaign, changed, StandardCharsets.UTF_8);

        Capture capture = run("validate", "--root", packet.root().toString());

        assertEquals(2, capture.exit());
        assertTrue(capture.err().contains("Unsupported qualification protocol version: 99"), capture.err());
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = ModelQualificationMain.run(args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(exit,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int exit, String out, String err) {}
}
