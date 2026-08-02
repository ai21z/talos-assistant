package dev.talos.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.runtime.intent.MutationPlanSelection;
import dev.talos.runtime.intent.TargetSource;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskResolution;
import dev.talos.runtime.trace.LexicalPlanEvidenceTraceEvent;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.TurnEvidenceProjectionTraceEvent;
import dev.talos.runtime.trace.TurnSettlementTraceEvent;
import dev.talos.runtime.trace.TurnTraceEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Adversarial durability and identity checks for T1204 lexical evidence. */
class T1204DurableEvidenceAdversarialTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant TURN_TIME =
            Instant.parse("2026-08-01T00:00:00Z");
    private static final String TRACE_TIME = "2026-08-01T00:00:00Z";
    private static final String REQUEST =
            "Read style.css and edit index.html, then update style.css.";

    @Test
    void actionlessProviderControlQuarantinePersistsInTurnAndTrace(
            @TempDir Path directory
    ) {
        String request = "Upda\u0000te b.css.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);
        TurnPolicyTrace policy = policyTrace(resolution);

        assertTrue(policy.lexicalDecisions().isEmpty());
        TurnPolicyTrace.LexicalRegionEvidence quarantine =
                policy.lexicalRegions().stream()
                        .filter(region -> "PROVIDER_REMOVED_CONTROL_CLAUSE"
                                .equals(region.kind()))
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                MutationPlanSelection.sourceRequestSha256(request),
                quarantine.sourceRequestSha256());
        assertEquals(
                MutationPlanSelection.sourceSpanSha256(
                        request.substring(
                                quarantine.startOffset(),
                                quarantine.endOffset())),
                quarantine.sourceSpanSha256());

        String sessionId = "actionless-provider-control";
        String traceId = "actionless-provider-control-trace";
        LocalTurnTrace trace = matchingTrace(
                traceId,
                sessionId,
                1,
                policy);
        JsonSessionStore store = new JsonSessionStore(directory);
        try (SessionWriterLease writer =
                store.acquireSessionWriter(sessionId)) {
            assertEquals(
                    TurnEnvelopePersistenceReceipt.State.BOTH_WRITES_OBSERVED,
                    writer.persistTurnEnvelope(
                            sessionId,
                            turnRecord(1, request, policy, traceId),
                            trace)
                            .state());
        }

        TurnRecord storedTurn = store.loadTurns(sessionId).getFirst();
        assertEquals(request, storedTurn.userInput());
        TurnPolicyTrace storedPolicy = storedTurn.policyTrace();
        assertEquals(policy.lexicalRegions(), storedPolicy.lexicalRegions());
        assertTrue(storedPolicy.lexicalDecisions().isEmpty());

        TurnTraceEvent expectedEvent = trace.events().stream()
                .filter(event -> LexicalPlanEvidenceTraceEvent.TYPE.equals(
                        event.type()))
                .findFirst()
                .orElseThrow();
        TurnTraceEvent lexicalEvent = store.loadTrace(sessionId, traceId)
                .orElseThrow()
                .events()
                .stream()
                .filter(event -> LexicalPlanEvidenceTraceEvent.TYPE.equals(
                        event.type()))
                .findFirst()
                .orElseThrow();
        assertEquals(expectedEvent, lexicalEvent);
        List<?> regions = (List<?>) lexicalEvent.data().get("regions");
        assertTrue(regions.stream().anyMatch(value ->
                value instanceof Map<?, ?> row
                        && "PROVIDER_REMOVED_CONTROL_CLAUSE".equals(
                                row.get("kind"))
                        && quarantine.regionSha256().equals(
                                row.get("regionSha256"))));
    }

    @Test
    void turnTraceEventPayloadIsDeeplyDetachedAndImmutable() {
        List<Object> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        Set<String> flags = new LinkedHashSet<>();
        row.put("path", "style.css");
        row.put("flags", flags);
        rows.add(row);
        Map<String, Object> supplied = new LinkedHashMap<>();
        supplied.put("rows", rows);

        TurnTraceEvent event = TurnTraceEvent.simple(
                "LEXICAL_TEST",
                TRACE_TIME,
                supplied);

        row.put("path", "forged.js");
        flags.add("forged");
        rows.add(Map.of("path", "late.js"));
        supplied.put("late", true);

        assertFalse(event.data().containsKey("late"));
        List<?> storedRows = (List<?>) event.data().get("rows");
        assertEquals(1, storedRows.size());
        Map<?, ?> storedRow = (Map<?, ?>) storedRows.getFirst();
        assertEquals("style.css", storedRow.get("path"));
        assertTrue(((Set<?>) storedRow.get("flags")).isEmpty());

        assertThrows(UnsupportedOperationException.class, event.data()::clear);
        assertThrows(UnsupportedOperationException.class, storedRows::clear);
        assertThrows(UnsupportedOperationException.class, storedRow::clear);
        assertThrows(UnsupportedOperationException.class,
                () -> ((Set<?>) storedRow.get("flags")).clear());
    }

    @Test
    void duplicateMembersAndTrailingJsonRootsAreRejected(
            @TempDir Path directory
    ) throws Exception {
        String valid = persistedLexicalTurnJson(directory, "strict-source");
        String marker = "\"lexicalEvidenceSchemaVersion\":1";
        assertTrue(valid.contains(marker));

        String duplicate = valid.replace(
                marker,
                marker + "," + marker);
        assertNotEquals(valid, duplicate);
        Files.writeString(
                directory.resolve("strict-duplicate.turns.jsonl"),
                duplicate + "\n");
        Files.writeString(
                directory.resolve("strict-trailing.turns.jsonl"),
                valid + "{}\n");

        JsonSessionStore store = new JsonSessionStore(directory);
        assertTrue(store.loadTurns("strict-duplicate").isEmpty());
        assertTrue(store.loadTurns("strict-trailing").isEmpty());
    }

    @Test
    void lexicalSchemaMarkerRejectsPartialOrUnsupportedShapesButReadsLegacy(
            @TempDir Path directory
    ) throws Exception {
        String valid = persistedLexicalTurnJson(directory, "schema-source");
        ObjectNode validRoot = (ObjectNode) JSON.readTree(valid);

        ObjectNode unversionedEvidence = validRoot.deepCopy();
        policy(unversionedEvidence).remove("lexicalEvidenceSchemaVersion");
        writeTurnJson(directory, "schema-unversioned", unversionedEvidence);

        ObjectNode missingRegionArray = validRoot.deepCopy();
        policy(missingRegionArray).remove("lexicalRegions");
        writeTurnJson(directory, "schema-partial", missingRegionArray);

        ObjectNode unsupported = validRoot.deepCopy();
        policy(unsupported).put("lexicalEvidenceSchemaVersion", 2);
        writeTurnJson(directory, "schema-unsupported", unsupported);

        ObjectNode legacy = validRoot.deepCopy();
        ObjectNode legacyPolicy = policy(legacy);
        legacyPolicy.remove("lexicalEvidenceSchemaVersion");
        legacyPolicy.remove("lexicalDecisions");
        legacyPolicy.remove("lexicalRegions");
        legacyPolicy.set("rolefulTargets", JSON.createArrayNode());
        writeTurnJson(directory, "schema-legacy", legacy);

        JsonSessionStore store = new JsonSessionStore(directory);
        assertTrue(store.loadTurns("schema-unversioned").isEmpty());
        assertTrue(store.loadTurns("schema-partial").isEmpty());
        assertTrue(store.loadTurns("schema-unsupported").isEmpty());
        assertEquals(1, store.loadTurns("schema-legacy").size());
        assertTrue(store.loadTurns("schema-legacy").getFirst()
                .policyTrace().lexicalDecisions().isEmpty());
    }

    @Test
    void targetIndexLimitRoundTripRejectsReasonKindAndAuthorityTampering(
            @TempDir Path directory
    ) throws Exception {
        String request = "Edit index.html. For reference, "
                + "a.txt ".repeat(8_192);
        TurnPolicyTrace validPolicy = policyTrace(request);
        assertEquals(
                "sequential-target-occurrence-limit-exceeded",
                validPolicy.classificationReason());
        assertEquals(
                List.of("TARGET_OCCURRENCE_LIMIT_QUARANTINE"),
                validPolicy.lexicalRegions().stream()
                        .map(TurnPolicyTrace.LexicalRegionEvidence::kind)
                        .distinct()
                        .toList());

        String sourceSession = "target-limit-source";
        JsonSessionStore sourceStore = new JsonSessionStore(directory);
        try (SessionWriterLease writer =
                sourceStore.acquireSessionWriter(sourceSession)) {
            writer.appendTurn(
                    sourceSession,
                    turnRecord(1, request, validPolicy, ""));
        }
        assertEquals(
                validPolicy,
                sourceStore.loadTurns(sourceSession).getFirst()
                        .policyTrace());
        ObjectNode valid = (ObjectNode) JSON.readTree(Files.readString(
                directory.resolve(sourceSession + ".turns.jsonl")));

        ObjectNode wrongReason = valid.deepCopy();
        policy(wrongReason).put(
                "classificationReason",
                "sequential-target-output-limit-exceeded");
        writeTurnJson(directory, "target-limit-wrong-reason", wrongReason);

        ObjectNode mutationEnabled = valid.deepCopy();
        policy(mutationEnabled).put("mutationAllowed", true);
        writeTurnJson(
                directory,
                "target-limit-mutation-enabled",
                mutationEnabled);

        ObjectNode wrongKind = valid.deepCopy();
        TurnPolicyTrace.LexicalRegionEvidence forgedRegion =
                withRegionKind(
                        validPolicy.lexicalRegions().getFirst(),
                        "TARGET_OUTPUT_LIMIT_QUARANTINE");
        policy(wrongKind).withArray("lexicalRegions").set(
                0,
                JSON.valueToTree(forgedRegion));
        writeTurnJson(directory, "target-limit-wrong-kind", wrongKind);

        JsonSessionStore rejectingStore = new JsonSessionStore(directory);
        assertTrue(rejectingStore.loadTurns(
                "target-limit-wrong-reason").isEmpty());
        assertTrue(rejectingStore.loadTurns(
                "target-limit-mutation-enabled").isEmpty());
        assertTrue(rejectingStore.loadTurns(
                "target-limit-wrong-kind").isEmpty());
    }

    @Test
    void oversizedRawLexicalArrayIsRejectedBeforeElementInspection(
            @TempDir Path directory
    ) throws Exception {
        String valid = persistedLexicalTurnJson(directory, "cap-source");
        ObjectNode oversized = (ObjectNode) JSON.readTree(valid);
        ArrayNode decisions = JSON.createArrayNode();
        for (int i = 0; i < 4_097; i++) {
            decisions.addNull();
        }
        policy(oversized).set("lexicalDecisions", decisions);
        writeTurnJson(directory, "cap-oversized", oversized);

        assertTrue(new JsonSessionStore(directory)
                .loadTurns("cap-oversized").isEmpty());

        List<TurnPolicyTrace.LexicalDecisionEvidence> nullRows =
                new ArrayList<>();
        for (int i = 0; i < 4_097; i++) {
            nullRows.add(null);
        }
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> MutationPlanSelection.validatedDurableDecisions(
                        nullRows));
        assertTrue(error.getMessage().contains("too many"), error.getMessage());
    }

    @Test
    void oversizedTraceEventListRejectsTheWholeEnvelopeBeforePublication(
            @TempDir Path directory
    ) {
        String sessionId = "event-cap";
        LocalTurnTrace.Builder builder = LocalTurnTrace.builder(
                "event-cap-trace",
                sessionId,
                1,
                TRACE_TIME);
        for (int i = 0; i < 4_097; i++) {
            builder.event(TurnTraceEvent.simple(
                    "BOUNDED_EVENT",
                    TRACE_TIME,
                    Map.of("ordinal", i)));
        }
        LocalTurnTrace trace = builder.build();
        TurnRecord record = turnRecord(
                1,
                "inspect",
                TurnPolicyTrace.empty(),
                trace.traceId());

        JsonSessionStore store = new JsonSessionStore(directory);
        try (SessionWriterLease writer =
                store.acquireSessionWriter(sessionId)) {
            assertEquals(
                    TurnEnvelopePersistenceReceipt.State.NO_WRITES_OBSERVED,
                    writer.persistTurnEnvelope(
                            sessionId,
                            record,
                            trace).state());
        }
        assertNoPublishedEnvelope(directory, sessionId);
    }

    @Test
    void terminalProjectionPreservesEveryBoundedSafeSessionIdentity() {
        for (int length : List.of(128, 129, 200)) {
            String sessionId = "s".repeat(length);
            assertEquals(
                    sessionId,
                    projectedFailureTrace(
                            sessionId,
                            "bounded-session-" + length).sessionId());
        }
        assertEquals(
                "",
                projectedFailureTrace(
                        "s".repeat(201),
                        "overlong-session").sessionId());
    }

    @Test
    void typedTerminalProjectionBindsAnEmptyTurnPolicy(
            @TempDir Path directory
    ) {
        String sessionId = "terminal-projection";
        String traceId = "terminal-projection-trace";
        LocalTurnTrace trace = projectedFailureTrace(sessionId, traceId);
        JsonSessionStore store = new JsonSessionStore(directory);

        try (SessionWriterLease writer =
                store.acquireSessionWriter(sessionId)) {
            assertEquals(
                    TurnEnvelopePersistenceReceipt.State.BOTH_WRITES_OBSERVED,
                    writer.persistTurnEnvelope(
                            sessionId,
                            turnRecord(
                                    1,
                                    "",
                                    TurnPolicyTrace.empty(),
                                    traceId),
                            trace).state());
        }
        assertEquals(
                trace,
                store.loadTrace(sessionId, traceId).orElseThrow());
    }

    @Test
    void invalidTerminalProjectionCannotCarryTargetAuthority(
            @TempDir Path directory
    ) {
        String sessionId = "invalid-terminal-authority";
        String traceId = "invalid-terminal-authority-trace";
        LocalTurnTrace trace = LocalTurnTrace.builder(
                        traceId,
                        sessionId,
                        1,
                        TRACE_TIME)
                .taskContract(new LocalTurnTrace.TaskContractSummary(
                        "UNKNOWN",
                        false,
                        false,
                        false,
                        List.of("style.css"),
                        List.of()))
                .event(TurnTraceEvent.simple(
                        TurnEvidenceProjectionTraceEvent.TYPE,
                        TRACE_TIME,
                        Map.of("schemaVersion", 1)))
                .build();

        assertRejectedEnvelope(
                directory,
                sessionId,
                turnRecord(
                        1,
                        "",
                        TurnPolicyTrace.empty(),
                        traceId),
                trace);
    }

    @Test
    void envelopeRejectsSessionTurnTraceTaskToolAndRoleMismatches(
            @TempDir Path directory
    ) {
        TurnPolicyTrace policy = policyTrace(REQUEST);

        assertRejectedEnvelope(
                directory.resolve("session"),
                "expected-session",
                turnRecord(1, REQUEST, policy, "session-trace"),
                matchingTrace(
                        "session-trace",
                        "wrong-session",
                        1,
                        policy));

        assertRejectedEnvelope(
                directory.resolve("turn"),
                "turn-session",
                turnRecord(1, REQUEST, policy, "turn-trace"),
                matchingTrace("turn-trace", "turn-session", 2, policy));

        assertRejectedEnvelope(
                directory.resolve("trace-id"),
                "trace-id-session",
                turnRecord(1, REQUEST, policy, "expected-trace"),
                matchingTrace(
                        "different-trace",
                        "trace-id-session",
                        1,
                        policy));

        LocalTurnTrace.TaskContractSummary matchingSummary =
                taskSummary(policy);
        LocalTurnTrace.TaskContractSummary wrongTask =
                new LocalTurnTrace.TaskContractSummary(
                        "FORGED_TASK",
                        matchingSummary.mutationAllowed(),
                        matchingSummary.verificationRequired(),
                        matchingSummary.mutationRequested(),
                        matchingSummary.expectedTargets(),
                        matchingSummary.forbiddenTargets(),
                        matchingSummary.classificationReason(),
                        matchingSummary.rolefulTargets());
        assertRejectedEnvelope(
                directory.resolve("task"),
                "task-session",
                turnRecord(1, REQUEST, policy, "task-trace"),
                traceWithProjection(
                        "task-trace",
                        "task-session",
                        1,
                        policy,
                        wrongTask,
                        policy.nativeTools(),
                        policy.promptTools()));

        LocalTurnTrace.TaskContractSummary wrongMutationRequest =
                new LocalTurnTrace.TaskContractSummary(
                        matchingSummary.type(),
                        matchingSummary.mutationAllowed(),
                        matchingSummary.verificationRequired(),
                        !matchingSummary.mutationRequested(),
                        matchingSummary.expectedTargets(),
                        matchingSummary.forbiddenTargets(),
                        matchingSummary.classificationReason(),
                        matchingSummary.rolefulTargets());
        assertRejectedEnvelope(
                directory.resolve("mutation-request"),
                "mutation-request-session",
                turnRecord(
                        1,
                        REQUEST,
                        policy,
                        "mutation-request-trace"),
                traceWithProjection(
                        "mutation-request-trace",
                        "mutation-request-session",
                        1,
                        policy,
                        wrongMutationRequest,
                        policy.nativeTools(),
                        policy.promptTools()));

        assertRejectedEnvelope(
                directory.resolve("tools"),
                "tool-session",
                turnRecord(1, REQUEST, policy, "tool-trace"),
                traceWithProjection(
                        "tool-trace",
                        "tool-session",
                        1,
                        policy,
                        matchingSummary,
                        List.of(),
                        List.of()));

        LocalTurnTrace.TaskContractSummary wrongRoles =
                new LocalTurnTrace.TaskContractSummary(
                        matchingSummary.type(),
                        matchingSummary.mutationAllowed(),
                        matchingSummary.verificationRequired(),
                        matchingSummary.mutationRequested(),
                        matchingSummary.expectedTargets(),
                        matchingSummary.forbiddenTargets(),
                        matchingSummary.classificationReason(),
                        List.of());
        assertRejectedEnvelope(
                directory.resolve("roles"),
                "role-session",
                turnRecord(1, REQUEST, policy, "role-trace"),
                traceWithProjection(
                        "role-trace",
                        "role-session",
                        1,
                        policy,
                        wrongRoles,
                        policy.nativeTools(),
                        policy.promptTools()));
    }

    @Test
    void aLateDuplicateTraceIdInvalidatesAnEarlierResolvableTrace(
            @TempDir Path directory
    ) throws Exception {
        String sessionId = "duplicate-trace-session";
        String traceId = "duplicate-trace";
        TurnPolicyTrace policy = policyTrace(REQUEST);
        LocalTurnTrace trace = matchingTrace(
                traceId,
                sessionId,
                1,
                policy);
        JsonSessionStore store = new JsonSessionStore(directory);

        try (SessionWriterLease writer =
                store.acquireSessionWriter(sessionId)) {
            assertEquals(
                    TurnEnvelopePersistenceReceipt.State.BOTH_WRITES_OBSERVED,
                    writer.persistTurnEnvelope(
                            sessionId,
                            turnRecord(1, REQUEST, policy, traceId),
                            trace).state());
            assertTrue(store.loadTrace(sessionId, traceId).isPresent());

            writer.appendTurn(
                    sessionId,
                    turnRecord(2, REQUEST, policy, traceId));
            assertEquals(2, store.loadTurns(sessionId).size());
            assertTrue(store.loadTrace(sessionId, traceId).isEmpty());

            writer.appendTurn(
                    sessionId,
                    turnRecord(
                            3,
                            "inspect",
                            TurnPolicyTrace.empty(),
                            traceId));
            assertTrue(store.loadTrace(sessionId, traceId).isEmpty());
            Path originalTrace = directory.resolve("traces")
                    .resolve(sessionId)
                    .resolve("000001-" + traceId + ".json");
            assertTrue(Files.exists(originalTrace));
            String originalBytes = Files.readString(originalTrace);

            LocalTurnTrace eventless = LocalTurnTrace.builder(
                            traceId,
                            sessionId,
                            3,
                            TRACE_TIME)
                    .build();
            writer.saveTrace(sessionId, eventless);

            assertEquals(originalBytes, Files.readString(originalTrace));
            assertFalse(Files.exists(directory.resolve("traces")
                    .resolve(sessionId)
                    .resolve("000003-" + traceId + ".json")));
            try (var files = Files.list(
                    directory.resolve("traces").resolve(sessionId))) {
                assertEquals(1, files.count());
            }
            assertTrue(store.loadTrace(sessionId, traceId).isEmpty());
        }
    }

    @Test
    void replayAgainstAHashOnlyPriorIsProjectedToHashOnlyInTurnAndTrace(
            @TempDir Path directory
    ) {
        String sessionId = "redacted-history";
        String originalRequest = "Read style.css and edit index.html, then "
                + "update style.css using "
                + "API_TOKEN=DO_NOT_LEAK_T1204_HISTORY.";
        TaskResolution original =
                TaskContractResolver.resolveFromUserRequest(originalRequest);
        TurnPolicyTrace originalPolicy = policyTrace(original);
        assertTrue(originalPolicy.rolefulTargets().stream().anyMatch(target ->
                target.path().equals("style.css")
                        && target.sourceText().contains(
                                "DO_NOT_LEAK_T1204_HISTORY")));
        JsonSessionStore store = new JsonSessionStore(directory);

        try (SessionWriterLease writer =
                store.acquireSessionWriter(sessionId)) {
        writer.appendTurn(
                sessionId,
                turnRecord(1, originalRequest, originalPolicy, ""));
        List<TurnRecord> firstLoad = store.loadTurns(sessionId);
        assertEquals(1, firstLoad.size());
        assertFalse(firstLoad.getFirst().userInput()
                .contains("DO_NOT_LEAK_T1204_HISTORY"));
        assertTrue(firstLoad.getFirst().policyTrace().rolefulTargets()
                .stream().noneMatch(target -> target.sourceText().contains(
                        "DO_NOT_LEAK_T1204_HISTORY")));
        assertTrue(firstLoad.getFirst().policyTrace().lexicalDecisions()
                .stream().allMatch(decision ->
                        MutationPlanSelection.DurableEvidenceState
                                .REDACTED_HASH_ONLY.name()
                                .equals(decision.evidenceState())));

        TaskResolution replay = TaskResolution.confirmedReplay(original);
        TurnPolicyTrace replayPolicy = policyTrace(replay);
        assertTrue(replayPolicy.lexicalDecisions().stream().allMatch(decision ->
                MutationPlanSelection.DurableEvidenceState.EXACT.name()
                        .equals(decision.evidenceState())));
        String traceId = "redacted-history-replay";
        LocalTurnTrace replayTrace = matchingTrace(
                traceId,
                sessionId,
                2,
                replayPolicy);

        assertEquals(
                TurnEnvelopePersistenceReceipt.State.BOTH_WRITES_OBSERVED,
                writer.persistTurnEnvelope(
                        sessionId,
                        turnRecord(2, "yes", replayPolicy, traceId),
                        replayTrace).state());

        List<TurnRecord> loaded = store.loadTurns(sessionId);
        assertEquals(2, loaded.size());
        TurnPolicyTrace storedReplay = loaded.getLast().policyTrace();
        assertTrue(storedReplay.lexicalDecisions().stream().allMatch(decision ->
                TargetSource.MESSAGE_HISTORY.name().equals(decision.source())
                        && MutationPlanSelection.DurableEvidenceState
                                .REDACTED_HASH_ONLY.name()
                                .equals(decision.evidenceState())
                        && decision.sourceRequestChars()
                                == originalRequest.strip().length()
                        && decision.sourceRequestBytes()
                                == originalRequest.strip()
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                                        .length));

        LocalTurnTrace storedTrace = store.loadTrace(sessionId, traceId)
                .orElseThrow();
        TurnTraceEvent lexicalEvent = storedTrace.events().stream()
                .filter(event -> LexicalPlanEvidenceTraceEvent.TYPE.equals(
                        event.type()))
                .findFirst()
                .orElseThrow();
        List<?> decisions = (List<?>) lexicalEvent.data().get("decisions");
        assertFalse(decisions.isEmpty());
        assertTrue(decisions.stream().allMatch(value ->
                value instanceof Map<?, ?> row
                        && MutationPlanSelection.DurableEvidenceState
                                .REDACTED_HASH_ONLY.name().equals(
                                        row.get("evidenceState"))));
        }
    }

    @Test
    void missingPriorIdentityCannotPublishAConfirmedReplay(
            @TempDir Path directory
    ) {
        TaskResolution original =
                TaskContractResolver.resolveFromUserRequest(REQUEST);
        TurnPolicyTrace replayPolicy =
                policyTrace(TaskResolution.confirmedReplay(original));
        String sessionId = "missing-prior";
        String traceId = "missing-prior-trace";

        JsonSessionStore store = new JsonSessionStore(directory);
        try (SessionWriterLease writer =
                store.acquireSessionWriter(sessionId)) {
            assertEquals(
                    TurnEnvelopePersistenceReceipt.State.NO_WRITES_OBSERVED,
                    writer.persistTurnEnvelope(
                            sessionId,
                            turnRecord(1, "yes", replayPolicy, traceId),
                            matchingTrace(
                                    traceId,
                                    sessionId,
                                    1,
                                    replayPolicy))
                            .state());
        }
        assertNoPublishedEnvelope(directory, sessionId);
    }

    @Test
    void redactedStoredPathMustBindItsStoredHashWhileRetainingOriginalHash() {
        TurnPolicyTrace.LexicalDecisionEvidence exact = policyTrace(REQUEST)
                .lexicalDecisions().getFirst();
        String redactedPath = "redacted.css";
        String redactedStoredHash =
                MutationPlanSelection.pathSha256(redactedPath);

        TurnPolicyTrace.LexicalDecisionEvidence redacted = copyDecision(
                exact,
                redactedPath,
                exact.pathSha256(),
                redactedStoredHash,
                MutationPlanSelection.DurableEvidenceState
                        .REDACTED_HASH_ONLY.name());
        assertEquals(exact.pathSha256(), redacted.pathSha256());
        assertNotEquals(redacted.pathSha256(), redacted.storedPathSha256());
        assertEquals(
                MutationPlanSelection.pathSha256(redacted.path()),
                redacted.storedPathSha256());

        assertThrows(
                IllegalArgumentException.class,
                () -> copyDecision(
                        exact,
                        redactedPath,
                        exact.pathSha256(),
                        exact.storedPathSha256(),
                        MutationPlanSelection.DurableEvidenceState
                                .REDACTED_HASH_ONLY.name()));
    }

    @Test
    void aLateExactRedactedIdentityConflictInvalidatesEveryOccurrence(
            @TempDir Path directory
    ) throws Exception {
        String valid = persistedLexicalTurnJson(directory, "conflict-source");
        ObjectNode exactFirst = (ObjectNode) JSON.readTree(valid);
        exactFirst.put("turnNumber", 1);

        ObjectNode redactedSecond = exactFirst.deepCopy();
        redactedSecond.put("turnNumber", 2);
        redactLexicalEvidence(redactedSecond);

        ObjectNode exactThird = exactFirst.deepCopy();
        exactThird.put("turnNumber", 3);

        Path target = directory.resolve("late-conflict.turns.jsonl");
        Files.writeString(
                target,
                JSON.writeValueAsString(exactFirst) + "\n"
                        + JSON.writeValueAsString(redactedSecond)
                        + "\n"
                        + JSON.writeValueAsString(exactThird)
                        + "\n");

        assertTrue(new JsonSessionStore(directory)
                .loadTurns("late-conflict").isEmpty());
    }

    private static TurnPolicyTrace policyTrace(String request) {
        return policyTrace(TaskContractResolver.resolveFromUserRequest(request));
    }

    private static TurnPolicyTrace policyTrace(TaskResolution resolution) {
        return TurnPolicyTrace.from(
                resolution,
                "APPLY",
                List.of("talos.read_file", "talos.edit_file"),
                List.of("tool_use:read_file", "tool_use:edit_file"));
    }

    private static TurnRecord turnRecord(
            int turnNumber,
            String request,
            TurnPolicyTrace policy,
            String traceId
    ) {
        return new TurnRecord(
                turnNumber,
                TURN_TIME.plusSeconds(turnNumber),
                1,
                request,
                "partial",
                List.of(),
                0,
                0,
                0,
                "",
                "ok",
                policy,
                traceId);
    }

    private static LocalTurnTrace matchingTrace(
            String traceId,
            String sessionId,
            int turnNumber,
            TurnPolicyTrace policy
    ) {
        return traceWithProjection(
                traceId,
                sessionId,
                turnNumber,
                policy,
                taskSummary(policy),
                policy.nativeTools(),
                policy.promptTools());
    }

    private static LocalTurnTrace traceWithProjection(
            String traceId,
            String sessionId,
            int turnNumber,
            TurnPolicyTrace policy,
            LocalTurnTrace.TaskContractSummary task,
            List<String> nativeTools,
            List<String> promptTools
    ) {
        return LocalTurnTrace.builder(
                        traceId,
                        sessionId,
                        turnNumber,
                        TRACE_TIME)
                .taskContract(task)
                .toolSurface(
                        nativeTools,
                        promptTools,
                        "selected for resolved task contract")
                .event(LexicalPlanEvidenceTraceEvent.create(
                        TRACE_TIME,
                        policy))
                .build();
    }

    private static LocalTurnTrace.TaskContractSummary taskSummary(
            TurnPolicyTrace policy
    ) {
        return new LocalTurnTrace.TaskContractSummary(
                policy.taskType(),
                policy.mutationAllowed(),
                policy.verificationRequired(),
                policy.mutationRequested(),
                policy.expectedTargets(),
                policy.forbiddenTargets(),
                policy.classificationReason(),
                policy.rolefulTargets().stream()
                        .map(T1204DurableEvidenceAdversarialTest
                                ::rolefulSummary)
                        .toList());
    }

    private static LocalTurnTrace.RolefulTargetSummary rolefulSummary(
            TurnPolicyTrace.RolefulTarget target
    ) {
        return new LocalTurnTrace.RolefulTargetSummary(
                target.path(),
                target.role(),
                target.source(),
                target.reason(),
                target.sourceText(),
                target.confidence(),
                target.startOffset(),
                target.authorityEndOffset(),
                target.endOffset());
    }

    private static LocalTurnTrace projectedFailureTrace(
            String sessionId,
            String traceId
    ) {
        TurnFailureClassifier.Classification failure =
                TurnFailureClassifier.classify(
                        new IllegalStateException("fixture failure"));
        return LocalTurnTrace.builder(
                        traceId,
                        sessionId,
                        1,
                        TRACE_TIME)
                .event(TurnSettlementTraceEvent.create(
                        TRACE_TIME,
                        TurnSettlement.Kind.FAILED,
                        TurnExecutionStage.GENERATION_COORDINATION,
                        failure))
                .event(TurnTraceEvent.simple(
                        "TRACE_COMPLETED",
                        TRACE_TIME,
                        Map.of()))
                .build()
                .safeTerminalProjection(TurnSettlement.Kind.FAILED, false);
    }

    private static void assertRejectedEnvelope(
            Path directory,
            String sessionId,
            TurnRecord record,
            LocalTurnTrace trace
    ) {
        JsonSessionStore store = new JsonSessionStore(directory);
        try (SessionWriterLease writer =
                store.acquireSessionWriter(sessionId)) {
            assertEquals(
                    TurnEnvelopePersistenceReceipt.State.NO_WRITES_OBSERVED,
                    writer.persistTurnEnvelope(
                            sessionId,
                            record,
                            trace).state());
        }
        assertNoPublishedEnvelope(directory, sessionId);
    }

    private static void assertNoPublishedEnvelope(
            Path directory,
            String sessionId
    ) {
        assertFalse(Files.exists(
                directory.resolve(sessionId + ".turns.jsonl")));
        assertFalse(Files.exists(
                directory.resolve("traces").resolve(sessionId)));
    }

    private static String persistedLexicalTurnJson(
            Path directory,
            String sessionId
    ) throws Exception {
        JsonSessionStore store = new JsonSessionStore(directory);
        TurnPolicyTrace policy = policyTrace(REQUEST);
        assertFalse(policy.lexicalDecisions().isEmpty());
        try (SessionWriterLease writer =
                store.acquireSessionWriter(sessionId)) {
            writer.appendTurn(
                    sessionId,
                    turnRecord(1, REQUEST, policy, ""));
        }
        assertEquals(1, store.loadTurns(sessionId).size());
        return Files.readString(
                        directory.resolve(sessionId + ".turns.jsonl"))
                .strip();
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

    private static ObjectNode policy(ObjectNode root) {
        JsonNode value = root.get("policyTrace");
        assertNotNull(value);
        assertTrue(value.isObject());
        return (ObjectNode) value;
    }

    private static TurnPolicyTrace.LexicalDecisionEvidence copyDecision(
            TurnPolicyTrace.LexicalDecisionEvidence source,
            String path,
            String pathSha256,
            String storedPathSha256,
            String evidenceState
    ) {
        return new TurnPolicyTrace.LexicalDecisionEvidence(
                source.ordinal(),
                source.owner(),
                path,
                pathSha256,
                storedPathSha256,
                source.authority(),
                source.action(),
                source.actionFamily(),
                source.source(),
                evidenceState,
                source.sourceRequestSha256(),
                source.sourceRequestChars(),
                source.sourceRequestBytes(),
                source.actionStartOffset(),
                source.actionEndOffset(),
                source.targetStartOffset(),
                source.startOffset(),
                source.authorityEndOffset(),
                source.endOffset(),
                source.selectedForPath(),
                source.activeInResolution(),
                source.sourceSpanSha256(),
                source.occurrenceSha256());
    }

    private static TurnPolicyTrace.LexicalRegionEvidence withRegionKind(
            TurnPolicyTrace.LexicalRegionEvidence source,
            String kind
    ) {
        return new TurnPolicyTrace.LexicalRegionEvidence(
                source.ordinal(),
                source.owner(),
                kind,
                source.source(),
                source.evidenceState(),
                source.sourceRequestSha256(),
                source.sourceRequestChars(),
                source.sourceRequestBytes(),
                source.action(),
                source.pathSha256(),
                source.startOffset(),
                source.endOffset(),
                source.sourceSpanSha256(),
                MutationPlanSelection.regionSha256(
                        source.owner(),
                        kind,
                        source.source(),
                        source.sourceRequestSha256(),
                        source.sourceRequestChars(),
                        source.sourceRequestBytes(),
                        source.action(),
                        source.pathSha256(),
                        source.startOffset(),
                        source.endOffset(),
                        source.sourceSpanSha256()));
    }

    private static void redactLexicalEvidence(ObjectNode root) {
        ObjectNode policy = policy(root);
        for (JsonNode target : policy.withArray("rolefulTargets")) {
            ObjectNode object = (ObjectNode) target;
            object.put("startOffset", -1);
            object.put("authorityEndOffset", -1);
            object.put("endOffset", -1);
        }
        for (String list : List.of("lexicalDecisions", "lexicalRegions")) {
            for (JsonNode row : policy.withArray(list)) {
                ((ObjectNode) row).put(
                        "evidenceState",
                        MutationPlanSelection.DurableEvidenceState
                                .REDACTED_HASH_ONLY.name());
            }
        }
    }
}
