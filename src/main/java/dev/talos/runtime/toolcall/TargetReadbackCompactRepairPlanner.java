package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.expectation.AppendLineExpectation;
import dev.talos.runtime.expectation.TaskExpectationResolver;
import dev.talos.runtime.repair.RepairPolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolAliasPolicy;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

final class TargetReadbackCompactRepairPlanner {
    private static final int COMPACT_READBACK_REPAIR_MAX_CHARS = 12_000;

    private TargetReadbackCompactRepairPlanner() {}

    enum Kind {
        APPEND_LINE,
        OLD_STRING_MISS
    }

    record Plan(
            Kind kind,
            String path,
            String promptedPathKey,
            List<ChatMessage> messages,
            List<ToolSpec> tools,
            ChatRequestControls controls,
            String retryName
    ) {}

    static Optional<Plan> nextAppendLinePlan(
            LoopState state,
            List<ToolSpec> baseTools,
            String userTask
    ) {
        if (state == null || state.toolOutcomes == null || state.toolOutcomes.isEmpty()) {
            return Optional.empty();
        }
        List<String> remainingExpectedTargets = remainingExpectedMutationTargets(state);
        if (remainingExpectedTargets.isEmpty()) return Optional.empty();
        Set<String> remaining = remainingExpectedTargets.stream()
                .map(TargetReadbackCompactRepairPlanner::normalizeExpectedTargetKey)
                .collect(Collectors.toSet());
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        for (int i = state.toolOutcomes.size() - 1; i >= 0; i--) {
            ToolCallLoop.ToolOutcome outcome = state.toolOutcomes.get(i);
            if (outcome == null || !outcome.appendLinePreservationFailure()) continue;
            String pathKey = normalizeExpectedTargetKey(outcome.pathHint());
            if (pathKey.isBlank() || !remaining.contains(pathKey)) continue;
            if (state.appendLineRepairPromptedPaths.contains(pathKey)) continue;
            String path = displayExpectedTargetForKey(remainingExpectedTargets, pathKey);
            if (path.isBlank()) {
                path = ToolCallSupport.normalizePath(outcome.pathHint());
            }
            if (isSensitiveReadbackPath(path) || !successfulReadbackForPath(state, path)) continue;
            AppendLineExpectation expectation = appendLineExpectationForPath(contract, path);
            if (expectation == null || expectation.expectedLine().isBlank()) continue;
            String readback = latestSuccessfulReadbackForPath(state, path);
            if (readback == null || readback.isBlank()) continue;
            return Optional.of(new Plan(
                    Kind.APPEND_LINE,
                    path,
                    pathKey,
                    appendLineRepairMessages(
                            path,
                            expectation.expectedLine(),
                            outcome.errorMessage(),
                            truncateForCompactRepair(readback),
                            userTask),
                    repairToolSpecs(baseTools),
                    repairControls(state, baseTools, "append-line-compact-repair"),
                    "append-line compact repair"));
        }
        return Optional.empty();
    }

    static Optional<Plan> nextOldStringMissPlan(
            LoopState state,
            List<ToolSpec> baseTools,
            String userTask
    ) {
        if (state == null || state.toolOutcomes == null || state.toolOutcomes.isEmpty()) {
            return Optional.empty();
        }
        List<String> remainingExpectedTargets = remainingExpectedMutationTargets(state);
        if (remainingExpectedTargets.isEmpty()) return Optional.empty();
        Set<String> remaining = remainingExpectedTargets.stream()
                .map(TargetReadbackCompactRepairPlanner::normalizeExpectedTargetKey)
                .collect(Collectors.toSet());
        for (int i = state.toolOutcomes.size() - 1; i >= 0; i--) {
            ToolCallLoop.ToolOutcome outcome = state.toolOutcomes.get(i);
            if (outcome == null || !outcome.oldStringNotFoundEditFailure()) continue;
            String pathKey = normalizeExpectedTargetKey(outcome.pathHint());
            if (pathKey.isBlank() || !remaining.contains(pathKey)) continue;
            if (state.oldStringMissRepairPromptedPaths.contains(pathKey)) continue;
            String path = displayExpectedTargetForKey(remainingExpectedTargets, pathKey);
            if (path.isBlank()) {
                path = ToolCallSupport.normalizePath(outcome.pathHint());
            }
            if (!successfulReadbackForPath(state, path)) continue;
            String readback = latestSuccessfulReadbackForPath(state, path);
            if (readback == null || readback.isBlank()) continue;
            return Optional.of(new Plan(
                    Kind.OLD_STRING_MISS,
                    path,
                    pathKey,
                    oldStringMissRepairMessages(
                            path,
                            outcome.errorMessage(),
                            truncateForCompactRepair(readback),
                            userTask),
                    repairToolSpecs(baseTools),
                    repairControls(state, baseTools, "old-string-miss-compact-repair"),
                    "old-string miss compact repair"));
        }
        return Optional.empty();
    }

