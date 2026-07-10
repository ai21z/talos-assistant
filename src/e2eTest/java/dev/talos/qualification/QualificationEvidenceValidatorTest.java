package dev.talos.qualification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationEvidenceValidatorTest {

    @TempDir
    Path temp;

    @Test
    void completeMinimalPacketValidates() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertTrue(result.valid(), () -> String.join("\n", result.findings()));
        assertEqualsOne(result.runs().size());
    }

    @Test
    void missingTraceFailsClosed() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Files.delete(packet.runRoot().resolve("trace.json"));

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("trace.json")),
                () -> String.join("\n", result.findings()));
    }

    @Test
    void missingApprovalEvidenceFailsClosed() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Files.delete(packet.runRoot().resolve("approvals.json"));

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("approvals.json")),
                () -> String.join("\n", result.findings()));
    }

    @Test
    void calibrationChangeAfterFreezeFailsClosed() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Path guide = packet.root().resolve("candidates/qwen14/calibration-guide.md");
        Files.writeString(guide, "\nchanged after freeze\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("calibrationGuideSha256")),
                () -> String.join("\n", result.findings()));
    }

    @Test
    void unknownCalibrationRuleIsRejected() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Path rules = packet.root().resolve("candidates/qwen14/calibration-rules.json");
        String content = Files.readString(rules, StandardCharsets.UTF_8)
                .replaceFirst("\\{", "{\n  \"unexpected\" : true,");
        Files.writeString(rules, content, StandardCharsets.UTF_8);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("unexpected")),
                () -> String.join("\n", result.findings()));
    }

    @Test
    void traversalIdentityFailsClosed() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        String campaign = Files.readString(packet.root().resolve("campaign.json"), StandardCharsets.UTF_8)
                .replace("\"candidateAlias\" : \"qwen14\"", "\"candidateAlias\" : \"../escape\"");
        Files.writeString(packet.root().resolve("campaign.json"), campaign, StandardCharsets.UTF_8);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("candidateAlias")),
                () -> String.join("\n", result.findings()));
    }

    @Test
    void unknownJsonFieldIsRejected() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Path campaignPath = packet.root().resolve("campaign.json");
        String campaign = Files.readString(campaignPath, StandardCharsets.UTF_8)
                .replaceFirst("\\{", "{\n  \"unexpected\" : true,");
        Files.writeString(campaignPath, campaign, StandardCharsets.UTF_8);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("unexpected")),
                () -> String.join("\n", result.findings()));
    }

    @Test
    void missingRequiredCampaignFieldIsRejected() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Path campaignPath = packet.root().resolve("campaign.json");
        String campaign = Files.readString(campaignPath, StandardCharsets.UTF_8)
                .replaceFirst("(?m)^  \"campaignId\" : \"[^\"]+\",\\R", "");
        Files.writeString(campaignPath, campaign, StandardCharsets.UTF_8);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("campaignId")),
                () -> String.join("\n", result.findings()));
    }

    @Test
    void redirectedProcessEvidenceCannotMasqueradeAsTruePty() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Path request = packet.runRoot().resolve("request.json");
        String content = Files.readString(request, StandardCharsets.UTF_8)
                .replace("\"transport\" : \"TRUE_PTY\"", "\"transport\" : \"REDIRECTED_PROCESS\"");
        Files.writeString(request, content, StandardCharsets.UTF_8);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("TRUE_PTY")),
                () -> String.join("\n", result.findings()));
    }

    @Test
    void finalBytesCannotDriftFromCapturedAfterBytes() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Files.writeString(packet.runRoot().resolve("final-files/README.md"),
                "tampered after capture\n", StandardCharsets.UTF_8);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("after-files and final-files")),
                () -> String.join("\n", result.findings()));
    }

    @Test
    void failedCanaryCannotBeScored() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        QualificationJson.write(packet.runRoot().resolve("canary.json"), java.util.Map.of("passed", false));

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("canary")),
                () -> String.join("\n", result.findings()));
    }

    private static void assertEqualsOne(int actual) {
        assertTrue(actual == 1, "expected one run but was " + actual);
    }
}
