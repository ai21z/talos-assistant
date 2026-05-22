package dev.talos.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeCliBoundaryOwnershipTest {

    @Test
    void runtimeUsesOwnedContextAndRouterPortsInsteadOfCliAdapters() throws Exception {
        String toolCallLoop = Files.readString(Path.of("src/main/java/dev/talos/runtime/ToolCallLoop.java"));
        String turnProcessor = Files.readString(Path.of("src/main/java/dev/talos/runtime/TurnProcessor.java"));
        String loopState = Files.readString(Path.of("src/main/java/dev/talos/runtime/toolcall/LoopState.java"));
        String context = Files.readString(Path.of("src/main/java/dev/talos/cli/repl/Context.java"));
        String modeController = Files.readString(Path.of("src/main/java/dev/talos/cli/modes/ModeController.java"));
        String baseline = Files.readString(Path.of("config/architecture-boundary-baseline.txt"));

        assertTrue(Files.exists(Path.of("src/main/java/dev/talos/runtime/RuntimeTurnContext.java")),
                "runtime should own the context view it needs from the CLI composition root");
        assertTrue(Files.exists(Path.of("src/main/java/dev/talos/runtime/TurnRouter.java")),
                "runtime should own the turn-routing port used by TurnProcessor");

        assertFalse(toolCallLoop.contains("dev.talos.cli.repl.Context"), toolCallLoop);
        assertFalse(turnProcessor.contains("dev.talos.cli.repl.Context"), turnProcessor);
        assertFalse(turnProcessor.contains("dev.talos.cli.modes.ModeController"), turnProcessor);
        assertFalse(loopState.contains("dev.talos.cli.repl.Context"), loopState);

        assertTrue(context.contains("implements RuntimeTurnContext"), context);
        assertTrue(modeController.contains("implements TurnRouter"), modeController);
        assertFalse(baseline.contains("dev.talos.cli.repl.Context"), baseline);
        assertFalse(baseline.contains("dev.talos.cli.modes.ModeController"), baseline);
    }
}
