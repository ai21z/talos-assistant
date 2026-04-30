package dev.talos.runtime;
import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.context.ArtifactGoal;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
/**
 * Tests for {@link JsonSessionStore}.
 */
class JsonSessionStoreTest {
    @TempDir Path tempDir;
    private JsonSessionStore store() {
        return new JsonSessionStore(tempDir);
    }
    private SessionData sample(String id, int turns) {
        List<SessionData.Turn> turnList = List.of(
                new SessionData.Turn("user", "hello", ""),
                new SessionData.Turn("assistant", "hi there", "ok")
        );
        return new SessionData(id, "/tmp/ws", "goal sketch", turns,
                Instant.parse("2026-01-15T10:30:00Z"), turnList, "ollama/qwen2.5-coder:14b");
    }
    // -- Basic CRUD --
    @Nested class SaveAndLoad {
        @Test void roundTrip_preservesAllFields() {
            var store = store();
            SessionData original = sample("abc123", 5);
            store.save(original);
            Optional<SessionData> loaded = store.load("abc123");
            assertTrue(loaded.isPresent());
            SessionData d = loaded.get();
            assertEquals("abc123", d.sessionId());
            assertEquals("/tmp/ws", d.workspace());
            assertEquals("goal sketch", d.sketch());
            assertEquals(5, d.turnCount());
            assertEquals(Instant.parse("2026-01-15T10:30:00Z"), d.createdAt());
            assertEquals("ollama/qwen2.5-coder:14b", d.model());
            assertEquals(2, d.turns().size());
            assertEquals("user", d.turns().get(0).role());
            assertEquals("hello", d.turns().get(0).content());
            assertEquals("assistant", d.turns().get(1).role());
            assertEquals("hi there", d.turns().get(1).content());
            assertEquals("ok", d.turns().get(1).status());
        }
        @Test void roundTrip_preservesActiveTaskContextAndArtifactGoal() {
            var store = store();
            ActiveTaskContext context = ActiveTaskContext.proposedChanges(
                    3, "trace-save", List.of("README.md"), "Improve README.");
            ArtifactGoal goal = ArtifactGoal.fromActiveContext(context);
            SessionData original = new SessionData("ctx1", "/tmp/ws", "goal sketch", 1,
                    Instant.parse("2026-01-15T10:30:00Z"), List.of(), "ollama/qwen2.5-coder:14b",
                    context, goal);

            store.save(original);

            SessionData loaded = store.load("ctx1").orElseThrow();
            assertEquals(ActiveTaskContext.State.ACTIVE, loaded.activeTaskContext().state());
            assertEquals(ActiveTaskContext.Kind.PROPOSED_CHANGES, loaded.activeTaskContext().kind());
            assertEquals(List.of("README.md"), loaded.activeTaskContext().targets());
            assertEquals(ArtifactGoal.ArtifactKind.README, loaded.artifactGoal().artifactKind());
        }
        @Test void load_oldSnapshotWithoutActiveContextDefaultsToNone() throws Exception {
            var store = store();
            Files.writeString(tempDir.resolve("legacy.json"), """
                    {
                      "sessionId": "legacy",
                      "workspace": "/tmp/ws",
                      "sketch": "old sketch",
                      "turnCount": 0,
                      "createdAt": "2026-01-15T10:30:00Z",
                      "model": "",
                      "turns": []
                    }
                    """);

            SessionData loaded = store.load("legacy").orElseThrow();
            assertEquals(ActiveTaskContext.State.NONE, loaded.activeTaskContext().state());
            assertEquals(ArtifactGoal.ArtifactKind.UNKNOWN, loaded.artifactGoal().artifactKind());
        }
        @Test void load_nonExistent_returnsEmpty() {
            var store = store();
            assertTrue(store.load("nonexistent").isEmpty());
        }
        @Test void load_nullId_returnsEmpty() {
            var store = store();
            assertTrue(store.load(null).isEmpty());
        }
        @Test void load_blankId_returnsEmpty() {
            var store = store();
            assertTrue(store.load("   ").isEmpty());
        }
        @Test void save_null_isIgnored() {
            var store = store();
            assertDoesNotThrow(() -> store.save(null));
        }
        @Test void save_blankId_isIgnored() {
            var store = store();
            assertDoesNotThrow(() -> store.save(
                    new SessionData("", "/tmp", "", 0, Instant.now())));
            // No file should be created
            assertEquals(0, tempDir.toFile().listFiles().length);
        }
        @Test void save_overwritesPrevious() {
            var store = store();
            store.save(sample("x", 1));
            store.save(new SessionData("x", "/new", "updated", 10,
                    Instant.now(), List.of()));
            SessionData d = store.load("x").orElseThrow();
            assertEquals("updated", d.sketch());
            assertEquals(10, d.turnCount());
            assertEquals(0, d.turns().size());
        }
    }
    // -- Delete --
    @Nested class Delete {
        @Test void delete_existing_returnsTrue() {
            var store = store();
            store.save(sample("del1", 2));
            assertTrue(store.delete("del1"));
            assertTrue(store.load("del1").isEmpty());
        }
        @Test void delete_nonExistent_returnsFalse() {
            var store = store();
            assertFalse(store.delete("nope"));
        }
        @Test void delete_null_returnsFalse() {
            var store = store();
            assertFalse(store.delete(null));
        }
    }
    // -- Session ID derivation --
    @Nested class SessionIdDerivation {
        @Test void sessionIdFor_isDeterministic() {
            Path ws = Path.of("/tmp/test-workspace");
            String id1 = JsonSessionStore.sessionIdFor(ws);
            String id2 = JsonSessionStore.sessionIdFor(ws);
            assertEquals(id1, id2);
            assertFalse(id1.isBlank());
        }
        @Test void differentWorkspaces_differentIds() {
            String id1 = JsonSessionStore.sessionIdFor(Path.of("/project/a"));
            String id2 = JsonSessionStore.sessionIdFor(Path.of("/project/b"));
            assertNotEquals(id1, id2);
        }
    }
    // -- File format --
    @Nested class FileFormat {
        @Test void savedFile_isReadableJson() throws Exception {
            var store = store();
            store.save(sample("json1", 3));
            Path file = tempDir.resolve("json1.json");
            assertTrue(Files.exists(file));
            String content = Files.readString(file);
            assertTrue(content.contains("\"sessionId\""));
            assertTrue(content.contains("\"sketch\""));
            assertTrue(content.contains("\"turns\""));
            assertTrue(content.contains("\"goal sketch\""));
        }
        @Test void corruptFile_returnsEmpty() throws Exception {
            var store = store();
            Path file = tempDir.resolve("corrupt.json");
            Files.writeString(file, "not valid json {{{");
            assertTrue(store.load("corrupt").isEmpty());
        }
        @Test void emptyTurns_roundTrip() {
            var store = store();
            SessionData data = new SessionData("empty", "/ws", "", 0, Instant.now(), List.of());
            store.save(data);
            SessionData loaded = store.load("empty").orElseThrow();
            assertTrue(loaded.turns().isEmpty());
            assertEquals(0, loaded.turnCount());
        }
    }
    // -- SessionData Turn record --
    @Nested class TurnRecord {
        @Test void nullFieldsNormalized() {
            var turn = new SessionData.Turn(null, null);
            assertEquals("", turn.role());
            assertEquals("", turn.content());
        }
        @Test void fieldsPreserved() {
            var turn = new SessionData.Turn("user", "hello world");
            assertEquals("user", turn.role());
            assertEquals("hello world", turn.content());
        }
    }
}
