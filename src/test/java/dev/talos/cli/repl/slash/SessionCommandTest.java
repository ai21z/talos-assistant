package dev.talos.cli.repl.slash;
import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.runtime.SessionMemory;
import dev.talos.core.Config;
import dev.talos.core.context.ConversationManager;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.SessionData;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.context.ArtifactGoal;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
/**
 * Tests for {@link SessionCommand}.
 */
class SessionCommandTest {
    @TempDir Path tempDir;
    private JsonSessionStore store() {
        return new JsonSessionStore(tempDir);
    }
    private Context minimalCtx() {
        return Context.builder(new Config()).build();
    }
    // -- Spec --
    @Nested class Spec {
        @Test void name() {
            var cmd = new SessionCommand(Path.of("/ws"), store());
            assertEquals("session", cmd.spec().name());
        }
        @Test void group() {
            var cmd = new SessionCommand(Path.of("/ws"), store());
            assertEquals(CommandGroup.SESSION, cmd.spec().group());
        }
    }
    // -- Info --
    @Nested class Info {
        @Test void showsSessionInfo() throws Exception {
            var cmd = new SessionCommand(Path.of("/ws"), store());
            Result r = cmd.execute("info", minimalCtx());
            assertInstanceOf(Result.Info.class, r);
            String text = ((Result.Info) r).text;
            assertTrue(text.contains("Session ID:"));
            assertTrue(text.contains("Turns:"));
            assertTrue(text.contains("Saved file:"));
        }
        @Test void defaultSubcommand_isInfo() throws Exception {
            var cmd = new SessionCommand(Path.of("/ws"), store());
            Result r = cmd.execute("", minimalCtx());
            assertInstanceOf(Result.Info.class, r);
            assertTrue(((Result.Info) r).text.contains("Session ID:"));
        }
    }
    // -- Save + Load --
    @Nested class SaveAndLoad {
        /**
         * T800: the cross-process flow. Session A saves under its instance
         * id; a NEW session (different instance id, same workspace) runs
         * /session load and gets A back — load means "resume the latest
         * other session", no longer "re-read my own slot".
         */
        @Test void save_thenLoad_restoresConversation() throws Exception {
            var st = store();
            Path ws = Path.of("/test/project").toAbsolutePath().normalize();
            String wsId = JsonSessionStore.sessionIdFor(ws);
            var sessionA = new SessionCommand(ws, st, wsId + "-20260612080000");
            // Set up context with conversation history
            SessionMemory mem = new SessionMemory();
            mem.update("What is Java?", "Java is a programming language.");
            mem.update("Tell me more", "Java runs on the JVM.");
            ConversationManager cm = new ConversationManager(mem);
            cm.setSketch("User is learning about Java.");
            Context ctx = Context.builder(new Config())
                    .memory(mem)
                    .conversationManager(cm)
                    .build();
            // Save
            Result saveResult = sessionA.execute("save", ctx);
            assertInstanceOf(Result.Info.class, saveResult);
            assertTrue(((Result.Info) saveResult).text.contains("Session saved"));
            // A later session of the same workspace
            var sessionB = new SessionCommand(ws, st, wsId + "-20260612090000");
            SessionMemory freshMem = new SessionMemory();
            ConversationManager freshCm = new ConversationManager(freshMem);
            Context freshCtx = Context.builder(new Config())
                    .memory(freshMem)
                    .conversationManager(freshCm)
                    .build();
            // Load
            Result loadResult = sessionB.execute("load", freshCtx);
            assertInstanceOf(Result.Info.class, loadResult);
            assertTrue(((Result.Info) loadResult).text.contains("Session restored"));
            // Verify restored state
            assertEquals(2, freshCm.turnCount());
            assertEquals("User is learning about Java.", freshCm.sketch());
            assertEquals(4, freshMem.getTurns().size()); // 2 pairs
        }
        @Test void save_persistsActiveTaskContextAndArtifactGoal() throws Exception {
            var st = store();
            Path ws = Path.of("/active/project").toAbsolutePath().normalize();
            var cmd = new SessionCommand(ws, st);
            SessionMemory mem = new SessionMemory();
            ActiveTaskContext context = ActiveTaskContext.proposedChanges(
                    3, "trace-save", List.of("README.md"), "Improve README.");
            ArtifactGoal goal = ArtifactGoal.fromActiveContext(context);
            mem.setActiveTaskContext(context);
            mem.setArtifactGoal(goal);
            Context ctx = Context.builder(new Config())
                    .memory(mem)
                    .conversationManager(new ConversationManager(mem))
                    .build();

            Result saveResult = cmd.execute("save", ctx);

            assertInstanceOf(Result.Info.class, saveResult);
            SessionData saved = st.load(cmd.sessionId()).orElseThrow();
            assertEquals(context, saved.activeTaskContext());
            assertEquals(goal, saved.artifactGoal());
        }
        @Test void load_noSession_returnsInfo() throws Exception {
            var cmd = new SessionCommand(Path.of("/empty"), store());
            Result r = cmd.execute("load", minimalCtx());
            assertInstanceOf(Result.Info.class, r);
            assertTrue(((Result.Info) r).text.contains("No saved session"));
        }
        @Test void load_usesTurnLogFallbackWhenSnapshotMissing() throws Exception {
            var st = store();
            Path ws = Path.of("/crash/project").toAbsolutePath().normalize();
            String wsId = JsonSessionStore.sessionIdFor(ws);
            // The crashed session left only a turn log; this run is a new instance.
            var cmd = new SessionCommand(ws, st, wsId + "-20260612090000");
            st.appendTurn(wsId + "-20260612080000", new TurnRecord(1, Instant.now(), 0L,
                    "recover me", "recovered answer", List.of(), 0, 0, 0, "", "ok"));

            SessionMemory freshMem = new SessionMemory();
            ConversationManager freshCm = new ConversationManager(freshMem);
            Context freshCtx = Context.builder(new Config())
                    .memory(freshMem)
                    .conversationManager(freshCm)
                    .build();

            Result loadResult = cmd.execute("load", freshCtx);
            assertInstanceOf(Result.Info.class, loadResult);
            assertTrue(((Result.Info) loadResult).text.contains("Session restored"));
            assertEquals(1, freshCm.turnCount());
            assertTrue(freshMem.get().contains("recovered answer"));
        }
        @Test void load_restoresContextOnlySnapshot() throws Exception {
            var st = store();
            Path ws = Path.of("/context/project").toAbsolutePath().normalize();
            String wsId = JsonSessionStore.sessionIdFor(ws);
            var cmd = new SessionCommand(ws, st, wsId + "-20260612090000");
            ActiveTaskContext context = ActiveTaskContext.proposedChanges(
                    3, "trace-save", List.of("README.md"), "Improve README.");
            st.save(new SessionData(wsId + "-20260612080000", ws.toString(), "", 0, Instant.now(), List.of(), "",
                    context, ArtifactGoal.fromActiveContext(context)));

            SessionMemory freshMem = new SessionMemory();
            ConversationManager freshCm = new ConversationManager(freshMem);
            Context freshCtx = Context.builder(new Config())
                    .memory(freshMem)
                    .conversationManager(freshCm)
                    .build();

            Result loadResult = cmd.execute("load", freshCtx);

            assertInstanceOf(Result.Info.class, loadResult);
            String text = ((Result.Info) loadResult).text;
            assertFalse(text.contains("No saved session found"));
            assertTrue(text.contains("Session restored"));
            assertEquals(List.of("README.md"), freshMem.activeTaskContext().targets());
            assertEquals(ArtifactGoal.ArtifactKind.README, freshMem.artifactGoal().artifactKind());
        }
    }
    // -- Clear --
    @Nested class Clear {
        @Test void clear_existing_deletesFile() throws Exception {
            var st = store();
            var cmd = new SessionCommand(Path.of("/ws"), st);
            // Manually save something
            st.save(new SessionData(cmd.sessionId(), "/ws", "sketch", 3,
                    Instant.now(), List.of()));
            Result r = cmd.execute("clear", minimalCtx());
            assertInstanceOf(Result.Info.class, r);
            assertTrue(((Result.Info) r).text.contains("Saved session deleted"));
            assertTrue(st.load(cmd.sessionId()).isEmpty());
        }
        @Test void clear_noFile_returnsInfo() throws Exception {
            var cmd = new SessionCommand(Path.of("/ws"), store());
            Result r = cmd.execute("clear", minimalCtx());
            assertInstanceOf(Result.Info.class, r);
            assertTrue(((Result.Info) r).text.contains("No saved session to delete"));
        }
        @Test void clear_turnLogOnly_deletesCompanionFile() throws Exception {
            var st = store();
            var cmd = new SessionCommand(Path.of("/ws-turn-log-only"), st);
            st.appendTurn(cmd.sessionId(), new TurnRecord(1, Instant.now(), 0L,
                    "u", "a", List.of(), 0, 0, 0, "", "ok"));

            Result r = cmd.execute("clear", minimalCtx());
            assertInstanceOf(Result.Info.class, r);
            assertTrue(((Result.Info) r).text.contains("Saved session deleted"));
            assertTrue(st.loadTurns(cmd.sessionId()).isEmpty());
        }
    }
    // -- List (T800) --
    @Nested class ListSessions {
        @Test void list_rendersCurrentLegacyAndCrashMarkers() throws Exception {
            var st = store();
            Path ws = Path.of("/list/project").toAbsolutePath().normalize();
            String wsId = JsonSessionStore.sessionIdFor(ws);
            String current = wsId + "-20260612090000";
            String previous = wsId + "-20260611090000";
            var cmd = new SessionCommand(ws, st, current);

            st.save(new SessionData(wsId, ws.toString(), "", 1,
                    Instant.parse("2026-06-09T09:00:00Z"),
                    List.of(new SessionData.Turn("user", "u", ""),
                            new SessionData.Turn("assistant", "a", "ok")), "model-l"));
            st.save(new SessionData(previous, ws.toString(), "", 2,
                    Instant.parse("2026-06-11T09:00:00Z"), List.of(), "model-p"));
            st.appendTurn(current, new TurnRecord(1, Instant.parse("2026-06-12T09:01:00Z"), 0L,
                    "u", "a", List.of(), 0, 0, 0, "", "ok"));

            String text = ((Result.Info) cmd.execute("list", minimalCtx())).text;

            assertTrue(text.startsWith("Sessions (newest first):"));
            assertTrue(text.contains("20260612090000"), "current session's display id");
            assertTrue(text.contains("(current)"));
            assertTrue(text.contains("20260611090000"));
            assertTrue(text.contains("2 exchanges"));
            assertTrue(text.contains("model-p"));
            assertTrue(text.contains("(legacy)"));
            assertTrue(text.indexOf("20260612090000") < text.indexOf("20260611090000"),
                    "newest first");
            assertTrue(text.indexOf("20260611090000") < text.indexOf(wsId.substring(0, 8)),
                    "legacy file is oldest");
        }

