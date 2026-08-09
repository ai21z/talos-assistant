package dev.talos.cli.repl.slash;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.Result;
import dev.talos.runtime.SessionData;
import dev.talos.runtime.SessionStore;
import dev.talos.runtime.SessionWriterLease;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.TurnEvidenceProjectionTraceEvent;
import dev.talos.runtime.trace.TurnTraceEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplainLastTurnCrossSessionTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant PRIOR_STARTED =
            Instant.parse("2026-08-03T18:00:00Z");
    private static final Instant ACTIVE_STARTED =
            Instant.parse("2026-08-03T19:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void boundedStartupFallbackPrecedesOrdinaryActiveLogLoading() {
        Path workspace = workspace("bounded-before-ordinary-load");
        JsonSessionStore persisted = new JsonSessionStore(
                tempDir.resolve("sessions-bounded-before-ordinary-load"));
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        String priorId;
        try (SessionWriterLease prior = persisted.reserveNewSession(
                workspace, PRIOR_STARTED)) {
            priorId = prior.sessionId();
            prior.persistTurnEnvelope(
                    priorId,
                    terminalRow(
                            1,
                            PRIOR_STARTED.plusSeconds(2),
                            "trc-bounded-before-load",
                            "failed"),
                    terminalTrace(
                            "trc-bounded-before-load",
                            workspaceId,
                            1,
                            "FAILED",
                            "ASSISTANT_EXECUTION"));
        }
        String activeId = JsonSessionStore.newSessionInstanceId(
                workspace, ACTIVE_STARTED);
        AtomicInteger ordinaryLoads = new AtomicInteger();
        SessionStore observed = new SessionStore() {
            @Override
            public void save(SessionData data) {
                persisted.save(data);
            }

            @Override
            public Optional<SessionData> load(String sessionId) {
                return persisted.load(sessionId);
            }

            @Override
            public boolean delete(String sessionId) {
                return persisted.delete(sessionId);
            }

            @Override
            public List<TurnRecord> loadTurns(String sessionId) {
                ordinaryLoads.incrementAndGet();
                return persisted.loadTurns(sessionId);
            }

            @Override
            public dev.talos.runtime.TurnLogTailObservation
                    observeTurnLogTail(String sessionId) {
                return persisted.observeTurnLogTail(sessionId);
            }

            @Override
            public dev.talos.runtime.TraceArtifactObservation
                    observeTraceArtifact(
                            String sessionId,
                            int turnNumber,
                            String traceId
                    ) {
                return persisted.observeTraceArtifact(
                        sessionId, turnNumber, traceId);
            }

            @Override
            public dev.talos.runtime.TerminalTurnPairObservation
                    observeTerminalTurnPair(String sessionId) {
                return persisted.observeTerminalTurnPair(sessionId);
            }

            @Override
            public dev.talos.runtime.TurnLogInventoryObservation
                    observeTurnLogInventory(String workspaceId) {
                return persisted.observeTurnLogInventory(workspaceId);
            }
        };
        ExplainLastTurnCommand command = new ExplainLastTurnCommand(
                workspace,
                observed,
                ACTIVE_STARTED,
                activeId,
                observedReference(persisted, priorId),
                () -> 0);

        assertInstanceOf(
                Result.TrustedInfo.class,
                command.execute("trace", minimalContext()));
        assertEquals(0, ordinaryLoads.get(),
                "the bounded ABSENT gate must run before ordinary active-log recovery");
    }

    @Test
    void activeFailedTurnTraceIdentitySupersedesSavedStartupIdentity() {
        Path workspace = workspace("active-failure-supersedes-startup");
        JsonSessionStore store = new JsonSessionStore(
                tempDir.resolve("sessions-active-failure-supersedes-startup"));
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        String priorId;
        try (SessionWriterLease prior = store.reserveNewSession(
                workspace, PRIOR_STARTED)) {
            priorId = prior.sessionId();
            prior.persistTurnEnvelope(
                    priorId,
                    terminalRow(
                            1,
                            PRIOR_STARTED.plusSeconds(2),
                            "trc-prior-failed-turn",
                            "failed"),
                    terminalTrace(
                            "trc-prior-failed-turn",
                            workspaceId,
                            1,
                            "FAILED",
                            "GENERATION_COORDINATION"));
        }

        try (SessionWriterLease active = store.reserveNewSession(
                workspace, ACTIVE_STARTED)) {
            active.persistTurnEnvelope(
                    active.sessionId(),
                    terminalRow(
                            1,
                            ACTIVE_STARTED.plusSeconds(2),
                            "trc-active-failed-turn",
                            "failed"),
                    terminalTrace(
                            "trc-active-failed-turn",
                            workspaceId,
                            1,
                            "FAILED",
                            "GENERATION_COORDINATION"));
            ExplainLastTurnCommand command = new ExplainLastTurnCommand(
                    workspace,
                    active,
                    ACTIVE_STARTED,
                    active.sessionId(),
                    observedReference(store, priorId),
                    () -> 1);

            Result.TrustedInfo result = assertInstanceOf(
                    Result.TrustedInfo.class,
                    command.execute("trace", minimalContext()));

            String activeTraceLine =
                    "  Local trace: trc-active-failed-turn";
            assertEquals(1L, result.text.lines()
                    .filter(activeTraceLine::equals)
                    .count(), result.text);
            assertFalse(result.text.contains("\nLocal Trace\n"), result.text);
            assertFalse(result.text.contains("trc-prior-failed-turn"), result.text);
            assertFalse(result.text.contains(
                    "Saved Startup Terminal-Turn Observation"), result.text);

            for (String view : List.of("", "tools", "sources")) {
                Result.TrustedInfo bounded = assertInstanceOf(
                        Result.TrustedInfo.class,
                        command.execute(view, minimalContext()));
                assertFalse(bounded.text.contains("Local trace:"), bounded.text);
                assertFalse(bounded.text.contains("trc-prior-failed-turn"),
                        bounded.text);
                assertFalse(bounded.text.contains(
                        "Saved Startup Terminal-Turn Observation"), bounded.text);
            }
        }
    }

    @Test
    void turnBeginningDuringSavedRenderingWithholdsTheStartupObservation() {
        Path workspace = workspace("turn-begins-during-rendering");
        JsonSessionStore store = new JsonSessionStore(
                tempDir.resolve("sessions-turn-begins-during-rendering"));
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        String priorId;
        try (SessionWriterLease prior = store.reserveNewSession(
                workspace, PRIOR_STARTED)) {
            priorId = prior.sessionId();
            prior.persistTurnEnvelope(
                    priorId,
                    terminalRow(
                            1,
                            PRIOR_STARTED.plusSeconds(2),
                            "trc-turn-begins-during-rendering",
                            "failed"),
                    terminalTrace(
                            "trc-turn-begins-during-rendering",
                            workspaceId,
                            1,
                            "FAILED",
                            "ASSISTANT_EXECUTION"));
        }

        AtomicInteger observations = new AtomicInteger();
        try (SessionWriterLease active = store.reserveNewSession(
                workspace, ACTIVE_STARTED)) {
            ExplainLastTurnCommand command = new ExplainLastTurnCommand(
                    workspace,
                    active,
                    ACTIVE_STARTED,
                    active.sessionId(),
                    observedReference(store, priorId),
                    () -> observations.incrementAndGet() < 3 ? 0 : 1);

            Result.Info result = assertInstanceOf(
                    Result.Info.class,
                    command.execute("trace", minimalContext()));

            assertEquals(3, observations.get(),
                    "eligibility must be checked before and after rendering");
            assertTrue(result.text.contains("No completed turn"), result.text);
            assertFalse(result.text.contains(
                    "Saved Startup Terminal-Turn Observation"), result.text);
        }
    }

    @Test
    void begunActiveTurnPermanentlySuppressesTheStartupReferenceWithoutAPersistedRow() {
        Path workspace = workspace("active-turn-count");
        JsonSessionStore store = new JsonSessionStore(
                tempDir.resolve("sessions-active-turn-count"));
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        String priorId;
        try (SessionWriterLease prior = store.reserveNewSession(
                workspace, PRIOR_STARTED)) {
            priorId = prior.sessionId();
            prior.persistTurnEnvelope(
                    priorId,
                    terminalRow(
                            1,
                            PRIOR_STARTED.plusSeconds(2),
                            "trc-active-turn-count",
                            "failed"),
                    terminalTrace(
                            "trc-active-turn-count",
                            workspaceId,
                            1,
                            "FAILED",
                            "ASSISTANT_EXECUTION"));
        }

        AtomicInteger activeTurnCount = new AtomicInteger();
        try (SessionWriterLease active = store.reserveNewSession(
                workspace, ACTIVE_STARTED)) {
            ExplainLastTurnCommand command = new ExplainLastTurnCommand(
                    workspace,
                    active,
                    ACTIVE_STARTED,
                    active.sessionId(),
                    observedReference(store, priorId),
                    activeTurnCount::get);

            assertInstanceOf(
                    Result.TrustedInfo.class,
                    command.execute("trace", minimalContext()));

            activeTurnCount.incrementAndGet();
            Result.Info afterTurnBegan = assertInstanceOf(
                    Result.Info.class,
                    command.execute("trace", minimalContext()));
            assertTrue(afterTurnBegan.text.contains("No completed turn"),
                    afterTurnBegan.text);
            assertFalse(afterTurnBegan.text.contains(
                    "Saved Startup Terminal-Turn Observation"),
                    afterTurnBegan.text);

            active.delete(active.sessionId());
            Result.Info afterClear = assertInstanceOf(
                    Result.Info.class,
                    command.execute("trace", minimalContext()));
            assertTrue(afterClear.text.contains("No completed turn"),
                    afterClear.text);
            assertFalse(afterClear.text.contains(
                    "Saved Startup Terminal-Turn Observation"), afterClear.text);
        }
    }

    @Test
    void unavailableActiveTurnCounterFailsClosed() {
        Path workspace = workspace("counter-unavailable");
        JsonSessionStore store = new JsonSessionStore(
                tempDir.resolve("sessions-counter-unavailable"));
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        String priorId;
        try (SessionWriterLease prior = store.reserveNewSession(
                workspace, PRIOR_STARTED)) {
            priorId = prior.sessionId();
            prior.persistTurnEnvelope(
                    priorId,
                    terminalRow(
                            1,
                            PRIOR_STARTED.plusSeconds(2),
                            "trc-counter-unavailable",
                            "failed"),
                    terminalTrace(
                            "trc-counter-unavailable",
                            workspaceId,
                            1,
                            "FAILED",
                            "ASSISTANT_EXECUTION"));
        }

        try (SessionWriterLease active = store.reserveNewSession(
                workspace, ACTIVE_STARTED)) {
            ExplainLastTurnCommand command = new ExplainLastTurnCommand(
                    workspace,
                    active,
                    ACTIVE_STARTED,
                    active.sessionId(),
                    observedReference(store, priorId),
                    () -> {
                        throw new IllegalStateException("counter unavailable");
                    });

            Result.Info result = assertInstanceOf(
                    Result.Info.class,
                    command.execute("trace", minimalContext()));
            assertTrue(result.text.contains("No completed turn"), result.text);
            assertFalse(result.text.contains(
                    "Saved Startup Terminal-Turn Observation"), result.text);
        }
    }

    @Test
    void laterPriorAppendInvalidatesTheFrozenCoordinateWithoutBacktracking() {
        Path workspace = workspace("prior-append");
        JsonSessionStore store = new JsonSessionStore(
                tempDir.resolve("sessions-prior-append"));
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        String priorId;
        ExplainLastTurnCommand.SavedTerminalTurnReference reference;
        try (SessionWriterLease prior = store.reserveNewSession(
                workspace, PRIOR_STARTED)) {
            priorId = prior.sessionId();
            prior.persistTurnEnvelope(
                    priorId,
                    terminalRow(
                            1,
                            PRIOR_STARTED.plusSeconds(2),
                            "OLD_FAILED_TRACE_CANARY",
                            "failed"),
                    terminalTrace(
                            "OLD_FAILED_TRACE_CANARY",
                            workspaceId,
                            1,
                            "FAILED",
                            "ASSISTANT_EXECUTION"));
        }
        reference = observedReference(store, priorId);
        try (SessionWriterLease prior = store.acquireSessionWriter(priorId)) {
            prior.appendTurn(
                    priorId,
                    new TurnRecord(
                            2,
                            PRIOR_STARTED.plusSeconds(3),
                            1,
                            "NEW_ROW_USER_CANARY",
                            "NEW_ROW_ASSISTANT_CANARY",
                            List.of(),
                            0,
                            0,
                            0,
                            "",
                            "ok",
                            TurnPolicyTrace.empty(),
                            "NEW_ROW_TRACE_CANARY"));
        }

        try (SessionWriterLease active = store.reserveNewSession(
                workspace, ACTIVE_STARTED)) {
            ExplainLastTurnCommand command = new ExplainLastTurnCommand(
                    workspace,
                    active,
                    ACTIVE_STARTED,
                    active.sessionId(),
                    reference,
                    () -> 0);
            Result.Info result = assertInstanceOf(
                    Result.Info.class,
                    command.execute("trace", minimalContext()));

            assertTrue(result.text.contains("selected at startup is unavailable"),
                    result.text);
            assertFalse(result.text.contains("OLD_FAILED_TRACE_CANARY"), result.text);
            assertFalse(result.text.contains("NEW_ROW_USER_CANARY"), result.text);
            assertFalse(result.text.contains("NEW_ROW_ASSISTANT_CANARY"), result.text);
            assertFalse(result.text.contains("NEW_ROW_TRACE_CANARY"), result.text);
        }
    }

    @Test
    void appendBetweenFinalTailAndTraceObservationFailsClosed() {
        Path workspace = workspace("prior-append-during-final-observation");
        JsonSessionStore store = new JsonSessionStore(
                tempDir.resolve("sessions-prior-append-during-final-observation"));
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        String priorId;
        ExplainLastTurnCommand.SavedTerminalTurnReference reference;
        try (SessionWriterLease prior = store.reserveNewSession(
                workspace, PRIOR_STARTED)) {
            priorId = prior.sessionId();
            prior.persistTurnEnvelope(
                    priorId,
                    terminalRow(
                            1,
                            PRIOR_STARTED.plusSeconds(2),
                            "trc-race-old",
                            "failed"),
                    terminalTrace(
                            "trc-race-old",
                            workspaceId,
                            1,
                            "FAILED",
                            "ASSISTANT_EXECUTION"));
        }
        reference = observedReference(store, priorId);

        AtomicInteger priorTailReads = new AtomicInteger();
        AtomicInteger priorPairReads = new AtomicInteger();
        AtomicInteger injectedAppends = new AtomicInteger();
        String exactPriorId = priorId;
        SessionStore racingObservation = new SessionStore() {
            @Override
            public void save(SessionData data) {
                throw new AssertionError("diagnostic observation must not save");
            }

            @Override
            public Optional<SessionData> load(String sessionId) {
                return store.load(sessionId);
            }

            @Override
            public boolean delete(String sessionId) {
                throw new AssertionError("diagnostic observation must not delete");
            }

            @Override
            public List<TurnRecord> loadTurns(String sessionId) {
                return store.loadTurns(sessionId);
            }

            @Override
            public dev.talos.runtime.TurnLogTailObservation observeTurnLogTail(
                    String sessionId
            ) {
                var observed = store.observeTurnLogTail(sessionId);
                if (exactPriorId.equals(sessionId)
                        && priorTailReads.incrementAndGet() == 2) {
                    try (SessionWriterLease prior =
                                 store.acquireSessionWriter(exactPriorId)) {
                        var receipt = prior.persistTurnEnvelope(
                                exactPriorId,
                                terminalRow(
                                        2,
                                        PRIOR_STARTED.plusSeconds(3),
                                        "trc-race-new",
                                        "failed"),
                                terminalTrace(
                                        "trc-race-new",
                                        workspaceId,
                                        2,
                                        "FAILED",
                                        "MODE_ROUTING"));
                        assertEquals(
                                dev.talos.runtime.TurnEnvelopePersistenceReceipt
                                        .State.BOTH_WRITES_OBSERVED,
                                receipt.state());
                    }
                    injectedAppends.incrementAndGet();
                }
                return observed;
            }

            @Override
            public dev.talos.runtime.TraceArtifactObservation observeTraceArtifact(
                    String sessionId,
                    int turnNumber,
                    String traceId
            ) {
                return store.observeTraceArtifact(
                        sessionId, turnNumber, traceId);
            }

            @Override
            public dev.talos.runtime.TerminalTurnPairObservation
                    observeTerminalTurnPair(String sessionId) {
                if (!exactPriorId.equals(sessionId)
                        || priorPairReads.incrementAndGet() != 2) {
                    return store.observeTerminalTurnPair(sessionId);
                }
                try (SessionWriterLease prior =
                             store.acquireSessionWriter(exactPriorId)) {
                    var receipt = prior.persistTurnEnvelope(
                            exactPriorId,
                            terminalRow(
                                    2,
                                    PRIOR_STARTED.plusSeconds(3),
                                    "trc-race-new",
                                    "failed"),
                            terminalTrace(
                                    "trc-race-new",
                                    workspaceId,
                                    2,
                                    "FAILED",
                                    "MODE_ROUTING"));
                    assertEquals(
                            dev.talos.runtime.TurnEnvelopePersistenceReceipt
                                    .State.BOTH_WRITES_OBSERVED,
                            receipt.state());
                    injectedAppends.incrementAndGet();
                    return store.observeTerminalTurnPair(sessionId);
                }
            }

            @Override
            public dev.talos.runtime.TurnLogInventoryObservation
                    observeTurnLogInventory(String observedWorkspaceId) {
                return store.observeTurnLogInventory(observedWorkspaceId);
            }
        };

        try (SessionWriterLease active = store.reserveNewSession(
                workspace, ACTIVE_STARTED)) {
            ExplainLastTurnCommand command = new ExplainLastTurnCommand(
                    workspace,
                    racingObservation,
                    ACTIVE_STARTED,
                    active.sessionId(),
                    reference,
                    () -> 0);

            Result.Info result = assertInstanceOf(
                    Result.Info.class,
                    command.execute("trace", minimalContext()));

            assertEquals(0, priorTailReads.get());
            assertEquals(2, priorPairReads.get());
            assertEquals(1, injectedAppends.get());
            assertTrue(result.text.contains("selected at startup is unavailable"),
                    result.text);
            assertFalse(result.text.contains("trc-race-old"), result.text);
            assertFalse(result.text.contains("trc-race-new"), result.text);
            assertNoRowCanary(result.text);
            assertEquals(2, store.observeTurnLogTail(priorId).turn().turnNumber());
        }
    }

    @Test
    void competingWorkspaceTailCreatedImmediatelyBeforeReturnFailsClosed() {
        Path workspace = workspace("competing-tail-before-return");
        JsonSessionStore store = new JsonSessionStore(
                tempDir.resolve("sessions-competing-tail-before-return"));
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        String priorId;
        try (SessionWriterLease prior = store.reserveNewSession(
                workspace, PRIOR_STARTED)) {
            priorId = prior.sessionId();
            prior.persistTurnEnvelope(
                    priorId,
                    terminalRow(
                            1,
                            PRIOR_STARTED.plusSeconds(2),
                            "trc-selected-before-competitor",
                            "failed"),
                    terminalTrace(
                            "trc-selected-before-competitor",
                            workspaceId,
                            1,
                            "FAILED",
                            "ASSISTANT_EXECUTION"));
        }
        ExplainLastTurnCommand.SavedTerminalTurnReference reference =
                observedReference(store, priorId);
        AtomicInteger eligibilityChecks = new AtomicInteger();
        AtomicInteger competingWrites = new AtomicInteger();

        try (SessionWriterLease active = store.reserveNewSession(
                workspace, ACTIVE_STARTED)) {
            ExplainLastTurnCommand command = new ExplainLastTurnCommand(
                    workspace,
                    active,
                    ACTIVE_STARTED,
                    active.sessionId(),
                    reference,
                    () -> {
                        if (eligibilityChecks.incrementAndGet() == 4) {
                            try (SessionWriterLease competitor =
                                         store.reserveNewSession(
                                                 workspace,
                                                 ACTIVE_STARTED.plusSeconds(1))) {
                                var receipt = competitor.persistTurnEnvelope(
                                        competitor.sessionId(),
                                        terminalRow(
                                                1,
                                                ACTIVE_STARTED.plusSeconds(2),
                                                "trc-competing-before-return",
                                                "cancelled"),
                                        terminalTrace(
                                                "trc-competing-before-return",
                                                workspaceId,
                                                1,
                                                "CANCELLED",
                                                "GENERATION_COORDINATION"));
                                assertEquals(
                                        dev.talos.runtime
                                                .TurnEnvelopePersistenceReceipt
                                                .State.BOTH_WRITES_OBSERVED,
                                        receipt.state());
                                competingWrites.incrementAndGet();
                            }
                        }
                        return 0;
                    });

            Result.Info result = assertInstanceOf(
                    Result.Info.class,
                    command.execute("trace", minimalContext()));

            assertEquals(4, eligibilityChecks.get());
            assertEquals(1, competingWrites.get());
            assertTrue(result.text.contains("selected at startup is unavailable"),
                    result.text);
            assertFalse(result.text.contains(
                    "trc-selected-before-competitor"), result.text);
            assertFalse(result.text.contains(
                    "trc-competing-before-return"), result.text);
            assertNoRowCanary(result.text);
            var inventory = store.observeTurnLogInventory(workspaceId);
            assertTrue(inventory.exact());
            assertEquals(2, inventory.sessionIds().size());
        }
    }

    @Test
    void laterRowReplacementInvalidatesTheFrozenArtifactDigest()
            throws Exception {
        Path workspace = workspace("row-replacement");
        Path sessions = tempDir.resolve("sessions-row-replacement");
        JsonSessionStore store = new JsonSessionStore(sessions);
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        String priorId;
        ExplainLastTurnCommand.SavedTerminalTurnReference reference;
        try (SessionWriterLease prior = store.reserveNewSession(
                workspace, PRIOR_STARTED)) {
            priorId = prior.sessionId();
            prior.persistTurnEnvelope(
                    priorId,
                    terminalRow(
                            1,
                            PRIOR_STARTED.plusSeconds(2),
                            "trc-replaced-row",
                            "failed"),
                    terminalTrace(
                            "trc-replaced-row",
                            workspaceId,
                            1,
                            "FAILED",
                            "ASSISTANT_EXECUTION"));
        }
        reference = observedReference(store, priorId);
        Path turnLog = sessions.resolve(priorId + ".turns.jsonl");
        ObjectNode replacement = (ObjectNode) MAPPER.readTree(
                Files.readString(turnLog, StandardCharsets.UTF_8).strip());
        replacement.put("durationMs", 13L);
        replacement.put("assistantText", "ROW_REPLACED_ASSISTANT_CANARY");
        Files.writeString(
                turnLog,
                MAPPER.writeValueAsString(replacement) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
        var replacedPair = store.observeTerminalTurnPair(priorId);
        assertTrue(replacedPair.exact());
        assertEquals(1, replacedPair.rowObservation().appendOrdinal());
        assertEquals(reference.turnNumber(),
                replacedPair.rowObservation().turn().turnNumber());
        assertEquals(reference.timestamp(),
                replacedPair.rowObservation().turn().timestamp());
        assertEquals(reference.traceId(),
                replacedPair.rowObservation().turn().traceId());
        assertEquals(reference.status(),
                replacedPair.rowObservation().turn().status());
        assertFalse(reference.rowSha256().equals(
                replacedPair.rowObservation().rowSha256()));

        try (SessionWriterLease active = store.reserveNewSession(
                workspace, ACTIVE_STARTED)) {
            ExplainLastTurnCommand command = new ExplainLastTurnCommand(
                    workspace,
                    active,
                    ACTIVE_STARTED,
                    active.sessionId(),
                    reference,
                    () -> 0);
            Result.Info result = assertInstanceOf(
                    Result.Info.class,
                    command.execute("trace", minimalContext()));

            assertTrue(result.text.contains("selected at startup is unavailable"),
                    result.text);
            assertFalse(result.text.contains(
                    "ROW_REPLACED_ASSISTANT_CANARY"), result.text);
            assertFalse(result.text.contains("trc-replaced-row"), result.text);
            assertNoRowCanary(result.text);
        }
    }

    @Test
    void rowReplacementAfterRenderingBeforeReturnInvalidatesTheFrozenArtifact()
            throws Exception {
        Path workspace = workspace("row-replacement-during-rendering");
        Path sessions = tempDir.resolve(
                "sessions-row-replacement-during-rendering");
        JsonSessionStore store = new JsonSessionStore(sessions);
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        String traceId = "trc-row-replaced-during-rendering";
        String priorId;
        ExplainLastTurnCommand.SavedTerminalTurnReference reference;
        try (SessionWriterLease prior = store.reserveNewSession(
                workspace, PRIOR_STARTED)) {
            priorId = prior.sessionId();
            prior.persistTurnEnvelope(
                    priorId,
                    terminalRow(
                            1,
                            PRIOR_STARTED.plusSeconds(2),
                            traceId,
                            "failed"),
                    terminalTrace(
                            traceId,
                            workspaceId,
                            1,
                            "FAILED",
                            "ASSISTANT_EXECUTION"));
        }
        reference = observedReference(store, priorId);
        Path turnLog = sessions.resolve(priorId + ".turns.jsonl");

        AtomicInteger eligibilityChecks = new AtomicInteger();
        try (SessionWriterLease active = store.reserveNewSession(
                workspace, ACTIVE_STARTED)) {
            ExplainLastTurnCommand command = new ExplainLastTurnCommand(
                    workspace,
                    active,
                    ACTIVE_STARTED,
                    active.sessionId(),
                    reference,
                    () -> {
                        if (eligibilityChecks.incrementAndGet() == 4) {
                            try {
                                ObjectNode replacement =
                                        (ObjectNode) MAPPER.readTree(
                                                Files.readString(
                                                        turnLog,
                                                        StandardCharsets.UTF_8)
                                                        .strip());
                                replacement.put("durationMs", 13L);
                                replacement.put(
                                        "assistantText",
                                        "ROW_REPLACED_DURING_RENDER_CANARY");
                                Files.writeString(
                                        turnLog,
                                        MAPPER.writeValueAsString(replacement)
                                                + "\n",
                                        StandardCharsets.UTF_8,
                                        StandardOpenOption.WRITE,
                                        StandardOpenOption.TRUNCATE_EXISTING);
                            } catch (java.io.IOException failure) {
                                throw new AssertionError(failure);
                            }
                        }
                        return 0;
                    });

            Result.Info result = assertInstanceOf(
                    Result.Info.class,
                    command.execute("trace", minimalContext()));

            assertEquals(4, eligibilityChecks.get());
            var replacedPair = store.observeTerminalTurnPair(priorId);
            assertTrue(replacedPair.exact());
            assertEquals(1, replacedPair.rowObservation().appendOrdinal());
            assertEquals(reference.turnNumber(),
                    replacedPair.rowObservation().turn().turnNumber());
            assertEquals(reference.timestamp(),
                    replacedPair.rowObservation().turn().timestamp());
            assertEquals(reference.traceId(),
                    replacedPair.rowObservation().turn().traceId());
            assertEquals(reference.status(),
                    replacedPair.rowObservation().turn().status());
            assertFalse(reference.rowSha256().equals(
                    replacedPair.rowObservation().rowSha256()));
            assertTrue(result.text.contains("selected at startup is unavailable"),
                    result.text);
            assertFalse(result.text.contains(
                    "ROW_REPLACED_DURING_RENDER_CANARY"), result.text);
            assertFalse(result.text.contains(traceId), result.text);
            assertNoRowCanary(result.text);
        }
    }

    @Test
    void laterTraceReplacementInvalidatesTheFrozenArtifactDigest() {
        Path workspace = workspace("trace-replacement");
        JsonSessionStore store = new JsonSessionStore(
                tempDir.resolve("sessions-trace-replacement"));
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        String priorId;
        ExplainLastTurnCommand.SavedTerminalTurnReference reference;
        try (SessionWriterLease prior = store.reserveNewSession(
                workspace, PRIOR_STARTED)) {
            priorId = prior.sessionId();
            prior.persistTurnEnvelope(
                    priorId,
                    terminalRow(
                            1,
                            PRIOR_STARTED.plusSeconds(2),
                            "trc-replaced-artifact",
                            "failed"),
                    terminalTrace(
                            "trc-replaced-artifact",
                            workspaceId,
                            1,
                            "FAILED",
                            "ASSISTANT_EXECUTION"));
        }
        reference = observedReference(store, priorId);
        try (SessionWriterLease prior = store.acquireSessionWriter(priorId)) {
            prior.saveTrace(
                    priorId,
                    terminalTrace(
                            "trc-replaced-artifact",
                            workspaceId,
                            1,
                            "FAILED",
                            "MODE_ROUTING"));
        }

        try (SessionWriterLease active = store.reserveNewSession(
                workspace, ACTIVE_STARTED)) {
            ExplainLastTurnCommand command = new ExplainLastTurnCommand(
                    workspace,
                    active,
                    ACTIVE_STARTED,
                    active.sessionId(),
                    reference,
                    () -> 0);
            Result.Info result = assertInstanceOf(
                    Result.Info.class,
                    command.execute("trace", minimalContext()));

            assertTrue(result.text.contains("selected at startup is unavailable"),
                    result.text);
            assertFalse(result.text.contains("ASSISTANT_EXECUTION"), result.text);
            assertFalse(result.text.contains("MODE_ROUTING"), result.text);
            assertNoRowCanary(result.text);
        }
    }

    @Test
    void traceReplacementAfterRenderingBeforeReturnInvalidatesTheFrozenArtifact()
            throws Exception {
        Path workspace = workspace("trace-replacement-during-rendering");
        Path sessions = tempDir.resolve(
                "sessions-trace-replacement-during-rendering");
        JsonSessionStore store = new JsonSessionStore(sessions);
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        String traceId = "trc-replaced-during-rendering";
        String priorId;
        ExplainLastTurnCommand.SavedTerminalTurnReference reference;
        try (SessionWriterLease prior = store.reserveNewSession(
                workspace, PRIOR_STARTED)) {
            priorId = prior.sessionId();
            prior.persistTurnEnvelope(
                    priorId,
                    terminalRow(
                            1,
                            PRIOR_STARTED.plusSeconds(2),
                            traceId,
                            "failed"),
                    terminalTrace(
                            traceId,
                            workspaceId,
                            1,
                            "FAILED",
                            "ASSISTANT_EXECUTION"));
        }
        reference = observedReference(store, priorId);

        AtomicInteger eligibilityChecks = new AtomicInteger();
        String exactPriorId = priorId;
        try (SessionWriterLease active = store.reserveNewSession(
                workspace, ACTIVE_STARTED)) {
            ExplainLastTurnCommand command = new ExplainLastTurnCommand(
                    workspace,
                    active,
                    ACTIVE_STARTED,
                    active.sessionId(),
                    reference,
                    () -> {
                        if (eligibilityChecks.incrementAndGet() == 3) {
                            try (SessionWriterLease prior =
                                         store.acquireSessionWriter(exactPriorId)) {
                                prior.saveTrace(
                                        exactPriorId,
                                        terminalTrace(
                                                traceId,
                                                workspaceId,
                                                1,
                                                "FAILED",
                                                "MODE_ROUTING"));
                            }
                        }
                        return 0;
                    });

            Result.Info result = assertInstanceOf(
                    Result.Info.class,
                    command.execute("trace", minimalContext()));

            assertEquals(3, eligibilityChecks.get());
            assertTrue(result.text.contains("selected at startup is unavailable"),
                    result.text);
            assertFalse(result.text.contains("ASSISTANT_EXECUTION"), result.text);
            assertFalse(result.text.contains("MODE_ROUTING"), result.text);
            assertNoRowCanary(result.text);
        }
    }

    @Test
    void frozenPriorFailureRendersForEveryBareViewWithoutMutatingStorage()
            throws Exception {
        Path workspace = workspace("restart");
        Path sessions = tempDir.resolve("sessions-restart");
        JsonSessionStore store = new JsonSessionStore(sessions);
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        String priorId;
        TurnRecord priorRow = terminalRow(
                1,
                PRIOR_STARTED.plusSeconds(2),
                "trc-prior-failure",
                "failed");
        try (SessionWriterLease prior = store.reserveNewSession(
                workspace, PRIOR_STARTED)) {
            priorId = prior.sessionId();
            prior.persistTurnEnvelope(
                    priorId,
                    priorRow,
                    terminalTrace(
                            "trc-prior-failure",
                            workspaceId,
                            1,
                            "FAILED",
                            "ASSISTANT_PREPARATION"));
        }
        ExplainLastTurnCommand.SavedTerminalTurnReference reference =
                observedReference(store, priorId);

        try (SessionWriterLease active = store.reserveNewSession(
                workspace, ACTIVE_STARTED)) {
            ExplainLastTurnCommand command = new ExplainLastTurnCommand(
                    workspace,
                    active,
                    ACTIVE_STARTED,
                    active.sessionId(),
                    reference,
                    () -> 0);
            Map<String, String> before = byteManifest(sessions);

            for (String view : List.of("", "tools", "sources", "trace")) {
                Result.TrustedInfo result = assertInstanceOf(
                        Result.TrustedInfo.class,
                        command.execute(view, minimalContext()));
                assertTrue(result.text.contains(
                        "Saved Startup Terminal-Turn Observation"), result.text);
                assertTrue(result.text.contains("Trace pair: VALID"), result.text);
                assertTrue(result.text.contains(
                        "Settlement: FAILED at ASSISTANT_PREPARATION"), result.text);
                assertTrue(result.text.contains(displayId(workspaceId, priorId)),
                        result.text);
                assertFalse(result.text.contains("Local trace:"), result.text);
                assertNoRowCanary(result.text);
            }

            assertTrue(active.isOpen());
            assertEquals(before, byteManifest(sessions),
                    "saved diagnostics must be a byte-stable read");

            active.persistTurnEnvelope(
                    active.sessionId(),
                    terminalRow(
                            1,
                            ACTIVE_STARTED.plusSeconds(2),
                            "trc-active-failure",
                            "failed"),
                    terminalTrace(
                            "trc-active-failure",
                            workspaceId,
                            1,
                            "FAILED",
                            "ASSISTANT_EXECUTION"));

            Result.TrustedInfo activeResult = assertInstanceOf(
                    Result.TrustedInfo.class,
                    command.execute("trace", minimalContext()));
            assertTrue(activeResult.text.contains(
                    "Settlement: FAILED at ASSISTANT_EXECUTION"), activeResult.text);
            assertFalse(activeResult.text.contains(
                    "Saved Startup Terminal-Turn Observation"), activeResult.text);
            assertNoRowCanary(activeResult.text);
        }
    }

    @Test
    void legacyBareHashReferenceRemainsInspectableAfterRestart() {
        Path workspace = workspace("legacy");
        JsonSessionStore store = new JsonSessionStore(
                tempDir.resolve("sessions-legacy"));
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        TurnRecord row = terminalRow(
                1,
                PRIOR_STARTED.plusSeconds(2),
                "trc-legacy-failure",
                "failed");
        try (SessionWriterLease legacy = store.acquireSessionWriter(workspaceId)) {
            legacy.persistTurnEnvelope(
                    workspaceId,
                    row,
                    terminalTrace(
                            "trc-legacy-failure",
                            workspaceId,
                            1,
                            "FAILED",
                            "ASSISTANT_PREPARATION"));
        }

        try (SessionWriterLease active = store.reserveNewSession(
                workspace, ACTIVE_STARTED)) {
            ExplainLastTurnCommand command = new ExplainLastTurnCommand(
                    workspace,
                    active,
                    ACTIVE_STARTED,
                    active.sessionId(),
                    observedReference(store, workspaceId),
                    () -> 0);
            Result.TrustedInfo result = assertInstanceOf(
                    Result.TrustedInfo.class,
                    command.execute("trace", minimalContext()));

            assertTrue(result.text.contains("Trace pair: VALID"), result.text);
            assertTrue(result.text.contains(workspaceId.substring(0, 8) + "…"),
                    result.text);
            assertNoRowCanary(result.text);
        }
    }

    @Test
    void cancelledAndNoResultReferencesRetainTheirExactTerminalKinds() {
        assertTerminalKind(
                "cancelled",
                "CANCELLED",
                "GENERATION_COORDINATION",
                "TURN_CANCELLED");
        assertTerminalKind(
                "no_result",
                "NO_RESULT",
                "TURN_FINALIZATION",
                "NO_RESULT");
    }

    @Test
    void missingFrozenRowFailsClosedWithoutScanningAnOlderValidFailure() {
        Path workspace = workspace("missing-reference");
        JsonSessionStore store = new JsonSessionStore(
                tempDir.resolve("sessions-missing-reference"));
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        try (SessionWriterLease older = store.reserveNewSession(
                workspace, PRIOR_STARTED.minusSeconds(30))) {
            older.persistTurnEnvelope(
                    older.sessionId(),
                    terminalRow(
                            1,
                            PRIOR_STARTED.minusSeconds(28),
                            "OLDER_VALID_TRACE_CANARY",
                            "failed"),
                    terminalTrace(
                            "OLDER_VALID_TRACE_CANARY",
                            workspaceId,
                            1,
                            "FAILED",
                            "MODE_ROUTING"));
        }
        String missingSession = workspaceId
                + "-20260803183000-11111111111111111111111111111111";
        TurnRecord missingRow = terminalRow(
                9,
                PRIOR_STARTED.plusSeconds(10),
                "trc-missing-frozen-row",
                "failed");

        try (SessionWriterLease active = store.reserveNewSession(
                workspace, ACTIVE_STARTED)) {
            ExplainLastTurnCommand command = new ExplainLastTurnCommand(
                    workspace,
                    active,
                    ACTIVE_STARTED,
                    active.sessionId(),
                    syntheticReference(missingSession, missingRow, '1'),
                    () -> 0);
            Result.Info result = assertInstanceOf(
                    Result.Info.class,
                    command.execute("trace", minimalContext()));

            assertTrue(result.text.contains("selected at startup is unavailable"),
                    result.text);
            assertFalse(result.text.contains("OLDER_VALID_TRACE_CANARY"), result.text);
            assertFalse(result.text.contains("MODE_ROUTING"), result.text);
        }
    }

    @Test
    void traceLookupNeverCrossesTheFrozenStorageNamespace() throws Exception {
        Path workspace = workspace("pairing");
        Path sessions = tempDir.resolve("sessions-pairing");
        JsonSessionStore store = new JsonSessionStore(sessions);
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        String selectedId;
        ExplainLastTurnCommand.SavedTerminalTurnReference reference;
        TurnRecord selectedRow = terminalRow(
                1,
                PRIOR_STARTED.plusSeconds(2),
                "trc-shared-id",
                "failed");
        try (SessionWriterLease selected = store.reserveNewSession(
                workspace, PRIOR_STARTED)) {
            selectedId = selected.sessionId();
            selected.persistTurnEnvelope(
                    selectedId,
                    selectedRow,
                    terminalTrace(
                            "trc-shared-id",
                            workspaceId,
                            1,
                            "FAILED",
                            "MODE_ROUTING"));
        }
        reference = observedReference(store, selectedId);
        Files.delete(sessions.resolve("traces")
                .resolve(selectedId)
                .resolve("000001-trc-shared-id.json"));

        try (SessionWriterLease active = store.reserveNewSession(
                workspace, ACTIVE_STARTED)) {
            active.saveTrace(
                    active.sessionId(),
                    terminalTrace(
                            "trc-shared-id",
                            workspaceId,
                            1,
                            "FAILED",
                            "ASSISTANT_EXECUTION"));
            ExplainLastTurnCommand command = new ExplainLastTurnCommand(
                    workspace,
                    active,
                    ACTIVE_STARTED,
                    active.sessionId(),
                    reference,
                    () -> 0);
            Result.Info result = assertInstanceOf(
                    Result.Info.class,
                    command.execute("trace", minimalContext()));

            assertTrue(result.text.contains("selected at startup is unavailable"),
                    result.text);
            assertFalse(result.text.contains("ASSISTANT_EXECUTION"), result.text);
            assertFalse(result.text.contains("MODE_ROUTING"), result.text);
            assertNoRowCanary(result.text);
        }
    }

    @Test
    void rowStatusAndSettlementMismatchMakesSavedObservationUnavailable() {
        Path workspace = workspace("status-mismatch");
        JsonSessionStore store = new JsonSessionStore(
                tempDir.resolve("sessions-status-mismatch"));
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        String priorId;
        TurnRecord row = terminalRow(
                1,
                PRIOR_STARTED.plusSeconds(2),
                "trc-status-mismatch",
                "failed");
        try (SessionWriterLease prior = store.reserveNewSession(
                workspace, PRIOR_STARTED)) {
            priorId = prior.sessionId();
            prior.persistTurnEnvelope(
                    priorId,
                    row,
                    terminalTrace(
                            "trc-status-mismatch",
                            workspaceId,
                            1,
                            "CANCELLED",
                            "GENERATION_COORDINATION"));
        }

        try (SessionWriterLease active = store.reserveNewSession(
                workspace, ACTIVE_STARTED)) {
            ExplainLastTurnCommand command = new ExplainLastTurnCommand(
                    workspace,
                    active,
                    ACTIVE_STARTED,
                    active.sessionId(),
                    observedReference(store, priorId),
                    () -> 0);
            Result.Info result = assertInstanceOf(
                    Result.Info.class,
                    command.execute("trace", minimalContext()));

            assertTrue(result.text.contains("selected at startup is unavailable"),
                    result.text);
            assertFalse(result.text.contains("Trace pair:"), result.text);
            assertFalse(result.text.contains("Settlement:"), result.text);
            assertFalse(result.text.contains("Evidence projection:"), result.text);
            assertFalse(result.text.contains("TURN_CANCELLED"), result.text);
            assertNoRowCanary(result.text);
        }
    }

    @Test
    void foreignAndOrdinaryReferencesAreRejectedAtTheCommandBoundary() {
        Path workspace = workspace("reference-boundary");
        Path foreignWorkspace = workspace("foreign-reference");
        JsonSessionStore store = new JsonSessionStore(
                tempDir.resolve("sessions-reference-boundary"));
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        String foreignId = JsonSessionStore.newSessionInstanceId(
                foreignWorkspace, PRIOR_STARTED);
        TurnRecord failed = terminalRow(
                1,
                PRIOR_STARTED.plusSeconds(2),
                "FOREIGN_TRACE_CANARY",
                "failed");
        String ordinaryId = workspaceId
                + "-20260803180000-22222222222222222222222222222222";
        TurnRecord ordinary = new TurnRecord(
                1,
                PRIOR_STARTED.plusSeconds(2),
                1,
                "ORDINARY_USER_CANARY",
                "ORDINARY_ASSISTANT_CANARY",
                List.of(),
                0,
                0,
                0,
                "",
                "ok",
                TurnPolicyTrace.empty(),
                "");

        try (SessionWriterLease active = store.reserveNewSession(
                workspace, ACTIVE_STARTED)) {
            for (var rejected : List.of(
                    syntheticReference(foreignId, failed, '2'),
                    syntheticReference(ordinaryId, ordinary, '3'))) {
                ExplainLastTurnCommand command = new ExplainLastTurnCommand(
                        workspace,
                        active,
                        ACTIVE_STARTED,
                        active.sessionId(),
                        rejected,
                        () -> 0);
                Result.Info result = assertInstanceOf(
                        Result.Info.class,
                        command.execute("trace", minimalContext()));
                assertTrue(result.text.contains("No completed turn"), result.text);
                assertFalse(result.text.contains("FOREIGN_TRACE_CANARY"), result.text);
                assertFalse(result.text.contains("ORDINARY_USER_CANARY"), result.text);
                assertFalse(result.text.contains("ORDINARY_ASSISTANT_CANARY"),
                        result.text);
            }
        }
    }

    private void assertTerminalKind(
            String status,
            String settlementKind,
            String stage,
            String expectedOutcome
    ) {
        Path workspace = workspace(status);
        JsonSessionStore store = new JsonSessionStore(
                tempDir.resolve("sessions-" + status));
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        TurnRecord row = terminalRow(
                1,
                PRIOR_STARTED.plusSeconds(2),
                "trc-" + status,
                status);
        String priorId;
        try (SessionWriterLease prior = store.reserveNewSession(
                workspace, PRIOR_STARTED)) {
            priorId = prior.sessionId();
            prior.persistTurnEnvelope(
                    priorId,
                    row,
                    terminalTrace(
                            "trc-" + status,
                            workspaceId,
                            1,
                            settlementKind,
                            stage));
        }
        try (SessionWriterLease active = store.reserveNewSession(
                workspace, ACTIVE_STARTED)) {
            ExplainLastTurnCommand command = new ExplainLastTurnCommand(
                    workspace,
                    active,
                    ACTIVE_STARTED,
                    active.sessionId(),
                    observedReference(store, priorId),
                    () -> 0);
            Result.TrustedInfo result = assertInstanceOf(
                    Result.TrustedInfo.class,
                    command.execute("trace", minimalContext()));
            assertTrue(result.text.contains(
                    "Settlement: " + settlementKind + " at " + stage), result.text);
            assertTrue(result.text.contains("Outcome:   " + expectedOutcome),
                    result.text);
            assertNoRowCanary(result.text);
        }
    }

    private Path workspace(String name) {
        return tempDir.resolve("workspace-" + name).toAbsolutePath().normalize();
    }

    private static TurnRecord terminalRow(
            int turn,
            Instant timestamp,
            String traceId,
            String status
    ) {
        return new TurnRecord(
                turn,
                timestamp,
                12,
                "ROW_USER_CANARY",
                "ROW_ASSISTANT_CANARY",
                List.of(new TurnRecord.ToolCallSummary(
                        "ROW_TOOL_CANARY", "ROW_PATH_CANARY", false)),
                1,
                0,
                1,
                "",
                status,
                TurnPolicyTrace.empty(),
                traceId);
    }

    private static LocalTurnTrace terminalTrace(
            String traceId,
            String workspaceId,
            int turn,
            String settlementKind,
            String stage
    ) {
        boolean cancelled = "CANCELLED".equals(settlementKind);
        boolean noResult = "NO_RESULT".equals(settlementKind);
        return LocalTurnTrace.builder(
                        traceId,
                        workspaceId,
                        turn,
                        PRIOR_STARTED.toString())
                .mode("ask")
                .event(TurnTraceEvent.simple(
                        "TRACE_STARTED",
                        PRIOR_STARTED.toString(),
                        Map.of("turnNumber", turn, "redactionMode", "DEFAULT")))
                .event(TurnTraceEvent.simple(
                        TurnEvidenceProjectionTraceEvent.TYPE,
                        PRIOR_STARTED.plusSeconds(1).toString(),
                        projection()))
                .event(TurnTraceEvent.simple(
                        "TURN_SETTLED",
                        PRIOR_STARTED.plusSeconds(1).toString(),
                        Map.of(
                                "schemaVersion", 1,
                                "settlementKind", settlementKind,
                                "stage", stage,
                                "errorKind", noResult
                                        ? "NONE"
                                        : cancelled ? "CANCELLATION" : "UNEXPECTED",
                                "errorCode", noResult ? 0 : 500,
                                "exceptionClass", noResult
                                        ? ""
                                        : cancelled
                                                ? "java.util.concurrent.CancellationException"
                                                : "java.lang.IllegalStateException",
                                "causalOwner", "UNKNOWN")))
                .event(TurnTraceEvent.simple(
                        "TRACE_COMPLETED",
                        PRIOR_STARTED.plusSeconds(2).toString(),
                        Map.of()))
                .build();
    }

    private static Map<String, Object> projection() {
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("schemaVersion", 1);
        marker.put("completeness", "INCOMPLETE");
        marker.put("reason", "NON_CONVERSATIONAL_SAFE_PROJECTION");
        marker.put("providerLifecycle", "NOT_OBSERVED");
        marker.put("retrieval", "UNAVAILABLE");
        marker.put("captureFinalizers", "OBSERVED_COMPLETE");
        marker.put("omittedCategories", List.of(
                "CHECKPOINT_VERIFICATION",
                "POLICY_CONTEXT",
                "TOOL_LIFECYCLE"));
        return marker;
    }

    private static String displayId(String workspaceId, String sessionId) {
        if (sessionId.startsWith(workspaceId + "-")) {
            return sessionId.substring(workspaceId.length() + 1);
        }
        return sessionId.substring(0, Math.min(8, sessionId.length())) + "…";
    }

    private static Context minimalContext() {
        return Context.builder(new Config()).build();
    }

    private static ExplainLastTurnCommand.SavedTerminalTurnReference
            observedReference(JsonSessionStore store, String sessionId) {
        var pair = store.observeTerminalTurnPair(sessionId);
        return ExplainLastTurnCommand.SavedTerminalTurnReference.from(
                sessionId,
                pair.rowObservation(),
                pair.traceObservation());
    }

    private static ExplainLastTurnCommand.SavedTerminalTurnReference
            syntheticReference(
                    String sessionId,
                    TurnRecord row,
                    char digestCharacter
            ) {
        return new ExplainLastTurnCommand.SavedTerminalTurnReference(
                sessionId,
                1,
                String.valueOf(digestCharacter).repeat(64),
                String.valueOf(digestCharacter).repeat(64),
                row.turnNumber(),
                row.timestamp(),
                row.traceId(),
                row.status());
    }

    private static void assertNoRowCanary(String text) {
        assertFalse(text.contains("ROW_USER_CANARY"), text);
        assertFalse(text.contains("ROW_ASSISTANT_CANARY"), text);
        assertFalse(text.contains("ROW_TOOL_CANARY"), text);
        assertFalse(text.contains("ROW_PATH_CANARY"), text);
    }

    private static Map<String, String> byteManifest(Path root) throws Exception {
        Map<String, String> manifest = new LinkedHashMap<>();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return manifest;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                if (path.equals(root)) continue;
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    manifest.put(relative + "/", "DIRECTORY");
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        manifest.put(relative, sha256(Files.readAllBytes(path)));
                    } catch (java.io.IOException locked) {
                        if (!relative.endsWith(".session.writer.lock")) {
                            throw locked;
                        }
                        manifest.put(relative, "LOCKED:" + Files.size(path));
                    }
                } else {
                    manifest.put(relative, "NON_REGULAR");
                }
            }
        }
        return manifest;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
