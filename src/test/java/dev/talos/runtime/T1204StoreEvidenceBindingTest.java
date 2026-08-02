package dev.talos.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.runtime.intent.MutationPlanSelection;
import dev.talos.runtime.intent.TaskIntentProjector;
import dev.talos.runtime.intent.TargetRole;
import dev.talos.runtime.intent.TargetSource;
import dev.talos.runtime.policy.CapabilityPosture;
import dev.talos.runtime.policy.CapabilityPosturePolicy;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskResolution;
import dev.talos.runtime.trace.LexicalPlanEvidenceTraceEvent;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Strict store and trace identity checks for T1204 durable evidence. */
class T1204StoreEvidenceBindingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TRACE_TIME = "2026-08-01T00:00:00Z";
    private static final String REQUEST =
            "Read style.css and edit index.html, then update style.css.";
    private static final String REPEATED_REQUEST =
            "Edit index.html so it works, and update style.css too. "
                    + "Update style.css. Rewrite style.css.";

    @Test
    void nonLexicalHistoryProjectionRemainsPersistable(
            @TempDir Path directory
    ) throws Exception {
        String sessionId = "nonlexical-history-projection";
        String confirmation = "Yes, proceed.";
        TaskResolution projected = TaskContractResolver.resolveFromMessages(
                List.of(
                        ChatMessage.system("sys"),
                        ChatMessage.user("Inspect index.html."),
                        ChatMessage.assistant(
                                "I can update index.html. Should I proceed?"),
                        ChatMessage.user(confirmation)));
        TurnPolicyTrace policy = policyTrace(projected);
        assertTrue(policy.rolefulTargets().stream().anyMatch(target ->
                TargetSource.MESSAGE_HISTORY.name().equals(target.source())));
        assertTrue(policy.lexicalDecisions().isEmpty());
        assertTrue(policy.lexicalRegions().isEmpty());

        JsonSessionStore store = new JsonSessionStore(directory);
        try (SessionWriterLease writer =
                store.acquireSessionWriter(sessionId)) {
            writer.appendTurn(
                    sessionId,
                    turnRecord(1, confirmation, policy, ""));
        }

        TurnRecord loaded = store.loadTurns(sessionId).getFirst();
        assertEquals(policy.rolefulTargets(),
                loaded.policyTrace().rolefulTargets());
        assertTrue(loaded.policyTrace().lexicalDecisions().isEmpty());
        assertTrue(loaded.policyTrace().lexicalRegions().isEmpty());
    }

    @Test
    void legacyEmptyManifestAndCurrentExactManifestForTheSameTextCoexist(
            @TempDir Path directory
    ) throws Exception {
        String sessionId = "legacy-to-v1-upgrade";
        TurnPolicyTrace current = policyTrace(
                TaskContractResolver.resolveFromUserRequest(REQUEST));
        writeLegacyTurnWithoutLexicalEvidence(
                directory,
                sessionId,
                "legacy-to-v1-source",
                current);
        JsonSessionStore store = new JsonSessionStore(directory);
        assertEquals(1, store.loadTurns(sessionId).size());

        try (SessionWriterLease writer =
                store.acquireSessionWriter(sessionId)) {
            writer.appendTurn(
                    sessionId,
                    turnRecord(2, REQUEST, current, ""));
        }

        List<TurnRecord> loaded = store.loadTurns(sessionId);
        assertEquals(2, loaded.size());
        assertTrue(loaded.getFirst().policyTrace()
                .lexicalDecisions().isEmpty());
        assertFalse(loaded.getLast().policyTrace()
                .lexicalDecisions().isEmpty());
    }

    @Test
    void confirmedReplayRejectsAPriorTurnWithNoLexicalManifest(
            @TempDir Path directory
    ) throws Exception {
        String sessionId = "legacy-empty-manifest-replay";
        TaskResolution original =
                TaskContractResolver.resolveFromUserRequest(REQUEST);
        TurnPolicyTrace originalPolicy = policyTrace(original);
        writeLegacyTurnWithoutLexicalEvidence(
                directory,
                sessionId,
                "legacy-replay-source",
                originalPolicy);
        Path turnLog = directory.resolve(sessionId + ".turns.jsonl");
        String before = Files.readString(turnLog);
        TurnPolicyTrace replay = policyTrace(
                TaskResolution.confirmedReplay(original));

        JsonSessionStore store = new JsonSessionStore(directory);
        assertEquals(1, store.loadTurns(sessionId).size());
        try (SessionWriterLease writer =
                store.acquireSessionWriter(sessionId)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> writer.appendTurn(
                            sessionId,
                            turnRecord(2, "yes", replay, "")));
        }

        assertEquals(before, Files.readString(turnLog));
        assertEquals(1, store.loadTurns(sessionId).size());
    }

    @Test
    void matchingPriorRequestDoesNotAuthorizeAMissingReplayDecision(
            @TempDir Path directory
    ) throws Exception {
        String sessionId = "replay-missing-decision";
        TaskResolution original =
                TaskContractResolver.resolveFromUserRequest(REPEATED_REQUEST);
        TurnPolicyTrace originalPolicy = policyTrace(original);
        TurnPolicyTrace replay = policyTrace(
                TaskResolution.confirmedReplay(original));
        int removable = firstNonSelectedDecision(replay);

        ArrayList<TurnPolicyTrace.LexicalDecisionEvidence> missing =
                new ArrayList<>(replay.lexicalDecisions());
        missing.remove(removable);
        for (int index = 0; index < missing.size(); index++) {
            missing.set(index, withOrdinal(missing.get(index), index));
        }
        TurnPolicyTrace forged = copyPolicy(
                replay,
                replay.mutationAllowed(),
                replay.mutationRequested(),
                missing,
                replay.lexicalRegions());

        assertReplayRejected(
                directory,
                sessionId,
                originalPolicy,
                forged);
    }

    @Test
    void matchingPriorRequestDoesNotAuthorizeASubstitutedReplayDecision(
            @TempDir Path directory
    ) throws Exception {
        String sessionId = "replay-substituted-decision";
        TaskResolution original =
                TaskContractResolver.resolveFromUserRequest(REPEATED_REQUEST);
        TurnPolicyTrace originalPolicy = policyTrace(original);
        TurnPolicyTrace replay = policyTrace(
                TaskResolution.confirmedReplay(original));
        int replace = firstNonSelectedDecision(replay);

        ArrayList<TurnPolicyTrace.LexicalDecisionEvidence> substituted =
                new ArrayList<>(replay.lexicalDecisions());
        substituted.set(
                replace,
                withSubstitutedPath(
                        substituted.get(replace),
                        "forged-target.css"));
        TurnPolicyTrace forged = copyPolicy(
                replay,
                replay.mutationAllowed(),
                replay.mutationRequested(),
                substituted,
                replay.lexicalRegions());

        assertReplayRejected(
                directory,
                sessionId,
                originalPolicy,
                forged);
    }

    @Test
    void unrelatedNonAuthorizingHistoryRoleDoesNotDriveLexicalReconciliation(
            @TempDir Path directory
    ) {
        TurnPolicyTrace current = policyTrace(
                TaskContractResolver.resolveFromUserRequest(REQUEST));
        ArrayList<TurnPolicyTrace.RolefulTarget> roles =
                new ArrayList<>(current.rolefulTargets());
        roles.add(new TurnPolicyTrace.RolefulTarget(
                "history-only.txt",
                TargetRole.MENTIONED_ONLY.name(),
                TargetSource.MESSAGE_HISTORY.name(),
                "unrelated-history-role",
                "historical mention",
                1.0));
        TurnPolicyTrace mixed = new TurnPolicyTrace(
                current.taskType(),
                current.mutationAllowed(),
                current.verificationRequired(),
                current.expectedTargets(),
                current.forbiddenTargets(),
                current.initialPhase(),
                current.finalPhase(),
                current.nativeTools(),
                current.promptTools(),
                current.blocks(),
                current.classificationReason(),
                roles,
                current.lexicalDecisions(),
                current.lexicalRegions(),
                current.mutationRequested());

        String sessionId = "mixed-source-nonauthorizing-role";
        JsonSessionStore store = new JsonSessionStore(directory);
        try (SessionWriterLease writer =
                store.acquireSessionWriter(sessionId)) {
            writer.appendTurn(
                    sessionId,
                    turnRecord(1, REQUEST, mixed, ""));
        }

        TurnPolicyTrace loaded = store.loadTurns(sessionId).getFirst()
                .policyTrace();
        assertEquals(current.lexicalDecisions(), loaded.lexicalDecisions());
        assertEquals(current.lexicalRegions(), loaded.lexicalRegions());
        assertTrue(loaded.rolefulTargets().stream().anyMatch(target ->
                target.path().equals("history-only.txt")
                        && TargetRole.MENTIONED_ONLY.name().equals(
                                target.role())));
    }

    @Test
    void unrelatedHistoryRoleCannotBypassCurrentManifestValidation(
            @TempDir Path directory
    ) {
        TaskResolution original =
                TaskContractResolver.resolveFromUserRequest(REPEATED_REQUEST);
        TurnPolicyTrace policy = policyTrace(original);
        int replace = firstNonSelectedDecision(policy);
        ArrayList<TurnPolicyTrace.LexicalDecisionEvidence> decisions =
                new ArrayList<>(policy.lexicalDecisions());
        decisions.set(
                replace,
                withSubstitutedPath(
                        decisions.get(replace),
                        "forged-target.css"));
        ArrayList<TurnPolicyTrace.RolefulTarget> roles =
                new ArrayList<>(policy.rolefulTargets());
        roles.add(new TurnPolicyTrace.RolefulTarget(
                "history-only.txt",
                TargetRole.MENTIONED_ONLY.name(),
                TargetSource.MESSAGE_HISTORY.name(),
                "unrelated-history-role",
                "historical mention",
                1.0));
        TurnPolicyTrace forged = new TurnPolicyTrace(
                policy.taskType(),
                policy.mutationAllowed(),
                policy.verificationRequired(),
                policy.expectedTargets(),
                policy.forbiddenTargets(),
                policy.initialPhase(),
                policy.finalPhase(),
                policy.nativeTools(),
                policy.promptTools(),
                policy.blocks(),
                policy.classificationReason(),
                roles,
                decisions,
                policy.lexicalRegions(),
                policy.mutationRequested());

        JsonSessionStore store = new JsonSessionStore(directory);
        String sessionId = "mixed-source-manifest-bypass";
        try (SessionWriterLease writer =
                store.acquireSessionWriter(sessionId)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> writer.appendTurn(
                            sessionId,
                            turnRecord(
                                    1,
                                    REPEATED_REQUEST,
                                    forged,
                                    "")));
        }
        assertTrue(store.loadTurns(sessionId).isEmpty());
    }

    @Test
    void compositeReadBeforeWriteRoleCannotBeDowngradedInCurrentOrReplayEvidence(
            @TempDir Path directory
    ) throws Exception {
        TaskResolution original =
                TaskContractResolver.resolveFromUserRequest(REQUEST);
        TurnPolicyTrace originalPolicy = policyTrace(original);
        assertTrue(originalPolicy.rolefulTargets().stream().anyMatch(target ->
                target.path().equals("style.css")
                        && TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE.name()
                                .equals(target.role())));
        TurnPolicyTrace downgradedCurrent = withRole(
                originalPolicy,
                "style.css",
                TargetRole.MUST_MUTATE);

        JsonSessionStore store = new JsonSessionStore(directory);
        try (SessionWriterLease writer = store.acquireSessionWriter(
                "downgraded-current-role")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> writer.appendTurn(
                            "downgraded-current-role",
                            turnRecord(
                                    1,
                                    REQUEST,
                                    downgradedCurrent,
                                    "")));
        }
        assertTrue(store.loadTurns("downgraded-current-role").isEmpty());

        String sourceSession = "composite-role-source";
        try (SessionWriterLease writer =
                store.acquireSessionWriter(sourceSession)) {
            writer.appendTurn(
                    sourceSession,
                    turnRecord(1, REQUEST, originalPolicy, ""));
            TurnPolicyTrace replay = policyTrace(
                    TaskResolution.confirmedReplay(original));
            TurnPolicyTrace downgradedReplay = withRole(
                    replay,
                    "style.css",
                    TargetRole.MUST_MUTATE);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> writer.appendTurn(
                            sourceSession,
                            turnRecord(
                                    2,
                                    "yes",
                                    downgradedReplay,
                                    "")));
        }
        assertEquals(1, store.loadTurns(sourceSession).size());

        ObjectNode forged = (ObjectNode) JSON.readTree(Files.readString(
                directory.resolve(sourceSession + ".turns.jsonl")));
        boolean changed = false;
        for (JsonNode target : ((ObjectNode) forged.get("policyTrace"))
                .withArray("rolefulTargets")) {
            if ("style.css".equals(target.path("path").asText())) {
                ((ObjectNode) target).put(
                        "role",
                        TargetRole.MUST_MUTATE.name());
                changed = true;
            }
        }
        assertTrue(changed);
        Files.writeString(
                directory.resolve("downgraded-raw-role.turns.jsonl"),
                JSON.writeValueAsString(forged) + "\n");
        assertTrue(store.loadTurns("downgraded-raw-role").isEmpty());

        for (TargetRole unsafeRole : List.of(
                TargetRole.MAY_MUTATE,
                TargetRole.OUTPUT_DESTINATION)) {
            ObjectNode inactive = (ObjectNode) JSON.readTree(
                    Files.readString(directory.resolve(
                            sourceSession + ".turns.jsonl")));
            ObjectNode inactivePolicy =
                    (ObjectNode) inactive.get("policyTrace");
            boolean changedRole = false;
            for (JsonNode target
                    : inactivePolicy.withArray("rolefulTargets")) {
                if ("style.css".equals(target.path("path").asText())) {
                    ((ObjectNode) target).put(
                            "role",
                            unsafeRole.name());
                    changedRole = true;
                }
            }
            boolean changedActive = false;
            for (JsonNode decision
                    : inactivePolicy.withArray("lexicalDecisions")) {
                if ("style.css".equals(decision.path("path").asText())
                        && decision.path("selectedForPath").asBoolean()) {
                    ((ObjectNode) decision).put(
                            "activeInResolution",
                            false);
                    changedActive = true;
                }
            }
            assertTrue(changedRole);
            assertTrue(changedActive);
            String inactiveSession = "inactive-composite-"
                    + unsafeRole.name().toLowerCase();
            Files.writeString(
                    directory.resolve(inactiveSession + ".turns.jsonl"),
                    JSON.writeValueAsString(inactive) + "\n");
            assertTrue(store.loadTurns(inactiveSession).isEmpty());
        }
    }

    @Test
    void deterministicReadOnlyProjectionMayRetainAnInactiveExactDecision(
            @TempDir Path directory
    ) {
        String request = "Edit index.html. Then create style.css.";
        TaskResolution raw =
                TaskContractResolver.resolveFromUserRequest(request);
        var effectiveTurn = CapabilityPosturePolicy.apply(
                CapabilityPosture.PLAN_READ_ONLY,
                raw.contract());
        var projectedIntent = TaskIntentProjector.project(
                raw.intent(),
                effectiveTurn.taskContract(),
                TargetSource.RUNTIME_DEFAULT,
                "capability-posture-projection");
        TaskResolution projected = raw.projectOnto(
                effectiveTurn.taskContract(),
                projectedIntent);
        TurnPolicyTrace policy = policyTrace(projected);
        assertTrue(policy.lexicalDecisions().stream().anyMatch(decision ->
                decision.path().equals("style.css")
                        && decision.selectedForPath()
                        && !decision.activeInResolution()));

        String sessionId = "read-only-inactive-decision";
        JsonSessionStore store = new JsonSessionStore(directory);
        try (SessionWriterLease writer =
                store.acquireSessionWriter(sessionId)) {
            writer.appendTurn(
                    sessionId,
                    turnRecord(1, request, policy, ""));
        }

        TurnPolicyTrace loaded = store.loadTurns(sessionId).getFirst()
                .policyTrace();
        assertEquals(policy.lexicalDecisions(), loaded.lexicalDecisions());
        assertEquals(policy.rolefulTargets(), loaded.rolefulTargets());
    }

    @Test
    void lateRawIdentityConflictBlocksLaterAppendAndEnvelopePreflight(
            @TempDir Path directory
    ) throws Exception {
        String sourceSession = "conflict-row-source";
        TurnPolicyTrace policy = policyTrace(
                TaskContractResolver.resolveFromUserRequest(REQUEST));
        JsonSessionStore sourceStore = new JsonSessionStore(directory);
        try (SessionWriterLease writer =
                sourceStore.acquireSessionWriter(sourceSession)) {
            writer.appendTurn(
                    sourceSession,
                    turnRecord(1, REQUEST, policy, ""));
        }
        ObjectNode exact = (ObjectNode) JSON.readTree(Files.readString(
                directory.resolve(sourceSession + ".turns.jsonl")));
        exact.put("turnNumber", 1);
        ObjectNode redacted = exact.deepCopy();
        redacted.put("turnNumber", 2);
        redactLexicalEvidence(redacted);

        String sessionId = "late-raw-conflict";
        Path turnLog = directory.resolve(sessionId + ".turns.jsonl");
        String poisoned = JSON.writeValueAsString(exact)
                + "\n"
                + JSON.writeValueAsString(redacted)
                + "\n";
        Files.writeString(turnLog, poisoned);
        JsonSessionStore store = new JsonSessionStore(directory);
        assertTrue(store.loadTurns(sessionId).isEmpty());

        String traceId = "late-raw-conflict-trace";
        try (SessionWriterLease writer =
                store.acquireSessionWriter(sessionId)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> writer.appendTurn(
                            sessionId,
                            turnRecord(3, REQUEST, policy, "")));
            assertEquals(poisoned, Files.readString(turnLog));

            assertEquals(
                    TurnEnvelopePersistenceReceipt.State.NO_WRITES_OBSERVED,
                    writer.persistTurnEnvelope(
                            sessionId,
                            turnRecord(3, REQUEST, policy, traceId),
                            matchingTrace(
                                    traceId,
                                    sessionId,
                                    3,
                                    policy))
                            .state());
        }
        assertEquals(poisoned, Files.readString(turnLog));
        assertFalse(Files.exists(
                directory.resolve("traces").resolve(sessionId)));
    }

    @Test
    void deniedMutationRequestRoundTripsAndBindsBothPolicyFlags(
            @TempDir Path directory
    ) {
        TurnPolicyTrace allowed = policyTrace(
                TaskContractResolver.resolveFromUserRequest(REQUEST));
        TurnPolicyTrace denied = copyPolicy(
                allowed,
                false,
                true,
                allowed.lexicalDecisions(),
                allowed.lexicalRegions());
        assertTrue(denied.mutationRequested());
        assertFalse(denied.mutationAllowed());

        String sessionId = "denied-mutation-request";
        String traceId = "denied-mutation-request-trace";
        JsonSessionStore store = new JsonSessionStore(directory);
        try (SessionWriterLease writer =
                store.acquireSessionWriter(sessionId)) {
            assertEquals(
                    TurnEnvelopePersistenceReceipt.State.BOTH_WRITES_OBSERVED,
                    writer.persistTurnEnvelope(
                            sessionId,
                            turnRecord(1, REQUEST, denied, traceId),
                            matchingTrace(
                                    traceId,
                                    sessionId,
                                    1,
                                    denied))
                            .state());
        }

        TurnPolicyTrace loadedPolicy = store.loadTurns(sessionId)
                .getFirst()
                .policyTrace();
        assertTrue(loadedPolicy.mutationRequested());
        assertFalse(loadedPolicy.mutationAllowed());
        LocalTurnTrace loadedTrace = store.loadTrace(sessionId, traceId)
                .orElseThrow();
        assertTrue(loadedTrace.taskContract().mutationRequested());
        assertFalse(loadedTrace.taskContract().mutationAllowed());
    }

    private static void assertReplayRejected(
            Path directory,
            String sessionId,
            TurnPolicyTrace originalPolicy,
            TurnPolicyTrace replayPolicy
    ) throws Exception {
        JsonSessionStore store = new JsonSessionStore(directory);
        Path turnLog = directory.resolve(sessionId + ".turns.jsonl");
        String before;
        try (SessionWriterLease writer =
                store.acquireSessionWriter(sessionId)) {
            writer.appendTurn(
                    sessionId,
                    turnRecord(1, REPEATED_REQUEST, originalPolicy, ""));
            before = Files.readString(turnLog);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> writer.appendTurn(
                            sessionId,
                            turnRecord(2, "yes", replayPolicy, "")));
        }

        assertEquals(before, Files.readString(turnLog));
        assertEquals(1, store.loadTurns(sessionId).size());
    }

    private static int firstNonSelectedDecision(TurnPolicyTrace policy) {
        for (int index = 0;
                index < policy.lexicalDecisions().size();
                index++) {
            if (!policy.lexicalDecisions().get(index).selectedForPath()) {
                return index;
            }
        }
        throw new AssertionError(
                "fixture must contain a non-selected repeated occurrence");
    }

    private static TurnPolicyTrace policyTrace(TaskResolution resolution) {
        return TurnPolicyTrace.from(
                resolution,
                "APPLY",
                List.of("talos.read_file", "talos.edit_file"),
                List.of("tool_use:read_file", "tool_use:edit_file"));
    }

    private static TurnPolicyTrace copyPolicy(
            TurnPolicyTrace source,
            boolean mutationAllowed,
            boolean mutationRequested,
            List<TurnPolicyTrace.LexicalDecisionEvidence> decisions,
            List<TurnPolicyTrace.LexicalRegionEvidence> regions
    ) {
        return new TurnPolicyTrace(
                source.taskType(),
                mutationAllowed,
                source.verificationRequired(),
                source.expectedTargets(),
                source.forbiddenTargets(),
                source.initialPhase(),
                source.finalPhase(),
                source.nativeTools(),
                source.promptTools(),
                source.blocks(),
                source.classificationReason(),
                source.rolefulTargets(),
                decisions,
                regions,
                mutationRequested);
    }

    private static TurnPolicyTrace withRole(
            TurnPolicyTrace source,
            String path,
            TargetRole role
    ) {
        List<TurnPolicyTrace.RolefulTarget> roles = source.rolefulTargets()
                .stream()
                .map(target -> target.path().equals(path)
                        ? new TurnPolicyTrace.RolefulTarget(
                                target.path(),
                                role.name(),
                                target.source(),
                                target.reason(),
                                target.sourceText(),
                                target.confidence(),
                                target.startOffset(),
                                target.authorityEndOffset(),
                                target.endOffset())
                        : target)
                .toList();
        return new TurnPolicyTrace(
                source.taskType(),
                source.mutationAllowed(),
                source.verificationRequired(),
                source.expectedTargets(),
                source.forbiddenTargets(),
                source.initialPhase(),
                source.finalPhase(),
                source.nativeTools(),
                source.promptTools(),
                source.blocks(),
                source.classificationReason(),
                roles,
                source.lexicalDecisions(),
                source.lexicalRegions(),
                source.mutationRequested());
    }

    private static TurnPolicyTrace.LexicalDecisionEvidence withOrdinal(
            TurnPolicyTrace.LexicalDecisionEvidence source,
            int ordinal
    ) {
        return new TurnPolicyTrace.LexicalDecisionEvidence(
                ordinal,
                source.owner(),
                source.path(),
                source.pathSha256(),
                source.storedPathSha256(),
                source.authority(),
                source.action(),
                source.actionFamily(),
                source.source(),
                source.evidenceState(),
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

    private static TurnPolicyTrace.LexicalDecisionEvidence
            withSubstitutedPath(
                    TurnPolicyTrace.LexicalDecisionEvidence source,
                    String path
            ) {
        String pathSha256 = MutationPlanSelection.pathSha256(path);
        String occurrenceSha256 = MutationPlanSelection.occurrenceSha256(
                source.owner(),
                pathSha256,
                source.authority(),
                source.action(),
                source.actionFamily(),
                source.source(),
                source.sourceRequestSha256(),
                source.sourceRequestChars(),
                source.sourceRequestBytes(),
                source.actionStartOffset(),
                source.actionEndOffset(),
                source.targetStartOffset(),
                source.startOffset(),
                source.authorityEndOffset(),
                source.endOffset(),
                source.sourceSpanSha256());
        return new TurnPolicyTrace.LexicalDecisionEvidence(
                source.ordinal(),
                source.owner(),
                path,
                pathSha256,
                pathSha256,
                source.authority(),
                source.action(),
                source.actionFamily(),
                source.source(),
                source.evidenceState(),
                source.sourceRequestSha256(),
                source.sourceRequestChars(),
                source.sourceRequestBytes(),
                source.actionStartOffset(),
                source.actionEndOffset(),
                source.targetStartOffset(),
                source.startOffset(),
                source.authorityEndOffset(),
                source.endOffset(),
                true,
                false,
                source.sourceSpanSha256(),
                occurrenceSha256);
    }

    private static TurnRecord turnRecord(
            int turnNumber,
            String request,
            TurnPolicyTrace policy,
            String traceId
    ) {
        return new TurnRecord(
                turnNumber,
                Instant.parse("2026-08-01T00:00:00Z")
                        .plusSeconds(turnNumber),
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
        return LocalTurnTrace.builder(
                        traceId,
                        sessionId,
                        turnNumber,
                        TRACE_TIME)
                .taskContract(new LocalTurnTrace.TaskContractSummary(
                        policy.taskType(),
                        policy.mutationAllowed(),
                        policy.verificationRequired(),
                        policy.mutationRequested(),
                        policy.expectedTargets(),
                        policy.forbiddenTargets(),
                        policy.classificationReason(),
                        policy.rolefulTargets().stream()
                                .map(T1204StoreEvidenceBindingTest
                                        ::rolefulSummary)
                                .toList()))
                .toolSurface(
                        policy.nativeTools(),
                        policy.promptTools(),
                        "selected for resolved task contract")
                .event(LexicalPlanEvidenceTraceEvent.create(
                        TRACE_TIME,
                        policy))
                .build();
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

    private static void redactLexicalEvidence(ObjectNode root) {
        ObjectNode policy = (ObjectNode) root.get("policyTrace");
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

    private static void writeLegacyTurnWithoutLexicalEvidence(
            Path directory,
            String sessionId,
            String sourceSession,
            TurnPolicyTrace currentPolicy
    ) throws Exception {
        JsonSessionStore sourceStore = new JsonSessionStore(directory);
        try (SessionWriterLease writer =
                sourceStore.acquireSessionWriter(sourceSession)) {
            writer.appendTurn(
                    sourceSession,
                    turnRecord(1, REQUEST, currentPolicy, ""));
        }
        ObjectNode legacy = (ObjectNode) JSON.readTree(Files.readString(
                directory.resolve(sourceSession + ".turns.jsonl")));
        ObjectNode policy = (ObjectNode) legacy.get("policyTrace");
        policy.remove("lexicalEvidenceSchemaVersion");
        policy.remove("lexicalDecisions");
        policy.remove("lexicalRegions");
        policy.set("rolefulTargets", JSON.createArrayNode());
        Files.writeString(
                directory.resolve(sessionId + ".turns.jsonl"),
                JSON.writeValueAsString(legacy) + "\n");
    }
}
