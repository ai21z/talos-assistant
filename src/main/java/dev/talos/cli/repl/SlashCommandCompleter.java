package dev.talos.cli.repl;

import dev.talos.cli.repl.slash.CommandRegistry;
import dev.talos.cli.repl.slash.CommandSpec;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * JLine tab-completer for Talos slash commands.
 *
 * <p>Provides interactive autocomplete when the user types {@code /} at the prompt:
 * <ul>
 *   <li>{@code /} alone → lists all available commands</li>
 *   <li>{@code /r} → filters to commands starting with "r" (e.g., {@code /reindex}, {@code /route})</li>
 *   <li>{@code /help} → shows only {@code /help} (exact match)</li>
 * </ul>
 *
 * <p>Each candidate includes the command's summary as a description and the
 * command's group as a display group, giving a clean, organized autocomplete menu.
 *
 * <p>Non-slash input (natural language prompts) produces no completions, so
 * the completer doesn't interfere with normal chat input.
 */
public final class SlashCommandCompleter implements Completer {

    private final CommandRegistry registry;
    private final WorkspaceCommandTemplates templates;
    private final Supplier<List<String>> modelAliases;

    /**
     * Create a completer backed by the given command registry.
     *
     * @param registry the registry containing all registered slash commands
     */
    public SlashCommandCompleter(CommandRegistry registry) {
        this(registry, null);
    }

    /** T806: workspace template commands complete beside the built-ins. */
    public SlashCommandCompleter(CommandRegistry registry, WorkspaceCommandTemplates templates) {
        this(registry, templates, List::of);
    }

    /** Model aliases are supplied from the local in-memory selection catalog. */
    public SlashCommandCompleter(CommandRegistry registry, WorkspaceCommandTemplates templates,
                                 Supplier<List<String>> modelAliases) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.templates = templates == null ? WorkspaceCommandTemplates.none() : templates;
        this.modelAliases = modelAliases == null ? List::of : modelAliases;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        List<String> words = line.words();
        if (words == null || words.isEmpty() || !words.get(0).startsWith("/")) return;
        int index = line.wordIndex();
        if (index < 0 || index >= words.size()) return;
        String current = line.word();
        if (current == null) return;
        String prefix = current.substring(0, Math.min(line.wordCursor(), current.length()))
                .toLowerCase(Locale.ROOT);
        if (index > 0) {
            completeArgument(words, index, prefix, candidates);
            return;
        }
        if (!prefix.startsWith("/")) return;
        prefix = prefix.substring(1);

        List<CommandSpec> specs = registry.allSpecs();
        for (CommandSpec spec : specs) {
            if (spec.hidden()) continue;

            // Primary name
            if (spec.name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                candidates.add(toCandidate(spec.name(), spec));
            }

            // Aliases
            if (spec.aliases() != null) {
                for (String alias : spec.aliases()) {
                    if (alias != null && alias.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        // Avoid duplicate if alias == name
                        if (!alias.equals(spec.name())) {
                            candidates.add(toCandidate(alias, spec));
                        }
                    }
                }
            }
        }

        for (String name : templates.names()) {
            if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                candidates.add(new Candidate(
                        "/" + name,
                        "/" + name,
                        "Workspace",
                        "workspace template (.talos/commands/" + name + ".md)",
                        null,
                        null,
                        true));
            }
        }
    }

    private void completeArgument(List<String> words, int index, String prefix,
                                  List<Candidate> candidates) {
        String command = words.get(0).substring(1).toLowerCase(Locale.ROOT);
        Set<String> options = new LinkedHashSet<>();
        if (isNameFor(command, "help")) {
            if (index != 1) return;
            options.addAll(List.of("all", "commands", "full", "debug", "trace",
                    "security", "safety", "approval", "rag", "retrieval", "knowledge",
                    "models", "model"));
            for (CommandSpec spec : registry.allSpecs()) {
                if (spec.hidden()) continue;
                options.add(spec.name());
                options.addAll(spec.aliases());
            }
        } else if (isNameFor(command, "models")) {
            if (index == 1) {
                options.addAll(List.of("use", "add", "--verbose"));
            } else if (index == 2 && "use".equalsIgnoreCase(words.get(1))) {
                addModelAliases(options);
            }
        } else if (isNameFor(command, "set")) {
            if (index == 1) {
                options.add("model");
            } else if (index == 2 && "model".equalsIgnoreCase(words.get(1))) {
                addModelAliases(options);
            }
        }
        for (String option : options) {
            if (option != null && option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                candidates.add(new Candidate(option));
            }
        }
    }

    private boolean isNameFor(String typed, String primary) {
        return registry.allSpecs().stream()
                .filter(spec -> !spec.hidden() && spec.name().equals(primary))
                .anyMatch(spec -> spec.name().equals(typed) || spec.aliases().contains(typed));
    }

    private void addModelAliases(Set<String> options) {
        try {
            List<String> aliases = modelAliases.get();
            if (aliases != null) options.addAll(aliases);
        } catch (RuntimeException failure) {
            if (dev.talos.spi.types.ControlFlowFailure.classify(failure)
                    != dev.talos.spi.types.ControlFlowFailure.Kind.NONE) {
                throw dev.talos.spi.types.ControlFlowFailure.propagate(failure);
            }
            // Completion remains optional when local model metadata is unavailable.
        }
    }

    /**
     * Build a JLine {@link Candidate} for a command name.
     *
     * @param name the command or alias name (without "/")
     * @param spec the command spec (for description and group)
     * @return a candidate that JLine will display in the completion menu
     */
    private static Candidate toCandidate(String name, CommandSpec spec) {
        return new Candidate(
                "/" + name,           // value - what gets inserted
                "/" + name,           // display - what the user sees
                spec.groupDisplayName(), // group
                spec.summary(),       // descr - shown beside the candidate
                null,                 // suffix
                null,                 // key
                true                  // complete - candidate is a full word
        );
    }
}


