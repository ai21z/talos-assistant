package dev.talos.qualification;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationCausalOwnershipTest {
    @TempDir
    Path temp;

    @Test
    void mechanicallyBoundProviderAnswerMayRemainAModelFailure() {
        QualificationRecords.RunResult result = failedResult(
                QualificationRecords.FailureClass.MODEL_FAILURE,
                QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                QualificationRecords.CausalOwner.MODEL);

        QualificationCausalOwnership.Evaluation evaluation =
                QualificationCausalOwnership.evaluate(result, true);

        assertTrue(evaluation.complete(), evaluation.reasonCodes().toString());
        assertEquals(List.of(), evaluation.reasonCodes());
    }

    @Test
    void deterministicRuntimeAnswerCannotBeClassifiedAsModelFailure() {
        QualificationRecords.RunResult result = failedResult(
                QualificationRecords.FailureClass.MODEL_FAILURE,
                QualificationRecords.AnswerOrigin.RUNTIME_DETERMINISTIC,
                QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                QualificationRecords.CausalOwner.MODEL);

        QualificationCausalOwnership.Evaluation evaluation =
                QualificationCausalOwnership.evaluate(result, false);

        assertFalse(evaluation.complete());
        assertTrue(evaluation.reasonCodes().contains("MODEL_FAILURE_ANSWER_NOT_MODEL_PROVIDER"));
        assertTrue(evaluation.reasonCodes().contains("MODEL_FAILURE_LACKS_MATCHED_MODEL_RESPONSE"));
    }

    @Test
    void unknownAndMixedOwnershipRemainIncompleteEvidence() {
        for (QualificationRecords.CausalOwner owner : List.of(
                QualificationRecords.CausalOwner.UNKNOWN,
                QualificationRecords.CausalOwner.MIXED)) {
            QualificationRecords.RunResult result = failedResult(
                    QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                    QualificationRecords.AnswerOrigin.NONE,
                    QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                    owner);

            QualificationCausalOwnership.Evaluation evaluation =
                    QualificationCausalOwnership.evaluate(result, false);

            assertFalse(evaluation.complete(), owner.name());
            assertTrue(evaluation.reasonCodes().contains("CAUSAL_OWNER_" + owner.name()),
                    evaluation.reasonCodes().toString());
            assertTrue(QualificationCampaignContinuationPolicy.evaluate(result, false).stop());
        }
    }

    @Test
    void incompleteEvidenceCannotRemainEligibleForComparativeScoring() throws Exception {
        QualificationRecords.RunResult result = failedResult(
                QualificationRecords.FailureClass.MODEL_FAILURE,
                QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                QualificationRecords.CausalOwner.MODEL);
        ObjectNode json = (ObjectNode) QualificationJson.readTree(writeTemp(result));
        json.put("evidenceComplete", false);
        ((ObjectNode) json.path("trustGates")).put("mandatoryEvidencePresent", false);
        QualificationRecords.RunResult inconsistent = QualificationJson.read(
                QualificationJson.bytes(json), QualificationRecords.RunResult.class);

        QualificationCausalOwnership.Evaluation evaluation =
                QualificationCausalOwnership.evaluate(inconsistent, true);

        assertFalse(evaluation.structurallyComplete());
        assertTrue(evaluation.reasonCodes().contains(
                "INCOMPLETE_EVIDENCE_MARKED_SCORE_ELIGIBLE"));
        assertTrue(evaluation.reasonCodes().contains(
                "COMPARATIVE_SCORE_ELIGIBILITY_MISMATCH"));
    }

    @Test
    void evidenceCompletenessMustAgreeWithTheMandatoryEvidenceGate() throws Exception {
        QualificationRecords.RunResult result = failedResult(
                QualificationRecords.FailureClass.MODEL_FAILURE,
                QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                QualificationRecords.CausalOwner.MODEL);
        ObjectNode json = (ObjectNode) QualificationJson.readTree(writeTemp(result));
        ((ObjectNode) json.path("trustGates")).put("mandatoryEvidencePresent", false);
        QualificationRecords.RunResult inconsistent = QualificationJson.read(
                QualificationJson.bytes(json), QualificationRecords.RunResult.class);

        QualificationCausalOwnership.Evaluation evaluation =
                QualificationCausalOwnership.evaluate(inconsistent, true);

        assertFalse(evaluation.structurallyComplete());
        assertTrue(evaluation.reasonCodes().contains(
                "EVIDENCE_COMPLETE_MANDATORY_GATE_MISMATCH"));
    }

    @Test
    void providerAnswerOriginMustMatchThePersistedModelResponseHash() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        ObjectNode trace = (ObjectNode) QualificationJson.readTree(
                packet.runRoot().resolve("trace.json"));
        String forgedHash = "sha256:" + "0".repeat(64);
        ((ObjectNode) event(trace, "GENERATION_RESPONSE_CAPTURED").path("data"))
                .put("assistantHash", forgedHash);
        ((ObjectNode) event(trace, "GENERATION_COMPLETED").path("data"))
                .put("assistantHash", forgedHash);
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);

        assertEquals(QualificationRecords.AnswerOrigin.NONE,
                QualificationCausalOwnership.matchedAnswerOrigin(
                        Files.readAllBytes(packet.runRoot().resolve("answer.txt")),
                        Files.readAllBytes(packet.runRoot().resolve("trace.json")),
                        QualificationProtocol.VERSION));

        QualificationEvidenceValidator.Validation validation =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(validation.valid());
        assertTrue(validation.findings().stream().anyMatch(finding ->
                        finding.contains("publication verification failed")),
                () -> String.join("\n", validation.findings()));
    }

    @Test
    void exactRuntimeAnswerTransformationChainPreservesProviderOrigin() throws Exception {
        QualificationTestPacket packet = transformedProviderPacket("valid-chain");

        assertEquals(QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                matchedOrigin(packet));
    }

    @Test
    void reviewedToolLoopTransformationReasonsPreserveExactLineage() throws Exception {
        for (String reason : List.of(
                "TOOL_LOOP_ITERATION_LIMIT",
                "TOOL_LOOP_FINALIZATION")) {
            QualificationTestPacket packet = transformedProviderPacket(
                    "tool-loop-reason-" + reason.toLowerCase(java.util.Locale.ROOT));
            ObjectNode trace = trace(packet);
            ((ObjectNode) event(trace, "ANSWER_TRANSFORMED").path("data"))
                    .put("reasonCode", reason);
            QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);

            assertEquals(QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                    matchedOrigin(packet), reason);
        }
    }

    @Test
    void runtimeAnswerTransformationRejectsForgedSourceHash() throws Exception {
        QualificationTestPacket packet = transformedProviderPacket("forged-source");
        ObjectNode trace = trace(packet);
        ObjectNode transformed = event(trace, "ANSWER_TRANSFORMED");
        ((ObjectNode) transformed.path("data"))
                .put("fromAnswerHash", "sha256:" + "0".repeat(64));
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);

        assertEquals(QualificationRecords.AnswerOrigin.NONE, matchedOrigin(packet));
    }

    @Test
    void runtimeAnswerTransformationRejectsStaleReorderedChain() throws Exception {
        QualificationTestPacket packet = transformedProviderPacket("reordered-chain");
        ObjectNode trace = trace(packet);
        ArrayNode events = trace.withArray("events");
        int first = eventIndex(events, "ANSWER_TRANSFORMED");
        ObjectNode firstEvent = (ObjectNode) events.get(first).deepCopy();
        ObjectNode secondEvent = (ObjectNode) events.get(first + 1).deepCopy();
        events.set(first, secondEvent);
        events.set(first + 1, firstEvent);
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);

        assertEquals(QualificationRecords.AnswerOrigin.NONE, matchedOrigin(packet));
    }

    @Test
    void runtimeAnswerTransformationRejectsUnknownReason() throws Exception {
        QualificationTestPacket packet = transformedProviderPacket("unknown-reason");
        ObjectNode trace = trace(packet);
        ObjectNode transformed = event(trace, "ANSWER_TRANSFORMED");
        ((ObjectNode) transformed.path("data"))
                .put("reasonCode", "UNREVIEWED_RUNTIME_REWRITE");
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);

        assertEquals(QualificationRecords.AnswerOrigin.NONE, matchedOrigin(packet));
    }

    @Test
    void runtimeAnswerTransformationAfterTerminalInvalidatesOrigin() throws Exception {
        QualificationTestPacket packet = transformedProviderPacket("post-terminal");
        ObjectNode trace = trace(packet);
        ArrayNode events = trace.withArray("events");
        int terminal = eventIndex(events, "TERMINAL_ANSWER_SELECTED");
        events.insert(terminal + 1, answerTransformation(
                "4", "post-terminal", "SESSION_COMMIT_NORMALIZATION"));
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);

        assertEquals(QualificationRecords.AnswerOrigin.NONE, matchedOrigin(packet));
    }

    @Test
    void exactProviderCandidateRestorationSurvivesRejectedForegroundRetry()
            throws Exception {
        QualificationTestPacket packet = restoredProviderPacket("valid-provider-restoration");

        assertEquals(QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                matchedOrigin(packet));
        QualificationProtocol.Scenario scenario = QualificationTestPacket.protocol6Catalog()
                .admission().scenarios().getFirst();
        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(
                packet.runRoot().resolve("trace.json"), scenario);
        assertTrue(facts.runtimeAnswerTransformed(),
                "runtime restoration must remain visible to causal finalization");
    }

    @Test
    void providerCandidateRestorationRejectsForgedSourceGeneration()
            throws Exception {
        QualificationTestPacket packet = restoredProviderPacket("forged-provider-restoration");
        ObjectNode trace = trace(packet);
        ((ObjectNode) event(trace, "ANSWER_CANDIDATE_RESTORED").path("data"))
                .put("sourceGenerationId", "forged-generation");
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);

        assertEquals(QualificationRecords.AnswerOrigin.NONE, matchedOrigin(packet));
    }

    @Test
    void providerCandidateRestorationRejectsForgedFingerprintAndUnknownReason()
            throws Exception {
        QualificationTestPacket forged = restoredProviderPacket("forged-restoration-fingerprint");
        ObjectNode forgedTrace = trace(forged);
        ((ObjectNode) event(forgedTrace, "ANSWER_CANDIDATE_RESTORED").path("data"))
                .put("answerHash", "sha256:" + "0".repeat(64));
        QualificationJson.write(forged.runRoot().resolve("trace.json"), forgedTrace);
        assertEquals(QualificationRecords.AnswerOrigin.NONE, matchedOrigin(forged));

        QualificationTestPacket unknown = restoredProviderPacket("unknown-restoration-reason");
        ObjectNode unknownTrace = trace(unknown);
        ((ObjectNode) event(unknownTrace, "ANSWER_CANDIDATE_RESTORED").path("data"))
                .put("reasonCode", "UNREVIEWED_RETRY_FALLBACK");
        QualificationJson.write(unknown.runRoot().resolve("trace.json"), unknownTrace);
        assertEquals(QualificationRecords.AnswerOrigin.NONE, matchedOrigin(unknown));
        QualificationProtocol.Scenario scenario = QualificationTestPacket.protocol6Catalog()
                .admission().scenarios().getFirst();
        assertThrows(IllegalArgumentException.class, () -> QualificationTraceEvidence.read(
                unknown.runRoot().resolve("trace.json"), scenario));
    }

    @Test
    void everyRestorationMustBindEvenWhenALaterRestorationMatchesTheTerminalAnswer()
            throws Exception {
        QualificationTestPacket packet = restoredProviderPacket("conflicting-restoration-chain");
        ObjectNode trace = trace(packet);
        ArrayNode events = trace.withArray("events");
        int finalRestoration = eventIndex(events, "ANSWER_CANDIDATE_RESTORED");
        events.insert(finalRestoration, traceEvent("ANSWER_CANDIDATE_RESTORED", Map.of(
                "answerHash", answerHash("unbound"),
                "answerChars", "unbound".length(),
                "origin", "MODEL_PROVIDER",
                "sourceGenerationId", "unbound-generation",
                "sourceAttemptId", "unbound-attempt",
                "reasonCode", "SPECULATIVE_RETRY_REJECTED")));
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);

        assertEquals(QualificationRecords.AnswerOrigin.NONE, matchedOrigin(packet));
    }

    @Test
    void restorationSchemaMustNotAcceptAnUnverifiableByteCount()
            throws Exception {
        QualificationTestPacket packet = restoredProviderPacket("unverifiable-restoration-bytes");
        ObjectNode trace = trace(packet);
        ((ObjectNode) event(trace, "ANSWER_CANDIDATE_RESTORED").path("data"))
                .put("answerBytes", 999);
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);

        assertEquals(QualificationRecords.AnswerOrigin.NONE, matchedOrigin(packet));
        QualificationProtocol.Scenario scenario = QualificationTestPacket.protocol6Catalog()
                .admission().scenarios().getFirst();
        assertThrows(IllegalArgumentException.class, () -> QualificationTraceEvidence.read(
                packet.runRoot().resolve("trace.json"), scenario));
    }

    @Test
    void providerCandidateRestorationAfterTerminalCannotRewriteOrigin()
            throws Exception {
        QualificationTestPacket packet = restoredProviderPacket("post-terminal-restoration");
        ObjectNode trace = trace(packet);
        ArrayNode events = trace.withArray("events");
        int restoration = eventIndex(events, "ANSWER_CANDIDATE_RESTORED");
        ObjectNode restored = (ObjectNode) events.remove(restoration);
        events.insert(eventIndex(events, "TERMINAL_ANSWER_SELECTED") + 1, restored);
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);

        assertEquals(QualificationRecords.AnswerOrigin.NONE, matchedOrigin(packet));
    }

    @Test
    void toolBearingProviderProseRequiresExactTaskContractSuppressionProof()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = noToolScenario(catalog);
        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve("tool-suppression-valid"), catalog, scenario);
        ObjectNode trace = trace(packet);
        markSelectedResponseAsTextualToolBearing(trace);
        insertProviderToolSuppression(trace, false);
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);

        assertEquals(QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                matchedOrigin(packet));
        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(
                packet.runRoot().resolve("trace.json"), scenario);
        QualificationRecords.DecisionResult noTool = facts.decisions().stream()
                .filter(result -> scenario.expectedDecisions().stream().anyMatch(expected ->
                        expected.id().equals(result.id())
                                && expected.kind() == QualificationProtocol.DecisionKind.NO_TOOL))
                .findFirst()
                .orElseThrow();
        assertFalse(noTool.correct(),
                "a policy-suppressed raw provider tool proposal is still a model decision");
    }

    @Test
    void toolBearingProviderProseWithoutSuppressionProofHasNoCanonicalOrigin()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = noToolScenario(catalog);
        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve("tool-suppression-missing"), catalog, scenario);
        ObjectNode trace = trace(packet);
        markSelectedResponseAsTextualToolBearing(trace);
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);

        assertEquals(QualificationRecords.AnswerOrigin.NONE, matchedOrigin(packet));
    }

    @Test
    void mismatchedProviderToolSuppressionProofFailsClosedInBothReaders()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = noToolScenario(catalog);
        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve("tool-suppression-mismatch"), catalog, scenario);
        ObjectNode trace = trace(packet);
        markSelectedResponseAsTextualToolBearing(trace);
        insertProviderToolSuppression(trace, true);
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);

        assertEquals(QualificationRecords.AnswerOrigin.NONE, matchedOrigin(packet));
        assertThrows(IllegalArgumentException.class, () -> QualificationTraceEvidence.read(
                packet.runRoot().resolve("trace.json"), scenario));
    }

    @Test
    void continuationGuardFailsClosedWhileActiveProcessBindingIsUnavailable()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Path resultPath = packet.runRoot().resolve("result.json");

        QualificationRecords.RunResult result = QualificationJson.read(
                resultPath, QualificationRecords.RunResult.class);
        assertEquals(QualificationRecords.CausalOwner.UNKNOWN, result.causalOwner());
        assertFalse(result.comparativeScoreEligible());
        assertEquals(1, continuationExit(packet.root(),
                resultPath.getParent().getParent().getFileName().toString(), 1));

        ObjectNode trace = (ObjectNode) QualificationJson.readTree(
                packet.runRoot().resolve("trace.json"));
        String forgedHash = "sha256:" + "0".repeat(64);
        ((ObjectNode) event(trace, "GENERATION_RESPONSE_CAPTURED").path("data"))
                .put("assistantHash", forgedHash);
        ((ObjectNode) event(trace, "GENERATION_COMPLETED").path("data"))
                .put("assistantHash", forgedHash);
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);

        assertEquals(1, continuationExit(packet.root(),
                resultPath.getParent().getParent().getFileName().toString(), 1));
    }

    @Test
    void legacyV1ResultRemainsReadableButCannotEnterNewComparativeScoring() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationRecords.RunResult current = QualificationTestPacket.runResult(
                catalog, catalog.admission().scenarios().getFirst(), 1, true, true);
        ObjectNode legacy = (ObjectNode) QualificationJson.readTree(writeTemp(current));
        legacy.put("schemaVersion", QualificationRecords.LEGACY_RUN_SCHEMA_VERSION);
        legacy.remove(List.of(
                "answerOrigin",
                "responseOwnerContract",
                "causalOwner",
                "causalReasonCode",
                "comparativeScoreEligible",
                "causalLedger"));

        QualificationRecords.RunResult restored = QualificationJson.read(
                QualificationJson.bytes(legacy), QualificationRecords.RunResult.class);
        QualificationCausalOwnership.Evaluation evaluation =
                QualificationCausalOwnership.evaluate(restored, false);

        assertEquals(QualificationRecords.LEGACY_RUN_SCHEMA_VERSION, restored.schemaVersion());
        assertEquals(QualificationRecords.CausalOwner.UNKNOWN, restored.causalOwner());
        assertFalse(evaluation.complete());
        assertTrue(evaluation.reasonCodes().contains("LEGACY_CAUSAL_SCHEMA_V1"));
        assertTrue(QualificationCampaignContinuationPolicy.evaluate(restored, false).stop());
    }

    @Test
    void legacyV1ResultRejectsSyntheticCausalFields() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationRecords.RunResult current = QualificationTestPacket.runResult(
                catalog, catalog.admission().scenarios().getFirst(), 1, true, true);
        ObjectNode legacy = (ObjectNode) QualificationJson.readTree(writeTemp(current));
        legacy.put("schemaVersion", QualificationRecords.LEGACY_RUN_SCHEMA_VERSION);
        legacy.remove(List.of(
                "responseOwnerContract",
                "causalOwner",
                "causalReasonCode",
                "comparativeScoreEligible",
                "causalLedger"));
        legacy.put("answerOrigin", QualificationRecords.AnswerOrigin.MODEL_PROVIDER.name());

        assertThrows(IllegalArgumentException.class, () -> QualificationJson.read(
                QualificationJson.bytes(legacy), QualificationRecords.RunResult.class));
    }

    @Test
    void legacyProviderEvidenceRequiresExactBooleanScalarTypes() throws Exception {
        byte[] answer = "4".getBytes(StandardCharsets.UTF_8);
        List<LegacyBooleanCase> cases = List.of(
                new LegacyBooleanCase("providerBacked-text", "providerBacked", true),
                new LegacyBooleanCase("providerBacked-number", "providerBacked", false),
                new LegacyBooleanCase("foreground-text", "foreground", true),
                new LegacyBooleanCase("foreground-number", "foreground", false),
                new LegacyBooleanCase("completedNormally-text", "completedNormally", true),
                new LegacyBooleanCase("completedNormally-number", "completedNormally", false));
        List<String> coercionsAccepted = new ArrayList<>();

        for (LegacyBooleanCase testCase : cases) {
            Map<String, Object> responseData = new java.util.LinkedHashMap<>(Map.of(
                    "providerBacked", true,
                    "foreground", true,
                    "completedNormally", true,
                    "assistantHash", answerHash("4"),
                    "assistantChars", 1));
            responseData.put(testCase.field(), testCase.textual() ? "true" : 1);
            ObjectNode trace = (ObjectNode) QualificationJson.tree(Map.of(
                    "schemaVersion", 2,
                    "events", List.of(
                            traceEvent("PROVIDER_GENERATION_RECEIVED",
                                    responseData),
                            traceEvent("OUTCOME_RENDERED", Map.of()))));

            QualificationRecords.AnswerOrigin origin =
                    QualificationCausalOwnership.matchedAnswerOrigin(
                            answer, QualificationJson.bytes(trace), 5);
            if (origin != QualificationRecords.AnswerOrigin.NONE) {
                coercionsAccepted.add(testCase.id() + "=" + origin);
            }
        }

        assertTrue(coercionsAccepted.isEmpty(),
                () -> "legacy provider booleans were coerced into authority: "
                        + coercionsAccepted);
    }

    @Test
    void preciseNonModelOwnersRemainDistinct() {
        assertOwnerAccepted(QualificationRecords.CausalOwner.RUNTIME_POLICY,
                QualificationRecords.FailureClass.TALOS_RUNTIME_DEFECT);
        assertOwnerAccepted(QualificationRecords.CausalOwner.VERIFIER,
                QualificationRecords.FailureClass.TALOS_RUNTIME_DEFECT);
        assertOwnerAccepted(QualificationRecords.CausalOwner.HARNESS,
                QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE);
        assertOwnerAccepted(QualificationRecords.CausalOwner.PROVIDER,
                QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE);
        assertOwnerAccepted(QualificationRecords.CausalOwner.HARDWARE,
                QualificationRecords.FailureClass.HARDWARE_FAILURE);
        assertOwnerAccepted(QualificationRecords.CausalOwner.OPERATOR,
                QualificationRecords.FailureClass.OPERATOR_INTERRUPTION);
    }

    @Test
    void exactModelDecisionFailureDoesNotRequireAModelOwnedTerminalResponse() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationRecords.RunResult base = QualificationTestPacket.runResult(
                catalog, catalog.admission().scenarios().getFirst(), 1, false, false);
        QualificationRecords.CausalLedgerEntry decisionFailure =
                new QualificationRecords.CausalLedgerEntry(
                        1,
                        QualificationRecords.CausalStage.DECISION,
                        QualificationRecords.CausalOwner.MODEL,
                        QualificationRecords.CausalFact.SCORED_DECISION_FAILED,
                        QualificationRecords.CausalReason.MODEL_DECISION_FAILED,
                        "trace.json#provider-native-decision",
                        true);
        QualificationRecords.RunResult result = base.withFailure(
                QualificationRecords.FailureClass.MODEL_FAILURE,
                QualificationRecords.AnswerOrigin.RUNTIME_DETERMINISTIC,
                QualificationRecords.ResponseOwnerContract.DETERMINISTIC_RUNTIME_ALLOWED,
                QualificationRecords.CausalOwner.MODEL,
                List.of(decisionFailure));

        QualificationCausalOwnership.Evaluation evaluation =
                QualificationCausalOwnership.evaluateStructural(result);

        assertTrue(evaluation.structurallyComplete(), evaluation.reasonCodes().toString());
    }

    @Test
    void forgedHarnessOwnedRuntimeDefectIsStructurallyRejected() {
        QualificationRecords.RunResult forged = failedResult(
                QualificationRecords.FailureClass.TALOS_RUNTIME_DEFECT,
                QualificationRecords.AnswerOrigin.NONE,
                QualificationRecords.ResponseOwnerContract.DETERMINISTIC_RUNTIME_ALLOWED,
                QualificationRecords.CausalOwner.HARNESS);

        QualificationCausalOwnership.Evaluation evaluation =
                QualificationCausalOwnership.evaluate(forged, false);

        assertFalse(evaluation.structurallyComplete());
        assertTrue(evaluation.structuralReasonCodes().contains(
                "RUNTIME_DEFECT_CAUSAL_OWNER_MISMATCH"));
        assertTrue(evaluation.structuralReasonCodes().contains(
                "HARNESS_OWNER_REQUIRES_INCOMPLETE_EVIDENCE"));
    }

    @Test
    void failureClassAndCausalOwnerMatrixRejectsForgedSuccessAndIncompleteOwners() {
        QualificationRecords.RunResult forgedSuccess = failedResult(
                QualificationRecords.FailureClass.NONE,
                QualificationRecords.AnswerOrigin.NONE,
                QualificationRecords.ResponseOwnerContract.DETERMINISTIC_RUNTIME_ALLOWED,
                QualificationRecords.CausalOwner.PROVIDER);

        QualificationCausalOwnership.Evaluation successEvaluation =
                QualificationCausalOwnership.evaluateStructural(forgedSuccess);

        assertFalse(successEvaluation.structurallyComplete());
        assertTrue(successEvaluation.structuralReasonCodes().contains(
                "SUCCESS_CAUSAL_OWNER_MISMATCH"));

        for (QualificationRecords.CausalOwner owner : List.of(
                QualificationRecords.CausalOwner.MODEL,
                QualificationRecords.CausalOwner.RUNTIME_POLICY,
                QualificationRecords.CausalOwner.VERIFIER,
                QualificationRecords.CausalOwner.HARDWARE,
                QualificationRecords.CausalOwner.OPERATOR)) {
            QualificationRecords.RunResult forgedIncomplete = failedResult(
                    QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                    owner == QualificationRecords.CausalOwner.MODEL
                            || owner == QualificationRecords.CausalOwner.VERIFIER
                            ? QualificationRecords.AnswerOrigin.MODEL_PROVIDER
                            : owner == QualificationRecords.CausalOwner.OPERATOR
                                    ? QualificationRecords.AnswerOrigin.OPERATOR
                                    : QualificationRecords.AnswerOrigin.NONE,
                    owner == QualificationRecords.CausalOwner.OPERATOR
                            ? QualificationRecords.ResponseOwnerContract.OPERATOR_OWNED
                            : QualificationRecords.ResponseOwnerContract
                                    .DETERMINISTIC_RUNTIME_ALLOWED,
                    owner);

            QualificationCausalOwnership.Evaluation incompleteEvaluation =
                    QualificationCausalOwnership.evaluateStructural(forgedIncomplete);

            assertFalse(incompleteEvaluation.structurallyComplete(), owner.name());
            assertTrue(incompleteEvaluation.structuralReasonCodes().contains(
                    "INCOMPLETE_EVIDENCE_CAUSAL_OWNER_MISMATCH"), owner.name());
        }
    }

    @Test
    void runResultRejectsWrongSchemaNameAndUnsupportedSchemaVersionAtDeserialization()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationRecords.RunResult current = QualificationTestPacket.runResult(
                catalog, catalog.admission().scenarios().getFirst(), 1, true, true);

        ObjectNode wrongName = (ObjectNode) QualificationJson.readTree(writeTemp(current));
        wrongName.put("schemaName", "talos.modelQualification.notRunResult");
        assertThrows(com.fasterxml.jackson.databind.exc.ValueInstantiationException.class,
                () -> QualificationJson.read(
                QualificationJson.bytes(wrongName), QualificationRecords.RunResult.class));

        ObjectNode unsupportedVersion =
                (ObjectNode) QualificationJson.readTree(writeTemp(current));
        unsupportedVersion.put("schemaVersion", 3);
        assertThrows(com.fasterxml.jackson.databind.exc.ValueInstantiationException.class,
                () -> QualificationJson.read(
                QualificationJson.bytes(unsupportedVersion),
                QualificationRecords.RunResult.class));
    }

    private static void assertOwnerAccepted(
            QualificationRecords.CausalOwner owner,
            QualificationRecords.FailureClass failureClass) {
        QualificationRecords.AnswerOrigin origin = switch (owner) {
            case MODEL, VERIFIER -> QualificationRecords.AnswerOrigin.MODEL_PROVIDER;
            case OPERATOR -> QualificationRecords.AnswerOrigin.OPERATOR;
            case PROVIDER, HARDWARE, HARNESS -> QualificationRecords.AnswerOrigin.NONE;
            case RUNTIME_POLICY ->
                    QualificationRecords.AnswerOrigin.RUNTIME_DETERMINISTIC;
            case MIXED, UNKNOWN -> QualificationRecords.AnswerOrigin.NONE;
        };
        QualificationRecords.RunResult result = failedResult(
                failureClass,
                origin,
                owner == QualificationRecords.CausalOwner.OPERATOR
                        ? QualificationRecords.ResponseOwnerContract.OPERATOR_OWNED
                        : QualificationRecords.ResponseOwnerContract.DETERMINISTIC_RUNTIME_ALLOWED,
                owner);

        QualificationCausalOwnership.Evaluation evaluation =
                QualificationCausalOwnership.evaluate(result, false);

        assertTrue(evaluation.structurallyComplete(),
                owner + ": " + evaluation.reasonCodes());
    }

    private record LegacyBooleanCase(
            String id,
            String field,
            boolean textual) {}

    private static QualificationRecords.RunResult failedResult(
            QualificationRecords.FailureClass failureClass,
            QualificationRecords.AnswerOrigin answerOrigin,
            QualificationRecords.ResponseOwnerContract ownerContract,
            QualificationRecords.CausalOwner owner) {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationRecords.RunResult base = QualificationTestPacket.runResult(
                catalog, catalog.admission().scenarios().getFirst(), 1, false, false);
        List<QualificationRecords.CausalLedgerEntry> ledger;
        if (owner == QualificationRecords.CausalOwner.UNKNOWN) {
            ledger = List.of();
        } else if (owner == QualificationRecords.CausalOwner.MIXED) {
            ledger = List.of(
                    new QualificationRecords.CausalLedgerEntry(
                            1,
                            QualificationRecords.CausalStage.RESPONSE_CONTRACT,
                            QualificationRecords.CausalOwner.RUNTIME_POLICY,
                            QualificationRecords.CausalFact.RESPONSE_OWNER_CONTRACT_VIOLATED,
                            QualificationRecords.CausalReason.RESPONSE_OWNER_CONTRACT_VIOLATED,
                            "trace.json#terminal-answer-origin",
                            true),
                    new QualificationRecords.CausalLedgerEntry(
                            2,
                            QualificationRecords.CausalStage.VERIFICATION,
                            QualificationRecords.CausalOwner.VERIFIER,
                            QualificationRecords.CausalFact.VERIFIER_REJECTION_PROVEN,
                            QualificationRecords.CausalReason
                                    .VERIFIER_DIVERGED_FROM_CANONICAL_REQUIREMENTS,
                            "trace.json#verification",
                            true));
        } else {
            QualificationRecords.CausalReason reason = switch (owner) {
                case MODEL -> QualificationRecords.CausalReason.MODEL_RESPONSE_FAILED_REQUIREMENT;
                case RUNTIME_POLICY -> QualificationRecords.CausalReason
                        .RESPONSE_OWNER_CONTRACT_VIOLATED;
                case VERIFIER -> QualificationRecords.CausalReason
                        .VERIFIER_DIVERGED_FROM_CANONICAL_REQUIREMENTS;
                case PROVIDER -> QualificationRecords.CausalReason.PROVIDER_GENERATION_FAILED;
                case HARNESS -> QualificationRecords.CausalReason.MANDATORY_EVIDENCE_INCOMPLETE;
                case HARDWARE -> QualificationRecords.CausalReason.HARDWARE_RESOURCE_EXHAUSTED;
                case OPERATOR -> QualificationRecords.CausalReason.OPERATOR_INTERRUPTED;
                case MIXED, UNKNOWN -> throw new IllegalStateException();
            };
            ledger = List.of(new QualificationRecords.CausalLedgerEntry(
                    1,
                    QualificationRecords.CausalStage.UTILITY,
                    owner,
                    QualificationRecords.CausalFact.CAUSE_NOT_PROVEN,
                    reason,
                    "trace.json#events",
                    true));
        }
        return base.withFailure(
                failureClass,
                answerOrigin,
                ownerContract,
                owner,
                ledger);
    }

    private Path writeTemp(QualificationRecords.RunResult result) throws Exception {
        Path path = temp.resolve("current-result.json");
        Files.writeString(path, QualificationJson.write(result), StandardCharsets.UTF_8);
        return path;
    }

    private QualificationTestPacket transformedProviderPacket(String name) throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve(name), catalog);
        ObjectNode trace = trace(packet);
        ArrayNode events = trace.withArray("events");
        int terminal = eventIndex(events, "TERMINAL_ANSWER_SELECTED");
        events.insert(terminal, answerTransformation(
                "4", "\n4\n\n", "MODE_PRESENTATION_LAYOUT"));
        events.insert(terminal + 1, answerTransformation(
                "\n4\n\n", "4", "SESSION_COMMIT_NORMALIZATION"));
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);
        return packet;
    }

    private QualificationTestPacket restoredProviderPacket(String name) throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve(name), catalog);
        ObjectNode trace = trace(packet);
        ArrayNode events = trace.withArray("events");
        ObjectNode originalCompletion = event(trace, "GENERATION_COMPLETED");
        String sourceGenerationId = originalCompletion.path("data")
                .path("generationId").asText();
        String sourceAttemptId = originalCompletion.path("data")
                .path("selectedAttemptId").asText();
        String retryGenerationId = sourceGenerationId + "-retry";
        String retryAttemptId = retryGenerationId + "-attempt-1";
        String retryAnswer = "How can I help?";
        String retryHash = answerHash(retryAnswer);
        int terminal = eventIndex(events, "TERMINAL_ANSWER_SELECTED");

        Map<String, Object> generation = Map.of(
                "providerLedgerVersion", 1,
                "ledgerSequence", 6,
                "generationId", retryGenerationId,
                "source", "FOREGROUND_PROVIDER",
                "purpose", "TURN_RESPONSE");
        events.insert(terminal++, traceEvent("GENERATION_STARTED", generation));
        events.insert(terminal++, traceEvent("GENERATION_ATTEMPT_STARTED",
                providerAttemptData(retryGenerationId, retryAttemptId, 7, false)));
        events.insert(terminal++, traceEvent("GENERATION_REQUEST_DISPATCHED",
                providerAttemptData(retryGenerationId, retryAttemptId, 8, true)));
        Map<String, Object> response = new java.util.LinkedHashMap<>(
                providerAttemptData(retryGenerationId, retryAttemptId, 9, true));
        response.put("assistantHash", retryHash);
        response.put("assistantChars", retryAnswer.length());
        response.put("nativeToolCallCount", 0);
        response.put("capturedNativeToolProposalCount", 0);
        response.put("nativeToolProposalsTruncated", false);
        response.put("textualToolCallPresent", false);
        response.put("completion", "PROVIDER_EOS");
        response.put("providerBacked", true);
        response.put("foreground", true);
        response.put("completedNormally", true);
        response.put("normalized", false);
        response.put("truncated", false);
        events.insert(terminal++, traceEvent("GENERATION_RESPONSE_CAPTURED", response));
        Map<String, Object> completed = new java.util.LinkedHashMap<>(generation);
        completed.put("ledgerSequence", 10);
        completed.put("selectedAttemptId", retryAttemptId);
        completed.put("selectedAttemptOrdinal", 1);
        completed.put("completion", "PROVIDER_EOS");
        completed.put("assistantHash", retryHash);
        completed.put("providerTerminalEligible", true);
        completed.put("providerToolCallEligible", false);
        events.insert(terminal++, traceEvent("GENERATION_COMPLETED", completed));
        events.insert(terminal, traceEvent("ANSWER_CANDIDATE_RESTORED", Map.of(
                "answerHash", answerHash("4"),
                "answerChars", 1,
                "origin", "MODEL_PROVIDER",
                "sourceGenerationId", sourceGenerationId,
                "sourceAttemptId", sourceAttemptId,
                "reasonCode", "SPECULATIVE_RETRY_REJECTED")));
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);
        return packet;
    }

    private static Map<String, Object> providerAttemptData(
            String generationId,
            String attemptId,
            int ledgerSequence,
            boolean requestDispatched) {
        return new java.util.LinkedHashMap<>(Map.ofEntries(
                Map.entry("providerLedgerVersion", 1),
                Map.entry("ledgerSequence", ledgerSequence),
                Map.entry("generationId", generationId),
                Map.entry("source", "FOREGROUND_PROVIDER"),
                Map.entry("purpose", "TURN_RESPONSE"),
                Map.entry("attemptId", attemptId),
                Map.entry("attemptOrdinal", 1),
                Map.entry("attemptKind", "PRIMARY"),
                Map.entry("retryReason", "NONE"),
                Map.entry("requestDispatched", requestDispatched)));
    }

    private static ObjectNode traceEvent(String type, Map<String, Object> data) {
        return (ObjectNode) QualificationJson.tree(Map.of(
                "type", type,
                "timestamp", "2026-07-16T00:00:01Z",
                "phase", "",
                "toolName", "",
                "data", data));
    }

    private static QualificationProtocol.Scenario noToolScenario(
            QualificationScenarioBank.Catalog catalog) {
        return java.util.stream.Stream.concat(
                        catalog.admission().scenarios().stream(),
                        catalog.full().scenarios().stream())
                .filter(scenario -> scenario.expectedDecisions().stream().anyMatch(decision ->
                        decision.scored()
                                && decision.kind() == QualificationProtocol.DecisionKind.NO_TOOL))
                .findFirst()
                .orElseThrow();
    }

    private static void markSelectedResponseAsTextualToolBearing(ObjectNode trace) {
        ObjectNode response = event(trace, "GENERATION_RESPONSE_CAPTURED");
        ((ObjectNode) response.path("data")).put("textualToolCallPresent", true);
        ObjectNode completed = event(trace, "GENERATION_COMPLETED");
        ((ObjectNode) completed.path("data")).put("providerToolCallEligible", true);
    }

    private static void insertProviderToolSuppression(
            ObjectNode trace,
            boolean mismatchedCount) {
        ObjectNode completed = event(trace, "GENERATION_COMPLETED");
        String generationId = completed.path("data").path("generationId").asText();
        String attemptId = completed.path("data").path("selectedAttemptId").asText();
        ObjectNode suppression = (ObjectNode) QualificationJson.tree(java.util.Map.of(
                "type", "PROVIDER_TOOL_CALLS_SUPPRESSED",
                "timestamp", "2026-07-16T00:00:01Z",
                "phase", "",
                "toolName", "",
                "data", java.util.Map.of(
                        "sourceGenerationId", generationId,
                        "sourceAttemptId", attemptId,
                        "reasonCode", "TASK_CONTRACT_BLOCKED_TOOL_CALLS",
                        "nativeToolCallCount", mismatchedCount ? 1 : 0,
                        "textualToolCallPresent", true)));
        ArrayNode events = trace.withArray("events");
        events.insert(eventIndex(events, "TERMINAL_ANSWER_SELECTED"), suppression);
    }

    private static ObjectNode answerTransformation(
            String source,
            String target,
            String reason) {
        return (ObjectNode) QualificationJson.tree(java.util.Map.of(
                "type", "ANSWER_TRANSFORMED",
                "timestamp", "2026-07-16T00:00:01Z",
                "phase", "",
                "toolName", "",
                "data", java.util.Map.of(
                        "fromAnswerHash", answerHash(source),
                        "fromAnswerChars", source.length(),
                        "answerHash", answerHash(target),
                        "answerChars", target.length(),
                        "reasonCode", reason)));
    }

    private static String answerHash(String answer) {
        return "sha256:" + QualificationScenarioBank.sha256(
                answer.getBytes(StandardCharsets.UTF_8));
    }

    private static QualificationRecords.AnswerOrigin matchedOrigin(
            QualificationTestPacket packet) throws Exception {
        return QualificationCausalOwnership.matchedAnswerOrigin(
                Files.readAllBytes(packet.runRoot().resolve("answer.txt")),
                Files.readAllBytes(packet.runRoot().resolve("trace.json")),
                QualificationProtocol.VERSION);
    }

    private static ObjectNode trace(QualificationTestPacket packet) throws Exception {
        return (ObjectNode) QualificationJson.readTree(
                packet.runRoot().resolve("trace.json"));
    }

    private static int eventIndex(ArrayNode events, String type) {
        for (int index = 0; index < events.size(); index++) {
            if (type.equals(events.get(index).path("type").asText(""))) return index;
        }
        throw new IllegalArgumentException("missing trace event " + type);
    }

    private static int continuationExit(Path packetRoot, String scenarioId, int repetition) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        return QualificationCampaignContinuationMain.run(
                new String[]{packetRoot.toString(), scenarioId, Integer.toString(repetition)},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private static ObjectNode event(ObjectNode trace, String type) {
        for (var event : trace.withArray("events")) {
            if (type.equals(event.path("type").asText(""))) return (ObjectNode) event;
        }
        throw new IllegalArgumentException("missing trace event " + type);
    }
}
