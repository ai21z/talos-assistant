package dev.talos.cli.repl;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.ui.CliTheme;
import dev.talos.core.Config;
import dev.talos.core.security.Redactor;
import dev.talos.runtime.Result;
import dev.talos.runtime.Session;
import dev.talos.runtime.SessionListener;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnPublicationReceipt;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReplRouterPublicationHealthTest {
    private static final Path WS = Path.of(".").toAbsolutePath().normalize();

    @Test
    void degradedPublicationIsVisibleWithoutLeakingListenerFailureDetails() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TurnProcessor processor = processor();
        processor.addListener(new SessionListener() {
            @Override
            public void onTurnComplete(dev.talos.runtime.TurnResult result, String userInput) {
                throw new IllegalStateException("raw-listener-secret-7b9f");
            }
        });
        processor.addListener(new SessionListener() {
            @Override
            public TurnPublicationReceipt onTurnSettled(
                    dev.talos.runtime.TurnSettlement settlement,
                    String userInput
            ) {
                return TurnPublicationReceipt.degraded(
                        TurnPublicationReceipt.Reason.PERSISTENCE_FAILED);
            }
        });

        assertTrue(router(out, processor).tryHandlePrompt("inspect"));

        String rendered = out.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("handled"));
        assertTrue(rendered.contains(
                "Local turn evidence is degraded (LISTENER_EXCEPTION, PERSISTENCE_FAILED)."));
        assertFalse(rendered.contains("raw-listener-secret-7b9f"));
        assertFalse(rendered.contains(IllegalStateException.class.getName()));
    }

    @Test
    void healthyPublicationDoesNotChangeSuccessfulPromptOutput() throws Exception {
        ByteArrayOutputStream baselineOut = new ByteArrayOutputStream();
        assertTrue(router(baselineOut, processor()).tryHandlePrompt("inspect"));

        ByteArrayOutputStream healthyOut = new ByteArrayOutputStream();
        TurnProcessor healthyProcessor = processor();
        healthyProcessor.addListener(new SessionListener() {
            @Override
            public TurnPublicationReceipt onTurnSettled(
                    dev.talos.runtime.TurnSettlement settlement,
                    String userInput
            ) {
                return TurnPublicationReceipt.complete();
            }
        });
        assertTrue(router(healthyOut, healthyProcessor).tryHandlePrompt("inspect"));

        String baseline = baselineOut.toString(StandardCharsets.UTF_8);
        String healthy = healthyOut.toString(StandardCharsets.UTF_8);
        assertEquals(baseline, healthy);
        assertTrue(healthy.contains("handled"));
        assertFalse(healthy.contains("Local turn evidence is degraded"));
    }

    private static TurnProcessor processor() {
        return new TurnProcessor(
                (rawLine, workspace, ctx) -> Optional.of(new Result.Ok("handled")));
    }

    private static ReplRouter router(ByteArrayOutputStream out, TurnProcessor processor) {
        Config cfg = new Config();
        PrintStream print = new PrintStream(out, true, StandardCharsets.UTF_8);
        RenderEngine render = new RenderEngine(
                cfg, new Redactor(), print, false, CliTheme.current());
        return new ReplRouter(
                ModeController.defaultController(),
                processor,
                new Session(WS, cfg),
                Context.builder(cfg).build(),
                render,
                new dev.talos.cli.repl.slash.CommandRegistry(),
                WS,
                new AtomicBoolean(false),
                "",
                WorkspaceCommandTemplates.none());
    }
}
