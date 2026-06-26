package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.spi.types.ChatMessage;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grounding postcondition for read-only review/proposal answers (T762).
 *
 * <p>If the answer states commands, internal prompt text, or workspace-file
 * claims that were not present in this turn's observed tool evidence, the
 * answer is prefixed with a grounding warning (and lines mentioning an
 * explicitly excluded {@code .env} are removed).
 *
 * <p>Previously this logic lived inline in AssistantTurnExecutor and its
 * file detection was a hardcoded list of the AGENTS.md audit fixture's
 * filenames (.env, config.json, index.html, notes.md, report.docx,
 * script.js, styles.css) — teaching-to-the-test: audit answers were
 * checked, real-workspace answers were not. File detection is now
 * evidence-derived: any filename-shaped mention in the answer is checked
 * against what the tools actually read or touched this turn.
 */
public final class ReadOnlyProposalGroundingGuard {

    public static final String GROUNDED_PROPOSAL_WARNING = "[Grounding warning: "
            + "Some commands, dependencies, protected-path advice, or file-content claims below were not present "
            + "in inspected workspace evidence. Treat unobserved items as conditional examples, "
            + "not observed project facts.]";

    private static final Set<String> READ_ONLY_PROPOSAL_MARKERS = Set.of(
            "review",
            "propose",
            "proposal",
            "improvement",
            "improvements",
            "suggest",
            "suggestions");

    /** Generic ecosystem command/dependency claims — not fixture-specific. */
    private static final Set<String> UNVERIFIED_COMMAND_OR_DEPENDENCY_MARKERS = Set.of(
            "npm install",
            "npm start",
            "yarn install",
            "yarn start",
            "pnpm install",
            "pnpm start",
            "node script.js",
            "node.js",
            "gradle",
            "gradlew",
            "maven",
            "mvn ",
            "pip install",
            "python -m");

    /** Internal prompt/runtime text that must never be claimed as file content. */
    private static final Set<String> UNVERIFIED_INTERNAL_CONTENT_MARKERS = Set.of(
            "behavior rules",
            "how to work",
            "what not to do",
            "you are an action-capable local assistant",
            "full read/write access",
            "python",
            "node",
            "talos.write_file",
            "talos.edit_file",
            "talos.read_file",
            "talos.list_dir",
            "talos.grep",
            "talos.retrieve");

    /**
     * Filename-shaped mention: word-ish stem + alphabetic extension of 2-8
     * chars, not embedded in a larger path/word. Matched on the lowercased
     * answer. Versions ("1.2.3") and abbreviations ("e.g.") don't match
     * (non-alphabetic or one-letter extensions).
     */
    private static final Pattern FILE_MENTION = Pattern.compile(
            "(?<![\\w./\\\\-])([a-z0-9_][a-z0-9_.-]*\\.[a-z]{2,8})(?![\\w-])");

    /** Leading-dot config-file shape class (generic across ecosystems). */
    private static final Pattern DOTFILE_MENTION = Pattern.compile(
            "(?<![\\w./\\\\-])(\\.(?:env|gitignore|gitattributes|editorconfig|npmrc|nvmrc"
                    + "|dockerignore|eslintrc|prettierrc|babelrc)(?:\\.[a-z0-9_-]+)*)(?![\\w-])");

    /** Domain-style "extensions" that are not files when mentioned bare. */
    private static final Set<String> NON_FILE_EXTENSIONS = Set.of("com", "org", "net", "io", "dev");

    private ReadOnlyProposalGroundingGuard() {}

    public static String apply(
            String answer,
            TaskContract contract,
            String latestUserRequest,
            ToolCallLoop.LoopResult loopResult
    ) {
        if (answer == null || answer.isBlank()) return answer;
        if (!isReadOnlyReviewProposalTurn(contract, latestUserRequest)) return answer;

        String evidence = observedToolEvidence(loopResult).toLowerCase(Locale.ROOT);
        String pathEvidence = observedPathEvidence(loopResult);
        String current = answer;
        boolean warned = hasUnobservedCommandOrDependencyClaim(current, evidence)
                || hasUnobservedInternalContentClaim(current, evidence)
                || hasUnobservedFileMention(current, evidence, pathEvidence);
        if (requestExcludesEnv(latestUserRequest)
                && !evidence.contains(".env")
                && current.toLowerCase(Locale.ROOT).contains(".env")) {
            String stripped = removeLinesMentioningEnv(current);
            if (!Objects.equals(stripped, current)) {
                current = stripped;
                warned = true;
            }
        }

        if (!warned || current.startsWith(GROUNDED_PROPOSAL_WARNING)) return current;
        return GROUNDED_PROPOSAL_WARNING + "\n\n" + current;
    }

    private static boolean isReadOnlyReviewProposalTurn(TaskContract contract, String latestUserRequest) {
        if (contract == null || contract.mutationRequested()) return false;
        TaskType type = contract.type();
        if (type != TaskType.DIAGNOSE_ONLY
                && type != TaskType.READ_ONLY_QA
                && type != TaskType.WORKSPACE_EXPLAIN) {
            return false;
        }
        String lower = latestUserRequest == null ? "" : latestUserRequest.toLowerCase(Locale.ROOT);
        boolean proposal = READ_ONLY_PROPOSAL_MARKERS.stream().anyMatch(lower::contains);
        boolean documentTarget = lower.contains("readme") || lower.contains(".md");
        return proposal && documentTarget;
    }