        @Test void list_currentMarkerWinsOverCrashLogOnly() throws Exception {
            var st = store();
            Path ws = Path.of("/list/markers").toAbsolutePath().normalize();
            String current = JsonSessionStore.sessionIdFor(ws) + "-20260612090000";
            var cmd = new SessionCommand(ws, st, current);
            st.appendTurn(current, new TurnRecord(1, Instant.now(), 0L,
                    "u", "a", List.of(), 0, 0, 0, "", "ok"));

            String text = ((Result.Info) cmd.execute("list", minimalCtx())).text;

            assertTrue(text.contains("(current)"));
            assertFalse(text.contains("crash log only"),
                    "(current) replaces the state tags for this run's row");
        }

        @Test void list_empty_reportsNoSavedSession() throws Exception {
            var cmd = new SessionCommand(Path.of("/list/empty"), store());
            assertEquals("No saved session found for this workspace.",
                    ((Result.Info) cmd.execute("list", minimalCtx())).text);
        }
    }
    // -- Resume (T800) --
    @Nested class Resume {
        private Context freshCtx(SessionMemory mem, ConversationManager cm) {
            return Context.builder(new Config()).memory(mem).conversationManager(cm).build();
        }

        private void saveSnapshot(JsonSessionStore st, Path ws, String id, Instant createdAt,
                                  String user, String assistant) {
            st.save(new SessionData(id, ws.toString(), "", 1, createdAt,
                    List.of(new SessionData.Turn("user", user, ""),
                            new SessionData.Turn("assistant", assistant, "ok")), ""));
        }

