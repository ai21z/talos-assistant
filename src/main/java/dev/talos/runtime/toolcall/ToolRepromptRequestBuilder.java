package dev.talos.runtime.toolcall;

import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.repair.RepairPolicy;
import dev.talos.runtime.capability.StaticWebCapabilityProfile;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.ActionObligationPolicy;
import dev.talos.runtime.policy.ProviderPromptSurfaceReconciler;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.WorkspaceTargetReconciler;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class ToolRepromptRequestBuilder {
    private ToolRepromptRequestBuilder() {}

    static List<ToolSpec> toolSpecs(
            LoopState state,
            boolean staticRepairProgress,
            boolean expectedTargetProgress
    ) {
        List<ToolSpec> base = currentNativeToolSpecs(state);
        if (base == null || base.isEmpty()) return base;
        if (staticRepairProgress) {
            List<ToolSpec> narrowed = filterTools(base, List.of("talos.write_file"));
            return narrowed;
        }
        if (expectedTargetProgress) {
            List<String> allowed = staticWebExpectedTargetProgressPrefersWriteFile(state)
                    ? List.of("talos.write_file")
                    : List.of("talos.write_file", "talos.edit_file");
            List<ToolSpec> narrowed = filterTools(base, allowed);
            return narrowed;
        }
        return base;
    }

    private static boolean staticWebExpectedTargetProgressPrefersWriteFile(LoopState state) {
        if (state == null || state.messages == null) return false;
        TaskContract contract = WorkspaceTargetReconciler.reconcile(
                TurnTaskContractCapture.currentOrResolve(state.messages),
                state.workspace);
        return StaticWebCapabilityProfile.prefersFullFileWriteForInitialApply(contract);
    }

    static List<ChatMessage> messages(
            LoopState state,
            boolean staticRepairObligationActive,
            List<String> remainingRepairTargets,
            String userTask
    ) {
        return messages(
                state,
                staticRepairObligationActive,
                remainingRepairTargets,
                userTask,
                currentNativeToolSpecs(state));
    }

    static List<ChatMessage> messages(
            LoopState state,
            boolean staticRepairObligationActive,
            List<String> remainingRepairTargets,
            String userTask,
            List<ToolSpec> repromptToolSpecs
    ) {
        if (!staticRepairObligationActive) {
            if (state == null) return List.of();
            var resolution = TurnTaskContractCapture.getResolution();
            if (resolution == null) {
                throw new IllegalStateException(
                        "captured current-turn task resolution is required "
                                + "before provider reprompt assembly");
            }
            List<ToolSpec> selected = repromptToolSpecs == null
                    ? List.of()
                    : List.copyOf(repromptToolSpecs);
            ProviderResponseCapture.ToolAuthorityMode authorityMode =
                    state.ctx == null || state.ctx.llm() == null
                            ? ProviderResponseCapture.ToolAuthorityMode.NONE
                            : state.ctx.llm()
                                    .toolAuthorityModeForToolSurface(selected);
            List<String> names = selected.stream()
                    .map(ToolSpec::name)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            ExecutionPhase phase =
                    state.ctx != null
                                    && state.ctx.executionPhaseState() != null
                            ? state.ctx.executionPhaseState().phase()
                            : CurrentTurnPlan.defaultPhaseFor(
                                    resolution.contract());
            List<String> nativeNames =
                    authorityMode
                                    == ProviderResponseCapture
                                            .ToolAuthorityMode
                                            .PROVIDER_NATIVE
                            ? names
                            : List.of();
            CurrentTurnPlan plan =
                    CurrentTurnPlan.createWithActionObligation(
                            resolution,
                            phase,
                            nativeNames,
                            names,
                            List.of(),
                            CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                            CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                            CurrentTurnPlan.derivedVerifierProfile(
                                    resolution.contract()),
                            ActionObligationPolicy.derive(
                                    resolution,
                                    phase),
                            state.ctx == null ? null : state.ctx.cfg());
            List<ChatMessage> out =
                    new ArrayList<>(state.messages);
            ProviderPromptSurfaceReconciler.reconcile(
                    out,
                    plan,
                    selected);
            return List.copyOf(out);
        }
        List<ChatMessage> out = new ArrayList<>();
        out.add(ChatMessage.system("""
                You are Talos, a local-first workspace assistant.
                This is a bounded static-repair continuation. Use the available file-write tool to repair the exact remaining target paths.
                Do not answer in prose instead of calling the required tool. Do not claim completion until tool-backed changes have executed.
                """));
        lastStaticVerificationRepairContext(state.messages)
                .map(message -> enrichStaticRepairContextForReprompt(message, state))
                .ifPresent(out::add);
        out.add(ChatMessage.system(
                "[Static repair progress] Continue the bounded repair. Remaining full-file "
                        + "replacement targets: " + String.join(", ", remainingRepairTargets)
                        + ". Use talos.write_file with complete corrected file content for each remaining target. "
                        + "Do not claim completion until static verification passes."));
        String currentTask = userTask == null || userTask.isBlank()
                ? "Continue the bounded static repair."
                : userTask.strip();
        // T1011: workspace bytes are data, not policy. The readbacks travel
        // inside the user-role instruction as structured JSON, never in a
        // system-role message.
        String instruction = staticRepairUserInstruction(remainingRepairTargets, currentTask);
        String readbacks = StaticRepairReadbackContext.render(state, remainingRepairTargets)
                .orElse("");
        out.add(ChatMessage.user(readbacks.isBlank()
                ? instruction
                : instruction + "\n\n" + readbacks));
        return out;
    }

    private static String staticRepairUserInstruction(List<String> remainingRepairTargets, String currentTask) {
        String targets = remainingRepairTargets == null || remainingRepairTargets.isEmpty()
                ? "(unknown)"
                : String.join(", ", remainingRepairTargets);
        return "Repair exactly the remaining static-web target path(s): " + targets + ".\n"
                + "Call talos.write_file with complete corrected file content for those path(s) only.\n"
                + "Do not write any other file in this continuation.\n\n"
                + "Original user request:\n"
                + (currentTask == null ? "" : currentTask.strip());
    }

    static List<ToolSpec> currentNativeToolSpecs(LoopState state) {
        if (state == null || state.ctx == null) return List.of();
        List<ToolSpec> available;
        if (state.ctx.hasNativeToolSpecOverride()) {
            available = state.ctx.nativeToolSpecs();
        } else if (state.ctx.llm() != null) {
            available = state.ctx.llm().getToolSpecs();
        } else {
            available = List.of();
        }
        ProviderResponseCapture.Receipt receipt =
                state.currentResponseReceipt;
        if (receipt == null || !receipt.providerToolCallEligible()) {
            return List.of();
        }
        ProviderResponseCapture.ToolAuthorityContract authority =
                receipt.toolAuthorityContract();
        if (authority == null || !authority.bound()) {
            return List.of();
        }
        return selectToolsInAuthorityOrder(
                available,
                authority.selectedToolNames());
    }

    static ChatRequestControls controls(LoopState state) {
        return controls(state, "pending-action-obligation");
    }

    static ChatRequestControls controls(LoopState state, String debugTag) {
        boolean supportsRequiredToolChoice = state != null
                && state.ctx != null
                && state.ctx.llm() != null
                && state.ctx.llm().supportsRequiredToolChoice();
        return controls(state, debugTag, supportsRequiredToolChoice);
    }

    static ChatRequestControls controls(
            LoopState state,
            String debugTag,
            boolean supportsRequiredToolChoice
    ) {
        if (state == null
                || state.ctx == null
                || state.ctx.llm() == null
                || !state.hasPendingActionObligation()
                || !supportsRequiredToolChoice
                || !hasMutatingTool(state.ctx.nativeToolSpecs())) {
            return ChatRequestControls.defaults();
        }
        List<String> tags = new ArrayList<>(List.of("pending-action-obligation"));
        if (debugTag != null && !debugTag.isBlank() && !tags.contains(debugTag)) {
            tags.add(debugTag);
        }
        return new ChatRequestControls(
                ToolChoiceMode.REQUIRED,
                "",
                ResponseFormatMode.TEXT,
                "",
                tags);
    }

    private static Optional<ChatMessage> lastStaticVerificationRepairContext(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return Optional.empty();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message != null
                    && "system".equals(message.role())
                    && message.content() != null
                    && message.content().startsWith("[Static verification repair context]")) {
                return Optional.of(message);
            }
        }
        return Optional.empty();
    }

    private static ChatMessage enrichStaticRepairContextForReprompt(ChatMessage message, LoopState state) {
        if (message == null || message.content() == null) return message;
        String enriched = RepairPolicy.enrichSelectorFactsForRepairContext(
                message.content(),
                state == null ? null : state.workspace);
        if (enriched.equals(message.content())) return message;
        return ChatMessage.system(enriched);
    }

    private static List<ToolSpec> filterTools(List<ToolSpec> specs, List<String> allowedNames) {
        if (specs == null || specs.isEmpty() || allowedNames == null || allowedNames.isEmpty()) {
            return List.of();
        }
        return specs.stream()
                .filter(spec -> spec != null && allowedNames.contains(spec.name()))
                .toList();
    }

    private static List<ToolSpec> selectToolsInAuthorityOrder(
            List<ToolSpec> specs,
            List<String> selectedNames
    ) {
        if (specs == null
                || specs.isEmpty()
                || selectedNames == null
                || selectedNames.isEmpty()) {
            return List.of();
        }
        java.util.Map<String, ToolSpec> byName =
                new java.util.LinkedHashMap<>();
        for (ToolSpec spec : specs) {
            if (spec != null && spec.name() != null) {
                byName.putIfAbsent(spec.name(), spec);
            }
        }
        List<ToolSpec> selected = new ArrayList<>();
        for (String name : selectedNames) {
            ToolSpec spec = byName.get(name);
            if (spec != null) selected.add(spec);
        }
        return List.copyOf(selected);
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
