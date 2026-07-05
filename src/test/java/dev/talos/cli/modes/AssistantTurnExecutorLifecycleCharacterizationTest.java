package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantTurnExecutorLifecycleCharacterizationTest {

    @Test
    void publicExecuteApiRemainsStable() throws Exception {
        Method execute = AssistantTurnExecutor.class.getDeclaredMethod(
                "execute",
                List.class,
                Path.class,
                Context.class,
                AssistantTurnExecutor.Options.class);

        assertTrue(Modifier.isPublic(execute.getModifiers()), "execute(...) must remain public");
        assertTrue(Modifier.isStatic(execute.getModifiers()), "execute(...) must remain static");
        assertTrue(execute.getReturnType().equals(AssistantTurnExecutor.TurnOutput.class),
                "execute(...) must continue returning TurnOutput");
    }

}
