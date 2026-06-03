package dev.talos.runtime.toolcall;

import dev.talos.runtime.repair.RepairPolicy;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
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
            return narrowed.isEmpty() ? base : narrowed;
        }
        if (expectedTargetProgress) {
            List<ToolSpec> narrowed = filterTools(base, List.of("talos.write_file", "talos.edit_file"));
            return narrowed.isEmpty() ? base : narrowed;
        }
        return base;
    }

    static List<ChatMessage> messages(
            LoopState state,
            boolean staticRepairObligationActive,
            List<String> remainingRepairTargets,
            String userTask
    ) {
        if (!staticRepairObligationActive) {
            return state == null ? List.of() : state.messages;
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
        StaticRepairReadbackContext.render(state, remainingRepairTargets)
                .ifPresent(readbacks -> out.add(ChatMessage.system(readbacks)));
        String currentTask = userTask == null || userTask.isBlank()
                ? "Continue the bounded static repair."
                : userTask.strip();
        out.add(ChatMessage.user(staticRepairUserInstruction(remainingRepairTargets, currentTask)));
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
        if (state.ctx.nativeToolSpecs() != null) {
            return state.ctx.nativeToolSpecs();
        }
        if (state.ctx.llm() != null) {
            return state.ctx.llm().getToolSpecs();
        }
        return List.of();
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
