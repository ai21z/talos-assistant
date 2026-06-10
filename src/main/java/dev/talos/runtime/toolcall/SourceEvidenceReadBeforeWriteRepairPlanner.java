package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.SamplingControls;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;

import java.util.List;
import java.util.stream.Collectors;

final class SourceEvidenceReadBeforeWriteRepairPlanner {
    private SourceEvidenceReadBeforeWriteRepairPlanner() {
    }

    record Plan(
            List<String> missingSourceTargets,
            String key,
            List<ChatMessage> messages,
            List<ToolSpec> tools,
            ChatRequestControls controls
    ) {
    }

    static java.util.Optional<Plan> nextPlan(
            LoopState state,
            List<ToolSpec> baseTools,
            String userTask
    ) {
        if (state == null || state.toolOutcomes == null || state.toolOutcomes.isEmpty()) {
            return java.util.Optional.empty();
        }
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        if (contract == null || contract.sourceEvidenceTargets().isEmpty()) {
            return java.util.Optional.empty();
        }
        List<String> missingSources = SourceDerivedEvidenceGuard.missingSourceEvidenceTargets(state, contract);
        if (missingSources.isEmpty()) {
            return java.util.Optional.empty();
        }
        if (!hasSourceEvidenceWriteBeforeReadFailure(state)) {
            return java.util.Optional.empty();
        }
        String key = repairKey(missingSources);
        if (state.sourceEvidenceReadRepairPromptedKeys.contains(key)) {
            return java.util.Optional.empty();
        }
        List<ToolSpec> tools = repairToolSpecs(baseTools, missingSources);
        if (tools.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new Plan(
                List.copyOf(missingSources),
                key,
                repairMessages(missingSources, userTask),
                tools,
                repairControls(state, tools)));
    }

    private static boolean hasSourceEvidenceWriteBeforeReadFailure(LoopState state) {
        for (ToolCallLoop.ToolOutcome outcome : state.toolOutcomes) {
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

    private static String repairKey(List<String> missingSources) {
        return missingSources.stream()
                .map(ToolCallSupport::normalizePath)
                .filter(path -> path != null && !path.isBlank())
                .collect(Collectors.joining(","));
    }

    private static List<ToolSpec> repairToolSpecs(List<ToolSpec> baseTools, List<String> missingSources) {
        List<ToolSpec> base = baseTools == null ? List.of() : baseTools;
        List<ToolSpec> readTools = base.stream()
                .filter(spec -> spec != null && "talos.read_file".equals(spec.name()))
                .toList();
        if (readTools.isEmpty()) return List.of();
        String enumValues = missingSources.stream()
                .map(ToolCallSupport::normalizePath)
                .filter(path -> path != null && !path.isBlank())
                .map(path -> "\"" + jsonEscape(path) + "\"")
                .collect(Collectors.joining(","));
        return readTools.stream()
                .map(spec -> new ToolSpec(
                        "talos.read_file",
                        "Read the missing source evidence file before any derived write.",
                        "{\"type\":\"object\",\"properties\":{"
                                + "\"path\":{\"type\":\"string\",\"enum\":[" + enumValues + "]},"
                                + "\"max_lines\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":500}"
                                + "},\"required\":[\"path\"]}"))
                .toList();
    }

    private static List<ChatMessage> repairMessages(List<String> missingSources, String userTask) {
        String missing = String.join(", ", missingSources);
        String currentTask = userTask == null || userTask.isBlank()
                ? "Create the requested source-derived output."
                : userTask.strip();
        StringBuilder frame = new StringBuilder();
        frame.append("[SourceEvidenceReadBeforeWriteRepair]\n")
                .append("A source-derived write was blocked before approval because the source file(s) ")
                .append("were not read in this turn. No approval was requested and no file was changed.\n")
                .append("Missing source target(s): ").append(missing).append('\n')
                .append("Call talos.read_file for the missing source target(s) first. ")
                .append("Do not call write_file or edit_file until the source read succeeds.\n");
        return List.of(
                ChatMessage.system("""
                        You are Talos, a local-first workspace assistant.
                        This is a source-evidence read-before-write repair.
                        Call talos.read_file for the missing source target first; do not mutate files in this repair step.
                        """),
                ChatMessage.system(frame.toString()),
                ChatMessage.user("Current user request:\n" + currentTask
                        + "\n\nRead the missing source target(s) now: " + missing));
    }

    private static ChatRequestControls repairControls(LoopState state, List<ToolSpec> tools) {
        if (state == null
                || state.ctx == null
                || state.ctx.llm() == null
                || !state.ctx.llm().supportsRequiredToolChoice()
                || !hasReadTool(tools)) {
            return ChatRequestControls.defaults();
        }
        // The gate names the required tool explicitly; pinning it via NAMED
        // removes the wrong-tool substitution observed in the t325 bank
        // failure (model answered "call talos.read_file" with grep, 4x).
        boolean named = state.ctx.llm().supportsNamedToolChoice();
        return new ChatRequestControls(
                named ? ToolChoiceMode.NAMED : ToolChoiceMode.REQUIRED,
                named ? "talos.read_file" : "",
                ResponseFormatMode.TEXT,
                "",
                List.of("source-evidence-read-before-write-repair"),
                SamplingControls.NEAR_GREEDY);
    }

    private static boolean hasReadTool(List<ToolSpec> tools) {
        if (tools == null || tools.isEmpty()) return false;
        for (ToolSpec tool : tools) {
            if (tool != null && "talos.read_file".equals(tool.name())) return true;
        }
        return false;
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
