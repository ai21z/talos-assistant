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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact-byte pins of the /session surface, originally T797 baselines.
 * Updated deliberately by T800 (the diff is the behavioral-delta
 * documentation): the info block gained a {@code Session:} row for the
 * active instance id, and the usage line grew
 * {@code list|resume|export}. The no-saved-session, save, and clear
 * strings are byte-identical to the T797 pins.
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
                        // Legacy wiring: the active session is the bare-hash
                        // slot, so its display id is the short hash form.
                        + "Session:     " + sessionId.substring(0, 8) + "…\n"
                        + "Workspace:   demo-ws\n"
                        + "Turns:       0\n"
                        + "Has sketch:  no\n"
                        + "Saved file:  no",
                ((Result.Info) result).text);
    }

    @Test
    void infoShowsTheInjectedInstanceIdAsTimestampSuffix() {
        Path workspace = tempDir.resolve("demo-ws");
        String instanceId = JsonSessionStore.sessionIdFor(workspace) + "-20260612083005";
        SessionCommand command = new SessionCommand(workspace, new NoOpSessionStore(), instanceId);

        Result result = command.execute("info", Context.builder(new Config()).build());

        assertTrue(((Result.Info) result).text.contains("Session:     20260612083005\n"),
                "instance ids display as their timestamp suffix");
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
        assertEquals("No saved session found for this workspace.",
                ((Result.Info) command.execute("resume", ctx)).text);
        assertEquals("No saved session found for this workspace.",
                ((Result.Info) command.execute("list", ctx)).text);
        assertEquals("No saved session to delete.",
                ((Result.Info) command.execute("clear", ctx)).text);

        Result unknown = command.execute("bogus", ctx);
        assertInstanceOf(Result.Error.class, unknown);
        assertEquals("Unknown subcommand: bogus\nUsage: /session [info|list|resume|save|load|clear|export]",
                ((Result.Error) unknown).message);
    }
}
