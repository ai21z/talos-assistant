package dev.talos.runtime.policy;

import dev.talos.runtime.context.ProjectMemoryContext;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.repair.RepairPolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.toolcall.ToolSurfacePlanner;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Mutates turn prompt messages with runtime-owned current-turn instruction frames. */
public final class CurrentTurnPromptInstructions {

    private CurrentTurnPromptInstructions() {}

    public static void injectProjectMemoryInstruction(
            List<ChatMessage> messages,
            ProjectMemoryContext projectMemory
    ) {
        if (messages == null || messages.isEmpty() || projectMemory == null) return;
        messages.removeIf(CurrentTurnPromptInstructions::isProjectMemoryInstruction);
        String rendered = projectMemory.renderForPrompt();
        if (rendered.isBlank()) return;

        int insertAt = 0;
        for (int i = 0; i < messages.size(); i++) {
            if ("system".equals(messages.get(i).role())) {
                insertAt = i + 1;
                break;
            }
        }
        messages.add(insertAt, ChatMessage.system(rendered));
    }

    public static void injectTaskContractInstruction(List<ChatMessage> messages) {
        TaskContract contract = TaskContractResolver.fromMessages(messages);
        ExecutionPhase phase = CurrentTurnPlan.defaultPhaseFor(contract);
        List<String> visibleTools = defaultVisibleToolNames(contract, phase);
        injectTaskContractInstruction(messages, CurrentTurnPlan.compatibility(
                contract, phase, visibleTools, visibleTools, List.of()));
    }

    public static void injectTaskContractInstruction(List<ChatMessage> messages, CurrentTurnPlan plan) {
        injectTaskContractInstruction(messages, plan, false);
    }

    public static void replaceTaskContractInstruction(List<ChatMessage> messages, CurrentTurnPlan plan) {
        injectTaskContractInstruction(messages, plan, true);
    }

    public static void injectTaskContractInstruction(
            List<ChatMessage> messages,
            TaskContract contract,
            ExecutionPhase phase,
            List<String> visibleTools
    ) {
        TaskContract safeContract = contract == null ? TaskContractResolver.fromMessages(messages) : contract;
        ExecutionPhase safePhase = phase == null ? CurrentTurnPlan.defaultPhaseFor(safeContract) : phase;
        injectTaskContractInstruction(messages, CurrentTurnPlan.compatibility(
                safeContract, safePhase, visibleTools, visibleTools, List.of()));
    }

    private static void injectTaskContractInstruction(
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            boolean replaceExisting
    ) {
        if (messages == null || messages.isEmpty()) return;
        if (replaceExisting) {
            messages.removeIf(CurrentTurnPromptInstructions::isTaskContractInstruction);
        } else if (messages.stream().anyMatch(CurrentTurnPromptInstructions::isTaskContractInstruction)) {
            return;
        }

        if (plan == null) {
            injectTaskContractInstruction(messages);
            return;
        }

        String instruction = CurrentTurnCapabilityFrame.render(plan);
        injectTaskContractInstruction(messages, instruction, replaceExisting);
    }

