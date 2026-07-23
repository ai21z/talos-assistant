package dev.talos.qualification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.runtime.TurnExecutionStage;
import dev.talos.runtime.TurnFailureClassifier;
import dev.talos.runtime.TurnSettlement;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.TurnEvidenceProjectionTraceEvent;
import dev.talos.runtime.trace.TurnSettlementTraceEvent;
import dev.talos.runtime.trace.TurnTraceEvent;
import dev.talos.spi.types.ModelIdentitySnapshot;
import dev.talos.spi.types.ProviderProcessLaunchReceipt;
import dev.talos.spi.types.ProviderResponseCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationTraceEvidenceEnvelopeTest {
    @TempDir
    Path temp;

    @Test
    void realSafeTerminalProjectionIsNonScoreBearingAndStrictlyValid() throws Exception {
        Fixture fixture = fixture("direct-terminal");
        byte[] bytes = terminalBytes(fixture, "trc-direct-terminal", true);

        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(bytes, fixture.scenario()));
        assertEquals(
                QualificationTraceEvidenceEnvelope.Kind.INCOMPLETE_TERMINAL,
                QualificationTraceEvidenceEnvelope.validate(
                        bytes, fixture.scenario()).kind());
    }

    @Test
    void processBoundFailedTerminalProjectionRetainsClosedIdentityAndValidates()
            throws Exception {
        Fixture fixture = fixture("process-bound-terminal");
        byte[] bytes = processBoundTerminalBytes(
                fixture, "trc-process-bound-terminal");
        LocalTurnTrace roundTrip = QualificationJson.read(bytes, LocalTurnTrace.class);
        LocalTurnTrace canonical = roundTrip.safeTerminalProjection(
                TurnSettlement.Kind.FAILED, false);
        assertEquals(roundTrip.events(), canonical.events(), () -> "round-trip events="
                + roundTrip.events().stream().map(TurnTraceEvent::type).toList()
                + " canonical="
                + canonical.events().stream().map(TurnTraceEvent::type).toList());
        assertTrue(TurnEvidenceProjectionTraceEvent.decode(roundTrip).valid(),
                () -> TurnEvidenceProjectionTraceEvent.decode(roundTrip).toString());

        QualificationTraceEvidenceEnvelope.Result result =
                QualificationTraceEvidenceEnvelope.validate(
                        bytes, fixture.scenario());
        ObjectNode root = (ObjectNode) QualificationJson.readTree(bytes);

        assertEquals(QualificationTraceEvidenceEnvelope.Kind.INCOMPLETE_TERMINAL,
                result.kind());
        assertEquals("QUALIFIED", event(root, "MODEL_IDENTITY_RESOLVED")
                .path("data").path("resolution").textValue());
        assertEquals(1, root.withArray("events").findValues("processReceiptId")
                .stream().map(JsonNode::textValue).distinct().count());
    }

    @Test
    void exactMarkerSelectsTerminalValidationWithoutExceptionFallback() throws Exception {
        Fixture fixture = fixture("exact-selector");
        byte[] original = Files.readAllBytes(
                fixture.packet().runRoot().resolve("trace.json"));
        assertEquals(
                QualificationTraceEvidenceEnvelope.Kind.SCORE_BEARING,
                QualificationTraceEvidenceEnvelope.validate(
                        original, fixture.scenario()).kind());
        ObjectNode scoreBearing = (ObjectNode) QualificationJson.readTree(original);
        ArrayNode events = (ArrayNode) scoreBearing.get("events");
        events.addObject()
                .put("type", TurnEvidenceProjectionTraceEvent.TYPE)
                .put("timestamp", "2026-07-10T00:00:01Z")
                .put("phase", "")
                .put("toolName", "")
                .putObject("data")
                .put("completeness", "COMPLETE");
        byte[] bytes = QualificationJson.bytes(scoreBearing);

        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidenceEnvelope.validate(
                        bytes, fixture.scenario()));
        assertTrue(invalid.getMessage().contains("trace schema v5"));
    }

    @Test
    void strictTerminalEnvelopeRejectsMalformedSchemaProjectionOutcomeAndLedger()
            throws Exception {
        Fixture fixture = fixture("malformed-terminal");

        assertMalformed(fixture, root -> root.put("schemaVersion", 4));
        ObjectNode unknownField = (ObjectNode) QualificationJson.readTree(
                terminalBytes(fixture, "trc-malformed-terminal", true));
        unknownField.put("futureEvidence", "not-closed");
        assertThrows(IOException.class,
                () -> QualificationTraceEvidenceEnvelope.validate(
                        QualificationJson.bytes(unknownField), fixture.scenario()));
        assertMalformed(fixture, root -> marker(root).withObject("data")
                .put("completeness", "COMPLETE"));
        assertMalformed(fixture, root -> root.withObject("outcome")
                .put("status", "COMPLETE"));
        assertMalformed(fixture, root -> event(root, "TURN_SETTLED")
                .withObject("data").put("settlementKind", "COMPLETED"));
        assertMalformed(fixture, root -> root.withArray("events").addObject()
                .put("type", "UNOWNED_TERMINAL_DETAIL")
                .put("timestamp", "2026-07-10T00:00:01Z")
                .put("phase", "")
                .put("toolName", "")
                .putObject("data")
                .put("rawPath", "C:/external/private.txt"));
        ObjectNode ledgerGap = (ObjectNode) QualificationJson.readTree(
                terminalBytes(fixture, "trc-malformed-terminal", true));
        event(ledgerGap, "GENERATION_ATTEMPT_STARTED")
                .withObject("data").put("ledgerSequence", 9);
        IllegalArgumentException invalidLedger = assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidenceEnvelope.validate(
                        QualificationJson.bytes(ledgerGap), fixture.scenario()));
        assertTrue(invalidLedger.getMessage().contains("provider ledger sequence"));
    }

    @Test
    void publicationCopiesExactValidTerminalBytesAndRejectsMalformedTerminals()
            throws Exception {
        Fixture valid = fixture("publication-valid");
        byte[] validBytes = terminalBytes(valid, "trace-1", false);
        writeSource(valid, validBytes);
        Files.delete(valid.target());

        QualificationTraceEvidencePublication.Result result =
                QualificationTraceEvidencePublication.publish(
                        valid.packet().root(), valid.packet().runRoot(),
                        valid.scenario().id(), valid.catalog());

        assertTrue(result.created());
        assertArrayEquals(validBytes, Files.readAllBytes(valid.target()));

        QualificationPreResultEvidence.Snapshot snapshot =
                QualificationPreResultEvidence.capture(
                        valid.packet().root(), valid.packet().runRoot(),
                        valid.scenario().id(), valid.catalog());
        QualificationRunFinalizer.Publication incomplete =
                QualificationRunFinalizer.derive(snapshot);
        assertNull(snapshot.trace());
        assertEquals("trace-1", snapshot.traceId());
        assertTrue(snapshot.incompleteTerminalTrace());
        assertTrue(snapshot.findings().contains(
                "TRACE_EVIDENCE_INCOMPLETE_TERMINAL"));
        assertFalse(snapshot.findings().contains("TRACE_INVALID"));
        assertEquals(QualificationRecords.AnswerOrigin.NONE,
                snapshot.matchedAnswerOrigin());
        assertFalse(snapshot.trustGates().modelIdentityAgreement());
        assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                incomplete.causal().failureClass());
        assertEquals(QualificationRecords.AnswerOrigin.NONE,
                incomplete.causal().answerOrigin());
        assertEquals(QualificationRecords.CausalOwner.UNKNOWN,
                incomplete.causal().causalOwner());
        assertFalse(incomplete.causal().comparativeScoreEligible());
        assertEquals("EVIDENCE_INCOMPLETE", incomplete.result().terminalState());

        assertPublicationRejects("publication-invalid-marker", root ->
                marker(root).withObject("data").put("reason", "UNKNOWN"));
        assertPublicationRejects("publication-invalid-outcome", root ->
                root.withObject("outcome").put("classification", "COMPLETE"));
        assertPublicationRejects("publication-invalid-schema", root ->
                root.put("schemaVersion", 4));
    }

    private void assertMalformed(Fixture fixture, Consumer<ObjectNode> mutation)
            throws Exception {
        ObjectNode root = (ObjectNode) QualificationJson.readTree(
                terminalBytes(fixture, "trc-malformed-terminal", true));
        mutation.accept(root);
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidenceEnvelope.validate(
                        QualificationJson.bytes(root), fixture.scenario()));
    }

    private void assertPublicationRejects(
            String name,
            Consumer<ObjectNode> mutation
    ) throws Exception {
        Fixture fixture = fixture(name);
        ObjectNode root = (ObjectNode) QualificationJson.readTree(
                terminalBytes(fixture, "trace-1", false));
        mutation.accept(root);
        writeSource(fixture, QualificationJson.bytes(root));
        Files.delete(fixture.target());

        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidencePublication.publish(
                        fixture.packet().root(), fixture.packet().runRoot(),
                        fixture.scenario().id(), fixture.catalog()));
        assertFalse(Files.exists(fixture.target()));
    }

    private Fixture fixture(String name) throws Exception {
        QualificationScenarioBank.Catalog catalog =
                QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve(name), catalog);
        QualificationProtocol.Scenario scenario =
                catalog.admission().scenarios().getFirst();
        return new Fixture(
                packet,
                catalog,
                scenario,
                packet.runRoot().resolve(
                        QualificationTraceEvidencePublication.OUTPUT_FILE));
    }

    private static byte[] terminalBytes(
            Fixture fixture,
            String traceId,
            boolean providerLifecycle
    ) throws Exception {
        String generationId = traceId + "-generation-1";
        String attemptId = generationId + "-attempt-1";
        LocalTurnTrace.Builder builder = LocalTurnTrace.builder(
                        traceId, "session-one", 1, "2026-07-10T00:00:01Z")
                .workspaceHash("sha256:" + "1".repeat(64))
                .mode("ask")
                .model("llama_cpp", "private-model-name")
                .taskContract(new LocalTurnTrace.TaskContractSummary(
                        "READ_ONLY_QA", false, false, false,
                        List.of("private/path"), List.of(), "private reason", List.of()))
                .phaseTransition("START", "MODEL", "private phase")
                .toolSurface(List.of("talos.read_file"), List.of(), "private tools")
                .promptSummary("private prompt")
                .assistantSummary("private partial response")
                .verification("FAILED", "private verifier prose", List.of("private problem"))
                .repair("FAILED", "private repair prose")
                .checkpoint("FAILED", "private-checkpoint")
                .warning("PRIVATE", "private warning")
                .outcome("COMPLETE", "PASSED", "NONE", "NOT_REQUESTED", "COMPLETE");
        builder.event(simple("TRACE_STARTED", traceStartedData(fixture)));
        if (providerLifecycle) {
            builder.event(simple("GENERATION_STARTED", generation(
                    generationId, 1)));
            builder.event(simple("GENERATION_ATTEMPT_STARTED", attempt(
                    generationId, attemptId, 2)));
            Map<String, Object> attemptFailure = new LinkedHashMap<>(
                    attempt(generationId, attemptId, 3));
            attemptFailure.put("failure", "RUNTIME_PRE_DISPATCH");
            builder.event(simple("GENERATION_ATTEMPT_FAILED", attemptFailure));
            Map<String, Object> generationFailure = new LinkedHashMap<>(
                    generation(generationId, 4));
            generationFailure.put("failure", "RUNTIME_PRE_DISPATCH");
            builder.event(simple("GENERATION_FAILED", generationFailure));
        }
        builder.event(TurnSettlementTraceEvent.create(
                "2026-07-10T00:00:01Z",
                TurnSettlement.Kind.FAILED,
                TurnExecutionStage.GENERATION_COORDINATION,
                TurnFailureClassifier.classify(new IllegalStateException())));
        builder.event(simple("TRACE_COMPLETED", Map.of()));
        LocalTurnTrace projected = builder.build().safeTerminalProjection(
                TurnSettlement.Kind.FAILED, false);
        return QualificationJson.bytes(projected);
    }

    private static byte[] processBoundTerminalBytes(
            Fixture fixture,
            String traceId
    ) throws Exception {
        String generationId = traceId + "-generation-1";
        String attemptId = generationId + "-attempt-1";
        String operationId = attemptId + "-transport-1";
        ProviderProcessLaunchReceipt receipt = processReceipt();
        LocalTurnTrace.Builder builder = LocalTurnTrace.builder(
                        traceId, "session-one", 1, "2026-07-10T00:00:01Z")
                .workspaceHash("sha256:" + "1".repeat(64))
                .mode("ask")
                .model("llama_cpp", receipt.resolvedProfile())
                .outcome("COMPLETE", "PASSED", "NONE", "NOT_REQUESTED", "COMPLETE");
        builder.event(simple("TRACE_STARTED", traceStartedData(fixture)));
        builder.event(simple("MODEL_IDENTITY_RESOLVED", processModelIdentity(receipt)));
        builder.event(simple("PROVIDER_PROCESS_LAUNCH_RECEIPT", receipt.traceData()));
        builder.event(simple("GENERATION_STARTED", generation(generationId, 1)));
        builder.event(simple("GENERATION_ATTEMPT_STARTED", attempt(
                generationId, attemptId, 2)));

        Map<String, Object> dispatch = new LinkedHashMap<>(
                attempt(generationId, attemptId, 3));
        dispatch.put("requestDispatched", true);
        dispatch.put("providerBodyHash", "sha256:" + "0".repeat(64));
        dispatch.put("dispatchedNativeTools", List.of());
        dispatch.put("toolAuthoritySurface", Map.of(
                "schemaVersion", 1,
                "bound", false,
                "toolChoiceMode", "NONE",
                "namedToolNameHash", ProviderResponseCapture.toolNameHash(""),
                "selectedToolNameHashes", List.of(),
                "authorizedToolNameHashes", List.of()));
        dispatch.put("transportOperationId", operationId);
        dispatch.put("processReceiptId", receipt.receiptId());
        builder.event(simple("GENERATION_REQUEST_DISPATCHED", dispatch));
        builder.event(simple("PROVIDER_TRANSPORT_LIFECYCLE", transport(
                generationId, attemptId, operationId, receipt, 1,
                "HANDOFF_ADMITTED", "NONE", "NONE")));
        builder.event(simple("PROVIDER_TRANSPORT_LIFECYCLE", transport(
                generationId, attemptId, operationId, receipt, 2,
                "SETTLED", "FAILED_BEFORE_HEADERS", "RUNTIME")));

        Map<String, Object> attemptFailure = new LinkedHashMap<>(
                attempt(generationId, attemptId, 4));
        attemptFailure.put("requestDispatched", true);
        attemptFailure.put("failure", "RUNTIME_EXCEPTION");
        attemptFailure.put("transportOperationId", operationId);
        attemptFailure.put("processReceiptId", receipt.receiptId());
        builder.event(simple("GENERATION_ATTEMPT_FAILED", attemptFailure));
        Map<String, Object> generationFailure = new LinkedHashMap<>(
                generation(generationId, 5));
        generationFailure.put("failure", "RUNTIME_EXCEPTION");
        generationFailure.put("transportOperationId", operationId);
        generationFailure.put("processReceiptId", receipt.receiptId());
        builder.event(simple("GENERATION_FAILED", generationFailure));
        builder.event(TurnSettlementTraceEvent.create(
                "2026-07-10T00:00:01Z",
                TurnSettlement.Kind.FAILED,
                TurnExecutionStage.GENERATION_COORDINATION,
                TurnFailureClassifier.classify(new IllegalStateException())));
        builder.event(simple("TRACE_COMPLETED", Map.of()));

        return QualificationJson.bytes(builder.build().safeTerminalProjection(
                TurnSettlement.Kind.FAILED, false));
    }

    private static Map<String, Object> transport(
            String generationId,
            String attemptId,
            String operationId,
            ProviderProcessLaunchReceipt receipt,
            int sequence,
            String transition,
            String settlement,
            String failure
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("transportLedgerVersion", 2);
        data.put("transportSequence", sequence);
        data.put("generationId", generationId);
        data.put("attemptId", attemptId);
        data.put("attemptOrdinal", 1);
        data.put("transportOperationId", operationId);
        data.put("transition", transition);
        data.put("observationMode", "OBSERVED");
        data.put("settlement", settlement);
        data.put("transportFailure", failure);
        data.put("cancellationState", "NOT_REQUESTED");
        data.put("cancellationReason", "NONE");
        data.put("wireDisposition", "SEND_API_NOT_ENTERED");
        data.put("quarantineRequired", false);
        data.put("lateAfterGenerationTerminal", false);
        data.put("processBinding", "MANAGED_QUALIFICATION_EXACT");
        data.put("processReceiptId", receipt.receiptId());
        return data;
    }

    private static Map<String, Object> processModelIdentity(
            ProviderProcessLaunchReceipt receipt) {
        return ModelIdentitySnapshot.builder()
                .backend("llama_cpp")
                .configuredModel(receipt.resolvedProfile())
                .resolvedProfile(receipt.resolvedProfile())
                .artifactState("EXACT")
                .engineExecutableIdentity(receipt.engineExecutableSha256())
                .servedIdentityState("MATCHED")
                .resolution("QUALIFIED")
                .propsState("AVAILABLE")
                .propsResponseSha256(receipt.propsResponseSha256())
                .chatAvailable(true)
                .build()
                .traceData();
    }

    private static ProviderProcessLaunchReceipt processReceipt() {
        return new ProviderProcessLaunchReceipt(
                42, 1_000, 1_001, 1_002,
                "0123456789abcdef0123456789abcdef",
                "1".repeat(64), "2".repeat(64), "3".repeat(64),
                "4".repeat(64),
                ProviderProcessLaunchReceipt.LaunchArgumentSet.STANDARD,
                "5".repeat(64),
                ProviderProcessLaunchReceipt.EnvironmentContract
                        .QUALIFICATION_RELEVANT_ALLOWLIST_V1,
                "127.0.0.1", 8080, true, "6".repeat(64), "candidate",
                ProviderProcessLaunchReceipt.ServedIdentity.MATCHED,
                ProviderProcessLaunchReceipt.EndpointObserverKind
                        .WINDOWS_NETSTAT_NOFOLLOW_SHA256,
                "7".repeat(64),
                ProviderProcessLaunchReceipt.EndpointOwnershipBoundary
                        .WINDOWS_NETSTAT_LISTENER_OWNER_PID_START_AT_READINESS_BIND_AND_TRANSPORT,
                ProviderProcessLaunchReceipt.StableEndpointAssumption
                        .WINDOWS_TRUSTED_HELPER_AND_STABLE_LISTENER_OWNER_BETWEEN_OBSERVATION_AND_NETWORK_TRANSITION,
                ProviderProcessLaunchReceipt.ContentVerificationBoundary
                        .IMMEDIATE_PRELAUNCH_NOFOLLOW_SHA256,
                ProviderProcessLaunchReceipt.StableFilesystemAssumption
                        .STABLE_LOCAL_FILES_DURING_PROCESS_LOAD);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> traceStartedData(Fixture fixture)
            throws Exception {
        JsonNode root = QualificationJson.readTree(Files.readAllBytes(fixture.target()));
        for (JsonNode event : root.withArray("events")) {
            if ("TRACE_STARTED".equals(event.path("type").textValue())) {
                Map<String, Object> data = new LinkedHashMap<>(QualificationJson.read(
                        QualificationJson.bytes(event.path("data")), Map.class));
                data.put("turnNumber", 1);
                data.put("redactionMode", "DEFAULT");
                return Map.copyOf(data);
            }
        }
        throw new AssertionError("fixture TRACE_STARTED event missing");
    }

    private static Map<String, Object> generation(
            String generationId,
            int sequence
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("providerLedgerVersion", 2);
        data.put("ledgerSequence", sequence);
        data.put("generationId", generationId);
        data.put("source", "FOREGROUND_PROVIDER");
        data.put("purpose", "TURN_RESPONSE");
        data.put("toolAuthorityMode", "NONE");
        return data;
    }

    private static Map<String, Object> attempt(
            String generationId,
            String attemptId,
            int sequence
    ) {
        Map<String, Object> data = new LinkedHashMap<>(
                generation(generationId, sequence));
        data.put("attemptId", attemptId);
        data.put("attemptOrdinal", 1);
        data.put("attemptKind", "PRIMARY");
        data.put("retryReason", "NONE");
        data.put("requestDispatched", false);
        return data;
    }

    private static TurnTraceEvent simple(String type, Map<String, Object> data) {
        return TurnTraceEvent.simple(type, "2026-07-10T00:00:01Z", data);
    }

    private static ObjectNode marker(ObjectNode root) {
        return event(root, TurnEvidenceProjectionTraceEvent.TYPE);
    }

    private static ObjectNode event(ObjectNode root, String type) {
        for (JsonNode event : root.withArray("events")) {
            if (type.equals(event.path("type").textValue())) {
                return (ObjectNode) event;
            }
        }
        throw new AssertionError("missing event " + type);
    }

    private static void writeSource(Fixture fixture, byte[] bytes) throws Exception {
        Path source = fixture.packet().root()
                .resolve("home/.talos/sessions/traces/session-one/000001-trace-1.json");
        Files.createDirectories(source.getParent());
        Files.write(source, bytes);
    }

    private record Fixture(
            QualificationTestPacket packet,
            QualificationScenarioBank.Catalog catalog,
            QualificationProtocol.Scenario scenario,
            Path target
    ) {}
}
