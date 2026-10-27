package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HelpCommandLayoutTest {
    private static final String SUMMARY = "Inspect every local candidate and its configured disposition without "
            + "claiming that the artifact was verified or that the backend is ready for this session.";

    @ParameterizedTest
    @ValueSource(ints = {40, 80, 120})
    void inventoryPreservesFullDescriptionsWithinTerminalWidth(int width) {
        var registry = registry();
        var help = new HelpCommand(registry, null, () -> width);

        String output = plain(help.execute("all", null).toString());
        assertTrue(output.replaceAll("\\s+", " ").contains(
                SUMMARY.substring(0, SUMMARY.length() - 1)), output);
        assertTrue(output.contains("/help <cmd> for full options"), output);
        assertFalse(output.contains("/hidden-internal"), output);
        for (String line : output.split("\\R")) {
            assertTrue(line.length() <= width,
                    () -> "width " + width + " exceeded by " + line.length() + ": " + line);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {40, 80, 120})
    void defaultAndTopicHelpRemainLayered(int width) {
        var help = new HelpCommand(registry(), null, () -> width);

        String overview = plain(help.execute("", null).toString());
        String topic = plain(help.execute("models", null).toString());
        assertTrue(overview.contains("Talos Help"), overview);
        assertTrue(overview.contains("/help models"), overview);
        assertFalse(overview.contains("/hidden-internal"), overview);
        assertTrue(topic.contains("Model Help"), topic);
        assertTrue(topic.contains("/models --verbose"), topic);
        assertTrue(topic.replaceAll("\\s+", " ").contains("/set model <alias> is a compatibility form"), topic);
        assertTrue(topic.replaceAll("\\s+", " ").contains("do not establish artifact verification"), topic);
        if (width == 40) {
            for (String line : (overview + topic).split("\\R")) {
                assertTrue(line.length() <= width,
                        () -> "width " + width + " exceeded by " + line.length() + ": " + line);
            }
        }
    }

    private static CommandRegistry registry() {
        var registry = new CommandRegistry();
        registry.register(command("models", SUMMARY, false));
        registry.register(command("hidden-internal", "Hidden detail", true));
        return registry;
    }

    private static Command command(String name, String summary, boolean hidden) {
        return new Command() {
            @Override public CommandSpec spec() {
                return new CommandSpec(name, List.of(), "/" + name, summary,
                        CommandGroup.MODELS, hidden);
            }

            @Override public Result execute(String args, Context ctx) {
                return new Result.Ok("stub");
            }
        };
    }

    private static String plain(String value) {
        return value.replaceAll("\u001B\\[[;\\d]*m", "");
    }
}