    private static void injectTaskContractInstruction(
            List<ChatMessage> messages,
            String instruction,
            boolean replaceExisting
    ) {
        if (messages == null || messages.isEmpty()) return;
        if (replaceExisting) {
            messages.removeIf(CurrentTurnPromptInstructions::isTaskContractInstruction);
        } else if (messages.stream().anyMatch(CurrentTurnPromptInstructions::isTaskContractInstruction)) {
            return;
        }

        int insertAt = messages.size();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                insertAt = i;
                break;
            }
        }
        if (insertAt == messages.size()) {
            insertAt = 0;
            for (int i = 0; i < messages.size(); i++) {
                if ("system".equals(messages.get(i).role())) {
                    insertAt = i + 1;
                    break;
                }
            }
        }
        messages.add(insertAt, ChatMessage.system(instruction));
    }

    private static List<String> defaultVisibleToolNames(TaskContract contract, ExecutionPhase phase) {
        return ToolSurfacePlanner.defaultVisibleToolNames(contract, phase);
    }

    public static void injectStaticVerificationRepairInstruction(
            List<ChatMessage> messages,
            TaskContract taskContract
    ) {
        injectStaticVerificationRepairInstruction(messages, taskContract, null);
    }

    public static void injectStaticVerificationRepairInstruction(
            List<ChatMessage> messages,
            TaskContract taskContract,
            Path workspace
    ) {
        if (messages == null || messages.isEmpty()) return;
        removeSupersededStaticVerificationRepairInstructions(messages, taskContract);
        if (messages.stream().anyMatch(CurrentTurnPromptInstructions::isStaticVerificationRepairInstruction)) {
            return;
        }
        var repairDecision = RepairPolicy.planForStaticVerification(messages, taskContract);
        repairDecision
                .plan()
                .ifPresentOrElse(plan -> {
                    String instruction = enrichStaticVerificationRepairInstruction(plan.instruction(), workspace);
                    if (instruction.isBlank()) return;
                    LocalTurnTraceCapture.recordRepair("PLANNED", plan.traceSummary());
                    int insertAt = 0;
                    for (int i = 0; i < messages.size(); i++) {
                        ChatMessage message = messages.get(i);
                        if ("system".equals(message.role())) {
                            insertAt = i + 1;
                            if (isTaskContractInstruction(message)) {
                                break;
                            }
                        }
                    }
                    messages.add(insertAt, ChatMessage.system(instruction));
                }, () -> {
                    if (repairDecision.reason().contains("targets did not overlap")) {
                        LocalTurnTraceCapture.recordRepair("SKIPPED", repairDecision.reason());
                    }
                });
    }

    private static String enrichStaticVerificationRepairInstruction(String instruction, Path workspace) {
        return RepairPolicy.enrichSelectorFactsForRepairContext(instruction, workspace);
    }

    private static void removeSupersededStaticVerificationRepairInstructions(
            List<ChatMessage> messages,
            TaskContract taskContract
    ) {
        if (messages == null || messages.isEmpty()
                || taskContract == null
                || !taskContract.mutationAllowed()
                || taskContract.expectedTargets().isEmpty()) {
            return;
        }
        Set<String> currentTargets = normalizedTargets(taskContract.expectedTargets());
        if (currentTargets.isEmpty()) return;

        List<String> removedTargets = new ArrayList<>();
        messages.removeIf(message -> {
            if (!isStaticVerificationRepairInstruction(message)) return false;
            Set<String> repairTargets = RepairPolicy.fullRewriteTargetsFromRepairContext(List.of(message));
            if (repairTargets.isEmpty() || targetsOverlap(currentTargets, repairTargets)) {
                return false;
            }
            removedTargets.addAll(repairTargets.stream().sorted().toList());
            return true;
        });
        if (!removedTargets.isEmpty()) {
            LocalTurnTraceCapture.recordRepair(
                    "SUPERSEDED",
                    "stale static repair context skipped: targets did not overlap with current task targets; "
                            + "current targets: " + String.join(", ", currentTargets.stream().sorted().toList())
                            + "; stale repair targets: " + String.join(", ", removedTargets.stream().sorted().toList()));
        }
    }

    private static Set<String> normalizedTargets(Set<String> targets) {
        Set<String> out = new LinkedHashSet<>();
        for (String target : targets == null ? Set.<String>of() : targets) {
            String normalized = normalizeTargetForRepairScope(target);
            if (!normalized.isBlank()) out.add(normalized);
        }
        return Set.copyOf(out);
    }

    private static boolean targetsOverlap(Set<String> leftTargets, Set<String> rightTargets) {
        Set<String> left = normalizedTargets(leftTargets);
        Set<String> right = normalizedTargets(rightTargets);
        for (String target : left) {
            if (right.contains(target)) return true;
        }
        return false;
    }

    private static String normalizeTargetForRepairScope(String raw) {
        if (raw == null) return "";
        String normalized = raw.strip()
                .replace('\\', '/')
                .replaceAll("^[`'\"(\\[]+", "")
                .replaceAll("[`'\"),.;:!?\\]]+$", "");
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static boolean isTaskContractInstruction(ChatMessage message) {
        return message != null
                && "system".equals(message.role())
                && message.content() != null
                && (message.content().startsWith("[TaskContract]")
                || message.content().startsWith("[CurrentTurnCapability]"));
    }

    private static boolean isProjectMemoryInstruction(ChatMessage message) {
        return message != null
                && "system".equals(message.role())
                && message.content() != null
                && message.content().startsWith("[ProjectMemory]");
    }

    private static boolean isStaticVerificationRepairInstruction(ChatMessage message) {
        return message != null
                && "system".equals(message.role())
                && message.content() != null
                && message.content().startsWith("[Static verification repair context]");
    }
}
