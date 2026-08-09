package dev.talos.cli.repl;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class TalosBootstrapFailedTurnDiagnosticsTest {
    private static final Instant PRIOR_STARTED =
            Instant.parse("2026-08-03T16:00:00Z");

    @TempDir
    Path home;

    private final List<ReplRouter> routers = new ArrayList<>();
    private String previousUserHome;

    @BeforeEach
    void isolateUserHome() {
        previousUserHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void closeRoutersAndRestoreUserHome() {
        try {
            for (int index = routers.size() - 1; index >= 0; index--) {
                routers.get(index).getRuntimeSession().close();
            }
        } finally {
            if (previousUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousUserHome);
            }
        }
    }

    @Test
    void selectorFreezesOnlyOneExactAbnormalPhysicalTail() throws Exception {
        Path workspace = home.resolve("selector-workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        Path sessions = home.resolve("selector-sessions");
        JsonSessionStore store = new JsonSessionStore(sessions);
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        String priorId = persistTerminal(
                store,
                workspace,
                PRIOR_STARTED,
                "selector-failed",
                "failed",
                "FAILED");

        var selected = TalosBootstrap.unambiguousSavedTerminalTurnAtStartup(
                store, workspaceId).orElseThrow();
        assertEquals(priorId, selected.storageSessionId());
        assertEquals(1, selected.appendOrdinal());
        assertEquals(1, selected.turnNumber());
        assertEquals("selector-failed", selected.traceId());
        assertEquals("failed", selected.status());

        try (SessionWriterLease writer = store.acquireSessionWriter(priorId)) {
            writer.appendTurn(
                    priorId,
                    ordinaryRow(
                            2,
                            PRIOR_STARTED.plusSeconds(4),
                            "ORDINARY_PHYSICAL_TAIL_CANARY"));
        }
        assertTrue(TalosBootstrap.unambiguousSavedTerminalTurnAtStartup(
                        store, workspaceId).isEmpty(),
                "an ordinary physical tail must not backtrack to an older failure");
    }

    @Test
    void selectorFailsClosedWhenInventoryChangesAroundPairObservation()
            throws Exception {
        Path workspace = home.resolve("selector-inventory-race-workspace")
                .toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        JsonSessionStore persisted = new JsonSessionStore(
                home.resolve("selector-inventory-race-sessions"));
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        String selectedId = persistTerminal(
                persisted,
                workspace,
                PRIOR_STARTED,
                "selector-inventory-race-selected",
                "failed",
                "FAILED");
        AtomicInteger inventoryReads = new AtomicInteger();
        AtomicInteger competingWrites = new AtomicInteger();
        SessionStore observed = new SessionStore() {
            @Override
            public void save(SessionData data) {
                throw new AssertionError("startup selection must be read-only");
            }

            @Override
            public java.util.Optional<SessionData> load(String sessionId) {
                return java.util.Optional.empty();
            }

            @Override
            public boolean delete(String sessionId) {
                throw new AssertionError("startup selection must be read-only");
            }

            @Override
            public dev.talos.runtime.TurnLogInventoryObservation
                    observeTurnLogInventory(String observedWorkspaceId) {
                assertEquals(workspaceId, observedWorkspaceId);
                if (inventoryReads.incrementAndGet() == 2) {
                    persistTerminal(
                            persisted,
                            workspace,
                            PRIOR_STARTED.plusSeconds(1),
                            "selector-inventory-race-competitor",
                            "cancelled",
                            "CANCELLED");
                    competingWrites.incrementAndGet();
                }
                return persisted.observeTurnLogInventory(observedWorkspaceId);
            }

            @Override
            public dev.talos.runtime.TerminalTurnPairObservation
                    observeTerminalTurnPair(String sessionId) {
                assertEquals(selectedId, sessionId);
                return persisted.observeTerminalTurnPair(sessionId);
            }
        };

        assertTrue(TalosBootstrap.unambiguousSavedTerminalTurnAtStartup(
                        observed, workspaceId).isEmpty(),
                "a competing log created around pair observation must fail closed");
        assertEquals(2, inventoryReads.get());
        assertEquals(1, competingWrites.get());
        assertEquals(2,
                persisted.observeTurnLogInventory(workspaceId).sessionIds().size());
    }

    @Test
    void selectorFailsClosedForBlankTraceAmbiguityAndDegradedCompetitors()
            throws Exception {
        Path blankWorkspace = home.resolve("blank-workspace")
                .toAbsolutePath().normalize();
        Files.createDirectories(blankWorkspace);
        JsonSessionStore blankStore = new JsonSessionStore(
                home.resolve("blank-sessions"));
        String blankId;
        try (SessionWriterLease writer = blankStore.reserveNewSession(
                blankWorkspace, PRIOR_STARTED)) {
            blankId = writer.sessionId();
            writer.appendTurn(
                    blankId,
                    terminalRow(1, PRIOR_STARTED.plusSeconds(1), "", "failed"));
        }
        assertTrue(TalosBootstrap.unambiguousSavedTerminalTurnAtStartup(
                        blankStore,
                        JsonSessionStore.sessionIdFor(blankWorkspace)).isEmpty(),
                "a terminal status without trace identity is not selectable");

        Path ambiguousWorkspace = home.resolve("ambiguous-workspace")
                .toAbsolutePath().normalize();
        Files.createDirectories(ambiguousWorkspace);
        JsonSessionStore ambiguousStore = new JsonSessionStore(
                home.resolve("ambiguous-sessions"));
        persistTerminal(
                ambiguousStore,
                ambiguousWorkspace,
                PRIOR_STARTED,
                "older-by-clock",
                "failed",
                "FAILED");
        persistTerminal(
                ambiguousStore,
                ambiguousWorkspace,
                PRIOR_STARTED.minusSeconds(60),
                "newer-by-list-order-is-irrelevant",
                "cancelled",
                "CANCELLED");
        assertTrue(TalosBootstrap.unambiguousSavedTerminalTurnAtStartup(
                        ambiguousStore,
                        JsonSessionStore.sessionIdFor(ambiguousWorkspace)).isEmpty(),
                "two independently ordered logs have no global predecessor");

        Path degradedWorkspace = home.resolve("degraded-workspace")
                .toAbsolutePath().normalize();
        Files.createDirectories(degradedWorkspace);
        Path degradedRoot = home.resolve("degraded-sessions");
        JsonSessionStore degradedStore = new JsonSessionStore(degradedRoot);
        persistTerminal(
                degradedStore,
                degradedWorkspace,
                PRIOR_STARTED,
                "valid-terminal",
                "failed",
                "FAILED");
        String workspaceId = JsonSessionStore.sessionIdFor(degradedWorkspace);
        String degradedId = workspaceId
                + "-20260803170000-11111111111111111111111111111111";
        Files.writeString(
                degradedRoot.resolve(degradedId + ".turns.jsonl"),
                "not-json\n",
                StandardCharsets.UTF_8);
        assertTrue(TalosBootstrap.unambiguousSavedTerminalTurnAtStartup(
                        degradedStore, workspaceId).isEmpty(),
                "one degraded competing log makes the startup observation ambiguous");

        Path hiddenWorkspace = home.resolve("hidden-workspace")
                .toAbsolutePath().normalize();
        Files.createDirectories(hiddenWorkspace);
        Path hiddenRoot = home.resolve("hidden-sessions");
        JsonSessionStore hiddenStore = new JsonSessionStore(hiddenRoot);
        persistTerminal(
                hiddenStore,
                hiddenWorkspace,
                PRIOR_STARTED,
                "visible-terminal",
                "failed",
                "FAILED");
        String hiddenWorkspaceId = JsonSessionStore.sessionIdFor(
                hiddenWorkspace);
        String hiddenId = hiddenWorkspaceId
                + "-20260803170100-22222222222222222222222222222222";
        Files.createDirectory(
                hiddenRoot.resolve(hiddenId + ".turns.jsonl"));
        assertTrue(TalosBootstrap.unambiguousSavedTerminalTurnAtStartup(
                        hiddenStore, hiddenWorkspaceId).isEmpty(),
                "a matching non-regular competitor must not disappear from the strict inventory");

        Path caseWorkspace = home.resolve("case-workspace")
                .toAbsolutePath().normalize();
        Files.createDirectories(caseWorkspace);
        Path caseRoot = home.resolve("case-sessions");
        JsonSessionStore caseStore = new JsonSessionStore(caseRoot);
        persistTerminal(
                caseStore,
                caseWorkspace,
                PRIOR_STARTED,
                "visible-case-terminal",
                "failed",
                "FAILED");
        String caseWorkspaceId = JsonSessionStore.sessionIdFor(caseWorkspace);
        String caseAliasId = caseWorkspaceId.toUpperCase(
                java.util.Locale.ROOT)
                + "-20260803170200-33333333333333333333333333333333";
        Files.writeString(
                caseRoot.resolve(caseAliasId + ".TURNS.JSONL"),
                "not-json\n",
                StandardCharsets.UTF_8);
        assertTrue(TalosBootstrap.unambiguousSavedTerminalTurnAtStartup(
                        caseStore, caseWorkspaceId).isEmpty(),
                "a filesystem case alias must remain a competing physical log");
    }

    @Test
    void productionBootstrapRendersBareLastWithoutRestoringFailedMemory()
            throws Exception {
        Path workspace = home.resolve("installed-shape-workspace")
                .toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        Path sessions = home.resolve(".talos").resolve("sessions");
        JsonSessionStore archive = new JsonSessionStore(sessions);
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        String priorId = persistTerminal(
                archive,
                workspace,
                PRIOR_STARTED,
                "trc-restart-failure",
                "failed",
                "FAILED");
        Map<String, String> priorBytesBefore = priorArtifactHashes(
                sessions, priorId);

        ReplRouter router = TalosBootstrap.create(
                sessionState(),
                sessionConfig(),
                new PrintStream(java.io.OutputStream.nullOutputStream()),
                workspace);
        routers.add(router);

        SessionWriterLease activeStore = assertInstanceOf(
                SessionWriterLease.class,
                router.getRuntimeSession().store());
        assertNotEquals(priorId, activeStore.sessionId());
        assertTrue(activeStore.sessionId().startsWith(workspaceId + "-"));

        Result.TrustedInfo last = assertInstanceOf(
                Result.TrustedInfo.class,
                router.getRegistry().execute(
                        "last", "trace", router.context()));
        assertTrue(last.text.contains(
                "Saved Startup Terminal-Turn Observation"), last.text);
        assertTrue(last.text.contains("Trace: trc-restart-failure"), last.text);
        assertTrue(last.text.contains("Trace pair: VALID"), last.text);
        assertTrue(last.text.contains(
                "Settlement: FAILED at ASSISTANT_EXECUTION"), last.text);
        assertTrue(last.text.contains(
                "class=java.lang.IllegalStateException"), last.text);
        assertFalse(last.text.contains("FAILED_USER_REQUEST_CANARY"), last.text);
        assertFalse(last.text.contains("FAILED_ASSISTANT_TEXT_CANARY"), last.text);

        assertFalse(router.context().conversationManager().hasHistory());
        assertFalse(String.valueOf(router.context().memory().get()).contains(
                "FAILED_USER_REQUEST_CANARY"));
        assertFalse(String.valueOf(router.context().memory().get()).contains(
                "FAILED_ASSISTANT_TEXT_CANARY"));

        Result.Info resume = assertInstanceOf(
                Result.Info.class,
                router.getRegistry().execute(
                        "session",
                        "resume " + priorId,
                        router.context()));
        assertEquals("No saved session found for this workspace.", resume.text);
        assertFalse(router.context().conversationManager().hasHistory(),
                "failed diagnostics must not become conversation memory");
        assertFalse(String.valueOf(router.context().memory().get()).contains(
                "FAILED_USER_REQUEST_CANARY"));
        assertFalse(String.valueOf(router.context().memory().get()).contains(
                "FAILED_ASSISTANT_TEXT_CANARY"));
        assertEquals(priorBytesBefore, priorArtifactHashes(sessions, priorId),
                "diagnostic inspection and failed-only resume are read-only");
    }

    private static String persistTerminal(
            JsonSessionStore store,
            Path workspace,
            Instant started,
            String traceId,
            String rowStatus,
            String settlementKind
    ) {
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        try (SessionWriterLease writer = store.reserveNewSession(
                workspace, started)) {
            writer.persistTurnEnvelope(
                    writer.sessionId(),
                    terminalRow(
                            1,
                            started.plusSeconds(2),
                            traceId,
                            rowStatus),
                    terminalTrace(
                            traceId,
                            workspaceId,
                            settlementKind));
            return writer.sessionId();
        }
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
                "FAILED_USER_REQUEST_CANARY",
                "FAILED_ASSISTANT_TEXT_CANARY",
                List.of(),
                0,
                0,
                0,
                "",
                status,
                TurnPolicyTrace.empty(),
                traceId);
    }

    private static TurnRecord ordinaryRow(
            int turn,
            Instant timestamp,
            String traceId
    ) {
        return new TurnRecord(
                turn,
                timestamp,
                1,
                "ordinary request",
                "ordinary answer",
                List.of(),
                0,
                0,
                0,
                "",
                "ok",
                TurnPolicyTrace.empty(),
                traceId);
    }

    private static LocalTurnTrace terminalTrace(
            String traceId,
            String workspaceId,
            String settlementKind
    ) {
        String stage = "CANCELLED".equals(settlementKind)
                ? "GENERATION_COORDINATION"
                : "NO_RESULT".equals(settlementKind)
                        ? "TURN_FINALIZATION"
                        : "ASSISTANT_EXECUTION";
        return LocalTurnTrace.builder(
                        traceId,
                        workspaceId,
                        1,
                        PRIOR_STARTED.toString())
                .mode("ask")
                .event(TurnTraceEvent.simple(
                        "TRACE_STARTED",
                        PRIOR_STARTED.toString(),
                        Map.of("turnNumber", 1, "redactionMode", "DEFAULT")))
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
                                "errorKind", "NO_RESULT".equals(settlementKind)
                                        ? "NONE"
                                        : "CANCELLED".equals(settlementKind)
                                                ? "CANCELLATION"
                                                : "UNEXPECTED",
                                "errorCode", "NO_RESULT".equals(settlementKind)
                                        ? 0 : 500,
                                "exceptionClass", "NO_RESULT".equals(settlementKind)
                                        ? ""
                                        : "CANCELLED".equals(settlementKind)
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

    private static Config sessionConfig() {
        Config config = new Config();
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("persistence", true);
        session.put("auto_load", false);
        config.data.put("session", session);
        return config;
    }

    private static SessionState sessionState() {
        return new SessionState() {
            private int k = 6;
            private boolean debug;

            @Override
            public int getK() {
                return k;
            }

            @Override
            public void setK(int value) {
                k = value;
            }

            @Override
            public boolean isDebug() {
                return debug;
            }

            @Override
            public void setDebug(boolean on) {
                debug = on;
            }
        };
    }

    private static Map<String, String> priorArtifactHashes(
            Path sessions,
            String sessionId
    ) throws Exception {
        Map<String, String> hashes = new LinkedHashMap<>();
        Path turnLog = sessions.resolve(sessionId + ".turns.jsonl");
        hashes.put("turn-log", sha256(Files.readAllBytes(turnLog)));
        Path traceDir = sessions.resolve("traces").resolve(sessionId);
        try (var paths = Files.list(traceDir)) {
            for (Path path : paths.sorted().toList()) {
                hashes.put(
                        path.getFileName().toString(),
                        sha256(Files.readAllBytes(path)));
            }
        }
        return hashes;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
