package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.capability.StaticWebCapabilityProfile;
import dev.talos.runtime.expectation.ReplacementExpectation;
import dev.talos.runtime.expectation.TaskExpectationResolver;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class ExpectedTargetScopeRepairPlanner {
    private static final int COMPACT_READBACK_REPAIR_MAX_CHARS = 12_000;

    private ExpectedTargetScopeRepairPlanner() {}

    record Plan(
            List<String> expectedTargets,
            String failedTarget,
            String key,
            List<ChatMessage> messages,
            List<ToolSpec> tools,
            ChatRequestControls controls,
            String retryName,
            ChatMessage.NativeToolCall exactReplacementRepair,
            String traceDetail
    ) {}

    private record ExpectedTargetRepair(
            List<String> expectedTargets,
            String failedTarget,
            String reason,
            String readbackFrame,
            String replacementOldText,
            String replacementNewText
    ) {}

    static Optional<Plan> nextPlan(
            LoopState state,
            List<ToolSpec> baseTools,
            String userTask
    ) {
        Optional<ExpectedTargetRepair> repair = nextExpectedTargetScopeRepair(state);
        if (repair.isEmpty()) return Optional.empty();
        ExpectedTargetRepair expectedTargetRepair = repair.get();
        String key = expectedTargetRepairKey(expectedTargetRepair);
        ChatMessage.NativeToolCall exactReplacementRepair =
                exactExpectedTargetReplacementRepairCall(expectedTargetRepair);
        return Optional.of(new Plan(
                expectedTargetRepair.expectedTargets(),
                expectedTargetRepair.failedTarget(),
                key,
                expectedTargetRepairMessages(expectedTargetRepair, userTask),
                repairToolSpecs(baseTools),
                repairControls(state, baseTools),
                "expected-target scope compact repair",
                exactReplacementRepair,
                "expected-target-scope exact replacement target="
                        + expectedTargetRepair.expectedTargets().getFirst()
                        + " after wrong-target block=" + expectedTargetRepair.failedTarget()));
    }

    private static Optional<ExpectedTargetRepair> nextExpectedTargetScopeRepair(LoopState state) {
        if (state == null || state.toolOutcomes == null || state.toolOutcomes.isEmpty()) {
            return Optional.empty();
        }
        String failureReason = state.failureDecision == null ? "" : state.failureDecision.reason();
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        List<String> remainingExpectedTargets = expectedMutationTargetsForScopeRepair(state);
        if (remainingExpectedTargets.isEmpty() && looksLikeExpectedTargetScopeFailure(failureReason)) {
            remainingExpectedTargets = expectedTargetsFromScopeFailureReason(failureReason);
        }
        if (remainingExpectedTargets.isEmpty()) return Optional.empty();
        for (int i = state.toolOutcomes.size() - 1; i >= 0; i--) {
            ToolCallLoop.ToolOutcome outcome = state.toolOutcomes.get(i);
            if (outcome == null || !outcome.expectedTargetScopeFailure()) continue;
            String failedTarget = ToolCallSupport.normalizePath(outcome.pathHint());
            if (failedTarget.isBlank()) failedTarget = "(unknown)";
            ExpectedTargetRepair repair = expectedTargetRepair(
                    remainingExpectedTargets,
                    failedTarget,
                    outcome.errorMessage(),
                    contract,
                    state);
            if (repair == null) continue;
            if (state.expectedTargetScopeRepairPromptedKeys.contains(expectedTargetRepairKey(repair))) {
                continue;
            }
            return Optional.of(repair);
        }
        if (looksLikeExpectedTargetScopeFailure(failureReason)) {
            String failedTarget = firstBacktickValue(failureReason);
            if (failedTarget.isBlank()) failedTarget = "(unknown)";
            ExpectedTargetRepair repair = expectedTargetRepair(
                    remainingExpectedTargets,
                    failedTarget,
                    failureReason,
                    contract,
                    state);
            if (repair != null
                    && !state.expectedTargetScopeRepairPromptedKeys.contains(expectedTargetRepairKey(repair))) {
                return Optional.of(repair);
            }
        }
        return Optional.empty();
    }

    private static List<String> expectedTargetsFromScopeFailureReason(String reason) {
        if (reason == null || reason.isBlank()) return List.of();
        String marker = "current expected target set:";
        String lower = reason.toLowerCase(Locale.ROOT);
        int start = lower.indexOf(marker);
        if (start < 0) return List.of();
        String tail = reason.substring(start + marker.length()).strip();
        int end = tail.indexOf(". Similar filenames");
        if (end >= 0) {
            tail = tail.substring(0, end);
        } else {
            int period = tail.indexOf('.');
            if (period >= 0) tail = tail.substring(0, period);
        }
        if (tail.isBlank()) return List.of();
        return java.util.Arrays.stream(tail.split(","))
                .map(ToolCallSupport::normalizePath)
                .filter(path -> !path.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    // T758 residual: this consumes FailureDecision.reason() — free text from
    // the loop's failure summary, not a ToolOutcome — and the surrounding code
    // additionally EXTRACTS data from it (expected-target list, backticked
    // failed target). Migrating it needs FailureDecision to carry structured
    // targets (Wave-5 typed OutcomeSignals), not just a reason code. Until
    // then this phrase is load-bearing: producers of the expected-target
    // scope message must not rephrase it.
    private static boolean looksLikeExpectedTargetScopeFailure(String reason) {
        return reason != null
                && reason.toLowerCase(Locale.ROOT)
                .contains("target outside expected targets before approval");
    }

    private static String firstBacktickValue(String value) {
        if (value == null || value.isBlank()) return "";
        int start = value.indexOf('`');
        if (start < 0) return "";
        int end = value.indexOf('`', start + 1);
        if (end <= start) return "";
        return ToolCallSupport.normalizePath(value.substring(start + 1, end));
    }

    private static List<String> expectedMutationTargetsForScopeRepair(LoopState state) {
        if (state == null || state.messages == null) return List.of();
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        if (contract == null || !contract.mutationAllowed()) return List.of();
        Set<String> expectedTargets = contract.expectedTargets().isEmpty()
                ? TaskContractResolver.extractExpectedTargets(ToolCallSupport.latestUserRequestIn(state.messages))
                : contract.expectedTargets();
        if (expectedTargets == null || expectedTargets.isEmpty()) return List.of();
        Set<String> successfullyMutated = new java.util.HashSet<>();
        for (ToolCallLoop.ToolOutcome outcome : state.toolOutcomes) {
            if (outcome == null || !outcome.success() || !outcome.mutating()) continue;
            addSatisfiedExpectedTargetKeys(successfullyMutated, outcome);
        }
        return expectedTargets.stream()
                .map(ToolCallSupport::normalizePath)
                .filter(path -> !path.isBlank())
                .distinct()
                .filter(path -> !successfullyMutated.contains(normalizeExpectedTargetKey(path)))
                .sorted()
                .toList();
    }

    private static ExpectedTargetRepair expectedTargetRepair(
            List<String> expectedTargets,
            String failedTarget,
            String reason,
            TaskContract contract,
            LoopState state
    ) {
        if (expectedTargets == null || expectedTargets.isEmpty() || state == null) return null;
        StringBuilder readbacks = new StringBuilder();
        for (String target : expectedTargets) {
            String path = ToolCallSupport.normalizePath(target);
            if (path.isBlank() || isSensitiveReadbackPath(path)) continue;
            if (!TargetReadbackCompactRepairPlanner.successfulReadbackForPath(state, path)) continue;
            String readback = TargetReadbackCompactRepairPlanner.latestSuccessfulReadbackForPath(state, path);
            if (readback == null || readback.isBlank()) continue;
            readbacks.append("Current readback for ")
                    .append(path)
                    .append(":\n")
                    .append(truncateForCompactRepair(readback))
                    .append("\n---\n");
        }
        appendSuccessfulStaticWebMutationReadbacks(state, readbacks);
        if (readbacks.isEmpty()) {
            if (expectedTargets.stream().noneMatch(StaticWebCapabilityProfile::isSmallWebFile)) {
                return null;
            }
            if (state.mutatingToolSuccesses <= 0 && !looksDirectoryLikeFailedTarget(failedTarget)) {
                return null;
            }
            readbacks.append("No current expected-target readback exists yet. ")
                    .append("Create the missing expected target file(s) from the current user request; ")
                    .append("do not create or mutate the failed attempted target unless it is explicitly listed as expected.");
        }
        List<String> normalizedTargets = expectedTargets.stream()
                .map(ToolCallSupport::normalizePath)
                .filter(path -> !path.isBlank())
                .distinct()
                .sorted()
                .toList();
        ReplacementExpectation replacement = replacementExpectationForTargets(contract, normalizedTargets);
        return new ExpectedTargetRepair(
                normalizedTargets,
                failedTarget,
                reason,
                readbacks.toString().strip(),
                replacement == null ? "" : replacement.oldText(),
                replacement == null ? "" : replacement.newText());
    }

    private static void appendSuccessfulStaticWebMutationReadbacks(
            LoopState state,
            StringBuilder readbacks
    ) {
        if (state == null || state.workspace == null || state.toolOutcomes == null || readbacks == null) return;
        Path root = state.workspace.toAbsolutePath().normalize();
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (ToolCallLoop.ToolOutcome outcome : state.toolOutcomes) {
            if (!StaticWebContinuationPlanner.mutatedSmallWebFile(outcome)) continue;
            addSmallWebReadbackPath(paths, outcome.pathHint());
            WorkspaceOperationPlan plan = outcome.workspaceOperationPlan();
            if (plan == null) continue;
            for (WorkspaceOperationPlan.PathEffect effect : plan.pathEffects()) {
                if (effect != null) {
                    addSmallWebReadbackPath(paths, effect.path());
                }
            }
        }
        for (String path : paths) {
            if (isSensitiveReadbackPath(path)) continue;
            try {
                Path resolved = root.resolve(path).toAbsolutePath().normalize();
                if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) continue;
                String content = Files.readString(resolved);
                if (content.isBlank()) continue;
                readbacks.append("Current generated static web file ")
                        .append(path)
                        .append(":\n")
                        .append(truncateForCompactRepair(content))
                        .append("\n---\n");
            } catch (Exception ignored) {
                // The compact repair can still proceed from the expected target frame.
            }
        }
    }

    private static void addSmallWebReadbackPath(Set<String> paths, String path) {
        if (paths == null || path == null || path.isBlank()) return;
        String normalized = ToolCallSupport.normalizePath(path);
        if (normalized.isBlank() || !StaticWebCapabilityProfile.isSmallWebFile(normalized)) return;
        paths.add(normalized);
    }

    private static ReplacementExpectation replacementExpectationForTargets(
            TaskContract contract,
            List<String> targets
    ) {
        if (contract == null || targets == null || targets.size() != 1) return null;
        String target = targets.getFirst();
        for (var expectation : TaskExpectationResolver.resolve(contract)) {
            if (expectation instanceof ReplacementExpectation replacement
                    && ToolCallSupport.normalizePath(replacement.targetPath()).equals(target)) {
                return replacement;
            }
        }
        return null;
    }

    private static boolean looksDirectoryLikeFailedTarget(String failedTarget) {
        if (failedTarget == null || failedTarget.isBlank()) return false;
        String normalized = ToolCallSupport.normalizePath(failedTarget).toLowerCase(Locale.ROOT);
        if (normalized.endsWith("/")) return true;
        int slash = normalized.lastIndexOf('/');
        String last = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return !last.contains(".");
    }

    private static String expectedTargetRepairKey(ExpectedTargetRepair repair) {
        if (repair == null) return "";
        return ToolCallSupport.normalizePath(repair.failedTarget())
                + "->"
                + String.join(",", repair.expectedTargets());
    }

    private static ChatMessage.NativeToolCall exactExpectedTargetReplacementRepairCall(
            ExpectedTargetRepair repair
    ) {
        if (repair == null || repair.expectedTargets().size() != 1) return null;
        if (repair.replacementOldText().isBlank() || repair.replacementNewText().isBlank()) {
            return null;
        }
        return new ChatMessage.NativeToolCall(
                "runtime_expected_target_repair",
                "talos.edit_file",
                Map.of(
                        "path", repair.expectedTargets().getFirst(),
                        "old_string", repair.replacementOldText(),
                        "new_string", repair.replacementNewText()));
    }

    private static List<ToolSpec> repairToolSpecs(List<ToolSpec> baseTools) {
        List<ToolSpec> base = baseTools == null ? List.of() : baseTools;
        List<ToolSpec> narrowed = filterTools(base, List.of("talos.edit_file", "talos.write_file"));
        return narrowed.isEmpty() ? base : narrowed;
    }

    private static ChatRequestControls repairControls(LoopState state, List<ToolSpec> tools) {
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
                List.of("pending-action-obligation", "expected-target-scope-compact-repair"));
    }

    private static List<ChatMessage> expectedTargetRepairMessages(
            ExpectedTargetRepair repair,
            String userTask
    ) {
        String currentTask = userTask == null || userTask.isBlank()
                ? "Apply the requested file change to the expected target."
                : userTask.strip();
        return List.of(
                ChatMessage.system("""
                        You are Talos, a local-first workspace assistant.
                        This is a compact target-only repair after a mutation was blocked before approval because it targeted a file outside the expected target set.
                        Use the provided expected-target frame as the only file-content source.
                        If the frame says no current readback exists, create the missing expected file(s) from the current user request.
                        Only mutate the expected target path(s). Do not mutate the failed attempted target unless it is also explicitly listed as expected.
                        Do not put required root files inside css/, js/, assets/, site/, or other subdirectories unless the expected target path explicitly includes that directory.
                        Do not answer in prose instead of calling a write/edit tool.
                        """),
                ChatMessage.system(
                        "[ExpectedTargetRepair]\n"
                                + "Expected target(s): " + String.join(", ", repair.expectedTargets()) + "\n"
                                + "Failed attempted target: " + repair.failedTarget() + "\n"
                                + expectedTargetRepairReplacementFrame(repair)
                                + "Failed reason: " + safeExpectedTargetRepairReason(repair.reason()) + "\n"
                                + "Only mutate the expected target path(s). Ignore stale prior history outside this compact repair frame."),
                ChatMessage.user(
                        "Current user request:\n"
                                + currentTask
                                + "\n\n"
                                + repair.readbackFrame()
                                + "\n\nCall talos.write_file or talos.edit_file for the expected target now."));
    }

    private static String expectedTargetRepairReplacementFrame(ExpectedTargetRepair repair) {
        if (repair == null || repair.replacementOldText().isBlank() || repair.replacementNewText().isBlank()) {
            return "";
        }
        return "Exact replacement: old_string=`" + repair.replacementOldText()
                + "` new_string=`" + repair.replacementNewText() + "`\n";
    }

    private static String safeExpectedTargetRepairReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "mutation targeted a file outside the expected target set";
        }
        return reason.strip();
    }

    private static boolean isSensitiveReadbackPath(String path) {
        // T759: delegates to the single canonical classifier (fail-closed:
        // blank/unnormalizable -> sensitive). Four identical planner-local
        // copies were removed.
        return dev.talos.safety.ProtectedPathTokens.isSensitiveReadbackPath(path);
    }

    private static String truncateForCompactRepair(String readback) {
        if (readback == null || readback.length() <= COMPACT_READBACK_REPAIR_MAX_CHARS) {
            return readback;
        }
        return readback.substring(0, COMPACT_READBACK_REPAIR_MAX_CHARS)
                + "\n... [readback truncated for compact old-string repair]";
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

    private static String normalizeExpectedTargetKey(String path) {
        return ToolCallSupport.normalizePath(path).toLowerCase(Locale.ROOT);
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
