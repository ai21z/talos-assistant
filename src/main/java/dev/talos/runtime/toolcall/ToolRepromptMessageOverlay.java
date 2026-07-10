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
    private final List<RestoredToolResult> restoredToolResults = new ArrayList<>();
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
     * T1003/T1011: one bounded full-rewrite recovery per path after
     * repeated empty-argument edit_file failures on a small, completely
     * read, unredacted, non-protected text file. Marking the path prompted
     * makes the recovery one-shot: the failure policy stops normally if
     * the failure persists.
     *
     * <p>Role separation: the temporary system message carries ONLY the
     * recovery instruction. The file content stays in the real
     * talos.read_file tool result, the lower-trust data carrier the model
     * already grounds on. That carrier is the line-numbered LF-normalized
     * read rendering, not raw disk bytes; the write pipeline's format
     * preservation (WriteFormatPolicy, T1012) restores the target's real
     * byte format before approval when the model echoes it back. When
     * tool-result compaction has summarized that result,
     * its original content is temporarily restored into the same message
     * (same id, same position) for this request and re-compacted on
     * close(), so no readback persists in post-turn history and workspace
     * text never gains system-role authority. Eligibility is re-checked
     * through the planner's single source owner at prompt time; if no safe
     * source is derivable, or the qualifying read message cannot be
     * located, the target is refused and the failure policy stops
     * truthfully on the next pass.
     */
    private void applyEditRecoveryFullRewrite() {
        for (String path : state.editRecoveryFullRewriteTargets.stream().sorted().toList()) {
            if (state.editRecoveryPromptedPaths.contains(path)) continue;
            String sourceBody = EditRecoveryFullRewritePlanner.recoverySourceBody(state, path);
            String rawBody = AppendLinePreApprovalGuard.completeReadRawBodyForPath(state, path);
            Integer readIndex = sourceBody == null || rawBody == null
                    ? null
                    : locateReadToolResult(rawBody);
            if (sourceBody == null || rawBody == null || readIndex == null) {
                state.editRecoveryFullRewriteTargets.remove(path);
                LocalTurnTraceCapture.recordRepair(
                        "REFUSED",
                        "edit-recovery-full-rewrite target=" + path
                                + " reason=safe-source-no-longer-derivable");
                continue;
            }
            restoreCompactedReadIfNeeded(readIndex, rawBody);
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
                            + "current content of `" + path + "` is in the talos.read_file result "
                            + "for `" + path + "` earlier in this conversation. Base the corrected "
                            + "content on it exactly. Line-number prefixes in that result are "
                            + "display-only; do not copy them into the file. The write requires "
                            + "approval and shows a full diff. If you cannot produce the complete "
                            + "corrected content, say no edit was applied.",
                    "[Edit recovery]");
            // One recovery target per generation: prompting several at once
            // consumes every one-shot mark while the model can only be
            // expected to service the narrowed REQUIRED surface once. The
            // remaining targets stay planned and prompt on later passes.
            break;
        }
    }

    /**
     * Finds the tool-result message that carries the qualifying complete
     * read. Production tool results wrap the raw output in formatter
     * chrome, so the match is containment of the exact multi-line body
     * (already validated as the single qualifying evidence), latest first.
     */
    private Integer locateReadToolResult(String rawBody) {
        for (int i = state.messages.size() - 1; i >= 0; i--) {
            ChatMessage message = state.messages.get(i);
            if (message == null || !"tool".equals(message.role())) continue;
            String content = message.content();
            if (content != null && content.contains(rawBody)) return i;
            String original = state.compactedToolResultOriginals.get(i);
            if (original != null && original.contains(rawBody)) return i;
        }
        return null;
    }

    private void restoreCompactedReadIfNeeded(int index, String rawBody) {
        ChatMessage current = state.messages.get(index);
        if (current.content() != null && current.content().contains(rawBody)) return;
        String original = state.compactedToolResultOriginals.get(index);
        if (original == null) return;
        restoredToolResults.add(new RestoredToolResult(index, current.content()));
        state.messages.set(index, ChatMessage.toolResult(current.toolCallId(), original));
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
        // T1011: re-compact temporarily restored read tool results so the
        // readback does not persist in post-turn history. Restored indices
        // precede the overlay's tail messages, so the removals above never
        // shift them.
        for (RestoredToolResult restored : restoredToolResults) {
            if (restored.index() < 0 || restored.index() >= state.messages.size()) continue;
            ChatMessage message = state.messages.get(restored.index());
            if ("tool".equals(message.role())) {
                state.messages.set(restored.index(),
                        ChatMessage.toolResult(message.toolCallId(), restored.compactedContent()));
            }
        }
    }

    private record TemporaryMessage(int index, String cleanupPrefix) {}

    private record RestoredToolResult(int index, String compactedContent) {}
}
