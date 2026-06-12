package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.NoOpSessionStore;
import dev.talos.runtime.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * T797 exact-byte pins of the /session surface T799/T800/T801 evolve:
 * the info block shape, the no-saved-session strings, and the usage line.
 * The later tickets update these pins deliberately — the diff is the
 * behavioral-delta documentation.
 */
class SessionCommandCharacterizationTest {

    @TempDir Path tempDir;

    @Test
    void infoBlockShapeForAnEmptySession() {
        Path workspace = tempDir.resolve("demo-ws");
        SessionCommand command = new SessionCommand(workspace, new NoOpSessionStore());
        String sessionId = JsonSessionStore.sessionIdFor(workspace);

        Result result = command.execute("info", Context.builder(new Config()).build());

        assertInstanceOf(Result.Info.class, result);
        assertEquals(
                "Session ID:  " + sessionId.substring(0, 8) + "…\n"
                        + "Workspace:   demo-ws\n"
                        + "Turns:       0\n"
                        + "Has sketch:  no\n"
                        + "Saved file:  no",
                ((Result.Info) result).text);
    }

    @Test
    void saveLoadClearAndUsageStringsArePinned() {
        Path workspace = tempDir.resolve("demo-ws");
        SessionCommand command = new SessionCommand(workspace, new NoOpSessionStore());
        Context ctx = Context.builder(new Config()).build();

        assertEquals("Session saved (0 exchanges, 0 messages).",
                ((Result.Info) command.execute("save", ctx)).text);
        assertEquals("No saved session found for this workspace.",
                ((Result.Info) command.execute("load", ctx)).text);
        assertEquals("No saved session to delete.",
                ((Result.Info) command.execute("clear", ctx)).text);

        Result unknown = command.execute("bogus", ctx);
        assertInstanceOf(Result.Error.class, unknown);
        assertEquals("Unknown subcommand: bogus\nUsage: /session [info|save|load|clear]",
                ((Result.Error) unknown).message);
    }
}
