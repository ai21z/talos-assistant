package dev.talos.qualification;

import com.fasterxml.jackson.databind.JsonNode;
import dev.talos.spi.types.ModelIdentitySnapshot;
import dev.talos.spi.types.ProviderProcessLaunchReceipt;
import dev.talos.spi.types.ProviderTransportAttempt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Strict, fail-closed reader for the trace-v5 active-provider-process receipt. */
final class QualificationProviderProcessReceiptLedger {
    static final String EVENT_TYPE = "PROVIDER_PROCESS_LAUNCH_RECEIPT";

    private static final Set<String> REFERENCE_EVENTS = Set.of(
            "GENERATION_REQUEST_DISPATCHED",
            "GENERATION_RESPONSE_CAPTURED",
            "GENERATION_ATTEMPT_FAILED",
            "GENERATION_ATTEMPT_ABANDONED",
            "GENERATION_FAILED",
            "GENERATION_ABANDONED",
            "GENERATION_COMPLETED",
            "PROVIDER_TOOL_PROPOSAL_CAPTURED",
            QualificationProviderTransportLedger.EVENT_TYPE);

    private QualificationProviderProcessReceiptLedger() {}

    record Reference(
            int eventIndex,
            String eventType,
            String generationId,
            String attemptId,
            String transportOperationId,
            String processReceiptId) {}

    record Ledger(
            ProviderProcessLaunchReceipt receipt,
            int receiptEventIndex,
            List<Reference> references) {
        Ledger {
            references = references == null ? List.of() : List.copyOf(references);
            if ((receipt == null) != (receiptEventIndex < 0)) {
                throw new IllegalArgumentException(
                        "provider process receipt and event index disagree");
            }
        }

        static Ledger empty() {
            return new Ledger(null, -1, List.of());
        }

        Optional<ProviderProcessLaunchReceipt> receiptOptional() {
            return Optional.ofNullable(receipt);
        }

        boolean present() {
            return receipt != null;
        }

        boolean completeFor(
                List<QualificationProviderAttemptLedger.Generation> generations,
                QualificationProviderTransportLedger.Ledger transports) {
            if (receipt == null || references.isEmpty() || transports == null) return false;
            List<QualificationProviderAttemptLedger.Attempt> dispatched = generations.stream()
                    .flatMap(generation -> generation.attempts().stream())
                    .filter(QualificationProviderAttemptLedger.Attempt::requestDispatched)
                    .toList();
            if (dispatched.isEmpty()) return false;
            String receiptId = receipt.receiptId();
            for (QualificationProviderAttemptLedger.Attempt attempt : dispatched) {
                QualificationProviderTransportLedger.Operation operation =
                        transports.operationForAttempt(attempt.attemptId());
                if (operation == null
                        || operation.processBinding()
                                != ProviderTransportAttempt.ProcessBinding.MANAGED_QUALIFICATION_EXACT
                        || !receiptId.equals(operation.processReceiptId())
                        || !hasReference("GENERATION_REQUEST_DISPATCHED",
                                operation.transportOperationId(), receiptId)
                        || !hasReference(QualificationProviderTransportLedger.EVENT_TYPE,
                                operation.transportOperationId(), receiptId)) {
                    return false;
                }
                if (attempt.response() != null
                        && !hasReference("GENERATION_RESPONSE_CAPTURED",
                                operation.transportOperationId(), receiptId)) {
                    return false;
                }
            }
            return references.stream().allMatch(reference ->
                    receiptId.equals(reference.processReceiptId()));
        }

        private boolean hasReference(String type, String operationId, String receiptId) {
            return references.stream().anyMatch(reference ->
                    type.equals(reference.eventType())
                            && operationId.equals(reference.transportOperationId())
                            && receiptId.equals(reference.processReceiptId()));
        }
    }

