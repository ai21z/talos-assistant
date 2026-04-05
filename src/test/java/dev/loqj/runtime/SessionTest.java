package dev.loqj.runtime;

import dev.loqj.cli.repl.SessionMemory;
import dev.loqj.core.Config;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SessionTest {

    private static final Path WS = Path.of(".").toAbsolutePath().normalize();

    @Test void constructorSetsFields() {
        Config cfg = new Config();
        var session = new Session(WS, cfg);

        assertEquals(WS, session.workspace());
        assertSame(cfg, session.config());
        assertNotNull(session.startedAt());
        assertEquals(0, session.turnCount());
        assertNotNull(session.memory());
    }

    @Test void nextTurnIncrements() {
        var session = new Session(WS, new Config());
        assertEquals(1, session.nextTurn());
        assertEquals(2, session.nextTurn());
        assertEquals(3, session.nextTurn());
        assertEquals(3, session.turnCount());
    }

    @Test void customMemoryIsPreserved() {
        var mem = new SessionMemory();
        mem.update("q", "a");
        var session = new Session(WS, new Config(), mem);
        assertSame(mem, session.memory());
        assertTrue(session.memory().hasContent());
    }

    @Test void nullWorkspaceThrows() {
        assertThrows(NullPointerException.class,
                () -> new Session(null, new Config()));
    }

    @Test void nullConfigThrows() {
        assertThrows(NullPointerException.class,
                () -> new Session(WS, null));
    }

    @Test void nullMemoryFallsBackToDefault() {
        var session = new Session(WS, new Config(), null);
        assertNotNull(session.memory());
        assertFalse(session.memory().hasContent());
    }
}