    private static AppendLineExpectation appendLineExpectationForPath(TaskContract contract, String path) {
        if (contract == null || path == null || path.isBlank()) return null;
        String target = ToolCallSupport.normalizePath(path).toLowerCase(Locale.ROOT);
        for (var expectation : TaskExpectationResolver.resolve(contract)) {
            if (expectation instanceof AppendLineExpectation appendLine
                    && ToolCallSupport.normalizePath(appendLine.targetPath())
                    .toLowerCase(Locale.ROOT)
                    .equals(target)) {
                return appendLine;
            }
        }
        return null;
    }

    static boolean successfulReadbackForPath(LoopState state, String normalizedPath) {
        if (state == null || normalizedPath == null || normalizedPath.isBlank()) return false;
        String targetKey = normalizeExpectedTargetKey(normalizedPath);
        if (targetKey.isBlank()) return false;
        for (ToolCallLoop.ToolOutcome outcome : state.toolOutcomes) {
            if (outcome == null || !outcome.success()) continue;
            if (!"talos.read_file".equals(canonicalToolName(outcome.toolName()))) continue;
            if (targetKey.equals(normalizeExpectedTargetKey(outcome.pathHint()))) {
                return true;
            }
        }
        return false;
    }

    static String latestSuccessfulReadbackForPath(LoopState state, String normalizedPath) {
        if (state == null || normalizedPath == null || normalizedPath.isBlank()) {
            return null;
        }
        String target = ToolCallSupport.canonicalizeReadPath(normalizedPath)
                .toLowerCase(Locale.ROOT);
        String fullBody = latestSuccessfulReadbackForPath(state.successfulReadCallBodies, target);
        if (fullBody != null) return fullBody;
        return latestSuccessfulReadbackForPath(state.successfulReadCalls, target);
    }

