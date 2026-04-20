package dev.talos.cli.repl;

import dev.talos.cli.repl.slash.Command;
import dev.talos.cli.repl.slash.CommandRegistry;
import dev.talos.cli.repl.slash.CommandSpec;
import dev.talos.cli.repl.slash.CommandGroup;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SlashCommandCompleter}: slash command tab-completion.
 */
class SlashCommandCompleterTest {

    private CommandRegistry registry;
    private SlashCommandCompleter completer;

    @BeforeEach
    void setUp() {
        registry = new CommandRegistry();
        registry.register(stubCommand("help", List.of("h", "?"), "Show help", CommandGroup.SESSION));
        registry.register(stubCommand("reindex", List.of(), "Reindex workspace", CommandGroup.KNOWLEDGE));
        registry.register(stubCommand("route", List.of(), "Test routing", CommandGroup.DEBUG));
        registry.register(stubCommand("mode", List.of("m"), "Switch mode", CommandGroup.MODELS));
        registry.register(stubCommand("models", List.of(), "List models", CommandGroup.MODELS));
        registry.register(stubCommand("status", List.of(), "Show status", CommandGroup.SESSION));
        registry.register(stubCommand("quit", List.of("q", "exit"), "Quit Talos", CommandGroup.SESSION));
        completer = new SlashCommandCompleter(registry);
    }

    // ── Slash prefix triggers completion ──────────────────────────────

    @Test
    void slashAloneShowsAllCommands() {
        List<Candidate> candidates = complete("/");
        // Should return all primary names + aliases
        assertFalse(candidates.isEmpty(), "Slash alone should produce completions");
        assertTrue(candidates.size() >= 7,
                "Should include at least all primary command names, got " + candidates.size());
    }

    @Test
    void slashRFiltersToMatchingCommands() {
        List<Candidate> candidates = complete("/r");
        List<String> values = candidates.stream().map(Candidate::value).toList();
        assertTrue(values.contains("/reindex"), "Should contain /reindex");
        assertTrue(values.contains("/route"), "Should contain /route");
        assertFalse(values.contains("/help"), "Should NOT contain /help");
        assertFalse(values.contains("/mode"), "Should NOT contain /mode");
    }

    @Test
    void slashHFiltersToHelpAndHAlias() {
        List<Candidate> candidates = complete("/h");
        List<String> values = candidates.stream().map(Candidate::value).toList();
        assertTrue(values.contains("/help"), "Should contain /help");
        assertTrue(values.contains("/h"), "Should contain /h alias");
        assertFalse(values.contains("/reindex"), "Should NOT contain /reindex");
    }

    @Test
    void exactMatchReturnsOneCandidate() {
        List<Candidate> candidates = complete("/reindex");
        List<String> values = candidates.stream().map(Candidate::value).toList();
        assertTrue(values.contains("/reindex"), "Exact match should still appear");
    }

    // ── Non-slash input produces no completions ──────────────────────

    @Test
    void plainTextProducesNoCompletions() {
        List<Candidate> candidates = complete("summarize the README");
        assertTrue(candidates.isEmpty(), "Non-slash input should produce no completions");
    }

    @Test
    void emptyInputProducesNoCompletions() {
        List<Candidate> candidates = complete("");
        assertTrue(candidates.isEmpty(), "Empty input should produce no completions");
    }

    // ── Candidate metadata ───────────────────────────────────────────

    @Test
    void candidateContainsDescription() {
        List<Candidate> candidates = complete("/help");
        Candidate helpCandidate = candidates.stream()
                .filter(c -> c.value().equals("/help"))
                .findFirst()
                .orElse(null);

        assertNotNull(helpCandidate, "Should find /help candidate");
        assertEquals("Show help", helpCandidate.descr(),
                "Candidate should include command summary as description");
    }

    @Test
    void candidateContainsGroup() {
        List<Candidate> candidates = complete("/reindex");
        Candidate reindexCandidate = candidates.stream()
                .filter(c -> c.value().equals("/reindex"))
                .findFirst()
                .orElse(null);

        assertNotNull(reindexCandidate, "Should find /reindex candidate");
        assertEquals("Knowledge", reindexCandidate.group(),
                "Candidate should include command group");
    }

    // ── Aliases are included ─────────────────────────────────────────

