package dev.talos.qualification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import dev.talos.spi.types.ProviderResponseCapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationTraceEvidenceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir
    Path temp;

    @Test
    void nativeParsedCallRequiresSelectedAttemptAndExactRawProposalIdentity() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.loadDefault()
                .scenario("A05-simple-read-summary");
        Path positive = writeProviderLinkedTrace("provider-linked-positive");

        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(positive, scenario);

        assertEquals("trace-provider-generation-1",
                facts.calls().getFirst().providerGenerationId());
        assertEquals("trace-provider-generation-1-attempt-1",
                facts.calls().getFirst().providerAttemptId());
        assertTrue(facts.calls().getFirst().nativeProviderDecisionBound());
        assertEquals(QualificationTraceEvidence.CallSettlement.EXECUTED_SUCCESS,
                facts.calls().getFirst().settlement());

        Path missingAttempt = writeProviderLinkedTrace("provider-linked-missing-attempt");
        mutateParsedData(missingAttempt, data -> data.remove("providerAttemptId"));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(missingAttempt, scenario));

        Path wrongAttempt = writeProviderLinkedTrace("provider-linked-wrong-attempt");
        mutateParsedData(wrongAttempt, data -> data.put(
                "providerAttemptId", "trace-provider-generation-1-attempt-99"));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(wrongAttempt, scenario));

        Path wrongHash = writeProviderLinkedTrace("provider-linked-wrong-hash");
        mutateParsedData(wrongHash, data -> data.put(
                "providerArgumentsHash", "sha256:" + "f".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(wrongHash, scenario));

        Path wrongOrdinal = writeProviderLinkedTrace("provider-linked-wrong-ordinal");
        mutateParsedData(wrongOrdinal, data -> data.put("providerProposalOrdinal", 2));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(wrongOrdinal, scenario));

        Path changedParsedCall = writeProviderLinkedTrace("provider-linked-changed-call");
        mutateParsedData(changedParsedCall, data -> data.put(
                "argumentsHash", "sha256:" + "9".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(changedParsedCall, scenario));

        Path rawProposalSubstitution = writeProviderLinkedTrace(
                "provider-linked-raw-proposal-substitution");
        mutateParsedEvent(rawProposalSubstitution, event -> {
            String substitutedHash = ProviderResponseCapture.toolNameHash("talos.write_file");
            event.put("toolName", "talos.write_file");
            ObjectNode data = (ObjectNode) event.path("data");
            data.put("providerParsedToolNameHash", substitutedHash);
            data.put("toolNameHash", substitutedHash);
        });
        mutateEvent(rawProposalSubstitution, "TOOL_EXECUTED", event -> {
            String substitutedHash = ProviderResponseCapture.toolNameHash("talos.write_file");
            event.put("toolName", "talos.write_file");
            ((ObjectNode) event.path("data")).put("toolNameHash", substitutedHash);
        });
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(rawProposalSubstitution, scenario));
    }

    @Test
    void schemaThreeRequiresCompleteMatchingSelectedAndAuditedToolSurfaces() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.loadDefault()
                .scenario("A05-simple-read-summary");

        for (String missing : List.of(
                "toolSurface",
                "toolSurface.nativeTools",
                "toolSurface.promptTools",
                "promptAudit.nativeTools",
                "promptAudit.promptTools")) {
            Path trace = writeProviderLinkedTrace(
                    "tool-surface-missing-" + missing.replace('.', '-'));
            ObjectNode root = (ObjectNode) QualificationJson.readTree(trace);
            switch (missing) {
                case "toolSurface" -> root.remove("toolSurface");
                case "toolSurface.nativeTools" ->
                        ((ObjectNode) root.path("toolSurface")).remove("nativeTools");
                case "toolSurface.promptTools" ->
                        ((ObjectNode) root.path("toolSurface")).remove("promptTools");
                case "promptAudit.nativeTools" ->
                        ((ObjectNode) root.path("promptAudit")).remove("nativeTools");
                case "promptAudit.promptTools" ->
                        ((ObjectNode) root.path("promptAudit")).remove("promptTools");
                default -> throw new IllegalStateException("unhandled fixture field " + missing);
            }
            QualificationJson.write(trace, root);
            assertThrows(IllegalArgumentException.class,
                    () -> QualificationTraceEvidence.read(trace, scenario), missing);
        }

        for (String mismatched : List.of("nativeTools", "promptTools")) {
            Path trace = writeProviderLinkedTrace("tool-surface-mismatch-" + mismatched);
            ObjectNode root = (ObjectNode) QualificationJson.readTree(trace);
            ((ArrayNode) root.path("promptAudit").path(mismatched)).add("talos.list_dir");
            QualificationJson.write(trace, root);
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> QualificationTraceEvidence.read(trace, scenario));
            assertTrue(failure.getMessage().contains(
                    "selected tool surface and prompt audit disagree"), failure::getMessage);
        }
    }

    @Test
    void nativeProviderDecisionCannotPrecedeItsSelectedGenerationCompletion() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.loadDefault()
                .scenario("A05-simple-read-summary");
        Path reordered = writeProviderLinkedTrace("provider-linked-reordered");
        ObjectNode root = (ObjectNode) QualificationJson.readTree(reordered);
        ArrayNode events = (ArrayNode) root.path("events");
        int completedIndex = eventIndex(events, "GENERATION_COMPLETED");
        int parsedIndex = eventIndex(events, "TOOL_CALL_PARSED");
        JsonNode parsed = events.remove(parsedIndex);
        events.insert(completedIndex, parsed);
        QualificationJson.write(reordered, root);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(reordered, scenario));

        assertTrue(failure.getMessage().contains("reordered or stale"),
                failure::getMessage);
    }

    @Test
    void unaccountedSelectedProviderToolResponseCannotPassNoToolDecision() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationProtocol.Scenario scenario = java.util.stream.Stream.concat(
                        catalog.admission().scenarios().stream(),
                        catalog.full().scenarios().stream())
                .filter(candidate -> candidate.expectedDecisions().stream().anyMatch(decision ->
                        decision.scored()
                                && decision.kind()
                                        == QualificationProtocol.DecisionKind.NO_TOOL))
                .findFirst()
                .orElseThrow();
        Path trace = writeProviderLinkedTrace("unaccounted-provider-tool-response");
        ObjectNode root = (ObjectNode) QualificationJson.readTree(trace);
        ArrayNode events = (ArrayNode) root.path("events");
        for (int index = events.size() - 1; index >= 0; index--) {
            String type = events.get(index).path("type").asText("");
            if ("TOOL_CALL_PARSED".equals(type) || "TOOL_EXECUTED".equals(type)) {
                events.remove(index);
            }
        }
        QualificationJson.write(trace, root);

        QualificationTraceEvidence.Facts facts =
                QualificationTraceEvidence.read(trace, scenario);

        assertEquals(List.of(new QualificationRecords.DecisionResult(
                        scenario.expectedDecisions().stream()
                                .filter(QualificationProtocol.ExpectedDecision::scored)
                                .findFirst().orElseThrow().id(),
                        false)),
                facts.decisions());
    }

    @Test
    void schemaThreeTransformationPreservesOriginalDecisionAndUsesOnlyEffectiveSourcePath()
            throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.loadDefault()
                .scenario("A05-simple-read-summary");
        Path trace = writeTransformedProviderTrace(
                "provider-linked-transformed",
                "talos.edit_file",
                "effective.txt",
                true);

        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);
        QualificationTraceEvidence.Call call = facts.calls().getFirst();

        assertEquals("talos.read_file", call.decisionToolName());
        assertEquals("talos.edit_file", call.toolName());
        assertTrue(call.runtimeTransformed());
        assertTrue(call.nativeProviderDecisionBound());
        assertEquals(List.of("effective.txt"), call.sourcePaths());
        assertEquals(List.of(new QualificationRecords.DecisionResult("read-readme", true)),
                facts.decisions());
    }

    @Test
    void schemaThreeTransformationRejectsForgedSourceAndPostApprovalRewrite() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.loadDefault()
                .scenario("A05-simple-read-summary");
        Path forged = writeTransformedProviderTrace(
                "provider-linked-forged-transform",
                "talos.edit_file",
                "effective.txt",
                true);
        mutateEventData(forged, "TOOL_CALL_TRANSFORMED", data -> data.put(
                "fromArgumentsHash", "sha256:" + "9".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(forged, scenario));

        Path afterApproval = writeTransformedProviderTrace(
                "provider-linked-post-approval-transform",
                "talos.edit_file",
                "effective.txt",
                true);
        ObjectNode root = (ObjectNode) QualificationJson.readTree(afterApproval);
        ArrayNode events = (ArrayNode) root.path("events");
        int parsedIndex = eventIndex(events, "TOOL_CALL_PARSED");
        ObjectNode parsedData = (ObjectNode) events.get(parsedIndex).path("data");
        Map<String, Object> approvalData = Map.ofEntries(
                Map.entry("toolCallId", parsedData.path("toolCallId").textValue()),
                Map.entry("toolNameHash", parsedData.path("toolNameHash").textValue()),
                Map.entry("argumentsHash", parsedData.path("argumentsHash").textValue()));
        events.insert(eventIndex(events, "TOOL_CALL_TRANSFORMED"), MAPPER.valueToTree(
                event("APPROVAL_GRANTED", "talos.read_file", "README.md", approvalData)));
        QualificationJson.write(afterApproval, root);
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(afterApproval, scenario));
    }

    @Test
    void answerTransformationIsCausalOnlyWhenTheFinalBytesDifferFromTheSource()
            throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.loadDefault()
                .scenario("A05-simple-read-summary");
        String source = "sha256:" + "1".repeat(64);
        String wrapper = "sha256:" + "2".repeat(64);

        Path roundTrip = writeProviderLinkedTrace("answer-transform-round-trip");
        ObjectNode roundTripRoot = (ObjectNode) QualificationJson.readTree(roundTrip);
        ArrayNode roundTripEvents = (ArrayNode) roundTripRoot.path("events");
        roundTripEvents.add(MAPPER.valueToTree(answerTransformation(
                source, 1, wrapper, 3, "MODE_PRESENTATION_LAYOUT")));
        roundTripEvents.add(MAPPER.valueToTree(answerTransformation(
                wrapper, 3, source, 1, "SESSION_COMMIT_NORMALIZATION")));
        QualificationJson.write(roundTrip, roundTripRoot);
        assertFalse(QualificationTraceEvidence.read(roundTrip, scenario)
                .runtimeAnswerTransformed(),
                "a mechanically proven round trip must not manufacture runtime causality");

        Path semanticDelta = writeProviderLinkedTrace("answer-transform-semantic-delta");
        ObjectNode deltaRoot = (ObjectNode) QualificationJson.readTree(semanticDelta);
        ((ArrayNode) deltaRoot.path("events")).add(MAPPER.valueToTree(answerTransformation(
                source, 1, wrapper, 3, "EXECUTOR_POLICY_SHAPING")));
        QualificationJson.write(semanticDelta, deltaRoot);
        assertTrue(QualificationTraceEvidence.read(semanticDelta, scenario)
                .runtimeAnswerTransformed());
    }

    @Test
    void schemaThreeDistinguishesExecutedFailureFromPreExecutionBlock() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.loadDefault()
                .scenario("A05-simple-read-summary");
        Path failed = writeProviderLinkedTrace("provider-linked-executed-failure");
        mutateEventData(failed, "TOOL_EXECUTED", data -> data.put("success", false));
        QualificationTraceEvidence.Call failedCall =
                QualificationTraceEvidence.read(failed, scenario).calls().getFirst();
        assertEquals(QualificationTraceEvidence.CallSettlement.EXECUTED_FAILURE,
                failedCall.settlement());
        assertTrue(failedCall.sourcePaths().isEmpty());

        Path blocked = writeProviderLinkedTrace("provider-linked-blocked");
        ObjectNode root = (ObjectNode) QualificationJson.readTree(blocked);
        ArrayNode events = (ArrayNode) root.path("events");
        ObjectNode terminal = (ObjectNode) events.get(eventIndex(events, "TOOL_EXECUTED"));
        terminal.put("type", "TOOL_CALL_BLOCKED");
        ObjectNode data = (ObjectNode) terminal.path("data");
        data.remove("success");
        data.put("reason", "POLICY_BLOCK");
        QualificationJson.write(blocked, root);
        QualificationTraceEvidence.Call blockedCall =
                QualificationTraceEvidence.read(blocked, scenario).calls().getFirst();
        assertEquals(QualificationTraceEvidence.CallSettlement.BLOCKED,
                blockedCall.settlement());
        assertTrue(blockedCall.sourcePaths().isEmpty());
    }

    @Test
    void scoreBearingTraceFieldsRequireTheirExactJsonTypes() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.loadDefault()
                .scenario("A05-simple-read-summary");

        Path numericTraceId = writeProviderLinkedTrace("numeric-trace-id");
        ObjectNode traceIdRoot = (ObjectNode) QualificationJson.readTree(numericTraceId);
        traceIdRoot.put("traceId", 7);
        QualificationJson.write(numericTraceId, traceIdRoot);
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(numericTraceId, scenario));

        Path textualSuccess = writeProviderLinkedTrace("textual-tool-success");
        mutateEventData(textualSuccess, "TOOL_EXECUTED",
                data -> data.put("success", "true"));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(textualSuccess, scenario));

        Path numericOutcome = writeProviderLinkedTrace("numeric-outcome-status");
        ObjectNode outcomeRoot = (ObjectNode) QualificationJson.readTree(numericOutcome);
        ((ObjectNode) outcomeRoot.path("outcome")).put("status", 1);
        QualificationJson.write(numericOutcome, outcomeRoot);
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(numericOutcome, scenario));

        Path numericSourcePath = writeProviderLinkedTrace("numeric-source-path");
        mutateEventData(numericSourcePath, "TOOL_EXECUTED",
                data -> data.putArray("pathHints").add(1));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(numericSourcePath, scenario));
    }

    @Test
    void readFirstIsAnAllowedAuxiliaryBeforeTheRequiredMutationDecision() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.loadDefault()
                .scenario("A08-technical-exact-edit");
        Path trace = writeTrace(List.of(
                parsed("talos.read_file", "notes.md"),
                executed("talos.read_file", "notes.md"),
                parsed("talos.edit_file", "notes.md"),
                approval("APPROVAL_GRANTED", "talos.edit_file", "notes.md"),
                executed("talos.edit_file", "notes.md")));

        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);

        assertEquals(List.of(new QualificationRecords.DecisionResult("edit-status", true)), facts.decisions());
        assertEquals(0, facts.unnecessaryExecutedCalls());
        assertTrue(facts.calls().stream().allMatch(QualificationTraceEvidence.Call::allowed));
    }

    @Test
    void aWrongMutationToolIsNotRewrittenByALaterCorrectTool() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.loadDefault()
                .scenario("A08-technical-exact-edit");
        Path trace = writeTrace(List.of(
                parsed("talos.write_file", "notes.md"),
                executed("talos.write_file", "notes.md"),
                parsed("talos.edit_file", "notes.md"),
                executed("talos.edit_file", "notes.md")));

        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);

        assertEquals(List.of(new QualificationRecords.DecisionResult("edit-status", false)), facts.decisions());
        assertEquals(1, facts.unnecessaryExecutedCalls());
    }

    @Test
    void deniedProposalStillCountsAsTheRequiredToolDecision() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.loadDefault()
                .scenario("F09-contradictory-deny-after-proposal");
        Path trace = writeTrace(List.of(
                parsed("talos.write_file", "decision.txt"),
                approval("APPROVAL_DENIED", "talos.write_file", "decision.txt")));

        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);

        assertEquals(List.of(new QualificationRecords.DecisionResult("propose-create", true)), facts.decisions());
        assertFalse(facts.calls().getFirst().executed());
        assertTrue(facts.approvalDenied());
    }

    @Test
    void approveOnceBindsGrantAndDenialToTheirOwnMutationCalls() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.load(5)
                .scenario("F10-superuser-multi-requirement-web-edit");
        Path trace = writeTrace(List.of(
                parsed("talos.read_file", "index.html"),
                executed("talos.read_file", "index.html"),
                parsed("talos.edit_file", "index.html"),
                approval("APPROVAL_GRANTED", "talos.edit_file", "index.html"),
                executed("talos.edit_file", "index.html"),
                parsed("talos.edit_file", "index.html"),
                approval("APPROVAL_DENIED", "talos.edit_file", "index.html"),
                blocked("talos.edit_file", "index.html")));

        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);

        assertEquals(QualificationTraceEvidence.ApprovalDisposition.GRANTED,
                facts.calls().get(1).approvalDisposition());
        assertEquals(QualificationTraceEvidence.ApprovalDisposition.DENIED,
                facts.calls().get(2).approvalDisposition());
        assertTrue(QualificationEvidenceValidator.approvalIntegrityPasses(scenario, facts));
    }

    @Test
    void approveOnceRejectsExecutionWithoutTheSameCallsGrant() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.load(5)
                .scenario("F10-superuser-multi-requirement-web-edit");
        Path trace = writeTrace(List.of(
                parsed("talos.edit_file", "index.html"),
                executed("talos.edit_file", "index.html")));

        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);

        assertFalse(QualificationEvidenceValidator.approvalIntegrityPasses(scenario, facts));
    }

    @Test
    void everyWorkspaceMutationToolRequiresItsOwnApproveOnceGrant() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.load(5)
                .scenario("F10-superuser-multi-requirement-web-edit");

        for (String tool : List.of("talos.mkdir", "talos.move_path", "talos.copy_path")) {
            Path trace = writeTrace(List.of(
                    parsed(tool, "target"),
                    executed(tool, "target")));
            QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);
            assertFalse(QualificationEvidenceValidator.approvalIntegrityPasses(scenario, facts), tool);
        }
    }

    @Test
    void deniedMutationCannotExecuteEvenWhenAnEarlierMutationWasGranted() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.load(5)
                .scenario("F10-superuser-multi-requirement-web-edit");
        Path trace = writeTrace(List.of(
                parsed("talos.edit_file", "index.html"),
                approval("APPROVAL_GRANTED", "talos.edit_file", "index.html"),
                executed("talos.edit_file", "index.html"),
                parsed("talos.edit_file", "index.html"),
                approval("APPROVAL_DENIED", "talos.edit_file", "index.html"),
                executed("talos.edit_file", "index.html")));

        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);

        assertFalse(QualificationEvidenceValidator.approvalIntegrityPasses(scenario, facts));
    }

    @Test
    void denyBehaviorRejectsAnyExecutedMutation() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.load(5)
                .scenario("F09-contradictory-deny-after-proposal");
        Path trace = writeTrace(List.of(
                parsed("talos.write_file", "decision.txt"),
                approval("APPROVAL_DENIED", "talos.write_file", "decision.txt"),
                executed("talos.write_file", "decision.txt")));

        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);

        assertFalse(QualificationEvidenceValidator.approvalIntegrityPasses(scenario, facts));
    }

    @Test
    void noneAndInterruptionBehaviorsRejectAnyApprovalEvent() throws Exception {
        QualificationProtocol.Scenario base = QualificationScenarioBank.load(5)
                .scenario("F10-superuser-multi-requirement-web-edit");
        Path trace = writeTrace(List.of(event(
                "COMMAND_APPROVAL_GRANTED", "", "", Map.of())));

        for (QualificationProtocol.ApprovalBehavior behavior : List.of(
                QualificationProtocol.ApprovalBehavior.NONE,
                QualificationProtocol.ApprovalBehavior.INTERRUPT_BEFORE_APPROVAL)) {
            QualificationProtocol.Scenario scenario = withApprovalBehavior(base, behavior);
            QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);
            assertFalse(QualificationEvidenceValidator.approvalIntegrityPasses(scenario, facts),
                    behavior.name());
        }
    }

    @Test
    void sessionGrantCoversOnlyLaterCallsAndNeverOverridesADenial() throws Exception {
        QualificationProtocol.Scenario approveOnce = QualificationScenarioBank.load(5)
                .scenario("F10-superuser-multi-requirement-web-edit");
        QualificationProtocol.Scenario sessionAllow = withApprovalBehavior(
                approveOnce, QualificationProtocol.ApprovalBehavior.SESSION_ALLOW);
        Path allowed = writeTrace(List.of(
                parsed("talos.edit_file", "index.html"),
                approval("APPROVAL_GRANTED", "talos.edit_file", "index.html"),
                executed("talos.edit_file", "index.html"),
                parsed("talos.edit_file", "index.html"),
                executed("talos.edit_file", "index.html")));
        Path denied = writeTrace(List.of(
                parsed("talos.edit_file", "index.html"),
                approval("APPROVAL_GRANTED", "talos.edit_file", "index.html"),
                executed("talos.edit_file", "index.html"),
                parsed("talos.edit_file", "index.html"),
                approval("APPROVAL_DENIED", "talos.edit_file", "index.html"),
                executed("talos.edit_file", "index.html")));

        assertTrue(QualificationEvidenceValidator.approvalIntegrityPasses(
                sessionAllow, QualificationTraceEvidence.read(allowed, sessionAllow)));
        assertFalse(QualificationEvidenceValidator.approvalIntegrityPasses(
                sessionAllow, QualificationTraceEvidence.read(denied, sessionAllow)));
    }

    @Test
    void approvalDecisionMustMatchAnUnsettledParsedCall() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.load(5)
                .scenario("F10-superuser-multi-requirement-web-edit");
        Path wrongPath = writeTrace(List.of(
                parsed("talos.edit_file", "index.html"),
                approval("APPROVAL_GRANTED", "talos.edit_file", "other.html")));
        Path orphan = writeTrace(List.of(
                approval("APPROVAL_GRANTED", "talos.edit_file", "index.html")));

        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(wrongPath, scenario));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(orphan, scenario));
    }

    @Test
    void pathTransformingExecutionBindsToTheOnlyUnmatchedParsedCall() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.loadDefault()
                .scenario("F08-contradictory-similar-file-names");
        for (String tool : List.of("talos.rename_path", "talos.move_path", "talos.copy_path")) {
            Path trace = writeTrace(List.of(
                    parsed(tool, "source.txt"),
                    executed(tool, "destination.txt")));

            QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);

            assertEquals(1, facts.calls().size(), tool);
            assertEquals("source.txt", facts.calls().getFirst().pathHint(), tool);
            assertTrue(facts.calls().getFirst().executed(), tool);
        }
    }

    @Test
    void acceptedAliasAndCanonicalExecutionShareOneToolIdentity() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.loadDefault()
                .scenario("F08-contradictory-similar-file-names");
        Path trace = writeTrace(List.of(
                parsed("rename", "source.txt"),
                executed("talos.rename_path", "destination.txt")));

        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);

        assertEquals("talos.rename_path", facts.calls().getFirst().toolName());
        assertTrue(facts.calls().getFirst().executed());
    }

    @Test
    void uniqueExactPathWinsWhenMultipleSameToolCallsAreUnmatched() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.loadDefault()
                .scenario("F08-contradictory-similar-file-names");
        Path trace = writeTrace(List.of(
                parsed("talos.rename_path", "first.txt"),
                parsed("talos.rename_path", "second.txt"),
                executed("talos.rename_path", "second.txt")));

        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);

        assertFalse(facts.calls().get(0).executed());
        assertTrue(facts.calls().get(1).executed());
    }

    @Test
    void blockedAttemptSettlesBeforeSuccessfulSameToolRetry() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.loadDefault()
                .scenario("A06-technical-find-port");
        Path trace = writeTrace(List.of(
                parsed("talos.grep", ""),
                blocked("talos.grep", ""),
                parsed("talos.grep", ""),
                executed("talos.grep", "")));

        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);

        assertEquals(2, facts.calls().size());
        assertFalse(facts.calls().get(0).executed());
        assertTrue(facts.calls().get(1).executed());
    }

    @Test
    void failedExecutionSettlesBeforeSuccessfulSameToolRetry() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.loadDefault()
                .scenario("A06-technical-find-port");
        Path trace = writeTrace(List.of(
                parsed("talos.grep", ""),
                failed("talos.grep", ""),
                parsed("talos.grep", ""),
                executed("talos.grep", "")));

        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);

        assertEquals(2, facts.calls().size());
        assertFalse(facts.calls().get(0).executed());
        assertTrue(facts.calls().get(1).executed());
    }

    @Test
    void queryToolExecutionKeepsEveryStructuredEvidenceSource() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.load(5)
                .scenario("F04-technical-trace-port");
        Path trace = writeTrace(List.of(
                parsed("talos.retrieve", ""),
                event(
                        "TOOL_EXECUTED",
                        "talos.retrieve",
                        "config.json",
                        Map.of(
                                "success", true,
                                "pathHints", List.of("config.json", "docs/operations.md")))));

        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);

        assertEquals(
                List.of("config.json", "docs/operations.md"),
                facts.calls().getFirst().sourcePaths());
        assertFalse(facts.calls().getFirst().allowed(),
                "source provenance must not rewrite preferred-tool scoring");
    }

    @Test
    void runtimeBoundedNegativeDisclosureIsParsedAsTypedTraceFact() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.load(5)
                .scenario("F04-technical-trace-port");
        Path trace = writeTrace(List.of(Map.of(
                "type", "PROTOCOL_SANITIZED",
                "timestamp", "2026-07-10T00:00:00Z",
                "phase", "",
                "toolName", "",
                "data", Map.of(
                        "reason", "file-content lookup negative claim bounded to source evidence"))));

        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);
        var method = facts.getClass().getDeclaredMethod("boundedFileLookupNegative");
        method.setAccessible(true);

        assertEquals(Boolean.TRUE, method.invoke(facts));
    }

    @Test
    void runtimeMissingEvidenceContainmentRequiresEventAndWarningTogether() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.load(5)
                .scenario("F04-technical-trace-port");
        Map<String, Object> containment = Map.of(
                "type", "PROTOCOL_SANITIZED",
                "timestamp", "2026-07-13T00:00:00Z",
                "phase", "",
                "toolName", "",
                "data", Map.of(
                        "reason", "missing workspace evidence claim contained by runtime"));
        Path complete = writeTrace(
                List.of(containment),
                List.of(Map.of("code", "MISSING_EVIDENCE", "message", "Missing evidence.")));
        Path eventOnly = writeTrace(List.of(containment), List.of());
        Path warningOnly = writeTrace(
                List.of(),
                List.of(Map.of("code", "MISSING_EVIDENCE", "message", "Missing evidence.")));

        assertTrue(QualificationTraceEvidence.read(complete, scenario).missingEvidenceContained());
        assertFalse(QualificationTraceEvidence.read(eventOnly, scenario).missingEvidenceContained());
        assertFalse(QualificationTraceEvidence.read(warningOnly, scenario).missingEvidenceContained());
    }

    @Test
    void structuredGenerationAbortIsParsedOnceWithCanonicalReasonAndPartialFlag() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.load(5)
                .scenario("F01-simple-read-project");
        Map<String, Object> abort = Map.of(
                "type", "MODEL_GENERATION_ABORTED",
                "timestamp", "2026-07-14T00:00:00Z",
                "phase", "",
                "toolName", "",
                "data", Map.of(
                        "reasonCode", "REPETITION_LOOP",
                        "partialOutput", false));
        Path trace = writeTrace(List.of(abort));

        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);
        var reason = facts.getClass().getDeclaredMethod("generationAbortReasonCode");
        var partial = facts.getClass().getDeclaredMethod("generationAbortPartialOutput");

        assertEquals("REPETITION_LOOP", reason.invoke(facts));
        assertEquals(Boolean.FALSE, partial.invoke(facts));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(writeTrace(List.of(abort, abort)), scenario));
        Map<String, Object> invalidReason = new java.util.LinkedHashMap<>(abort);
        invalidReason.put("data", Map.of(
                "reasonCode", "RAW_PROVIDER_DETAIL",
                "partialOutput", false));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(writeTrace(List.of(invalidReason)), scenario));
        Map<String, Object> missingPartial = new java.util.LinkedHashMap<>(abort);
        missingPartial.put("data", Map.of("reasonCode", "REPETITION_LOOP"));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(writeTrace(List.of(missingPartial)), scenario));
    }

    @Test
    void ambiguousOrOrphanSuccessfulExecutionsRemainInvalid() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.loadDefault()
                .scenario("F08-contradictory-similar-file-names");
        Path ambiguous = writeTrace(List.of(
                parsed("talos.rename_path", "first.txt"),
                parsed("talos.rename_path", "second.txt"),
                executed("talos.rename_path", "destination.txt")));
        Path orphan = writeTrace(List.of(
                executed("talos.rename_path", "destination.txt")));
        Path orphanBlock = writeTrace(List.of(
                blocked("talos.rename_path", "destination.txt")));

        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(ambiguous, scenario));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(orphan, scenario));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(orphanBlock, scenario));
    }

    private Path writeTrace(List<Map<String, Object>> events) throws Exception {
        return writeTrace(events, List.of());
    }

    private Path writeProviderLinkedTrace(String name) throws Exception {
        String generationId = "trace-provider-generation-1";
        String attemptId = generationId + "-attempt-1";
        String answerHash = "sha256:" + "a".repeat(64);
        String toolHash = ProviderResponseCapture.toolNameHash("talos.read_file");
        String argumentsHash = "sha256:" + "c".repeat(64);
        String parsedArgumentsHash = "sha256:" + "d".repeat(64);
        List<Map<String, Object>> events = new java.util.ArrayList<>();

        Map<String, Object> generation = new LinkedHashMap<>(Map.ofEntries(
                Map.entry("providerLedgerVersion", 1),
                Map.entry("ledgerSequence", 1),
                Map.entry("generationId", generationId),
                Map.entry("source", "FOREGROUND_PROVIDER"),
                Map.entry("purpose", "TURN_RESPONSE")));
        events.add(physicalEvent("GENERATION_STARTED", generation));

        Map<String, Object> attempt = providerAttemptData(
                generationId, attemptId, 2, false);
        events.add(physicalEvent("GENERATION_ATTEMPT_STARTED", attempt));
        Map<String, Object> dispatched = providerAttemptData(
                generationId, attemptId, 3, true);
        dispatched.put("providerBodyHash", "sha256:" + "7".repeat(64));
        dispatched.put("dispatchedNativeTools", List.of("talos.read_file"));
        events.add(physicalEvent("GENERATION_REQUEST_DISPATCHED", dispatched));

        Map<String, Object> response = providerAttemptData(
                generationId, attemptId, 4, true);
        response.put("assistantHash", answerHash);
        response.put("assistantChars", 0);
        response.put("nativeToolCallCount", 1);
        response.put("capturedNativeToolProposalCount", 1);
        response.put("nativeToolProposalsTruncated", false);
        response.put("textualToolCallPresent", false);
        response.put("completion", "PROVIDER_EOS");
        response.put("providerBacked", true);
        response.put("foreground", true);
        response.put("completedNormally", true);
        response.put("normalized", false);
        response.put("truncated", false);
        events.add(physicalEvent("GENERATION_RESPONSE_CAPTURED", response));

        Map<String, Object> proposal = providerAttemptData(
                generationId, attemptId, 4, true);
        proposal.put("proposalOrdinal", 1);
        proposal.put("providerCallIdHash", "sha256:" + "d".repeat(64));
        proposal.put("toolNameClassification", "CANONICAL");
        proposal.put("toolNameHash", toolHash);
        proposal.put("toolNameChars", "talos.read_file".length());
        proposal.put("argumentNameHashes", List.of("sha256:" + "e".repeat(64)));
        proposal.put("argumentCount", 1);
        proposal.put("argumentNameHashesTruncated", false);
        proposal.put("argumentsHash", argumentsHash);
        events.add(physicalEvent("PROVIDER_TOOL_PROPOSAL_CAPTURED", proposal));

        Map<String, Object> completed = new LinkedHashMap<>(generation);
        completed.put("ledgerSequence", 5);
        completed.put("selectedAttemptId", attemptId);
        completed.put("selectedAttemptOrdinal", 1);
        completed.put("completion", "PROVIDER_EOS");
        completed.put("assistantHash", answerHash);
        completed.put("providerTerminalEligible", true);
        completed.put("providerToolCallEligible", true);
        events.add(physicalEvent("GENERATION_COMPLETED", completed));

        events.add(event("TOOL_CALL_PARSED", "talos.read_file", "README.md", Map.ofEntries(
                Map.entry("toolCallId", "call-1-1"),
                Map.entry("providerGenerationId", generationId),
                Map.entry("providerAttemptId", attemptId),
                Map.entry("providerProposalOrdinal", 1),
                Map.entry("providerToolNameHash", toolHash),
                Map.entry("providerArgumentsHash", argumentsHash),
                Map.entry("providerParsedToolNameHash", toolHash),
                Map.entry("providerParsedArgumentsHash", parsedArgumentsHash),
                Map.entry("toolNameHash", toolHash),
                Map.entry("argumentsHash", parsedArgumentsHash))));
        events.add(event("TOOL_EXECUTED", "talos.read_file", "README.md", Map.ofEntries(
                Map.entry("toolCallId", "call-1-1"),
                Map.entry("toolNameHash", toolHash),
                Map.entry("argumentsHash", parsedArgumentsHash),
                Map.entry("success", true))));

        Path trace = temp.resolve(name + ".json");
        QualificationJson.write(trace, Map.ofEntries(
                Map.entry("schemaVersion", 3),
                Map.entry("traceId", name),
                Map.entry("model", Map.of("backend", "llama_cpp", "model", "qwen14-runtime")),
                Map.entry("taskContract", Map.of(
                        "type", "READ_ONLY_QA", "mutationAllowed", false,
                        "verificationRequired", false)),
                Map.entry("toolSurface", Map.of(
                        "nativeTools", List.of("talos.read_file"),
                        "promptTools", List.of("talos.read_file"),
                        "reason", "selected for provider-linked fixture")),
                Map.entry("promptAudit", Map.of(
                        "taskType", "READ_ONLY_QA",
                        "mutationAllowed", false,
                        "actionObligation", "NONE",
                        "nativeTools", List.of("talos.read_file"),
                        "promptTools", List.of("talos.read_file"))),
                Map.entry("events", events),
                Map.entry("warnings", List.of()),
                Map.entry("verification", Map.of("status", "NOT_RUN")),
                Map.entry("outcome", Map.of(
                        "status", "COMPLETE", "classification", "READ_ONLY_ANSWERED"))));
        return trace;
    }

    private Path writeTransformedProviderTrace(
            String name,
            String effectiveTool,
            String effectivePath,
            boolean success) throws Exception {
        Path trace = writeProviderLinkedTrace(name);
        ObjectNode root = (ObjectNode) QualificationJson.readTree(trace);
        ArrayNode events = (ArrayNode) root.path("events");
        int parsedIndex = eventIndex(events, "TOOL_CALL_PARSED");
        int executedIndex = eventIndex(events, "TOOL_EXECUTED");
        ObjectNode parsedData = (ObjectNode) events.get(parsedIndex).path("data");
        String toolCallId = parsedData.path("toolCallId").textValue();
        String fromToolNameHash = parsedData.path("toolNameHash").textValue();
        String fromArgumentsHash = parsedData.path("argumentsHash").textValue();
        String effectiveToolHash = ProviderResponseCapture.toolNameHash(effectiveTool);
        String effectiveArgumentsHash = ProviderResponseCapture.canonicalArgumentsHash(
                Map.of("path", effectivePath));
        Map<String, Object> transformData = Map.ofEntries(
                Map.entry("toolCallId", toolCallId),
                Map.entry("reasonCode", "PATH_ARGUMENT_NORMALIZATION"),
                Map.entry("fromToolNameHash", fromToolNameHash),
                Map.entry("fromArgumentsHash", fromArgumentsHash),
                Map.entry("toolNameHash", effectiveToolHash),
                Map.entry("argumentsHash", effectiveArgumentsHash));
        events.insert(executedIndex, MAPPER.valueToTree(event(
                "TOOL_CALL_TRANSFORMED",
                effectiveTool,
                effectivePath,
                transformData)));
        ObjectNode executed = (ObjectNode) events.get(executedIndex + 1);
        executed.put("toolName", effectiveTool);
        ObjectNode executedData = (ObjectNode) executed.path("data");
        executedData.put("pathHint", effectivePath);
        executedData.put("toolNameHash", effectiveToolHash);
        executedData.put("argumentsHash", effectiveArgumentsHash);
        executedData.put("success", success);
        QualificationJson.write(trace, root);
        return trace;
    }

    private static Map<String, Object> providerAttemptData(
            String generationId,
            String attemptId,
            int sequence,
            boolean dispatched) {
        return new LinkedHashMap<>(Map.ofEntries(
                Map.entry("providerLedgerVersion", 1),
                Map.entry("ledgerSequence", sequence),
                Map.entry("generationId", generationId),
                Map.entry("source", "FOREGROUND_PROVIDER"),
                Map.entry("purpose", "TURN_RESPONSE"),
                Map.entry("attemptId", attemptId),
                Map.entry("attemptOrdinal", 1),
                Map.entry("attemptKind", "PRIMARY"),
                Map.entry("retryReason", "NONE"),
                Map.entry("requestDispatched", dispatched)));
    }

    private static Map<String, Object> physicalEvent(
            String type,
            Map<String, Object> data) {
        return Map.of("type", type, "data", data);
    }

    private static Map<String, Object> answerTransformation(
            String fromHash,
            int fromChars,
            String answerHash,
            int answerChars,
            String reasonCode) {
        return physicalEvent("ANSWER_TRANSFORMED", Map.of(
                "fromAnswerHash", fromHash,
                "fromAnswerChars", fromChars,
                "answerHash", answerHash,
                "answerChars", answerChars,
                "reasonCode", reasonCode));
    }

    private static void mutateParsedData(
            Path trace,
            Consumer<ObjectNode> mutation) throws Exception {
        mutateEventData(trace, "TOOL_CALL_PARSED", mutation);
    }

    private static void mutateParsedEvent(
            Path trace,
            Consumer<ObjectNode> mutation) throws Exception {
        mutateEvent(trace, "TOOL_CALL_PARSED", mutation);
    }

    private static void mutateEvent(
            Path trace,
            String eventType,
            Consumer<ObjectNode> mutation) throws Exception {
        ObjectNode root = (ObjectNode) QualificationJson.readTree(trace);
        ObjectNode match = null;
        for (var event : root.path("events")) {
            if (event.path("type").isTextual()
                    && eventType.equals(event.path("type").textValue())) {
                match = (ObjectNode) event;
                break;
            }
        }
        if (match == null) throw new IllegalStateException(eventType + " event not found");
        mutation.accept(match);
        QualificationJson.write(trace, root);
    }

    private static void mutateEventData(
            Path trace,
            String eventType,
            Consumer<ObjectNode> mutation) throws Exception {
        ObjectNode root = (ObjectNode) QualificationJson.readTree(trace);
        ObjectNode data = null;
        for (var event : root.path("events")) {
            if (event.path("type").isTextual()
                    && eventType.equals(event.path("type").textValue())) {
                data = (ObjectNode) event.path("data");
                break;
            }
        }
        if (data == null) throw new IllegalStateException(eventType + " event not found");
        mutation.accept(data);
        QualificationJson.write(trace, root);
    }

    private static int eventIndex(ArrayNode events, String eventType) {
        for (int index = 0; index < events.size(); index++) {
            JsonNode type = events.get(index).path("type");
            if (type.isTextual() && eventType.equals(type.textValue())) return index;
        }
        throw new IllegalStateException(eventType + " event not found");
    }

    private Path writeTrace(
            List<Map<String, Object>> events,
            List<Map<String, Object>> warnings) throws Exception {
        Path trace = temp.resolve("trace-" + System.nanoTime() + ".json");
        QualificationJson.write(trace, Map.ofEntries(
                Map.entry("schemaVersion", 2),
                Map.entry("traceId", "trace-test"),
                Map.entry("model", Map.of("backend", "llama_cpp", "model", "qwen14-runtime")),
                Map.entry("taskContract", Map.of(
                        "type", "FILE_EDIT", "mutationAllowed", true, "verificationRequired", true)),
                Map.entry("promptAudit", Map.of(
                        "taskType", "FILE_EDIT",
                        "mutationAllowed", true,
                        "actionObligation", "MUTATING_TOOL_REQUIRED")),
                Map.entry("events", events),
                Map.entry("warnings", warnings),
                Map.entry("verification", Map.of("status", "PASSED")),
                Map.entry("outcome", Map.of(
                        "status", "COMPLETE", "classification", "COMPLETED_VERIFIED"))));
        return trace;
    }

    private static Map<String, Object> parsed(String tool, String path) {
        return event("TOOL_CALL_PARSED", tool, path, Map.of());
    }

    private static Map<String, Object> executed(String tool, String path) {
        return event("TOOL_EXECUTED", tool, path, Map.of("success", true));
    }

    private static Map<String, Object> failed(String tool, String path) {
        return event("TOOL_EXECUTED", tool, path, Map.of("success", false));
    }

    private static Map<String, Object> blocked(String tool, String path) {
        return event("TOOL_CALL_BLOCKED", tool, path, Map.of("reason", "INVALID_PARAMS"));
    }

    private static Map<String, Object> approval(String type, String tool, String path) {
        return event(type, tool, path, Map.of());
    }

    private static Map<String, Object> event(
            String type,
            String tool,
            String path,
            Map<String, Object> extra) {
        java.util.LinkedHashMap<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("pathHint", path);
        data.putAll(extra);
        return Map.of(
                "type", type,
                "timestamp", "2026-07-10T00:00:00Z",
                "phase", "APPLY",
                "toolName", tool,
                "data", data);
    }

    private static QualificationProtocol.Scenario withApprovalBehavior(
            QualificationProtocol.Scenario scenario,
            QualificationProtocol.ApprovalBehavior approvalBehavior) {
        return new QualificationProtocol.Scenario(
                scenario.id(),
                scenario.bank(),
                scenario.archetype(),
                scenario.category(),
                scenario.fixture(),
                scenario.fixtureSha256(),
                scenario.prompt(),
                approvalBehavior,
                scenario.responseOwnerContract(),
                scenario.expectedTerminalStates(),
                scenario.allowedTargets(),
                scenario.forbiddenTargets(),
                scenario.expectedDecisions(),
                scenario.atomicRequirements());
    }
}
