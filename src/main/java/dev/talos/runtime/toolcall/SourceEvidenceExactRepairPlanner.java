package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.repair.RepairPolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

final class SourceEvidenceExactRepairPlanner {
    private static final int SOURCE_EVIDENCE_READBACK_MAX_CHARS = 4_000;

    private SourceEvidenceExactRepairPlanner() {}

    record Plan(
            String path,
            String key,
            List<SourceDerivedEvidenceGuard.SourceReadback> sourceReadbacks,
            List<ChatMessage> messages,
            List<ToolSpec> tools,
            ChatRequestControls controls
    ) {}

    static Optional<Plan> nextPlan(
            LoopState state,
            List<ToolSpec> baseTools,
            String userTask
    ) {
        if (state == null || state.toolOutcomes == null || state.toolOutcomes.isEmpty()) {
            return Optional.empty();
        }
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        if (contract == null || contract.sourceEvidenceTargets().isEmpty()) return Optional.empty();
        List<SourceDerivedEvidenceGuard.SourceReadback> sourceReadbacks =
                SourceDerivedEvidenceGuard.sourceReadbacks(state, contract);
        if (sourceReadbacks.isEmpty()) return Optional.empty();

        List<String> remainingExpectedTargets = remainingExpectedMutationTargets(state);
        if (remainingExpectedTargets.isEmpty()) return Optional.empty();
        Set<String> remaining = remainingExpectedTargets.stream()
                .map(SourceEvidenceExactRepairPlanner::normalizeExpectedTargetKey)
                .collect(Collectors.toSet());
        for (int i = state.toolOutcomes.size() - 1; i >= 0; i--) {
            ToolCallLoop.ToolOutcome outcome = state.toolOutcomes.get(i);
            if (outcome == null || !outcome.mutating() || outcome.success()) continue;
            String reason = outcome.errorMessage() == null ? "" : outcome.errorMessage();
            if (!reason.contains("Source-derived write blocked before approval")) continue;
            String pathKey = normalizeExpectedTargetKey(outcome.pathHint());
            if (pathKey.isBlank() || !remaining.contains(pathKey)) continue;
            String path = displayExpectedTargetForKey(remainingExpectedTargets, pathKey);
            if (path.isBlank()) {
                path = ToolCallSupport.normalizePath(outcome.pathHint());
            }
            String key = repairKey(path, sourceReadbacks);
            if (state.sourceEvidenceExactRepairPromptedKeys.contains(key)) {
                continue;
            }
            List<ToolSpec> tools = repairToolSpecs(baseTools, path, sourceReadbacks);
            List<ChatMessage> messages = repairMessages(path, reason, sourceReadbacks, userTask);
            ChatRequestControls controls = repairControls(state, baseTools);
            return Optional.of(new Plan(path, key, sourceReadbacks, messages, tools, controls));
        }
        return Optional.empty();
    }

    private static String repairKey(
            String path,
            List<SourceDerivedEvidenceGuard.SourceReadback> sourceReadbacks
    ) {
        return ToolCallSupport.normalizePath(path)
                + "->"
                + sourceReadbacks.stream()
                .map(SourceDerivedEvidenceGuard.SourceReadback::path)
                .collect(Collectors.joining(","));
    }

    private static List<ToolSpec> repairToolSpecs(
            List<ToolSpec> baseTools,
            String path,
            List<SourceDerivedEvidenceGuard.SourceReadback> sourceReadbacks
    ) {
        List<ToolSpec> base = baseTools == null ? List.of() : baseTools;
        List<ToolSpec> narrowed = filterTools(base, List.of("talos.write_file"));
        if (narrowed.isEmpty()) return fallbackRepairToolSpecs(base);
        String target = ToolCallSupport.normalizePath(path);
        String snippets = sourceReadbacks == null
                ? ""
                : sourceReadbacks.stream()
                .map(sourceReadback -> SourceDerivedEvidenceGuard.evidenceSnippet(sourceReadback.readback()))
                .filter(snippet -> snippet != null && !snippet.isBlank())
                .collect(Collectors.joining("; "));
        return narrowed.stream()
                .map(spec -> {
                    if (spec == null || !"talos.write_file".equals(spec.name())) return spec;
                    String schema = "{\"type\":\"object\",\"properties\":{"
                            + "\"path\":{\"type\":\"string\",\"enum\":[\"" + jsonEscape(target) + "\"]},"
                            + "\"content\":{\"type\":\"string\",\"description\":\"Complete content for "
                            + jsonEscape(target)
                            + ". Must include these exact source evidence phrases verbatim: "
                            + jsonEscape(snippets)
                            + "\"}},\"required\":[\"path\",\"content\"]}";
                    return new ToolSpec(
                            "talos.write_file",
                            "Write the complete repaired source-derived output to " + target
                                    + " only, including the required exact source evidence phrases.",
                            schema);
                })
                .toList();
    }

