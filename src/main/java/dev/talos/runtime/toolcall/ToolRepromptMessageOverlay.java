package dev.talos.runtime.toolcall;

import dev.talos.runtime.repair.RepairInstruction;
import dev.talos.runtime.repair.RepairPolicy;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
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
        return apply(state, remainingRepairTargets, remainingExpectedTargets, userTask, false);
    }

    static ToolRepromptMessageOverlay apply(
            LoopState state,
            List<String> remainingRepairTargets,
            List<String> remainingExpectedTargets,
            String userTask,
            boolean staticRepairObligationActive
    ) {
        ToolRepromptMessageOverlay overlay = new ToolRepromptMessageOverlay(state);
        overlay.applyStaleEditRepair();
        overlay.applyEmptyEditRepair();
        // An active static-repair obligation REPLACES the request messages
        // entirely (ToolRepromptRequestBuilder.messages), so prompting a
        // recovery here would silently consume its one shot without the
        // model ever seeing the instruction. The target stays planned and
        // prompts once the static repair completes.
        if (!staticRepairObligationActive) {
            overlay.applyEditRecoveryFullRewrite();
        }
        overlay.applyStaticRepairProgress(remainingRepairTargets);
        overlay.applyExpectedTargetProgress(remainingExpectedTargets);
        overlay.applyCurrentTaskAnchor(userTask);
        return overlay;
    }

    /**
     * T1003: one bounded full-rewrite recovery per path after repeated
     * empty-argument edit_file failures on a small, completely read,
     * unredacted, non-protected text file. Marking the path prompted makes
     * the recovery one-shot: the failure policy stops normally if the
     * failure persists.
     *
     * <p>The complete safe source content is injected into this temporary
     * message because tool-result compaction may already have summarized
     * the same-turn read out of provider-visible context. The content is
     * re-derived through the planner's single source owner at prompt time,
     * so protected, redacted, ranged, truncated, or oversized bodies can
     * never be injected. If no safe source is derivable anymore the target
     * is refused instead of prompted, and the failure policy stops
     * truthfully on the next pass. The overlay close() removes the message
     * after the request, so the readback never persists in history.
     */
    private void applyEditRecoveryFullRewrite() {
        for (String path : state.editRecoveryFullRewriteTargets.stream().sorted().toList()) {
            if (state.editRecoveryPromptedPaths.contains(path)) continue;
            String sourceBody = EditRecoveryFullRewritePlanner.recoverySourceBody(state, path);
            if (sourceBody == null) {
                state.editRecoveryFullRewriteTargets.remove(path);
                LocalTurnTraceCapture.recordRepair(
                        "REFUSED",
                        "edit-recovery-full-rewrite target=" + path
                                + " reason=safe-source-no-longer-derivable");
                continue;
            }
            state.editRecoveryPromptedPaths.add(path);
            LocalTurnTraceCapture.recordRepair(
                    "PROMPTED",
                    "edit-recovery-full-rewrite target=" + path);
            addSystem(
                    "[Edit recovery] talos.edit_file repeatedly failed for `" + path
                            + "` with empty old_string after the file was read. Do not call "
                            + "talos.edit_file for this file again this turn. Call talos.write_file "
                            + "once for `" + path + "` with the COMPLETE corrected file content, "
                            + "keeping all existing content plus the requested change. The complete "
                            + "current content of `" + path + "` from the same-turn read is between "
                            + "the BEGIN FILE and END FILE markers below. Base the corrected content "
                            + "on it exactly. Do not include the markers and do not add line-number "
                            + "prefixes.\n"
                            + EditRecoveryFullRewritePlanner.READBACK_BEGIN_MARKER + " " + path + ">>>\n"
                            + sourceBody
                            + EditRecoveryFullRewritePlanner.READBACK_END_MARKER + " " + path + ">>>\n"
                            + "The write requires approval and shows a full diff. If you cannot "
                            + "produce the complete corrected content, say no edit was applied.",
                    "[Edit recovery]");
            // One recovery target per generation: prompting several at once
            // consumes every one-shot mark while the model can only be
            // expected to service the narrowed REQUIRED surface once, and
            // close() would delete the unserviced targets' safe source. The
            // remaining targets stay planned and prompt on later passes.
            break;
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
