package dev.talos.runtime.repair;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.toolcall.LoopState;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.spi.types.ChatMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded repair policy for verifier-driven and invalid-edit repair prompts. */
public final class RepairPolicy {

    private static final Pattern FILE_TARGET = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_./\\\\-])([A-Za-z0-9_.\\\\/-]+\\."
                    + "(?:html|htm|css|js|jsx|ts|tsx|java|md|txt|json|yaml|yml|xml|"
                    + "properties|gradle|kts|toml|ini|env|csv))"
                    + "(?=$|\\s|[`'\"),;:!?\\]]|\\.(?:$|\\s))");

    private RepairPolicy() {}

    public static RepairDecision planForStaticVerification(
            List<ChatMessage> messages,
            TaskContract contract
    ) {
        if (messages == null || messages.isEmpty()) {
            return RepairDecision.notApplicable("no messages");
        }
        if (contract == null || !contract.mutationAllowed()) {
            return RepairDecision.notApplicable("current task is not mutation-capable");
        }
        if (!looksLikeRepairContinuation(latestUserRequest(messages))) {
            return RepairDecision.notApplicable("current prompt is not a repair continuation");
        }

        String previous = previousStaticVerificationFailure(messages);
        if (previous == null || previous.isBlank()) {
            return RepairDecision.notApplicable("no previous static verification failure");
        }

        List<String> problems = extractProblemBullets(previous);
        if (problems.isEmpty()) {
            problems = List.of(firstStaticFailureLine(previous));
        }
        List<String> expectedTargets = contract.expectedTargets().stream()
                .sorted()
                .toList();
        List<String> forbiddenTargets = contract.forbiddenTargets().stream()
                .sorted()
                .toList();
        List<RepairPlanStep> steps = planSteps(problems, expectedTargets);
        String instruction = renderStaticVerificationInstruction(problems, expectedTargets, steps);

        return RepairDecision.planned(new RepairPlan(
                "repair-static-verification-v1",
                RepairPlanKind.STATIC_VERIFICATION_REPAIR,
                steps,
                RepairAttemptBudget.defaults(),
                "Use previous static verification findings as a bounded repair checklist.",
                true,
                true,
                true,
                problems,
                expectedTargets,
                forbiddenTargets,
                instruction));
    }

    public static Optional<RepairInstruction> nextStaleEditRepair(LoopState state) {
        if (state == null
                || state.staleEditFailuresByPath.isEmpty()
                || state.pathsMutatedSinceRead.isEmpty()) {
            return Optional.empty();
        }

        return state.staleEditFailuresByPath.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() >= 1)
                .filter(entry -> state.pathsMutatedSinceRead.contains(entry.getKey()))
                .filter(entry -> !state.staleEditRepairPromptedPaths.contains(entry.getKey()))
                .max(Comparator
                        .<java.util.Map.Entry<String, Integer>>comparingInt(java.util.Map.Entry::getValue)
                        .thenComparing(java.util.Map.Entry::getKey))
                .map(entry -> new RepairInstruction(
                        RepairPlanKind.STALE_EDIT_REREAD_REPAIR,
                        entry.getKey(),
                        staleEditRepairInstruction(entry.getKey())));
    }

    public static Optional<RepairInstruction> nextEmptyEditRepair(LoopState state) {
        if (state == null
                || state.emptyEditArgumentFailuresByPath.isEmpty()
                || state.pathsReadThisTurn.isEmpty()) {
            return Optional.empty();
        }

        return state.emptyEditArgumentFailuresByPath.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() >= 1)
                .filter(entry -> state.pathsReadThisTurn.contains(entry.getKey()))
                .filter(entry -> !state.emptyEditRepairPromptedPaths.contains(entry.getKey()))
                .max(Comparator
                        .<java.util.Map.Entry<String, Integer>>comparingInt(java.util.Map.Entry::getValue)
                        .thenComparing(java.util.Map.Entry::getKey))
                .map(entry -> new RepairInstruction(
                        RepairPlanKind.INVALID_EDIT_ARGUMENT_REPAIR,
                        entry.getKey(),
                        emptyEditRepairInstruction(entry.getKey())));
    }

    public static String staleEditRepairInstruction(String path) {
        String target = path == null || path.isBlank() ? "the target file" : "`" + path + "`";
        return "[Stale edit repair required] You edited " + target
                + " earlier in this turn, and a later talos.edit_file call for the same file failed "
                + "because old_string was not found. The file contents have changed. Your next step "
                + "for this file must be talos.read_file on " + target
                + " only; do not call talos.edit_file for this path again until after that read_file "
                + "result has been returned in a separate follow-up. If you cannot reread the file, "
                + "stop and say the remaining edit was not applied.";
    }

    public static String emptyEditRepairInstruction(String path) {
        String target = path == null || path.isBlank() ? "the target file" : "`" + path + "`";
        return "[Edit repair required] You previously called talos.edit_file for "
                + target
                + " with empty old_string/new_string, and the file has now been read. "
                + "Your next talos.edit_file call for this file must include a non-empty "
                + "old_string copied exactly from the latest talos.read_file result, without "
                + "line-number prefixes, and a new_string parameter containing the intended "
                + "replacement. new_string may be empty only for an explicit deletion task. "
                + "Use this key layout: {\"name\":\"talos.edit_file\","
                + "\"arguments\":{\"path\":\"" + targetPathForJson(path) + "\","
                + "\"old_string\":\"...\",\"new_string\":\"...\"}}. "
                + "Fill old_string and new_string with real file text, not placeholders. "
                + "Do not call talos.edit_file with empty old_string again. If you "
                + "cannot form the exact edit, stop and say no edit was applied.";
    }

    private static List<RepairPlanStep> planSteps(List<String> problems, List<String> expectedTargets) {
        List<RepairPlanStep> steps = new ArrayList<>();
        Set<String> targets = new LinkedHashSet<>();
        for (String problem : problems) {
            targets.addAll(extractTargets(problem));
        }
        if (targets.isEmpty() && expectedTargets != null) {
            targets.addAll(expectedTargets);
        }
        for (String target : targets) {
            if (!isSmallWebFile(target)) continue;
            steps.add(new RepairPlanStep(
                    RepairStepType.WRITE_COMPLETE_FILE,
                    target,
                    "static verifier reported unresolved web-file problem",
                    "Use talos.write_file with complete corrected file content for " + target + ".",
                    false));
        }
        steps.add(new RepairPlanStep(
                RepairStepType.VERIFY_STATIC,
                "",
                "repair output must be verified before completion can be claimed",
                "Run static post-apply verification before claiming the task is complete.",
                false));
        return List.copyOf(steps);
    }

    private static String renderStaticVerificationInstruction(
            List<String> problems,
            List<String> expectedTargets,
            List<RepairPlanStep> steps
    ) {
        StringBuilder out = new StringBuilder();
        out.append("[Static verification repair context]\n")
                .append("The previous mutation task ended incomplete after static verification. ")
                .append("Use the prior verifier findings as the repair checklist for this turn.\n\n")
                .append("Expected targets: ")
                .append(expectedTargets == null || expectedTargets.isEmpty()
                        ? "(not available from current task contract)"
                        : String.join(", ", expectedTargets))
                .append("\n\n");

        out.append("Previous static verification problems:\n");
        for (String problem : problems.subList(0, Math.min(8, problems.size()))) {
            out.append("- ").append(problem).append("\n");
        }
        if (problems.size() > 8) {
            out.append("- ... ").append(problems.size() - 8).append(" more\n");
        }
        out.append("\nRepair plan:\n");
        for (RepairPlanStep step : steps) {
            if (step.type() == RepairStepType.VERIFY_STATIC) {
                out.append("- Verify static checks again before claiming completion.\n");
            } else if (!step.targetPath().isBlank()) {
                out.append("- ").append(step.targetPath()).append(": ")
                        .append(step.instruction()).append("\n");
            }
        }
        out.append("\nFor small HTML/CSS/JS files, prefer talos.write_file with complete corrected file content ")
                .append("when exact talos.edit_file old_string matching would be brittle. ")
                .append("Do not repeat an edit_file old_string that already failed. ")
                .append("After tool-backed changes, answer only from tool results and static verification.");
        return out.toString();
    }

    private static boolean looksLikeRepairContinuation(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT);
        return lower.contains("fix")
                || lower.contains("repair")
                || lower.contains("remaining")
                || lower.contains("try again")
                || lower.contains("try one more time")
                || lower.contains("complete")
                || lower.contains("finish")
                || lower.contains("make it work")
                || lower.contains("still does not work")
                || lower.contains("still doesn't work")
                || lower.contains("nothing changed")
                || lower.contains("nothing happened")
                || lower.contains("overwrite")
                || lower.contains("write_file");
    }

    private static String latestUserRequest(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message == null || !"user".equals(message.role())) continue;
            String content = message.content();
            if (ToolCallSupport.isSyntheticToolResultContent(content)) continue;
            return content == null || content.isBlank() ? null : content;
        }
        return null;
    }

    private static String previousStaticVerificationFailure(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message == null || !"assistant".equals(message.role())) continue;
            String content = message.content();
            if (looksLikeStaticVerificationFailure(content)) {
                return content;
            }
        }
        return null;
    }

    private static boolean looksLikeStaticVerificationFailure(String value) {
        if (value == null || value.isBlank()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("static verification failed")
                || lower.contains("partial verification")
                || lower.contains("remaining static verification problems")
                || lower.contains("unresolved static verification problems")
                || lower.contains("task incomplete");
    }

    private static List<String> extractProblemBullets(String previous) {
        if (previous == null || previous.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        boolean inProblems = false;
        for (String rawLine : previous.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.strip();
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("remaining static verification problems")
                    || lower.contains("unresolved static verification problems")) {
                inProblems = true;
                continue;
            }
            if (!inProblems) continue;
            if (line.isBlank()) {
                if (!out.isEmpty()) break;
                continue;
            }
            if (line.startsWith("-")) {
                String problem = line.substring(1).strip();
                if (!problem.isBlank()) {
                    out.add(singleLine(problem));
                }
                continue;
            }
            if (!out.isEmpty()) break;
        }
        return List.copyOf(out);
    }

    private static String firstStaticFailureLine(String previous) {
        if (previous == null || previous.isBlank()) return "Static verification failed.";
        for (String rawLine : previous.split("\\R")) {
            String line = singleLine(rawLine);
            if (line.isBlank()) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("static verification")
                    || lower.contains("task incomplete")
                    || lower.contains("not verified complete")) {
                return line;
            }
        }
        return "Static verification failed.";
    }

    private static Set<String> extractTargets(String text) {
        if (text == null || text.isBlank()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        Matcher matcher = FILE_TARGET.matcher(text);
        while (matcher.find()) {
            String target = normalizeTarget(matcher.group(1));
            if (!target.isBlank()) out.add(target);
        }
        return out;
    }

    private static boolean isSmallWebFile(String target) {
        String lower = target == null ? "" : target.toLowerCase(Locale.ROOT);
        return lower.endsWith(".html")
                || lower.endsWith(".htm")
                || lower.endsWith(".css")
                || lower.endsWith(".js")
                || lower.endsWith(".jsx")
                || lower.endsWith(".ts")
                || lower.endsWith(".tsx");
    }

    private static String targetPathForJson(String path) {
        if (path == null || path.isBlank()) return "<target path>";
        return path.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String normalizeTarget(String raw) {
        if (raw == null) return "";
        String normalized = raw.strip()
                .replace('\\', '/')
                .replaceAll("^[`'\"(\\[]+", "")
                .replaceAll("[`'\"),.;:!?\\]]+$", "");
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static String singleLine(String value) {
        if (value == null) return "";
        String line = value.replace('\n', ' ').replace('\r', ' ').strip();
        return line.length() <= 300 ? line : line.substring(0, 297) + "...";
    }
}
