package dev.talos.cli.repl;

import dev.talos.core.context.ConversationManager;
import dev.talos.core.context.TokenBudget;
import dev.talos.core.Config;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.SessionData;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.context.ActiveTaskContextPolicy;
import dev.talos.runtime.context.ArtifactGoal;
import dev.talos.runtime.context.ProposalContextTestSupport;
import dev.talos.runtime.task.TaskContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prompt 1 - snapshot + JSONL reconciliation.
 *
 * <p>Verifies the bootstrap load path:
 * <ul>
 *   <li>When a snapshot with turns exists, snapshot wins (JSONL ignored).</li>
 *   <li>When no snapshot exists but JSONL does (crash path), JSONL is
 *       replayed into memory.</li>
 *   <li>When a snapshot exists but has zero turns and JSONL has turns,
 *       JSONL is replayed as the fallback.</li>
 *   <li>When neither exists, memory stays empty.</li>
 * </ul>
 */
class TalosBootstrapReconcileTest {

    @Test
    void restoresTypedEffectsWithoutReplayingFailedProseThroughEitherRestoreRoute(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "effect-restore";
        var effects = new dev.talos.tools.ToolMutationEffects(List.of("docs", "note.txt"), List.of("maybe.txt"));
        try (var writer = store.acquireSessionWriter(sid)) {
            writer.save(new SessionData(sid, "/ws", "", 1, Instant.now(),
                    List.of(new SessionData.Turn("user", "safe question"),
                            new SessionData.Turn("assistant", "safe answer"))));
            writer.appendTurn(sid, new TurnRecord(1, Instant.now(), 0L,
                    "failed mutation", "DO_NOT_REPLAY_FAILED_MODEL_PROSE",
                    List.of(new TurnRecord.ToolCallSummary("talos.apply_workspace_batch", "docs",
                            List.of("docs", "note.txt", "maybe.txt"), false, "partial", effects)),
                    1, 1, 0, "", "error"));
        }
        SessionMemory snapshot = new SessionMemory();
        TalosBootstrap.replaySnapshot(store, sid, snapshot, cm(snapshot));
        assertFalse(snapshot.get().contains("DO_NOT_REPLAY_FAILED_MODEL_PROSE"));
        assertEquals(List.of("docs", "note.txt", "maybe.txt"), snapshot.changeSummaryContext()
                .changedFiles().stream().map(dev.talos.runtime.context.ChangeSummaryContext.FileChange::path).toList());
        assertTrue(snapshot.changeSummaryContext().renderForChangeSummaryQuestion().contains("UNKNOWN"));
        SessionMemory crash = new SessionMemory();
        assertEquals(0, TalosBootstrap.replayTurnLog(store, sid, crash));
        assertTrue(crash.get() == null || !crash.get().contains("DO_NOT_REPLAY_FAILED_MODEL_PROSE"));
        assertEquals(snapshot.changeSummaryContext(), crash.changeSummaryContext());
    }