    static Ledger parse(JsonNode traceRoot) {
        if (traceRoot == null || !traceRoot.isObject()) {
            throw new IllegalArgumentException("trace must be an object");
        }
        int schemaVersion = QualificationJson.requiredInt(
                traceRoot.path("schemaVersion"), "trace schemaVersion");
        JsonNode events = QualificationJson.requiredArray(
                traceRoot.path("events"), "trace events");
        ProviderProcessLaunchReceipt receipt = null;
        int receiptEventIndex = -1;
        List<Reference> references = new ArrayList<>();
        List<Map.Entry<Integer, JsonNode>> modelIdentities = new ArrayList<>();
        Map<String, String> operationReceipts = new LinkedHashMap<>();

        for (int eventIndex = 0; eventIndex < events.size(); eventIndex++) {
            JsonNode event = events.get(eventIndex);
            String type = QualificationJson.requiredText(
                    event.path("type"), "trace event type");
            JsonNode data = event.path("data");
            if ("MODEL_IDENTITY_RESOLVED".equals(type)) {
                modelIdentities.add(Map.entry(eventIndex, data));
            }
            if (EVENT_TYPE.equals(type)) {
                if (schemaVersion != QualificationProviderAttemptLedger.PROCESS_TRACE_SCHEMA_VERSION) {
                    throw new IllegalArgumentException(
                            "active provider process receipt requires trace schema v5");
                }
                if (receipt != null) {
                    throw new IllegalArgumentException(
                            "trace contains multiple active provider process receipts");
                }
                receipt = parseReceipt(data);
                receiptEventIndex = eventIndex;
                continue;
            }
            boolean hasReceipt = data != null && data.has("processReceiptId");
            if (!hasReceipt) continue;
            if (schemaVersion != QualificationProviderAttemptLedger.PROCESS_TRACE_SCHEMA_VERSION
                    || !REFERENCE_EVENTS.contains(type)) {
                throw new IllegalArgumentException(
                        "process receipt reference appears outside the trace-v5 provider ledger");
            }
            if (receipt == null || receiptEventIndex >= eventIndex) {
                throw new IllegalArgumentException(
                        "process receipt reference precedes its full receipt");
            }
            String processReceiptId = processReceiptId(data);
            String operationId = safeId(data, "transportOperationId");
            if (!receipt.receiptId().equals(processReceiptId)) {
                throw new IllegalArgumentException(
                        "provider event references a different process receipt");
            }
            String previous = operationReceipts.putIfAbsent(operationId, processReceiptId);
            if (previous != null && !previous.equals(processReceiptId)) {
                throw new IllegalArgumentException(
                        "transport operation changes its active process receipt");
            }
            references.add(new Reference(
                    eventIndex,
                    type,
                    optionalSafeId(data, "generationId"),
                    optionalSafeId(data, "attemptId"),
                    operationId,
                    processReceiptId));
        }
        if (schemaVersion != QualificationProviderAttemptLedger.PROCESS_TRACE_SCHEMA_VERSION) {
            return Ledger.empty();
        }
        if (receipt != null && references.isEmpty()) {
            throw new IllegalArgumentException(
                    "active provider process receipt is never referenced");
        }
        if (receipt != null) {
            validateModelIdentity(modelIdentities, receiptEventIndex, receipt);
            validateReferenceCoverage(events, receipt);
        }
        return new Ledger(receipt, receiptEventIndex, references);
    }

    private static void validateModelIdentity(
            List<Map.Entry<Integer, JsonNode>> observations,
            int receiptEventIndex,
            ProviderProcessLaunchReceipt receipt) {
        if (observations.size() != 1
                || observations.getFirst().getKey() >= receiptEventIndex) {
            throw new IllegalArgumentException(
                    "active provider process receipt lacks one prior model identity observation");
        }
        JsonNode data = QualificationJson.requiredObject(
                observations.getFirst().getValue(), "model identity data");
        ModelIdentitySnapshot identity = canonicalModelIdentity(data);
        if (!identity.traceData().keySet().containsAll(
                    ModelIdentitySnapshot.PROCESS_BINDING_TRACE_FIELDS)
                || !receipt.propsResponseSha256().equals(
                        identity.propsResponseSha256())
                || !receipt.engineExecutableSha256().equals(
                        identity.engineExecutableIdentity())
                || !receipt.resolvedProfile().equals(identity.resolvedProfile())
                || !"EXACT".equals(identity.artifactState())
                || !"MATCHED".equals(identity.servedIdentityState())
                || !"QUALIFIED".equals(identity.resolution())
                || !"AVAILABLE".equals(identity.propsState())) {
            throw new IllegalArgumentException(
                    "active provider process receipt and model identity observation disagree");
        }
    }

