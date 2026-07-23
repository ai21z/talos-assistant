package dev.talos.runtime.trace;

import dev.talos.spi.types.ModelIdentitySnapshot;
import dev.talos.spi.types.ProviderProcessLaunchReceipt;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeTerminalTraceProjectorTest {

    @Test
    void completeProviderResponseWithTypedTextToolPresenceIsPreserved() {
        String traceId = "trc-projector-response";
        String generationId = traceId + "-generation-1";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("providerLedgerVersion", 1);
        data.put("ledgerSequence", 4);
        data.put("generationId", generationId);
        data.put("source", "FOREGROUND_PROVIDER");
        data.put("purpose", "TURN_RESPONSE");
        data.put("toolAuthorityMode", "PROVIDER_NATIVE");
        data.put("attemptId", generationId + "-attempt-1");
        data.put("attemptOrdinal", 1);
        data.put("attemptKind", "PRIMARY");
        data.put("retryReason", "NONE");
        data.put("requestDispatched", true);
        data.put("assistantHash", "sha256:" + "0".repeat(64));
        data.put("assistantChars", 0);
        data.put("nativeToolCallCount", 1);
        data.put("capturedNativeToolProposalCount", 1);
        data.put("nativeToolProposalsTruncated", false);
        data.put("textualToolCallPresent", false);
        data.put("completion", "PROVIDER_EOS");
        data.put("providerBacked", true);
        data.put("foreground", true);
        data.put("completedNormally", true);
        data.put("normalized", false);
        data.put("truncated", false);
        TurnTraceEvent response = TurnTraceEvent.simple(
                "GENERATION_RESPONSE_CAPTURED",
                "2026-07-21T00:00:00Z",
                data);

        SafeTerminalTraceProjector.Projection projection =
                SafeTerminalTraceProjector.project(List.of(response), traceId);

        assertTrue(projection.providerLifecycleObserved());
        assertTrue(projection.providerLifecyclePreserved());
        assertEquals(List.of(response), projection.events());
    }

    @Test
    void transportSettlementPreservesTheExactQuarantineDecision() {
        String traceId = "trc-projector-transport";
        String generationId = traceId + "-generation-1";
        String attemptId = generationId + "-attempt-1";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("transportLedgerVersion", 1);
        data.put("transportSequence", 5);
        data.put("generationId", generationId);
        data.put("attemptId", attemptId);
        data.put("attemptOrdinal", 1);
        data.put("transportOperationId", attemptId + "-transport-1");
        data.put("transition", "SETTLED");
        data.put("observationMode", "OBSERVED");
        data.put("settlement", "CANCELLED_IN_FLIGHT");
        data.put("transportFailure", "BODY_IO");
        data.put("cancellationState", "BODY_CLOSE_ACKNOWLEDGED");
        data.put("cancellationReason", "SCOPE_CLOSED");
        data.put("wireDisposition", "UNKNOWN");
        data.put("quarantineRequired", true);
        data.put("lateAfterGenerationTerminal", false);
        data.put("processBinding", "UNAVAILABLE");
        TurnTraceEvent settlement = TurnTraceEvent.simple(
                "PROVIDER_TRANSPORT_LIFECYCLE",
                "2026-07-22T00:00:00Z",
                data);

        SafeTerminalTraceProjector.Projection projection =
                SafeTerminalTraceProjector.project(List.of(settlement), traceId);

        assertTrue(projection.providerLifecycleObserved());
        assertTrue(projection.providerLifecyclePreserved());
        assertEquals(List.of(settlement), projection.events());
    }

    @Test
    void safeTerminalProjectionPreservesTheFullReceiptAndBoundSettlement() {
        String traceId = "trc-projector-process";
        ProviderProcessLaunchReceipt receipt = receipt();
        TurnTraceEvent identityEvent = TurnTraceEvent.simple(
                "MODEL_IDENTITY_RESOLVED",
                "2026-07-22T00:00:00Z",
                modelIdentity(receipt));
        TurnTraceEvent receiptEvent = TurnTraceEvent.simple(
                "PROVIDER_PROCESS_LAUNCH_RECEIPT",
                "2026-07-22T00:00:00Z",
                receipt.traceData());
        String generationId = traceId + "-generation-1";
        String attemptId = generationId + "-attempt-1";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("transportLedgerVersion", 2);
        data.put("transportSequence", 1);
        data.put("generationId", generationId);
        data.put("attemptId", attemptId);
        data.put("attemptOrdinal", 1);
        data.put("transportOperationId", attemptId + "-transport-1");
        data.put("transition", "SETTLED");
        data.put("observationMode", "OBSERVED");
        data.put("settlement", "COMPLETED_PROVIDER_TERMINAL");
        data.put("transportFailure", "NONE");
        data.put("cancellationState", "NOT_REQUESTED");
        data.put("cancellationReason", "NONE");
        data.put("wireDisposition", "UNKNOWN");
        data.put("quarantineRequired", false);
        data.put("lateAfterGenerationTerminal", false);
        data.put("processBinding", "MANAGED_QUALIFICATION_EXACT");
        data.put("processReceiptId", receipt.receiptId());
        TurnTraceEvent settlement = TurnTraceEvent.simple(
                "PROVIDER_TRANSPORT_LIFECYCLE",
                "2026-07-22T00:00:01Z",
                data);

        SafeTerminalTraceProjector.Projection projection =
                SafeTerminalTraceProjector.project(
                        List.of(identityEvent, receiptEvent, settlement), traceId);

        assertTrue(projection.providerLifecyclePreserved());
        assertEquals(List.of(identityEvent, receiptEvent, settlement), projection.events());
    }

    @Test
    void modelIdentityProjectionRejectsUnknownOrPathBearingData() {
        ProviderProcessLaunchReceipt receipt = receipt();
        Map<String, Object> unknown = new LinkedHashMap<>(modelIdentity(receipt));
        unknown.put("modelPath", "C:/secret/model.gguf");
        Map<String, Object> pathBearing = new LinkedHashMap<>(modelIdentity(receipt));
        pathBearing.put("engineBuildInfo", "build=/private/toolchain");

        for (Map<String, Object> data : List.of(unknown, pathBearing)) {
            SafeTerminalTraceProjector.Projection projection =
                    SafeTerminalTraceProjector.project(List.of(
                            TurnTraceEvent.simple(
                                    "MODEL_IDENTITY_RESOLVED",
                                    "2026-07-22T00:00:00Z",
                                    data)), "trc-projector-model-invalid");
            assertTrue(projection.events().isEmpty());
        }
    }

    @Test
    void legacyTransportLedgerCannotSmuggleAProcessReceiptReference() {
        String traceId = "trc-projector-legacy-process";
        String generationId = traceId + "-generation-1";
        String attemptId = generationId + "-attempt-1";
        Map<String, Object> data = settledTransport(
                generationId, attemptId, 1, "UNAVAILABLE");
        data.put("processReceiptId", "pr-" + "a".repeat(64));
        TurnTraceEvent settlement = TurnTraceEvent.simple(
                "PROVIDER_TRANSPORT_LIFECYCLE",
                "2026-07-22T00:00:00Z",
                data);

        SafeTerminalTraceProjector.Projection projection =
                SafeTerminalTraceProjector.project(List.of(settlement), traceId);

        assertTrue(projection.providerLifecycleObserved());
        assertFalse(projection.providerLifecyclePreserved());
        assertTrue(projection.events().isEmpty());
    }

    @Test
    void managedProcessBindingCannotOmitItsReceiptReference() {
        String traceId = "trc-projector-missing-process";
        String generationId = traceId + "-generation-1";
        String attemptId = generationId + "-attempt-1";
        Map<String, Object> data = settledTransport(
                generationId, attemptId, 2, "MANAGED_QUALIFICATION_EXACT");
        TurnTraceEvent settlement = TurnTraceEvent.simple(
                "PROVIDER_TRANSPORT_LIFECYCLE",
                "2026-07-22T00:00:00Z",
                data);

        SafeTerminalTraceProjector.Projection projection =
                SafeTerminalTraceProjector.project(List.of(settlement), traceId);

        assertTrue(projection.providerLifecycleObserved());
        assertFalse(projection.providerLifecyclePreserved());
        assertTrue(projection.events().isEmpty());
    }

    @Test
    void processReceiptReferenceRequiresTheExactAttemptTransportOperation() {
        String traceId = "trc-projector-wrong-operation";
        String generationId = traceId + "-generation-1";
        String attemptId = generationId + "-attempt-1";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("providerLedgerVersion", 2);
        data.put("ledgerSequence", 1);
        data.put("generationId", generationId);
        data.put("source", "FOREGROUND_PROVIDER");
        data.put("purpose", "TURN_RESPONSE");
        data.put("toolAuthorityMode", "NONE");
        data.put("attemptId", attemptId);
        data.put("attemptOrdinal", 1);
        data.put("attemptKind", "PRIMARY");
        data.put("retryReason", "NONE");
        data.put("requestDispatched", true);
        data.put("failure", "RUNTIME_EXCEPTION");
        data.put("transportOperationId", attemptId + "-transport-2");
        data.put("processReceiptId", "pr-" + "a".repeat(64));
        TurnTraceEvent failed = TurnTraceEvent.simple(
                "GENERATION_ATTEMPT_FAILED",
                "2026-07-22T00:00:00Z",
                data);

        SafeTerminalTraceProjector.Projection projection =
                SafeTerminalTraceProjector.project(List.of(failed), traceId);

        assertTrue(projection.providerLifecycleObserved());
        assertFalse(projection.providerLifecyclePreserved());
        assertTrue(projection.events().isEmpty());
    }

    @Test
    void processReceiptReferenceCannotExistWithoutATransportOperation() {
        String traceId = "trc-projector-missing-operation";
        String generationId = traceId + "-generation-1";
        String attemptId = generationId + "-attempt-1";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("providerLedgerVersion", 2);
        data.put("ledgerSequence", 1);
        data.put("generationId", generationId);
        data.put("source", "FOREGROUND_PROVIDER");
        data.put("purpose", "TURN_RESPONSE");
        data.put("toolAuthorityMode", "NONE");
        data.put("attemptId", attemptId);
        data.put("attemptOrdinal", 1);
        data.put("attemptKind", "PRIMARY");
        data.put("retryReason", "NONE");
        data.put("requestDispatched", true);
        data.put("failure", "RUNTIME_EXCEPTION");
        data.put("processReceiptId", "pr-" + "a".repeat(64));
        TurnTraceEvent failed = TurnTraceEvent.simple(
                "GENERATION_ATTEMPT_FAILED",
                "2026-07-22T00:00:00Z",
                data);

        SafeTerminalTraceProjector.Projection projection =
                SafeTerminalTraceProjector.project(List.of(failed), traceId);

        assertTrue(projection.providerLifecycleObserved());
        assertFalse(projection.providerLifecyclePreserved());
        assertTrue(projection.events().isEmpty());
    }

    private static Map<String, Object> settledTransport(
            String generationId,
            String attemptId,
            int ledgerVersion,
            String processBinding) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("transportLedgerVersion", ledgerVersion);
        data.put("transportSequence", 1);
        data.put("generationId", generationId);
        data.put("attemptId", attemptId);
        data.put("attemptOrdinal", 1);
        data.put("transportOperationId", attemptId + "-transport-1");
        data.put("transition", "SETTLED");
        data.put("observationMode", "OBSERVED");
        data.put("settlement", "COMPLETED_PROVIDER_TERMINAL");
        data.put("transportFailure", "NONE");
        data.put("cancellationState", "NOT_REQUESTED");
        data.put("cancellationReason", "NONE");
        data.put("wireDisposition", "UNKNOWN");
        data.put("quarantineRequired", false);
        data.put("lateAfterGenerationTerminal", false);
        data.put("processBinding", processBinding);
        return data;
    }

    private static ProviderProcessLaunchReceipt receipt() {
        return new ProviderProcessLaunchReceipt(
                9001L, 1_721_600_000_000L, 1_721_600_000_100L,
                1_721_600_000_200L, "0123456789abcdef0123456789abcdef",
                "a".repeat(64), "b".repeat(64), "c".repeat(64),
                "e".repeat(64), ProviderProcessLaunchReceipt.LaunchArgumentSet.VERIFICATION,
                "f".repeat(64),
                ProviderProcessLaunchReceipt.EnvironmentContract.QUALIFICATION_RELEVANT_ALLOWLIST_V1,
                "127.0.0.1", 8080, true, "1".repeat(64), "qualification-model",
                ProviderProcessLaunchReceipt.ServedIdentity.MATCHED,
                ProviderProcessLaunchReceipt.EndpointObserverKind
                        .WINDOWS_NETSTAT_NOFOLLOW_SHA256,
                "2".repeat(64),
                ProviderProcessLaunchReceipt.EndpointOwnershipBoundary
                        .WINDOWS_NETSTAT_LISTENER_OWNER_PID_START_AT_READINESS_BIND_AND_TRANSPORT,
                ProviderProcessLaunchReceipt.StableEndpointAssumption
                        .WINDOWS_TRUSTED_HELPER_AND_STABLE_LISTENER_OWNER_BETWEEN_OBSERVATION_AND_NETWORK_TRANSITION,
                ProviderProcessLaunchReceipt.ContentVerificationBoundary
                        .IMMEDIATE_PRELAUNCH_NOFOLLOW_SHA256,
                ProviderProcessLaunchReceipt.StableFilesystemAssumption
                        .STABLE_LOCAL_FILES_DURING_PROCESS_LOAD);
    }

    private static Map<String, Object> modelIdentity(
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
}
