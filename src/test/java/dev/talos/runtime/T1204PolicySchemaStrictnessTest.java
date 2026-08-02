package dev.talos.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskResolution;
import dev.talos.runtime.trace.TurnTraceEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Strict current-schema and bounded-payload checks for T1204 evidence. */
class T1204PolicySchemaStrictnessTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REQUEST =
            "Read style.css and edit index.html, then update style.css.";
    private static final List<String> POLICY_KEYS = List.of(
            "taskType",
            "mutationAllowed",
            "mutationRequested",
            "verificationRequired",
            "expectedTargets",
            "forbiddenTargets",
            "initialPhase",
            "finalPhase",
            "nativeTools",
            "promptTools",
            "blocks",
            "classificationReason",
            "rolefulTargets",
            "lexicalEvidenceSchemaVersion",
            "lexicalDecisions",
            "lexicalRegions");
    private static final List<String> POLICY_STRING_KEYS = List.of(
            "taskType",
            "initialPhase",
            "finalPhase",
            "classificationReason");
    private static final List<String> POLICY_BOOLEAN_KEYS = List.of(
            "mutationAllowed",
            "mutationRequested",
            "verificationRequired");
    private static final List<String> POLICY_STRING_LIST_KEYS = List.of(
            "expectedTargets",
            "forbiddenTargets",
            "nativeTools",
            "promptTools",
            "blocks");
    private static final List<String> ROLEFUL_TARGET_KEYS = List.of(
            "path",
            "role",
            "source",
            "reason",
            "sourceText",
            "confidence",
            "startOffset",
            "authorityEndOffset",
            "endOffset");

    @Test
    void currentPolicyRejectsEveryMissingKeyAndAnyUnknownKey(
            @TempDir Path directory
    ) throws Exception {
        ObjectNode valid = persistedCurrentRoot(directory, "policy-source");

        for (int index = 0; index < POLICY_KEYS.size(); index++) {
            String key = POLICY_KEYS.get(index);
            ObjectNode missing = valid.deepCopy();
            assertNotNull(policy(missing).remove(key), key);
            assertRejected(directory, "policy-missing-" + index, missing);
        }

        ObjectNode unknown = valid.deepCopy();
        policy(unknown).put("unrecognizedPolicyMember", "must-not-coerce");
        assertRejected(directory, "policy-unknown", unknown);
    }

    @Test
    void currentPolicyRejectsWrongExactStringBooleanAndListTypes(
            @TempDir Path directory
    ) throws Exception {
        ObjectNode valid = persistedCurrentRoot(directory, "types-source");

        for (int index = 0; index < POLICY_STRING_KEYS.size(); index++) {
            ObjectNode wrong = valid.deepCopy();
            policy(wrong).put(POLICY_STRING_KEYS.get(index), true);
            assertRejected(directory, "wrong-string-" + index, wrong);
        }
        for (int index = 0; index < POLICY_BOOLEAN_KEYS.size(); index++) {
            ObjectNode wrong = valid.deepCopy();
            policy(wrong).put(POLICY_BOOLEAN_KEYS.get(index), "true");
            assertRejected(directory, "wrong-boolean-" + index, wrong);
        }
        for (int index = 0; index < POLICY_STRING_LIST_KEYS.size(); index++) {
            String key = POLICY_STRING_LIST_KEYS.get(index);

            ObjectNode wrongContainer = valid.deepCopy();
            policy(wrongContainer).put(key, "not-an-array");
            assertRejected(
                    directory,
                    "wrong-list-container-" + index,
                    wrongContainer);

            ObjectNode wrongElement = valid.deepCopy();
            policy(wrongElement).set(
                    key,
                    JSON.createArrayNode().add(7));
            assertRejected(
                    directory,
                    "wrong-list-element-" + index,
                    wrongElement);
        }
    }

    @Test
    void currentRolefulTargetsRejectEveryMissingKeyAndAnyUnknownKey(
            @TempDir Path directory
    ) throws Exception {
        ObjectNode valid = persistedCurrentRoot(directory, "roles-source");
        firstRolefulTarget(valid);

        for (int index = 0; index < ROLEFUL_TARGET_KEYS.size(); index++) {
            String key = ROLEFUL_TARGET_KEYS.get(index);
            ObjectNode missing = valid.deepCopy();
            ObjectNode target = firstRolefulTarget(missing);
            assertNotNull(target.remove(key), key);
            assertRejected(directory, "role-missing-" + index, missing);
        }

        ObjectNode unknown = valid.deepCopy();
        firstRolefulTarget(unknown).put("unrecognizedRoleMember", true);
        assertRejected(directory, "role-unknown", unknown);
    }

    @Test
    void rolefulTargetsAndEveryPolicyStringListAreBoundedAt4096(
            @TempDir Path directory
    ) throws Exception {
        ObjectNode valid = persistedCurrentRoot(directory, "caps-source");
        ObjectNode validTarget = firstRolefulTarget(valid);

        ObjectNode oversizedTargets = valid.deepCopy();
        ArrayNode targets = JSON.createArrayNode();
        for (int index = 0; index < 4_097; index++) {
            targets.add(validTarget.deepCopy());
        }
        policy(oversizedTargets).set("rolefulTargets", targets);
        assertRejected(directory, "role-cap", oversizedTargets);

        for (int listIndex = 0;
                listIndex < POLICY_STRING_LIST_KEYS.size();
                listIndex++) {
            ObjectNode oversizedList = valid.deepCopy();
            ArrayNode entries = JSON.createArrayNode();
            for (int index = 0; index < 4_097; index++) {
                entries.add("bounded-entry");
            }
            policy(oversizedList).set(
                    POLICY_STRING_LIST_KEYS.get(listIndex),
                    entries);
            assertRejected(
                    directory,
                    "string-list-cap-" + listIndex,
                    oversizedList);
        }
    }

    @Test
    void combinedLexicalRowCapAppliesBeforeEitherArrayReachesItsOwnCap(
            @TempDir Path directory
    ) throws Exception {
        ObjectNode oversized = persistedCurrentRoot(
                directory,
                "combined-cap-source");
        ArrayNode decisions = JSON.createArrayNode();
        ArrayNode regions = JSON.createArrayNode();
        for (int index = 0; index < 2_048; index++) {
            decisions.addNull();
        }
        for (int index = 0; index < 2_049; index++) {
            regions.addNull();
        }
        assertTrue(decisions.size() <= 4_096);
        assertTrue(regions.size() <= 4_096);
        assertEquals(4_097, decisions.size() + regions.size());
        policy(oversized).set("lexicalDecisions", decisions);
        policy(oversized).set("lexicalRegions", regions);

        assertRejected(directory, "combined-cap", oversized);
    }

    @Test
    void unversionedPolicyWithoutLexicalArraysRemainsReadable(
            @TempDir Path directory
    ) throws Exception {
        ObjectNode legacy = persistedCurrentRoot(directory, "legacy-source");
        ObjectNode legacyPolicy = policy(legacy);
        legacyPolicy.remove("lexicalEvidenceSchemaVersion");
        legacyPolicy.remove("lexicalDecisions");
        legacyPolicy.remove("lexicalRegions");
        legacyPolicy.set("rolefulTargets", JSON.createArrayNode());
        writeTurnJson(directory, "legacy-control", legacy);

        List<TurnRecord> loaded = new JsonSessionStore(directory)
                .loadTurns("legacy-control");
        assertEquals(1, loaded.size());
        assertEquals("FILE_EDIT", loaded.getFirst().policyTrace().taskType());
        assertTrue(loaded.getFirst().policyTrace().lexicalDecisions().isEmpty());
        assertTrue(loaded.getFirst().policyTrace().lexicalRegions().isEmpty());
    }

    @Test
    void traceEventRejectsArraysMutableObjectsAndNonFiniteNumbers() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TurnTraceEvent.simple(
                        "INVALID",
                        "2026-08-01T00:00:00Z",
                        Map.of("array", new Object[] {"mutable"})));
        assertThrows(
                IllegalArgumentException.class,
                () -> TurnTraceEvent.simple(
                        "INVALID",
                        "2026-08-01T00:00:00Z",
                        Map.of("object", new StringBuilder("mutable"))));
        for (Number number : List.of(
                Double.NaN,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                Float.NaN,
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TurnTraceEvent.simple(
                            "INVALID",
                            "2026-08-01T00:00:00Z",
                            Map.of("number", number)),
                    number.toString());
        }
    }

    private static ObjectNode persistedCurrentRoot(
            Path directory,
            String sessionId
    ) throws Exception {
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(REQUEST);
        TurnPolicyTrace trace = TurnPolicyTrace.from(
                resolution,
                "APPLY",
                List.of("talos.read_file", "talos.edit_file"),
                List.of("tool_use:read_file", "tool_use:edit_file"));
        assertFalse(trace.lexicalDecisions().isEmpty());
        assertFalse(trace.rolefulTargets().isEmpty());

        JsonSessionStore store = new JsonSessionStore(directory);
        try (SessionWriterLease writer =
                store.acquireSessionWriter(sessionId)) {
            writer.appendTurn(sessionId, new TurnRecord(
                    1,
                    Instant.parse("2026-08-01T00:00:00Z"),
                    1,
                    REQUEST,
                    "partial",
                    List.of(),
                    0,
                    0,
                    0,
                    "",
                    "ok",
                    trace));
        }
        assertEquals(1, store.loadTurns(sessionId).size());
        String json = Files.readString(
                directory.resolve(sessionId + ".turns.jsonl")).strip();
        JsonNode parsed = JSON.readTree(json);
        assertTrue(parsed.isObject());
        return (ObjectNode) parsed;
    }

    private static ObjectNode policy(ObjectNode root) {
        JsonNode value = root.get("policyTrace");
        assertTrue(value != null && value.isObject());
        return (ObjectNode) value;
    }

    private static ObjectNode firstRolefulTarget(ObjectNode root) {
        ArrayNode targets = policy(root).withArray("rolefulTargets");
        assertFalse(targets.isEmpty());
        JsonNode target = targets.get(0);
        assertTrue(target.isObject());
        return (ObjectNode) target;
    }

    private static void assertRejected(
            Path directory,
            String sessionId,
            ObjectNode root
    ) throws Exception {
        writeTurnJson(directory, sessionId, root);
        assertTrue(
                new JsonSessionStore(directory).loadTurns(sessionId).isEmpty(),
                sessionId);
    }

    private static void writeTurnJson(
            Path directory,
            String sessionId,
            JsonNode root
    ) throws Exception {
        Files.writeString(
                directory.resolve(sessionId + ".turns.jsonl"),
                JSON.writeValueAsString(root) + "\n");
    }
}
