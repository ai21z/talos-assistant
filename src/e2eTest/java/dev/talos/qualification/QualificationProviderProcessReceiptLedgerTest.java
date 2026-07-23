package dev.talos.qualification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.spi.types.ModelIdentitySnapshot;
import dev.talos.spi.types.ProviderProcessLaunchReceipt;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationProviderProcessReceiptLedgerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, WireKind> RECEIPT_WIRE_KINDS = Map.ofEntries(
            Map.entry("schemaName", WireKind.TEXT),
            Map.entry("schemaVersion", WireKind.INTEGRAL),
            Map.entry("processReceiptId", WireKind.TEXT),
            Map.entry("processId", WireKind.INTEGRAL),
            Map.entry("processStartEpochMilli", WireKind.INTEGRAL),
            Map.entry("launchObservedEpochMilli", WireKind.INTEGRAL),
            Map.entry("readinessObservedEpochMilli", WireKind.INTEGRAL),
            Map.entry("launchNonce", WireKind.TEXT),
            Map.entry("candidateDeclarationSha256", WireKind.TEXT),
            Map.entry("engineExecutableSha256", WireKind.TEXT),
            Map.entry("modelArtifactSha256", WireKind.TEXT),
            Map.entry("launchCommandSha256", WireKind.TEXT),
            Map.entry("launchArgumentSet", WireKind.TEXT),
            Map.entry("effectiveEnvironmentSha256", WireKind.TEXT),
            Map.entry("environmentContract", WireKind.TEXT),
            Map.entry("managedHost", WireKind.TEXT),
            Map.entry("managedPort", WireKind.INTEGRAL),
            Map.entry("prelaunchNoListener", WireKind.BOOLEAN),
            Map.entry("propsResponseSha256", WireKind.TEXT),
            Map.entry("resolvedProfile", WireKind.TEXT),
            Map.entry("servedIdentity", WireKind.TEXT),
            Map.entry("endpointObserverKind", WireKind.TEXT),
            Map.entry("endpointObserverIdentitySha256", WireKind.TEXT),
            Map.entry("endpointOwnershipBoundary", WireKind.TEXT),
            Map.entry("stableEndpointAssumption", WireKind.TEXT),
            Map.entry("contentVerificationBoundary", WireKind.TEXT),
            Map.entry("stableFilesystemAssumption", WireKind.TEXT));

    @Test
    void exactTypedReceiptIsRecomputedBeforeReferencesAreAccepted() {
        ProviderProcessLaunchReceipt receipt = receipt();
        QualificationProviderProcessReceiptLedger.Ledger ledger =
                QualificationProviderProcessReceiptLedger.parse(validTrace(receipt));

        assertTrue(ledger.present());
        assertEquals(receipt, ledger.receiptOptional().orElseThrow());
        assertEquals(1, ledger.references().size());
        assertEquals(receipt.receiptId(), ledger.references().getFirst().processReceiptId());
    }

    @Test
    void missingReceiptRemainsReadableButCannotBecomeCompleteProcessEvidence() {
        ObjectNode root = root(5);
        QualificationProviderProcessReceiptLedger.Ledger ledger =
                QualificationProviderProcessReceiptLedger.parse(root);

        assertFalse(ledger.present());
    }

    @Test
    void historicalTraceSchemasCannotCarryOrReferenceAProcessReceipt() {
        ProviderProcessLaunchReceipt receipt = receipt();
        for (int schemaVersion : List.of(2, 3, 4)) {
            ObjectNode fullReceipt = root(schemaVersion);
            fullReceipt.withArray("events").add(
                    event("PROVIDER_PROCESS_LAUNCH_RECEIPT", receipt.traceData()));
            assertThrows(IllegalArgumentException.class,
                    () -> QualificationProviderProcessReceiptLedger.parse(fullReceipt),
                    "schema=" + schemaVersion + " full receipt");

            ObjectNode reference = root(schemaVersion);
            reference.withArray("events").add(event(
                    "GENERATION_REQUEST_DISPATCHED",
                    Map.of(
                            "generationId", "g-1",
                            "attemptId", "g-1-attempt-1",
                            "transportOperationId", "g-1-attempt-1-transport-1",
                            "processReceiptId", receipt.receiptId())));
            assertThrows(IllegalArgumentException.class,
                    () -> QualificationProviderProcessReceiptLedger.parse(reference),
                    "schema=" + schemaVersion + " receipt reference");
        }
    }

    @Test
    void duplicateLateUnusedOrMismatchedReceiptFailsClosed() {
        ProviderProcessLaunchReceipt receipt = receipt();

        ObjectNode duplicate = validTrace(receipt);
        ((ArrayNode) duplicate.path("events")).insert(2,
                event("PROVIDER_PROCESS_LAUNCH_RECEIPT", receipt.traceData()));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderProcessReceiptLedger.parse(duplicate));

        ObjectNode late = validTrace(receipt);
        ArrayNode lateEvents = (ArrayNode) late.path("events");
        var fullReceipt = lateEvents.remove(1);
        lateEvents.add(fullReceipt);
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderProcessReceiptLedger.parse(late));

        ObjectNode unused = root(5);
        unused.withArray("events").add(
                event("PROVIDER_PROCESS_LAUNCH_RECEIPT", receipt.traceData()));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderProcessReceiptLedger.parse(unused));

        ObjectNode mismatch = validTrace(receipt);
        ((ObjectNode) mismatch.path("events").get(2).path("data"))
                .put("processReceiptId", "pr-" + "0".repeat(64));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderProcessReceiptLedger.parse(mismatch));
    }

    @Test
    void receiptFieldsAreExactAndScalarCoercionIsRejected() {
        ProviderProcessLaunchReceipt receipt = receipt();

        ObjectNode extra = validTrace(receipt);
        ((ObjectNode) extra.path("events").get(1).path("data"))
                .put("enginePath", "C:/secret/llama-server.exe");
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderProcessReceiptLedger.parse(extra));

        ObjectNode coerced = validTrace(receipt);
        ((ObjectNode) coerced.path("events").get(1).path("data"))
                .put("processId", "42");
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderProcessReceiptLedger.parse(coerced));

        for (String field : List.of(
                "endpointObserverKind", "endpointObserverIdentitySha256")) {
            ObjectNode missing = validTrace(receipt);
            ((ObjectNode) missing.path("events").get(1).path("data")).remove(field);
            assertThrows(IllegalArgumentException.class,
                    () -> QualificationProviderProcessReceiptLedger.parse(missing),
                    field + " missing");

            ObjectNode explicitNull = validTrace(receipt);
            ((ObjectNode) explicitNull.path("events").get(1).path("data"))
                    .putNull(field);
            assertThrows(IllegalArgumentException.class,
                    () -> QualificationProviderProcessReceiptLedger.parse(explicitNull),
                    field + " null");

            ObjectNode numeric = validTrace(receipt);
            ((ObjectNode) numeric.path("events").get(1).path("data"))
                    .put(field, 7);
            assertThrows(IllegalArgumentException.class,
                    () -> QualificationProviderProcessReceiptLedger.parse(numeric),
                    field + " numeric");

            ObjectNode container = validTrace(receipt);
            ((ObjectNode) container.path("events").get(1).path("data"))
                    .putObject(field);
            assertThrows(IllegalArgumentException.class,
                    () -> QualificationProviderProcessReceiptLedger.parse(container),
                    field + " object");
        }

        ObjectNode unknownKind = validTrace(receipt);
        ((ObjectNode) unknownKind.path("events").get(1).path("data"))
                .put("endpointObserverKind", "UNDECLARED_OBSERVER");
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderProcessReceiptLedger.parse(unknownKind));

        ObjectNode incompatibleKind = validTrace(receipt);
        ((ObjectNode) incompatibleKind.path("events").get(1).path("data"))
                .put("endpointObserverKind", "LINUX_PROCFS_MANAGED_PROCESS_TREE_V1");
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderProcessReceiptLedger.parse(incompatibleKind));

        ObjectNode uppercaseHash = validTrace(receipt);
        ((ObjectNode) uppercaseHash.path("events").get(1).path("data"))
                .put("endpointObserverIdentitySha256", "A".repeat(64));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderProcessReceiptLedger.parse(uppercaseHash));
    }

    @Test
    void everyReceiptFieldRejectsMissingNullContainerAndWrongScalar() {
        assertEquals(
                ProviderProcessLaunchReceipt.TRACE_FIELDS,
                RECEIPT_WIRE_KINDS.keySet());

        RECEIPT_WIRE_KINDS.forEach((field, kind) -> {
            assertReceiptRejected(field + " missing", data -> data.remove(field));
            assertReceiptRejected(field + " null", data -> data.putNull(field));
            assertReceiptRejected(field + " object", data -> data.putObject(field));
            assertReceiptRejected(
                    field + " wrong scalar",
                    data -> putWrongScalar(data, field, kind));
        });
    }

    @Test
    void receiptNumericBooleanAndChronologySemanticsFailClosed() {
        for (String field : List.of(
                "schemaVersion",
                "processId",
                "processStartEpochMilli",
                "launchObservedEpochMilli",
                "readinessObservedEpochMilli",
                "managedPort")) {
            assertReceiptRejected(
                    field + " fractional",
                    data -> data.put(field, 1.5));
        }
        for (String field : List.of(
                "processId",
                "processStartEpochMilli",
                "launchObservedEpochMilli",
                "readinessObservedEpochMilli")) {
            assertReceiptRejected(field + " zero", data -> data.put(field, 0));
        }
        assertReceiptRejected("port zero", data -> data.put("managedPort", 0));
        assertReceiptRejected("port too high", data -> data.put("managedPort", 65_536));
        assertReceiptRejected(
                "listener preflight false",
                data -> data.put("prelaunchNoListener", false));
        assertReceiptRejected(
                "launch precedes process start",
                data -> data.put("launchObservedEpochMilli", 999));
        assertReceiptRejected(
                "readiness precedes launch",
                data -> data.put("readinessObservedEpochMilli", 1_000));
    }

    @Test
    void receiptIdentifiersHashesEnumsAndPathFreeFieldsFailClosed() {
        assertReceiptRejected(
                "wrong schema name",
                data -> data.put("schemaName", "talos.otherReceipt"));
        assertReceiptRejected(
                "wrong schema version",
                data -> data.put("schemaVersion", 2));
        assertReceiptRejected(
                "uppercase nonce",
                data -> data.put("launchNonce", "A".repeat(32)));
        assertReceiptRejected(
                "short nonce",
                data -> data.put("launchNonce", "a".repeat(31)));
        for (String host : List.of("localhost", "::1")) {
            assertReceiptRejected(
                    "noncanonical host " + host,
                    data -> data.put("managedHost", host));
        }
        assertReceiptRejected(
                "path-bearing profile",
                data -> data.put("resolvedProfile", "C:/secret/model.gguf"));

        for (String field : List.of(
                "candidateDeclarationSha256",
                "engineExecutableSha256",
                "modelArtifactSha256",
                "launchCommandSha256",
                "effectiveEnvironmentSha256",
                "propsResponseSha256",
                "endpointObserverIdentitySha256")) {
            assertReceiptRejected(
                    field + " uppercase",
                    data -> data.put(field, "A".repeat(64)));
        }
        for (String field : List.of(
                "launchArgumentSet",
                "environmentContract",
                "servedIdentity",
                "endpointObserverKind",
                "endpointOwnershipBoundary",
                "stableEndpointAssumption",
                "contentVerificationBoundary",
                "stableFilesystemAssumption")) {
            assertReceiptRejected(
                    field + " unknown",
                    data -> data.put(field, "UNDECLARED"));
        }
        assertReceiptRejected(
                "valid-format mismatched receipt ID",
                data -> data.put("processReceiptId", "pr-" + "0".repeat(64)));
        assertReceiptRejected(
                "uppercase receipt ID",
                data -> data.put("processReceiptId", "pr-" + "A".repeat(64)));
    }

    @Test
    void duplicateRawReceiptKeyIsRejectedDuringStrictJsonReading() throws Exception {
        String raw = MAPPER.writeValueAsString(validTrace(receipt()));
        String needle = "\"processId\":42";
        int first = raw.indexOf(needle);

        assertTrue(first >= 0);
        assertEquals(first, raw.lastIndexOf(needle));

        byte[] duplicate = raw.replace(
                        needle, needle + "," + needle)
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> QualificationJson.readTree(duplicate));
    }

    @Test
    void nonTextTraceEventTypesAreRejectedWithoutCoercion() {
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                event -> event.remove("type"),
                event -> event.putNull("type"),
                event -> event.put("type", 7),
                event -> event.putObject("type"))) {
            ObjectNode trace = validTrace(receipt());
            mutation.accept((ObjectNode) trace.path("events").get(1));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> QualificationProviderProcessReceiptLedger.parse(trace));
        }
    }

    @Test
    void receiptMustMatchOnePriorTypedModelIdentityObservation() {
        ProviderProcessLaunchReceipt receipt = receipt();
        for (String field : Set.of(
                "propsResponseSha256", "engineExecutableIdentity", "resolvedProfile",
                "artifactState", "servedIdentityState", "resolution", "propsState")) {
            ObjectNode changed = validTrace(receipt);
            ((ObjectNode) changed.path("events").get(0).path("data"))
                    .put(field, field.endsWith("Sha256") || field.endsWith("Identity")
                            ? "9".repeat(64) : "DIFFERENT");
            assertThrows(IllegalArgumentException.class,
                    () -> QualificationProviderProcessReceiptLedger.parse(changed), field);
        }

        ObjectNode missing = validTrace(receipt);
        ((ArrayNode) missing.path("events")).remove(0);
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderProcessReceiptLedger.parse(missing));

        ObjectNode late = validTrace(receipt);
        ArrayNode events = (ArrayNode) late.path("events");
        JsonNode identity = events.remove(0);
        events.add(identity);
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderProcessReceiptLedger.parse(late));

        ObjectNode unknown = validTrace(receipt);
        ((ObjectNode) unknown.path("events").get(0).path("data"))
                .put("modelPath", "C:/secret/model.gguf");
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderProcessReceiptLedger.parse(unknown));

        ObjectNode embeddedPath = validTrace(receipt);
        ((ObjectNode) embeddedPath.path("events").get(0).path("data"))
                .put("engineBuildInfo", "build=C:/secret/toolchain");
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderProcessReceiptLedger.parse(embeddedPath));
    }

    @Test
    void generationTerminalUsesTheActualFinalPreDispatchRetryAttempt() {
        ProviderProcessLaunchReceipt receipt = receipt();
        assertTrue(QualificationProviderProcessReceiptLedger.parse(
                retryAfterDispatchTrace(
                        receipt,
                        "GENERATION_ATTEMPT_FAILED",
                        "GENERATION_FAILED",
                        false,
                        false)).present());
        assertTrue(QualificationProviderProcessReceiptLedger.parse(
                retryAfterDispatchTrace(
                        receipt,
                        "GENERATION_ATTEMPT_ABANDONED",
                        "GENERATION_ABANDONED",
                        false,
                        false)).present());
    }

    @Test
    void preDispatchRetryAndItsGenerationTerminalRejectStaleProcessReferences() {
        ProviderProcessLaunchReceipt receipt = receipt();
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderProcessReceiptLedger.parse(
                        retryAfterDispatchTrace(
                                receipt,
                                "GENERATION_ATTEMPT_FAILED",
                                "GENERATION_FAILED",
                                true,
                                false)));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderProcessReceiptLedger.parse(
                        retryAfterDispatchTrace(
                                receipt,
                                "GENERATION_ATTEMPT_ABANDONED",
                                "GENERATION_ABANDONED",
                                false,
                                true)));

        ObjectNode transportOnly = retryAfterDispatchTrace(
                receipt,
                "GENERATION_ATTEMPT_FAILED",
                "GENERATION_FAILED",
                false,
                false);
        ((ObjectNode) transportOnly.path("events").get(4).path("data"))
                .put("transportOperationId", "g-1-attempt-1-transport-1");
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderProcessReceiptLedger.parse(transportOnly));
    }

    @Test
    void verificationLaunchCannotSatisfyTheScoreBearingTrialBoundary() {
        assertTrue(QualificationPreResultEvidence.scoreBearingLaunchReceipt(receipt()));
        assertFalse(QualificationPreResultEvidence.scoreBearingLaunchReceipt(receipt(
                ProviderProcessLaunchReceipt.LaunchArgumentSet.VERIFICATION)));
    }

    @Test
    void everyDispatchedResponseAbortTerminalProposalAndLifecycleRequiresExactReceipt() {
        ProviderProcessLaunchReceipt receipt = receipt();
        for (String type : Set.of(
                "GENERATION_RESPONSE_CAPTURED",
                "GENERATION_ATTEMPT_FAILED",
                "GENERATION_ATTEMPT_ABANDONED",
                "GENERATION_FAILED",
                "GENERATION_ABANDONED",
                "GENERATION_COMPLETED",
                "PROVIDER_TOOL_PROPOSAL_CAPTURED",
                "PROVIDER_TRANSPORT_LIFECYCLE")) {
            ObjectNode trace = validTrace(receipt);
            ObjectNode data = MAPPER.createObjectNode();
            data.put("generationId", "g-1");
            if (type.startsWith("GENERATION_ATTEMPT")
                    || "GENERATION_RESPONSE_CAPTURED".equals(type)
                    || "PROVIDER_TOOL_PROPOSAL_CAPTURED".equals(type)
                    || "PROVIDER_TRANSPORT_LIFECYCLE".equals(type)) {
                data.put("attemptId", "g-1-attempt-1");
            }
            if ("GENERATION_COMPLETED".equals(type)) {
                data.put("selectedAttemptId", "g-1-attempt-1");
            }
            data.put("transportOperationId", "g-1-attempt-1-transport-1");
            trace.withArray("events").add(event(type, data));

            assertThrows(IllegalArgumentException.class,
                    () -> QualificationProviderProcessReceiptLedger.parse(trace), type);
        }

        ObjectNode wrongOperation = validTrace(receipt);
        ObjectNode data = MAPPER.createObjectNode();
        data.put("generationId", "g-1");
        data.put("attemptId", "g-1-attempt-1");
        data.put("transportOperationId", "g-1-attempt-1-transport-9");
        data.put("processReceiptId", receipt.receiptId());
        wrongOperation.withArray("events").add(
                event("GENERATION_RESPONSE_CAPTURED", data));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderProcessReceiptLedger.parse(wrongOperation));
    }

    private static ObjectNode validTrace(ProviderProcessLaunchReceipt receipt) {
        ObjectNode root = root(5);
        ArrayNode events = root.withArray("events");
        events.add(event("MODEL_IDENTITY_RESOLVED", modelIdentity(receipt)));
        events.add(event("PROVIDER_PROCESS_LAUNCH_RECEIPT", receipt.traceData()));
        events.add(event("GENERATION_REQUEST_DISPATCHED", Map.of(
                "generationId", "g-1",
                "attemptId", "g-1-attempt-1",
                "transportOperationId", "g-1-attempt-1-transport-1",
                "processReceiptId", receipt.receiptId())));
        return root;
    }

    private static ObjectNode receiptData(ObjectNode trace) {
        JsonNode data = trace.path("events").get(1).path("data");
        if (!(data instanceof ObjectNode object)) {
            throw new AssertionError("receipt fixture lacks object data");
        }
        return object;
    }

    private static void assertReceiptRejected(
            String label,
            Consumer<ObjectNode> mutation) {
        ObjectNode trace = validTrace(receipt());
        mutation.accept(receiptData(trace));
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationProviderProcessReceiptLedger.parse(trace),
                label);
    }

    private static void putWrongScalar(
            ObjectNode data,
            String field,
            WireKind kind) {
        switch (kind) {
            case TEXT -> data.put(field, 7);
            case INTEGRAL -> data.put(field, "7");
            case BOOLEAN -> data.put(field, "true");
        }
    }

    private static ObjectNode retryAfterDispatchTrace(
            ProviderProcessLaunchReceipt receipt,
            String attemptTerminalType,
            String generationTerminalType,
            boolean staleAttemptReference,
            boolean staleGenerationReference) {
        ObjectNode trace = validTrace(receipt);
        ArrayNode events = trace.withArray("events");
        events.add(event("GENERATION_ATTEMPT_STARTED", Map.of(
                "generationId", "g-1",
                "attemptId", "g-1-attempt-2")));
        Map<String, Object> attemptTerminal = new java.util.LinkedHashMap<>(Map.of(
                "generationId", "g-1",
                "attemptId", "g-1-attempt-2"));
        if (staleAttemptReference) {
            attemptTerminal.put("transportOperationId", "g-1-attempt-1-transport-1");
            attemptTerminal.put("processReceiptId", receipt.receiptId());
        }
        events.add(event(attemptTerminalType, attemptTerminal));
        Map<String, Object> generationTerminal = new java.util.LinkedHashMap<>(Map.of(
                "generationId", "g-1"));
        if (staleGenerationReference) {
            generationTerminal.put("transportOperationId", "g-1-attempt-1-transport-1");
            generationTerminal.put("processReceiptId", receipt.receiptId());
        }
        events.add(event(generationTerminalType, generationTerminal));
        return trace;
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

    private static ObjectNode root(int schemaVersion) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", schemaVersion);
        root.putArray("events");
        return root;
    }

    private static ObjectNode event(String type, Object data) {
        ObjectNode event = MAPPER.createObjectNode();
        event.put("type", type);
        event.set("data", MAPPER.valueToTree(data));
        return event;
    }

    private static ProviderProcessLaunchReceipt receipt() {
        return receipt(ProviderProcessLaunchReceipt.LaunchArgumentSet.STANDARD);
    }

    private static ProviderProcessLaunchReceipt receipt(
            ProviderProcessLaunchReceipt.LaunchArgumentSet argumentSet) {
        return new ProviderProcessLaunchReceipt(
                42,
                1_000,
                1_001,
                1_002,
                "0123456789abcdef0123456789abcdef",
                "1".repeat(64),
                "2".repeat(64),
                "3".repeat(64),
                "4".repeat(64),
                argumentSet,
                "5".repeat(64),
                ProviderProcessLaunchReceipt.EnvironmentContract.QUALIFICATION_RELEVANT_ALLOWLIST_V1,
                "127.0.0.1",
                8080,
                true,
                "6".repeat(64),
                "candidate",
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

    private enum WireKind {
        TEXT,
        INTEGRAL,
        BOOLEAN
    }
}
