package dev.talos.cli.repl.slash;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.cli.repl.SessionMemory;
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
        @Test void save_thenLoad_restoresConversation() throws Exception {
            var st = store();
            Path ws = Path.of("/test/project").toAbsolutePath().normalize();
            var cmd = new SessionCommand(ws, st);
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
            Result saveResult = cmd.execute("save", ctx);
            assertInstanceOf(Result.Info.class, saveResult);
            assertTrue(((Result.Info) saveResult).text.contains("Session saved"));
            // Create fresh context
            SessionMemory freshMem = new SessionMemory();
            ConversationManager freshCm = new ConversationManager(freshMem);
            Context freshCtx = Context.builder(new Config())
                    .memory(freshMem)
                    .conversationManager(freshCm)
                    .build();
            // Load
            Result loadResult = cmd.execute("load", freshCtx);
            assertInstanceOf(Result.Info.class, loadResult);
            assertTrue(((Result.Info) loadResult).text.contains("Session restored"));
            // Verify restored state
            assertEquals(2, freshCm.turnCount());
            assertEquals("User is learning about Java.", freshCm.sketch());
            assertEquals(4, freshMem.getTurns().size()); // 2 pairs
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
            var cmd = new SessionCommand(ws, st);
            st.appendTurn(cmd.sessionId(), new TurnRecord(1, Instant.now(), 0L,
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
            var cmd = new SessionCommand(ws, st);
            ActiveTaskContext context = ActiveTaskContext.proposedChanges(
                    3, "trace-save", List.of("README.md"), "Improve README.");
            st.save(new SessionData(cmd.sessionId(), ws.toString(), "", 0, Instant.now(), List.of(), "",
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
    // -- Unknown subcommand --
    @Nested class Unknown {
        @Test void unknownSubcommand_returnsError() throws Exception {
            var cmd = new SessionCommand(Path.of("/ws"), store());
            Result r = cmd.execute("banana", minimalCtx());
            assertInstanceOf(Result.Error.class, r);
        }
    }
}
