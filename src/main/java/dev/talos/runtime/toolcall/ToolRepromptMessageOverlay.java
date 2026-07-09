package dev.talos.runtime.toolcall;

import dev.talos.runtime.repair.RepairInstruction;
import dev.talos.runtime.repair.RepairPolicy;
import dev.talos.spi.types.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class ToolRepromptMessageOverlay implements AutoCloseable {
    private final LoopState state;
    private final List<TemporaryMessage> temporaryMessages = new ArrayList<>();
    private boolean closed;

    private ToolRepromptMessageOverlay(LoopState state) {
        this.state = state;
    }

    static ToolRepromptMessageOverlay apply(
            LoopState state,
            List<String> remainingRepairTargets,
            List<String> remainingExpectedTargets,
            String userTask
    ) {
        ToolRepromptMessageOverlay overlay = new ToolRepromptMessageOverlay(state);
        overlay.applyStaleEditRepair();
        overlay.applyEmptyEditRepair();
        overlay.applyEditRecoveryFullRewrite();
        overlay.applyStaticRepairProgress(remainingRepairTargets);
        overlay.applyExpectedTargetProgress(remainingExpectedTargets);
        overlay.applyCurrentTaskAnchor(userTask);
        return overlay;
    }

    /**
     * T1003: one bounded full-rewrite recovery per path after repeated
     * empty-argument edit_file failures on a small, completely read,
     * unredacted text file. Marking the path prompted makes the recovery
     * one-shot: the failure policy stops normally if the failure persists.
     */
    private void applyEditRecoveryFullRewrite() {
        for (String path : state.editRecoveryFullRewriteTargets.stream().sorted().toList()) {
            if (!state.editRecoveryPromptedPaths.add(path)) continue;
            addSystem(
                    "[Edit recovery] talos.edit_file repeatedly failed for `" + path
                            + "` with empty old_string after the file was read. Do not call "
                            + "talos.edit_file for this file again this turn. Call talos.write_file "
                            + "once for `" + path + "` with the COMPLETE corrected file content "
                            + "based on the same-turn read (without line-number prefixes), keeping "
                            + "all existing content plus the requested change. The write requires "
                            + "approval and shows a full diff. If you cannot produce the complete "
                            + "corrected content, say no edit was applied.",
                    "[Edit recovery]");
        }
    }

    private void applyStaleEditRepair() {
        Optional<RepairInstruction> staleRepair = RepairPolicy.nextStaleEditRepair(state);
        if (staleRepair.isEmpty()) return;
        RepairInstruction repair = staleRepair.get();
        addSystem(repair.instruction(), "[Stale edit repair required]");
        state.staleEditRepairPromptedPaths.add(repair.path());
    }

    private void applyEmptyEditRepair() {
        Optional<RepairInstruction> repair = RepairPolicy.nextEmptyEditRepair(state);
        if (repair.isEmpty()) return;
        RepairInstruction instruction = repair.get();
        addSystem(instruction.instruction(), "[Edit repair required]");
        state.emptyEditRepairPromptedPaths.add(instruction.path());
    }

    private void applyStaticRepairProgress(List<String> remainingRepairTargets) {
        if (remainingRepairTargets == null || remainingRepairTargets.isEmpty()) return;
        addSystem(
                "[Static repair progress] Continue the bounded repair. Remaining full-file "
                        + "replacement targets: " + String.join(", ", remainingRepairTargets)
                        + ". Use talos.write_file with complete corrected file content for each remaining target. "
                        + "Do not claim completion until static verification passes.",
                "[Static repair progress]");
    }

    private void applyExpectedTargetProgress(List<String> remainingExpectedTargets) {
        if (remainingExpectedTargets == null || remainingExpectedTargets.isEmpty()) return;
        addSystem(
                "[Expected target progress] Continue this mutation task. Remaining expected target paths "
                        + "not successfully mutated in this turn: " + String.join(", ", remainingExpectedTargets)
                        + ". Use the visible write/edit tools to mutate these exact paths before answering. "
                        + "Similar filenames are not substitutes. For small static web files, prefer "
                        + "talos.write_file with complete file content. Do not claim completion until "
                        + "static verification passes.",
                "[Expected target progress]");
    }

    private void applyCurrentTaskAnchor(String userTask) {
        if (userTask == null || userTask.isBlank()) return;
        String pinned = userTask.length() <= 500 ? userTask : userTask.substring(0, 500) + "…";
        addSystem("[Current task - stay focused on this] " + pinned, "[Current task");
    }

    private void addSystem(String content, String cleanupPrefix) {
        state.messages.add(ChatMessage.system(content));
        temporaryMessages.add(new TemporaryMessage(state.messages.size() - 1, cleanupPrefix));
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (int i = temporaryMessages.size() - 1; i >= 0; i--) {
            TemporaryMessage temporary = temporaryMessages.get(i);
            if (temporary.index() < 0 || temporary.index() >= state.messages.size()) continue;
            ChatMessage message = state.messages.get(temporary.index());
            if ("system".equals(message.role())
                    && message.content() != null
                    && message.content().startsWith(temporary.cleanupPrefix())) {
                state.messages.remove(temporary.index());
            }
        }
    }

    private record TemporaryMessage(int index, String cleanupPrefix) {}
}
