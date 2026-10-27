package dev.talos.qualification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void launchEnvironmentIdentityIncludesFrozenEndpointObserverAuthority() {
        Capture capture = run("launch-environment-hash");

        assertEquals(0, capture.exit(), capture.err());
        String[] fields = capture.out().strip().split("\\s+");
        assertEquals(4, fields.length);
        assertEquals("QUALIFICATION_RELEVANT_ALLOWLIST_V1", fields[0]);
        assertTrue(fields[1].matches("[0-9a-f]{64}"));
        assertTrue(List.of(
                        "WINDOWS_NETSTAT_NOFOLLOW_SHA256",
                        "LINUX_PROCFS_MANAGED_PROCESS_TREE_V1")
                .contains(fields[2]));
        assertTrue(fields[3].matches("[0-9a-f]{64}"));
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
        packet.removeRunEvidenceForTest();

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
    void validateReadsEveryLegacyProtocolConservativelyAndWithoutMutation() throws Exception {
        for (int protocol = 1; protocol <= 5; protocol++) {
            QualificationScenarioBank.Catalog catalog =
                    QualificationScenarioBank.load(protocol);
            QualificationTestPacket packet = QualificationTestPacket.create(
                    temp.resolve("legacy-validate-v" + protocol), catalog);
            Map<String, FileState> before = treeState(packet.root());

            Capture capture = run("validate", "--root", packet.root().toString());

            assertEquals(0, capture.exit(),
                    "protocol=" + protocol + " error=" + capture.err());
            assertTrue(capture.err().isBlank(), capture.err());
            assertTrue(capture.out().contains("Validated runs: 1"), capture.out());
            assertEquals(before, treeState(packet.root()), "protocol=" + protocol);

            for (Path historicalIdentity : List.of(
                    packet.root().resolve("engines/cuda.json"),
                    packet.root().resolve("candidates/qwen14/calibration-rules.json"))) {
                var rawIdentity = QualificationJson.readTree(historicalIdentity);
                assertEquals(QualificationRecords.SCHEMA_VERSION,
                        rawIdentity.path("schemaVersion").asInt(),
                        "protocol=" + protocol + " path=" + historicalIdentity);
                assertFalse(rawIdentity.has("launchEnvironmentSha256"),
                        "protocol=" + protocol + " path=" + historicalIdentity);
                assertFalse(rawIdentity.has("launchEnvironmentContract"),
                        "protocol=" + protocol + " path=" + historicalIdentity);
                assertFalse(rawIdentity.has("endpointObserverKind"),
                        "protocol=" + protocol + " path=" + historicalIdentity);
                assertFalse(rawIdentity.has("endpointObserverIdentitySha256"),
                        "protocol=" + protocol + " path=" + historicalIdentity);
            }

            Path resultPath = packet.runRoot().resolve("result.json");
            var raw = QualificationJson.readTree(resultPath);
            assertEquals(QualificationRecords.LEGACY_RUN_SCHEMA_VERSION,
                    raw.path("schemaVersion").asInt());
            for (String field : List.of(
                    "answerOrigin", "responseOwnerContract", "causalOwner",
                    "causalReasonCode", "comparativeScoreEligible", "causalLedger")) {
                assertFalse(raw.has(field),
                        "protocol=" + protocol + " synthetic field=" + field);
            }

            QualificationRecords.RunResult restored = QualificationJson.read(
                    resultPath, QualificationRecords.RunResult.class);
            assertTrue(restored.valid());
            assertTrue(restored.evidenceComplete());
            assertEquals(QualificationRecords.AnswerOrigin.NONE,
                    restored.answerOrigin());
            assertEquals(QualificationRecords.ResponseOwnerContract
                            .DETERMINISTIC_RUNTIME_ALLOWED,
                    restored.responseOwnerContract());
            assertEquals(QualificationRecords.CausalOwner.UNKNOWN,
                    restored.causalOwner());
            assertEquals(QualificationRecords.CausalReason
                            .LEGACY_CAUSAL_EVIDENCE_UNAVAILABLE,
                    restored.causalReasonCode());
            assertFalse(restored.comparativeScoreEligible());
            assertTrue(restored.causalLedger().isEmpty());

            QualificationCausalOwnership.Evaluation evaluation =
                    QualificationCausalOwnership.evaluate(restored, false);
            assertFalse(evaluation.complete());
            assertEquals(List.of("LEGACY_CAUSAL_SCHEMA_V1"),
                    evaluation.reasonCodes());
            QualificationCampaignContinuationPolicy.Decision continuation =
                    QualificationCampaignContinuationPolicy.evaluate(restored, false);
            assertTrue(continuation.stop());
            assertEquals(List.of("causal-evidence:LEGACY_CAUSAL_SCHEMA_V1"),
                    continuation.reasons());
        }
    }

    @Test
    void validateRejectsAnUnknownCampaignProtocolBeforeUsingLatestResources() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Path campaign = packet.root().resolve("campaign.json");
        String changed = Files.readString(campaign, StandardCharsets.UTF_8)
                .replace("\"protocolVersion\" : 6", "\"protocolVersion\" : 99");
        Files.writeString(campaign, changed, StandardCharsets.UTF_8);

        Capture capture = run("validate", "--root", packet.root().toString());

        assertEquals(2, capture.exit());
        assertTrue(capture.err().contains("Unsupported qualification protocol version: 99"), capture.err());
    }

    @Test
    void validateRejectsNonIntegralCampaignProtocolBeforeCatalogSelection() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        for (boolean textual : List.of(true, false)) {
            QualificationTestPacket packet = QualificationTestPacket.create(
                    temp.resolve("validate-protocol-" + (textual ? "text" : "fraction")),
                    catalog);
            Path campaignPath = packet.root().resolve("campaign.json");
            var campaign = (com.fasterxml.jackson.databind.node.ObjectNode)
                    QualificationJson.readTree(campaignPath);
            if (textual) {
                campaign.put("protocolVersion", "6");
            } else {
                campaign.put("protocolVersion", 6.9d);
            }
            QualificationJson.write(campaignPath, campaign);
            Map<String, FileState> before = treeState(packet.root());

            Capture capture = run("validate", "--root", packet.root().toString());

            assertEquals(2, capture.exit(), capture.err());
            assertTrue(capture.err().contains(
                    "campaign protocolVersion must be an exact JSON integer"), capture.err());
            assertEquals(before, treeState(packet.root()));
        }
    }

    @Test
    void protocolV4RequirementEvaluationRemainsReadableWithoutWritingPacketState() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(4);
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        QualificationRequirementFinalizer.Document document =
                QualificationRequirementFinalizer.evaluate(
                        catalog.protocolVersion(),
                        catalog.sha256(),
                        scenario,
                        new QualificationRequirementEvaluator.Evidence(
                                "4.\n", "{}", Map.of(), Map.of()));

        assertEquals(4, document.protocolVersion());
        assertEquals("ANSWER_SCALAR_EQUALS", document.requirements().getFirst().type());
        assertTrue(document.requirements().getFirst().passed());
    }

    @Test
    void protocolV3RequirementEvaluationPreservesHistoricalLiteralEqualityWithoutWriting() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(3);
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        QualificationRequirementFinalizer.Document document =
                QualificationRequirementFinalizer.evaluate(
                        catalog.protocolVersion(),
                        catalog.sha256(),
                        scenario,
                        new QualificationRequirementEvaluator.Evidence(
                                "4.\n", "{}", Map.of(), Map.of()));

        assertEquals(3, document.protocolVersion());
        assertEquals("ANSWER_EQUALS", document.requirements().getFirst().type());
        assertFalse(document.requirements().getFirst().passed());
    }

    @Test
    void everyLegacyMutationCommandLeavesTheCompletePacketTreeUnchanged() throws Exception {
        for (int protocol = 1; protocol <= 5; protocol++) {
            for (String command : List.of(
                    "assert-writable-campaign",
                    "requirements", "approvals", "provider-evidence", "trace-evidence", "score",
                    "complete-campaign", "reconcile-run-publication")) {
                QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(protocol);
                QualificationTestPacket packet = QualificationTestPacket.create(
                        temp.resolve("legacy-" + protocol + "-" + command), catalog);
                if (command.equals("requirements")) {
                    Files.deleteIfExists(packet.runRoot().resolve("mechanical-requirements.json"));
                } else if (command.equals("approvals")) {
                    Files.deleteIfExists(packet.runRoot().resolve("approvals.json"));
                } else if (command.equals("provider-evidence")) {
                    Files.deleteIfExists(packet.runRoot().resolve("prompt-debug.txt"));
                    Files.deleteIfExists(packet.runRoot().resolve("llama.log"));
                }
                Map<String, FileState> before = treeState(packet.root());
                String scenario = catalog.admission().scenarios().getFirst().id();

                Capture capture = switch (command) {
                    case "assert-writable-campaign" -> run(
                            command,
                            "--root", packet.root().toString());
                    case "requirements", "approvals" -> run(
                            command,
                            "--root", packet.root().toString(),
                            "--scenario", scenario,
                            "--run", packet.runRoot().toString());
                    case "provider-evidence" -> run(
                            command,
                            "--root", packet.root().toString(),
                            "--scenario", scenario,
                            "--run", packet.runRoot().toString(),
                            "--server-port", "18161");
                    case "trace-evidence" -> run(
                            command,
                            "--root", packet.root().toString(),
                            "--scenario", scenario,
                            "--run", packet.runRoot().toString());
                    case "score" -> run(
                            command,
                            "--root", packet.root().toString(),
                            "--bank", "admission");
                    case "complete-campaign" -> run(
                            command,
                            "--root", packet.root().toString(),
                            "--bank", "admission");
                    case "reconcile-run-publication" -> run(
                            command,
                            "--root", packet.root().toString(),
                            "--scenario", scenario,
                            "--run", packet.runRoot().toString(),
                            "--server-port", "18161");
                    default -> throw new AssertionError(command);
                };

                assertEquals(2, capture.exit(), "protocol=" + protocol + " command=" + command
                        + " error=" + capture.err());
                assertEquals(before, treeState(packet.root()),
                        "protocol=" + protocol + " command=" + command);
            }
        }
    }

    @Test
    void everyMutationCommandRefusesAMissingCampaignBeforeChangingThePacketTree() throws Exception {
        Path root = temp.resolve("missing-campaign");
        Files.createDirectories(root);
        Map<String, FileState> before = treeState(root);

        for (String command : List.of(
                "assert-writable-campaign",
                "requirements", "approvals", "provider-evidence", "trace-evidence", "score",
                "complete-campaign", "reconcile-run-publication")) {
            Capture capture = switch (command) {
                case "assert-writable-campaign" -> run(
                        command,
                        "--root", root.toString());
                case "requirements", "approvals" -> run(
                        command,
                        "--root", root.toString(),
                        "--scenario", "A01-missing-campaign",
                        "--run", root.resolve("runs/trial-01").toString());
                case "provider-evidence" -> run(
                        command,
                        "--root", root.toString(),
                        "--scenario", "A01-missing-campaign",
                        "--run", root.resolve("runs/trial-01").toString(),
                        "--server-port", "18161");
                case "trace-evidence" -> run(
                        command,
                        "--root", root.toString(),
                        "--scenario", "A01-missing-campaign",
                        "--run", root.resolve("runs/trial-01").toString());
                case "score" -> run(
                        command,
                        "--root", root.toString(),
                        "--bank", "admission");
                case "complete-campaign" -> run(
                        command,
                        "--root", root.toString(),
                        "--bank", "admission");
                case "reconcile-run-publication" -> run(
                        command,
                        "--root", root.toString(),
                        "--scenario", "A01-simple-direct-answer",
                        "--run", root.resolve("run").toString(),
                        "--server-port", "18161");
                default -> throw new AssertionError(command);
            };

            assertEquals(2, capture.exit(), "command=" + command + " error=" + capture.err());
            assertTrue(capture.err().contains("publication requires campaign.json"), capture.err());
            assertEquals(before, treeState(root), "command=" + command);
        }
    }

    @Test
    void writableCampaignGuardRejectsNonIntegerMissingAndDuplicateProtocolWithoutMutation()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        List<java.util.function.UnaryOperator<String>> invalidCampaigns = List.of(
                campaign -> campaign.replace("\"protocolVersion\" : 6,", ""),
                campaign -> campaign.replace(
                        "\"protocolVersion\" : 6", "\"protocolVersion\" : \"6\""),
                campaign -> campaign.replace(
                        "\"protocolVersion\" : 6", "\"protocolVersion\" : 6.5"),
                campaign -> campaign.replace(
                        "\"protocolVersion\" : 6",
                        "\"protocolVersion\" : 6,\n  \"protocolVersion\" : 6"));

        for (int index = 0; index < invalidCampaigns.size(); index++) {
            QualificationTestPacket packet = QualificationTestPacket.create(
                    temp.resolve("invalid-protocol-" + index), catalog);
            Path campaignPath = packet.root().resolve("campaign.json");
            String campaign = Files.readString(campaignPath, StandardCharsets.UTF_8);
            Files.writeString(campaignPath, invalidCampaigns.get(index).apply(campaign),
                    StandardCharsets.UTF_8);
            Map<String, FileState> before = treeState(packet.root());

            Capture capture = run(
                    "assert-writable-campaign",
                    "--root", packet.root().toString());

            assertTrue(capture.exit() != 0, "case=" + index + " out=" + capture.out());
            assertEquals(before, treeState(packet.root()), "case=" + index);
        }
    }

    @Test
    void writableCampaignGuardAcceptsTheCanonicalProtocolSixCampaignReadOnly()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve("writable-v6"), catalog);
        Map<String, FileState> before = treeState(packet.root());

        Capture capture = run(
                "assert-writable-campaign",
                "--root", packet.root().toString());

        assertEquals(0, capture.exit(), capture.err());
        assertTrue(capture.out().contains("protocol v6"), capture.out());
        assertEquals(before, treeState(packet.root()));
    }

    @Test
    void trackedResultFinalizerTemplateRunsCanonicalWritableGuardBeforePublication()
            throws Exception {
        String script = Files.readString(Path.of(
                "work-cycle-docs/templates/finalize-model-qualification-result-v6.ps1"),
                StandardCharsets.UTF_8);
        int guard = script.indexOf("assertWritableModelQualificationCampaign");
        int trace = script.indexOf("finalizeModelQualificationTraceEvidence");
        int reconcile = script.indexOf("reconcileModelQualificationRunPublication");
        int finalize = script.indexOf("finalizeModelQualificationResult");

        assertTrue(guard >= 0, "tracked finalizer template has no canonical writable guard");
        assertTrue(trace > guard, "tracked finalizer does not publish trace after its guard");
        assertTrue(reconcile > trace, "tracked finalizer does not reconcile after trace publication");
        assertTrue(finalize > reconcile, "tracked finalizer publishes before reconciliation");
        assertEquals(reconcile,
                script.lastIndexOf("reconcileModelQualificationRunPublication"));
        assertEquals(trace, script.lastIndexOf("finalizeModelQualificationTraceEvidence"));
        assertFalse(script.contains("qualificationTraceSource"));
        assertFalse(script.contains("QualificationTraceSource"));
        assertTrue(script.contains("-PqualificationServerPort="));
        assertTrue(script.contains(
                "Qualification trace evidence publication failed with exit code"));
        assertTrue(script.contains(
                "Qualification publication reconciliation failed with exit code"));
    }

    @Test
    void driveRelativeCampaignCoordinateCannotEscapeOrChangePacketState() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve("drive-relative"), catalog);
        Path campaignPath = packet.root().resolve("campaign.json");
        var campaign = (com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.readTree(campaignPath);
        campaign.put("candidateAlias", "C:escape");
        QualificationJson.write(campaignPath, campaign);
        Map<String, FileState> before = treeState(packet.root());

        Capture capture = run(
                "requirements",
                "--root", packet.root().toString(),
                "--scenario", catalog.admission().scenarios().getFirst().id(),
                "--run", packet.runRoot().toString());

        assertEquals(2, capture.exit(), capture.err());
        assertTrue(capture.err().contains("not a safe path segment"), capture.err());
        assertEquals(before, treeState(packet.root()));
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
        assertTrue(capture.err().contains("not an exact trial declared by the campaign"),
                capture.err());
        assertTrue(!Files.exists(outside.resolve("mechanical-requirements.json")));
    }

    @Test
    void scoreCommandPublishesNothingBeforeCampaignCompletion() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve("score-blocked"), catalog);
        Path scorecard = packet.root().resolve(
                "scorecards/qwen14-standard-admission.json");

        Capture capture = run(
                "score",
                "--root", packet.root().toString(),
                "--bank", "admission");

        assertEquals(1, capture.exit(), capture.err());
        assertTrue(capture.err().contains("campaign completion is not committed"),
                capture.err());
        assertFalse(Files.exists(scorecard));
    }

    @Test
    void completeCampaignCommandRefusesABankRepetitionMismatchWithoutRewritingCampaignIdentity()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.createFullCampaign(
                temp.resolve("completion-incomplete"), catalog,
                catalog.admission().scenarios().getFirst());
        Path campaign = packet.root().resolve("campaign.json");
        byte[] campaignBytes = Files.readAllBytes(campaign);
        var campaignModified = Files.getLastModifiedTime(campaign);

        Capture capture = run(
                "complete-campaign",
                "--root", packet.root().toString(),
                "--bank", "admission");

        assertEquals(1, capture.exit(), capture.err());
        assertFalse(capture.err().contains("Unknown command"), capture.err());
        assertTrue(capture.err().contains(
                "campaign repetition identities do not match the selected bank"), capture.err());
        assertFalse(Files.exists(packet.root().resolve("campaign-completion.json")));
        assertTrue(java.util.Arrays.equals(campaignBytes, Files.readAllBytes(campaign)));
        assertEquals(campaignModified, Files.getLastModifiedTime(campaign));
    }

    @Test
    void writableGuardRejectsACommittedCampaignCompletion() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.createHardStopCampaign(
                temp.resolve("completed-writable-guard"),
                catalog,
                catalog.admission().scenarios().getFirst());
        QualificationCampaignCompletion.complete(
                packet.root(), QualificationProtocol.BankKind.ADMISSION, catalog);

        Capture capture = run(
                "assert-writable-campaign",
                "--root", packet.root().toString());

        assertTrue(capture.exit() != 0, capture.out());
        assertTrue(capture.err().contains("campaign completion has started or committed"),
                capture.err());
        assertFalse(capture.out().contains("is writable"), capture.out());
    }

    @Test
    void reconcileRunPublicationReportsASettledCommittedResultWithoutMutation()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve("reconcile-committed"), catalog, scenario);
        Map<String, FileState> before = treeState(packet.root());

        Capture capture = run(
                "reconcile-run-publication",
                "--root", packet.root().toString(),
                "--scenario", scenario.id(),
                "--run", packet.runRoot().toString(),
                "--server-port", "18161");

        assertEquals(0, capture.exit(), capture.err());
        assertEquals("{\"state\":\"COMMITTED\",\"publicationKind\":\"RUN_FINALIZATION\"}",
                capture.out().strip());
        assertEquals(before, treeState(packet.root()));
    }

    @Test
    void requirementsCommandAcceptsExactExistingMechanicalResultWithoutTouchingIt() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp.resolve("immutable"), catalog);
        String[] args = {
                "requirements",
                "--root", packet.root().toString(),
                "--scenario", catalog.admission().scenarios().getFirst().id(),
                "--run", packet.runRoot().toString()
        };

        Capture first = run(args);
        Path output = packet.runRoot().resolve("mechanical-requirements.json");
        byte[] before = Files.readAllBytes(output);
        var modified = Files.getLastModifiedTime(output);
        Capture second = run(args);

        assertEquals(0, first.exit(), first.err());
        assertEquals(0, second.exit(), second.err());
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(output)));
        assertEquals(modified, Files.getLastModifiedTime(output));
    }

    @Test
    void publicationFailureIsMachineTypedBeforeTheHumanDiagnostic() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve("typed-publication-failure"), catalog, scenario);
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
        packet.removePublicationForTest(
                QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                List.of(
                        packet.runRoot().resolve("llama.log"),
                        packet.runRoot().resolve("prompt-debug.txt")));
        Map<String, FileState> before = treeState(packet.root());

        Capture capture = run(
                "requirements",
                "--root", packet.root().toString(),
                "--scenario", scenario.id(),
                "--run", packet.runRoot().toString());

        assertEquals(3, capture.exit(), capture.err());
        assertEquals(
                "{\"schemaName\":\"talos.modelQualification.publicationFailure\""
                        + ",\"schemaVersion\":1,\"reason\":\"INVALID_REQUEST\""
                        + ",\"commitState\":\"NOT_COMMITTED\"}",
                capture.err().lines().findFirst().orElseThrow());
        assertTrue(capture.err().contains("committed predecessor"), capture.err());
        assertEquals(before, treeState(packet.root()));
    }

    @Test
    void approvalsCommandWritesEveryOrderedPtyDecisionImmutably() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp.resolve("approvals"), catalog);
        Path output = packet.runRoot().resolve("approvals.json");
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
                java.util.List.of(output));
        Path capturePath = packet.runRoot().resolve("transcript.raw.json");
        var capture = (com.fasterxml.jackson.databind.node.ObjectNode)
                QualificationJson.readTree(capturePath);
        var events = (com.fasterxml.jackson.databind.node.ArrayNode)
                capture.path("events");
        events.insertObject(1)
                .put("timestamp", "2026-07-10T00:00:00Z")
                .put("type", "terminal-outcome")
                .put("data", "approval-unexpected-deny-1");
        events.insertObject(2)
                .put("timestamp", "2026-07-10T00:00:00Z")
                .put("type", "input")
                .put("data", "approval-unexpected-deny-1");
        events.insertObject(3)
                .put("timestamp", "2026-07-10T00:00:01Z")
                .put("type", "terminal-outcome")
                .put("data", "approval-unexpected-deny-2");
        events.insertObject(4)
                .put("timestamp", "2026-07-10T00:00:01Z")
                .put("type", "input")
                .put("data", "approval-unexpected-deny-2");
        QualificationJson.write(capturePath, capture);
        packet.refreshPublicationForTest(
                QualificationPacketPublisher.PublicationKind.PTY_CAPTURE,
                java.util.List.of(capturePath));
        String[] args = {
                "approvals",
                "--root", packet.root().toString(),
                "--scenario", catalog.admission().scenarios().getFirst().id(),
                "--run", packet.runRoot().toString()
        };

        Capture first = run(args);
        byte[] before = Files.readAllBytes(output);
        var modified = Files.getLastModifiedTime(output);
        Capture second = run(args);

        assertEquals(0, first.exit(), first.err());
        var json = QualificationJson.readTree(output);
        assertEquals(2, json.path("events").size());
        assertEquals("DENIED", json.path("events").get(0).path("response").asText());
        assertEquals("DENIED", json.path("events").get(1).path("response").asText());
        assertEquals(0, second.exit(), second.err());
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(output)));
        assertEquals(modified, Files.getLastModifiedTime(output));
    }

    @Test
    void traceEvidenceCommandPublishesOnlyThroughTheJavaTransactionOwner() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationProtocol.Scenario selected =
                catalog.admission().scenarios().getFirst();
        QualificationTestPacket packet = QualificationTestPacket.createProcessBound(
                temp.resolve("trace-evidence"), catalog, selected);
        String scenario = selected.id();
        Path target = packet.runRoot().resolve("trace.json");
        byte[] expected = Files.readAllBytes(target);
        packet.removePublicationForTest(
                QualificationPacketPublisher.PublicationKind.TRACE_EVIDENCE,
                List.of(target));
        Path source = packet.root().resolve(
                "home/.talos/sessions/traces/session-one/000001-trace-1.json");
        Files.createDirectories(source.getParent());
        Files.write(source, expected);
        assertFalse(Files.exists(target));

        Capture capture = run(
                "trace-evidence",
                "--root", packet.root().toString(),
                "--scenario", scenario,
                "--run", packet.runRoot().toString());

        assertEquals(0, capture.exit(), capture.err());
        assertTrue(capture.out().contains("traceId=trace-1"), capture.out());
        assertTrue(capture.out().contains("created=true"), capture.out());
        assertTrue(java.util.Arrays.equals(expected, Files.readAllBytes(target)));
        assertTrue(QualificationTraceEvidencePublication.requireCommittedForSchema(
                packet.root(), packet.runRoot(), 5, expected).isPresent());
    }

    @Test
    void providerEvidenceCommandAcceptsIdentityOnlyStartupWithNoProviderTurn() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve("provider-evidence"), catalog);
        String scenario = catalog.admission().scenarios().getFirst().id();
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
        Files.writeString(packet.runRoot().resolve("prompt-debug.txt"),
                QualificationProviderEvidence.promptNotApplicableText(), StandardCharsets.UTF_8);
        Files.writeString(packet.runRoot().resolve("llama.log"),
                QualificationProviderEvidence.modelRuntimeNotApplicableText(), StandardCharsets.UTF_8);
        packet.refreshPublicationForTest(
                QualificationPacketPublisher.PublicationKind.PROVIDER_EVIDENCE,
                java.util.List.of(
                        packet.runRoot().resolve("prompt-debug.txt"),
                        packet.runRoot().resolve("llama.log")));
        Path source = packet.root().resolve("home/.talos/logs/llama_cpp-18161.log");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "model loaded\nlistening\nstopped\n", StandardCharsets.UTF_8);

        Capture capture = run(
                "provider-evidence",
                "--root", packet.root().toString(),
                "--scenario", scenario,
                "--run", packet.runRoot().toString(),
                "--server-port", "18161");

        assertEquals(0, capture.exit(), capture.err());
        assertTrue(capture.out().contains("providerPromptSent=false"), capture.out());
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

    private record Capture(int exit, String out, String err) {}

    private record FileState(
            String kind,
            long size,
            java.nio.file.attribute.FileTime modified,
            String sha256) {}
}
