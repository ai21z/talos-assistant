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

    @Test
    void assertedCanaryPassCannotHideARawRuntimeArtifactLeak() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Files.writeString(packet.runRoot().resolve("transcript.txt"),
                "leaked TALOS_CANARY_MODEL_QUALIFICATION_PRIVATE\n", StandardCharsets.UTF_8);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("canary")),
                () -> String.join("\n", result.findings()));
    }

    @Test
    void arbitraryNonEmptyJsonCannotMasqueradeAsATalosTrace() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        QualificationJson.write(packet.runRoot().resolve("trace.json"), java.util.Map.of("notATrace", true));

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("trace")),
                () -> String.join("\n", result.findings()));
    }

    @Test
    void selfAssertedDecisionCorrectnessMustMatchTraceEvidence() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Path resultPath = packet.runRoot().resolve("result.json");
        String content = Files.readString(resultPath, StandardCharsets.UTF_8)
                .replace("\"correct\" : true", "\"correct\" : false");
        Files.writeString(resultPath, content, StandardCharsets.UTF_8);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("decision")),
                () -> String.join("\n", result.findings()));
    }

    @Test
    void changedFileOutsideScenarioTargetsFailsClosed() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Files.writeString(packet.runRoot().resolve("after-files/unapproved.txt"), "changed\n", StandardCharsets.UTF_8);
        Files.writeString(packet.runRoot().resolve("final-files/unapproved.txt"), "changed\n", StandardCharsets.UTF_8);
        QualificationFixtureMaterializer.Snapshot after =
                QualificationFixtureMaterializer.snapshot(packet.runRoot().resolve("after-files"));
        QualificationJson.write(packet.runRoot().resolve("after-hashes.json"), after.sha256());

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("mutation scope")),
                () -> String.join("\n", result.findings()));
    }

    @Test
    void ptyClaimRequiresStructuredRawPtyCapture() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Files.writeString(packet.runRoot().resolve("transcript.raw.json"), "{\"tty\":false}",
                StandardCharsets.UTF_8);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("PTY")),
                () -> String.join("\n", result.findings()));
    }

    @Test
    void ptyRawOutputMustEqualTheCapturedOutputEvents() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Path capture = packet.runRoot().resolve("transcript.raw.json");
        String content = Files.readString(capture, StandardCharsets.UTF_8)
                .replace("\"rawOutput\" : \"Outcome: READ_ONLY_ANSWERED\"",
                        "\"rawOutput\" : \"fabricated output\"");
        Files.writeString(capture, content, StandardCharsets.UTF_8);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("PTY")),
                () -> String.join("\n", result.findings()));
    }

    @Test
    void modelBytesCannotDriftAfterCalibrationPreflight() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Files.writeString(packet.root().resolve("artifacts/model.gguf"), "different model bytes\n",
                StandardCharsets.UTF_8);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("model artifact SHA-256")),
                () -> String.join("\n", result.findings()));
    }

    @Test
    void engineBytesCannotDriftAfterCalibrationPreflight() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Files.writeString(packet.root().resolve("artifacts/llama-server.exe"), "different engine bytes\n",
                StandardCharsets.UTF_8);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("engine executable SHA-256")),
                () -> String.join("\n", result.findings()));
    }

    @Test
    void timingMetricsCannotDisagreeWithTheScoredRun() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Path timings = packet.runRoot().resolve("timings.json");
        String content = Files.readString(timings, StandardCharsets.UTF_8)
                .replace("\"totalTurnWallTimeMillis\" : 1100", "\"totalTurnWallTimeMillis\" : 9999");
        Files.writeString(timings, content, StandardCharsets.UTF_8);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("timing evidence")),
                () -> String.join("\n", result.findings()));
    }

    @Test
    void resourceMetricsCannotDisagreeWithTheScoredRun() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Path resources = packet.runRoot().resolve("resources.json");
        String content = Files.readString(resources, StandardCharsets.UTF_8)
                .replace("\"processWorkingSetBytes\" : 8589934592", "\"processWorkingSetBytes\" : 1");
        Files.writeString(resources, content, StandardCharsets.UTF_8);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("resource evidence")),
                () -> String.join("\n", result.findings()));
    }

    private static void assertEqualsOne(int actual) {
        assertTrue(actual == 1, "expected one run but was " + actual);
    }
}
