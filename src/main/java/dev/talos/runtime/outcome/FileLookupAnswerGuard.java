package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.policy.EvidenceObligation;
import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.runtime.turn.CurrentTurnPlan;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounds negative unnamed-file lookup answers to current-turn source evidence. */
public final class FileLookupAnswerGuard {
    public static final String TRACE_REASON =
            "file-content lookup negative claim bounded to source evidence";
    public static final String PREFIX =
            "[Grounding scope: the requested file-backed fact was not established.]";

    private static final List<Pattern> BROAD_NEGATIVE_CLAIMS = List.of(
            Pattern.compile("(?is)\\b(?:i|we|talos|the model)\\s+"
                    + "(?:did not|didn't|could not|couldn't)\\s+"
                    + "(?:find|locate|identify|establish)\\b"),
            Pattern.compile("(?is)\\b(?:requested|relevant|service|configuration|config|workspace|"
                    + "file-backed|files?|information|value|key|port|setting|endpoint|dependency|"
                    + "version|script|fact)\\b.{0,100}\\b(?:was|were|is|are)\\s+"
                    + "(?:not found|not located|absent)\\b"),
            Pattern.compile("(?is)\\b(?:no|zero)\\s+(?:matches?|matching files?|relevant files?|"
                    + "results?|sources?|files?)\\b"),
            Pattern.compile("(?is)\\b(?:nothing|none)\\s+(?:was\\s+)?found\\b"),
            Pattern.compile("(?is)\\b(?:files?|sources?|workspace)\\b.{0,100}"
                    + "\\b(?:does|do|did)\\s+not\\s+contain\\b"));
    private static final Set<String> CONTENT_TOOLS = Set.of(
            "talos.read_file", "talos.grep", "talos.retrieve");
    private static final int MAX_DISCLOSED_SOURCES = 8;

    private FileLookupAnswerGuard() {}

    public static String boundNegativeClaimIfNeeded(
            String answer,
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult loopResult) {
        if (!requiresBoundedNegative(answer, plan)) return answer;
        List<String> sources = safeSourcePaths(loopResult);
        String sourceSentence = sources.isEmpty()
                ? "No non-protected source path established the requested fact in this turn."
                : "Source-backed file(s) inspected this turn: " + String.join(", ", sources) + ".";
        return PREFIX + "\n\n"
                + "The model did not establish the requested fact from current-turn source evidence. "
                + sourceSentence + " This does not prove the fact is absent from other workspace files.";
    }

    private static boolean requiresBoundedNegative(String answer, CurrentTurnPlan plan) {
        if (answer == null || answer.isBlank() || plan == null) return false;
        if (!EvidenceObligation.FILE_CONTENT_LOOKUP_REQUIRED.name().equals(plan.evidenceObligation())) {
            return false;
        }
        return BROAD_NEGATIVE_CLAIMS.stream().anyMatch(pattern -> pattern.matcher(answer).find());
    }

    private static List<String> safeSourcePaths(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null || loopResult.toolOutcomes() == null) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        boolean protectedSource = false;
        for (ToolCallLoop.ToolOutcome outcome : loopResult.toolOutcomes()) {
            if (outcome == null
                    || !outcome.success()
                    || outcome.denied()
                    || !CONTENT_TOOLS.contains(outcome.canonicalToolName())) {
                continue;
            }
            List<String> candidates = outcome.sourcePaths();
            if (candidates.isEmpty() && "talos.read_file".equals(outcome.canonicalToolName())) {
                candidates = List.of(outcome.pathHint());
            }
            for (String candidate : candidates) {
                String normalized = ToolCallSupport.normalizePath(candidate);
                if (normalized.isBlank()) continue;
                if ("<protected-path>".equals(normalized)
                        || ProtectedContentPolicy.looksProtectedPathString(normalized)) {
                    protectedSource = true;
                    continue;
                }
                if (out.size() < MAX_DISCLOSED_SOURCES) out.add(normalized);
            }
        }
        if (protectedSource && out.size() < MAX_DISCLOSED_SOURCES) out.add("<protected-path>");
        return List.copyOf(out);
    }
}
