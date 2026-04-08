package dev.talos.cli.repl;

import dev.talos.cli.commands.CommandRegistry;
import dev.talos.cli.commands.CommandSpec;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;
import java.util.Objects;

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

    /**
     * Create a completer backed by the given command registry.
     *
     * @param registry the registry containing all registered slash commands
     */
    public SlashCommandCompleter(CommandRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String buffer = line.line();
        if (buffer == null) return;

        // Only complete slash commands
        if (!buffer.startsWith("/")) return;

        // Strip the leading "/" to get the typed prefix
        String prefix = buffer.substring(1).toLowerCase();

        List<CommandSpec> specs = registry.allSpecs();
        for (CommandSpec spec : specs) {
            // Primary name
            if (spec.name().toLowerCase().startsWith(prefix)) {
                candidates.add(toCandidate(spec.name(), spec));
            }

            // Aliases
            if (spec.aliases() != null) {
                for (String alias : spec.aliases()) {
                    if (alias != null && alias.toLowerCase().startsWith(prefix)) {
                        // Avoid duplicate if alias == name
                        if (!alias.equals(spec.name())) {
                            candidates.add(toCandidate(alias, spec));
                        }
                    }
                }
            }
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
                "/" + name,           // value — what gets inserted
                "/" + name,           // display — what the user sees
                spec.groupDisplayName(), // group
                spec.summary(),       // descr — shown beside the candidate
                null,                 // suffix
                null,                 // key
                true                  // complete — candidate is a full word
        );
    }
}


