package dev.talos.qualification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationCampaignContinuationMainTest {
    @TempDir
    Path temp;

    @Test
    void duplicateProtocolSixThenFiveCannotDowngradeIntoLegacyContinuation() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog, scenario);
        Path campaignPath = packet.root().resolve("campaign.json");
        String campaign = Files.readString(campaignPath, StandardCharsets.UTF_8);
        String protocolSix = "\"protocolVersion\" : 6";
        assertTrue(campaign.contains(protocolSix));

        // This is the exact {6,5} downgrade shape that permissive JSON readers
        // collapse to one value. The Java authority must reject both keys.
        Files.writeString(
                campaignPath,
                campaign.replace(
                        protocolSix,
                        protocolSix + ",\n  \"protocolVersion\" : 5"),
                StandardCharsets.UTF_8);

        Invocation invocation = invoke(packet.root(), scenario.id(), 1);

        assertEquals(1, invocation.exit());
        assertTrue(invocation.err().toLowerCase(Locale.ROOT).contains("duplicate"),
                invocation.err());
        assertFalse(invocation.out().contains("CONTINUE"), invocation.out());
    }

    @Test
    void legacyCampaignIsReadableButContinuationFailsClosed() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog, scenario);
        Path campaignPath = packet.root().resolve("campaign.json");
        String campaign = Files.readString(campaignPath, StandardCharsets.UTF_8);
        Files.writeString(
                campaignPath,
                campaign.replace("\"protocolVersion\" : 6", "\"protocolVersion\" : 5"),
                StandardCharsets.UTF_8);

        Invocation invocation = invoke(packet.root(), scenario.id(), 1);

        assertEquals(1, invocation.exit());
        assertTrue(invocation.err().contains("read-only or unsupported"), invocation.err());
        assertFalse(invocation.out().contains("CONTINUE"), invocation.out());
    }

    private static Invocation invoke(Path root, String scenarioId, int repetition) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = QualificationCampaignContinuationMain.run(
                new String[]{root.toString(), scenarioId, Integer.toString(repetition)},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Invocation(
                exit,
                out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private record Invocation(int exit, String out, String err) {}
}
