package dev.talos.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskResolution;
import dev.talos.runtime.workspace.WorkspaceOperationAuthorityPlan;
import dev.talos.runtime.workspace.WorkspaceOperationPlanEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class T1205WorkspaceOperationPersistenceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COPY_REQUEST = "Copy source.txt to dest.txt.";

    @Test
    void exactTypedPlanRoundTripsAcrossAllFiveOperationFamilies(
            @TempDir Path directory
    ) {
        List<String> requests = List.of(
                COPY_REQUEST,
                "Move source.txt to dest.txt.",
                "Rename source.txt to renamed.txt.",
                "Create a directory named assets.",
                "Delete source.txt.");

        int ordinal = 0;
        for (String request : requests) {
            String sessionId = "t1205-family-roundtrip-" + ordinal++;
            TaskResolution resolution = TaskContractResolver
                    .resolveFromUserRequest(request);
            WorkspaceOperationPlanEvidence expected =
                    WorkspaceOperationPlanEvidence.from(
                            resolution.workspaceOperationPlan());
            JsonSessionStore store = new JsonSessionStore(directory);

            try (SessionWriterLease writer =
                         store.acquireSessionWriter(sessionId)) {
                writer.appendTurn(
                        sessionId,
                        turnRecord(1, request, policy(resolution)));
            }

            List<TurnRecord> loaded = store.loadTurns(sessionId);
            assertEquals(1, loaded.size(), request);
            assertEquals(request, loaded.getFirst().userInput(), request);
            assertEquals(
                    expected,
                    loaded.getFirst().policyTrace().workspaceOperationPlan(),
                    request);
        }
    }

    @Test
    void confirmedReplayCannotSpliceOneOperationOutOfABlockedPlan(
            @TempDir Path directory
    ) throws Exception {
        String request = "Copy a.md to b.md, then delete c.md.";
        TaskResolution raw = TaskContractResolver.resolveFromUserRequest(request);
        assertEquals(
                WorkspaceOperationAuthorityPlan.State.BLOCKED,
                raw.workspaceOperationPlan().state());
        assertEquals(
                2,
                raw.sequentialMutationPlan()
                        .workspaceOperationObligations().size());

        String sessionId = "t1205-replay-splice";
        JsonSessionStore store = new JsonSessionStore(directory);
        try (SessionWriterLease writer = store.acquireSessionWriter(sessionId)) {
            writer.appendTurn(
                    sessionId,
                    turnRecord(1, request, policy(raw)));
        }

        TaskResolution replay = TaskResolution.confirmedReplay(raw);
        assertEquals(
                WorkspaceOperationAuthorityPlan.State.BLOCKED,
                replay.workspaceOperationPlan().state());
        WorkspaceOperationAuthorityPlan forged =
                WorkspaceOperationAuthorityPlan.authorized(
                        request,
                        raw.sequentialMutationPlan()
                                .workspaceOperationObligations().getFirst());
        assertThrows(
                IllegalArgumentException.class,
                () -> policy(replay).withWorkspaceOperationPlan(
                        WorkspaceOperationPlanEvidence.from(forged)),
                "A confirmed replay must reject a spliced workspace operation "
                        + "before persistence receives the forged policy trace.");
        assertEquals(1, store.loadTurns(sessionId).size());

        try (SessionWriterLease writer = store.acquireSessionWriter(sessionId)) {
            writer.appendTurn(
                    sessionId,
                    turnRecord(2, "yes", policy(replay)));
        }
        List<TurnRecord> loaded = store.loadTurns(sessionId);
        assertEquals(2, loaded.size());
        assertEquals(
                "BLOCKED",
                loaded.getLast().policyTrace().workspaceOperationPlan().state());
        assertTrue(loaded.getLast().policyTrace()
                .workspaceOperationPlan().operations().isEmpty());
    }

    @Test
    void redactedCurrentRequestCannotSpliceOneOperationOutOfABlockedPlan(
            @TempDir Path directory
    ) throws Exception {
        String request = "Copy a.md to b.md, then delete c.md.";
        TaskResolution raw = TaskContractResolver.resolveFromUserRequest(
                request);
        assertEquals(
                WorkspaceOperationAuthorityPlan.State.BLOCKED,
                raw.workspaceOperationPlan().state());
        ObjectNode root = persistedRoot(
                directory,
                "redacted-splice-source",
                request,
                raw);

        WorkspaceOperationAuthorityPlan forged =
                WorkspaceOperationAuthorityPlan.authorized(
                        request,
                        raw.sequentialMutationPlan()
                                .workspaceOperationObligations().getFirst());
        policyNode(root).set(
                "workspaceOperationPlan",
                JSON.valueToTree(WorkspaceOperationPlanEvidence.from(forged)));
        for (JsonNode target : policyNode(root).withArray("rolefulTargets")) {
            ObjectNode object = (ObjectNode) target;
            object.put("startOffset", -1);
            object.put("authorityEndOffset", -1);
            object.put("endOffset", -1);
        }
        for (String field : List.of(
                "lexicalDecisions",
                "lexicalRegions")) {
            for (JsonNode evidence : policyNode(root).withArray(field)) {
                ((ObjectNode) evidence).put(
                        "evidenceState",
                        "REDACTED_HASH_ONLY");
            }
        }

        assertRejected(directory, "redacted-current-splice", root);
    }

    @Test
    void planEvidenceCannotSpliceAcrossEqualLengthCanonicalRequests() {
        TaskResolution first = TaskContractResolver.resolveFromUserRequest(
                "Copy a.txt to b.txt.");
        TaskResolution second = TaskContractResolver.resolveFromUserRequest(
                "Copy c.txt to d.txt.");
        assertEquals(
                first.workspaceOperationPlan().canonicalRequestUtf8Bytes(),
                second.workspaceOperationPlan().canonicalRequestUtf8Bytes());

        assertThrows(
                IllegalArgumentException.class,
                () -> policy(first).withWorkspaceOperationPlan(
                        WorkspaceOperationPlanEvidence.from(
                                second.workspaceOperationPlan())));
    }

    @Test
    void observedPlanRequiresItsLexicalEnvelope() {
        WorkspaceOperationPlanEvidence observed =
                WorkspaceOperationPlanEvidence.from(
                        TaskContractResolver.resolveFromUserRequest(COPY_REQUEST)
                                .workspaceOperationPlan());

        assertThrows(
                IllegalArgumentException.class,
                () -> TurnPolicyTrace.empty()
                        .withWorkspaceOperationPlan(observed));
    }

    @Test
    void nestedWorkspaceEvidenceRejectsUnknownMembersAndWrongTypes(
            @TempDir Path directory
    ) throws Exception {
        ObjectNode root = persistedCopyRoot(directory, "nested-schema-source");

        ObjectNode unknownPlan = root.deepCopy();
        workspacePlan(unknownPlan).put("unknown", "x");
        assertRejected(directory, "nested-unknown-plan", unknownPlan);

        ObjectNode unknownOperation = root.deepCopy();
        firstOperation(unknownOperation).put("unknown", "x");
        assertRejected(directory, "nested-unknown-operation", unknownOperation);

        ObjectNode unknownOperand = root.deepCopy();
        firstOperand(unknownOperand).put("unknown", "x");
        assertRejected(directory, "nested-unknown-operand", unknownOperand);

        ObjectNode wrongPlanType = root.deepCopy();
        workspacePlan(wrongPlanType).put("schemaVersion", "1");
        assertRejected(directory, "nested-wrong-plan-type", wrongPlanType);

        ObjectNode wrongOperationType = root.deepCopy();
        firstOperation(wrongOperationType).put("ordinal", "0");
        assertRejected(directory, "nested-wrong-operation-type", wrongOperationType);

        ObjectNode wrongOperandType = root.deepCopy();
        firstOperand(wrongOperandType).put("valueStartOffset", "0");
        assertRejected(directory, "nested-wrong-operand-type", wrongOperandType);
    }

    @Test
    void nestedWorkspaceEvidenceRejectsCardinalityAndIdentityTampering(
            @TempDir Path directory
    ) throws Exception {
        ObjectNode root = persistedCopyRoot(directory, "nested-identity-source");

        ObjectNode duplicateOperation = root.deepCopy();
        ArrayNode operations = workspacePlan(duplicateOperation)
                .withArray("operations");
        operations.add(operations.get(0).deepCopy());
        assertRejected(directory, "nested-duplicate-operation", duplicateOperation);

        ObjectNode extraOperand = root.deepCopy();
        ArrayNode operands = firstOperation(extraOperand).withArray("operands");
        operands.add(operands.get(0).deepCopy());
        assertRejected(directory, "nested-extra-operand", extraOperand);

        ObjectNode wrongState = root.deepCopy();
        workspacePlan(wrongState).put("state", "NONE");
        assertRejected(directory, "nested-wrong-state", wrongState);

        ObjectNode wrongPlanId = root.deepCopy();
        workspacePlan(wrongPlanId).put("planId", "0".repeat(64));
        assertRejected(directory, "nested-wrong-plan-id", wrongPlanId);

        ObjectNode wrongOperationId = root.deepCopy();
        firstOperation(wrongOperationId)
                .put("operationId", "f".repeat(64));
        assertRejected(directory, "nested-wrong-operation-id", wrongOperationId);

        ObjectNode wrongRole = root.deepCopy();
        firstOperand(wrongRole).put("role", "TARGET");
        assertRejected(directory, "nested-wrong-role", wrongRole);

        ObjectNode wrongPolicy = root.deepCopy();
        firstOperation(wrongPolicy)
                .put("recursivePolicy", "NOT_APPLICABLE");
        assertRejected(directory, "nested-wrong-policy", wrongPolicy);

        ObjectNode wrongTool = root.deepCopy();
        firstOperation(wrongTool).put("toolName", "talos.move_path");
        assertRejected(directory, "nested-wrong-tool", wrongTool);
    }

    @Test
    void legacyPolicyLoadsAsHashBoundNoneNotExecutableAuthority(
            @TempDir Path directory
    ) throws Exception {
        ObjectNode legacy = persistedCopyRoot(directory, "legacy-source")
                .deepCopy();
        ObjectNode policy = policyNode(legacy);
        policy.remove("lexicalEvidenceSchemaVersion");
        policy.remove("lexicalDecisions");
        policy.remove("lexicalRegions");
        policy.remove("workspaceOperationPlan");
        writeTurn(directory, "legacy-none", legacy);

        List<TurnRecord> loaded = new JsonSessionStore(directory)
                .loadTurns("legacy-none");

        assertEquals(1, loaded.size());
        assertEquals(
                "NONE",
                loaded.getFirst().policyTrace().workspaceOperationPlan().state());
        assertTrue(loaded.getFirst().policyTrace()
                .workspaceOperationPlan().operations().isEmpty());
    }

    private static TurnPolicyTrace policy(TaskResolution resolution) {
        List<String> tools = resolution.workspaceOperationPlan()
                .authorizedOperations().stream()
                .map(operation -> operation.toolName())
                .toList();
        if (tools.isEmpty()) {
            tools = List.of("talos.copy_path", "talos.delete_path");
        }
        return TurnPolicyTrace.from(
                resolution,
                "APPLY",
                tools,
                tools);
    }

    private static TurnRecord turnRecord(
            int number,
            String request,
            TurnPolicyTrace policy
    ) {
        return new TurnRecord(
                number,
                Instant.parse("2026-08-02T00:00:00Z").plusSeconds(number),
                1,
                request,
                "partial",
                List.of(),
                0,
                0,
                0,
                "",
                "ok",
                policy);
    }

    private static ObjectNode persistedCopyRoot(
            Path directory,
            String sessionId
    ) throws Exception {
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(COPY_REQUEST);
        return persistedRoot(
                directory,
                sessionId,
                COPY_REQUEST,
                resolution);
    }

    private static ObjectNode persistedRoot(
            Path directory,
            String sessionId,
            String request,
            TaskResolution resolution
    ) throws Exception {
        JsonSessionStore store = new JsonSessionStore(directory);
        try (SessionWriterLease writer = store.acquireSessionWriter(sessionId)) {
            writer.appendTurn(
                    sessionId,
                    turnRecord(1, request, policy(resolution)));
        }
        JsonNode parsed = JSON.readTree(Files.readString(
                directory.resolve(sessionId + ".turns.jsonl")).strip());
        assertTrue(parsed.isObject());
        return (ObjectNode) parsed;
    }

    private static ObjectNode policyNode(ObjectNode root) {
        return (ObjectNode) root.get("policyTrace");
    }

    private static ObjectNode workspacePlan(ObjectNode root) {
        return (ObjectNode) policyNode(root).get("workspaceOperationPlan");
    }

    private static ObjectNode firstOperation(ObjectNode root) {
        return (ObjectNode) workspacePlan(root).withArray("operations").get(0);
    }

    private static ObjectNode firstOperand(ObjectNode root) {
        return (ObjectNode) firstOperation(root).withArray("operands").get(0);
    }

    private static void assertRejected(
            Path directory,
            String sessionId,
            ObjectNode root
    ) throws Exception {
        writeTurn(directory, sessionId, root);
        assertTrue(
                new JsonSessionStore(directory).loadTurns(sessionId).isEmpty(),
                sessionId);
    }

    private static void writeTurn(
            Path directory,
            String sessionId,
            JsonNode root
    ) throws Exception {
        Files.writeString(
                directory.resolve(sessionId + ".turns.jsonl"),
                JSON.writeValueAsString(root) + "\n");
    }
}
