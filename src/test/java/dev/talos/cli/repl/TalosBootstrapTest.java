package dev.talos.cli.repl;

import dev.talos.core.Config;
import org.junit.jupiter.api.Test;

import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TalosBootstrap} — the composition root.
 *
 * <p>Verifies that the bootstrap wires everything correctly and
 * produces a functional ReplRouter without exceptions.
 */
class TalosBootstrapTest {

    private static final Path WS = Path.of(".").toAbsolutePath().normalize();

    @Test
    void createProducesWorkingRouter() {
        SessionState session = new SessionState() {
            private int k = 6; private boolean dbg;
            public int getK() { return k; } public void setK(int v) { k = v; }
            public boolean isDebug() { return dbg; } public void setDebug(boolean on) { dbg = on; }
        };

        ReplRouter router = TalosBootstrap.create(session, new Config(), System.out, WS);

        assertNotNull(router);
        assertNotNull(router.getModes());
        assertNotNull(router.getRuntimeSession());
        assertFalse(router.shouldQuit());
        assertEquals("auto", router.getModes().getActiveName());
    }

    @Test
    void createHandlesNullConfig() {
        SessionState session = new SessionState() {
            private int k = 6; private boolean dbg;
            public int getK() { return k; } public void setK(int v) { k = v; }
            public boolean isDebug() { return dbg; } public void setDebug(boolean on) { dbg = on; }
        };

        ReplRouter router = TalosBootstrap.create(session, null, null, null);
        assertNotNull(router);
        assertFalse(router.shouldQuit());
    }

    @Test
    void backwardCompatibleConstructorWorks() {
        SessionState session = new SessionState() {
            private int k = 6; private boolean dbg;
            public int getK() { return k; } public void setK(int v) { k = v; }
            public boolean isDebug() { return dbg; } public void setDebug(boolean on) { dbg = on; }
        };

        // This is how RunCmd currently creates the router
        ReplRouter router = new ReplRouter(session, new Config(), System.out, WS);
        assertNotNull(router);
        assertNotNull(router.getModes());
        assertEquals("auto", router.getModes().getActiveName());
    }

    @Test
    void modesHaveSymbolCheckerWired() {
        SessionState session = new SessionState() {
            private int k = 6; private boolean dbg;
            public int getK() { return k; } public void setK(int v) { k = v; }
            public boolean isDebug() { return dbg; } public void setDebug(boolean on) { dbg = on; }
        };

        ReplRouter router = TalosBootstrap.create(session, new Config(), System.out, WS);
        // SymbolChecker is set during bootstrap
        assertNotNull(router.getModes().getSymbolChecker());
    }

    @Test
    void unknownCommandIsNotHandled() {
        SessionState session = new SessionState() {
            private int k = 6; private boolean dbg;
            public int getK() { return k; } public void setK(int v) { k = v; }
            public boolean isDebug() { return dbg; } public void setDebug(boolean on) { dbg = on; }
        };

        ReplRouter router = TalosBootstrap.create(session, new Config(),
                new PrintStream(java.io.OutputStream.nullOutputStream()), WS);

        // Known command should be handled
        assertTrue(router.tryHandle("/help"));

        // Unknown command should not be handled
        assertFalse(router.tryHandle("/nonexistent"));

        // Non-command text should not be handled as command
        assertFalse(router.tryHandle("hello world"));
    }
}

