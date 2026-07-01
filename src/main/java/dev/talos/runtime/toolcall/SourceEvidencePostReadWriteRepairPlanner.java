package dev.talos.runtime.toolcall;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.SamplingControls;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

final class SourceEvidencePostReadWriteRepairPlanner {
    private static final int SOURCE_READBACK_MAX_CHARS = 4_000;

    private SourceEvidencePostReadWriteRepairPlanner() {
    }

    record Plan(
            List<String> remainingTargets,
            String key,
            List<SourceDerivedEvidenceGuard.SourceReadback> sourceReadbacks,
            List<ChatMessage> messages,
            List<ToolSpec> tools,
            ChatRequestControls controls
    ) {
    }

    static Optional<Plan> nextPlan(
            LoopState state,
            List<ToolSpec> baseTools,
            String userTask
    ) {
        if (state == null || state.toolOutcomes.isEmpty()) {
            return Optional.empty();
        }
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        if (contract == null || contract.sourceEvidenceTargets().isEmpty()) {
            return Optional.empty();
        }
        if (!SourceDerivedEvidenceGuard.missingSourceEvidenceTargets(state, contract).isEmpty()) {
            return Optional.empty();
        }
        if (!hasSourceEvidenceWriteBeforeReadFailure(state)) {
            return Optional.empty();
        }
        List<SourceDerivedEvidenceGuard.SourceReadback> sourceReadbacks =
                SourceDerivedEvidenceGuard.sourceReadbacks(state, contract);
        if (sourceReadbacks.isEmpty()) {
            return Optional.empty();
        }
        List<String> remainingTargets = ExpectedTargetProgressAccounting.remainingExpectedMutationTargets(state);
        if (remainingTargets.isEmpty()) {
            return Optional.empty();
        }
        String key = repairKey(remainingTargets, sourceReadbacks);
        if (state.sourceEvidencePostReadWriteRepairPromptedKeys.contains(key)) {
            return Optional.empty();
        }
        List<ToolSpec> tools = repairToolSpecs(baseTools, remainingTargets);
        if (tools.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Plan(
                List.copyOf(remainingTargets),
                key,
                List.copyOf(sourceReadbacks),
                repairMessages(remainingTargets, sourceReadbacks, userTask),
                tools,
                repairControls(state, tools)));
    }

    private static boolean hasSourceEvidenceWriteBeforeReadFailure(LoopState state) {
        for (var outcome : state.toolOutcomes) {
            if (outcome == null || outcome.success() || !outcome.mutating()) continue;
            String reason = outcome.errorMessage() == null ? "" : outcome.errorMessage();
            if (reason.contains("Source-derived artifact write blocked before approval")
                    && reason.contains("requires reading source target(s)")
                    && reason.contains("Call talos.read_file")) {
                return true;
            }
        }
        return false;
    }

    private static String repairKey(
            List<String> remainingTargets,
            List<SourceDerivedEvidenceGuard.SourceReadback> sourceReadbacks
    ) {
        String targets = remainingTargets.stream()
                .map(ToolCallSupport::normalizePath)
                .collect(Collectors.joining(","));
        String sources = sourceReadbacks.stream()
                .map(SourceDerivedEvidenceGuard.SourceReadback::path)
                .collect(Collectors.joining(","));
        return targets + "->" + sources;
    }

    private static List<ToolSpec> repairToolSpecs(List<ToolSpec> baseTools, List<String> remainingTargets) {
        List<ToolSpec> base = baseTools == null ? List.of() : baseTools;
        List<ToolSpec> writeTools = base.stream()
                .filter(spec -> spec != null && "talos.write_file".equals(spec.name()))
                .toList();
        if (writeTools.isEmpty()) return List.of();
        String enumValues = remainingTargets.stream()
                .map(ToolCallSupport::normalizePath)
                .filter(path -> !path.isBlank())
                .map(path -> "\"" + jsonEscape(path) + "\"")
                .collect(Collectors.joining(","));
        return writeTools.stream()
                .map(spec -> new ToolSpec(
                        "talos.write_file",
                        "Write a complete source-derived output file for one of the remaining expected targets.",
                        "{\"type\":\"object\",\"properties\":{"
                                + "\"path\":{\"type\":\"string\",\"enum\":[" + enumValues + "]},"
                                + "\"content\":{\"type\":\"string\",\"description\":\"Complete file content derived "
                                + "from the source readback. Do not use placeholders.\"}"
                                + "},\"required\":[\"path\",\"content\"]}"))
                .toList();
    }

    private static List<ChatMessage> repairMessages(
            List<String> remainingTargets,
            List<SourceDerivedEvidenceGuard.SourceReadback> sourceReadbacks,
            String userTask
    ) {
        String targets = String.join(", ", remainingTargets);
        String currentTask = userTask == null || userTask.isBlank()
                ? "Create the requested source-derived output."
                : userTask.strip();
        StringBuilder frame = new StringBuilder();
        frame.append("[SourceEvidencePostReadWriteRepair]\n")
                .append("The required source file(s) have now been read after an earlier write-before-read block.\n")
                .append("Remaining expected target(s): ").append(targets).append('\n')
                .append("Write complete content for these exact target path(s). Similar filenames are not substitutes.\n")
                .append("Do not output placeholders. Do not inspect unrelated files.\n\n")
                .append("Source readbacks:\n");
        for (SourceDerivedEvidenceGuard.SourceReadback sourceReadback : sourceReadbacks) {
            frame.append("Path: ").append(sourceReadback.path()).append('\n')
                    .append(truncateReadback(sourceReadback.readback()))
                    .append("\n---\n");
        }
        return List.of(
                ChatMessage.system("""
                        You are Talos, a local-first workspace assistant.
                        This is a source-evidence post-read mutation continuation.
                        Call talos.write_file now for the remaining expected target path(s).
                        Use the provided source readback as the source of truth.
                        Do not answer in prose unless the mutation tool call is impossible.
                        """),
                ChatMessage.system(frame.toString()),
                ChatMessage.user("Current user request:\n"
                        + currentTask
                        + "\n\nWrite the remaining expected target(s) now: "
                        + targets));
    }

    private static ChatRequestControls repairControls(LoopState state, List<ToolSpec> tools) {
        if (state == null
                || state.ctx == null
                || state.ctx.llm() == null
                || !state.ctx.llm().supportsRequiredToolChoice()
                || !hasWriteTool(tools)) {
            return ChatRequestControls.defaults();
        }
        // The repair frame narrows the surface to talos.write_file and names it
        // explicitly; NAMED pins the call for 14B-class models (T741).
        boolean named = state.ctx.llm().supportsNamedToolChoice();
        return new ChatRequestControls(
                named ? ToolChoiceMode.NAMED : ToolChoiceMode.REQUIRED,
                named ? "talos.write_file" : "",
                ResponseFormatMode.TEXT,
                "",
                List.of("pending-action-obligation", "source-evidence-post-read-write-repair"),
                SamplingControls.NEAR_GREEDY);
    }

    private static boolean hasWriteTool(List<ToolSpec> tools) {
        if (tools == null || tools.isEmpty()) return false;
        for (ToolSpec tool : tools) {
            if (tool != null && "talos.write_file".equals(tool.name())) return true;
        }
        return false;
    }

    private static String truncateReadback(String readback) {
        if (readback == null || readback.length() <= SOURCE_READBACK_MAX_CHARS) {
            return readback == null ? "" : readback;
        }
        return readback.substring(0, SOURCE_READBACK_MAX_CHARS)
                + "\n... [readback truncated for source-evidence post-read mutation continuation]";
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
}
