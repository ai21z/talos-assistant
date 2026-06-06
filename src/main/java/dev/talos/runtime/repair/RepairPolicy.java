package dev.talos.runtime.repair;

import dev.talos.runtime.capability.StaticWebCapabilityProfile;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.toolcall.LoopState;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.runtime.verification.StaticTaskVerifier;
import dev.talos.spi.types.ChatMessage;

import java.nio.file.Path;
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
    private static final Pattern BACKTICKED_TOKEN = Pattern.compile("`([^`]+)`");
    private static final int MAX_SELECTOR_FACT_CHARS = 2_200;
    private static final int MAX_OBSERVED_SELECTOR_TOKENS = 24;

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
        if (expectedTargets.isEmpty() && problems.stream().anyMatch(StaticWebCapabilityProfile::isStructuralProblem)) {
            expectedTargets = StaticWebCapabilityProfile.inferStructuralTargets(messages, problems);
        }
        List<String> appliedMutationTargets = extractAppliedMutationTargets(previous);
        List<String> missingExpectedTargets = missingExpectedTargets(problems, expectedTargets);
        List<WrongTargetPair> similarWrongTargets = similarWrongTargets(
                missingExpectedTargets,
                appliedMutationTargets);
        Set<String> previousTargets = previousFailureTargets(
                previous,
                problems,
                messages,
                missingExpectedTargets);
        List<String> forbiddenTargets = contract.forbiddenTargets().stream()
                .sorted()
                .toList();
        previousTargets = withoutForbiddenTargets(previousTargets, forbiddenTargets);
        if (!expectedTargets.isEmpty()
                && !previousTargets.isEmpty()
                && !targetsOverlap(expectedTargets, previousTargets)) {
            return RepairDecision.notApplicable(
                    "static repair context skipped: targets did not overlap with current task targets");
        }
        boolean structuralWebRepair = problems.stream().anyMatch(StaticWebCapabilityProfile::isStructuralProblem);
        boolean frontendFrameworkCoherenceRepair =
                problems.stream().anyMatch(RepairPolicy::isFrontendFrameworkCoherenceProblem);
        List<RepairPlanStep> steps = planSteps(
                problems,
                expectedTargets,
                missingExpectedTargets,
                similarWrongTargets,
                forbiddenTargets);
        String instruction = renderStaticVerificationInstruction(
                problems,
                expectedTargets,
                steps,
                structuralWebRepair || frontendFrameworkCoherenceRepair,
                missingExpectedTargets,
                similarWrongTargets);

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

    public static String enrichSelectorFactsForRepairContext(String instruction, Path workspace) {
        if (instruction == null || instruction.isBlank()) return "";
        if (workspace == null
                || instruction.contains("[Current static selector facts]")
                || !hasSelectorRepairProblemInstruction(instruction)) {
            return instruction;
        }
        String selectorFacts = StaticTaskVerifier.renderTargetAwareSelectorInspection(
                workspace,
                repairInstructionTargetHints(instruction));
        if (selectorFacts == null || selectorFacts.isBlank()) {
            return instruction;
        }
        selectorFacts = compactSelectorFacts(selectorFacts);
        return instruction
                + "\n\n[Current static selector facts]\n"
                + selectorFacts
                + "\nUse these current facts when repairing static files; "
                + "do not preserve a selector listed as missing.";
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

    private static List<RepairPlanStep> planSteps(
            List<String> problems,
            List<String> expectedTargets,
            List<String> missingExpectedTargets,
            List<WrongTargetPair> similarWrongTargets,
            List<String> forbiddenTargets
    ) {
        List<RepairPlanStep> steps = new ArrayList<>();
        Set<String> targets = new LinkedHashSet<>();
        Set<String> forbiddenKeys = normalizedTargetKeys(forbiddenTargets);
        boolean structuralWebRepair = problems.stream().anyMatch(StaticWebCapabilityProfile::isStructuralProblem);
        boolean frontendFrameworkCoherenceRepair =
                problems.stream().anyMatch(RepairPolicy::isFrontendFrameworkCoherenceProblem);
        boolean siteCoherenceRepair = structuralWebRepair || frontendFrameworkCoherenceRepair;
        Set<String> verifierSpecificTargets = verifierSpecificStructuralRepairTargets(problems, expectedTargets);
        if (structuralWebRepair && !verifierSpecificTargets.isEmpty()) {
            targets.addAll(verifierSpecificTargets);
        } else if (siteCoherenceRepair && expectedTargets != null && !expectedTargets.isEmpty()) {
            targets.addAll(expectedTargets);
        } else {
            for (String problem : problems) {
                targets.addAll(extractTargets(problem));
            }
            if (targets.isEmpty() && expectedTargets != null) {
                targets.addAll(expectedTargets);
            }
        }
        removeWrongSimilarEvidenceTargets(targets, missingExpectedTargets, similarWrongTargets);
        removeForbiddenTargets(targets, forbiddenKeys);
        if (targets.isEmpty() && siteCoherenceRepair && expectedTargets != null && !expectedTargets.isEmpty()) {
            targets.addAll(expectedTargets);
            removeForbiddenTargets(targets, forbiddenKeys);
        }
        for (String target : targets) {
            if (!StaticWebCapabilityProfile.isSmallWebFile(target)) continue;
            steps.add(new RepairPlanStep(
                    RepairStepType.WRITE_COMPLETE_FILE,
                    target,
                    siteCoherenceRepair
                            ? "static verifier reported structural web-file problems"
                            : "static verifier reported unresolved web-file problem",
                    "You must use talos.write_file with complete corrected file content for " + target + ".",
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

    private static boolean isTailwindCoherenceProblem(String problem) {
        if (problem == null || problem.isBlank()) return false;
        String lower = problem.toLowerCase(Locale.ROOT);
        return lower.contains("tailwind")
                && (lower.contains("artifact")
                || lower.contains("directive")
                || lower.contains("cdn")
                || lower.contains("runtime")
                || lower.contains("build")
                || lower.contains("utility class"));
    }

    private static boolean isFrontendFrameworkCoherenceProblem(String problem) {
        if (problem == null || problem.isBlank()) return false;
        if (isTailwindCoherenceProblem(problem)) return true;
        String lower = problem.toLowerCase(Locale.ROOT);
        boolean namesFramework = containsFrameworkToken(problem, "bootstrap")
                || containsFrameworkToken(problem, "alpine")
                || containsFrameworkToken(problem, "htmx")
                || containsFrameworkToken(problem, "react")
                || containsFrameworkToken(problem, "vue");
        if (!namesFramework) return false;
        return lower.contains("artifact")
                || lower.contains("placeholder")
                || lower.contains("cdn")
                || lower.contains("runtime")
                || lower.contains("build")
                || lower.contains("framework");
    }

    private static boolean containsFrameworkToken(String value, String frameworkName) {
        if (value == null || value.isBlank() || frameworkName == null || frameworkName.isBlank()) {
            return false;
        }
        return Pattern.compile("(?i)(?<![A-Za-z0-9_-])"
                        + Pattern.quote(frameworkName)
                        + "(?![A-Za-z0-9_-])")
                .matcher(value)
                .find();
    }

    private static Set<String> withoutForbiddenTargets(
            Set<String> targets,
            List<String> forbiddenTargets
    ) {
        if (targets == null || targets.isEmpty()) return Set.of();
        Set<String> forbiddenKeys = normalizedTargetKeys(forbiddenTargets);
        if (forbiddenKeys.isEmpty()) return targets;
        LinkedHashSet<String> out = new LinkedHashSet<>(targets);
        removeForbiddenTargets(out, forbiddenKeys);
        return out;
    }

    private static Set<String> normalizedTargetKeys(List<String> targets) {
        if (targets == null || targets.isEmpty()) return Set.of();
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (String target : targets) {
            String key = normalizeTargetKey(target);
            if (!key.isBlank()) keys.add(key);
        }
        return keys;
    }

    private static void removeForbiddenTargets(Set<String> targets, Set<String> forbiddenKeys) {
        if (targets == null || targets.isEmpty()
                || forbiddenKeys == null || forbiddenKeys.isEmpty()) {
            return;
        }
        targets.removeIf(target -> forbiddenKeys.contains(normalizeTargetKey(target)));
    }

    private static Set<String> verifierSpecificStructuralRepairTargets(
            List<String> problems,
            List<String> expectedTargets
    ) {
        if (problems == null || problems.isEmpty()) return Set.of();
        Set<String> targets = new LinkedHashSet<>();
        for (String problem : problems) {
            Set<String> problemTargets = verifierSpecificTargetsForProblem(problem, expectedTargets);
            if (problemTargets.isEmpty()) {
                return Set.of();
            }
            targets.addAll(problemTargets);
        }
        return targets;
    }

    private static Set<String> verifierSpecificTargetsForProblem(
            String problem,
            List<String> expectedTargets
    ) {
        if (problem == null || problem.isBlank()) return Set.of();
        String lower = problem.toLowerCase(Locale.ROOT);
        if (lower.contains("css references missing class selectors")
                || lower.contains("css references missing id selectors")
                || lower.contains("css likely uses bare element selectors")) {
            return expectedTargetsWithExtension(expectedTargets, ".css");
        }
        if (lower.contains("javascript references missing class selectors")
                || lower.contains("javascript references missing ids")) {
            return expectedTargetsWithExtension(expectedTargets, ".js", ".jsx", ".ts", ".tsx");
        }
        if (lower.contains("html defines duplicate ids")
                || lower.contains("html file is empty")
                || lower.contains("unclosed `<")
                || lower.contains("malformed closing tag")) {
            return expectedTargetsWithExtension(expectedTargets, ".html", ".htm");
        }
        return Set.of();
    }

    private static Set<String> expectedTargetsWithExtension(List<String> expectedTargets, String... extensions) {
        Set<String> targets = new LinkedHashSet<>();
        if (expectedTargets != null) {
            for (String target : expectedTargets) {
                String normalized = normalizeTarget(target);
                String lower = normalized.toLowerCase(Locale.ROOT);
                for (String extension : extensions == null ? new String[0] : extensions) {
                    if (!extension.isBlank() && lower.endsWith(extension)) {
                        targets.add(normalized);
                        break;
                    }
                }
            }
        }
        if (!targets.isEmpty()) return targets;
        for (String extension : extensions == null ? new String[0] : extensions) {
            if (".css".equals(extension)) {
                targets.add("styles.css");
            } else if (".js".equals(extension)) {
                targets.add("scripts.js");
            } else if (".html".equals(extension)) {
                targets.add("index.html");
            }
        }
        return targets;
    }

    private static void removeWrongSimilarEvidenceTargets(
            Set<String> targets,
            List<String> missingExpectedTargets,
            List<WrongTargetPair> similarWrongTargets
    ) {
        if (targets == null || targets.isEmpty()
                || similarWrongTargets == null || similarWrongTargets.isEmpty()) {
            return;
        }
        Set<String> missingKeys = new LinkedHashSet<>();
        for (String target : missingExpectedTargets == null ? List.<String>of() : missingExpectedTargets) {
            String normalized = normalizeTargetKey(target);
            if (!normalized.isBlank()) missingKeys.add(normalized);
        }
        Set<String> wrongSimilarKeys = new LinkedHashSet<>();
        for (WrongTargetPair pair : similarWrongTargets) {
            String normalized = normalizeTargetKey(pair.appliedTarget());
            if (!normalized.isBlank() && !missingKeys.contains(normalized)) {
                wrongSimilarKeys.add(normalized);
            }
        }
        if (wrongSimilarKeys.isEmpty()) return;
        targets.removeIf(target -> wrongSimilarKeys.contains(normalizeTargetKey(target)));
    }

    private static String renderStaticVerificationInstruction(
            List<String> problems,
            List<String> expectedTargets,
            List<RepairPlanStep> steps,
            boolean structuralWebRepair,
            List<String> missingExpectedTargets,
            List<WrongTargetPair> similarWrongTargets
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

        if (missingExpectedTargets != null && !missingExpectedTargets.isEmpty()) {
            out.append("Missing expected targets: ")
                    .append(String.join(", ", missingExpectedTargets))
                    .append("\n");
        }
        if (similarWrongTargets != null && !similarWrongTargets.isEmpty()) {
            out.append("Similar changed targets that do not satisfy missing expected targets:\n");
            for (WrongTargetPair pair : similarWrongTargets) {
                out.append("- ").append(pair.appliedTarget())
                        .append(" does not satisfy ")
                        .append(pair.expectedTarget())
                        .append("; write or update ")
                        .append(pair.expectedTarget())
                        .append(" explicitly.\n");
            }
        }
        if ((missingExpectedTargets != null && !missingExpectedTargets.isEmpty())
                || (similarWrongTargets != null && !similarWrongTargets.isEmpty())) {
            out.append("\n");
        }

        out.append("Previous static verification problems:\n");
        for (String problem : problems.subList(0, Math.min(8, problems.size()))) {
            out.append("- ").append(problem).append("\n");
        }
        if (problems.size() > 8) {
            out.append("- ... ").append(problems.size() - 8).append(" more\n");
        }
        out.append("\nRepair plan:\n");
        List<String> fullWriteTargets = steps.stream()
                .filter(step -> step.type() == RepairStepType.WRITE_COMPLETE_FILE)
                .map(RepairPlanStep::targetPath)
                .filter(target -> target != null && !target.isBlank())
                .sorted()
                .toList();
        if (!fullWriteTargets.isEmpty()) {
            out.append("Full-file replacement targets: ")
                    .append(String.join(", ", fullWriteTargets))
                    .append("\n");
        }
        for (RepairPlanStep step : steps) {
            if (step.type() == RepairStepType.VERIFY_STATIC) {
                out.append("- Verify static checks again before claiming completion.\n");
            } else if (!step.targetPath().isBlank()) {
                out.append("- ").append(step.targetPath()).append(": ")
                        .append(step.instruction()).append("\n");
            }
        }
        if (structuralWebRepair
                && isCssOnlyRepairTargetSet(fullWriteTargets)
                && hasCssSelectorSourceProblem(problems)) {
            out.append("\nCSS selector repair constraint:\n")
                    .append("- Only CSS targets are in this repair plan, so do not depend on HTML edits ")
                    .append("to satisfy the verifier.\n")
                    .append("- For missing CSS class/id selector findings, rewrite the stylesheet so ")
                    .append("class/id selectors correspond to classes or IDs already present in HTML; ")
                    .append("remove or rename orphan selectors that are not used by HTML.\n")
                    .append("- Do not leave a reported missing selector unchanged unless the current HTML ")
                    .append("already defines that class or ID.\n");
        }
        if (!fullWriteTargets.isEmpty()) {
            out.append("\nFor these structural web repair targets, you must use talos.write_file ")
                    .append("with complete corrected file content. Do not use talos.edit_file ")
                    .append("for these structural web repair targets; partial edits are too brittle ")
                    .append("for these verifier findings. ");
            if (structuralWebRepair) {
                out.append(StaticWebCapabilityProfile.repairCoherenceGuidance(fullWriteTargets))
                        .append("\n\n");
            }
        } else {
            out.append("\nFor small HTML/CSS/JS files, prefer talos.write_file with complete corrected file content ")
                    .append("when exact talos.edit_file old_string matching would be brittle. ");
        }
        out.append("Do not repeat an edit_file old_string that already failed. ")
                .append("After tool-backed changes, answer only from tool results and static verification.");
        return out.toString();
    }

    private static boolean isCssOnlyRepairTargetSet(List<String> fullWriteTargets) {
        if (fullWriteTargets == null || fullWriteTargets.isEmpty()) return false;
        for (String target : fullWriteTargets) {
            if (target == null || !target.toLowerCase(Locale.ROOT).endsWith(".css")) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasCssSelectorSourceProblem(List<String> problems) {
        if (problems == null || problems.isEmpty()) return false;
        for (String problem : problems) {
            if (problem == null || problem.isBlank()) continue;
            String lower = problem.toLowerCase(Locale.ROOT);
            if (lower.contains("css references missing class selectors")
                    || lower.contains("css references missing id selectors")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSelectorRepairProblemInstruction(String instruction) {
        if (instruction == null || instruction.isBlank()) return false;
        String lower = instruction.toLowerCase(Locale.ROOT);
        return lower.contains("css references missing class selectors")
                || lower.contains("css references missing id selectors")
                || lower.contains("css likely uses bare element selectors")
                || lower.contains("javascript references missing class selectors")
                || lower.contains("javascript references missing ids");
    }

    private static List<String> repairInstructionTargetHints(String instruction) {
        if (instruction == null || instruction.isBlank()) return List.of();
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        addRepairInstructionTargets(targets, firstRepairContextValue(instruction, "Expected targets:"));
        addRepairInstructionTargets(targets, firstRepairContextValue(instruction, "Missing expected targets:"));
        addRepairInstructionTargets(targets, firstRepairContextValue(instruction, "Full-file replacement targets:"));
        return List.copyOf(targets);
    }

    private static void addRepairInstructionTargets(Set<String> out, String value) {
        if (out == null || value == null || value.isBlank() || value.startsWith("(")) return;
        for (String raw : value.split(",")) {
            String target = normalizeTarget(raw);
            if (!target.isBlank()) {
                out.add(target);
            }
        }
    }

    private static String compactSelectorFacts(String selectorFacts) {
        if (selectorFacts == null || selectorFacts.isBlank()) return "";
        if (selectorFacts.length() <= MAX_SELECTOR_FACT_CHARS) return selectorFacts;
        StringBuilder out = new StringBuilder();
        int mismatchLines = 0;
        boolean inMismatches = false;
        for (String rawLine : selectorFacts.split("\\R")) {
            String line = rawLine.stripTrailing();
            if (line.startsWith("- Classes:") || line.startsWith("- IDs:")) {
                appendLine(out, compactObservedSelectorLine(line));
                continue;
            }
            if (line.equals("Mismatches found:")) {
                inMismatches = true;
                appendLine(out, line);
                continue;
            }
            if (inMismatches && line.startsWith("- ")) {
                mismatchLines++;
                if (mismatchLines <= 12) {
                    appendLine(out, line);
                }
                continue;
            }
            appendLine(out, line);
        }
        if (mismatchLines > 12) {
            appendLine(out, "- ... " + (mismatchLines - 12) + " more selector/linkage mismatch lines omitted");
        }
        String compacted = out.toString().stripTrailing();
        if (compacted.length() <= MAX_SELECTOR_FACT_CHARS) return compacted;
        return compacted.substring(0, MAX_SELECTOR_FACT_CHARS - 80).stripTrailing()
                + "\n... selector fact context truncated after preserving primary targets and mismatch findings.";
    }

    private static String compactObservedSelectorLine(String line) {
        Matcher matcher = BACKTICKED_TOKEN.matcher(line);
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            String token = matcher.group(1);
            if (token != null && !token.isBlank()) tokens.add(token);
        }
        if (tokens.size() <= MAX_OBSERVED_SELECTOR_TOKENS) return line;
        String label = line.substring(0, line.indexOf(':') + 1);
        List<String> kept = tokens.subList(0, MAX_OBSERVED_SELECTOR_TOKENS);
        String rendered = kept.stream()
                .map(token -> "`" + token + "`")
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");
        return label + " " + rendered + ", ... "
                + (tokens.size() - kept.size()) + " more observed selectors omitted";
    }

    private static void appendLine(StringBuilder out, String line) {
        if (out.length() > 0) out.append('\n');
        out.append(line == null ? "" : line);
    }

    private static String firstRepairContextValue(String content, String label) {
        if (content == null || content.isBlank() || label == null || label.isBlank()) return "";
        String lowerLabel = label.toLowerCase(Locale.ROOT);
        for (String rawLine : content.split("\\R")) {
            String line = rawLine.strip();
            if (line.toLowerCase(Locale.ROOT).startsWith(lowerLabel)) {
                return line.substring(label.length()).strip();
            }
        }
        return "";
    }

    public static Set<String> fullRewriteTargetsFromRepairContext(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return Set.of();
        Set<String> targets = new LinkedHashSet<>();
        for (ChatMessage message : messages) {
            if (message == null || !"system".equals(message.role()) || message.content() == null) continue;
            String content = message.content();
            if (!content.startsWith("[Static verification repair context]")) continue;
            for (String rawLine : content.split("\\R")) {
                String line = rawLine.strip();
                if (!line.toLowerCase(Locale.ROOT).startsWith("full-file replacement targets:")) continue;
                String values = line.substring(line.indexOf(':') + 1);
                for (String value : values.split(",")) {
                    String target = normalizeTarget(value);
                    if (!target.isBlank()) targets.add(target);
                }
            }
        }
        return Set.copyOf(targets);
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
            if (looksLikeStaticVerificationPass(content)) {
                return null;
            }
            if (looksLikeStaticVerificationFailure(content)) {
                return content;
            }
        }
        return null;
    }

    private static boolean looksLikeStaticVerificationPass(String value) {
        if (value == null || value.isBlank()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("[static verification: passed")
                || lower.contains("static web coherence checks passed")
                || lower.contains("verification status: verified complete");
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
            String line = rawLine.strip();
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

    private static Set<String> previousFailureTargets(
            String previous,
            List<String> problems,
            List<ChatMessage> messages,
            List<String> missingExpectedTargets
    ) {
        Set<String> targets = new LinkedHashSet<>();
        if (missingExpectedTargets != null && !missingExpectedTargets.isEmpty()) {
            targets.addAll(missingExpectedTargets);
            return Set.copyOf(targets);
        }
        for (String problem : problems == null ? List.<String>of() : problems) {
            targets.addAll(extractTargets(problem));
        }
        if (targets.isEmpty()) {
            targets.addAll(extractTargets(firstStaticFailureLine(previous)));
        }
        if (targets.isEmpty()
                && problems != null
                && problems.stream().anyMatch(StaticWebCapabilityProfile::isStructuralProblem)) {
            targets.addAll(StaticWebCapabilityProfile.inferStructuralTargets(messages, problems));
        }
        return Set.copyOf(targets);
    }

    private static List<String> extractAppliedMutationTargets(String previous) {
        if (previous == null || previous.isBlank()) return List.of();
        Set<String> targets = new LinkedHashSet<>();
        boolean inAppliedSection = false;
        for (String rawLine : previous.split("\\R")) {
            String line = rawLine.strip();
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("applied mutating tool calls:")
                    || lower.startsWith("succeeded:")) {
                inAppliedSection = true;
                continue;
            }
            if (!inAppliedSection) continue;
            if (line.isBlank()) {
                if (!targets.isEmpty()) break;
                continue;
            }
            if (line.startsWith("-")) {
                targets.addAll(extractTargets(line));
                continue;
            }
            if (!targets.isEmpty()) break;
        }
        return targets.stream().sorted().toList();
    }

    private static List<String> missingExpectedTargets(
            List<String> problems,
            List<String> expectedTargets
    ) {
        if (problems == null || problems.isEmpty()) return List.of();
        Set<String> missing = new LinkedHashSet<>();
        for (String problem : problems) {
            if (problem == null) continue;
            String lower = problem.toLowerCase(Locale.ROOT);
            if (!lower.contains("expected target was not successfully mutated")) continue;
            int colon = problem.indexOf(':');
            if (colon > 0) {
                missing.addAll(extractTargets(problem.substring(0, colon)));
            }
            if (expectedTargets != null) {
                for (String expected : expectedTargets) {
                    if (lower.contains(normalizeTargetKey(expected))) {
                        missing.add(normalizeTarget(expected));
                    }
                }
            }
        }
        return missing.stream()
                .filter(target -> !target.isBlank())
                .sorted()
                .toList();
    }

    private static List<WrongTargetPair> similarWrongTargets(
            List<String> missingExpectedTargets,
            List<String> appliedMutationTargets
    ) {
        if (missingExpectedTargets == null || missingExpectedTargets.isEmpty()
                || appliedMutationTargets == null || appliedMutationTargets.isEmpty()) {
            return List.of();
        }
        List<WrongTargetPair> out = new ArrayList<>();
        for (String expected : missingExpectedTargets) {
            for (String applied : appliedMutationTargets) {
                if (normalizeTargetKey(expected).equals(normalizeTargetKey(applied))) continue;
                if (looksLikeSingularPluralSibling(expected, applied)) {
                    out.add(new WrongTargetPair(expected, applied));
                }
            }
        }
        return out.stream()
                .sorted(Comparator
                        .comparing(WrongTargetPair::expectedTarget)
                        .thenComparing(WrongTargetPair::appliedTarget))
                .toList();
    }

    private static boolean targetsOverlap(List<String> expectedTargets, Set<String> previousTargets) {
        Set<String> previous = new LinkedHashSet<>();
        for (String target : previousTargets == null ? Set.<String>of() : previousTargets) {
            String normalized = normalizeTargetKey(target);
            if (!normalized.isBlank()) previous.add(normalized);
        }
        for (String target : expectedTargets == null ? List.<String>of() : expectedTargets) {
            if (previous.contains(normalizeTargetKey(target))) {
                return true;
            }
        }
        return false;
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

    private static String normalizeTargetKey(String raw) {
        return normalizeTarget(raw).toLowerCase(Locale.ROOT);
    }

    private static boolean looksLikeSingularPluralSibling(String leftPath, String rightPath) {
        String left = normalizeTargetKey(leftPath);
        String right = normalizeTargetKey(rightPath);
        if (left.isBlank() || right.isBlank()) return false;

        int leftSlash = left.lastIndexOf('/');
        int rightSlash = right.lastIndexOf('/');
        String leftDir = leftSlash >= 0 ? left.substring(0, leftSlash + 1) : "";
        String rightDir = rightSlash >= 0 ? right.substring(0, rightSlash + 1) : "";
        if (!leftDir.equals(rightDir)) return false;

        String leftName = leftSlash >= 0 ? left.substring(leftSlash + 1) : left;
        String rightName = rightSlash >= 0 ? right.substring(rightSlash + 1) : right;
        int leftDot = leftName.lastIndexOf('.');
        int rightDot = rightName.lastIndexOf('.');
        if (leftDot <= 0 || rightDot <= 0) return false;
        String leftExt = leftName.substring(leftDot);
        String rightExt = rightName.substring(rightDot);
        if (!leftExt.equals(rightExt)) return false;

        String leftStem = leftName.substring(0, leftDot);
        String rightStem = rightName.substring(0, rightDot);
        return leftStem.equals(rightStem + "s") || rightStem.equals(leftStem + "s");
    }

    private static String singleLine(String value) {
        if (value == null) return "";
        String line = value.replace('\n', ' ').replace('\r', ' ').strip();
        return line.length() <= 300 ? line : line.substring(0, 297) + "...";
    }

    private record WrongTargetPair(String expectedTarget, String appliedTarget) {}
}