        @Test void resume_defaultsToLatestOtherSession() throws Exception {
            var st = store();
            Path ws = Path.of("/resume/default").toAbsolutePath().normalize();
            String wsId = JsonSessionStore.sessionIdFor(ws);
            String current = wsId + "-20260612090000";
            var cmd = new SessionCommand(ws, st, current);
            saveSnapshot(st, ws, wsId + "-20260610090000", Instant.parse("2026-06-10T09:00:00Z"),
                    "older-q", "older-a");
            saveSnapshot(st, ws, wsId + "-20260611090000", Instant.parse("2026-06-11T09:00:00Z"),
                    "newer-q", "newer-a");
            // The current session's own (newest) crash log must be skipped.
            st.appendTurn(current, new TurnRecord(1, Instant.parse("2026-06-12T09:01:00Z"), 0L,
                    "current-q", "current-a", List.of(), 0, 0, 0, "", "ok"));

            SessionMemory mem = new SessionMemory();
            ConversationManager cm = new ConversationManager(mem);
            Result r = cmd.execute("resume", freshCtx(mem, cm));

            assertTrue(((Result.Info) r).text.contains("Session restored: 1 exchange"));
            assertTrue(mem.get().contains("newer-a"), "latest OTHER session wins");
            assertFalse(mem.get().contains("older-a"));
            assertFalse(mem.get().contains("current-a"));
        }

