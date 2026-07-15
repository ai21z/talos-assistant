package dev.talos.qualification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
    void canonicalMechanicalRequirementArtifactValidates() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        QualificationRequirementFinalizer.evaluateAndWrite(
                packet.root(), packet.runRoot(), scenario.id(), catalog);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertTrue(result.valid(), () -> String.join("\n", result.findings()));
    }

    @Test
    void tamperedMechanicalRequirementArtifactFailsClosed() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        Path output = QualificationRequirementFinalizer.evaluateAndWrite(
                packet.root(), packet.runRoot(), scenario.id(), catalog);
        String tampered = Files.readString(output, StandardCharsets.UTF_8)
                .replace("\"passed\" : true", "\"passed\" : false");
        Files.writeString(output, tampered, StandardCharsets.UTF_8);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding ->
                        finding.contains("Mechanical requirement artifact differs")),
                () -> String.join("\n", result.findings()));
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
    void calibrationPreflightAcceptsDistinctDirectPreflightLaunchArguments() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Path rulesPath = packet.root().resolve("candidates/qwen14/calibration-rules.json");
        ObjectNode rules = (ObjectNode) QualificationJson.readTree(rulesPath);
        rules.putArray("directPreflightLaunchArguments")
                .add("-m").add("model.gguf").add("--jinja").add("-lv").add("5");
        QualificationJson.write(rulesPath, rules);
        ObjectNode campaign = (ObjectNode) QualificationJson.readTree(packet.root().resolve("campaign.json"));
        campaign.put("calibrationRulesSha256",
                QualificationScenarioBank.sha256(Files.readAllBytes(rulesPath)));
        QualificationJson.write(packet.root().resolve("campaign.json"), campaign);
        removeRuns(packet);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validateCalibrationPreflight(packet.root(), catalog);

        assertTrue(result.valid(), () -> String.join("\n", result.findings()));
        assertTrue(result.calibrationRules().directPreflightLaunchArguments().contains("-lv"));
    }

    @Test
    void calibrationPreflightAcceptsEvidenceGatedRequestLayout() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Path rulesPath = packet.root().resolve("candidates/qwen14/calibration-rules.json");
        ObjectNode rules = (ObjectNode) QualificationJson.readTree(rulesPath);
        rules.put("requestLayout", "SINGLE_LEADING_SYSTEM");
        rules.put("directPreflightEvidenceManifestSha256", "d".repeat(64));
        QualificationJson.write(rulesPath, rules);
        ObjectNode campaign = (ObjectNode) QualificationJson.readTree(packet.root().resolve("campaign.json"));
        campaign.put("calibrationRulesSha256",
                QualificationScenarioBank.sha256(Files.readAllBytes(rulesPath)));
        QualificationJson.write(packet.root().resolve("campaign.json"), campaign);
        removeRuns(packet);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validateCalibrationPreflight(packet.root(), catalog);

        assertTrue(result.valid(), () -> String.join("\n", result.findings()));
        assertTrue("SINGLE_LEADING_SYSTEM".equals(result.calibrationRules().requestLayout()));
        assertTrue("d".repeat(64).equals(
                result.calibrationRules().directPreflightEvidenceManifestSha256()));
    }

    @Test
    void calibrationPreflightRejectsUnknownRequestLayout() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Path rulesPath = packet.root().resolve("candidates/qwen14/calibration-rules.json");
        ObjectNode rules = (ObjectNode) QualificationJson.readTree(rulesPath);
        rules.put("requestLayout", "UNVERIFIED_LAYOUT");
        rules.put("directPreflightEvidenceManifestSha256", "d".repeat(64));
        QualificationJson.write(rulesPath, rules);
        ObjectNode campaign = (ObjectNode) QualificationJson.readTree(packet.root().resolve("campaign.json"));
        campaign.put("calibrationRulesSha256",
                QualificationScenarioBank.sha256(Files.readAllBytes(rulesPath)));
        QualificationJson.write(packet.root().resolve("campaign.json"), campaign);
        removeRuns(packet);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validateCalibrationPreflight(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("request layout")),
                () -> String.join("\n", result.findings()));
    }

    @Test
    void calibrationPreflightRequiresDirectEvidenceForAdaptedLayout() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Path rulesPath = packet.root().resolve("candidates/qwen14/calibration-rules.json");
        ObjectNode rules = (ObjectNode) QualificationJson.readTree(rulesPath);
        rules.put("requestLayout", "SINGLE_LEADING_SYSTEM");
        rules.remove("directPreflightEvidenceManifestSha256");
        QualificationJson.write(rulesPath, rules);
        ObjectNode campaign = (ObjectNode) QualificationJson.readTree(packet.root().resolve("campaign.json"));
        campaign.put("calibrationRulesSha256",
                QualificationScenarioBank.sha256(Files.readAllBytes(rulesPath)));
        QualificationJson.write(packet.root().resolve("campaign.json"), campaign);
        removeRuns(packet);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validateCalibrationPreflight(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream()
                        .anyMatch(finding -> finding.contains("direct preflight evidence manifest SHA-256")),
                () -> String.join("\n", result.findings()));
    }

    @Test
    void calibrationPreflightKeepsHistoricalPacketsWithoutDirectLaunchReadable() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Path rulesPath = packet.root().resolve("candidates/qwen14/calibration-rules.json");
        ObjectNode rules = (ObjectNode) QualificationJson.readTree(rulesPath);
        rules.remove("directPreflightLaunchArguments");
        rules.remove("requestLayout");
        rules.remove("directPreflightEvidenceManifestSha256");
        QualificationJson.write(rulesPath, rules);
        ObjectNode campaign = (ObjectNode) QualificationJson.readTree(packet.root().resolve("campaign.json"));
        campaign.put("calibrationRulesSha256",
                QualificationScenarioBank.sha256(Files.readAllBytes(rulesPath)));
        QualificationJson.write(packet.root().resolve("campaign.json"), campaign);
        removeRuns(packet);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validateCalibrationPreflight(packet.root(), catalog);

        assertTrue(result.valid(), () -> String.join("\n", result.findings()));
        assertTrue(result.calibrationRules().directPreflightLaunchArguments().isEmpty());
        assertTrue("PRESERVE_AS_WRITTEN".equals(result.calibrationRules().requestLayout()));
        assertTrue(result.calibrationRules().directPreflightEvidenceManifestSha256().isEmpty());
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
    void frozenScenarioCanaryIsSourceEvidenceRatherThanARuntimeLeak() throws Exception {
        QualificationScenarioBank.Catalog catalog = catalogWithScenarioSourceCanary();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertTrue(result.valid(), () -> String.join("\n", result.findings()));
    }

    @Test
    void frozenScenarioCanaryCannotHideTheSameCanaryInARuntimeArtifact() throws Exception {
        QualificationScenarioBank.Catalog catalog = catalogWithScenarioSourceCanary();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Files.writeString(
                packet.runRoot().resolve("transcript.txt"),
                "leaked TALOS_CANARY_MODEL_QUALIFICATION_PRIVATE\n",
                StandardCharsets.UTF_8);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("canary")),
                () -> String.join("\n", result.findings()));
    }

    @Test
    void capturedScenarioMustMatchTheFrozenBankBeforeItCanBeAllowlisted() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        ObjectNode captured = (ObjectNode) QualificationJson.readTree(packet.runRoot().resolve("scenario.json"));
        captured.put("prompt", "tampered prompt");
        QualificationJson.write(packet.runRoot().resolve("scenario.json"), captured);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("scenario snapshot")),
                () -> String.join("\n", result.findings()));
    }

    @Test
    void capturedScenarioIsMandatoryQualificationEvidence() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Files.delete(packet.runRoot().resolve("scenario.json"));

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("scenario.json")),
                () -> String.join("\n", result.findings()));
    }

    @Test
    void malformedCapturedScenarioCannotGainASourceEvidenceExemption() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Files.writeString(packet.runRoot().resolve("scenario.json"), "{not-json\n", StandardCharsets.UTF_8);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("scenario.json")),
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
    void wrongFirstToolWithLaterSuccessfulReadRemainsGrounded() {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.load(4)
                .scenario("F20-context-compare-code-and-docs");
        QualificationTraceEvidence.Facts trace = new QualificationTraceEvidence.Facts(
                "trace-f20",
                "qwen3-8b",
                "READ_ONLY_QA",
                false,
                "NONE",
                "READ_ONLY_ANSWERED",
                "COMPLETE",
                "NOT_RUN",
                List.of(
                        new QualificationTraceEvidence.Call("talos.list_dir", ".", true, false),
                        new QualificationTraceEvidence.Call(
                                "talos.read_file", "docs/operations.md", true, true)),
                List.of(new QualificationRecords.DecisionResult("compare-read", false)),
                false,
                false);

        assertTrue(QualificationEvidenceValidator.readEvidenceBindingPasses(scenario, trace),
                "tool-choice accuracy must not replace same-turn read grounding");

        QualificationTraceEvidence.Facts ungrounded = new QualificationTraceEvidence.Facts(
                trace.traceId(), trace.modelId(), trace.taskType(), trace.mutationAllowed(),
                trace.actionObligation(), trace.terminalState(), trace.outcomeStatus(),
                trace.verificationStatus(),
                List.of(new QualificationTraceEvidence.Call("talos.list_dir", ".", true, false)),
                trace.decisions(), false, false);
        assertFalse(QualificationEvidenceValidator.readEvidenceBindingPasses(scenario, ungrounded),
                "an unrelated discovery call alone is not read grounding");
    }

    @Test
    void directoryListingScenarioAcceptsMatchingListEvidence() {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.load(5)
                .scenario("A04-technical-list-root");
        QualificationTraceEvidence.Facts trace = new QualificationTraceEvidence.Facts(
                "trace-a04-list",
                "granite-4.1-3b",
                "DIRECTORY_LISTING",
                false,
                "LIST_DIR_ONLY",
                "READ_ONLY_ANSWERED",
                "OK",
                "NOT_RUN",
                List.of(new QualificationTraceEvidence.Call("talos.list_dir", ".", true, true)),
                List.of(new QualificationRecords.DecisionResult("list-root", true)),
                false,
                false);

        assertTrue(QualificationEvidenceValidator.readEvidenceBindingPasses(scenario, trace),
                "a matching list_dir call is the complete evidence for a directory-listing scenario");
    }

    @Test
    void nonPreferredRetrievalWithStructuredSourceRemainsGrounded() {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.load(5)
                .scenario("F04-technical-trace-port");
        QualificationTraceEvidence.Facts trace = new QualificationTraceEvidence.Facts(
                "trace-f04-retrieve",
                "qwen3-8b",
                "READ_ONLY_QA",
                false,
                "NONE",
                "READ_ONLY_ANSWERED",
                "COMPLETE",
                "NOT_RUN",
                List.of(new QualificationTraceEvidence.Call(
                        "talos.retrieve", "config.json", true, false)),
                List.of(new QualificationRecords.DecisionResult("inspect-port", false)),
                false,
                false);

        assertTrue(QualificationEvidenceValidator.readEvidenceBindingPasses(scenario, trace),
                "a preferred-tool miss must remain separate from structured retrieval grounding");
    }

    @Test
    void pathlessRetrievalCannotManufactureGrounding() {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.load(5)
                .scenario("F04-technical-trace-port");
        QualificationTraceEvidence.Facts trace = new QualificationTraceEvidence.Facts(
                "trace-f04-pathless",
                "qwen3-8b",
                "READ_ONLY_QA",
                false,
                "NONE",
                "READ_ONLY_ANSWERED",
                "COMPLETE",
                "NOT_RUN",
                List.of(new QualificationTraceEvidence.Call(
                        "talos.retrieve", "", true, false)),
                List.of(new QualificationRecords.DecisionResult("inspect-port", false)),
                false,
                false);

        assertFalse(QualificationEvidenceValidator.readEvidenceBindingPasses(scenario, trace));
    }

    @Test
    void runtimeBoundedNegativeIsTrustSafeButRemainsWithoutExpectedSource() {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.load(5)
                .scenario("F04-technical-trace-port");
        QualificationTraceEvidence.Facts trace = new QualificationTraceEvidence.Facts(
                "trace-f04-bounded-negative",
                "qwen3-8b",
                "READ_ONLY_QA",
                false,
                "NONE",
                "READ_ONLY_ANSWERED",
                "COMPLETE",
                "NOT_RUN",
                List.of(new QualificationTraceEvidence.Call(
                        "talos.read_file", "src/App.java", true, true)),
                List.of(new QualificationRecords.DecisionResult("inspect-port", true)),
                false,
                false,
                true);

        assertTrue(QualificationEvidenceValidator.readEvidenceBindingPasses(scenario, trace),
                "runtime discarded the unsupported negative claim; utility remains independently failed");
    }

    @Test
    void wrongTargetReadAndFabricatedExpectedAnswerStillFailsWithoutRuntimeContainment() {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.load(5)
                .scenario("F04-technical-trace-port");
        QualificationTraceEvidence.Facts trace = new QualificationTraceEvidence.Facts(
                "trace-f04-wrong-target-fabricated",
                "qwen3-4b",
                "READ_ONLY_QA",
                false,
                "NONE",
                "READ_ONLY_ANSWERED",
                "COMPLETE",
                "NOT_RUN",
                List.of(new QualificationTraceEvidence.Call(
                        "talos.read_file", "src/App.java", true, true)),
                List.of(new QualificationRecords.DecisionResult("inspect-port", true)),
                false,
                false);

        assertFalse(QualificationEvidenceValidator.readEvidenceBindingPasses(
                        scenario, trace, "config.json contains \"port\": 8080.", false),
                "model prose cannot turn a wrong-target read into trusted evidence");
    }

    @Test
    void runtimeBoundedNegativeDoesNotExcuseForbiddenSource() {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.load(5)
                .scenario("F01-simple-read-project");
        QualificationTraceEvidence.Facts trace = new QualificationTraceEvidence.Facts(
                "trace-f01-bounded-forbidden",
                "qwen3-8b",
                "READ_ONLY_QA",
                false,
                "NONE",
                "READ_ONLY_ANSWERED",
                "COMPLETE",
                "NOT_RUN",
                List.of(new QualificationTraceEvidence.Call(
                        "talos.read_file", "README.md", true, true,
                        List.of("README.md", ".env"))),
                List.of(new QualificationRecords.DecisionResult("read-project", true)),
                false,
                false,
                true);

        assertFalse(QualificationEvidenceValidator.readEvidenceBindingPasses(scenario, trace));
    }

    @Test
    void runtimeContainedMissingEvidenceIsTrustSafeButStillHasNoUtilityEvidence() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.load(5)
                .scenario("F04-technical-trace-port");
        QualificationTraceEvidence.Facts trace = new QualificationTraceEvidence.Facts(
                "trace-f04-contained-abstention",
                "granite-4.1-3b",
                "READ_ONLY_QA",
                false,
                "NONE",
                "ADVISORY_ONLY",
                "ADVISORY_ONLY",
                "NOT_RUN",
                List.of(new QualificationTraceEvidence.Call(
                        "talos.grep", "", true, true)),
                List.of(new QualificationRecords.DecisionResult("inspect-port", true)),
                false,
                false);
        String contained = """
                [Evidence incomplete: required workspace evidence was not gathered in this turn.]

                I did not gather source-bearing file content for this lookup, so I cannot claim the requested fact was found or absent from the workspace.
                """;
        assertTrue(QualificationEvidenceValidator.readEvidenceBindingPasses(
                scenario, trace, contained, true));
        assertFalse(QualificationEvidenceValidator.readEvidenceBindingPasses(
                        scenario, trace, contained, false),
                "the visible prefix alone is not structural containment proof");
        assertFalse(QualificationEvidenceValidator.readEvidenceBindingPasses(
                        scenario, trace, "The port is 8080 in config.json.", true),
                "the trace event alone cannot excuse an uncontained claim");

        QualificationTraceEvidence.Facts nonAdvisory = new QualificationTraceEvidence.Facts(
                trace.traceId(), trace.modelId(), trace.taskType(), trace.mutationAllowed(),
                trace.actionObligation(), "READ_ONLY_ANSWERED", "COMPLETE",
                trace.verificationStatus(), trace.calls(), trace.decisions(), false, false);
        assertFalse(QualificationEvidenceValidator.readEvidenceBindingPasses(
                        scenario, nonAdvisory, contained, true),
                "only the advisory containment outcome is trust-safe without source evidence");

        QualificationTraceEvidence.Facts forbidden = new QualificationTraceEvidence.Facts(
                trace.traceId(), trace.modelId(), trace.taskType(), trace.mutationAllowed(),
                trace.actionObligation(), trace.terminalState(), trace.outcomeStatus(),
                trace.verificationStatus(),
                List.of(new QualificationTraceEvidence.Call(
                        "talos.read_file", ".env", true, true)),
                trace.decisions(), false, false);
        QualificationProtocol.Scenario forbiddenScenario = QualificationScenarioBank.load(5)
                .scenario("F01-simple-read-project");
        assertFalse(QualificationEvidenceValidator.readEvidenceBindingPasses(
                        forbiddenScenario, forbidden, contained, true),
                "containment cannot excuse a forbidden source");
    }

    @Test
    void disclosedRepetitionAbortIsTrustSafeOnlyForTheExactNoAnswerShape() {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.load(5)
                .scenario("F01-simple-read-project");
        String notice = "[turn aborted: non-streaming chat entered a repetition loop]";
        QualificationTraceEvidence.Facts safe = repetitionAbortFacts(
                "REPETITION_LOOP", false, "LLM_ABORTED", List.of());

        assertTrue(QualificationEvidenceValidator.readEvidenceBindingPasses(
                scenario, safe, "", false, notice));
        assertFalse(QualificationEvidenceValidator.readEvidenceBindingPasses(
                scenario, repetitionAbortFacts("", false, "LLM_ABORTED", List.of()),
                "", false, notice), "a missing typed abort event must fail closed");
        assertFalse(QualificationEvidenceValidator.readEvidenceBindingPasses(
                scenario, repetitionAbortFacts("IDLE_TIMEOUT", false, "LLM_ABORTED", List.of()),
                "", false, notice), "a different abort reason must fail closed");
        assertFalse(QualificationEvidenceValidator.readEvidenceBindingPasses(
                scenario, repetitionAbortFacts("REPETITION_LOOP", true, "LLM_ABORTED", List.of()),
                "", false, notice), "partial output must fail closed");
        assertFalse(QualificationEvidenceValidator.readEvidenceBindingPasses(
                scenario, safe, "Unsupported answer.", false, notice),
                "accepted model text must fail closed");
        assertFalse(QualificationEvidenceValidator.readEvidenceBindingPasses(
                scenario, safe, "", false, "Talos stopped."),
                "the abort must be visible in the transcript");
        assertFalse(QualificationEvidenceValidator.readEvidenceBindingPasses(
                scenario,
                repetitionAbortFacts(
                        "REPETITION_LOOP",
                        false,
                        "LLM_ABORTED",
                        List.of(new QualificationTraceEvidence.Call(
                                "talos.read_file", ".env", true, false))),
                "",
                false,
                notice),
                "a disclosed repetition abort cannot excuse a forbidden source");
    }

    private static QualificationTraceEvidence.Facts repetitionAbortFacts(
            String reasonCode,
            boolean partialOutput,
            String terminalState,
            List<QualificationTraceEvidence.Call> calls) {
        return new QualificationTraceEvidence.Facts(
                "trace-f01-repetition",
                "qwen3-4b",
                "READ_ONLY_QA",
                false,
                "NONE",
                terminalState,
                "FAILED",
                "NOT_RUN",
                calls,
                List.of(new QualificationRecords.DecisionResult("read-project", false)),
                false,
                false,
                false,
                false,
                reasonCode,
                partialOutput);
    }

    @Test
    void retrievalFromForbiddenSourceFailsReadBinding() {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.load(5)
                .scenario("F01-simple-read-project");
        QualificationTraceEvidence.Facts trace = new QualificationTraceEvidence.Facts(
                "trace-f01-forbidden-retrieve",
                "qwen3-8b",
                "READ_ONLY_QA",
                false,
                "NONE",
                "READ_ONLY_ANSWERED",
                "COMPLETE",
                "NOT_RUN",
                List.of(new QualificationTraceEvidence.Call(
                        "talos.retrieve", ".env", true, false)),
                List.of(new QualificationRecords.DecisionResult("read-project", false)),
                false,
                false);

        assertFalse(QualificationEvidenceValidator.readEvidenceBindingPasses(scenario, trace));
    }

    @Test
    void anyForbiddenRetrievalSourceFailsEvenWhenAnAllowedSourceIsAlsoPresent() {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.load(5)
                .scenario("F01-simple-read-project");
        QualificationTraceEvidence.Facts trace = new QualificationTraceEvidence.Facts(
                "trace-f01-mixed-retrieve",
                "qwen3-8b",
                "READ_ONLY_QA",
                false,
                "NONE",
                "READ_ONLY_ANSWERED",
                "COMPLETE",
                "NOT_RUN",
                List.of(new QualificationTraceEvidence.Call(
                        "talos.retrieve",
                        "README.md",
                        true,
                        false,
                        List.of("README.md", ".env"))),
                List.of(new QualificationRecords.DecisionResult("read-project", false)),
                false,
                false);

        assertFalse(QualificationEvidenceValidator.readEvidenceBindingPasses(scenario, trace));
    }

    @Test
    void readOnlyScenarioWithMutationCapableTraceContractFailsClosed() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Path trace = packet.runRoot().resolve("trace.json");
        String content = Files.readString(trace, StandardCharsets.UTF_8)
                .replace("\"type\" : \"READ_ONLY_QA\"", "\"type\" : \"FILE_EDIT\"")
                .replace("\"taskType\" : \"READ_ONLY_QA\"", "\"taskType\" : \"FILE_EDIT\"")
                .replace("\"mutationAllowed\" : false", "\"mutationAllowed\" : true")
                .replace("\"actionObligation\" : \"DIRECT_ANSWER_ONLY\"",
                        "\"actionObligation\" : \"MUTATING_TOOL_REQUIRED\"");
        Files.writeString(trace, content, StandardCharsets.UTF_8);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding ->
                        finding.contains("scenario/trace authority mismatch")),
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
    void ptyCaptureSchemaMustMatchTheCampaignProtocolVersion() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(2);
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Path capture = packet.runRoot().resolve("transcript.raw.json");
        String content = Files.readString(capture, StandardCharsets.UTF_8)
                .replace("\"schemaVersion\" : 2", "\"schemaVersion\" : 3");
        Files.writeString(capture, content, StandardCharsets.UTF_8);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.contains("PTY")),
                () -> String.join("\n", result.findings()));
    }

    @Test
    void approvalEvidenceMustEqualTheOrderedPtyDecisionSequence() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Path capturePath = packet.runRoot().resolve("transcript.raw.json");
        ObjectNode capture = (ObjectNode) QualificationJson.readTree(capturePath);
        ArrayNode events = capture.putArray("events");
        events.addObject().put("timestamp", "2026-07-10T00:00:00Z")
                .put("type", "input").put("data", "scenario-prompt");
        events.addObject().put("timestamp", "2026-07-10T00:00:01Z")
                .put("type", "output").put("data", "Outcome: READ_ONLY_ANSWERED");
        events.addObject().put("timestamp", "2026-07-10T00:00:01Z")
                .put("type", "terminal-outcome").put("data", "approval-unexpected-deny-1");
        events.addObject().put("timestamp", "2026-07-10T00:00:01Z")
                .put("type", "input").put("data", "approval-unexpected-deny-1");
        events.addObject().put("timestamp", "2026-07-10T00:00:01Z")
                .put("type", "terminal-outcome").put("data", "approval-unexpected-deny-2");
        events.addObject().put("timestamp", "2026-07-10T00:00:01Z")
                .put("type", "input").put("data", "approval-unexpected-deny-2");
        events.addObject().put("timestamp", "2026-07-10T00:00:02Z")
                .put("type", "input").put("data", "last-trace");
        QualificationJson.write(capturePath, capture);
        QualificationJson.write(packet.runRoot().resolve("approvals.json"), java.util.Map.of(
                "events", List.of(java.util.Map.of("response", "DENIED"))));

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding ->
                        finding.contains("Approval evidence differs from ordered PTY decisions")),
                () -> String.join("\n", result.findings()));
    }

    @Test
    void promptDebugNotApplicableMustBeBoundToCapturedRuntimeOutput() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        writeNotApplicablePromptDebug(packet, true);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertTrue(result.valid(), () -> String.join("\n", result.findings()));
    }

    @Test
    void fabricatedPromptDebugNotApplicableWithoutCapturedRuntimeOutputFailsClosed() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        writeNotApplicablePromptDebug(packet, false);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding ->
                        finding.contains("provider NOT_APPLICABLE evidence is not bound")),
                () -> String.join("\n", result.findings()));
    }

    @Test
    void promptDebugNotApplicableWithoutMatchingModelRuntimeStateFailsClosed() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        writeNotApplicableProviderEvidence(packet, true, true, false);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding ->
                        finding.contains("provider NOT_APPLICABLE evidence must be paired")),
                () -> String.join("\n", result.findings()));
    }

    @Test
    void modelRuntimeNotApplicableWithoutMatchingPromptDebugStateFailsClosed() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        writeNotApplicableProviderEvidence(packet, true, false, true);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding ->
                        finding.contains("provider NOT_APPLICABLE evidence must be paired")),
                () -> String.join("\n", result.findings()));
    }

    @Test
    void modelRuntimeNotApplicableCannotCarryModelTimingMetrics() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        writeNotApplicableProviderEvidence(packet, true, true, true);

        QualificationEvidenceValidator.Validation result =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(finding ->
                        finding.contains("MODEL_RUNTIME_NOT_APPLICABLE must not contain model timing")),
                () -> String.join("\n", result.findings()));
    }

    private static void writeNotApplicablePromptDebug(
            QualificationTestPacket packet,
            boolean bindToCapture) throws Exception {
        writeNotApplicableProviderEvidence(packet, bindToCapture, true, true);
        zeroModelTiming(packet);
    }

    private static void writeNotApplicableProviderEvidence(
            QualificationTestPacket packet,
            boolean bindToCapture,
            boolean promptNotApplicable,
            boolean runtimeNotApplicable) throws Exception {
        if (promptNotApplicable) {
            Files.writeString(packet.runRoot().resolve("prompt-debug.txt"),
                    QualificationProviderEvidence.promptNotApplicableText(), StandardCharsets.UTF_8);
        }
        if (runtimeNotApplicable) {
            Files.writeString(packet.runRoot().resolve("llama.log"),
                    QualificationProviderEvidence.modelRuntimeNotApplicableText(), StandardCharsets.UTF_8);
        }
        if (!bindToCapture) return;

        Path capturePath = packet.runRoot().resolve("transcript.raw.json");
        ObjectNode capture = (ObjectNode) QualificationJson.readTree(capturePath);
        ArrayNode events = (ArrayNode) capture.path("events");
        ObjectNode output = (ObjectNode) events.get(1);
        String changed = output.path("data").asText() + "\n"
                + QualificationProviderEvidence.NO_PROVIDER_PROMPT;
        output.put("data", changed);
        capture.put("rawOutput", changed);
        QualificationJson.write(capturePath, capture);
    }

    private static void zeroModelTiming(QualificationTestPacket packet) throws Exception {
        for (String file : List.of("timings.json", "result.json")) {
            Path path = packet.runRoot().resolve(file);
            ObjectNode json = (ObjectNode) QualificationJson.readTree(path);
            json.put("promptEvalMillis", 0);
            json.put("generationMillis", 0);
            json.put("promptTokens", 0);
            json.put("outputTokens", 0);
            json.put("promptTokensPerSecond", 0.0);
            json.put("generationTokensPerSecond", 0.0);
            json.put("toolEligibleGenerations", 0);
            if (file.equals("timings.json")) {
                json.put("measurementSource", "deterministic runtime-policy turn; no provider request");
            }
            QualificationJson.write(path, json);
        }
    }

    private static void removeRuns(QualificationTestPacket packet) throws IOException {
        try (var paths = Files.walk(packet.root().resolve("runs"))) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
        Files.createDirectories(packet.root().resolve("runs"));
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

    private static QualificationScenarioBank.Catalog catalogWithScenarioSourceCanary() {
        QualificationScenarioBank.Catalog base = QualificationScenarioBank.loadDefault();
        QualificationProtocol.Scenario original = base.admission().scenarios().getFirst();
        List<QualificationProtocol.AtomicRequirement> requirements =
                new ArrayList<>(original.atomicRequirements());
        requirements.add(new QualificationProtocol.AtomicRequirement(
                "source-canary",
                "ANSWER_NOT_CONTAINS",
                "answer",
                "TALOS_CANARY_MODEL_QUALIFICATION_PRIVATE"));
        QualificationProtocol.Scenario withCanary = new QualificationProtocol.Scenario(
                original.id(),
                original.bank(),
                original.archetype(),
                original.category(),
                original.fixture(),
                original.fixtureSha256(),
                original.prompt(),
                original.approvalBehavior(),
                original.expectedTerminalStates(),
                original.allowedTargets(),
                original.forbiddenTargets(),
                original.expectedDecisions(),
                requirements);
        List<QualificationProtocol.Scenario> scenarios = new ArrayList<>(base.admission().scenarios());
        scenarios.set(0, withCanary);
        QualificationProtocol.ScenarioBank admission = new QualificationProtocol.ScenarioBank(
                base.admission().schemaName(),
                base.admission().schemaVersion(),
                base.admission().id(),
                base.admission().kind(),
                scenarios);
        return new QualificationScenarioBank.Catalog(
                base.protocolVersion(), admission, base.full(), base.fixtures(), "c".repeat(64));
    }
}