    private static ConversationManager cm(SessionMemory mem) {
        return new ConversationManager(mem, new TokenBudget());
    }

    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private static void withUserHome(Path home, CheckedRunnable body) throws Exception {
        String previous = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            body.run();
        } finally {
            if (previous == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previous);
            }
        }
    }

    private static Config configWithSessionPolicy(boolean persistence, boolean autoLoad) {
        Config cfg = new Config();
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("persistence", persistence);
        session.put("auto_load", autoLoad);
        cfg.data.put("session", session);
        return cfg;
    }

    private static SessionState sessionState() {
        return new SessionState() {
            private int k = 6;
            private boolean debug;

            public int getK() { return k; }
            public void setK(int k) { this.k = k; }
            public boolean isDebug() { return debug; }
            public void setDebug(boolean on) { debug = on; }
        };
    }

    @Test
    void snapshotWinsWhenPresentWithTurns(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "ws-1";

        // Snapshot has one paired turn.
        try (var writer = store.acquireSessionWriter(sid)) {
            writer.save(new SessionData(sid, "/ws", "", 1, Instant.now(),
                    List.of(new SessionData.Turn("user", "from-snap-u"),
                            new SessionData.Turn("assistant", "from-snap-a"))));

            // JSONL has a *different* turn - must be ignored when snapshot wins.
            writer.appendTurn(sid, new TurnRecord(1, Instant.now(), 0L,
                    "from-jsonl-u", "from-jsonl-a", List.of(), 0, 0, 0, ""));
        }

        SessionMemory mem = new SessionMemory();
        var snap = TalosBootstrap.replaySnapshot(store, sid, mem, cm(mem));
        assertEquals(1, snap.pairsReplayed(), "snapshot replay count");
        // Fallback must NOT run because snap > 0.
        String buf = mem.get();
        assertNotNull(buf);
        assertTrue(buf.contains("from-snap-u"));
        assertTrue(buf.contains("from-snap-a"));
        assertFalse(buf.contains("from-jsonl-u"),
                "JSONL content must not leak in when snapshot has turns");
    }

    @Test
    void snapshotRestoresActiveTaskContextAndArtifactGoal(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "ws-context";
        ActiveTaskContext context = ProposalContextTestSupport.accepted(
                3, "trace-save", List.of("README.md"), "Improve README.");
        ArtifactGoal goal = ArtifactGoal.fromActiveContext(context);
        try (var writer = store.acquireSessionWriter(sid)) {
            writer.save(new SessionData(sid, "/ws", "", 0, Instant.now(), List.of(), "",
                    context, goal));
        }

        SessionMemory mem = new SessionMemory();
        TalosBootstrap.replaySnapshot(store, sid, mem, cm(mem));

        assertEquals(ActiveTaskContext.State.ACTIVE, mem.activeTaskContext().state());
        assertEquals(List.of("README.md"), mem.activeTaskContext().targets());
        assertTrue(mem.activeTaskContext().hasBoundProposal());
        assertEquals(context.proposalProvenance(), mem.activeTaskContext().proposalProvenance());
        assertEquals(ArtifactGoal.ArtifactKind.README, mem.artifactGoal().artifactKind());
    }

    @Test
    void inspectSavedSessionReportsContextOnlySnapshotAvailable(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "ws-context-only";
        Instant created = Instant.parse("2026-01-15T10:30:00Z");
        ActiveTaskContext context = ProposalContextTestSupport.accepted(
                3, "trace-save", List.of("README.md"), "Improve README.");
        ArtifactGoal goal = ArtifactGoal.fromActiveContext(context);
        try (var writer = store.acquireSessionWriter(sid)) {
            writer.save(new SessionData(sid, "/ws", "", 0, created, List.of(), "llama_cpp/qwen2.5-coder-14b",
                    context, goal));
        }

        var summary = TalosBootstrap.inspectSavedSession(store, sid);

        assertTrue(summary.hasSavedSession(), "context-only snapshot should count as available");
        assertEquals(0, summary.pairsReplayed());
        assertEquals(created, summary.createdAt());
        assertEquals("llama_cpp/qwen2.5-coder-14b", summary.model());
    }

    @Test
    void restoreSavedSessionRestoresContextOnlySnapshot(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "ws-context-only-restore";
        ActiveTaskContext context = ProposalContextTestSupport.accepted(
                3, "trace-save", List.of("README.md"), "Improve README.");
        ArtifactGoal goal = ArtifactGoal.fromActiveContext(context);
        try (var writer = store.acquireSessionWriter(sid)) {
            writer.save(new SessionData(sid, "/ws", "", 0, Instant.now(), List.of(), "",
                    context, goal));
        }

        SessionMemory mem = new SessionMemory();
        var summary = TalosBootstrap.restoreSavedSession(store, sid, mem, cm(mem));

        assertTrue(summary.hasSavedSession(), "context-only restore should count as available");
        assertEquals(0, summary.pairsReplayed());
        assertEquals(ActiveTaskContext.State.ACTIVE, mem.activeTaskContext().state());
        assertEquals(List.of("README.md"), mem.activeTaskContext().targets());
        assertTrue(mem.activeTaskContext().hasBoundProposal());
        assertEquals(context.proposalProvenance(), mem.activeTaskContext().proposalProvenance());
        assertEquals(ArtifactGoal.ArtifactKind.README, mem.artifactGoal().artifactKind());
    }

    @Test
    void restoreSavedSessionFallsBackToJsonlForContextOnlySnapshot(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "ws-context-with-jsonl";
        ActiveTaskContext context = ProposalContextTestSupport.accepted(
                3, "trace-save", List.of("README.md"), "Improve README.");
        ArtifactGoal goal = ArtifactGoal.fromActiveContext(context);
        try (var writer = store.acquireSessionWriter(sid)) {
            writer.save(new SessionData(sid, "/ws", "", 0, Instant.now(), List.of(), "",
                    context, goal));
            writer.appendTurn(sid, new TurnRecord(1, Instant.now(), 0L,
                    "from-jsonl-u", "from-jsonl-a", List.of(), 0, 0, 0, ""));
        }

        SessionMemory mem = new SessionMemory();
        var summary = TalosBootstrap.restoreSavedSession(store, sid, mem, cm(mem));

        assertTrue(summary.hasSavedSession());
        assertEquals(1, summary.pairsReplayed());
        assertTrue(mem.get().contains("from-jsonl-u"));
        assertTrue(mem.get().contains("from-jsonl-a"));
        assertEquals(ActiveTaskContext.State.ACTIVE, mem.activeTaskContext().state());
        assertEquals(List.of("README.md"), mem.activeTaskContext().targets());
        assertTrue(mem.activeTaskContext().hasBoundProposal());
        assertEquals(context.proposalProvenance(), mem.activeTaskContext().proposalProvenance());
        assertEquals(ArtifactGoal.ArtifactKind.README, mem.artifactGoal().artifactKind());
    }

    @ParameterizedTest
    @ValueSource(strings = {"snapshot", "restore", "jsonl-fallback"})
    void legacyProposalRestorationRetainsContextWithoutAuthorityOrRewritingFiles(
            String route, @TempDir Path dir) throws Exception {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "legacy-proposal-" + route;
        ActiveTaskContext context = ActiveTaskContext.proposedChanges(
                3, "trace-save", List.of("README.md"), "Improve README.");
        boolean fallback = route.equals("jsonl-fallback");
        try (var writer = store.acquireSessionWriter(sid)) {
            writer.save(new SessionData(sid, "/ws", "", 0, Instant.now(), List.of(), "",
                    context, ArtifactGoal.fromActiveContext(context)));
            if (fallback) {
                writer.appendTurn(sid, new TurnRecord(1, Instant.now(), 0L,
                        "from-jsonl-u", "from-jsonl-a", List.of(), 0, 0, 0, ""));
            }
        }
        Path snapshot = dir.resolve(sid + ".json");
        Path turnLog = dir.resolve(sid + ".turns.jsonl");
        byte[] snapshotBefore = Files.readAllBytes(snapshot);
        byte[] turnLogBefore = fallback ? Files.readAllBytes(turnLog) : null;
        SessionMemory mem = new SessionMemory();
        var summary = route.equals("snapshot")
                ? TalosBootstrap.replaySnapshot(store, sid, mem, cm(mem))
                : TalosBootstrap.restoreSavedSession(store, sid, mem, cm(mem));

        assertTrue(summary.hasSavedSession());
        assertEquals(fallback ? 1 : 0, summary.pairsReplayed());
        if (fallback) {
            assertTrue(mem.get().contains("from-jsonl-u"));
            assertTrue(mem.get().contains("from-jsonl-a"));
        }
        assertEquals(context, mem.activeTaskContext());
        assertEquals(ActiveTaskContext.State.ACTIVE, mem.activeTaskContext().state());
        assertFalse(mem.activeTaskContext().hasBoundProposal());
        assertEquals(ArtifactGoal.none(), mem.artifactGoal());
        var decision = ActiveTaskContextPolicy.evaluate("apply those changes",
                TaskContract.unknown("apply those changes"), mem.activeTaskContext(), mem.artifactGoal(), 4);
        assertFalse(decision.consumed());
        assertFalse(decision.taskContract().mutationRequested());
        assertFalse(decision.taskContract().mutationAllowed());
        assertEquals(context, decision.memoryContext());
        assertEquals(ActiveTaskContext.State.SUPPRESSED, decision.planContext().state());
        assertArrayEquals(snapshotBefore, Files.readAllBytes(snapshot));
        if (fallback) assertArrayEquals(turnLogBefore, Files.readAllBytes(turnLog));
    }

    @Test
    void closeSavePersistsActiveTaskContextAndArtifactGoal(@TempDir Path home) throws Exception {
        Path workspace = home.resolve("workspace");
        java.nio.file.Files.createDirectories(workspace);

        withUserHome(home, () -> {
            ReplRouter router = TalosBootstrap.create(
                    sessionState(),
                    configWithSessionPolicy(true, false),
                    new PrintStream(java.io.OutputStream.nullOutputStream()),
                    workspace);
            ActiveTaskContext context = ProposalContextTestSupport.accepted(
                    3, "trace-save", List.of("README.md"), "Improve README.");
            router.context().memory().setActiveTaskContext(context);
            router.context().memory().setArtifactGoal(ArtifactGoal.fromActiveContext(context));

            router.getRuntimeSession().close();

            // T799: the close snapshot lands under this run's instance id,
            // not the legacy bare workspace hash.
            JsonSessionStore store = new JsonSessionStore(home.resolve(".talos").resolve("sessions"));
            String workspaceId = JsonSessionStore.sessionIdFor(workspace);
            var sessions = store.listSessions(workspaceId);
            assertEquals(1, sessions.size());
            assertTrue(sessions.get(0).sessionId().startsWith(workspaceId + "-"),
                    "close save uses a per-run instance id");
            SessionData saved = store.load(sessions.get(0).sessionId()).orElseThrow();
            assertEquals(ActiveTaskContext.State.ACTIVE, saved.activeTaskContext().state());
            assertEquals(List.of("README.md"), saved.activeTaskContext().targets());
            assertTrue(saved.activeTaskContext().hasBoundProposal());
            assertEquals(context.proposalProvenance(), saved.activeTaskContext().proposalProvenance());
            assertEquals(ArtifactGoal.ArtifactKind.README, saved.artifactGoal().artifactKind());
        });
    }

    /**
     * T799: an empty session (no turns, no sketch, no active task context)
     * is not worth a file. Before this, every REPL start-and-quit overwrote
     * the workspace's single session file with an empty snapshot; with
     * per-run instance ids it would instead litter one file per launch.
     */
    @Test
    void closeSaveSkipsEmptySession(@TempDir Path home) throws Exception {
        Path workspace = home.resolve("workspace");
        java.nio.file.Files.createDirectories(workspace);

        withUserHome(home, () -> {
            ReplRouter router = TalosBootstrap.create(
                    sessionState(),
                    configWithSessionPolicy(true, false),
                    new PrintStream(java.io.OutputStream.nullOutputStream()),
                    workspace);

            router.getRuntimeSession().close();

            JsonSessionStore store = new JsonSessionStore(home.resolve(".talos").resolve("sessions"));
            assertTrue(store.listSessions(JsonSessionStore.sessionIdFor(workspace)).isEmpty(),
                    "an empty session must not be saved on close");
        });
    }

    @Test
    void latestSessionIdPicksNewestAcrossLegacyAndInstanceFiles(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        Path workspace = dir.resolve("ws");
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        String instance = JsonSessionStore.newSessionInstanceId(
                workspace, Instant.parse("2026-06-11T10:00:00Z"));

        try (var writer = store.acquireSessionWriter(workspaceId)) {
            writer.save(new SessionData(workspaceId, "/ws", "", 1, Instant.parse("2026-06-09T10:00:00Z"),
                    List.of(new SessionData.Turn("user", "old-u", ""),
                            new SessionData.Turn("assistant", "old-a", "ok"))));
        }
        try (var writer = store.acquireSessionWriter(instance)) {
            writer.save(new SessionData(instance, "/ws", "", 1, Instant.parse("2026-06-11T10:00:00Z"),
                    List.of(new SessionData.Turn("user", "new-u", ""),
                            new SessionData.Turn("assistant", "new-a", "ok"))));
        }

        assertEquals(instance, TalosBootstrap.latestSessionId(store, workspaceId));

        // Auto-restore through the latest id replays the newest session only.
        SessionMemory mem = new SessionMemory();
        TalosBootstrap.restoreSavedSession(store,
                TalosBootstrap.latestSessionId(store, workspaceId), mem, cm(mem));
        assertTrue(mem.get().contains("new-a"));
        assertFalse(mem.get().contains("old-a"));
    }

    @Test
    void latestSessionIdFallsBackToLegacyIdWhenNothingIsStored(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        assertEquals("ws-hash", TalosBootstrap.latestSessionId(store, "ws-hash"),
                "empty store keeps the pre-T799 bare-hash lookup");
    }

    @Test
    void jsonlFallbackUsedWhenSnapshotMissing(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "ws-2";

        // No snapshot - simulate crash before onSessionEnd fired.
        try (var writer = store.acquireSessionWriter(sid)) {
            writer.appendTurn(sid, new TurnRecord(1, Instant.now(), 0L,
                    "q1", "a1", List.of(), 0, 0, 0, ""));
            writer.appendTurn(sid, new TurnRecord(2, Instant.now(), 0L,
                    "q2", "a2", List.of(), 0, 0, 0, ""));
        }

        SessionMemory mem = new SessionMemory();
        var snap = TalosBootstrap.replaySnapshot(store, sid, mem, cm(mem));
        assertEquals(0, snap.pairsReplayed(), "no snapshot, no pairs");

        int replayed = TalosBootstrap.replayTurnLog(store, sid, mem);
        assertEquals(2, replayed);
        String buf = mem.get();
        assertTrue(buf.contains("q1") && buf.contains("a1"));
        assertTrue(buf.contains("q2") && buf.contains("a2"));
    }

    @Test
    void jsonlFallbackUsedWhenSnapshotHasZeroTurns(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "ws-3";

        // Snapshot exists but empty (e.g., save fired with a session that
        // had no turns yet - defensive case).
        try (var writer = store.acquireSessionWriter(sid)) {
            writer.save(new SessionData(sid, "/ws", "", 0, Instant.now(), List.of()));
            writer.appendTurn(sid, new TurnRecord(1, Instant.now(), 0L,
                    "only-in-jsonl-u", "only-in-jsonl-a", List.of(), 0, 0, 0, ""));
        }

        SessionMemory mem = new SessionMemory();
        var snap = TalosBootstrap.replaySnapshot(store, sid, mem, cm(mem));
        assertEquals(0, snap.pairsReplayed());

        int replayed = TalosBootstrap.replayTurnLog(store, sid, mem);
        assertEquals(1, replayed);
        assertTrue(mem.get().contains("only-in-jsonl-u"));
        assertTrue(mem.get().contains("only-in-jsonl-a"));
    }

    @Test
    void nothingToReplayWhenBothAbsent(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        SessionMemory mem = new SessionMemory();
        var snap = TalosBootstrap.replaySnapshot(store, "ws-4", mem, cm(mem));
        int tlog = TalosBootstrap.replayTurnLog(store, "ws-4", mem);
        assertEquals(0, snap.pairsReplayed());
        assertEquals(0, tlog);
        assertFalse(mem.hasContent());
    }

    @Test
    void snapshotSkipsNonOkAssistantTurns(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "ws-9";

        try (var writer = store.acquireSessionWriter(sid)) {
            writer.save(new SessionData(sid, "/ws", "", 2, Instant.now(),
                    List.of(
                            new SessionData.Turn("user", "u1", ""),
                            new SessionData.Turn("assistant", "poison", "error"),
                            new SessionData.Turn("user", "u2", ""),
                            new SessionData.Turn("assistant", "clean", "ok")
                    ),
                    "llama_cpp/qwen2.5-coder-14b"));
        }

        SessionMemory mem = new SessionMemory();
        var snap = TalosBootstrap.replaySnapshot(store, sid, mem, cm(mem));
        assertEquals(1, snap.pairsReplayed());
        assertEquals("llama_cpp/qwen2.5-coder-14b", snap.model());
        assertTrue(mem.get().contains("u2"));
        assertFalse(mem.get().contains("poison"));
    }

    @Test
    void turnRecordsWithBlankTextAreSkipped(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "ws-5";
        try (var writer = store.acquireSessionWriter(sid)) {
            writer.appendTurn(sid, new TurnRecord(1, Instant.now(), 0L,
                    "", "", List.of(), 0, 0, 0, ""));
            writer.appendTurn(sid, new TurnRecord(2, Instant.now(), 0L,
                    "real-u", "real-a", List.of(), 0, 0, 0, ""));
        }

        SessionMemory mem = new SessionMemory();
        int replayed = TalosBootstrap.replayTurnLog(store, sid, mem);
        assertEquals(1, replayed, "blank-pair records are skipped");
        assertTrue(mem.get().contains("real-u"));
    }

    /**
     * Cross-session hallucination guard: an "aborted" turn (wall-clock
     * timeout, idle watchdog, or interrupt) must not re-enter SessionMemory
     * on the next session. Real incident: gemma4:26b fell into a repetition
     * attractor, the turn timed out at 300s, and on the next REPL start the
     * 200-line confabulated body was replayed as authoritative history.
     */
    @Test
    void abortedTurnIsSkippedOnReplay(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "ws-6";

        // A turn that timed out - persisted by JsonTurnLogAppender with
        // status="aborted" (the abortedText below mirrors what LlmClient
        // emits on wall-clock expiry). The garbage prose that streamed
        // before the timeout is captured in assistantText.
        try (var writer = store.acquireSessionWriter(sid)) {
            writer.appendTurn(sid, new TurnRecord(1, Instant.now(), 387_800L,
                    "user turn 1",
                    "The user's prompt is 'The user's prompt is 'The user's prompt is",
                    List.of(), 0, 0, 0, "", "aborted"));
            // A legitimate turn afterwards - must still replay.
            writer.appendTurn(sid, new TurnRecord(2, Instant.now(), 0L,
                    "user turn 2", "clean reply", List.of(), 0, 0, 0, "", "ok"));
        }

        SessionMemory mem = new SessionMemory();
        int replayed = TalosBootstrap.replayTurnLog(store, sid, mem);
        assertEquals(1, replayed, "only the ok turn is replayed");
        String buf = mem.get();
        assertTrue(buf.contains("user turn 2") && buf.contains("clean reply"));
        assertFalse(buf.contains("The user's prompt is"),
                "aborted turn's confabulated body must not enter memory");
    }

    /**
     * Non-ok statuses other than "aborted" are also non-conversational
     * (error, info, stream-lifecycle) and must be filtered out on replay.
     */
    @Test
    void errorAndInfoTurnsAreSkippedOnReplay(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "ws-7";

        try (var writer = store.acquireSessionWriter(sid)) {
            writer.appendTurn(sid, new TurnRecord(1, Instant.now(), 0L,
                    "u-err", "tool crashed", List.of(), 0, 0, 0, "", "error"));
            writer.appendTurn(sid, new TurnRecord(2, Instant.now(), 0L,
                    "u-info", "some info line", List.of(), 0, 0, 0, "", "info"));
            writer.appendTurn(sid, new TurnRecord(3, Instant.now(), 0L,
                    "u-ok", "real answer", List.of(), 0, 0, 0, "", "ok"));
        }

        SessionMemory mem = new SessionMemory();
        int replayed = TalosBootstrap.replayTurnLog(store, sid, mem);
        assertEquals(1, replayed);
        String buf = mem.get();
        assertTrue(buf.contains("u-ok") && buf.contains("real answer"));
        assertFalse(buf.contains("tool crashed"));
        assertFalse(buf.contains("some info line"));
    }

    /**
     * Back-compat: legacy JSONL records written before the status field
     * existed serialize status="" on read. These must still replay, or we
     * break session restoration for anyone upgrading from a pre-status
     * build.
     */
    @Test
    void legacyBlankStatusRecordsStillReplay(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "ws-8";
        try (var writer = store.acquireSessionWriter(sid)) {
            writer.appendTurn(sid, new TurnRecord(1, Instant.now(), 0L,
                    "legacy-u", "legacy-a", List.of(), 0, 0, 0, "", ""));
        }

        SessionMemory mem = new SessionMemory();
        int replayed = TalosBootstrap.replayTurnLog(store, sid, mem);
        assertEquals(1, replayed);
        assertTrue(mem.get().contains("legacy-u"));
    }
}

