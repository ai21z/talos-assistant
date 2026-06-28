package dev.talos.cli.repl;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.ui.CliTheme;
import dev.talos.core.Config;
import dev.talos.core.security.Redactor;
import dev.talos.runtime.Result;
import dev.talos.runtime.Session;
import dev.talos.runtime.TurnProcessor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReplRouterRouteHintTest {
    private static final Path WS = Path.of(".").toAbsolutePath().normalize();

    @Test
    void autoNaturalLanguageRouteHintUsesAgentLabel() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ReplRouter router = router(out);

        assertTrue(router.tryHandlePrompt("Explain README.md"));

        assertTrue(out.toString(StandardCharsets.UTF_8).contains("route agent"));
    }

    @Test
    void autoCommandRouteHintUsesStructuralLabel() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ReplRouter router = router(out);

        assertTrue(router.tryHandlePrompt("ls"));

        assertTrue(out.toString(StandardCharsets.UTF_8).contains("route structural"));
    }

    private static ReplRouter router(ByteArrayOutputStream out) throws Exception {
        Config cfg = new Config();
        PrintStream print = new PrintStream(out, true, StandardCharsets.UTF_8);
        RenderEngine render = new RenderEngine(cfg, new Redactor(), print, true, CliTheme.current());
        TurnProcessor processor = new TurnProcessor(
                (rawLine, workspace, ctx) -> Optional.of(new Result.Ok("handled")));
        Context ctx = Context.builder(cfg).build();
        return new ReplRouter(
                ModeController.defaultController(),
                processor,
                new Session(WS, cfg),
                ctx,
                render,
                new dev.talos.cli.repl.slash.CommandRegistry(),
                WS,
                new AtomicBoolean(false),
                "",
                WorkspaceCommandTemplates.none());
    }
}
