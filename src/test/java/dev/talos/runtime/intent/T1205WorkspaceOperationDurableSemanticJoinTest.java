package dev.talos.runtime.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.SessionWriterLease;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.TurnRecord;
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

import static org.junit.jupiter.api.Assertions.assertTrue;

class T1205WorkspaceOperationDurableSemanticJoinTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REQUEST = "Copy source.txt to dest.txt.";

    @Test
    void forgedOperationKindIsRejectedForExactAndRedactedEvidence(
            @TempDir Path directory
    ) throws Exception {
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(REQUEST);
        WorkspaceOperationObligation original = resolution
                .workspaceOperationPlan()
                .authorizedOperations()
                .getFirst();
        WorkspaceOperationObligation forged = WorkspaceOperationObligation.create(
                original.owner(),
                WorkspaceOperationObligation.Kind.MOVE_PATH,
                original.operands(),
                original.overwritePolicy(),
                WorkspaceOperationObligation.RecursivePolicy.NOT_APPLICABLE,
                original.parentPolicy(),
                REQUEST,
                original.actionStartOffset(),
                original.actionEndOffset(),
                original.sourceStartOffset(),
                original.sourceEndOffset());
        WorkspaceOperationPlanEvidence forgedEvidence =
                WorkspaceOperationPlanEvidence.from(
                        WorkspaceOperationAuthorityPlan.authorized(
                                REQUEST,
                                forged));

        ObjectNode exact = persistedRoot(
                directory,
                "t1205-semantic-source",
                resolution);
        policyNode(exact).set(
                "workspaceOperationPlan",
                JSON.valueToTree(forgedEvidence));
        assertRejected(directory, "t1205-semantic-exact", exact);

        ObjectNode redacted = exact.deepCopy();
        for (JsonNode target : policyNode(redacted).withArray("rolefulTargets")) {
            ObjectNode object = (ObjectNode) target;
            object.put("startOffset", -1);
            object.put("authorityEndOffset", -1);
            object.put("endOffset", -1);
        }
        for (String field : List.of("lexicalDecisions", "lexicalRegions")) {
            for (JsonNode evidence : policyNode(redacted).withArray(field)) {
                ((ObjectNode) evidence).put(
                        "evidenceState",
                        "REDACTED_HASH_ONLY");
            }
        }
        assertRejected(directory, "t1205-semantic-redacted", redacted);
    }

    private static ObjectNode persistedRoot(
            Path directory,
            String sessionId,
            TaskResolution resolution
    ) throws Exception {
        JsonSessionStore store = new JsonSessionStore(directory);
        TurnPolicyTrace policy = TurnPolicyTrace.from(
                resolution,
                "APPLY",
                List.of("talos.copy_path"),
                List.of("talos.copy_path"));
        try (SessionWriterLease writer = store.acquireSessionWriter(sessionId)) {
            writer.appendTurn(
                    sessionId,
                    new TurnRecord(
                            1,
                            Instant.parse("2026-08-02T00:00:00Z"),
                            1,
                            REQUEST,
                            "partial",
                            List.of(),
                            0,
                            0,
                            0,
                            "",
                            "ok",
                            policy));
        }
        JsonNode parsed = JSON.readTree(Files.readString(
                directory.resolve(sessionId + ".turns.jsonl")).strip());
        return (ObjectNode) parsed;
    }

    private static ObjectNode policyNode(ObjectNode root) {
        return (ObjectNode) root.get("policyTrace");
    }

    private static void assertRejected(
            Path directory,
            String sessionId,
            ObjectNode root
    ) throws Exception {
        Files.writeString(
                directory.resolve(sessionId + ".turns.jsonl"),
                JSON.writeValueAsString(root) + "\n");
        assertTrue(
                new JsonSessionStore(directory).loadTurns(sessionId).isEmpty(),
                sessionId);
    }
}
