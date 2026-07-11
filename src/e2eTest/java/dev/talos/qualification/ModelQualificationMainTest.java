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
    void validateSelectsTheHistoricalV3CatalogWithoutApplyingV4ScalarRules() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(3);
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);

        Capture capture = run("validate", "--root", packet.root().toString());

        assertEquals(0, capture.exit(), capture.err());
        assertTrue(capture.out().contains("Validated runs: 1"));
        assertEquals("ANSWER_EQUALS", catalog.scenario("F24-weak-hardware-no-tool-short-answer")
                .atomicRequirements().getFirst().type());
    }

    @Test
    void validateRejectsAnUnknownCampaignProtocolBeforeUsingLatestResources() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Path campaign = packet.root().resolve("campaign.json");
        String changed = Files.readString(campaign, StandardCharsets.UTF_8)
                .replace("\"protocolVersion\" : 4", "\"protocolVersion\" : 99");
        Files.writeString(campaign, changed, StandardCharsets.UTF_8);

        Capture capture = run("validate", "--root", packet.root().toString());

        assertEquals(2, capture.exit());
        assertTrue(capture.err().contains("Unsupported qualification protocol version: 99"), capture.err());
    }

    @Test
    void requirementsCommandWritesCanonicalProtocolV4ScalarResult() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(4);
        QualificationTestPacket packet = QualificationTestPacket.create(temp.resolve("v4"), catalog);
        Files.writeString(packet.runRoot().resolve("answer.txt"), "4.\n", StandardCharsets.UTF_8);

        Capture capture = run(
                "requirements",
                "--root", packet.root().toString(),
                "--scenario", catalog.admission().scenarios().getFirst().id(),
                "--run", packet.runRoot().toString());

        assertEquals(0, capture.exit(), capture.err());
        Path output = packet.runRoot().resolve("mechanical-requirements.json");
        assertTrue(Files.isRegularFile(output));
        var json = QualificationJson.readTree(output);
        assertEquals("talos.modelQualification.mechanicalRequirements",
                json.path("schemaName").asText());
        assertEquals(4, json.path("protocolVersion").asInt());
        assertEquals(catalog.sha256(), json.path("scenarioBankSha256").asText());
        assertEquals("ANSWER_SCALAR_EQUALS",
                json.path("requirements").get(0).path("type").asText());
        assertTrue(json.path("requirements").get(0).path("passed").asBoolean());
    }

    @Test
    void requirementsCommandPreservesHistoricalV3LiteralEquality() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(3);
        QualificationTestPacket packet = QualificationTestPacket.create(temp.resolve("v3"), catalog);
        Files.writeString(packet.runRoot().resolve("answer.txt"), "4.\n", StandardCharsets.UTF_8);

        Capture capture = run(
                "requirements",
                "--root", packet.root().toString(),
                "--scenario", catalog.admission().scenarios().getFirst().id(),
                "--run", packet.runRoot().toString());

        assertEquals(0, capture.exit(), capture.err());
        var json = QualificationJson.readTree(
                packet.runRoot().resolve("mechanical-requirements.json"));
        assertEquals(3, json.path("protocolVersion").asInt());
        assertEquals("ANSWER_EQUALS", json.path("requirements").get(0).path("type").asText());
        assertTrue(!json.path("requirements").get(0).path("passed").asBoolean());
    }

    @Test
    void requirementsCommandRefusesRunOutsidePacket() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp.resolve("packet"), catalog);
        Path outside = temp.resolve("outside-run");
        Files.createDirectories(outside);

        Capture capture = run(
                "requirements",
                "--root", packet.root().toString(),
                "--scenario", catalog.admission().scenarios().getFirst().id(),
                "--run", outside.toString());

        assertTrue(capture.exit() != 0);
        assertTrue(capture.err().contains("packet runs"), capture.err());
        assertTrue(!Files.exists(outside.resolve("mechanical-requirements.json")));
    }

    @Test
    void requirementsCommandRefusesToOverwriteMechanicalResult() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp.resolve("immutable"), catalog);
        String[] args = {
                "requirements",
                "--root", packet.root().toString(),
                "--scenario", catalog.admission().scenarios().getFirst().id(),
                "--run", packet.runRoot().toString()
        };

        Capture first = run(args);
        byte[] before = Files.readAllBytes(
                packet.runRoot().resolve("mechanical-requirements.json"));
        Capture second = run(args);

        assertEquals(0, first.exit(), first.err());
        assertTrue(second.exit() != 0);
        assertTrue(second.err().contains("refusing to overwrite"), second.err());
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(
                packet.runRoot().resolve("mechanical-requirements.json"))));
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
