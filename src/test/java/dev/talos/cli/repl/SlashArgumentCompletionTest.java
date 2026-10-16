package dev.talos.cli.repl;

import dev.talos.cli.repl.slash.Command;
import dev.talos.cli.repl.slash.CommandGroup;
import dev.talos.cli.repl.slash.CommandRegistry;
import dev.talos.cli.repl.slash.CommandSpec;
import dev.talos.cli.repl.slash.StatusCommand;
import dev.talos.cli.modes.ModeController;
import dev.talos.runtime.Result;
import org.jline.reader.Candidate;
import org.jline.reader.Parser;
import org.jline.reader.impl.DefaultParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SlashArgumentCompletionTest {
    private final CommandRegistry registry = registry();
    private final AtomicInteger aliasReads = new AtomicInteger();
    private final SlashCommandCompleter completer = new SlashCommandCompleter(
            registry, null, () -> {
                aliasReads.incrementAndGet();
                return List.of("qwen-local", "gpt-local");
            });

    @Test void helpCompletesTopicsAndVisibleCommandsWithRealParser() {
        assertTrue(values("/help de").contains("debug"));
        assertTrue(values("/h stat").contains("status"));
        assertTrue(values("/help all").contains("all"));
        assertFalse(values("/help hid").contains("hidden-internal"));
        assertFalse(values("/help int").contains("internal"));
        assertEquals(0, aliasReads.get());
    }

    @Test void modelsCompletesSubcommandsAndAliasesOnlyAtRelevantPosition() {
        assertEquals(List.of("use"), values("/models us"));
        assertTrue(values("/model --v").contains("--verbose"));
        assertEquals(List.of("qwen-local"), values("/models use qw"));
        assertEquals(List.of("gpt-local"), values("/model use gp"));
        assertTrue(values("/models add qw").isEmpty());
        assertEquals(2, aliasReads.get());
    }

    @Test void setModelCompletesAliasesButOtherArgumentsStayUntouched() {
        assertEquals(List.of("model"), values("/set mo"));
        assertEquals(List.of("qwen-local"), values("/set model qw"));
        assertTrue(values("/status --v").isEmpty());
        assertTrue(values("/-v").isEmpty());
        assertTrue(values("/--verbose").isEmpty());
        assertTrue(values("ordinary text").isEmpty());
        assertEquals(1, aliasReads.get());
    }

    @Test void cursorInsideWordUsesOnlyTextBeforeCursor() {
        String line = "/models use qw-other";
        assertEquals(List.of("qwen-local"), values(line, "/models use qw".length()));
    }

    @Test void unavailableCatalogDoesNotBreakCompletion() {
        var unavailable = new SlashCommandCompleter(registry, null, () -> {
            throw new IllegalArgumentException("Unavailable local metadata");
        });
        var parsed = new DefaultParser().parse("/models use ", 12, Parser.ParseContext.COMPLETE);
        var candidates = new ArrayList<Candidate>();
        assertDoesNotThrow(() -> unavailable.complete(null, parsed, candidates));
        assertTrue(candidates.isEmpty());
    }

    @Test void cancellationIsNotSilencedByOptionalCompletion() {
        var cancelled = new SlashCommandCompleter(registry, null, () -> {
            throw new java.util.concurrent.CancellationException("cancelled");
        });
        var parsed = new DefaultParser().parse("/models use ", 12, Parser.ParseContext.COMPLETE);
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> cancelled.complete(null, parsed, new ArrayList<>()));
    }

    private List<String> values(String line) {
        return values(line, line.length());
    }

    private List<String> values(String line, int cursor) {
        var parsed = new DefaultParser().parse(line, cursor, Parser.ParseContext.COMPLETE);
        var candidates = new ArrayList<Candidate>();
        completer.complete(null, parsed, candidates);
        return candidates.stream().map(Candidate::value).toList();
    }

    private static CommandRegistry registry() {
        var registry = new CommandRegistry();
        registry.register(command("help", List.of("h", "?"), CommandGroup.SESSION, false));
        registry.register(command("models", List.of("model"), CommandGroup.MODELS, false));
        registry.register(command("set", List.of(), CommandGroup.MODELS, false));
        registry.register(new StatusCommand(ModeController.defaultController(), Path.of(".")));
        registry.register(command("hidden-internal", List.of("internal"), CommandGroup.DEBUG, true));
        return registry;
    }

    private static Command command(String name, List<String> aliases, CommandGroup group, boolean hidden) {
        return new Command() {
            @Override public CommandSpec spec() {
                return new CommandSpec(name, aliases, "/" + name, "stub", group, hidden);
            }

            @Override public Result execute(String args, Context ctx) {
                return new Result.Ok("stub");
            }
        };
    }
}