    private static List<ToolSpec> fallbackRepairToolSpecs(List<ToolSpec> baseTools) {
        List<ToolSpec> narrowed = filterTools(baseTools, List.of("talos.edit_file", "talos.write_file"));
        return narrowed.isEmpty() ? baseTools : narrowed;
    }

    private static List<ChatMessage> repairMessages(
            String path,
            String reason,
            List<SourceDerivedEvidenceGuard.SourceReadback> sourceReadbacks,
            String userTask
    ) {
        String currentTask = userTask == null || userTask.isBlank()
                ? "Create the requested source-derived output."
                : userTask.strip();
        StringBuilder frame = new StringBuilder();
        frame.append("[SourceEvidenceExactRepair] Target: ").append(path).append('\n')
                .append("Previous write was rejected before approval because it omitted exact source evidence. ")
                .append("No file was changed by the rejected write.\n")
                .append("Failed reason: ").append(safeRepairReason(reason)).append('\n')
                .append("Only mutate this target. Ignore stale prior history outside this compact repair frame.\n\n")
                .append("Required exact source evidence phrases:\n");
        for (SourceDerivedEvidenceGuard.SourceReadback sourceReadback : sourceReadbacks) {
            String snippet = SourceDerivedEvidenceGuard.evidenceSnippet(sourceReadback.readback());
            if (snippet.isBlank()) continue;
            frame.append("- ").append(sourceReadback.path())
                    .append(": `")
                    .append(snippet)
                    .append("`\n");
        }
        frame.append("\nSource readbacks:\n");
        for (SourceDerivedEvidenceGuard.SourceReadback sourceReadback : sourceReadbacks) {
            frame.append("Path: ").append(sourceReadback.path()).append('\n')
                    .append(truncateSourceEvidenceReadback(sourceReadback.readback()))
                    .append("\n---\n");
        }
        return List.of(
                ChatMessage.system("""
                        You are Talos, a local-first workspace assistant.
                        This is a compact source-evidence repair after a source-derived write was blocked before approval.
                        Call a file mutation tool now; do not inspect more files and do not answer in prose.
                        The replacement content must include at least one required exact source evidence phrase for every listed source.
                        Do not invent office facts that are not present in the source readbacks.
                        """),
                ChatMessage.system(frame.toString()),
                ChatMessage.user(
                        "Current user request:\n"
                                + currentTask
                                + "\n\nWrite " + path
                                + " now using talos.write_file or talos.edit_file. "
                                + "Include the required exact source evidence phrases verbatim."));
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
                List.of("pending-action-obligation", "source-evidence-exact-compact-repair"));
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

    private static String normalizeExpectedTargetKey(String path) {
        return ToolCallSupport.normalizePath(path).toLowerCase(Locale.ROOT);
    }

    private static String safeRepairReason(String reason) {
        if (reason == null || reason.isBlank()) return "old_string not found";
        return reason.strip();
    }

    private static String truncateSourceEvidenceReadback(String readback) {
        if (readback == null || readback.length() <= SOURCE_EVIDENCE_READBACK_MAX_CHARS) {
            return readback;
        }
        return readback.substring(0, SOURCE_EVIDENCE_READBACK_MAX_CHARS)
                + "\n... [readback truncated for compact mutation continuation]";
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    private static List<ToolSpec> filterTools(List<ToolSpec> specs, List<String> allowedNames) {
        if (specs == null || specs.isEmpty() || allowedNames == null || allowedNames.isEmpty()) return List.of();
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