    private static String latestSuccessfulReadbackForPath(Map<String, String> readbacksBySignature, String target) {
        if (readbacksBySignature == null || readbacksBySignature.isEmpty()
                || target == null || target.isBlank()) {
            return null;
        }
        for (var entry : readbacksBySignature.entrySet()) {
            String signature = entry.getKey() == null
                    ? ""
                    : entry.getKey().replace('\\', '/').toLowerCase(Locale.ROOT);
            if (signature.startsWith("talos.read_file:")
                    && signature.contains("path=" + target + ";")) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static List<ToolSpec> repairToolSpecs(List<ToolSpec> baseTools) {
        List<ToolSpec> base = baseTools == null ? List.of() : baseTools;
        List<ToolSpec> narrowed = filterTools(base, List.of("talos.edit_file", "talos.write_file"));
        return narrowed.isEmpty() ? base : narrowed;
    }

    private static List<ChatMessage> oldStringMissRepairMessages(
            String path,
            String reason,
            String readback,
            String userTask
    ) {
        String currentTask = userTask == null || userTask.isBlank()
                ? "Apply the requested file change."
                : userTask.strip();
        return List.of(
                ChatMessage.system("""
                        You are Talos, a local-first workspace assistant.
                        This is a compact target-only repair after talos.edit_file failed because old_string was not found.
                        Use the provided current file readback as the only file-content source.
                        Use talos.write_file with complete target content for small Markdown/prose files unless a precise talos.edit_file replacement is obvious from the readback.
                        Do not answer in prose instead of calling a write/edit tool.
                        """),
                ChatMessage.system(
                        "[OldStringMissRepair] Target: " + path + "\n"
                                + "Failed reason: " + safeRepairReason(reason) + "\n"
                                + "Only mutate this target. Ignore stale prior history outside this compact repair frame."),
                ChatMessage.user(
                        "Current user request:\n"
                                + currentTask
                                + "\n\nCurrent readback for " + path + ":\n"
                                + readback
                                + "\n\nApply the current request to " + path
                                + " using talos.write_file or talos.edit_file now."));
    }

    private static List<ChatMessage> appendLineRepairMessages(
            String path,
            String expectedLine,
            String reason,
            String readback,
            String userTask
    ) {
        String currentTask = userTask == null || userTask.isBlank()
                ? "Append the requested line to the target file."
                : userTask.strip();
        return List.of(
                ChatMessage.system("""
                        You are Talos, a local-first workspace assistant.
                        This is a compact target-only repair after talos.write_file was blocked before approval because it did not preserve the same-turn readback for an append-line task.
                        Use the provided current file readback as the only file-content source.
                        Prefer talos.write_file with complete target content equal to the readback plus exactly the required appended line as the final logical line.
                        Do not answer in prose instead of calling a write/edit tool.
                        """),
                ChatMessage.system(
                        "[AppendLineRepair] Target: " + path + "\n"
                                + "Required appended line: " + expectedLine + "\n"
                                + "Failed reason: " + safeAppendLineRepairReason(reason) + "\n"
                                + "Only mutate this target. Ignore stale prior history outside this compact repair frame."),
                ChatMessage.user(
                        "Current user request:\n"
                                + currentTask
                                + "\n\nCurrent readback for " + path + ":\n"
                                + readback
                                + "\n\nAppend exactly this line as the final logical line:\n"
                                + expectedLine
                                + "\n\nCall talos.write_file or talos.edit_file now."));
    }

    private static ChatRequestControls repairControls(
            LoopState state,
            List<ToolSpec> tools,
            String debugTag
    ) {
        if (state == null
                || state.ctx == null
                || state.ctx.llm() == null
                || !state.ctx.llm().supportsRequiredToolChoice()
                || !hasMutatingTool(tools)) {
            return ChatRequestControls.defaults();
        }
        return new ChatRequestControls(
                ToolChoiceMode.REQUIRED,
                "",
                ResponseFormatMode.TEXT,
                "",
                List.of("pending-action-obligation", debugTag));
    }

    private static List<String> remainingExpectedMutationTargets(LoopState state) {
        if (state == null || state.messages == null) return List.of();
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        if (contract == null || !contract.mutationAllowed()) {
            return List.of();
        }
        if (!RepairPolicy.fullRewriteTargetsFromRepairContext(state.messages).isEmpty()
                || !state.staticWebFullRewriteRequiredTargets.isEmpty()) {
            return List.of();
        }
        String latestUserRequest = ToolCallSupport.latestUserRequestIn(state.messages);
        Set<String> expectedTargets = contract.expectedTargets().isEmpty()
                ? TaskContractResolver.extractExpectedTargets(latestUserRequest)
                : contract.expectedTargets();
        if (expectedTargets.isEmpty()) {
            return List.of();
        }
        Set<String> satisfiedTargets = new java.util.HashSet<>();
        for (ToolCallLoop.ToolOutcome outcome : state.toolOutcomes) {
            if (outcome == null || !outcome.success() || !outcome.mutating()) continue;
            addSatisfiedExpectedTargetKeys(satisfiedTargets, outcome);
        }
        java.util.LinkedHashMap<String, String> expectedDisplayByKey = new java.util.LinkedHashMap<>();
        for (String target : expectedTargets) {
            String display = ToolCallSupport.normalizePath(target);
            String key = normalizeExpectedTargetKey(display);
            if (!key.isBlank()) {
                expectedDisplayByKey.putIfAbsent(key, display);
            }
        }
        return expectedDisplayByKey.entrySet().stream()
                .filter(entry -> !satisfiedTargets.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .sorted()
                .toList();
    }

    private static void addSatisfiedExpectedTargetKeys(
            Set<String> satisfiedTargets,
            ToolCallLoop.ToolOutcome outcome
    ) {
        if (satisfiedTargets == null || outcome == null) return;
        WorkspaceOperationPlan plan = outcome.workspaceOperationPlan();
        if (plan != null && !plan.pathEffects().isEmpty()) {
            for (WorkspaceOperationPlan.PathEffect effect : plan.pathEffects()) {
                addExpectedTargetPathKeys(satisfiedTargets, effect.path());
            }
            return;
        }
        addExpectedTargetPathKeys(satisfiedTargets, outcome.pathHint());
    }

    private static void addExpectedTargetPathKeys(Set<String> satisfiedTargets, String path) {
        String normalized = normalizeExpectedTargetKey(path);
        if (normalized.isBlank()) return;
        satisfiedTargets.add(normalized);
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < normalized.length()) {
            satisfiedTargets.add(normalized.substring(slash + 1));
        }
    }

    private static String displayExpectedTargetForKey(List<String> targets, String key) {
        if (targets == null || targets.isEmpty() || key == null || key.isBlank()) return "";
        for (String target : targets) {
            String display = ToolCallSupport.normalizePath(target);
            if (!display.isBlank() && key.equals(normalizeExpectedTargetKey(display))) {
                return display;
            }
        }
        return "";
    }

    private static boolean isSensitiveReadbackPath(String path) {
        if (path == null || path.isBlank()) return true;
        String normalized = ToolCallSupport.normalizePath(path).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return true;
        for (String segment : normalized.split("/")) {
            if (segment.equals(".env") || segment.startsWith(".env.")) return true;
            if (segment.equals(".git") || segment.equals(".ssh") || segment.equals(".gnupg")) return true;
        }
        return normalized.contains("id_rsa")
                || normalized.contains("credentials")
                || normalized.contains("secret");
    }

    private static String truncateForCompactRepair(String readback) {
        if (readback == null || readback.length() <= COMPACT_READBACK_REPAIR_MAX_CHARS) {
            return readback;
        }
        return readback.substring(0, COMPACT_READBACK_REPAIR_MAX_CHARS)
                + "\n... [readback truncated for compact old-string repair]";
    }

    private static String safeRepairReason(String reason) {
        if (reason == null || reason.isBlank()) return "old_string not found";
        return reason.strip();
    }

    private static String safeAppendLineRepairReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "append-line write_file did not preserve same-turn readback";
        }
        return reason.strip();
    }

    private static String normalizeExpectedTargetKey(String path) {
        return ToolCallSupport.normalizePath(path).toLowerCase(Locale.ROOT);
    }

    private static String canonicalToolName(String toolName) {
        ToolAliasPolicy.Decision decision = ToolAliasPolicy.resolve(toolName);
        if (decision.accepted() && decision.canonicalToolName() != null && !decision.canonicalToolName().isBlank()) {
            return decision.canonicalToolName();
        }
        return toolName == null ? "" : toolName;
    }

    private static List<ToolSpec> filterTools(List<ToolSpec> specs, List<String> allowedNames) {
        if (specs == null || specs.isEmpty() || allowedNames == null || allowedNames.isEmpty()) {
            return List.of();
        }
        return specs.stream()
                .filter(spec -> spec != null && allowedNames.contains(spec.name()))
                .toList();
    }

    private static boolean hasMutatingTool(List<ToolSpec> specs) {
        if (specs == null || specs.isEmpty()) return false;
        for (ToolSpec spec : specs) {
            String name = spec == null ? "" : spec.name();
            if ("talos.write_file".equals(name) || "talos.edit_file".equals(name)) {
                return true;
            }
        }
        return false;
    }
}