        @Test void resume_uniqueTimestampPrefixSelectsThatSession() throws Exception {
            var st = store();
            Path ws = Path.of("/resume/prefix").toAbsolutePath().normalize();
            String wsId = JsonSessionStore.sessionIdFor(ws);
            var cmd = new SessionCommand(ws, st, wsId + "-20260612090000");
            saveSnapshot(st, ws, wsId + "-20260610090000", Instant.parse("2026-06-10T09:00:00Z"),
                    "older-q", "older-a");
            saveSnapshot(st, ws, wsId + "-20260611090000", Instant.parse("2026-06-11T09:00:00Z"),
                    "newer-q", "newer-a");

            SessionMemory mem = new SessionMemory();
            Result r = cmd.execute("resume 20260610", freshCtx(mem, new ConversationManager(mem)));

            assertInstanceOf(Result.Info.class, r);
            assertTrue(mem.get().contains("older-a"), "explicit prefix overrides latest");
        }

        @Test void resume_ambiguousPrefixListsCandidates() throws Exception {
            var st = store();
            Path ws = Path.of("/resume/ambiguous").toAbsolutePath().normalize();
            String wsId = JsonSessionStore.sessionIdFor(ws);
            var cmd = new SessionCommand(ws, st, wsId + "-20260612090000");
            saveSnapshot(st, ws, wsId + "-20260610090000", Instant.parse("2026-06-10T09:00:00Z"), "q", "a");
            saveSnapshot(st, ws, wsId + "-20260611090000", Instant.parse("2026-06-11T09:00:00Z"), "q", "a");

            Result r = cmd.execute("resume 2026061", minimalCtx());

            assertInstanceOf(Result.Error.class, r);
            String message = ((Result.Error) r).message;
            assertTrue(message.contains("Ambiguous session id '2026061'"));
            assertTrue(message.contains("20260610090000"));
            assertTrue(message.contains("20260611090000"));
        }

        @Test void resume_noMatchPointsAtList() throws Exception {
            var st = store();
            Path ws = Path.of("/resume/nomatch").toAbsolutePath().normalize();
            String wsId = JsonSessionStore.sessionIdFor(ws);
            var cmd = new SessionCommand(ws, st, wsId + "-20260612090000");
            saveSnapshot(st, ws, wsId + "-20260610090000", Instant.parse("2026-06-10T09:00:00Z"), "q", "a");

            Result r = cmd.execute("resume zzz", minimalCtx());

            assertInstanceOf(Result.Error.class, r);
            assertEquals("No session matches 'zzz'. See /session list.",
                    ((Result.Error) r).message);
        }

        @Test void resume_explicitPrefixMayReloadTheCurrentSession() throws Exception {
            var st = store();
            Path ws = Path.of("/resume/self").toAbsolutePath().normalize();
            String wsId = JsonSessionStore.sessionIdFor(ws);
            String current = wsId + "-20260612090000";
            var cmd = new SessionCommand(ws, st, current);
            saveSnapshot(st, ws, current, Instant.parse("2026-06-12T09:00:00Z"),
                    "self-q", "self-a");

            SessionMemory mem = new SessionMemory();
            Result r = cmd.execute("resume 20260612", freshCtx(mem, new ConversationManager(mem)));

            assertInstanceOf(Result.Info.class, r);
            assertTrue(mem.get().contains("self-a"),
                    "explicitly targeting the current session is a reload-from-disk");
        }

        @Test void resume_onlyOwnFilesMeansNothingToResume() throws Exception {
            var st = store();
            Path ws = Path.of("/resume/own").toAbsolutePath().normalize();
            String current = JsonSessionStore.sessionIdFor(ws) + "-20260612090000";
            var cmd = new SessionCommand(ws, st, current);
            st.appendTurn(current, new TurnRecord(1, Instant.now(), 0L,
                    "u", "a", List.of(), 0, 0, 0, "", "ok"));

            assertEquals("No saved session found for this workspace.",
                    ((Result.Info) cmd.execute("resume", minimalCtx())).text);
        }
    }
    // -- Unknown subcommand --
    @Nested class Unknown {
        @Test void unknownSubcommand_returnsError() throws Exception {
            var cmd = new SessionCommand(Path.of("/ws"), store());
            Result r = cmd.execute("banana", minimalCtx());
            assertInstanceOf(Result.Error.class, r);
        }
    }
}