    private static ModelIdentitySnapshot canonicalModelIdentity(JsonNode data) {
        try {
            Map<?, ?> decoded = QualificationJson.read(
                    QualificationJson.bytes(data), Map.class);
            LinkedHashMap<String, Object> raw = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : decoded.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException(
                            "model identity trace key is not a string");
                }
                raw.put(key, entry.getValue());
            }
            return ModelIdentitySnapshot.fromTraceData(raw);
        } catch (IOException invalid) {
            throw new IllegalArgumentException(
                    "model identity trace data is not readable", invalid);
        }
    }

    private static void validateReferenceCoverage(
            JsonNode events,
            ProviderProcessLaunchReceipt receipt) {
        Map<String, String> attemptOperations = new LinkedHashMap<>();
        Map<String, String> finalAttemptByGeneration = new LinkedHashMap<>();
        for (JsonNode event : events) {
            String type = QualificationJson.requiredText(
                    event.path("type"), "trace event type");
            if ("GENERATION_ATTEMPT_STARTED".equals(type)) {
                JsonNode data = event.path("data");
                finalAttemptByGeneration.put(
                        safeId(data, "generationId"), safeId(data, "attemptId"));
                continue;
            }
            if (!"GENERATION_REQUEST_DISPATCHED".equals(type)) {
                continue;
            }
            JsonNode data = event.path("data");
            String generationId = safeId(data, "generationId");
            String attemptId = safeId(data, "attemptId");
            String expectedOperation = attemptId + "-transport-1";
            requireBoundReference(data, expectedOperation, receipt.receiptId(),
                    "generation request dispatch");
            if (attemptOperations.putIfAbsent(attemptId, expectedOperation) != null) {
                throw new IllegalArgumentException(
                        "provider attempt is dispatched more than once");
            }
            finalAttemptByGeneration.put(generationId, attemptId);
        }
        if (attemptOperations.isEmpty()) {
            throw new IllegalArgumentException(
                    "active provider process receipt lacks a dispatched request");
        }

        for (JsonNode event : events) {
            String type = QualificationJson.requiredText(
                    event.path("type"), "trace event type");
            if (!REFERENCE_EVENTS.contains(type)
                    || "GENERATION_REQUEST_DISPATCHED".equals(type)) {
                continue;
            }
            JsonNode data = event.path("data");
            String attemptId = data.has("attemptId")
                    ? safeId(data, "attemptId")
                    : generationTerminalAttempt(
                            type, data, finalAttemptByGeneration);
            String expectedOperation = attemptOperations.get(attemptId);
            if (expectedOperation == null) {
                if (data.has("transportOperationId")
                        || data.has("processReceiptId")) {
                    throw new IllegalArgumentException(
                            type + " references a process for a non-dispatched attempt");
                }
                continue;
            }
            requireBoundReference(data, expectedOperation, receipt.receiptId(), type);
        }
    }

    private static String generationTerminalAttempt(
            String type,
            JsonNode data,
            Map<String, String> finalAttemptByGeneration) {
        if (!("GENERATION_FAILED".equals(type)
                || "GENERATION_ABANDONED".equals(type)
                || "GENERATION_COMPLETED".equals(type))) {
            return "";
        }
        if ("GENERATION_COMPLETED".equals(type) && data.has("selectedAttemptId")) {
            return safeId(data, "selectedAttemptId");
        }
        return finalAttemptByGeneration.getOrDefault(
                safeId(data, "generationId"), "");
    }

    private static void requireBoundReference(
            JsonNode data,
            String expectedOperation,
            String expectedReceipt,
            String label) {
        if (!data.has("transportOperationId") || !data.has("processReceiptId")
                || !expectedOperation.equals(safeId(data, "transportOperationId"))
                || !expectedReceipt.equals(processReceiptId(data))) {
            throw new IllegalArgumentException(
                    label + " is not bound to its exact active provider process");
        }
    }

    private static ProviderProcessLaunchReceipt parseReceipt(JsonNode raw) {
        JsonNode data = QualificationJson.requiredObject(raw, "provider process receipt data");
        Set<String> fields = new LinkedHashSet<>();
        data.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(ProviderProcessLaunchReceipt.TRACE_FIELDS)) {
            throw new IllegalArgumentException(
                    "provider process receipt fields are not exact");
        }
        String schemaName = QualificationJson.requiredText(
                data.path("schemaName"), "provider process receipt schemaName");
        int schemaVersion = QualificationJson.requiredInt(
                data.path("schemaVersion"), "provider process receipt schemaVersion");
        if (!ProviderProcessLaunchReceipt.SCHEMA_NAME.equals(schemaName)
                || schemaVersion != ProviderProcessLaunchReceipt.SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "provider process receipt schema is unsupported");
        }
        ProviderProcessLaunchReceipt receipt = new ProviderProcessLaunchReceipt(
                positiveLong(data, "processId"),
                positiveLong(data, "processStartEpochMilli"),
                positiveLong(data, "launchObservedEpochMilli"),
                positiveLong(data, "readinessObservedEpochMilli"),
                QualificationJson.requiredText(data.path("launchNonce"),
                        "provider process launch nonce"),
                rawHash(data, "candidateDeclarationSha256"),
                rawHash(data, "engineExecutableSha256"),
                rawHash(data, "modelArtifactSha256"),
                rawHash(data, "launchCommandSha256"),
                QualificationJson.requiredEnum(data.path("launchArgumentSet"),
                        ProviderProcessLaunchReceipt.LaunchArgumentSet.class,
                        "provider process launch argument set"),
                rawHash(data, "effectiveEnvironmentSha256"),
                QualificationJson.requiredEnum(data.path("environmentContract"),
                        ProviderProcessLaunchReceipt.EnvironmentContract.class,
                        "provider process environment contract"),
                QualificationJson.requiredText(data.path("managedHost"),
                        "provider process managed host"),
                QualificationJson.requiredInt(data.path("managedPort"), 1, 65_535,
                        "provider process managed port"),
                QualificationJson.requiredBoolean(data.path("prelaunchNoListener"),
                        "provider process no-listener preflight"),
                rawHash(data, "propsResponseSha256"),
                QualificationJson.requiredText(data.path("resolvedProfile"),
                        "provider process resolved profile"),
                QualificationJson.requiredEnum(data.path("servedIdentity"),
                        ProviderProcessLaunchReceipt.ServedIdentity.class,
                        "provider process served identity"),
                QualificationJson.requiredEnum(data.path("endpointObserverKind"),
                        ProviderProcessLaunchReceipt.EndpointObserverKind.class,
                        "provider process endpoint observer kind"),
                rawHash(data, "endpointObserverIdentitySha256"),
                QualificationJson.requiredEnum(data.path("endpointOwnershipBoundary"),
                        ProviderProcessLaunchReceipt.EndpointOwnershipBoundary.class,
                        "provider process endpoint ownership boundary"),
                QualificationJson.requiredEnum(data.path("stableEndpointAssumption"),
                        ProviderProcessLaunchReceipt.StableEndpointAssumption.class,
                        "provider process stable-endpoint assumption"),
                QualificationJson.requiredEnum(data.path("contentVerificationBoundary"),
                        ProviderProcessLaunchReceipt.ContentVerificationBoundary.class,
                        "provider process content verification boundary"),
                QualificationJson.requiredEnum(data.path("stableFilesystemAssumption"),
                        ProviderProcessLaunchReceipt.StableFilesystemAssumption.class,
                        "provider process stable-filesystem assumption"));
        if (!receipt.receiptId().equals(processReceiptId(data))) {
            throw new IllegalArgumentException(
                    "provider process receipt ID does not match its typed fields");
        }
        return receipt;
    }

    private static long positiveLong(JsonNode data, String key) {
        long value = QualificationJson.requiredLong(
                data.path(key), "provider process " + key);
        if (value < 1) throw new IllegalArgumentException(key + " must be positive");
        return value;
    }

    private static String rawHash(JsonNode data, String key) {
        String value = QualificationJson.requiredText(
                data.path(key), "provider process " + key);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(key + " is not a lowercase SHA-256 value");
        }
        return value;
    }

    private static String processReceiptId(JsonNode data) {
        String value = QualificationJson.requiredText(
                data.path("processReceiptId"), "provider process receipt ID");
        if (!value.matches("pr-[0-9a-f]{64}")) {
            throw new IllegalArgumentException("provider process receipt ID is invalid");
        }
        return value;
    }

    private static String safeId(JsonNode data, String key) {
        String value = QualificationJson.requiredText(data.path(key), key);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,511}")) {
            throw new IllegalArgumentException(key + " is invalid");
        }
        return value;
    }

    private static String optionalSafeId(JsonNode data, String key) {
        if (!data.has(key)) return "";
        return safeId(data, key);
    }
}
