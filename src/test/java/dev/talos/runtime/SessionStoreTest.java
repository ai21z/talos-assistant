package dev.talos.runtime;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SessionStoreTest {

    // ── SessionData ──────────────────────────────────────────────

    @Nested class SessionDataTests {

        @Test void nullFieldsNormalized() {
            var data = new SessionData(null, null, null, 0, null);
            assertEquals("", data.sessionId());
            assertEquals("", data.workspace());
            assertEquals("", data.sketch());
            assertNotNull(data.createdAt());
        }

        @Test void fieldsPreserved() {
            Instant ts = Instant.parse("2026-01-01T00:00:00Z");
            var data = new SessionData("s1", "/tmp/ws", "recap of goals", 5, ts);
            assertEquals("s1", data.sessionId());
            assertEquals("/tmp/ws", data.workspace());
            assertEquals("recap of goals", data.sketch());
            assertEquals(5, data.turnCount());
            assertEquals(ts, data.createdAt());
        }

        @Test void emptySketchIsEmptyString() {
            var data = new SessionData("s1", "/tmp", null, 0, Instant.now());
            assertEquals("", data.sketch());
        }
    }

    // ── NoOpSessionStore ─────────────────────────────────────────

    @Nested class NoOpTests {

        private final SessionStore store = new NoOpSessionStore();

        @Test void saveDoesNotThrow() {
            var data = new SessionData("s1", "/tmp", "sketch", 3, Instant.now());
            assertDoesNotThrow(() -> store.save(data));
        }

        @Test void loadReturnsEmpty() {
            Optional<SessionData> result = store.load("anything");
            assertTrue(result.isEmpty());
        }

        @Test void loadNullIdReturnsEmpty() {
            assertTrue(store.load(null).isEmpty());
        }

        @Test void deleteReturnsFalse() {
            assertFalse(store.delete("anything"));
        }

        @Test void saveFollowedByLoadStillEmpty() {
            var data = new SessionData("s1", "/tmp", "sketch", 3, Instant.now());
            store.save(data);
            assertTrue(store.load("s1").isEmpty());
        }
    }

    // ── Session wiring ───────────────────────────────────────────

    @Nested class SessionWiringTests {

        @Test void defaultStoreIsNoOp() {
            var session = new Session(
                    java.nio.file.Path.of(".").toAbsolutePath().normalize(),
                    new dev.talos.core.Config()
            );
            assertNotNull(session.store());
            assertInstanceOf(NoOpSessionStore.class, session.store());
        }

        @Test void customStoreIsPreserved() {
            var custom = new NoOpSessionStore();
            var session = new Session(
                    java.nio.file.Path.of(".").toAbsolutePath().normalize(),
                    new dev.talos.core.Config(),
                    null, // default memory
                    custom
            );
            assertSame(custom, session.store());
        }

        @Test void nullStoreFallsBackToNoOp() {
            var session = new Session(
                    java.nio.file.Path.of(".").toAbsolutePath().normalize(),
                    new dev.talos.core.Config(),
                    null,
                    null
            );
            assertNotNull(session.store());
            assertInstanceOf(NoOpSessionStore.class, session.store());
        }
    }
}