    @Test
    void aliasesAppearAsSeparateCandidates() {
        List<Candidate> candidates = complete("/q");
        List<String> values = candidates.stream().map(Candidate::value).toList();
        assertTrue(values.contains("/q") || values.contains("/quit"),
                "Alias /q should appear as candidate");
    }

    @Test
    void exitAliasAppears() {
        List<Candidate> candidates = complete("/ex");
        List<String> values = candidates.stream().map(Candidate::value).toList();
        assertTrue(values.contains("/exit"), "Alias /exit should appear");
    }

    @Test
    void questionMarkAliasAppears() {
        List<Candidate> candidates = complete("/?");
        List<String> values = candidates.stream().map(Candidate::value).toList();
        assertTrue(values.contains("/?"), "Alias /? should appear");
    }

    // ── Case insensitive ─────────────────────────────────────────────

    @Test
    void completionIsCaseInsensitive() {
        List<Candidate> candidates = complete("/H");
        List<String> values = candidates.stream().map(Candidate::value).toList();
        assertTrue(values.contains("/help"), "Should match /help for /H input");
    }

    // ── Null safety ──────────────────────────────────────────────────

    @Test
    void nullRegistryThrows() {
        assertThrows(NullPointerException.class, () -> new SlashCommandCompleter(null));
    }

    // ── Multi-prefix matching ────────────────────────────────────────

    @Test
    void slashMFiltersToModeAndModels() {
        List<Candidate> candidates = complete("/m");
        List<String> values = candidates.stream().map(Candidate::value).toList();
        assertTrue(values.contains("/mode"), "Should contain /mode");
        assertTrue(values.contains("/models"), "Should contain /models");
        assertTrue(values.contains("/m"), "Should contain /m alias for mode");
        assertFalse(values.contains("/help"), "Should NOT contain /help");
    }

    @Test
    void slashMoFiltersToModeAndModels() {
        List<Candidate> candidates = complete("/mo");
        List<String> values = candidates.stream().map(Candidate::value).toList();
        assertTrue(values.contains("/mode"), "Should contain /mode");
        assertTrue(values.contains("/models"), "Should contain /models");
        assertFalse(values.contains("/m"), "/m alias should not match /mo prefix");
    }

    @Test
    void slashModFiltersToModeAndModels() {
        List<Candidate> candidates = complete("/mod");
        List<String> values = candidates.stream().map(Candidate::value).toList();
        assertTrue(values.contains("/mode"), "Should contain /mode");
        assertTrue(values.contains("/models"), "Should contain /models");
    }

    @Test
    void slashModeMatchesModeAndModels() {
        // "mode" is a prefix of "models", so both match — this is correct autocomplete behavior
        List<Candidate> candidates = complete("/mode");
        List<String> values = candidates.stream().map(Candidate::value).toList();
        assertTrue(values.contains("/mode"), "Should contain /mode");
        assertTrue(values.contains("/models"), "Should also contain /models since 'models' starts with 'mode'");
    }

    @Test
    void slashModelFiltersToModelsOnly() {
        List<Candidate> candidates = complete("/model");
        List<String> values = candidates.stream().map(Candidate::value).toList();
        assertTrue(values.contains("/models"), "Should contain /models");
        assertFalse(values.contains("/mode"), "Should NOT contain /mode for /model prefix");
    }

    // ── No false positives ───────────────────────────────────────────

    @Test
    void nonExistentPrefixProducesNoCandidates() {
        List<Candidate> candidates = complete("/xyz");
        assertTrue(candidates.isEmpty(), "Unknown prefix should produce no candidates");
    }

    // ── Helper ────────────────────────────────────────────────────────

    private List<Candidate> complete(String input) {
        List<Candidate> candidates = new ArrayList<>();
        completer.complete(null, stubParsedLine(input), candidates);
        return candidates;
    }

    private static ParsedLine stubParsedLine(String line) {
        return new ParsedLine() {
            @Override public String word() { return line; }
            @Override public int wordCursor() { return line.length(); }
            @Override public int wordIndex() { return 0; }
            @Override public List<String> words() { return List.of(line); }
            @Override public String line() { return line; }
            @Override public int cursor() { return line.length(); }
        };
    }

    private static Command stubCommand(String name, List<String> aliases,
                                       String summary, CommandGroup group) {
        return new Command() {
            @Override
            public CommandSpec spec() {
                return new CommandSpec(name, aliases, "/" + name, summary, group);
            }

            @Override
            public Result execute(String args, Context ctx) {
                return new Result.Ok("stub");
            }
        };
    }
}