    private static String observedToolEvidence(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null || loopResult.messages() == null || loopResult.messages().isEmpty()) return "";
        StringBuilder evidence = new StringBuilder();
        for (ChatMessage message : loopResult.messages()) {
            if (message == null || message.content() == null) continue;
            if (!"tool".equals(message.role()) && !message.content().contains("[tool_result:")) continue;
            evidence.append('\n').append(message.content());
        }
        return evidence.toString();
    }

    /**
     * Paths the tools actually touched this turn. Tool-result text does not
     * echo target paths (read_file output is numbered lines), so a reviewed
     * file's own name must count as evidenced even when its content never
     * mentions it.
     */
    private static String observedPathEvidence(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null) return "";
        StringBuilder out = new StringBuilder();
        if (loopResult.readPaths() != null) {
            for (String path : loopResult.readPaths()) {
                if (path != null && !path.isBlank()) out.append('\n').append(path);
            }
        }
        if (loopResult.toolOutcomes() != null) {
            for (ToolCallLoop.ToolOutcome outcome : loopResult.toolOutcomes()) {
                if (outcome == null || outcome.pathHint().isBlank()) continue;
                out.append('\n').append(outcome.pathHint());
            }
        }
        return out.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static boolean hasUnobservedCommandOrDependencyClaim(String answer, String evidenceLower) {
        if (answer == null || answer.isBlank()) return false;
        String lower = answer.toLowerCase(Locale.ROOT);
        String evidence = evidenceLower == null ? "" : evidenceLower;
        for (String marker : UNVERIFIED_COMMAND_OR_DEPENDENCY_MARKERS) {
            if (!lower.contains(marker)) continue;
            if (evidence.contains(marker)) continue;
            if (markerAlreadyMarkedConditional(lower, marker)) continue;
            return true;
        }
        return false;
    }

    private static boolean hasUnobservedInternalContentClaim(String answer, String evidenceLower) {
        if (answer == null || answer.isBlank()) return false;
        String lower = answer.toLowerCase(Locale.ROOT);
        String evidence = evidenceLower == null ? "" : evidenceLower;
        for (String marker : UNVERIFIED_INTERNAL_CONTENT_MARKERS) {
            if (lower.contains(marker) && !evidence.contains(marker)) return true;
        }
        return false;
    }

    /**
     * Evidence-derived replacement for the old fixture-filename list: every
     * filename-shaped mention in the answer must appear in the observed tool
     * evidence or among the paths the tools actually touched.
     */
    private static boolean hasUnobservedFileMention(String answer, String evidenceLower, String pathEvidenceLower) {
        if (answer == null || answer.isBlank()) return false;
        String lower = answer.toLowerCase(Locale.ROOT);
        for (String mention : fileMentions(lower)) {
            if (evidenceLower.contains(mention)) continue;
            if (pathEvidenceLower.contains(mention)) continue;
            return true;
        }
        return false;
    }

    private static Set<String> fileMentions(String lowerAnswer) {
        Set<String> mentions = new LinkedHashSet<>();
        Matcher files = FILE_MENTION.matcher(lowerAnswer);
        while (files.find()) {
            String mention = files.group(1);
            String extension = mention.substring(mention.lastIndexOf('.') + 1);
            if (NON_FILE_EXTENSIONS.contains(extension)) continue;
            // Mentions that ARE ecosystem command markers (e.g. node.js) keep
            // the command check's conditional-context semantics instead.
            if (UNVERIFIED_COMMAND_OR_DEPENDENCY_MARKERS.contains(mention)) continue;
            mentions.add(mention);
        }
        Matcher dotfiles = DOTFILE_MENTION.matcher(lowerAnswer);
        while (dotfiles.find()) {
            mentions.add(dotfiles.group(1));
        }
        return mentions;
    }

    private static boolean markerAlreadyMarkedConditional(String lowerAnswer, String marker) {
        int index = lowerAnswer.indexOf(marker);
        while (index >= 0) {
            int start = Math.max(0, index - 120);
            String context = lowerAnswer.substring(start, index);
            if (context.contains("if applicable")
                    || context.contains("for example")
                    || context.contains("example")
                    || context.contains("placeholder")
                    || context.contains("optional")
                    || context.contains("if this project")) {
                return true;
            }
            index = lowerAnswer.indexOf(marker, index + marker.length());
        }
        return false;
    }

    private static boolean requestExcludesEnv(String request) {
        if (request == null || request.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT);
        return lower.contains(".env")
                && (lower.contains("do not want")
                || lower.contains("don't want")
                || lower.contains("not the .env")
                || lower.contains("do not inspect")
                || lower.contains("don't inspect"));
    }

    private static String removeLinesMentioningEnv(String answer) {
        StringBuilder out = new StringBuilder();
        for (String line : answer.lines().toList()) {
            if (line.toLowerCase(Locale.ROOT).contains(".env")) continue;
            if (!out.isEmpty()) out.append('\n');
            out.append(line);
        }
        return out.toString().strip();
    }
}
