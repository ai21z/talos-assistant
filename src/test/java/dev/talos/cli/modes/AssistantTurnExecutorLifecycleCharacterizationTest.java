package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantTurnExecutorLifecycleCharacterizationTest {

    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path REPORT = ROOT.resolve(
            "work-cycle-docs/reports/t811-assistant-turn-executor-lifecycle-characterization.md");

    @Test
    void lifecycleReportPinsScopesAndExtractionBoundaries() throws Exception {
        assertTrue(Files.exists(REPORT), "T811 requires a committed lifecycle characterization report");
        String report = Files.readString(REPORT);

        assertContainsAll(report, List.of(
                "# T811 AssistantTurnExecutor Lifecycle Ownership Characterization",
                "priority index is only a review-order heuristic",
                "## Lifecycle Ownership Map",
                "`APPLICATION`",
                "`SESSION`",
                "`TURN`",
                "`TOOL_LOOP`",
                "`TOOL_CALL`",
                "`TRACE`",
                "`TEMPORARY`",
                "`UNKNOWN`",
                "## Existing Behavior Coverage",
                "## Extraction Order Authorized By This Characterization",
                "## Stop Conditions",
                "## Post-Extraction Result",
                "Turn preparation and prompt-audit setup",
                "AssistantTurnPreparation",
                "Final-answer truthfulness is not a standalone owner"));
    }

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

    private static void assertContainsAll(String text, List<String> needles) {
        for (String needle : needles) {
            assertTrue(text.contains(needle), "Missing characterization anchor: " + needle);
        }
    }
}
