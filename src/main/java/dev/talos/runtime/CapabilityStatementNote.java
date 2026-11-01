package dev.talos.runtime;

import dev.talos.core.tool.ToolNamePolicy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Runtime notes for Talos tool names that a final answer names without running them (MICA invariant I4).
 * A note states the runtime fact for a mutating tool, an accepted alias of one, or an unknown name in the Talos
 * namespace. Availability comes from the tool surface the turn offered. The model text is never changed.
 */
final class CapabilityStatementNote {
    /** The snake case shape of a Talos tool identifier, so a file name such as talos.json gains no note. */
    private static final Pattern TOOL_IDENTIFIER = Pattern.compile("talos\\.[a-z]+(?:_[a-z]+)+");

    private CapabilityStatementNote() {
    }

    static String annotate(String answer, Collection<String> offeredTools, Collection<String> ranTools) {
        return annotate(answer, answer, offeredTools, ranTools);
    }

    /** Notes come only from the model statement, and are appended after the unchanged answer. */
    static String annotate(String answer, String statement, Collection<String> offeredTools,
                           Collection<String> ranTools) {
        List<String> notes = notes(statement, offeredTools, ranTools);
        if (notes.isEmpty()) return answer;
        String separator = answer.endsWith("\n\n") ? "" : answer.endsWith("\n") ? "\n" : "\n\n";
        return answer + separator + String.join("\n", notes);
    }

    static List<String> notes(String answer, Collection<String> offeredTools, Collection<String> ranTools) {
        Set<String> offered = canonical(offeredTools);
        Set<String> ran = canonical(ranTools);
        Set<String> seen = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();
        for (String token : ToolNamePolicy.toolLikeTokens(answer)) {
            String named = ToolNamePolicy.normalizeTalosSeparator(token.toLowerCase(Locale.ROOT));
            if (!named.startsWith("talos.") || !seen.add(named)) continue;
            ToolNamePolicy.Decision decision = ToolNamePolicy.resolve(token);
            if (decision.accepted()) {
                String tool = decision.canonicalToolName();
                if (!decision.mutating() || ran.contains(tool)) continue;
                String subject = named.equals(tool) ? tool : named + " is an alias of " + tool + ", which";
                notes.add("[Runtime note: " + subject + (offered.contains(tool)
                        ? " is available in this turn and changes files only under the approval policy.]"
                        : " is not available in this turn.]"));
            } else if (decision.status() == ToolNamePolicy.AliasDecisionStatus.UNKNOWN
                    && TOOL_IDENTIFIER.matcher(named).matches()) {
                notes.add("[Runtime note: " + named + " is not a Talos tool.]");
            }
        }
        return List.copyOf(notes);
    }

    private static Set<String> canonical(Collection<String> names) {
        Set<String> canonical = new LinkedHashSet<>();
        if (names == null) return canonical;
        for (String name : names) {
            ToolNamePolicy.Decision decision = ToolNamePolicy.resolve(name);
            if (decision.accepted()) canonical.add(decision.canonicalToolName());
        }
        return canonical;
    }
}
